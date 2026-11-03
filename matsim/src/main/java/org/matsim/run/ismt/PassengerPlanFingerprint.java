package org.matsim.run.ismt;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.replanning.inheritance.PlanInheritanceModule;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.utils.objectattributes.attributable.Attributable;
import org.matsim.vehicles.PersonVehicleTypes;
import org.matsim.vehicles.PersonVehicles;

import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Read-only, full-memory passenger-plan fingerprint. Exact floating values, plan
 * order, selected-plan identity and all supported attributes enter the digest.
 * No Population/XML writer, Id creation, clone, setter or random stream is used.
 * Register this singleton only as an iteration-end listener, after scoring.
 */
@Singleton
public final class PassengerPlanFingerprint implements IterationEndsListener {
    public static final String SCHEMA_VERSION = "ismt-passenger-plan-fingerprint-v1";
    public static final String FILE_NAME = "passenger_plan_fingerprint.csv";
    private static final String HEADER = "schema_version,experiment_id,block_id,arm,iteration,passenger_count,plan_sha256\n";
    private final ScientificRunContext context;
    private final Scenario scenario;
    private final FreightOrderRegistry registry;
    private final Path outputFile;
    private final List<Fingerprint> completed = new ArrayList<>();

    @Inject
    public PassengerPlanFingerprint(ExperimentSpec spec, Scenario scenario, FreightOrderRegistry registry,
            OutputDirectoryHierarchy io) {
        this(spec, scenario, registry, Path.of(io.getOutputPath()).resolve(FILE_NAME));
    }

    public PassengerPlanFingerprint(ExperimentSpec spec, Scenario scenario, FreightOrderRegistry registry, Path outputFile) {
        this(ScientificRunContext.legacy(spec), scenario, registry, outputFile);
    }

    public PassengerPlanFingerprint(ScientificRunContext context, Scenario scenario,
            FreightOrderRegistry registry, Path outputFile) {
        this.context = Objects.requireNonNull(context);
        this.scenario = Objects.requireNonNull(scenario);
        this.registry = Objects.requireNonNull(registry);
        this.outputFile = Objects.requireNonNull(outputFile).toAbsolutePath().normalize();
        if (Files.exists(this.outputFile)) throw new IllegalStateException("Refusing to overwrite passenger-plan fingerprint: " + outputFile);
    }

    @Override public double priority() { return 50.0; }

    @Override public void notifyIterationEnds(IterationEndsEvent event) {
        int iteration = event.getIteration();
        if (iteration != context.firstIteration() + completed.size() || iteration > context.finalIteration())
            throw new IllegalStateException("Missing, repeated, or out-of-order passenger-plan fingerprint iteration: " + iteration);
        Digest current = fingerprintNow();
        completed.add(new Fingerprint(iteration, current.passengerCount(), current.sha256(), SCHEMA_VERSION));
        writeCompleted();
    }

    public List<Fingerprint> completedFingerprints() { return List.copyOf(completed); }

    /** A fresh read-only measurement, without writing files or changing observer/agent state. */
    public Digest fingerprintNow() {
        List<? extends Person> persons = scenario.getPopulation().getPersons().values().stream()
                .filter(person -> !registry.isFreightPerson(person.getId()))
                .sorted(Comparator.comparing(person -> person.getId().toString())).toList();
        MessageDigest digest = newDigest();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new DigestOutputStream(OutputStream.nullOutputStream(), digest)))) {
            Encoder encoder = new Encoder(out, new LegacyReadAccess(), new IdentityHashMap<>());
            encoder.string(SCHEMA_VERSION);
            out.writeInt(persons.size());
            for (Person person : persons) encoder.person(person);
            out.flush();
            return new Digest(persons.size(), HexFormat.of().formatHex(digest.digest()));
        } catch (IOException error) {
            throw new UncheckedIOException("Could not stream passenger-plan fingerprint", error);
        }
    }

    private void writeCompleted() {
        StringBuilder csv = new StringBuilder(context.fingerprintHeader(HEADER));
        for (Fingerprint value : completed) csv.append(SCHEMA_VERSION).append(',').append(context.experimentId()).append(',')
                .append(context.blockId()).append(',').append(context.arm()).append(',').append(value.iteration()).append(',')
                .append(value.passengerCount()).append(',').append(value.sha256()).append(context.fingerprintRowSuffix()).append('\n');
        try {
            Files.createDirectories(outputFile.getParent());
            Path staging = Files.createTempFile(outputFile.getParent(), ".passenger-plan-fingerprint-", ".tmp");
            try {
                Files.writeString(staging, csv, StandardCharsets.UTF_8);
                Files.move(staging, outputFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staging);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Could not commit passenger-plan fingerprints", error);
        }
    }

    public record Digest(int passengerCount, String sha256) { }
    public record Fingerprint(int iteration, int passengerCount, String sha256, String schemaVersion) { }

    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    static final int STRING_BUFFER_BYTES = 8192;

    /**
     * Byte-identical to writeInt(length) followed by DataOutputStream.writeChars,
     * including unpaired surrogates. Batch raw UTF-16 code units, not characters
     * interpreted by a Charset encoder, and never allocate a string-sized array.
     * Package-private for comparison against the JDK reference encoding in tests.
     */
    static void writeString(DataOutputStream out, String text, byte[] buffer) throws IOException {
        if (buffer.length < 2) throw new IllegalArgumentException("UTF-16 buffer needs at least two bytes");
        out.writeInt(text == null ? -1 : text.length());
        if (text == null) return;
        for (int start = 0; start < text.length();) {
            int count = Math.min(buffer.length / 2, text.length() - start);
            for (int index = 0; index < count; index++) {
                char codeUnit = text.charAt(start + index);
                buffer[2 * index] = (byte) (codeUnit >>> 8);
                buffer[2 * index + 1] = (byte) codeUnit;
            }
            out.write(buffer, 0, 2 * count);
            start += count;
        }
    }

    /** Explicit versioned binary framing; strings are length-prefixed UTF-16 code units. */
    private static final class Encoder {
        private final DataOutputStream out;
        private final LegacyReadAccess legacy;
        private final IdentityHashMap<Object, Boolean> activeValues;
        private final byte[] stringBuffer = new byte[STRING_BUFFER_BYTES];
        Encoder(DataOutputStream out, LegacyReadAccess legacy, IdentityHashMap<Object, Boolean> activeValues) {
            this.out = out; this.legacy = legacy; this.activeValues = activeValues;
        }

        void person(Person person) throws IOException {
            string("person"); string(person.getId().toString());
            attributes(person); value(legacy.customAttributes(person));
            List<? extends Plan> plans = person.getPlans();
            Plan selected = person.getSelectedPlan();
            int selectedIndex = -1;
            for (int index = 0; index < plans.size(); index++) if (plans.get(index) == selected) selectedIndex = index;
            if (selected != null && selectedIndex < 0) throw new IllegalStateException("Selected plan is absent from person memory: " + person.getId());
            out.writeInt(selectedIndex); out.writeInt(plans.size());
            for (Plan plan : plans) plan(plan);
        }

        void plan(Plan plan) throws IOException {
            string("plan"); string(legacy.effectivePlanId(plan)); string(plan.getType()); value(plan.getScore());
            string(plan.getPerson() == null ? null : plan.getPerson().getId().toString());
            attributes(plan); value(legacy.customAttributes(plan));
            List<PlanElement> elements = plan.getPlanElements();
            out.writeInt(elements.size());
            for (PlanElement element : elements) {
                if (element instanceof Activity activity) {
                    string("activity"); string(activity.getType()); id(activity.getLinkId()); id(activity.getFacilityId());
                    coord(activity.getCoord()); time(activity.getStartTime()); time(activity.getEndTime()); time(activity.getMaximumDuration());
                    attributes(activity);
                } else if (element instanceof Leg leg) {
                    string("leg"); string(leg.getMode()); string(leg.getRoutingMode());
                    time(leg.getDepartureTime()); time(leg.getTravelTime()); attributes(leg);
                    route(leg.getRoute(), new IdentityHashMap<>());
                } else throw new IllegalArgumentException("Unreviewed plan-element implementation: " + element.getClass().getName());
            }
        }

        void route(Route route, IdentityHashMap<Route, Boolean> chain) throws IOException {
            if (route == null) { string(null); return; }
            if (chain.put(route, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic transit route chain");
            try {
                // Native PT description and egress getters recurse internally; guard before invoking either.
                if (route instanceof TransitPassengerRoute transitRoot) {
                    IdentityHashMap<TransitPassengerRoute, Boolean> seen = new IdentityHashMap<>();
                    for (TransitPassengerRoute part = transitRoot; part != null; part = part.getChainedRoute()) {
                        if (seen.put(part, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic transit route chain");
                    }
                }
                string("route"); string(route.getRouteType()); id(route.getStartLinkId()); id(route.getEndLinkId());
                floating(route.getDistance()); time(route.getTravelTime());
                // This declared serialization is not sufficient for precise PT times; typed fields follow below.
                string(route.getRouteDescription());
                out.writeBoolean(route instanceof Attributable);
                if (route instanceof Attributable attributable) attributes(attributable);
                out.writeBoolean(route instanceof NetworkRoute);
                if (route instanceof NetworkRoute network) {
                    id(network.getVehicleId()); floating(network.getTravelCost());
                    out.writeInt(network.getLinkIds().size());
                    for (Id<?> link : network.getLinkIds()) id(link);
                }
                out.writeBoolean(route instanceof PassengerRoute);
                if (route instanceof PassengerRoute passenger) time(passenger.getBoardingTime());
                out.writeBoolean(route instanceof TransitPassengerRoute);
                if (route instanceof TransitPassengerRoute transit) {
                    id(transit.getAccessStopId()); id(transit.getEgressStopId()); id(transit.getLineId()); id(transit.getRouteId());
                    route(transit.getChainedRoute(), chain);
                }
            } finally { chain.remove(route); }
        }

        void attributes(Attributable object) throws IOException { value(object.getAttributes().getAsMap()); }
        void id(Id<?> id) throws IOException { string(id == null ? null : id.toString()); }
        void floating(double number) throws IOException { string(Double.toHexString(number)); }
        void time(OptionalTime time) throws IOException {
            out.writeBoolean(time.isDefined());
            if (time.isDefined()) floating(time.seconds());
        }
        void coord(Coord coord) throws IOException {
            out.writeBoolean(coord != null);
            if (coord != null) {
                floating(coord.getX()); floating(coord.getY()); out.writeBoolean(coord.hasZ());
                if (coord.hasZ()) floating(coord.getZ());
            }
        }
        void string(String text) throws IOException {
            writeString(out, text, stringBuffer);
        }

        void value(Object value) throws IOException {
            if (value == null) { string("null"); return; }
            if (value instanceof String text) { string("string"); string(text); return; }
            if (value instanceof Boolean flag) { string("boolean"); out.writeBoolean(flag); return; }
            if (value instanceof Character character) { string("char"); out.writeChar(character); return; }
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof BigInteger) {
                string(value.getClass().getName()); string(value.toString()); return;
            }
            if (value instanceof Double number) { string("double"); floating(number); return; }
            if (value instanceof Float number) { string("float"); string(Float.toHexString(number)); return; }
            if (value instanceof BigDecimal number) { string("bigdecimal"); string(number.unscaledValue().toString()); out.writeInt(number.scale()); return; }
            if (value instanceof Enum<?> enumeration) { string("enum"); string(enumeration.getDeclaringClass().getName()); string(enumeration.name()); return; }
            if (value instanceof Id<?> id) { string("id"); string(id.classType().getName()); id(id); return; }
            if (value instanceof Coord coord) { string("coord"); coord(coord); return; }
            if (value instanceof OptionalTime time) { string("optional-time"); time(time); return; }
            if (activeValues.put(value, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic scientific attribute value");
            try {
                // These native getters return the existing maps without populating them. Distinct
                // type tags preserve the container semantics; the map encoder sorts modes and
                // retains each Id's declared type and exact value without creating any Id.
                // PersonVehicles is extensible, so an unreviewed subclass must still fail closed.
                if (value.getClass() == PersonVehicles.class) {
                    string("native-person-vehicles-v1"); value(((PersonVehicles) value).getModeVehicles());
                } else if (value.getClass() == PersonVehicleTypes.class) {
                    string("native-person-vehicle-types-v1"); value(((PersonVehicleTypes) value).getModeVehicleTypes());
                } else if (value instanceof Map<?, ?> map) {
                    string("map"); TreeMap<String, Object> sorted = new TreeMap<>();
                    for (var entry : map.entrySet()) {
                        if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Attribute maps require String keys");
                        sorted.put(key, entry.getValue());
                    }
                    out.writeInt(sorted.size());
                    for (var entry : sorted.entrySet()) { string(entry.getKey()); value(entry.getValue()); }
                } else if (value instanceof List<?> list) {
                    string("list"); out.writeInt(list.size()); for (Object element : list) value(element);
                } else if (value instanceof Set<?> set) {
                    string("set"); List<byte[]> encoded = new ArrayList<>();
                    for (Object element : set) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        try (DataOutputStream member = new DataOutputStream(bytes)) { new Encoder(member, legacy, activeValues).value(element); }
                        encoded.add(bytes.toByteArray());
                    }
                    encoded.sort(Arrays::compareUnsigned); out.writeInt(encoded.size());
                    for (byte[] element : encoded) { out.writeInt(element.length); out.write(element); }
                } else if (value.getClass().isArray()) {
                    string("array"); string(value.getClass().getComponentType().getName());
                    int length = Array.getLength(value); out.writeInt(length);
                    for (int index = 0; index < length; index++) value(Array.get(value, index));
                } else throw new IllegalArgumentException("No deterministic scientific attribute serializer for " + value.getClass().getName());
            } finally { activeValues.remove(value); }
        }
    }

    /**
     * These three native fields are read, never written. Calling their public lazy
     * getters instead would mutate a person/plan or create an Id during observation.
     * An implementation/layout change requires a reviewed adapter, not toString().
     */
    private static final class LegacyReadAccess {
        private final Map<String, Field> fields = new HashMap<>();
        Map<?, ?> customAttributes(Object object) {
            String name = object.getClass().getName();
            if (!name.equals("org.matsim.core.population.PersonImpl") && !name.equals("org.matsim.core.population.PlanImpl"))
                throw new IllegalArgumentException("No read-only legacy-attribute adapter for " + name);
            Object delegate = read(object, "customizableDelegate");
            if (delegate == null) return Map.of();
            if (!delegate.getClass().getName().equals("org.matsim.core.scenario.CustomizableImpl"))
                throw new IllegalArgumentException("Unreviewed legacy attribute delegate: " + delegate.getClass().getName());
            Object map = read(delegate, "custom_attributes");
            if (map == null) return Map.of();
            if (!(map instanceof Map<?, ?> values)) throw new IllegalStateException("Unexpected legacy attribute container");
            return values;
        }

        String effectivePlanId(Plan plan) {
            if (!plan.getClass().getName().equals("org.matsim.core.population.PlanImpl"))
                throw new IllegalArgumentException("No read-only plan-ID adapter for " + plan.getClass().getName());
            Object id = read(plan, "id");
            if (id == null) id = plan.getAttributes().getAttribute(PlanInheritanceModule.PLAN_ID);
            if (id == null) return null;
            if (id instanceof String text) return text;
            if (id instanceof Id<?> typedId) return typedId.toString();
            throw new IllegalArgumentException("Plan inheritance ID must be a String or Id");
        }

        private Object read(Object object, String fieldName) {
            String key = object.getClass().getName() + "#" + fieldName;
            try {
                Field field = fields.get(key);
                if (field == null) {
                    field = object.getClass().getDeclaredField(fieldName);
                    if (!field.trySetAccessible()) throw new IllegalStateException("Read-only fingerprint access is denied: " + key);
                    fields.put(key, field);
                }
                return field.get(object);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Native plan/person layout changed; fingerprint adapter needs review: " + key, error);
            }
        }
    }
}

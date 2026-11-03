package org.matsim.run.ismt;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.cotransport.FreightOrderRegistry;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.qsim.pt.PersonEntersPtVehicleEvent;
import org.matsim.core.mobsim.qsim.pt.PersonLeavesPtVehicleEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Versioned passenger-event multiset fingerprint, ordered between distinct time buckets.
 * It observes an explicit native event whitelist, not complete road-vehicle trajectories,
 * scores, plans, penalties, diagnostics, or freight execution. See the schema documentation.
 * Register this singleton as both an event handler and an iteration-end listener.
 */
@Singleton
public final class PassengerScientificFingerprint implements BasicEventHandler, IterationEndsListener {
	public static final String SCHEMA_VERSION = "ismt-passenger-event-fingerprint-v1";
	public static final String FILE_NAME = "passenger_event_fingerprint.csv";
	private static final String HEADER = "experiment_id,block_id,arm,iteration,event_count,event_sha256,schema_version\n";
	private static final Set<Class<? extends Event>> SCIENTIFIC_EVENTS = Set.of(
			PersonInitializedEvent.class, ActivityStartEvent.class, ActivityEndEvent.class,
			PersonDepartureEvent.class, PersonArrivalEvent.class, TeleportationArrivalEvent.class,
			AgentWaitingForPtEvent.class, PersonEntersVehicleEvent.class, PersonLeavesVehicleEvent.class,
			PersonEntersPtVehicleEvent.class, PersonLeavesPtVehicleEvent.class,
			PersonContinuesInVehicleEvent.class, BoardingDeniedEvent.class,
			PersonStuckEvent.class, PersonStuckAndContinueEvent.class);
	private static final Set<String> SCIENTIFIC_TYPES = Set.of(
			PersonInitializedEvent.EVENT_TYPE, ActivityStartEvent.EVENT_TYPE, ActivityEndEvent.EVENT_TYPE,
			PersonDepartureEvent.EVENT_TYPE, PersonArrivalEvent.EVENT_TYPE, TeleportationArrivalEvent.EVENT_TYPE,
			AgentWaitingForPtEvent.EVENT_TYPE, PersonEntersVehicleEvent.EVENT_TYPE, PersonLeavesVehicleEvent.EVENT_TYPE,
			PersonEntersPtVehicleEvent.EVENT_TYPE, PersonLeavesPtVehicleEvent.EVENT_TYPE,
			PersonContinuesInVehicleEvent.EVENT_TYPE, BoardingDeniedEvent.EVENT_TYPE,
			PersonStuckEvent.EVENT_TYPE, PersonStuckAndContinueEvent.EVENT_TYPE);

	private final ScientificRunContext context;
	private final Path outputFile;
	private final Set<Id<Person>> passengerIds;
	private final Map<Integer, Fingerprint> completed = new LinkedHashMap<>();
	private final List<String> sameTimeEvents = new ArrayList<>();
	private MessageDigest digest;
	private double currentTime = Double.NaN;
	private long eventCount;
	private int iteration = -1;
	private boolean acceptingEvents;

	@Inject
	public PassengerScientificFingerprint(ExperimentSpec spec, FreightOrderRegistry registry,
			Scenario scenario, OutputDirectoryHierarchy controllerIO) {
		this(spec, registry, scenario, Path.of(controllerIO.getOutputPath()).resolve(FILE_NAME));
	}

	/** Explicit output path constructor also permits deterministic fixtures without a controller. */
	public PassengerScientificFingerprint(ExperimentSpec spec, FreightOrderRegistry registry,
			Scenario scenario, Path outputFile) {
		this(ScientificRunContext.legacy(spec), registry, scenario, outputFile);
	}

	public PassengerScientificFingerprint(ScientificRunContext context, FreightOrderRegistry registry,
			Scenario scenario, Path outputFile) {
		this.context = Objects.requireNonNull(context);
		this.outputFile = Objects.requireNonNull(outputFile).toAbsolutePath().normalize();
		if (Files.exists(this.outputFile)) {
			throw new IllegalStateException("Refusing to overwrite an existing passenger fingerprint: " + this.outputFile);
		}
		Set<Id<Person>> ids = new HashSet<>();
		for (Id<Person> id : scenario.getPopulation().getPersons().keySet()) {
			if (!registry.isFreightPerson(id)) ids.add(id);
		}
		passengerIds = Set.copyOf(ids);
	}

	@Override
	public void handleEvent(Event event) {
		// Filter before inspecting time: score/diagnostic events need not be in QSim time order.
		if (!acceptingEvents) return;
		if (!(event instanceof HasPersonId person) || !passengerIds.contains(person.getPersonId())) return;
		if (!SCIENTIFIC_EVENTS.contains(event.getClass())) {
			if (SCIENTIFIC_TYPES.contains(event.getEventType())) {
				throw new IllegalArgumentException("Unreviewed implementation of scientific passenger event: " + event.getClass().getName());
			}
			return;
		}
		double time = event.getTime();
		if (!Double.isFinite(time) || time < 0) {
			throw new IllegalArgumentException("Invalid passenger scientific-event time: " + time);
		}
		time = time == 0 ? 0 : time; // Canonicalize signed zero, but do not round other times.
		if (!Double.isNaN(currentTime) && time < currentTime) {
			throw new IllegalStateException("Passenger scientific-event time moved backwards: " + time + " < " + currentTime);
		}
		String canonical = canonical(event);
		if (Double.isNaN(currentTime) || time != currentTime) {
			flushTimeBucket();
			currentTime = time;
		}
		sameTimeEvents.add(canonical);
		eventCount++;
	}

	@Override
	public void reset(int iteration) {
		if (iteration < context.firstIteration() || iteration > context.finalIteration()) {
			throw new IllegalArgumentException("Fingerprint iteration is outside the experiment protocol: " + iteration);
		}
		if (completed.containsKey(iteration)) throw new IllegalStateException("Fingerprint iteration already completed: " + iteration);
		int expected = context.firstIteration() + completed.size();
		if (iteration != expected) throw new IllegalStateException("Expected fingerprint iteration " + expected + ", got " + iteration);
		if (acceptingEvents && eventCount != 0) throw new IllegalStateException("Cannot discard an unfinished passenger fingerprint");
		this.iteration = iteration;
		digest = newDigest();
		updateRecord(SCHEMA_VERSION);
		sameTimeEvents.clear();
		currentTime = Double.NaN;
		eventCount = 0;
		acceptingEvents = true;
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		finishIteration(event.getIteration());
		writeCompletedIterations();
	}

	/** Finish before ordinary iteration-end artifact writers inspect the fingerprint. */
	@Override public double priority() { return 100; }

	public List<Fingerprint> completedFingerprints() { return List.copyOf(completed.values()); }
	public int passengerCount() { return passengerIds.size(); }

	Fingerprint finishIteration(int requestedIteration) {
		if (!acceptingEvents || requestedIteration != iteration) {
			throw new IllegalStateException("No active passenger fingerprint for iteration " + requestedIteration);
		}
		flushTimeBucket();
		Fingerprint result = new Fingerprint(iteration, eventCount, HexFormat.of().formatHex(digest.digest()), SCHEMA_VERSION);
		completed.put(iteration, result);
		acceptingEvents = false;
		return result;
	}

	private void flushTimeBucket() {
		if (sameTimeEvents.isEmpty()) return;
		updateRecord("time=" + Double.toHexString(currentTime));
		sameTimeEvents.sort(Comparator.naturalOrder());
		for (String event : sameTimeEvents) updateRecord(event);
		sameTimeEvents.clear();
	}

	private static String canonical(Event event) {
		Map<String, String> attributes = new TreeMap<>(event.getAttributes());
		attributes.remove(Event.ATTRIBUTE_TIME);
		attributes.put(Event.ATTRIBUTE_TYPE, event.getEventType());
		// MATSim 2026.0 getAttributes() incorrectly copies legMode into the stuck reason.
		// Read the actual typed field without altering the underlying event or MATSim core.
		if (event instanceof PersonStuckEvent stuck) {
			attributes.remove(PersonStuckEvent.ATTRIBUTE_REASON);
			if (stuck.getReason() != null) attributes.put(PersonStuckEvent.ATTRIBUTE_REASON, stuck.getReason());
		}
		StringBuilder value = new StringBuilder();
		for (var attribute : attributes.entrySet()) {
			appendField(value, attribute.getKey());
			appendField(value, attribute.getValue());
		}
		return value.toString();
	}

	private static void appendField(StringBuilder result, String value) {
		if (value == null) result.append("-1:");
		else result.append(value.length()).append(':').append(value);
	}

	private void updateRecord(String record) {
		byte[] bytes = record.getBytes(StandardCharsets.UTF_8);
		int length = bytes.length;
		digest.update((byte) (length >>> 24)); digest.update((byte) (length >>> 16));
		digest.update((byte) (length >>> 8)); digest.update((byte) length);
		digest.update(bytes);
	}

	private void writeCompletedIterations() {
		StringBuilder csv = new StringBuilder(context.fingerprintHeader(HEADER));
		for (Fingerprint result : completed.values()) {
			csv.append(context.experimentId()).append(',').append(context.blockId()).append(',').append(context.arm()).append(',')
					.append(result.iteration()).append(',').append(result.eventCount()).append(',')
					.append(result.sha256()).append(',').append(result.schemaVersion()).append(context.fingerprintRowSuffix()).append('\n');
		}
		try {
			Files.createDirectories(outputFile.getParent());
			Path temporary = Files.createTempFile(outputFile.getParent(), ".passenger-fingerprint-", ".csv.tmp");
			try {
				Files.writeString(temporary, csv, StandardCharsets.UTF_8);
				Files.move(temporary, outputFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(temporary);
			}
		} catch (IOException error) {
			throw new UncheckedIOException("Could not write passenger fingerprint: " + outputFile, error);
		}
	}

	private static MessageDigest newDigest() {
		try { return MessageDigest.getInstance("SHA-256"); }
		catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
	}

	public record Fingerprint(int iteration, long eventCount, String sha256, String schemaVersion) { }
}

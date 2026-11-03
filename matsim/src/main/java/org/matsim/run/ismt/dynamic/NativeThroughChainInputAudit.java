package org.matsim.run.ismt.dynamic;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.routes.ExperimentalTransitRoute;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.ChainedDeparture;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Assembly-only, read-only audit for the presently unsupported native through-chain
 * input domain. The caller explicitly supplies the plans actually to be executed;
 * this class NEVER substitutes Scenario population/selected-plan memory. It cannot
 * itself certify that those plans are QSim's executing plans or cover every agent.
 * Ordinary separate PT legs and empty vehicle reuse are not through-chains.
 *
 * <p>Both departure chain references and typed TransitPassengerRoute chains are
 * inspected. Known exact DefaultTransitPassengerRoute/ExperimentalTransitRoute
 * representations are checked structurally. Other PT representations are reported
 * unverified and cannot pass requireNoThroughChains. No XML/route-description text
 * is parsed. Identity-based, iterative traversal detects cyclic route objects
 * without recursively calling DefaultTransitPassengerRoute.getEgressStopId().
 *
 * <p>Two consecutive captures compare ONLY examined structural fields: registry
 * keys/IDs, vehicle IDs, ordered stop IDs, chain edges, supplied plan identities,
 * element/leg modes and typed route parts. The digest excludes times, capacity,
 * scores, activities' attributes, network, other plans and unexamined route fields.
 * It is NOT full input identity, an atomic snapshot, a concurrency/source seal or
 * proof of future immutability. Assembly must exclude concurrent mutation and audit
 * again for each actual prepared execution. The route traversal bound is a defensive
 * inspection limit, not an operational constraint; reaching it is unverified.
 * No runner, event, schedule or plan is changed. Rejection does not implement the
 * requested dynamic framework or claim that unsupported through-chains work.</p>
 */
public final class NativeThroughChainInputAudit {
    private static final int MAX_ROUTE_PARTS_PER_LEG = 10_000;
    private static final String HASH_SCOPE = "native-through-chain-examined-structure-v1";

    public enum Code {
        SCHEDULE_THROUGH_CHAIN, PLAN_THROUGH_CHAIN, CYCLIC_PLAN_ROUTE, CYCLIC_SCHEDULE_CHAIN,
        INVALID_STRUCTURE, DUPLICATE_IDENTITY, UNVERIFIED_PT_ROUTE, UNVERIFIED_READ_FAILURE,
        UNVERIFIED_TRAVERSAL_LIMIT, STRUCTURE_CHANGED
    }
    public record Violation(Code code, String location, String message) {
        public Violation { Objects.requireNonNull(code); Objects.requireNonNull(location); Objects.requireNonNull(message); }
    }
    /**
     * Exact available string IDs and zero-based element/leg/part indices; no retained native objects.
     * Plan routes have line/route IDs but no departure ID: no target departure is inferred.
     */
    public record Identity(String location, Map<String, String> checkedFields) {
        public Identity { Objects.requireNonNull(location); checkedFields = Map.copyOf(checkedFields); }
    }
    /** Inspected first-read counts; a read failure/traversal limit can leave a partial inspection, never a passing report. */
    public record Counts(long scheduleLines, long scheduleRoutes, long scheduleDepartures, long scheduledChainEdges,
                         long executingPlans, long legs, long ptLegs, long planRouteParts,
                         long planChainEdges, long unverifiedItems) { }

    public static final class Report {
        private final Counts counts;
        private final List<Identity> identities;
        private final List<Violation> violations;
        private final String beforeStructureSha256, afterStructureSha256;
        private Report(Capture first, Capture second) {
            counts = first.counts(); identities = List.copyOf(first.identities);
            beforeStructureSha256 = first.hash(); afterStructureSha256 = second.hash();
            var problems = new ArrayList<>(first.violations);
            if (!beforeStructureSha256.equals(afterStructureSha256)) {
                problems.add(new Violation(Code.STRUCTURE_CHANGED, "audit", "Examined structure differed between consecutive reads; neither capture is a source seal"));
            }
            violations = List.copyOf(problems);
        }
        public Counts counts() { return counts; }
        /** Entries/counts are the first read; afterStructureSha256 makes a different second read explicit. */
        public List<Identity> identities() { return identities; }
        public List<Violation> violations() { return violations; }
        public String beforeStructureSha256() { return beforeStructureSha256; }
        public String afterStructureSha256() { return afterStructureSha256; }
        public boolean stableAcrossReads() { return beforeStructureSha256.equals(afterStructureSha256); }
        public boolean verifiedNoThroughChains() { return stableAcrossReads() && violations.isEmpty(); }
        public void requireNoThroughChains() {
            if (!verifiedNoThroughChains()) throw new IllegalStateException("Native through-chain input boundary not verified: "
                    + violations.stream().map(issue -> issue.code() + " at " + issue.location() + ": " + issue.message()).toList());
        }
    }

    private NativeThroughChainInputAudit() { }

    public static Report inspect(TransitSchedule schedule, Map<String, ? extends Plan> executingPlans) {
        Objects.requireNonNull(schedule, "schedule"); Objects.requireNonNull(executingPlans, "explicit executing plans");
        return new Report(capture(schedule, executingPlans), capture(schedule, executingPlans));
    }

    private record RouteKey(String line, String route) { }
    private record Edge(TripKey source, TripKey target, String location) { }
    private record Part(String location, String line, String route, String access, String localEgress,
                        boolean verifiedRepresentation, TransitPassengerRoute next) { }
    private static final class Capture {
        final List<Identity> identities = new ArrayList<>();
        final List<Violation> violations = new ArrayList<>();
        final Map<RouteKey, List<String>> routeStops = new HashMap<>();
        final Map<TripKey, String> trips = new HashMap<>();
        final List<Edge> edges = new ArrayList<>();
        long lines, routes, departures, scheduleEdges, plans, legs, ptLegs, routeParts, planEdges, unverified;
        Counts counts() { return new Counts(lines, routes, departures, scheduleEdges, plans, legs, ptLegs, routeParts, planEdges, unverified); }
        void issue(Code code, String location, String message) {
            violations.add(new Violation(code, location, message));
            if (code.name().startsWith("UNVERIFIED_")) unverified++;
        }
        void entry(String location, Map<String, String> fields) { identities.add(new Identity(location, fields)); }
        String hash() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); field(digest, HASH_SCOPE); field(digest, counts().toString());
                for (Identity entry : identities) {
                    field(digest, "identity"); field(digest, entry.location()); field(digest, Integer.toString(entry.checkedFields().size()));
                    new TreeMap<>(entry.checkedFields()).forEach((key, value) -> { field(digest, key); field(digest, value); });
                }
                for (Violation issue : violations) {
                    field(digest, "violation"); field(digest, issue.code().name()); field(digest, issue.location()); field(digest, issue.message());
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }

    private static Capture capture(TransitSchedule schedule, Map<String, ? extends Plan> plans) {
        Capture capture = new Capture();
        try { schedule(capture, schedule); }
        catch (RuntimeException failure) { capture.issue(Code.UNVERIFIED_READ_FAILURE, "schedule", "Read failed with " + failure.getClass().getName()); }
        try { plans(capture, plans); }
        catch (RuntimeException failure) { capture.issue(Code.UNVERIFIED_READ_FAILURE, "executingPlans", "Read failed with " + failure.getClass().getName()); }
        return capture;
    }

    private static void schedule(Capture c, TransitSchedule schedule) {
        int lineIndex = 0;
        for (var lineEntry : sorted(schedule.getTransitLines())) {
            String lp = "schedule.lines[" + lineIndex++ + "]"; c.lines++;
            var fields = fields("kind", "line"); put(fields, "mapKey", id(lineEntry.getKey()));
            var line = lineEntry.getValue();
            if (line == null) { c.entry(lp, fields); c.issue(Code.INVALID_STRUCTURE, lp, "Null transit line"); continue; }
            String lineId = id(line.getId()); put(fields, "lineId", lineId); c.entry(lp, fields);
            sameKey(c, lp, id(lineEntry.getKey()), lineId);
            int routeIndex = 0;
            for (var routeEntry : sorted(line.getRoutes())) {
                String rp = lp + ".routes[" + routeIndex++ + "]"; c.routes++;
                var rf = fields("kind", "route"); put(rf, "lineId", lineId); put(rf, "mapKey", id(routeEntry.getKey()));
                var route = routeEntry.getValue();
                if (route == null) { c.entry(rp, rf); c.issue(Code.INVALID_STRUCTURE, rp, "Null schedule route"); continue; }
                String routeId = id(route.getId()); put(rf, "routeId", routeId); c.entry(rp, rf);
                sameKey(c, rp, id(routeEntry.getKey()), routeId);
                var stops = new ArrayList<String>(); int stopIndex = 0;
                for (var stop : route.getStops()) {
                    String sp = rp + ".stops[" + stopIndex++ + "]"; var sf = fields("kind", "stop");
                    String stopId = stop == null || stop.getStopFacility() == null ? null : id(stop.getStopFacility().getId());
                    put(sf, "stopId", stopId); c.entry(sp, sf);
                    if (valid(stopId)) stops.add(stopId); else c.issue(Code.INVALID_STRUCTURE, sp, "Missing stop facility ID");
                }
                if (stops.isEmpty()) c.issue(Code.INVALID_STRUCTURE, rp, "Empty schedule route stop list");
                if (valid(lineId) && valid(routeId) && c.routeStops.putIfAbsent(new RouteKey(lineId, routeId), List.copyOf(stops)) != null)
                    c.issue(Code.DUPLICATE_IDENTITY, rp, "Repeated actual line/route identity");
                int departureIndex = 0;
                for (var departureEntry : sorted(route.getDepartures())) {
                    String dp = rp + ".departures[" + departureIndex++ + "]"; c.departures++;
                    var df = fields("kind", "departure"); put(df, "source.line", lineId); put(df, "source.route", routeId);
                    put(df, "mapKey", id(departureEntry.getKey())); var departure = departureEntry.getValue();
                    if (departure == null) { c.entry(dp, df); c.issue(Code.INVALID_STRUCTURE, dp, "Null departure"); continue; }
                    String depId = id(departure.getId()); put(df, "source.departure", depId); put(df, "vehicleId", id(departure.getVehicleId())); c.entry(dp, df);
                    sameKey(c, dp, id(departureEntry.getKey()), depId); TripKey source = key(lineId, routeId, depId);
                    if (source != null && c.trips.putIfAbsent(source, dp) != null) c.issue(Code.DUPLICATE_IDENTITY, dp, "Repeated actual trip identity " + source);
                    List<ChainedDeparture> chains = departure.getChainedDepartures();
                    if (chains == null) { c.issue(Code.INVALID_STRUCTURE, dp, "Null departure chain list; absence is not verified"); continue; }
                    var seen = new HashSet<TripKey>();
                    for (int i = 0; i < chains.size(); i++) {
                        c.scheduleEdges++; String ep = dp + ".chains[" + i + "]";
                        var ef = fields("kind", "scheduledChain", "chainIndex", Integer.toString(i));
                        put(ef, "source.line", lineId); put(ef, "source.route", routeId); put(ef, "source.departure", depId);
                        ChainedDeparture chain = chains.get(i);
                        String tl = chain == null ? null : id(chain.getChainedTransitLineId());
                        String tr = chain == null ? null : id(chain.getChainedRouteId());
                        String td = chain == null ? null : id(chain.getChainedDepartureId());
                        put(ef, "target.line", tl); put(ef, "target.route", tr); put(ef, "target.departure", td); c.entry(ep, ef);
                        c.issue(Code.SCHEDULE_THROUGH_CHAIN, ep, "Unsupported native departure continuation " + source + " -> " + key(tl, tr, td));
                        TripKey target = key(tl, tr, td);
                        if (source == null || target == null) c.issue(Code.INVALID_STRUCTURE, ep, "Missing source/target trip ID component");
                        else {
                            if (!seen.add(target)) c.issue(Code.DUPLICATE_IDENTITY, ep, "Repeated target within source departure " + target);
                            c.edges.add(new Edge(source, target, ep));
                        }
                    }
                }
            }
        }
        for (Edge edge : c.edges) if (!c.trips.containsKey(edge.target())) c.issue(Code.INVALID_STRUCTURE, edge.location(), "Unregistered target trip " + edge.target());
        detectScheduleCycles(c);
    }

    private static void plans(Capture c, Map<String, ? extends Plan> executingPlans) {
        Set<String> owners = new HashSet<>();
        for (var entry : sorted(executingPlans)) {
            String person = entry.getKey(); String pp = "executingPlans[" + person + "]"; c.plans++;
            var pf = fields("kind", "executingPlan"); put(pf, "personId", person); Plan plan = entry.getValue();
            if (plan == null) { c.entry(pp, pf); c.issue(Code.INVALID_STRUCTURE, pp, "Null explicitly supplied execution plan"); continue; }
            put(pf, "planClass", plan.getClass().getName()); put(pf, "planId", id(plan.getId()));
            var planPerson = plan.getPerson();
            String owner = planPerson == null ? null : id(planPerson.getId()); put(pf, "ownerPersonId", owner); c.entry(pp, pf);
            if (!valid(person) || !person.equals(owner)) c.issue(Code.INVALID_STRUCTURE, pp, "Supplied person key and plan owner must agree");
            if (owner != null && !owners.add(owner)) c.issue(Code.DUPLICATE_IDENTITY, pp, "Same plan owner appears under multiple supplied keys");
            List<PlanElement> elements = plan.getPlanElements();
            if (elements == null || elements.isEmpty()) { c.issue(Code.INVALID_STRUCTURE, pp, "Empty/null executing plan element list"); continue; }
            int legIndex = 0;
            for (int elementIndex = 0; elementIndex < elements.size(); elementIndex++) {
                PlanElement element = elements.get(elementIndex); String ep = pp + ".elements[" + elementIndex + "]";
                var ef = fields("elementIndex", Integer.toString(elementIndex)); put(ef, "personId", person);
                if (element instanceof Activity) { ef.put("kind", "activity"); c.entry(ep, ef); continue; }
                if (!(element instanceof Leg leg)) { c.entry(ep, ef); c.issue(Code.INVALID_STRUCTURE, ep, "Null or unsupported plan element"); continue; }
                c.legs++; ef.put("kind", "leg"); ef.put("legIndex", Integer.toString(legIndex++)); put(ef, "mode", leg.getMode());
                var route = leg.getRoute(); put(ef, "routeClass", route == null ? null : route.getClass().getName()); c.entry(ep, ef);
                if (!valid(leg.getMode())) c.issue(Code.INVALID_STRUCTURE, ep, "Missing leg mode");
                boolean pt = TransportMode.pt.equals(leg.getMode());
                if (pt) c.ptLegs++;
                if (route instanceof TransitPassengerRoute transitRoute) {
                    if (!pt) c.issue(Code.INVALID_STRUCTURE, ep, "TransitPassengerRoute occurs on a non-pt leg");
                    routeParts(c, ep, person, elementIndex, legIndex - 1, transitRoute);
                } else if (pt) c.issue(Code.UNVERIFIED_PT_ROUTE, ep, "PT leg does not expose a typed TransitPassengerRoute; no route text is interpreted");
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void routeParts(Capture c, String legPath, String person, int elementIndex, int legIndex, TransitPassengerRoute root) {
        var visited = new IdentityHashMap<TransitPassengerRoute, Integer>(); var parts = new ArrayList<Part>();
        TransitPassengerRoute current = root;
        while (current != null) {
            if (parts.size() == MAX_ROUTE_PARTS_PER_LEG) { c.issue(Code.UNVERIFIED_TRAVERSAL_LIMIT, legPath, "Route-part inspection limit reached"); break; }
            int depth = parts.size(); visited.put(current, depth); c.routeParts++;
            String pp = legPath + ".ptRoute.parts[" + depth + "]";
            var f = fields("kind", "planRoutePart", "personId", person, "elementIndex", Integer.toString(elementIndex),
                    "legIndex", Integer.toString(legIndex), "partIndex", Integer.toString(depth), "routeClass", current.getClass().getName());
            boolean known = current.getClass() == DefaultTransitPassengerRoute.class || current.getClass() == ExperimentalTransitRoute.class;
            if (!known) c.issue(Code.UNVERIFIED_PT_ROUTE, pp, "Unregistered PT route implementation; typed chain presence is inspected but semantics are unverified");
            TransitPassengerRoute next = current.getChainedRoute();
            String line = null, route = null, access = null, egress = null;
            if (known) {
                line = id(current.getLineId()); route = id(current.getRouteId()); access = id(current.getAccessStopId());
                // Default's public local egress field avoids its recursive effective-egress getter.
                egress = current instanceof DefaultTransitPassengerRoute nativeRoute ? id(nativeRoute.egressFacility) : id(current.getEgressStopId());
                put(f, "lineId", line); put(f, "routeId", route); put(f, "accessStopId", access); put(f, "localEgressStopId", egress);
                if (!valid(line) || !valid(route) || !valid(access) || !valid(egress)) c.issue(Code.INVALID_STRUCTURE, pp, "Missing route identity/access/egress field");
                if (!c.routeStops.containsKey(new RouteKey(line, route))) c.issue(Code.INVALID_STRUCTURE, pp, "PT route references an unregistered line/route");
                else if (!c.routeStops.get(new RouteKey(line, route)).contains(access)) c.issue(Code.INVALID_STRUCTURE, pp, "Access stop is not in the registered route");
            }
            parts.add(new Part(pp, line, route, access, egress, known, next));
            if (next != null) {
                c.planEdges++; Integer alias = visited.get(next); f.put("nextPartIndex", Integer.toString(alias == null ? depth + 1 : alias));
                c.issue(Code.PLAN_THROUGH_CHAIN, pp, "Unsupported continuous PT route in person " + person + ", element " + elementIndex + ", leg " + legIndex);
                if (alias != null) { c.entry(pp, f); c.issue(Code.CYCLIC_PLAN_ROUTE, pp, "Route-object edge returns to part " + alias); break; }
            }
            c.entry(pp, f); current = next;
        }
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            Integer targetIndex = part.next() == null ? null : visited.get(part.next());
            if (part.next() != null) {
                var edgeFields = fields("kind", "planChain", "personId", person, "elementIndex", Integer.toString(elementIndex),
                        "legIndex", Integer.toString(legIndex), "sourcePartIndex", Integer.toString(i));
                put(edgeFields, "source.line", part.line()); put(edgeFields, "source.route", part.route());
                if (targetIndex != null) {
                    Part target = parts.get(targetIndex);
                    edgeFields.put("targetPartIndex", Integer.toString(targetIndex));
                    put(edgeFields, "target.line", target.line()); put(edgeFields, "target.route", target.route());
                }
                c.entry(part.location() + ".chain", edgeFields);
            }
            if (!part.verifiedRepresentation()) continue;
            List<String> stops = c.routeStops.get(new RouteKey(part.line(), part.route())); if (stops == null) continue;
            // The outer route can represent the full chained leg (including after native cloning).
            // Its effective exit on this service is the next part's access, not necessarily its stored egress.
            String serviceExit = part.next() == null ? part.localEgress() : targetIndex == null ? null : parts.get(targetIndex).access();
            if (serviceExit != null && !stops.contains(serviceExit)) c.issue(Code.INVALID_STRUCTURE, part.location(), "Exit/continuation access is not in the registered source route");
        }
    }

    private static void detectScheduleCycles(Capture c) {
        var outgoing = new HashMap<TripKey, List<Edge>>();
        c.edges.forEach(edge -> outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge));
        Map<TripKey, Integer> color = new HashMap<>();
        record Frame(TripKey key, int nextEdge) { }
        for (Edge initial : c.edges) {
            if (color.getOrDefault(initial.source(), 0) != 0) continue;
            var stack = new ArrayDeque<Frame>(); stack.push(new Frame(initial.source(), 0)); color.put(initial.source(), 1);
            while (!stack.isEmpty()) {
                Frame frame = stack.pop(); List<Edge> edges = outgoing.getOrDefault(frame.key(), List.of());
                if (frame.nextEdge() == edges.size()) { color.put(frame.key(), 2); continue; }
                Edge edge = edges.get(frame.nextEdge()); stack.push(new Frame(frame.key(), frame.nextEdge() + 1));
                int state = color.getOrDefault(edge.target(), 0);
                if (state == 1) c.issue(Code.CYCLIC_SCHEDULE_CHAIN, edge.location(), "Schedule chain closes a directed cycle at " + edge.target());
                else if (state == 0) { color.put(edge.target(), 1); stack.push(new Frame(edge.target(), 0)); }
            }
        }
    }

    private static void sameKey(Capture c, String location, String mapKey, String actualId) {
        if (!valid(mapKey) || !valid(actualId) || !mapKey.equals(actualId)) c.issue(Code.INVALID_STRUCTURE, location, "Registry key and object ID must agree");
    }
    private static TripKey key(String line, String route, String departure) { return valid(line) && valid(route) && valid(departure) ? new TripKey(line, route, departure) : null; }
    private static String id(Object id) { return id == null ? null : id.toString(); }
    private static boolean valid(String text) { return text != null && !text.isBlank(); }
    private static void put(Map<String, String> fields, String key, String value) { if (value != null) fields.put(key, value); }
    private static Map<String, String> fields(String... pairs) {
        var fields = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) put(fields, pairs[i], pairs[i + 1]); return fields;
    }
    private static <K, V> List<Map.Entry<K, V>> sorted(Map<K, V> map) {
        var entries = new ArrayList<>(Objects.requireNonNull(map).entrySet());
        entries.sort(Comparator.comparing((Map.Entry<K, V> entry) -> id(entry.getKey()), Comparator.nullsFirst(Comparator.naturalOrder()))); return entries;
    }
    private static void field(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
    }
}

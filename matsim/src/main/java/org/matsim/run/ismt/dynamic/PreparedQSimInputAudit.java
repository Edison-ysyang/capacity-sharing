package org.matsim.run.ismt.dynamic;

import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.mobsim.framework.PlanAgent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.pt.AbstractTransitDriverAgent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read-only assembly audit of the agents actually registered in a supplied QSim.
 * The caller MUST call at a real post-prepare lifecycle barrier, exclude concurrent
 * registry/plan/schedule mutation, and supply the explicit expected population IDs.
 * This class neither invokes nor verifies preparation, accepts no Initialized-event
 * token, and cannot turn a manual callback or a never-run QSim into lifecycle proof.
 * A passing report means the observed source structure meets this bounded input
 * domain; it is not preparation, execution, future-immutability or producer sealing.
 *
 * <p>Actual non-driver PlanAgent plans, never replacement Scenario selected plans,
 * are passed to NativeThroughChainInputAudit. Source-owner/coverage defects fail
 * closed even when the remaining partial plan set has no through-chains. Ordinary
 * real leave/enter transfers remain allowed by the underlying audit. A plan being
 * a different object from the selected plan is descriptive only: legitimate
 * PlanAgent implementations need not all use the same copying strategy.</p>
 *
 * <p>No injector, lifecycle method or native mutator is called. Reports retain only
 * value descriptions and the native-object-free structural audit report, not QSim,
 * agents, plans, persons, schedules or MATSim IDs. Getter failures are reported as
 * unverified input rather than silently dropping an agent.</p>
 */
public final class PreparedQSimInputAudit {
    private PreparedQSimInputAudit() { }

    public enum Code {
        INVALID_REGISTRY_ENTRY, REGISTRY_AGENT_ID_MISMATCH, DUPLICATE_AGENT_ID,
        DRIVER_POPULATION_OVERLAP, NON_PLAN_AGENT, INVALID_PLAN, PLAN_OWNER_MISMATCH,
        MISSING_POPULATION_AGENT, EXTRA_POPULATION_AGENT, READ_FAILURE, UNSUPPORTED_INPUT_STRUCTURE
    }
    public enum SelectedPlanRelation { SAME_OBJECT, DIFFERENT_OBJECT, UNAVAILABLE }
    public record Violation(Code code, String location, String message) {
        public Violation { Objects.requireNonNull(code); Objects.requireNonNull(location); Objects.requireNonNull(message); }
    }
    public record AgentIdentity(String registryId, String agentId, String agentClass, boolean transitDriver,
                                String planOwnerId, SelectedPlanRelation selectedPlanRelation) { }
    /** Counts describe this read, including rejected actors; validatedPlans is never a coverage substitute. */
    public record Counts(int registryEntries, int transitDrivers, int nonDriverAgents, int validatedPlans,
                         int expectedPopulation, int missingPopulationAgents, int extraPopulationAgents) { }

    public static final class Report {
        private final Counts counts;
        private final Set<String> expectedPopulationIds;
        private final List<AgentIdentity> identities;
        private final List<Violation> violations;
        private final NativeThroughChainInputAudit.Report throughChainReport;
        private Report(Counts counts, Set<String> expected, List<AgentIdentity> identities, List<Violation> violations,
                       NativeThroughChainInputAudit.Report throughChainReport) {
            this.counts = counts; this.expectedPopulationIds = Set.copyOf(expected);
            this.identities = List.copyOf(identities); this.violations = List.copyOf(violations);
            this.throughChainReport = throughChainReport;
        }
        public Counts counts() { return counts; }
        public Set<String> expectedPopulationIds() { return expectedPopulationIds; }
        public List<AgentIdentity> identities() { return identities; }
        public List<Violation> violations() { return violations; }
        /** Absent only when the nested inspection could not be obtained; such a report cannot pass. */
        public Optional<NativeThroughChainInputAudit.Report> throughChainReport() { return Optional.ofNullable(throughChainReport); }
        public boolean preparationBarrierCertified() { return false; }
        public boolean verifiedSupportedInput() {
            return violations.isEmpty() && throughChainReport != null && throughChainReport.verifiedNoThroughChains();
        }
        public void requireSupportedInput() {
            if (!verifiedSupportedInput()) throw new IllegalStateException("Prepared QSim input domain not verified: " + violations);
        }
    }

    public static Report inspect(QSim qsim, Set<String> expectedPopulationIds) {
        Objects.requireNonNull(qsim, "qsim");
        Set<String> expected = Set.copyOf(Objects.requireNonNull(expectedPopulationIds, "explicit expected population IDs"));
        for (String id : expected) if (id.isBlank()) throw new IllegalArgumentException("Expected population IDs must be nonblank");
        var identities = new ArrayList<AgentIdentity>(); var violations = new ArrayList<Violation>();
        var executing = new TreeMap<String, Plan>(); var observed = new TreeSet<String>(); var allIds = new TreeSet<String>();
        int entries = 0, drivers = 0, nonDrivers = 0;
        try {
            var registry = new ArrayList<>(qsim.getAgents().entrySet());
            registry.sort(Comparator.comparing(entry -> entry.getKey() == null ? "" : entry.getKey().toString()));
            for (var entry : registry) {
                entries++;
                String registryId = entry.getKey() == null ? "" : entry.getKey().toString();
                String agentId = "", ownerId = "", agentClass = "";
                boolean driver = false;
                SelectedPlanRelation relation = SelectedPlanRelation.UNAVAILABLE;
                try {
                    var agent = entry.getValue();
                    if (agent == null) {
                        issue(violations, Code.INVALID_REGISTRY_ENTRY, registryId, "Null registered agent");
                        continue;
                    }
                    agentClass = classLabel(agent.getClass());
                    driver = agent instanceof AbstractTransitDriverAgent;
                    if (driver) drivers++; else nonDrivers++;
                    var nativeId = agent.getId();
                    agentId = nativeId == null ? "" : nativeId.toString();
                    if (registryId.isBlank() || agentId.isBlank()) {
                        issue(violations, Code.INVALID_REGISTRY_ENTRY, registryId, "Registry key and agent ID must both be nonblank");
                        continue;
                    }
                    if (!registryId.equals(agentId)) issue(violations, Code.REGISTRY_AGENT_ID_MISMATCH, registryId, "Agent reports ID " + agentId);
                    if (!allIds.add(agentId)) issue(violations, Code.DUPLICATE_AGENT_ID, registryId, "Repeated actual agent ID " + agentId);
                    if (driver) {
                        if (expected.contains(agentId) || expected.contains(registryId))
                            issue(violations, Code.DRIVER_POPULATION_OVERLAP, registryId, "Transit driver overlaps explicit expected population: " + agentId);
                        continue;
                    }
                    observed.add(agentId);
                    if (!(agent instanceof PlanAgent planAgent)) {
                        issue(violations, Code.NON_PLAN_AGENT, registryId, "Non-driver does not expose PlanAgent.getCurrentPlan");
                        continue;
                    }
                    Plan plan = planAgent.getCurrentPlan();
                    if (plan == null || plan.getPerson() == null || plan.getPerson().getId() == null) {
                        issue(violations, Code.INVALID_PLAN, registryId, "Current plan or its owner identity is absent");
                        continue;
                    }
                    ownerId = plan.getPerson().getId().toString();
                    if (!ownerId.equals(agentId)) {
                        issue(violations, Code.PLAN_OWNER_MISMATCH, registryId, "Current plan owner is " + ownerId + ", agent is " + agentId);
                        continue;
                    }
                    if (plan.getPlanElements() == null || plan.getPlanElements().isEmpty()) {
                        issue(violations, Code.INVALID_PLAN, registryId, "Current plan has no executable elements");
                        continue;
                    }
                    var populationPerson = qsim.getScenario().getPopulation().getPersons().get(nativeId);
                    if (populationPerson != null && populationPerson.getSelectedPlan() != null)
                        relation = plan == populationPerson.getSelectedPlan() ? SelectedPlanRelation.SAME_OBJECT : SelectedPlanRelation.DIFFERENT_OBJECT;
                    if (executing.putIfAbsent(agentId, plan) != null)
                        issue(violations, Code.DUPLICATE_AGENT_ID, registryId, "Multiple current plans for actual ID " + agentId);
                } catch (RuntimeException failure) {
                    issue(violations, Code.READ_FAILURE, registryId, "Agent source read failed with " + classLabel(failure.getClass()));
                } finally {
                    identities.add(new AgentIdentity(registryId, agentId, agentClass, driver, ownerId, relation));
                }
            }
        } catch (RuntimeException failure) {
            issue(violations, Code.READ_FAILURE, "registry", "Registry read failed with " + classLabel(failure.getClass()));
        }
        var missing = new TreeSet<>(expected); missing.removeAll(observed);
        var extra = new TreeSet<>(observed); extra.removeAll(expected);
        for (String id : missing) issue(violations, Code.MISSING_POPULATION_AGENT, id, "Expected population ID absent from observed non-driver agents");
        for (String id : extra) issue(violations, Code.EXTRA_POPULATION_AGENT, id, "Non-driver ID is outside the explicit expected population");
        NativeThroughChainInputAudit.Report nested = null;
        try {
            nested = NativeThroughChainInputAudit.inspect(qsim.getScenario().getTransitSchedule(), executing);
            if (!nested.verifiedNoThroughChains()) issue(violations, Code.UNSUPPORTED_INPUT_STRUCTURE, "executingPlans/schedule",
                    "Native structural audit rejected: " + nested.violations());
        } catch (RuntimeException failure) {
            issue(violations, Code.READ_FAILURE, "executingPlans/schedule", "Structural inspection failed with " + classLabel(failure.getClass()));
        }
        return new Report(new Counts(entries, drivers, nonDrivers, executing.size(), expected.size(), missing.size(), extra.size()),
                expected, identities, violations, nested);
    }

    private static void issue(List<Violation> violations, Code code, String location, String message) {
        violations.add(new Violation(code, location, message));
    }
    private static String classLabel(Class<?> type) {
        if (!type.isHidden()) return type.getName();
        String host = type.getNestHost().getName(); int suffix = host.indexOf('/');
        return "hidden:" + (suffix < 0 ? host : host.substring(0, suffix));
    }
}

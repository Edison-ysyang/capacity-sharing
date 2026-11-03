package org.matsim.run.ismt;

import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Objects;

/** Immutable scientific inputs. Execution provenance belongs to the run receipt, not this object. */
public record ExperimentSpec(String schemaVersion, String experimentId, String blockId, Arm arm,
        Seeds seeds, Inputs inputs, Protocol protocol, FreightDemand freightDemand,
        Policies policies, OutputOptions output, String validationProfile) {
    public static final String SCHEMA = "ismt-experiment-v1";
    public enum Arm { CONTROL, MIXED, TIMETABLE_ONLY, DEDICATED }
    public enum Access { NO_FREIGHT, SHARED_PASSENGER_SERVICE, DEDICATED_ONLY }
    public enum ExposureOutput { TERMINAL, REPORTING_WINDOW }

    public ExperimentSpec {
        if (!SCHEMA.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported experiment schema");
        identifier(experimentId); identifier(blockId);
        Objects.requireNonNull(arm); Objects.requireNonNull(seeds); Objects.requireNonNull(inputs);
        Objects.requireNonNull(protocol); Objects.requireNonNull(freightDemand); Objects.requireNonNull(policies);
        Objects.requireNonNull(output); identifier(validationProfile);
        boolean freight = arm == Arm.MIXED || arm == Arm.DEDICATED;
        if (freight != (freightDemand.orderCount() > 0))
            throw new IllegalArgumentException("Arm and freight demand disagree");
        Access expected = arm == Arm.MIXED ? Access.SHARED_PASSENGER_SERVICE
                : arm == Arm.DEDICATED ? Access.DEDICATED_ONLY : Access.NO_FREIGHT;
        if (policies.access() != expected) throw new IllegalArgumentException("Arm and access policy disagree");
        if ((arm == Arm.DEDICATED || arm == Arm.TIMETABLE_ONLY) && policies.dedicatedLineId().isBlank())
            throw new IllegalArgumentException("Dedicated supply requires an explicit line ID");
        if ((arm == Arm.CONTROL || arm == Arm.MIXED) && !policies.dedicatedLineId().isBlank())
            throw new IllegalArgumentException("Baseline/shared arm must not name a dedicated-only line");
        if (freightDemand.serviceWindowEndSeconds() > protocol.executionHorizonSeconds())
            throw new IllegalArgumentException("Freight service ends after execution horizon");
    }

    public record Seeds(long passenger, long orderPool, long matsimGlobal) { }

    /** Paths are relative to the recorded workspace, never to a process working directory. */
    public record FileRef(String path, String sha256) {
        public FileRef {
            Objects.requireNonNull(path); Objects.requireNonNull(sha256);
            Path p = Path.of(path);
            if (path.isBlank() || p.isAbsolute() || p.normalize().startsWith(".."))
                throw new IllegalArgumentException("Input path must be workspace-relative: " + path);
            if (!sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid SHA-256: " + path);
        }
        public Path resolve(Path workspace) {
            return workspace.toAbsolutePath().normalize().resolve(path).normalize();
        }
    }

    public record Inputs(FileRef matsimConfig, FileRef network, FileRef population,
            FileRef passengerPlanMemory, FileRef personAttributes, FileRef baselineSchedule,
            FileRef baselineTransitVehicles, FileRef activatedSchedule, FileRef activatedTransitVehicles,
            FileRef roadVehicles, FileRef orderPool, FileRef executionOrders) {
        public Inputs {
            Objects.requireNonNull(matsimConfig); Objects.requireNonNull(network); Objects.requireNonNull(population);
            Objects.requireNonNull(passengerPlanMemory); Objects.requireNonNull(personAttributes);
            Objects.requireNonNull(baselineSchedule); Objects.requireNonNull(baselineTransitVehicles);
            Objects.requireNonNull(activatedSchedule); Objects.requireNonNull(activatedTransitVehicles);
            Objects.requireNonNull(roadVehicles); Objects.requireNonNull(orderPool); Objects.requireNonNull(executionOrders);
        }
        public Map<String, FileRef> all() {
            return Map.ofEntries(Map.entry("matsimConfig", matsimConfig), Map.entry("network", network),
                    Map.entry("population", population), Map.entry("passengerPlanMemory", passengerPlanMemory),
                    Map.entry("personAttributes", personAttributes), Map.entry("baselineSchedule", baselineSchedule),
                    Map.entry("baselineTransitVehicles", baselineTransitVehicles), Map.entry("activatedSchedule", activatedSchedule),
                    Map.entry("activatedTransitVehicles", activatedTransitVehicles), Map.entry("roadVehicles", roadVehicles),
                    Map.entry("orderPool", orderPool), Map.entry("executionOrders", executionOrders));
        }
    }

    public record Protocol(int firstIteration, int activationIteration, int innovationOffIteration,
            int innovationReopenIteration, int innovationFinalOffIteration, int finalIteration,
            int reportFirstIteration, int reportLastIteration, double behaviouralHorizonSeconds,
            double executionHorizonSeconds) {
        public Protocol {
            if (firstIteration < 0 || activationIteration <= firstIteration || activationIteration > finalIteration
                    || innovationOffIteration < firstIteration || innovationReopenIteration < innovationOffIteration
                    || innovationFinalOffIteration < innovationReopenIteration || innovationFinalOffIteration > finalIteration
                    || reportFirstIteration < innovationFinalOffIteration || reportLastIteration < reportFirstIteration
                    || reportLastIteration > finalIteration)
                throw new IllegalArgumentException("Invalid iteration protocol");
            positive(behaviouralHorizonSeconds, "behavioural horizon");
            positive(executionHorizonSeconds, "execution horizon");
            if (executionHorizonSeconds <= behaviouralHorizonSeconds)
                throw new IllegalArgumentException("Execution horizon must exceed behavioural horizon");
        }
    }

    public record FreightDemand(int orderCount, double equivalentPassengersPerM3,
            double serviceWindowStartSeconds, double serviceWindowEndSeconds,
            String destinationActivityPrefix, String shadowToken) {
        public FreightDemand {
            if (orderCount < 0) throw new IllegalArgumentException("Negative order count");
            positive(equivalentPassengersPerM3, "unit conversion");
            nonnegative(serviceWindowStartSeconds, "service start");
            positive(serviceWindowEndSeconds, "service end");
            if (serviceWindowEndSeconds <= serviceWindowStartSeconds) throw new IllegalArgumentException("Invalid service window");
            if (destinationActivityPrefix == null || destinationActivityPrefix.isBlank()
                    || shadowToken == null || shadowToken.isBlank()) throw new IllegalArgumentException("Missing freight ID/activity convention");
        }
    }

    public record Policies(Access access, String dedicatedLineId, List<String> transitModes, double freightShareCap,
            double passengerLoadGate, double legacyHardCapacityRatio, double handlingDwellSeconds,
            boolean passengerAdaptation, double comfortThreshold, double betaPhysical, double betaFreight,
            double freightLatePenaltyPerMinute, double freightDeniedBoardingPenalty,
            double freightIncompletePenaltyPerUnit, double passengerDeniedBoardingPenalty) {
        public Policies {
            Objects.requireNonNull(access); Objects.requireNonNull(dedicatedLineId);
            transitModes = List.copyOf(transitModes);
            if (!transitModes.contains("pt") || !transitModes.contains("freight_pt")
                    || transitModes.size() != new java.util.HashSet<>(transitModes).size())
                throw new IllegalArgumentException("Current routing adapters require distinct pt and freight_pt transit modes");
            fraction(freightShareCap, "freight cap"); fraction(passengerLoadGate, "passenger gate");
            fraction(legacyHardCapacityRatio, "legacy hard capacity ratio"); fraction(comfortThreshold, "comfort threshold");
            nonnegative(handlingDwellSeconds, "dwell"); nonnegative(betaPhysical, "physical beta");
            nonnegative(betaFreight, "co-presence beta"); nonnegative(freightLatePenaltyPerMinute, "late penalty");
            nonnegative(freightDeniedBoardingPenalty, "freight denial penalty");
            nonnegative(freightIncompletePenaltyPerUnit, "incomplete penalty");
            nonnegative(passengerDeniedBoardingPenalty, "passenger denial penalty");
        }
    }

    public record OutputOptions(String schemaVersion, ExposureOutput passengerExposure,
            boolean vehicleIntervals, int eventsWriteInterval, int plansWriteInterval) {
        public OutputOptions {
            if (!"ismt-output-v1".equals(schemaVersion)) throw new IllegalArgumentException("Unsupported output schema");
            Objects.requireNonNull(passengerExposure);
            if (eventsWriteInterval < 0 || plansWriteInterval < 0) throw new IllegalArgumentException("Negative output interval");
        }
    }

    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"))
            throw new IllegalArgumentException("Invalid identifier: " + value);
    }
    private static void fraction(double value, String name) {
        nonnegative(value, name);
        if (value > 1) throw new IllegalArgumentException(name + " exceeds one");
    }
    private static void positive(double value, String name) {
        nonnegative(value, name);
        if (value == 0) throw new IllegalArgumentException(name + " must be positive");
    }
    private static void nonnegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException(name + " must be finite and nonnegative");
    }
}

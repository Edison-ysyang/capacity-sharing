package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.core.mobsim.qsim.pt.ControlledTransitDriverAgentFactory;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Limits;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.DynamicMixedDesign.*;

/** Pure phase/value tests, with constructor-only factory comparisons; no QSim/JVM simulation. */
class DynamicMixedDesignTest {
    // Explicit fixture values, not production defaults or endorsed operating constraints.
    private static final Protocol PROTOCOL = new Protocol(0, 20, 150, 199, 170, 199);
    private static final Limits NONZERO = new Limits(30, 45, 10);

    private static DynamicMixedDesign design(Arm arm) {
        return new DynamicMixedDesign(arm, PROTOCOL, arm == Arm.DYNAMIC_MIXED, NONZERO);
    }

    @Test void v2IdentityContainsExactlyFourArmsAndHasNoLegacyT() {
        assertEquals(Set.of("CONTROL", "MIXED", "DYNAMIC_MIXED", "DEDICATED"),
                java.util.Arrays.stream(Arm.values()).map(Arm::name).collect(java.util.stream.Collectors.toSet()));
        assertEquals(4, Arm.values().length);
        assertEquals("dynamic-mixed-v2", SCHEMA_ID);
        for (Arm arm : Arm.values()) {
            assertSame(arm, parseArm(arm.name()));
            assertEquals(SCHEMA_ID, design(arm).schemaId());
        }
    }

    @Test void legacyTimetableOnlyCannotBeParsedOrSilentlyMappedToDynamicMixed() {
        var failure = assertThrows(IllegalArgumentException.class, () -> parseArm("TIMETABLE_ONLY"));
        assertTrue(failure.getMessage().contains("Legacy TIMETABLE_ONLY"));
        assertTrue(failure.getMessage().contains("historical results"));
    }

    @Test void aliasesWhitespaceAndCaseChangesAreNotImplicitSchemaMigration() {
        for (String value : List.of("T", "M", "C", "D", "dynamic_mixed", " DYNAMIC_MIXED", "MIXED ", "", "UNKNOWN")) {
            assertThrows(IllegalArgumentException.class, () -> parseArm(value));
        }
        assertThrows(NullPointerException.class, () -> parseArm(null));
    }

    @Test void commonHistoryHasNoFreightOrControlForAllFourArms() {
        for (Arm arm : Arm.values()) for (int iteration = 0; iteration < 20; iteration++) {
            assertEquals(new Phase(iteration, false, false, iteration == 0, false), design(arm).phaseAt(iteration));
        }
    }

    @Test void allIterationEntryInnovationFlagsMatchTheRegisteredR5Schedule() {
        for (Arm arm : Arm.values()) {
            int initiallyEnabled = 0, preactivationClosed = 0, activeEnabled = 0, finallyClosed = 0;
            for (int iteration = 0; iteration <= 199; iteration++) {
                boolean enabled = design(arm).phaseAt(iteration).passengerInnovationEnabled();
                if (iteration == 0) {
                    assertTrue(enabled); initiallyEnabled++;
                } else if (iteration < 20) {
                    assertFalse(enabled); preactivationClosed++;
                } else if (iteration < 150) {
                    assertTrue(enabled); activeEnabled++;
                } else {
                    assertFalse(enabled); finallyClosed++;
                }
            }
            assertEquals(List.of(1, 19, 130, 50),
                    List.of(initiallyEnabled, preactivationClosed, activeEnabled, finallyClosed));
        }
    }

    @Test void activationBoundaryStartsFreightForMtdButControlOnlyForT() {
        assertEquals(new Phase(20, false, false, true, false), design(Arm.CONTROL).phaseAt(20));
        assertEquals(new Phase(20, true, false, true, false), design(Arm.MIXED).phaseAt(20));
        assertEquals(new Phase(20, true, true, true, false), design(Arm.DYNAMIC_MIXED).phaseAt(20));
        assertEquals(new Phase(20, true, false, true, false), design(Arm.DEDICATED).phaseAt(20));
    }

    @Test void innovationCutoffDoesNotSwitchOffDynamicOperations() {
        var dynamic = design(Arm.DYNAMIC_MIXED);
        assertEquals(new Phase(149, true, true, true, false), dynamic.phaseAt(149));
        assertEquals(new Phase(150, true, true, false, false), dynamic.phaseAt(150));
        assertEquals(new Phase(169, true, true, false, false), dynamic.phaseAt(169));
        assertEquals(new Phase(170, true, true, false, true), dynamic.phaseAt(170));
        assertEquals(new Phase(199, true, true, false, true), dynamic.phaseAt(199));
    }

    @Test void allReportingIterationsRetainTreatmentButNotInnovation() {
        for (Arm arm : Arm.values()) for (int iteration = 170; iteration <= 199; iteration++) {
            Phase phase = design(arm).phaseAt(iteration);
            assertTrue(phase.reporting()); assertFalse(phase.passengerInnovationEnabled());
            assertEquals(arm != Arm.CONTROL, phase.freightTreatmentActive());
            assertEquals(arm == Arm.DYNAMIC_MIXED, phase.controlEligible());
        }
    }

    @Test void offDynamicRetainsFreightAndDynamicIdentityButBypassesControl() {
        var off = new DynamicMixedDesign(Arm.DYNAMIC_MIXED, PROTOCOL, false, NONZERO);
        for (int iteration : List.of(20, 150, 170, 199)) {
            assertFalse(off.phaseAt(iteration).controlEligible());
            assertTrue(off.phaseAt(iteration).freightTreatmentActive());
            assertEquals(design(Arm.MIXED).phaseAt(iteration), off.phaseAt(iteration));
        }
        assertEquals(Arm.DYNAMIC_MIXED, off.arm());
        assertNotEquals(design(Arm.MIXED), off, "Same phase switches do not collapse study identities or prove output equivalence");
    }

    @Test void zeroAmplitudeBypassesControlIndependentlyOfLockWindow() {
        for (double lockWindow : List.of(0.0, 10.0, 999.0)) {
            var zero = new DynamicMixedDesign(Arm.DYNAMIC_MIXED, PROTOCOL, true, new Limits(0, 0, lockWindow));
            for (int iteration : List.of(20, 150, 170, 199)) {
                assertFalse(zero.phaseAt(iteration).controlEligible());
                assertTrue(zero.phaseAt(iteration).freightTreatmentActive());
                assertEquals(design(Arm.MIXED).phaseAt(iteration), zero.phaseAt(iteration));
            }
            assertEquals(Arm.DYNAMIC_MIXED, zero.arm());
        }
    }

    @Test void advanceOnlyAndDelayOnlyBothPermitControl() {
        for (Limits limits : List.of(new Limits(1, 0, 0), new Limits(0, 1, 0))) {
            var dynamic = new DynamicMixedDesign(Arm.DYNAMIC_MIXED, PROTOCOL, true, limits);
            assertFalse(dynamic.phaseAt(19).controlEligible());
            assertTrue(dynamic.phaseAt(20).controlEligible()); assertTrue(dynamic.phaseAt(199).controlEligible());
        }
    }

    @Test void switchesMatchExistingFactoryActivationWithoutConstructingAnyDriver() {
        for (Arm arm : Arm.values()) for (boolean enabled : List.of(false, true)) {
            if (enabled && arm != Arm.DYNAMIC_MIXED) continue;
            for (Limits limits : List.of(NONZERO, new Limits(0, 0, 10), new Limits(1, 0, 10), new Limits(0, 1, 10))) {
                var design = new DynamicMixedDesign(arm, PROTOCOL, enabled, limits);
                for (int iteration : List.of(0, 19, 20, 149, 150, 170, 199)) {
                    var factory = new ControlledTransitDriverAgentFactory(enabled, iteration,
                            PROTOCOL.activationIteration(), limits,
                            () -> { throw new AssertionError("Phase comparison must not request active context"); });
                    assertEquals(factory.isControlActive(), design.phaseAt(iteration).controlEligible());
                }
            }
        }
    }

    @Test void nonDynamicArmsCannotSilentlyAcceptAnEnabledController() {
        for (Arm arm : List.of(Arm.CONTROL, Arm.MIXED, Arm.DEDICATED)) {
            assertThrows(IllegalArgumentException.class, () -> new DynamicMixedDesign(arm, PROTOCOL, true, NONZERO));
            assertThrows(IllegalArgumentException.class, () -> new DynamicMixedDesign(arm, PROTOCOL, true, new Limits(0, 0, 0)));
        }
    }

    @Test void nullIdentityProtocolAndLimitsHaveNoDefaults() {
        assertThrows(NullPointerException.class, () -> new DynamicMixedDesign(null, PROTOCOL, false, NONZERO));
        assertThrows(NullPointerException.class, () -> new DynamicMixedDesign(Arm.MIXED, null, false, NONZERO));
        assertThrows(NullPointerException.class, () -> new DynamicMixedDesign(Arm.MIXED, PROTOCOL, false, null));
    }

    @Test void invalidProtocolOrderIsRejectedAtConstruction() {
        for (int[] v : List.of(
                new int[]{-1, 20, 150, 199, 170, 199},
                new int[]{0, 0, 150, 199, 170, 199},
                new int[]{21, 20, 150, 199, 170, 199},
                new int[]{0, 20, 20, 199, 170, 199},
                new int[]{0, 20, 19, 199, 170, 199},
                new int[]{0, 20, 171, 199, 170, 199},
                new int[]{0, 20, 150, 199, 190, 180},
                new int[]{0, 20, 150, 198, 170, 199},
                new int[]{0, 20, 150, -1, 170, 199})) {
            assertThrows(IllegalArgumentException.class, () -> new Protocol(v[0], v[1], v[2], v[3], v[4], v[5]));
        }
    }

    @Test void phaseQueriesOutsideTheDeclaredExperimentAreRejected() {
        for (Arm arm : Arm.values()) for (int iteration : List.of(-1, 200, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class, () -> design(arm).phaseAt(iteration));
        }
        assertFalse(PROTOCOL.contains(-1)); assertTrue(PROTOCOL.contains(0));
        assertTrue(PROTOCOL.contains(199)); assertFalse(PROTOCOL.contains(200));
    }

    @Test void explicitNonzeroStartAndSingleIterationReportHaveNoHiddenDefaultDates() {
        var protocol = new Protocol(7, 9, 12, 15, 12, 12);
        var dynamic = new DynamicMixedDesign(Arm.DYNAMIC_MIXED, protocol, true, new Limits(2, 0, 0));
        assertEquals(new Phase(7, false, false, true, false), dynamic.phaseAt(7));
        assertEquals(new Phase(8, false, false, false, false), dynamic.phaseAt(8));
        assertEquals(new Phase(9, true, true, true, false), dynamic.phaseAt(9));
        assertEquals(new Phase(12, true, true, false, true), dynamic.phaseAt(12));
        assertEquals(new Phase(15, true, true, false, false), dynamic.phaseAt(15));
        assertThrows(IllegalArgumentException.class, () -> dynamic.phaseAt(6));
        assertThrows(IllegalArgumentException.class, () -> dynamic.phaseAt(16));
    }

    @Test void phaseQueriesAreImmutableAndDoNotAccumulateControlAcrossIterations() {
        var dynamic = design(Arm.DYNAMIC_MIXED); Phase first = dynamic.phaseAt(19);
        dynamic.phaseAt(199); dynamic.phaseAt(20); dynamic.phaseAt(150);
        assertEquals(first, dynamic.phaseAt(19));
        assertEquals(new DynamicMixedDesign(Arm.DYNAMIC_MIXED, PROTOCOL, true, NONZERO), dynamic);
        assertSame(PROTOCOL, dynamic.protocol()); assertSame(NONZERO, dynamic.limits());
    }
}

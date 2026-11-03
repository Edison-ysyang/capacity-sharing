package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.framework.events.MobsimAfterSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.mobsim.qsim.pt.PersonEntersPtVehicleEvent;
import org.matsim.core.mobsim.qsim.pt.PersonLeavesPtVehicleEvent;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.KnownFreightJourneyState.Contract;
import org.matsim.run.ismt.dynamic.KnownFreightJourneyState.Phase;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.ReceivedEvent;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure transitions and manually issued callbacks on NEVER RUN QSim objects; no native-run certification. */
class KnownFreightJourneyStateTest {
    private static final TripKey TRIP = new TripKey("line", "route", "departure");
    private static Contract journeyContract(String... units) { return new Contract("order", List.of(units), "cargo_delivery"); }
    private static KnownFreightReleaseState.Contract releaseContract(String... units) {
        return new KnownFreightReleaseState.Contract("order", "s0", "s1", 0, 100, List.of(units), "origin_explicit");
    }
    private static ActivityEndEvent release(String unit, double at) {
        return new ActivityEndEvent(at, Id.createPersonId(unit), Id.createLinkId("origin"), null, "origin_explicit", null);
    }
    private static AgentWaitingForPtEvent waitAt(String unit, double at) {
        return new AgentWaitingForPtEvent(at, Id.createPersonId(unit), Id.create("s0", TransitStopFacility.class),
                Id.create("s1", TransitStopFacility.class));
    }
    private static PersonEntersPtVehicleEvent board(String unit, double at, String vehicle) {
        return new PersonEntersPtVehicleEvent(at, Id.createPersonId(unit), Id.createVehicleId(vehicle),
                Id.create("line", TransitLine.class), Id.create("route", TransitRoute.class));
    }
    private static PersonLeavesPtVehicleEvent alight(String unit, double at, String vehicle) {
        return new PersonLeavesPtVehicleEvent(at, Id.createPersonId(unit), Id.createVehicleId(vehicle),
                Id.create("line", TransitLine.class), Id.create("route", TransitRoute.class));
    }
    private static ActivityStartEvent activity(String unit, double at, String type) {
        return new ActivityStartEvent(at, Id.createPersonId(unit), Id.createLinkId("delivery-link"), null, type);
    }
    private static ReceivedEvent received(Event event, long sequence) {
        return new ReceivedEvent(sequence, event.getTime(), event.getTime(), event.getClass().getName(),
                event.getEventType(), event.getAttributes(), true);
    }
    private static final class Fold {
        final KnownFreightReleaseState releases;
        final KnownFreightJourneyState journeys;
        KnownFreightReleaseState.State r;
        KnownFreightJourneyState.State j;
        long sequence;
        Fold() { this(List.of(journeyContract("unit", "private-suffix")), List.of(releaseContract("unit", "private-suffix"))); }
        Fold(List<Contract> journeyContracts, List<KnownFreightReleaseState.Contract> releaseContracts) {
            releases = new KnownFreightReleaseState(releaseContracts); journeys = new KnownFreightJourneyState(journeyContracts);
            r = releases.initialState(); j = journeys.initialState();
        }
        void event(Event event) {
            ReceivedEvent value = received(event, ++sequence);
            var nextRelease = releases.apply(r, value);
            var nextJourney = journeys.apply(j, value, nextRelease);
            r = nextRelease; j = nextJourney;
        }
        KnownFreightJourneyState.Journey unit() { return j.journeys().get("unit"); }
    }

    @Test void releasePublishesOnlyThatUnitAndDoesNotInventWaitingBoardingOrDelivery() {
        var fold = new Fold(); var initial = fold.j;
        assertTrue(initial.registrationPresent()); assertTrue(initial.knownReleasedUnitIds().isEmpty());
        fold.event(release("unit", 10));
        assertEquals(Set.of("unit"), fold.j.knownReleasedUnitIds());
        assertEquals(Set.of("unit"), fold.j.journeys().keySet());
        assertTrue(fold.j.allKnownReleasedUnitsCovered()); assertFalse(fold.j.journeys().toString().contains("private-suffix"));
        var unit = fold.unit(); assertEquals(Phase.RELEASED, unit.phase()); assertEquals(10, unit.releasedAtSeconds());
        assertTrue(unit.firstWaitingAtSeconds().isEmpty()); assertTrue(unit.firstBoardedAtSeconds().isEmpty());
        assertTrue(unit.lastAlightedAtSeconds().isEmpty()); assertTrue(unit.deliveredAtSeconds().isEmpty());
        assertTrue(unit.abortedAtSeconds().isEmpty()); assertEquals(1, unit.lastClosedSequence());
        assertTrue(initial.journeys().isEmpty());
    }

    @Test void completeNativeEventVocabularyKeepsReleaseWaitBoardAlightAndDeliverySeparate() {
        var fold = new Fold(); fold.event(release("unit", 10)); fold.event(waitAt("unit", 20));
        assertEquals(20, fold.unit().firstWaitingAtSeconds().orElseThrow());
        assertEquals(20, fold.unit().currentWaitingAtSeconds().orElseThrow());
        fold.event(board("unit", 30, "v")); var boarded = fold.j;
        assertEquals(30, fold.unit().firstBoardedAtSeconds().orElseThrow());
        assertTrue(fold.unit().currentWaitingAtSeconds().isEmpty());
        fold.event(alight("unit", 40, "v"));
        assertEquals(Phase.ALIGHTED, fold.unit().phase()); assertEquals(40, fold.unit().lastAlightedAtSeconds().orElseThrow());
        assertTrue(fold.unit().deliveredAtSeconds().isEmpty());
        fold.event(activity("unit", 45, "cargo_delivery:terminal"));
        assertEquals(Phase.DELIVERED, fold.unit().phase()); assertEquals(45, fold.unit().deliveredAtSeconds().orElseThrow());
        assertEquals(30, fold.unit().firstBoardedAtSeconds().orElseThrow()); assertEquals(5, fold.unit().lastClosedSequence());
        assertEquals(Phase.RIDING, boarded.journeys().get("unit").phase());
    }

    @Test void firstBoardingSurvivesTransferAndCurrentWaitingUsesTheNewEpisode() {
        var fold = new Fold(); fold.event(release("unit", 0)); fold.event(waitAt("unit", 1));
        fold.event(board("unit", 2, "v1")); fold.event(alight("unit", 3, "v1")); fold.event(waitAt("unit", 8));
        assertEquals(1, fold.unit().firstWaitingAtSeconds().orElseThrow());
        assertEquals(8, fold.unit().currentWaitingAtSeconds().orElseThrow());
        fold.event(board("unit", 9, "v2")); fold.event(alight("unit", 12, "v2"));
        assertEquals(2, fold.unit().firstBoardedAtSeconds().orElseThrow());
        assertEquals(12, fold.unit().lastAlightedAtSeconds().orElseThrow());
    }

    @Test void explicitDeliveryPrefixIsNotGuessedFromReleaseOrFacilityAndUsesStartsWith() {
        var fold = new Fold(); fold.event(release("unit", 0)); var released = fold.j;
        fold.event(activity("unit", 1, "origin_explicit_delivery"));
        fold.event(activity("unit", 2, "freight_delivery"));
        assertSame(released, fold.j);
        fold.event(activity("unit", 3, "cargo_delivery_extra"));
        assertEquals(3, fold.unit().deliveredAtSeconds().orElseThrow());
        assertTrue(fold.unit().firstBoardedAtSeconds().isEmpty(), "Actual delivery never invents a boarding");
    }

    @Test void releasedWalkingUnitCanAbortBeforeAnyStopArrival() {
        var fold = new Fold(); fold.event(release("unit", 2));
        fold.event(new PersonStuckEvent(3, Id.createPersonId("unit"), Id.createLinkId("access"), "walk"));
        assertEquals(Phase.ABORTED, fold.unit().phase()); assertEquals(3, fold.unit().abortedAtSeconds().orElseThrow());
        assertTrue(fold.unit().firstWaitingAtSeconds().isEmpty()); assertTrue(fold.unit().deliveredAtSeconds().isEmpty());
    }

    @Test void abortClearsCurrentWaitingOrVehicleWithoutInventingAlighting() {
        for (boolean riding : List.of(false, true)) {
            var fold = new Fold(); fold.event(release("unit", 0)); fold.event(waitAt("unit", 1));
            if (riding) fold.event(board("unit", 2, "v"));
            fold.event(new PersonStuckEvent(3, Id.createPersonId("unit"), Id.createLinkId("link"), "pt"));
            assertTrue(fold.unit().currentWaitingAtSeconds().isEmpty()); assertTrue(fold.unit().ridingVehicleId().isEmpty());
            assertTrue(fold.unit().lastAlightedAtSeconds().isEmpty()); assertEquals(Phase.ABORTED, fold.unit().phase());
        }
    }

    @Test void unregisteredEmptyEvidenceIsNotAZeroCompletionAndCoverageContainsOnlyReleasedIds() {
        var fold = new Fold(List.of(), List.of(releaseContract("unit", "private-suffix")));
        assertFalse(fold.j.registrationPresent()); assertFalse(fold.j.allKnownReleasedUnitsCovered());
        fold.event(release("unit", 0)); fold.event(activity("unit", 2, "cargo_delivery"));
        assertTrue(fold.j.journeys().isEmpty()); assertEquals(Set.of("unit"), fold.j.knownReleasedUnitIds());
        assertEquals(Set.of("unit"), fold.j.uncoveredReleasedUnitIds()); assertFalse(fold.j.allKnownReleasedUnitsCovered());
    }

    @Test void partiallyRegisteredOrdersBecomeExplicitlyUncoveredOnlyWhenTheirUnitReleases() {
        var fold = new Fold(List.of(journeyContract("unit")), List.of(releaseContract("unit", "private-suffix")));
        fold.event(release("unit", 0)); assertTrue(fold.j.allKnownReleasedUnitsCovered());
        fold.event(release("private-suffix", 1));
        assertEquals(Set.of("private-suffix"), fold.j.uncoveredReleasedUnitIds());
        assertEquals(Set.of("unit"), fold.j.journeys().keySet()); assertFalse(fold.j.allKnownReleasedUnitsCovered());
    }

    @Test void allJourneyEventsBeforeReleaseAreRejectedWithoutPublishingPrivateIdentities() {
        for (Event event : List.of(waitAt("unit", 1), board("unit", 1, "v"), alight("unit", 1, "v"),
                activity("unit", 1, "cargo_delivery"), new PersonStuckEvent(1, Id.createPersonId("unit"), null, "walk"))) {
            var fold = new Fold(); var before = fold.j;
            assertThrows(IllegalArgumentException.class, () -> fold.event(event));
            assertSame(before, fold.j); assertTrue(fold.j.journeys().isEmpty());
        }
    }

    @Test void malformedEnvelopeTimesTypesAndMissingDeliveryTypeCannotBecomeFacts() {
        var fold = new Fold(); fold.event(release("unit", 0));
        var good = received(waitAt("unit", 2), 2);
        var badTime = new LinkedHashMap<>(good.attributes()); badTime.put("time", "1");
        var badType = new LinkedHashMap<>(good.attributes()); badType.put("type", "wrong");
        for (var attrs : List.of(badTime, badType)) {
            var event = new ReceivedEvent(2, 2, 2, good.eventClass(), good.eventType(), attrs, true);
            assertThrows(IllegalArgumentException.class, () -> fold.journeys.apply(fold.j, event, fold.r));
        }
        var wrongClass = new ReceivedEvent(2, 2, 2, Event.class.getName(), good.eventType(), good.attributes(), true);
        assertThrows(IllegalArgumentException.class, () -> fold.journeys.apply(fold.j, wrongClass, fold.r));
        var wrongType = new ReceivedEvent(2, 2, 2, good.eventClass(), "wrong", good.attributes(), true);
        assertThrows(IllegalArgumentException.class, () -> fold.journeys.apply(fold.j, wrongType, fold.r));
        var delivered = received(activity("unit", 3, "cargo_delivery"), 3);
        var missing = new LinkedHashMap<>(delivered.attributes()); missing.remove("actType");
        assertThrows(IllegalArgumentException.class, () -> fold.journeys.apply(fold.j,
                new ReceivedEvent(3, 3, 3, delivered.eventClass(), delivered.eventType(), missing, true), fold.r));
        assertEquals(Phase.RELEASED, fold.unit().phase());
    }

    @Test void futureAndNonfiniteReceivedFactsAreRejectedBeforeApplyingThem() {
        var event = waitAt("unit", 2);
        for (double at : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1})
            assertThrows(IllegalStateException.class, () -> new ReceivedEvent(1, at, 3,
                    event.getClass().getName(), event.getEventType(), event.getAttributes(), true));
        assertThrows(IllegalArgumentException.class, () -> new ReceivedEvent(1, 2, 1,
                event.getClass().getName(), event.getEventType(), event.getAttributes(), true));
    }

    @Test void duplicateOrRegressingPerUnitEventsFailButSameSecondDifferentTransitionsAreValid() {
        var fold = new Fold(); fold.event(release("unit", 10)); fold.event(waitAt("unit", 10));
        var waiting = fold.j;
        assertThrows(IllegalArgumentException.class, () -> fold.journeys.apply(waiting, received(waitAt("unit", 10), 2), fold.r));
        assertThrows(IllegalArgumentException.class, () -> fold.journeys.apply(waiting, received(board("unit", 9, "v"), 3), fold.r));
        fold.event(board("unit", 10, "v")); fold.event(alight("unit", 10, "v")); fold.event(activity("unit", 10, "cargo_delivery"));
        assertEquals(10, fold.unit().firstBoardedAtSeconds().orElseThrow()); assertEquals(10, fold.unit().deliveredAtSeconds().orElseThrow());
    }

    @Test void differentUnitsMayHaveEarlierOccurrenceTimesInLaterPublicationOrder() {
        var fold = new Fold(); fold.event(release("unit", 20)); fold.event(release("private-suffix", 10));
        fold.event(waitAt("unit", 30)); fold.event(waitAt("private-suffix", 15));
        assertEquals(30, fold.unit().firstWaitingAtSeconds().orElseThrow());
        assertEquals(15, fold.j.journeys().get("private-suffix").firstWaitingAtSeconds().orElseThrow());
    }

    @Test void invalidPhaseVehicleAndTerminalTransitionsAreRejectedAtomically() {
        var fold = new Fold(); fold.event(release("unit", 0));
        assertThrows(IllegalArgumentException.class, () -> fold.event(board("unit", 1, "v")));
        fold.event(waitAt("unit", 2));
        assertThrows(IllegalArgumentException.class, () -> fold.event(waitAt("unit", 3)));
        assertThrows(IllegalArgumentException.class, () -> fold.event(activity("unit", 3, "cargo_delivery")));
        fold.event(board("unit", 4, "v")); var boarded = fold.j;
        assertThrows(IllegalArgumentException.class, () -> fold.event(board("unit", 5, "v")));
        assertThrows(IllegalArgumentException.class, () -> fold.event(alight("unit", 5, "wrong")));
        assertThrows(IllegalArgumentException.class, () -> fold.event(activity("unit", 5, "cargo_delivery")));
        assertSame(boarded, fold.j); fold.event(alight("unit", 6, "v")); fold.event(activity("unit", 7, "cargo_delivery"));
        var delivered = fold.j;
        assertThrows(IllegalArgumentException.class, () -> fold.event(activity("unit", 8, "cargo_delivery")));
        assertThrows(IllegalArgumentException.class, () -> fold.event(waitAt("unit", 8)));
        assertThrows(IllegalArgumentException.class, () -> fold.event(new PersonStuckEvent(8, Id.createPersonId("unit"), null, "walk")));
        assertSame(delivered, fold.j);
    }

    @Test void unrelatedPassengersAndGenericVehicleEntryDoNotInventFreightPtBoarding() {
        var fold = new Fold(); fold.event(release("unit", 0)); var before = fold.j;
        fold.event(waitAt("ordinary-passenger", 1));
        fold.event(new PersonEntersVehicleEvent(1, Id.createPersonId("unit"), Id.createVehicleId("car")));
        assertSame(before, fold.j); assertTrue(fold.unit().firstBoardedAtSeconds().isEmpty());
    }

    @Test void releaseAfterStateCannotIntroduceMissingHistoryChangeAnOrderOrRollBack() {
        var fold = new Fold(); var actual = received(release("unit", 0), 1);
        var next = fold.releases.apply(fold.r, actual);
        assertThrows(IllegalArgumentException.class, () -> fold.journeys.apply(fold.j, received(waitAt("unit", 1), 2), next));
        var released = fold.journeys.apply(fold.j, actual, next);
        assertThrows(IllegalArgumentException.class, () -> fold.journeys.apply(released, received(waitAt("unit", 1), 2), fold.r));
        assertThrows(IllegalArgumentException.class, () -> fold.journeys.apply(released, actual, next));
        var multiple = fold.releases.apply(next, received(release("private-suffix", 1), 2));
        assertThrows(IllegalArgumentException.class, () -> fold.journeys.apply(fold.j, received(release("private-suffix", 1), 2), multiple));
        var foreignRelease = new KnownFreightReleaseState(List.of(new KnownFreightReleaseState.Contract(
                "wrong-order", "s0", "s1", 0, 100, List.of("unit"), "origin_explicit")));
        var wrong = foreignRelease.apply(foreignRelease.initialState(), actual);
        assertThrows(IllegalArgumentException.class, () -> fold.journeys.apply(fold.j, actual, wrong));
    }

    @Test void contractsCollectionsAndStatesAreImmutableAndOwnerBound() {
        var units = new ArrayList<>(List.of("unit")); var contracts = new ArrayList<>(List.of(new Contract("order", units, "cargo_delivery")));
        var mapper = new KnownFreightJourneyState(contracts); units.clear(); contracts.clear();
        var fold = new Fold(); fold.event(release("unit", 0));
        assertThrows(UnsupportedOperationException.class, () -> fold.j.journeys().clear());
        assertThrows(UnsupportedOperationException.class, () -> fold.j.knownReleasedUnitIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> fold.j.uncoveredReleasedUnitIds().add("future"));
        assertThrows(IllegalArgumentException.class, () -> mapper.apply(fold.j, received(waitAt("unit", 1), 2), fold.r));
        assertTrue(java.util.Arrays.stream(KnownFreightJourneyState.State.class.getDeclaredConstructors())
                .allMatch(c -> Modifier.isPrivate(c.getModifiers())));
        assertTrue(mapper.initialState().registrationPresent());
    }

    @Test void badOrMismatchedRegistrationIsRejectedAndLegacyInputsRemainUnavailable() {
        assertThrows(IllegalArgumentException.class, () -> new Contract("order", List.of("u", "u"), "cargo"));
        assertThrows(IllegalArgumentException.class, () -> new Contract("order", List.of(), "cargo"));
        assertThrows(IllegalArgumentException.class, () -> new Contract("order", List.of("u"), " "));
        assertThrows(IllegalArgumentException.class, () -> new KnownFreightJourneyState(List.of(journeyContract("u"), journeyContract("v"))));
        assertThrows(IllegalArgumentException.class, () -> new KnownFreightJourneyState(List.of(journeyContract("u"), new Contract("other", List.of("u"), "cargo"))));
        assertThrows(IllegalArgumentException.class, () -> KnownFreightJourneyState.validateAgainstReleaseContracts(
                List.of(journeyContract("missing")), List.of(releaseContract("unit"))));
        var old = new DynamicMixedQSimModule.Inputs(List.of(), List.of(), List.of(releaseContract("unit")),
                Set.of(), Set.of("unit"), List.of(), List.of(), 0);
        assertTrue(old.journeyContracts().isEmpty());
        var explicit = new DynamicMixedQSimModule.Inputs(List.of(), List.of(), List.of(releaseContract("unit")),
                Set.of(), Set.of("unit"), List.of(), List.of(), 0, List.of(journeyContract("unit")));
        assertEquals(1, explicit.journeyContracts().size());
        assertThrows(IllegalArgumentException.class, () -> new DynamicMixedQSimModule.Inputs(List.of(), List.of(),
                List.of(releaseContract("unit")), Set.of(), Set.of("unit"), List.of(), List.of(), 0,
                List.of(new Contract("different-order", List.of("unit"), "cargo"))));
    }

    private static ClosedCoTransportObservationState composition(boolean registered) {
        var services = List.of(new ObservedTransitServiceState.Service(TRIP, "v", List.of("s0", "s1")));
        return registered ? new ClosedCoTransportObservationState(20, 1, services, List.of(releaseContract("unit", "private-suffix")),
                Set.of(), Map.of(), List.of(journeyContract("unit", "private-suffix")))
                : new ClosedCoTransportObservationState(20, 1, services, List.of(releaseContract("unit", "private-suffix")), Set.of(), Map.of());
    }
    private static final class Source implements AutoCloseable {
        final EventsManager events = EventsUtils.createEventsManager();
        final QSim qsim;
        final NativeEventPrefixObserver observer;
        final List<NativeEventPrefixObserver.StepView> views = new ArrayList<>();
        Source() {
            var config = ConfigUtils.createConfig(); config.qsim().setNumberOfThreads(1); config.global().setNumberOfThreads(1);
            config.qsim().setStartTime(0); config.qsim().setEndTime(10);
            config.controller().setOutputDirectory(Path.of(".build-cache", "journey-unit-unused").toAbsolutePath().toString());
            qsim = new QSimBuilder(config).useDefaults().build(ScenarioUtils.createScenario(config), events);
            observer = NativeEventPrefixObserver.attach(qsim, 20, 1, 0, views::add);
            qsim.getSimTimer().setTime(0); observer.notifyMobsimInitialized(new MobsimInitializedEvent<>(qsim));
        }
        NativeEventPrefixObserver.StepView before(double at) {
            qsim.getSimTimer().setTime(at); observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(qsim, at));
            return views.getLast();
        }
        void after(double at) { qsim.getSimTimer().setTime(at); observer.notifyMobsimAfterSimStep(new MobsimAfterSimStepEvent<>(qsim, at)); }
        @Override public void close() { observer.close(); }
    }

    @Test void publicSnapshotPublishesOnlyClosedJourneyPrefixAndNeverConfusesAlightWithDelivery() {
        try (Source source = new Source()) {
            var component = composition(true); component.bindTo(source.observer); var initial = component.accept(source.before(0));
            source.events.processEvent(release("unit", 0)); source.after(0);
            var released = component.accept(source.before(1));
            assertTrue(released.riders().riders().isEmpty()); assertEquals(Phase.RELEASED, released.journeys().journeys().get("unit").phase());
            source.events.processEvent(new TransitDriverStartsEvent(1, Id.createPersonId("driver"), Id.createVehicleId("v"),
                    Id.create("line", TransitLine.class), Id.create("route", TransitRoute.class), Id.create("departure", Departure.class)));
            source.events.processEvent(new VehicleArrivesAtFacilityEvent(1, Id.createVehicleId("v"), Id.create("s0", TransitStopFacility.class), 0));
            source.events.processEvent(waitAt("unit", 1)); source.events.processEvent(board("unit", 1, "v"));
            source.events.processEvent(new VehicleDepartsAtFacilityEvent(1, Id.createVehicleId("v"), Id.create("s0", TransitStopFacility.class), 0));
            source.after(1); var riding = component.accept(source.before(2));
            assertEquals(1, riding.journeys().journeys().get("unit").firstBoardedAtSeconds().orElseThrow());
            source.events.processEvent(new VehicleArrivesAtFacilityEvent(2, Id.createVehicleId("v"), Id.create("s1", TransitStopFacility.class), 0));
            source.events.processEvent(alight("unit", 2, "v")); source.after(2);
            var alighted = component.accept(source.before(3));
            assertTrue(alighted.journeys().journeys().get("unit").deliveredAtSeconds().isEmpty());
            source.events.processEvent(activity("unit", 3, "cargo_delivery_suffix"));
            assertSame(alighted, component.snapshot().orElseThrow(), "Unclosed delivery is unavailable");
            source.after(3); var delivered = component.accept(source.before(4));
            assertEquals(3, delivered.journeys().journeys().get("unit").deliveredAtSeconds().orElseThrow());
            assertEquals(3, delivered.position().closedThroughSeconds().orElseThrow());
            assertEquals(Set.of("unit"), delivered.journeys().knownReleasedUnitIds());
            assertTrue(initial.journeys().journeys().isEmpty()); assertTrue(alighted.journeys().journeys().get("unit").deliveredAtSeconds().isEmpty());
        }
    }

    @Test void journeyTailFailureRollsBackTheEntireSharedPrefixIncludingItsEarlierRelease() {
        try (Source source = new Source()) {
            var component = composition(true); component.bindTo(source.observer); var initial = component.accept(source.before(0));
            source.events.processEvent(release("unit", 0)); source.events.processEvent(activity("unit", 0, "cargo_delivery"));
            source.events.processEvent(activity("unit", 0, "cargo_delivery_again")); source.after(0);
            assertThrows(IllegalArgumentException.class, () -> component.accept(source.before(1)));
            assertTrue(component.isPoisoned()); assertSame(initial, component.lastAcceptedSnapshotForAudit().orElseThrow());
            assertTrue(initial.releases().knownOrders().isEmpty()); assertTrue(initial.journeys().journeys().isEmpty());
            assertThrows(IllegalArgumentException.class, component::snapshot);
        }
    }

    @Test void legacyCompositionAndDifferentSourceEpochDoNotInventEvidenceOrCarryYesterday() {
        try (Source source = new Source(); Source foreign = new Source()) {
            var legacy = composition(false); legacy.bindTo(source.observer); legacy.accept(source.before(0));
            source.events.processEvent(release("unit", 0)); source.after(0); var known = legacy.accept(source.before(1));
            assertFalse(known.journeys().registrationPresent()); assertEquals(Set.of("unit"), known.journeys().uncoveredReleasedUnitIds());
            var fresh = composition(true); fresh.bindTo(foreign.observer); var empty = fresh.accept(foreign.before(0));
            assertTrue(empty.journeys().journeys().isEmpty());
            source.after(1);
            assertThrows(IllegalArgumentException.class, () -> fresh.accept(source.before(2)));
            assertSame(empty, fresh.lastAcceptedSnapshotForAudit().orElseThrow());
            var wrongEpoch = new ClosedCoTransportObservationState(20, 2, List.of(new ObservedTransitServiceState.Service(TRIP, "v", List.of("s0", "s1"))),
                    List.of(releaseContract("unit")), Set.of(), Map.of(), List.of(journeyContract("unit")));
            assertThrows(IllegalArgumentException.class, () -> wrongEpoch.bindTo(foreign.observer));
        }
    }
}

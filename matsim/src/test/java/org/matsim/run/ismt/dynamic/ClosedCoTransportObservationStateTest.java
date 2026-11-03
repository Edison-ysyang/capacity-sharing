package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.framework.events.MobsimAfterSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.TripKey;
import org.matsim.run.ismt.dynamic.KnownFreightReleaseState.Contract;
import org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.StepView;
import org.matsim.run.ismt.dynamic.ObservedTransitServiceState.Service;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Manual callbacks on real, NEVER RUN QSim instances: composition tests, not source/runtime certification. */
class ClosedCoTransportObservationStateTest {
    private static final TripKey A = new TripKey("line", "route", "A");
    private static List<Service> services() { return List.of(new Service(A, "v", List.of("s0", "s1"))); }
    private static List<Contract> contracts() {
        return List.of(new Contract("order", "s0", "s1", 0, 50, List.of("freight"), "cargo_origin"));
    }
    private static ClosedCoTransportObservationState component() {
        return new ClosedCoTransportObservationState(20, 1, services(), contracts(), Set.of("passenger"), Map.of());
    }
    private static ActivityEndEvent release(double at) {
        return new ActivityEndEvent(at, Id.createPersonId("freight"), Id.createLinkId("origin"), null, "cargo_origin", null);
    }
    private static AgentWaitingForPtEvent waiting(double at, String person) {
        return new AgentWaitingForPtEvent(at, Id.createPersonId(person), Id.create("s0", TransitStopFacility.class),
                Id.create("s1", TransitStopFacility.class));
    }
    private static TransitDriverStartsEvent start(double at) {
        return new TransitDriverStartsEvent(at, Id.createPersonId("driver"), Id.createVehicleId("v"),
                Id.create(A.line(), TransitLine.class), Id.create(A.route(), TransitRoute.class), Id.create(A.departure(), Departure.class));
    }
    private static VehicleArrivesAtFacilityEvent arrival(double at, String stop) {
        return new VehicleArrivesAtFacilityEvent(at, Id.createVehicleId("v"), Id.create(stop, TransitStopFacility.class), 0);
    }

    private static final class Marker extends Event {
        private final String value;
        private Marker(double time, String value) { super(time); this.value = value; }
        @Override public String getEventType() { return "composition-marker"; }
        @Override public Map<String, String> getAttributes() {
            Map<String, String> attrs = super.getAttributes(); attrs.put("value", value); return attrs;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final EventsManager events = EventsUtils.createEventsManager();
        final QSim qsim;
        final NativeEventPrefixObserver observer;
        final List<StepView> views = new ArrayList<>();
        Fixture() { this(20, 1, null); }
        Fixture(int iteration, long epoch, Consumer<StepView> consumer) {
            var config = ConfigUtils.createConfig();
            config.qsim().setNumberOfThreads(1); config.global().setNumberOfThreads(1);
            config.qsim().setStartTime(0); config.qsim().setEndTime(5);
            config.controller().setOutputDirectory(Path.of(".build-cache", "closed-composition-unit-unused").toAbsolutePath().toString());
            qsim = new QSimBuilder(config).useDefaults().build(ScenarioUtils.createScenario(config), events);
            observer = NativeEventPrefixObserver.attach(qsim, iteration, epoch, 0, view -> {
                views.add(view); if (consumer != null) consumer.accept(view);
            });
            qsim.getSimTimer().setTime(0);
            observer.notifyMobsimInitialized(new MobsimInitializedEvent<>(qsim));
        }
        void event(Event event) { events.processEvent(event); }
        void at(double time) { qsim.getSimTimer().setTime(time); }
        StepView before(double time) {
            at(time); observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(qsim, time)); return latest();
        }
        void after(double time) {
            at(time); observer.notifyMobsimAfterSimStep(new MobsimAfterSimStepEvent<>(qsim, time));
        }
        StepView latest() { return views.getLast(); }
        @Override public void close() { observer.close(); }
    }

    @Test void explicitBindingAndTheInitialUnknownViewAreRequired() {
        try (Fixture fixture = new Fixture()) {
            var unbound = component(); StepView view = fixture.before(0);
            assertThrows(IllegalArgumentException.class, () -> unbound.accept(view));
            assertTrue(unbound.isPoisoned()); assertTrue(unbound.lastAcceptedSnapshotForAudit().isEmpty());
            var bound = component(); bound.bindTo(fixture.observer);
            assertTrue(bound.snapshot().isEmpty()); var first = bound.accept(view);
            assertEquals(1, first.position().viewSequence());
            assertEquals(0, first.position().closedEventCount());
            assertTrue(first.position().closedThroughSeconds().isEmpty());
            assertTrue(first.services().observedServices().isEmpty());
            assertTrue(first.releases().knownOrders().isEmpty()); assertTrue(first.riders().riders().isEmpty());
        }
    }

    @Test void identicalIterationEpochAndContentCannotSubstituteAnotherObserver() {
        try (Fixture left = new Fixture(); Fixture right = new Fixture()) {
            var component = component(); component.bindTo(left.observer);
            StepView a = left.before(0), b = right.before(0);
            assertEquals(a.closedPrefixSha256(), b.closedPrefixSha256());
            assertNotSame(a.sourceIdentity(), b.sourceIdentity());
            assertThrows(IllegalArgumentException.class, () -> component.accept(b));
            assertTrue(component.isPoisoned()); assertThrows(IllegalArgumentException.class, () -> component.accept(a));
        }
    }

    @Test void wrongIterationOrEpochAndRebindingAreRejected() {
        for (long epoch : new long[]{1, 2}) {
            try (Fixture source = new Fixture(epoch == 1 ? 21 : 20, epoch, null)) {
                var component = component();
                assertThrows(IllegalArgumentException.class, () -> component.bindTo(source.observer));
                assertTrue(component.isPoisoned());
            }
        }
        try (Fixture source = new Fixture()) {
            var component = component(); component.bindTo(source.observer);
            assertThrows(IllegalArgumentException.class, () -> component.bindTo(source.observer));
        }
    }

    @Test void replayAndSkippedEmptyViewsFailEvenWhenEventHashesAreEqual() {
        try (Fixture source = new Fixture()) {
            var component = component(); component.bindTo(source.observer);
            StepView first = source.before(0); var accepted = component.accept(first);
            assertThrows(IllegalArgumentException.class, () -> component.accept(first));
            assertSame(accepted, component.lastAcceptedSnapshotForAudit().orElseThrow());
        }
        try (Fixture source = new Fixture()) {
            var component = component(); component.bindTo(source.observer);
            StepView first = source.before(0); component.accept(first);
            source.after(0); StepView skipped = source.before(1); source.after(1); StepView later = source.before(2);
            assertEquals(first.closedPrefixSha256(), skipped.closedPrefixSha256());
            assertEquals(skipped.closedPrefixSha256(), later.closedPrefixSha256());
            assertThrows(IllegalArgumentException.class, () -> component.accept(later));
        }
    }

    @Test void startingFromAClosedNonInitialViewIsRejected() {
        try (Fixture source = new Fixture()) {
            var component = component(); component.bindTo(source.observer);
            source.before(0); source.event(release(0)); source.after(0); StepView second = source.before(1);
            assertThrows(IllegalArgumentException.class, () -> component.accept(second));
            assertTrue(component.lastAcceptedSnapshotForAudit().isEmpty());
        }
    }

    @Test void closedDeltaAtomicallyComposesServiceReleaseAndFreightQueueState() {
        try (Fixture source = new Fixture()) {
            var component = component(); component.bindTo(source.observer);
            var first = component.accept(source.before(0));
            source.event(start(0)); source.event(arrival(0, "s0"));
            source.event(release(0)); source.event(waiting(0, "freight")); source.event(waiting(0, "passenger"));
            assertSame(first, component.snapshot().orElseThrow(), "Received but unclosed events are not visible");
            source.after(0); var next = component.accept(source.before(1));
            assertEquals(5, next.position().closedEventCount());
            assertEquals(Set.of(A), next.services().observedServices().keySet());
            assertEquals(Map.of("freight", 0.0), next.releases().knownOrders().get("order").releasedUnits());
            assertEquals(Set.of("freight", "passenger"), next.riders().riders().keySet());
            assertEquals("WAITING", next.riders().riders().get("freight").phase().name());
            assertTrue(first.services().observedServices().isEmpty()); assertTrue(first.releases().knownOrders().isEmpty());
            assertTrue(first.riders().riders().isEmpty());
        }
    }

    @Test void releaseAloneDoesNotPublishFreightAsAWaitingRider() {
        try (Fixture source = new Fixture()) {
            var component = component(); component.bindTo(source.observer);
            component.accept(source.before(0)); source.event(release(0)); source.after(0);
            var released = component.accept(source.before(1));
            assertEquals(1, released.releases().knownOrders().size()); assertTrue(released.riders().riders().isEmpty());
            source.event(waiting(1, "freight")); source.after(1); var waiting = component.accept(source.before(2));
            assertEquals("WAITING", waiting.riders().riders().get("freight").phase().name());
            assertTrue(released.riders().riders().isEmpty());
        }
    }

    @Test void invalidServiceTailCannotPublishEarlierValidReleaseOrServiceStart() {
        try (Fixture source = new Fixture()) {
            var component = component(); component.bindTo(source.observer);
            var accepted = component.accept(source.before(0));
            source.event(release(0)); source.event(start(0)); source.event(arrival(0, "s1")); source.after(0);
            StepView rejected = source.before(1);
            assertThrows(IllegalArgumentException.class, () -> component.accept(rejected));
            assertTrue(component.isPoisoned()); assertThrows(IllegalArgumentException.class, component::snapshot);
            assertSame(accepted, component.lastAcceptedSnapshotForAudit().orElseThrow());
            assertTrue(accepted.releases().knownOrders().isEmpty()); assertTrue(accepted.services().observedServices().isEmpty());
        }
    }

    @Test void invalidRiderTailCannotPublishEarlierValidReleaseAndServiceState() {
        try (Fixture source = new Fixture()) {
            var component = component(); component.bindTo(source.observer);
            var accepted = component.accept(source.before(0));
            source.event(start(0)); source.event(arrival(0, "s0")); source.event(release(0));
            source.event(waiting(0, "unregistered")); source.after(0); StepView rejected = source.before(1);
            assertThrows(IllegalArgumentException.class, () -> component.accept(rejected));
            assertSame(accepted, component.lastAcceptedSnapshotForAudit().orElseThrow());
            assertTrue(accepted.riders().riders().isEmpty()); assertTrue(accepted.releases().knownOrders().isEmpty());
            assertTrue(accepted.services().observedServices().isEmpty());
        }
    }

    @Test void freightWaitingBeforeActualReleaseFailsWithoutReorderingEqualTimeEvents() {
        try (Fixture source = new Fixture()) {
            var component = component(); component.bindTo(source.observer);
            var accepted = component.accept(source.before(0));
            source.event(waiting(0, "freight")); source.event(release(0)); source.after(0);
            assertThrows(IllegalArgumentException.class, () -> component.accept(source.before(1)));
            assertSame(accepted, component.lastAcceptedSnapshotForAudit().orElseThrow());
        }
    }

    @Test void sourceAndComposerBothFailClosedWhenNativeDeliveryConsumerRejects() {
        var component = component();
        try (Fixture source = new Fixture(20, 1, component::accept)) {
            component.bindTo(source.observer); source.before(0);
            var prior = component.snapshot().orElseThrow();
            source.event(waiting(0, "freight")); source.after(0);
            assertThrows(IllegalArgumentException.class, () -> source.before(1));
            assertTrue(component.isPoisoned()); assertTrue(source.observer.isPoisoned());
            assertSame(prior, component.lastAcceptedSnapshotForAudit().orElseThrow());
        }
    }

    @Test void wrongConsumptionThreadPoisonsWithoutChangingState() throws Exception {
        try (Fixture source = new Fixture()) {
            var component = component(); component.bindTo(source.observer); StepView view = source.before(0);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread thread = new Thread(() -> { try { component.accept(view); } catch (Throwable failure) { error.set(failure); } });
            thread.start(); thread.join(2_000); assertFalse(thread.isAlive());
            assertInstanceOf(IllegalArgumentException.class, error.get()); assertTrue(component.isPoisoned());
            assertTrue(component.lastAcceptedSnapshotForAudit().isEmpty());
        }
    }

    @Test void fractionalStepTimesAndEmptyDeltasRetainPrefixContentWithoutAssumingOneSecond() {
        try (Fixture source = new Fixture()) {
            var component = component(); component.bindTo(source.observer);
            component.accept(source.before(0)); source.event(release(0)); source.after(0);
            var one = component.accept(source.before(2.5)); source.after(2.5);
            var two = component.accept(source.before(8));
            assertEquals(2.5, two.position().closedThroughSeconds().orElseThrow());
            assertEquals(8, two.position().decisionTimeSeconds()); assertEquals(3, two.position().viewSequence());
            assertEquals(one.position().closedPrefixSha256(), two.position().closedPrefixSha256());
            assertEquals(one.releases().knownOrders(), two.releases().knownOrders());
        }
    }

    @Test void pendingSuffixDoesNotLeakThroughIdentityOrViewCountIntoPublicState() {
        try (Fixture left = new Fixture(); Fixture right = new Fixture()) {
            var a = component(); var b = component(); a.bindTo(left.observer); b.bindTo(right.observer);
            a.accept(left.before(0)); b.accept(right.before(0)); left.after(0); right.after(0);
            left.at(1); right.at(1); left.event(new Marker(1, "hidden-a")); left.event(new Marker(1, "hidden-b"));
            left.event(release(0)); right.event(release(0));
            var one = a.accept(left.before(1)); var two = b.accept(right.before(1));
            assertEquals(one.position(), two.position());
            assertEquals(one.releases().knownOrders(), two.releases().knownOrders());
            assertEquals(one.services().observedServices(), two.services().observedServices());
            assertEquals(one.riders().riders(), two.riders().riders());
            assertEquals(1, one.position().closedEventCount());
        }
    }

    @Test void provenanceBindingDoesNotUpgradeIncompleteRuntimeEvidence() {
        try (Fixture source = new Fixture()) {
            var component = component(); component.bindTo(source.observer); StepView view = source.before(0);
            var accepted = component.accept(view);
            assertEquals(view.evidence(), accepted.position().sourceEvidence());
            assertFalse(accepted.position().sourceEvidence().allProducersCertified());
            assertTrue(accepted.position().sourceEvidence().primaryEngineIdentityCertified());
            assertFalse(accepted.position().sourceEvidence().actualRunnerCountVerified());
            for (var constructor : ClosedCoTransportObservationState.Snapshot.class.getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()));
            }
            assertThrows(UnsupportedOperationException.class, () -> accepted.releases().knownOrders().clear());
            assertThrows(UnsupportedOperationException.class, () -> accepted.services().observedServices().clear());
            assertThrows(UnsupportedOperationException.class, () -> accepted.riders().riders().clear());
        }
    }

    @Test void invalidExternalStopAndPopulationContractsAreRejectedBeforeBinding() {
        var bad = new Contract("order", "activity-facility", "s1", 0, 10, List.of("freight"), "cargo_origin");
        assertThrows(IllegalArgumentException.class, () -> new ClosedCoTransportObservationState(20, 1,
                services(), List.of(bad), Set.of("passenger"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ClosedCoTransportObservationState(20, 1,
                services(), contracts(), Set.of("freight"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ClosedCoTransportObservationState(-1, 1,
                services(), contracts(), Set.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ClosedCoTransportObservationState(20, 0,
                services(), contracts(), Set.of(), Map.of()));
    }
}

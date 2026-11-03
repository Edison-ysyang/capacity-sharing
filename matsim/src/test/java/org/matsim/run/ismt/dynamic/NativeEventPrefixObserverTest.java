package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.EventsManagerImpl;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.mobsim.framework.events.MobsimAfterSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeCleanupEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.mobsim.qsim.QSimRuntimeInspection;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimEngineI;
import org.matsim.core.mobsim.qsim.qnetsimengine.QNetsimRuntimeInspection;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.matsim.run.ismt.dynamic.NativeEventPrefixObserver.*;

/** Manual callbacks on a real, never-run QSim: state/API tests, not native source proof. */
class NativeEventPrefixObserverTest {
    @Test void drainCoverageIsNotAnActualWorkerReceiptClockAndIsIncludedInTheHash() {
        var attributes = Map.of("time", "10.0", "type", "test");
        var event = new ReceivedEvent(1, 10, Double.NaN, "example.Event", "test", attributes,
                false, Optional.of(new ParallelEventDrainBuffer.DrainCoverage(2, 11)));
        assertEquals(11, event.availableBySeconds());
        assertThrows(IllegalStateException.class, event::receivedAtSeconds);
        assertEquals(2, event.firstCoveredByDrain().orElseThrow().sequence());
        var later = new ReceivedEvent(1, 10, Double.NaN, "example.Event", "test", attributes,
                false, Optional.of(new ParallelEventDrainBuffer.DrainCoverage(3, 12)));
        assertNotEquals(extendHash(initialPrefixHash(20, 1), event), extendHash(initialPrefixHash(20, 1), later));
        var synchronous = new ReceivedEvent(1, 10, 11, "example.Event", "test", attributes, false);
        assertEquals(11, synchronous.receivedAtSeconds());
        assertTrue(synchronous.firstCoveredByDrain().isEmpty());
        assertNotEquals(extendHash(initialPrefixHash(20, 1), event), extendHash(initialPrefixHash(20, 1), synchronous));
    }

    @Test void parallelTimingCannotPretendToHaveAReceiptClockOrCoverAFutureEvent() {
        var coverage = Optional.of(new ParallelEventDrainBuffer.DrainCoverage(1, 10));
        assertThrows(IllegalStateException.class, () -> new ReceivedEvent(1, 9, 10, "example.Event", "test", Map.of(), false, coverage));
        assertThrows(IllegalStateException.class, () -> new ReceivedEvent(1, 11, Double.NaN, "example.Event", "test", Map.of(), false, coverage));
        assertThrows(IllegalStateException.class, () -> new ReceivedEvent(1, 9, Double.NaN, "example.Event", "test", Map.of(), false));
    }

    private static QSim qsim(EventsManager manager) {
        return qsim(manager, 1);
    }

    private static QSim qsim(EventsManager manager, int constructionThreads) {
        var config = ConfigUtils.createConfig();
        config.qsim().setNumberOfThreads(constructionThreads);
        config.global().setNumberOfThreads(1);
        config.qsim().setStartTime(0);
        config.qsim().setEndTime(5);
        config.controller().setOutputDirectory(Path.of(".build-cache", "native-prefix-unit-unused").toAbsolutePath().toString());
        return new QSimBuilder(config).useDefaults().build(ScenarioUtils.createScenario(config), manager);
    }

    private static class TestEvent extends Event {
        String value;
        TestEvent(double time, String value) { super(time); this.value = value; }
        @Override public String getEventType() { return "prefix-test"; }
        @Override public Map<String, String> getAttributes() {
            var attributes = super.getAttributes(); attributes.put("value", value); return attributes;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final EventsManager manager = EventsUtils.createEventsManager();
        final QSim qsim = qsim(manager);
        final List<StepView> views = new ArrayList<>();
        final NativeEventPrefixObserver observer;
        Fixture() { this(null, true); }
        Fixture(Consumer<StepView> consumer, boolean initialize) {
            observer = NativeEventPrefixObserver.attach(qsim, 20, 1, 0,
                    consumer == null ? views::add : consumer);
            qsim.getSimTimer().setTime(0);
            if (initialize) observer.notifyMobsimInitialized(new MobsimInitializedEvent<>(qsim));
        }
        void at(double time) { qsim.getSimTimer().setTime(time); }
        void event(double occurred, String value) { manager.processEvent(new TestEvent(occurred, value)); }
        void before(double time) {
            at(time); observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(qsim, time));
        }
        void after(double time) {
            at(time); observer.notifyMobsimAfterSimStep(new MobsimAfterSimStepEvent<>(qsim, time));
        }
        StepView latest() { return views.getLast(); }
        @Override public void close() { observer.close(); }
    }

    @Test void firstViewIsUnknownAndOnlyNextBeforeClosesTheWholePriorAfterStep() {
        try (Fixture f = new Fixture()) {
            f.event(0, "initial"); f.before(0);
            assertTrue(f.latest().closedThroughSeconds().isEmpty());
            assertTrue(f.latest().newlyClosedEvents().isEmpty());
            assertEquals(0, f.latest().closedEventCount());
            f.event(0, "engine"); f.after(0); f.event(0, "late-after-listener");
            assertEquals(1, f.views.size(), "After cannot publish a prefix prematurely");
            f.before(1);
            assertEquals(0, f.latest().closedThroughSeconds().orElseThrow());
            assertEquals(List.of("initial", "engine", "late-after-listener"), values(f.latest()));
            assertEquals(List.of(1L, 2L, 3L), f.latest().newlyClosedEvents().stream().map(ReceivedEvent::closedSequence).toList());
        }
    }

    @Test void pendingCountCannotLeakThroughClosedSequenceOrHashEvenWithLateTimestampReceipt() {
        try (Fixture left = new Fixture(); Fixture right = new Fixture()) {
            for (Fixture f : List.of(left, right)) { f.before(0); f.after(0); f.at(1); }
            left.event(1, "unclosed-secret-a"); left.event(1, "unclosed-secret-b");
            // Still unclosed, so a declared old timestamp received at1 is legal in this limited layer.
            for (Fixture f : List.of(left, right)) { f.event(0, "common"); f.before(1); }
            assertEquals(left.latest().newlyClosedEvents(), right.latest().newlyClosedEvents());
            assertEquals(left.latest().closedPrefixSha256(), right.latest().closedPrefixSha256());
            assertEquals(1, left.latest().closedEventCount());
            assertEquals(1, left.latest().newlyClosedEvents().getFirst().closedSequence());
            left.after(1); left.before(2);
            assertEquals(List.of("unclosed-secret-a", "unclosed-secret-b"), values(left.latest()));
            assertEquals(List.of(2L, 3L), left.latest().newlyClosedEvents().stream().map(ReceivedEvent::closedSequence).toList());
        }
    }

    @Test void closureUsesRecordedStepTimesNotAnAssumedOneSecondStep() {
        try (Fixture f = new Fixture()) {
            f.before(0); f.after(0); f.before(2.5);
            f.event(1.75, "fractional"); f.after(2.5); f.before(8);
            assertEquals(2.5, f.latest().closedThroughSeconds().orElseThrow());
            assertEquals(1.75, f.latest().newlyClosedEvents().getFirst().occurredAtSeconds());
            assertEquals(2.5, f.latest().newlyClosedEvents().getFirst().receivedAtSeconds());
        }
    }

    @Test void nativeEventMutationCannotChangeAnIssuedOrPendingCopy() {
        try (Fixture f = new Fixture()) {
            TestEvent event = new TestEvent(0, "original");
            f.manager.processEvent(event); event.setTime(999); event.value = "changed";
            f.before(0); f.after(0); f.before(1);
            var view = f.latest(); var record = view.newlyClosedEvents().getFirst();
            assertEquals(0, record.occurredAtSeconds()); assertEquals("original", record.attributes().get("value"));
            assertThrows(UnsupportedOperationException.class, () -> record.attributes().put("value", "rewrite"));
            assertThrows(UnsupportedOperationException.class, () -> view.newlyClosedEvents().clear());
            f.after(1); f.before(2); assertTrue(f.latest().newlyClosedEvents().isEmpty());
            assertEquals(1, view.newlyClosedEvents().size());
        }
    }

    @Test void sourceReceptionCanUseAnotherThreadButThatIsNotProducerCertification() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> { try { f.event(0, "worker"); } catch (Throwable e) { failure.set(e); } });
            worker.start(); worker.join(2_000); assertFalse(worker.isAlive()); assertNull(failure.get());
            f.before(0); f.after(0); f.before(1);
            assertFalse(f.latest().newlyClosedEvents().getFirst().receivedOnControlThread());
            var evidence = f.latest().evidence();
            assertTrue(evidence.actualManagerChecked()); assertTrue(evidence.registeredEngineClassChecked());
            assertTrue(evidence.activeNetworkIdentityChecked()); assertTrue(evidence.singleRunnerConfigurationDeclared());
            assertTrue(evidence.primaryEngineIdentityCertified()); assertTrue(evidence.singleRunnerConstructionChecked());
            assertFalse(evidence.actualRunnerCountVerified(), "Manual callback cannot create a real runner list");
            assertFalse(evidence.allProducersCertified());
        }
    }

    @Test void futureEventPoisonsWithoutPublishingIt() {
        try (Fixture f = new Fixture()) {
            assertThrows(RuntimeException.class, () -> f.event(1, "future"));
            assertTrue(f.observer.isPoisoned()); assertTrue(f.views.isEmpty());
            assertThrows(IllegalStateException.class, () -> f.before(0));
        }
    }

    @Test void sameNetworkReplacementCannotImpersonateTheActualPrimaryEngine() {
        QSim qsim = qsim(EventsUtils.createEventsManager());
        var engine = qsim.getChildInjector().getInstance(QNetsimEngineI.class);
        var replacement = mock(QNetsimEngineI.class);
        when(replacement.getNetsimNetwork()).thenReturn(engine.getNetsimNetwork());
        qsim.addMobsimEngine(replacement);
        assertSame(engine.getNetsimNetwork(), qsim.getNetsimNetwork());
        assertThrows(IllegalStateException.class, () -> NativeEventPrefixObserver.attach(qsim, 20, 1, 0, ignored -> { }));
    }

    @Test void aLaterPrimaryReplacementPoisonsBeforePublishingAnotherView() {
        try (Fixture f = new Fixture()) {
            f.before(0); f.after(0);
            var replacement = mock(QNetsimEngineI.class);
            when(replacement.getNetsimNetwork()).thenReturn(f.qsim.getNetsimNetwork());
            f.qsim.addMobsimEngine(replacement);
            assertThrows(IllegalStateException.class, () -> f.before(1));
            assertTrue(f.observer.isPoisoned()); assertEquals(1, f.views.size());
        }
    }

    @Test void lateClosedEventCannotReviseAView() {
        try (Fixture f = new Fixture()) {
            f.before(0); f.after(0); f.before(1);
            String hash = f.latest().closedPrefixSha256();
            assertThrows(RuntimeException.class, () -> f.event(0, "late"));
            assertTrue(f.observer.isPoisoned()); assertEquals(hash, f.latest().closedPrefixSha256());
            assertEquals(0, f.latest().closedEventCount());
        }
    }

    @Test void preInitializationEventsAndASecondInitializationAreRejected() {
        try (Fixture f = new Fixture(null, false)) {
            assertThrows(RuntimeException.class, () -> f.event(0, "early")); assertTrue(f.observer.isPoisoned());
        }
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> f.observer.notifyMobsimInitialized(new MobsimInitializedEvent<>(f.qsim)));
        }
    }

    @Test void preparationOptInCannotBeRequestedOnANeverRunQsimAndHasNoRegistrationSideEffects() {
        var manager = (EventsManagerImpl) EventsUtils.createEventsManager(); QSim qsim = qsim(manager);
        var components = QSimRuntimeInspection.inspect(qsim);
        var handlers = manager.inspectRuntimeRegistrations(List.of(Event.class));
        assertFalse(QSimRuntimeInspection.isAtPreparationStart(qsim));
        assertThrows(IllegalStateException.class,
                () -> NativeEventPrefixObserver.attachForPreparation(qsim, 20, 1, 0,
                        ignored -> { throw new AssertionError("No view may be delivered"); }));
        assertTrue(components.sameComponentsAs(QSimRuntimeInspection.inspect(qsim)));
        assertTrue(handlers.sameRegistrationsAs(manager.inspectRuntimeRegistrations(List.of(Event.class))));
        assertTrue(qsim.getAgents().isEmpty());
    }

    @Test void manualInitializationDoesNotAuthorizePreparationAttachment() {
        try (Fixture f = new Fixture()) {
            assertFalse(QSimRuntimeInspection.isAtPreparationStart(f.qsim));
            assertFalse(QSimRuntimeInspection.isAtPreparationCheck(f.qsim));
            assertThrows(IllegalStateException.class,
                    () -> NativeEventPrefixObserver.attachForPreparation(f.qsim, 20, 1, 0, ignored -> { }));
            f.before(0); f.after(0); f.before(1);
            assertEquals(2, f.views.size(), "The rejected opt-in did not alter the existing observer");
            assertFalse(f.observer.isPoisoned());
        }
    }

    @Test void missingOrRepeatedStepMarkersFailClosed() {
        try (Fixture f = new Fixture()) {
            f.before(0); assertThrows(IllegalStateException.class, () -> f.before(1)); assertTrue(f.observer.isPoisoned());
        }
        try (Fixture f = new Fixture()) {
            f.before(0); f.after(0); assertThrows(IllegalStateException.class, () -> f.after(0));
        }
        try (Fixture f = new Fixture()) {
            f.before(0); assertThrows(IllegalStateException.class, () -> f.before(0));
        }
    }

    @Test void mismatchedNativeClockAndQsimCannotCloseObservations() {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> f.observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(f.qsim, 1)));
        }
        try (Fixture f = new Fixture(); Fixture other = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> f.observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(other.qsim, 0)));
        }
    }

    @Test void onlyTheOwnerThreadCanCloseAPrefixOrDetach() throws Exception {
        for (boolean detach : List.of(false, true)) {
            try (Fixture f = new Fixture()) {
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread worker = new Thread(() -> {
                    try { if (detach) f.observer.close(); else f.before(0); }
                    catch (Throwable e) { failure.set(e); }
                });
                worker.start(); worker.join(2_000); assertFalse(worker.isAlive());
                assertInstanceOf(IllegalStateException.class, failure.get()); assertTrue(f.observer.isPoisoned());
            }
        }
    }

    @Test void callbackFailureAndCaughtReentrantFailureBothPoisonTheOuterDelivery() {
        try (Fixture f = new Fixture(view -> { throw new IllegalArgumentException("consumer failure"); }, true)) {
            assertThrows(IllegalArgumentException.class, () -> f.before(0)); assertTrue(f.observer.isPoisoned());
        }
        AtomicReference<Fixture> holder = new AtomicReference<>();
        try (Fixture f = new Fixture(view -> assertThrows(IllegalStateException.class, () -> holder.get().before(0)), true)) {
            holder.set(f);
            assertThrows(IllegalStateException.class, () -> f.before(0)); assertTrue(f.observer.isPoisoned());
        }
    }

    @Test void recursiveFieldCopyCannotAppendAPartialEvent() {
        try (Fixture f = new Fixture()) {
            Event recursive = new TestEvent(0, "outer") {
                @Override public Map<String, String> getAttributes() {
                    f.observer.handleEvent(new TestEvent(0, "inner")); return super.getAttributes();
                }
            };
            assertThrows(IllegalStateException.class, () -> f.observer.handleEvent(recursive));
            f.observer.close(); assertEquals(0, f.observer.closeReceipt().receivedEvents());
            assertTrue(f.observer.closeReceipt().poisoned());
        }
    }

    @Test void differentEpochRequiresANewObserverAndIndependentPrefixIdentity() {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> f.observer.reset(21));
        }
        try (Fixture f = new Fixture(null, false)) {
            f.observer.reset(20);
            f.observer.notifyMobsimInitialized(new MobsimInitializedEvent<>(f.qsim));
            assertThrows(IllegalStateException.class, () -> f.observer.reset(20));
        }
        try (Fixture first = new Fixture()) {
            first.before(0);
            QSim nextQsim = qsim(EventsUtils.createEventsManager());
            List<StepView> nextViews = new ArrayList<>();
            try (NativeEventPrefixObserver next = NativeEventPrefixObserver.attach(nextQsim, 20, 2, 0, nextViews::add)) {
                nextQsim.getSimTimer().setTime(0);
                next.notifyMobsimInitialized(new MobsimInitializedEvent<>(nextQsim));
                next.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent<>(nextQsim, 0));
                assertEquals(1, first.latest().epoch()); assertEquals(2, nextViews.getFirst().epoch());
                assertNotEquals(first.latest().closedPrefixSha256(), nextViews.getFirst().closedPrefixSha256(),
                        "Even empty prefixes must retain their distinct epoch identities");
                assertTrue(nextViews.getFirst().closedThroughSeconds().isEmpty());
            }
        }
    }

    @Test void unsupportedManagerConfigAndChangedPrimaryNetworkAreRejected() {
        EventsManager asynchronous = EventsUtils.createParallelEventsManager();
        QSim qsim = qsim(asynchronous);
        assertThrows(IllegalStateException.class, () -> NativeEventPrefixObserver.attach(qsim, 20, 1, 0, ignored -> { }));
        try (Fixture f = new Fixture()) {
            f.qsim.getScenario().getConfig().qsim().setNumberOfThreads(2);
            assertThrows(IllegalStateException.class, () -> f.before(0));
        }
        try (Fixture f = new Fixture()) {
            f.qsim.addMobsimEngine(mock(QNetsimEngineI.class));
            assertThrows(IllegalStateException.class, () -> f.before(0));
        }
    }

    @Test void invalidIdentityParametersAreRejectedBeforeAttaching() {
        EventsManager events = EventsUtils.createEventsManager(); QSim qsim = qsim(events);
        assertThrows(IllegalArgumentException.class, () -> NativeEventPrefixObserver.attach(qsim, -1, 1, 0, ignored -> { }));
        assertThrows(IllegalArgumentException.class, () -> NativeEventPrefixObserver.attach(qsim, 20, 0, 0, ignored -> { }));
        assertThrows(IllegalStateException.class, () -> NativeEventPrefixObserver.attach(qsim, 20, 1, -1, ignored -> { }));
    }

    @Test void configurationResetDoesNotEraseTwoRunnerConstructionOrUnwrapItsNativeManager() {
        EventsManager events = EventsUtils.createEventsManager();
        QSim qsim = qsim(events, 2);
        // Force native construction before mutating the public configuration.
        var engine = qsim.getChildInjector().getInstance(QNetsimEngineI.class);
        qsim.getScenario().getConfig().qsim().setNumberOfThreads(1);
        assertEquals(2, QNetsimRuntimeInspection.inspect(engine).constructionRunnerCount());
        assertNotSame(events, qsim.getEventsManager(), "Native multi-runner QSim wraps the supplied manager");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> NativeEventPrefixObserver.attach(qsim, 20, 1, 0, ignored -> { }));
        // This native construction fails the earlier exact-manager gate. Do not
        // fabricate an unwrapped manager to claim isolated coverage of a later gate.
        assertTrue(failure.getMessage().contains("wrappers/async"), failure::getMessage);
        assertTrue(qsim.getAgents().isEmpty(), "This counterexample never executes QSim");
    }

    @Test void duplicatePrimaryRegistrationIsRejectedBeforeAttachmentAndAfterInitialization() {
        EventsManager events = EventsUtils.createEventsManager();
        QSim qsim = qsim(events);
        qsim.addMobsimEngine(qsim.getChildInjector().getInstance(QNetsimEngineI.class));
        assertThrows(IllegalStateException.class,
                () -> NativeEventPrefixObserver.attach(qsim, 20, 1, 0, ignored -> { }));
        try (Fixture f = new Fixture()) {
            f.before(0); f.after(0);
            f.qsim.addMobsimEngine(f.qsim.getChildInjector().getInstance(QNetsimEngineI.class));
            assertThrows(IllegalStateException.class, () -> f.before(1));
            assertTrue(f.observer.isPoisoned());
            assertEquals(1, f.views.size(), "Duplicate engine cannot publish another view");
        }
    }

    @Test void cleanupDetachesAndReportsUnpublishedTailWithoutCertifyingTerminalCompleteness() {
        try (Fixture f = new Fixture()) {
            List<Event> otherHandlerEvents = new ArrayList<>();
            f.manager.addHandler((BasicEventHandler) otherHandlerEvents::add);
            f.event(0, "published"); f.before(0); f.after(0); f.before(1); f.event(1, "unpublished-last-step");
            assertThrows(IllegalStateException.class, f.observer::closeReceipt);
            f.observer.notifyMobsimBeforeCleanup(new MobsimBeforeCleanupEvent<>(f.qsim));
            var receipt = f.observer.closeReceipt();
            assertEquals(2, receipt.receivedEvents()); assertEquals(1, receipt.closedEvents());
            assertEquals(1, receipt.unpublishedEvents()); assertFalse(receipt.terminalPrefixCertified());
            f.event(1, "native-cleanup-tail");
            assertEquals(3, otherHandlerEvents.size()); assertEquals(receipt, f.observer.closeReceipt());
            assertFalse(f.observer.isPoisoned());
        }
    }

    @Test void wrongQsimCleanupPoisonsWithoutDetachingTheActualObserver() {
        try (Fixture f = new Fixture(); Fixture other = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> f.observer.notifyMobsimBeforeCleanup(new MobsimBeforeCleanupEvent<>(other.qsim)));
            assertTrue(f.observer.isPoisoned());
            assertThrows(IllegalStateException.class, f.observer::closeReceipt);
        }
    }

    private static List<String> values(StepView view) {
        return view.newlyClosedEvents().stream().map(event -> event.attributes().get("value")).toList();
    }
}

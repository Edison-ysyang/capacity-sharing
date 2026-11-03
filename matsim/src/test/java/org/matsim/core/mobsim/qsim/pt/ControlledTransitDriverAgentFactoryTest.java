package org.matsim.core.mobsim.qsim.pt;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.core.mobsim.qsim.QSim;
import org.matsim.core.mobsim.qsim.interfaces.Netsim;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.Umlauf;
import org.matsim.pt.UmlaufImpl;
import org.matsim.pt.UmlaufStueck;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Limits;
import org.matsim.run.ismt.dynamic.DispatchCommitmentState.Version;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.core.mobsim.qsim.pt.ControlledTransitDriverAgentFactory.ActiveContext;
import static org.mockito.Mockito.*;

/** Construction/selection tests only; actual queued dispatch has separate native microfixtures. */
class ControlledTransitDriverAgentFactoryTest {
    private static final Limits NONZERO = new Limits(30, 30, 10);

    private static final class Fixture {
        final Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        final QSim qsim = mock(QSim.class);
        final EventsManager events = mock(EventsManager.class);
        final InternalInterface internal = mock(InternalInterface.class);
        final TransitStopAgentTracker tracker = new TransitStopAgentTracker(EventsUtils.createEventsManager());

        Fixture() {
            when(qsim.getScenario()).thenReturn(scenario);
            when(qsim.getEventsManager()).thenReturn(events);
            when(internal.getMobsim()).thenReturn(qsim);
        }

        Umlauf work(String suffix) {
            var factory = scenario.getTransitSchedule().getFactory();
            var link = Id.createLinkId("factory-link");
            var facility = factory.createTransitStopFacility(Id.create("factory-stop", TransitStopFacility.class), new Coord(0, 0), false);
            facility.setLinkId(link);
            var stop = factory.createTransitRouteStop(facility, 0, 10);
            TransitRoute route = factory.createTransitRoute(Id.create("route-" + suffix, TransitRoute.class),
                    RouteUtils.createLinkNetworkRouteImpl(link, link), List.of(stop), "pt");
            Departure departure = factory.createDeparture(Id.create("departure-" + suffix, Departure.class), 100);
            route.addDeparture(departure);
            TransitLine line = factory.createTransitLine(Id.create("factory-line", TransitLine.class));
            line.addRoute(route);
            UmlaufImpl work = new UmlaufImpl(Id.create("factory-work-" + suffix, Umlauf.class));
            work.getUmlaufStuecke().add(new UmlaufStueck(line, route, departure));
            work.setVehicleId(Id.createVehicleId("factory-vehicle-" + suffix));
            return work;
        }
    }

    private Supplier<ActiveContext> forbiddenContext() {
        return () -> { throw new AssertionError("A native bypass must not evaluate its context supplier"); };
    }

    private void assertNativeBypass(boolean enabled, int iteration, int activation, Limits limits, String suffix) {
        Fixture f = new Fixture();
        Umlauf work = f.work(suffix);
        ControlledTransitDriverAgentFactory factory = new ControlledTransitDriverAgentFactory(enabled, iteration, activation, limits, forbiddenContext());
        assertFalse(factory.isControlActive());
        AbstractTransitDriverAgent driver = factory.createTransitDriver(work, f.internal, f.tracker);
        assertEquals(TransitDriverAgentImpl.class, driver.getClass());
        assertEquals("pt_" + work.getId(), driver.getId().toString());
        assertSame(driver.getId(), driver.getPerson().getId());
        assertEquals(100, driver.getActivityEndTime());
        verify(f.qsim, never()).rescheduleActivityEnd(any());
        verify(f.internal, never()).arrangeNextAgentState(any());
        verify(f.events, never()).processEvent(any(Event.class));
    }

    @Test void disabledControllerUsesExactNativeClassWithoutContext() {
        assertNativeBypass(false, 199, 20, NONZERO, "disabled");
    }

    @Test void wholeCommonHistoryUsesExactNativeClassWithoutContext() {
        for (int iteration = 0; iteration < 20; iteration++) {
            assertNativeBypass(true, iteration, 20, NONZERO, "preactivation-" + iteration);
        }
    }

    @Test void zeroAmplitudeUsesExactNativeClassEvenAfterActivation() {
        assertNativeBypass(true, 20, 20, new Limits(0, 0, 10), "zero");
    }

    @Test void bypassAllowsAbsentActiveContextSupplier() {
        Fixture f = new Fixture();
        for (ControlledTransitDriverAgentFactory factory : List.of(
                new ControlledTransitDriverAgentFactory(false, 199, 20, NONZERO, null),
                new ControlledTransitDriverAgentFactory(true, 19, 20, NONZERO, null),
                new ControlledTransitDriverAgentFactory(true, 199, 20, new Limits(0, 0, 10), null))) {
            assertEquals(TransitDriverAgentImpl.class,
                    factory.createTransitDriver(f.work("null-context"), f.internal, f.tracker).getClass());
        }
    }

    @Test void bypassHasExactlyTheNativeConstructorInteractionTrace() {
        Fixture f = new Fixture();
        Umlauf work = f.work("native-call-trace");
        clearInvocations(f.internal, f.qsim);
        new DefaultTransitDriverAgentFactory().createTransitDriver(work, f.internal, f.tracker);
        List<String> nativeInternal = mockingDetails(f.internal).getInvocations().stream().map(call -> call.getMethod().getName()).toList();
        List<String> nativeQsim = mockingDetails(f.qsim).getInvocations().stream().map(call -> call.getMethod().getName()).toList();
        clearInvocations(f.internal, f.qsim);
        new ControlledTransitDriverAgentFactory(false, 199, 20, NONZERO, forbiddenContext())
                .createTransitDriver(work, f.internal, f.tracker);
        assertEquals(nativeInternal, mockingDetails(f.internal).getInvocations().stream().map(call -> call.getMethod().getName()).toList());
        assertEquals(nativeQsim, mockingDetails(f.qsim).getInvocations().stream().map(call -> call.getMethod().getName()).toList());
    }

    @Test void bypassCreatesOneNewNativePersonIdentityAndNoAdditionalTypedIds() {
        Fixture f = new Fixture();
        Umlauf work = f.work("identity-count-only");
        int people = Id.getNumberOfIds(Person.class);
        int vehicles = Id.getNumberOfIds(org.matsim.vehicles.Vehicle.class);
        int departures = Id.getNumberOfIds(Departure.class);
        int lines = Id.getNumberOfIds(TransitLine.class);
        int routes = Id.getNumberOfIds(TransitRoute.class);
        new ControlledTransitDriverAgentFactory(false, 199, 20, NONZERO, forbiddenContext())
                .createTransitDriver(work, f.internal, f.tracker);
        assertEquals(people + 1, Id.getNumberOfIds(Person.class));
        assertEquals(vehicles, Id.getNumberOfIds(org.matsim.vehicles.Vehicle.class));
        assertEquals(departures, Id.getNumberOfIds(Departure.class));
        assertEquals(lines, Id.getNumberOfIds(TransitLine.class));
        assertEquals(routes, Id.getNumberOfIds(TransitRoute.class));
    }

    @Test void activeContextIsLazyAndReusedForMultipleNativeWorkSequences() {
        Fixture f = new Fixture();
        AtomicInteger supplierCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        ActiveContext context = new ActiveContext(key -> {
            providerCalls.incrementAndGet();
            return Optional.of(new ControlledTransitDriverAgent.AcceptedCommitment(80, new Version(7, 2)));
        }, 90, 7);
        ControlledTransitDriverAgentFactory factory = new ControlledTransitDriverAgentFactory(true, 20, 20, NONZERO,
                () -> { supplierCalls.incrementAndGet(); return context; });
        assertTrue(factory.isControlActive());
        assertEquals(0, supplierCalls.get());
        assertEquals(0, providerCalls.get());
        AbstractTransitDriverAgent first = factory.createTransitDriver(f.work("active-first"), f.internal, f.tracker);
        AbstractTransitDriverAgent second = factory.createTransitDriver(f.work("active-second"), f.internal, f.tracker);
        assertEquals(ControlledTransitDriverAgent.class, first.getClass());
        assertEquals(ControlledTransitDriverAgent.class, second.getClass());
        assertNotSame(first, second);
        assertEquals(1, supplierCalls.get());
        assertEquals(2, providerCalls.get());
        for (AbstractTransitDriverAgent driver : List.of(first, second)) {
            assertEquals(90, driver.getActivityEndTime());
            assertEquals(Optional.of(new Version(7, 2)), ((ControlledTransitDriverAgent) driver).currentExecutionState().acceptedVersion());
        }
        verify(f.qsim, never()).rescheduleActivityEnd(any());
    }

    @Test void positiveOperatingControlRemainsActiveAt150And199() {
        Fixture f = new Fixture();
        for (int iteration : new int[]{20, 149, 150, 170, 199}) {
            AtomicInteger calls = new AtomicInteger();
            ControlledTransitDriverAgentFactory factory = new ControlledTransitDriverAgentFactory(true, iteration, 20, NONZERO,
                    () -> { calls.incrementAndGet(); return new ActiveContext(key -> Optional.empty(), 0, iteration + 1L); });
            assertEquals(ControlledTransitDriverAgent.class,
                    factory.createTransitDriver(f.work("iteration-" + iteration), f.internal, f.tracker).getClass());
            assertEquals(1, calls.get());
        }
    }

    @Test void eitherPositiveAdjustmentDirectionActivatesControl() {
        Fixture f = new Fixture();
        for (Limits limits : List.of(new Limits(30, 0, 10), new Limits(0, 30, 10))) {
            ControlledTransitDriverAgentFactory factory = new ControlledTransitDriverAgentFactory(true, 20, 20, limits,
                    () -> new ActiveContext(key -> Optional.empty(), 0, 1));
            assertEquals(ControlledTransitDriverAgent.class,
                    factory.createTransitDriver(f.work("one-direction"), f.internal, f.tracker).getClass());
        }
    }

    @Test void activeFactoryCannotLeakContextIntoAnotherQsimInstance() {
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        ControlledTransitDriverAgentFactory factory = new ControlledTransitDriverAgentFactory(true, 20, 20, NONZERO,
                () -> { calls.incrementAndGet(); return new ActiveContext(key -> Optional.empty(), 0, 1); });
        factory.createTransitDriver(first.work("owner-first"), first.internal, first.tracker);
        assertThrows(IllegalStateException.class,
                () -> factory.createTransitDriver(second.work("owner-second"), second.internal, second.tracker));
        assertEquals(1, calls.get());
    }

    @Test void activeFactoryRejectsNonLocalMobsimBeforeQueryingContext() {
        Fixture f = new Fixture();
        InternalInterface foreign = mock(InternalInterface.class);
        when(foreign.getMobsim()).thenReturn(mock(Netsim.class));
        ControlledTransitDriverAgentFactory factory = new ControlledTransitDriverAgentFactory(true, 20, 20, NONZERO, forbiddenContext());
        assertThrows(IllegalArgumentException.class, () -> factory.createTransitDriver(f.work("foreign"), foreign, f.tracker));
    }

    @Test void invalidSettingsAndActiveContextsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ControlledTransitDriverAgentFactory(true, -1, 20, NONZERO, forbiddenContext()));
        assertThrows(IllegalArgumentException.class, () -> new ControlledTransitDriverAgentFactory(true, 20, -1, NONZERO, forbiddenContext()));
        assertThrows(NullPointerException.class, () -> new ControlledTransitDriverAgentFactory(false, 20, 20, null, null));
        assertThrows(NullPointerException.class, () -> new ControlledTransitDriverAgentFactory(true, 20, 20, NONZERO, null));
        assertThrows(NullPointerException.class, () -> new ActiveContext(null, 0, 1));
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new ActiveContext(key -> Optional.empty(), invalid, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> new ActiveContext(key -> Optional.empty(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ActiveContext(key -> Optional.empty(), 0, -1));
    }

    @Test void nullActiveContextIsRejectedOnlyWhenActiveDriverIsRequested() {
        Fixture f = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        ControlledTransitDriverAgentFactory factory = new ControlledTransitDriverAgentFactory(true, 20, 20, NONZERO,
                () -> { calls.incrementAndGet(); return null; });
        assertEquals(0, calls.get());
        assertThrows(NullPointerException.class, () -> factory.createTransitDriver(f.work("null-active"), f.internal, f.tracker));
        assertEquals(1, calls.get());
        assertThrows(IllegalStateException.class, () -> factory.createTransitDriver(f.work("null-active-retry"), f.internal, f.tracker));
        assertEquals(1, calls.get(), "A failed lazy context is not silently replaced by querying the supplier again");
    }
}

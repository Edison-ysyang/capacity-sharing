package org.matsim.core.events;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.mobsim.qsim.pt.PersonEntersPtVehicleEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.core.events.EventsManagerImpl.HandlerGroupDescription;
import org.matsim.core.events.EventsManagerImpl.RuntimeInspectionSnapshot;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.core.utils.misc.Counter;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** These are registration/dispatch inspection tests, not producer or concurrency-sealing proofs. */
class EventsManagerRuntimeInspectionTest {
    @Test
    void nativePtSubclassDispatchesBeforeItsParentAndBasicDespiteOppositeGroupOrder() {
        var manager = new EventsManagerImpl();
        var calls = new ArrayList<String>();
        manager.addHandler(new BasicLog(calls, "basic"));
        manager.addHandler(new EntryLog(calls, "parent"));
        manager.addHandler(new PtEntryLog(calls));
        var snapshot = manager.inspectRuntimeRegistrations(List.of(PersonEntersPtVehicleEvent.class, Event.class));
        assertEquals(List.of(Event.class.getName(), PersonEntersVehicleEvent.class.getName(), PersonEntersPtVehicleEvent.class.getName()),
                snapshot.registeredGroups().stream().map(HandlerGroupDescription::eventClass).toList());
        var pt = snapshot.dispatches().getFirst();
        assertEquals(PersonEntersPtVehicleEvent.class.getName(), pt.eventClass());
        assertEquals(List.of(2, 1, 0), pt.handlers().stream().map(EventsManagerImpl.DispatchRegistrationDescription::groupIndex).toList());
        assertEquals(List.of(0, 1, 2), pt.handlers().stream().map(EventsManagerImpl.DispatchRegistrationDescription::dispatchPosition).toList());
        assertEquals(List.of(0, 0, 0), pt.handlers().stream().map(EventsManagerImpl.DispatchRegistrationDescription::registrationPosition).toList());
        assertEquals(List.of(PersonEntersPtVehicleEvent.class.getName(), PersonEntersVehicleEvent.class.getName(), Event.class.getName()),
                pt.handlers().stream().map(EventsManagerImpl.DispatchRegistrationDescription::registeredEventClass).toList());
        assertEquals(PtEntryHandler.class.getName(), pt.handlers().getFirst().method().declaringClass());
        assertEquals("handleEvent", pt.handlers().getFirst().method().methodName());
        assertEquals(List.of(PersonEntersPtVehicleEvent.class.getName()), pt.handlers().getFirst().method().parameterTypes());
        assertEquals("void", pt.handlers().getFirst().method().returnType());
        assertEquals(1, snapshot.dispatches().get(1).handlers().size());
        assertTrue(calls.isEmpty(), "Inspection must not invoke any handler");
        manager.processEvent(ptEvent(10));
        assertEquals(List.of("pt", "parent", "basic"), calls);
    }

    @Test
    void duplicateRegistrationsAndNativeSingleOccurrenceRemovalStayVisible() {
        var manager = new EventsManagerImpl();
        var calls = new ArrayList<String>();
        var dual = new DualLog(calls);
        manager.addHandler(dual);
        manager.addHandler(dual);
        var duplicate = manager.inspectRuntimeRegistrations(List.of(PersonEntersVehicleEvent.class));
        assertEquals(2, duplicate.registeredGroups().size());
        for (var group : duplicate.registeredGroups()) {
            assertEquals(List.of(0, 1), group.handlers().stream().map(EventsManagerImpl.HandlerRegistrationDescription::registrationPosition).toList());
            assertEquals(List.of(0, 0), group.handlers().stream().map(EventsManagerImpl.HandlerRegistrationDescription::handlerIdentityGroup).toList());
        }
        assertEquals(4, duplicate.dispatches().getFirst().handlers().size());
        manager.processEvent(entryEvent(1));
        assertEquals(List.of("dual-parent", "dual-parent", "dual-basic", "dual-basic"), calls);
        manager.resetHandlers(7);
        assertEquals(1, dual.resets, "Existing reset semantics still reset the same object only once");
        assertEquals(7, dual.lastResetIteration);
        manager.removeHandler(dual);
        var once = manager.inspectRuntimeRegistrations(List.of(PersonEntersVehicleEvent.class));
        assertFalse(duplicate.sameRegistrationsAs(once));
        assertEquals(2, once.dispatches().getFirst().handlers().size());
        calls.clear(); manager.processEvent(entryEvent(2));
        assertEquals(List.of("dual-parent", "dual-basic"), calls);
        manager.removeHandler(dual);
        var empty = manager.inspectRuntimeRegistrations(List.of(PersonEntersVehicleEvent.class));
        assertEquals(2, empty.registeredGroups().size(), "Removal leaves native registration groups in place");
        assertTrue(empty.registeredGroups().stream().allMatch(group -> group.handlers().isEmpty()));
        assertTrue(empty.dispatches().getFirst().handlers().isEmpty());
        assertEquals(4, duplicate.dispatches().getFirst().handlers().size(), "Earlier snapshots are detached");
    }

    @Test
    void oneObjectAcrossTypedAndBasicRolesHasOneSnapshotIdentityGroup() {
        var manager = new EventsManagerImpl();
        var calls = new ArrayList<String>();
        var dual = new DualLog(calls);
        manager.addHandler(new BasicLog(calls, "first"));
        manager.addHandler(dual);
        var snapshot = manager.inspectRuntimeRegistrations(List.of(PersonEntersVehicleEvent.class));
        var basic = group(snapshot, Event.class);
        var typed = group(snapshot, PersonEntersVehicleEvent.class);
        assertNotEquals(basic.handlers().getFirst().handlerIdentityGroup(), basic.handlers().get(1).handlerIdentityGroup());
        assertEquals(basic.handlers().get(1).handlerIdentityGroup(), typed.handlers().getFirst().handlerIdentityGroup());
        assertEquals(3, snapshot.dispatches().getFirst().handlers().size(), "Aliasing must not deduplicate real dispatch occurrences");
        manager.processEvent(entryEvent(1));
        assertEquals(List.of("dual-parent", "first", "dual-basic"), calls);
    }

    @Test
    void sameClassReplacementHasEqualDescriptionsButDifferentPrivateIdentity() {
        var manager = new EventsManagerImpl();
        var calls = new ArrayList<String>();
        var first = new BasicLog(calls, "one");
        var replacement = new BasicLog(calls, "two");
        manager.addHandler(first);
        var before = manager.inspectRuntimeRegistrations(List.of(Event.class));
        manager.removeHandler(first); manager.addHandler(replacement);
        var after = manager.inspectRuntimeRegistrations(List.of(Event.class));
        assertEquals(before.registeredGroups(), after.registeredGroups());
        assertEquals(before.dispatches(), after.dispatches());
        assertFalse(before.sameRegistrationsAs(after));
        assertFalse(after.sameRegistrationsAs(before));
        manager.processEvent(entryEvent(1));
        assertEquals(List.of("two"), calls);
    }

    @Test
    void reorderedSameClassInstancesCannotPassThroughEqualPublicLabels() {
        var manager = new EventsManagerImpl();
        var calls = new ArrayList<String>();
        var first = new BasicLog(calls, "one");
        var second = new BasicLog(calls, "two");
        manager.addHandler(first); manager.addHandler(second);
        var before = manager.inspectRuntimeRegistrations(List.of(Event.class));
        manager.removeHandler(first); manager.addHandler(first);
        var after = manager.inspectRuntimeRegistrations(List.of(Event.class));
        assertEquals(before.registeredGroups(), after.registeredGroups());
        assertEquals(before.dispatches(), after.dispatches());
        assertFalse(before.sameRegistrationsAs(after));
        manager.processEvent(entryEvent(1));
        assertEquals(List.of("two", "one"), calls);
    }

    @Test
    void privateIdentityComparisonNeverUsesHandlerEqualityHashCodeOrNames() {
        var manager = new EventsManagerImpl();
        var first = new EqualityTrap();
        var second = new EqualityTrap();
        manager.addHandler(first); manager.addHandler(second);
        var before = manager.inspectRuntimeRegistrations(List.of(Event.class));
        var after = manager.inspectRuntimeRegistrations(List.of(PersonEntersPtVehicleEvent.class));
        assertTrue(before.sameRegistrationsAs(after));
        assertEquals(List.of(0, 1), before.registeredGroups().getFirst().handlers().stream()
                .map(EventsManagerImpl.HandlerRegistrationDescription::handlerIdentityGroup).toList());
        assertEquals(0, first.calls); assertEquals(0, second.calls);
    }

    @Test
    void allRegisteredGroupsAreIncludedEvenWhenOnlyBasicDispatchIsRequested() {
        var manager = new EventsManagerImpl();
        var calls = new ArrayList<String>();
        manager.addHandler(new BasicLog(calls, "basic"));
        var before = manager.inspectRuntimeRegistrations(List.of(Event.class));
        manager.addHandler(new EntryLog(calls, "typed"));
        var after = manager.inspectRuntimeRegistrations(List.of(Event.class));
        assertEquals(before.dispatches(), after.dispatches(), "Event-only flattening does not dispatch subclass handlers");
        assertEquals(2, after.registeredGroups().size());
        assertFalse(before.sameRegistrationsAs(after), "Complete private registration comparison still detects the typed addition");
    }

    @Test
    void dispatchQueryChoiceAndDuplicatesDoNotChangeRegistrationEquality() {
        var manager = new EventsManagerImpl();
        manager.addHandler(new DualLog(new ArrayList<>()));
        var emptyQuery = manager.inspectRuntimeRegistrations(List.of());
        var repeatedQuery = manager.inspectRuntimeRegistrations(List.of(Event.class, PersonEntersVehicleEvent.class, Event.class));
        assertTrue(emptyQuery.dispatches().isEmpty());
        assertEquals(3, repeatedQuery.dispatches().size());
        assertEquals(repeatedQuery.dispatches().getFirst(), repeatedQuery.dispatches().getLast());
        assertTrue(emptyQuery.sameRegistrationsAs(repeatedQuery));
        assertTrue(repeatedQuery.sameRegistrationsAs(emptyQuery));
        assertFalse(emptyQuery.sameRegistrationsAs(null));
    }

    @Test
    void sameHandlersInAnotherManagerDoNotHaveTheSameSourceIdentity() {
        var first = new EventsManagerImpl();
        var second = new EventsManagerImpl();
        var handler = new BasicLog(new ArrayList<>(), "shared");
        first.addHandler(handler); second.addHandler(handler);
        var one = first.inspectRuntimeRegistrations(List.of(Event.class));
        var two = second.inspectRuntimeRegistrations(List.of(Event.class));
        assertEquals(one.registeredGroups(), two.registeredGroups());
        assertEquals(one.dispatches(), two.dispatches());
        assertFalse(one.sameRegistrationsAs(two));
    }

    @Test
    void detachedPublicDescriptionsAndRequestedListCannotMutateTheSnapshot() {
        var manager = new EventsManagerImpl();
        var handler = new BasicLog(new ArrayList<>(), "basic");
        manager.addHandler(handler);
        var requested = new ArrayList<Class<? extends Event>>(List.of(Event.class));
        var snapshot = manager.inspectRuntimeRegistrations(requested);
        requested.clear(); requested.add(PersonEntersVehicleEvent.class);
        assertEquals(Event.class.getName(), snapshot.dispatches().getFirst().eventClass());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.registeredGroups().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.registeredGroups().getFirst().handlers().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.registeredGroups().getFirst().method().parameterTypes().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.dispatches().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.dispatches().getFirst().handlers().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.dispatches().getFirst().handlers().getFirst().method().parameterTypes().clear());
        manager.removeHandler(handler);
        assertEquals(1, snapshot.registeredGroups().getFirst().handlers().size());
        assertEquals(1, snapshot.dispatches().getFirst().handlers().size());
        for (var constructor : RuntimeInspectionSnapshot.class.getDeclaredConstructors()) assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        for (var field : RuntimeInspectionSnapshot.class.getDeclaredFields()) assertTrue(Modifier.isPrivate(field.getModifiers()));
        assertEquals(List.of("dispatches", "registeredGroups", "sameRegistrationsAs"),
                java.util.Arrays.stream(RuntimeInspectionSnapshot.class.getDeclaredMethods()).filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(java.lang.reflect.Method::getName).sorted().toList());
    }

    @Test
    void inspectionDoesNotPopulateOrClearCacheChangeCounterOrInvokeHandlers() throws ReflectiveOperationException {
        var manager = new EventsManagerImpl();
        var calls = new ArrayList<String>();
        var dual = new DualLog(calls);
        manager.addHandler(dual);
        Map<?, ?> cache = dispatchCache(manager);
        Counter counter = counter(manager);
        assertTrue(cache.isEmpty()); assertEquals(0, counter.getCounter());
        var initial = manager.inspectRuntimeRegistrations(List.of(PersonEntersPtVehicleEvent.class, Event.class));
        assertTrue(cache.isEmpty()); assertEquals(0, counter.getCounter());
        assertTrue(calls.isEmpty()); assertEquals(0, dual.resets);
        manager.processEvent(entryEvent(1));
        var warmCache = new HashMap<>(cache);
        var callsBefore = List.copyOf(calls);
        long countBefore = counter.getCounter();
        var after = manager.inspectRuntimeRegistrations(List.of(PersonEntersPtVehicleEvent.class, Event.class));
        assertTrue(initial.sameRegistrationsAs(after));
        assertEquals(warmCache.keySet(), cache.keySet());
        warmCache.forEach((key, value) -> assertSame(value, cache.get(key), "Inspection must not rebuild native cached dispatch arrays"));
        assertEquals(countBefore, counter.getCounter());
        assertEquals(callsBefore, calls); assertEquals(0, dual.resets);
    }

    @Test
    void insertingInspectionLeavesProcessingRemovalAndResetTraceUnchanged() {
        assertEquals(observableLifecycle(false), observableLifecycle(true));
    }

    @Test
    void snapshotsAreNotAMutationHistoryOrRegistrationSeal() {
        var manager = new EventsManagerImpl();
        var handler = new BasicLog(new ArrayList<>(), "basic");
        manager.addHandler(handler);
        var before = manager.inspectRuntimeRegistrations(List.of(Event.class));
        manager.removeHandler(handler);
        var removed = manager.inspectRuntimeRegistrations(List.of(Event.class));
        assertFalse(before.sameRegistrationsAs(removed));
        manager.addHandler(handler);
        var restored = manager.inspectRuntimeRegistrations(List.of(Event.class));
        assertTrue(before.sameRegistrationsAs(restored), "A restored identical sequence is indistinguishable from uninterrupted registration; this is not sealing");
    }

    @Test
    void hiddenLambdaLabelsOmitVmAddressesAndStillRequirePrivateIdentityComparison() {
        var manager = new EventsManagerImpl();
        BasicEventHandler first = capturingLambda(new ArrayList<>());
        BasicEventHandler second = capturingLambda(new ArrayList<>());
        assertNotSame(first, second);
        manager.addHandler(first);
        var before = manager.inspectRuntimeRegistrations(List.of(Event.class));
        String label = before.registeredGroups().getFirst().handlers().getFirst().handlerClass();
        assertFalse(label.contains("/0x"));
        if (first.getClass().isHidden()) {
            assertEquals("hidden:" + EventsManagerRuntimeInspectionTest.class.getName(), label);
        }
        manager.removeHandler(first); manager.addHandler(second);
        var after = manager.inspectRuntimeRegistrations(List.of(Event.class));
        assertEquals(before.registeredGroups(), after.registeredGroups());
        assertFalse(before.sameRegistrationsAs(after));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void invalidQueryFailsWithoutAnyRegistrationOrCacheMutation() throws ReflectiveOperationException {
        var manager = new EventsManagerImpl();
        manager.addHandler(new BasicLog(new ArrayList<>(), "basic"));
        var before = manager.inspectRuntimeRegistrations(List.of(Event.class));
        assertThrows(NullPointerException.class, () -> manager.inspectRuntimeRegistrations(null));
        var withNull = new ArrayList<Class<? extends Event>>(); withNull.add(null);
        assertThrows(NullPointerException.class, () -> manager.inspectRuntimeRegistrations(withNull));
        assertThrows(IllegalArgumentException.class, () -> manager.inspectRuntimeRegistrations((List) List.of(String.class)));
        assertTrue(before.sameRegistrationsAs(manager.inspectRuntimeRegistrations(List.of(Event.class))));
        assertTrue(dispatchCache(manager).isEmpty()); assertEquals(0, counter(manager).getCounter());
    }

    private static List<String> observableLifecycle(boolean inspect) {
        var manager = new EventsManagerImpl();
        var calls = new ArrayList<String>();
        var dual = new DualLog(calls);
        var basic = new BasicLog(calls, "other");
        manager.addHandler(basic); manager.addHandler(dual); manager.addHandler(dual);
        if (inspect) manager.inspectRuntimeRegistrations(List.of(PersonEntersPtVehicleEvent.class, Event.class));
        manager.initProcessing(); manager.processEvent(ptEvent(1)); manager.afterSimStep(1);
        if (inspect) manager.inspectRuntimeRegistrations(List.of(Event.class));
        manager.resetHandlers(12);
        calls.add("reset:" + dual.resets + ":" + basic.resets + ":" + dual.lastResetIteration);
        manager.removeHandler(dual);
        if (inspect) manager.inspectRuntimeRegistrations(List.of(PersonEntersVehicleEvent.class));
        manager.processEvent(entryEvent(2)); manager.finishProcessing();
        return List.copyOf(calls);
    }

    private static HandlerGroupDescription group(RuntimeInspectionSnapshot snapshot, Class<? extends Event> type) {
        return snapshot.registeredGroups().stream().filter(group -> group.eventClass().equals(type.getName())).findFirst().orElseThrow();
    }
    private static PersonEntersVehicleEvent entryEvent(double time) {
        return new PersonEntersVehicleEvent(time, Id.createPersonId("inspection-person"), Id.createVehicleId("inspection-vehicle"));
    }
    private static PersonEntersPtVehicleEvent ptEvent(double time) {
        return new PersonEntersPtVehicleEvent(time, Id.createPersonId("inspection-person"), Id.createVehicleId("inspection-vehicle"),
                Id.create("inspection-line", TransitLine.class), Id.create("inspection-route", TransitRoute.class));
    }
    private static BasicEventHandler capturingLambda(List<String> calls) { return event -> calls.add(event.getEventType()); }
    private static Map<?, ?> dispatchCache(EventsManagerImpl manager) throws ReflectiveOperationException {
        Field field = EventsManagerImpl.class.getDeclaredField("cacheHandlers"); field.setAccessible(true);
        return (Map<?, ?>) field.get(manager);
    }
    private static Counter counter(EventsManagerImpl manager) throws ReflectiveOperationException {
        Field field = EventsManagerImpl.class.getDeclaredField("counter"); field.setAccessible(true);
        return (Counter) field.get(manager);
    }

    public interface PtEntryHandler extends EventHandler { void handleEvent(PersonEntersPtVehicleEvent event); }
    private static final class PtEntryLog implements PtEntryHandler {
        private final List<String> calls;
        private PtEntryLog(List<String> calls) { this.calls = calls; }
        @Override public void handleEvent(PersonEntersPtVehicleEvent event) { calls.add("pt"); }
    }
    private static final class EntryLog implements PersonEntersVehicleEventHandler {
        private final List<String> calls; private final String name;
        private EntryLog(List<String> calls, String name) { this.calls = calls; this.name = name; }
        @Override public void handleEvent(PersonEntersVehicleEvent event) { calls.add(name); }
    }
    private static final class BasicLog implements BasicEventHandler {
        private final List<String> calls; private final String name; private int resets;
        private BasicLog(List<String> calls, String name) { this.calls = calls; this.name = name; }
        @Override public void handleEvent(Event event) { calls.add(name); }
        @Override public void reset(int iteration) { resets++; }
    }
    private static final class DualLog implements PersonEntersVehicleEventHandler, BasicEventHandler {
        private final List<String> calls; private int resets; private int lastResetIteration = -1;
        private DualLog(List<String> calls) { this.calls = calls; }
        @Override public void handleEvent(PersonEntersVehicleEvent event) { calls.add("dual-parent"); }
        @Override public void handleEvent(Event event) { calls.add("dual-basic"); }
        @Override public void reset(int iteration) { resets++; lastResetIteration = iteration; }
    }
    private static final class EqualityTrap implements BasicEventHandler {
        private int calls;
        @Override public void handleEvent(Event event) { calls++; }
        @Override public boolean equals(Object ignored) { throw new AssertionError("Inspection called handler.equals"); }
        @Override public int hashCode() { throw new AssertionError("Inspection called handler.hashCode"); }
        @Override public String getName() { throw new AssertionError("Inspection called handler.getName"); }
        @Override public String toString() { throw new AssertionError("Inspection called handler.toString"); }
    }
}

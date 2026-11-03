package org.matsim.run.ismt.dynamic;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.Event;
import org.matsim.facilities.ActivityFacility;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.matsim.run.ismt.dynamic.KnownFreightReleaseState.*;

/** Pure event-value transitions only; this class does not run QSim or certify event provenance. */
class KnownFreightReleaseStateTest {
    private static Contract contract(String order, double earliest, double deadline, String... units) {
        return new Contract(order, "origin-pt-stop", "destination-pt-stop", earliest, deadline,
                List.of(units), "freight_origin");
    }

    private static KnownFreightReleaseState component(String... units) {
        return new KnownFreightReleaseState(List.of(contract("order", 10, 60, units)));
    }

    private static ActivityEndEvent end(String unit, double time, String activityType) {
        return new ActivityEndEvent(time, Id.createPersonId(unit), Id.createLinkId("activity-link"),
                Id.create("activity-facility-not-a-pt-stop", ActivityFacility.class), activityType,
                new Coord(12, 34));
    }

    private static NativeEventPrefixObserver.ReceivedEvent received(Event event, long sequence, double receivedAt) {
        return new NativeEventPrefixObserver.ReceivedEvent(sequence, event.getTime(), receivedAt,
                event.getClass().getName(), event.getEventType(), event.getAttributes(), true);
    }

    private static NativeEventPrefixObserver.ReceivedEvent release(String unit, double time) {
        return received(end(unit, time, "freight_origin"), 1, time);
    }

    private static NativeEventPrefixObserver.ReceivedEvent altered(
            NativeEventPrefixObserver.ReceivedEvent event, Map<String, String> attributes) {
        return new NativeEventPrefixObserver.ReceivedEvent(event.closedSequence(), event.occurredAtSeconds(),
                event.receivedAtSeconds(), event.eventClass(), event.eventType(), attributes,
                event.receivedOnControlThread());
    }

    @Test void classificationDoesNotRevealAnOrderOrReleaseAnyUnits() {
        var reducer = component("ordinary-looking-unit", "freight-secondary");
        var initial = reducer.initialState();
        assertTrue(reducer.isFreightUnit("ordinary-looking-unit"));
        assertTrue(reducer.isFreightUnit("freight-secondary"));
        assertFalse(reducer.isFreightUnit("freight-unregistered"));
        assertFalse(reducer.isFreightUnit("order"));
        assertFalse(reducer.isFreightUnit(null));
        assertFalse(reducer.isFreightUnit(""));
        assertTrue(initial.knownOrders().isEmpty());
    }

    @Test void nativeActivityEndFieldsReleaseOneUnitWithoutInferringAStopFromFacility() {
        var reducer = component("unit-a", "unit-b");
        var event = received(end("unit-b", 15, "freight_origin"), 4, 18);
        assertEquals("actend", event.attributes().get("type"));
        assertEquals("unit-b", event.attributes().get("person"));
        assertEquals("freight_origin", event.attributes().get("actType"));
        assertEquals("15.0", event.attributes().get("time"));
        assertEquals("activity-facility-not-a-pt-stop", event.attributes().get("facility"));
        assertFalse(event.attributes().containsKey("atStop"));
        var before = reducer.initialState();
        var after = reducer.apply(before, event);
        assertEquals(new KnownOrder("order", "origin-pt-stop", "destination-pt-stop", 60, 2,
                Map.of("unit-b", 15.0)), after.knownOrders().get("order"));
        assertTrue(before.knownOrders().isEmpty());
        assertFalse(after.knownOrders().get("order").releasedUnits().containsKey("unit-a"));
        assertEquals(1, after.knownOrders().get("order").releasedUnits().size());
    }

    @Test void staggeredUnitsHaveSeparateActualReleaseTimesAndPreserveEarlierSnapshots() {
        var reducer = component("unit-a", "unit-b", "unit-c");
        var initial = reducer.initialState();
        var one = reducer.apply(initial, release("unit-a", 10));
        var two = reducer.apply(one, release("unit-c", 27));
        var three = reducer.apply(two, release("unit-b", 49));
        assertTrue(initial.knownOrders().isEmpty());
        assertEquals(Map.of("unit-a", 10.0), one.knownOrders().get("order").releasedUnits());
        assertEquals(Map.of("unit-a", 10.0, "unit-c", 27.0), two.knownOrders().get("order").releasedUnits());
        assertEquals(Map.of("unit-a", 10.0, "unit-c", 27.0, "unit-b", 49.0),
                three.knownOrders().get("order").releasedUnits());
        assertEquals(3, one.knownOrders().get("order").contractedUnits());
    }

    @Test void nonActivityEndUnknownPersonsAndOtherActivityTypesAreIgnored() {
        var reducer = component("unit-a");
        var state = reducer.initialState();
        var start = new ActivityStartEvent(12, Id.createPersonId("unit-a"), null, null, "freight_origin", null);
        assertSame(state, reducer.apply(state, received(start, 1, 12)));
        assertSame(state, reducer.apply(state, release("unregistered-unit", 12)));
        for (String type : List.of("freight_destination", "pt interaction", "freight_origin_extra", "")) {
            assertSame(state, reducer.apply(state, received(end("unit-a", 12, type), 1, 12)));
        }
        var event = release("unit-a", 12);
        var customType = new NativeEventPrefixObserver.ReceivedEvent(1, 12, 12,
                "custom.NotAnActivityEnd", event.eventType(), event.attributes(), true);
        assertSame(state, reducer.apply(state, customType), "A type-name collision is not a native ActivityEnd");
    }

    @Test void aContractCanUseAnotherExplicitInitialActivityType() {
        var reducer = new KnownFreightReleaseState(List.of(new Contract("order", "o", "d", 0, 60,
                List.of("unit"), "parcel_pickup")));
        var before = reducer.initialState();
        assertSame(before, reducer.apply(before, release("unit", 12)));
        var after = reducer.apply(before, received(end("unit", 12, "parcel_pickup"), 1, 12));
        assertEquals(Map.of("unit", 12.0), after.knownOrders().get("order").releasedUnits());
    }

    @Test void duplicateReleaseIsRejectedAtTheSameOrALaterTimeWithoutMutation() {
        var reducer = component("unit-a", "unit-b");
        var once = reducer.apply(reducer.initialState(), release("unit-a", 10));
        assertThrows(IllegalArgumentException.class, () -> reducer.apply(once, release("unit-a", 10)));
        assertThrows(IllegalArgumentException.class, () -> reducer.apply(once, release("unit-a", 20)));
        assertEquals(Map.of("unit-a", 10.0), once.knownOrders().get("order").releasedUnits());
        var twice = reducer.apply(once, release("unit-b", 30));
        assertEquals(2, twice.knownOrders().get("order").releasedUnits().size());
    }

    @Test void aLaterNonInitialActivityEndDoesNotReleaseAnAlreadyReleasedUnitAgain() {
        var reducer = component("unit");
        var once = reducer.apply(reducer.initialState(), release("unit", 10));
        assertSame(once, reducer.apply(once, received(end("unit", 20, "freight_destination"), 2, 20)));
    }

    @Test void releasesBeforeEarliestAreRejectedEvenWhenReceivedAfterEarliest() {
        var reducer = component("unit-a", "unit-b");
        var before = reducer.initialState();
        var tooEarly = received(end("unit-a", Math.nextDown(10.0), "freight_origin"), 1, 50);
        assertThrows(IllegalArgumentException.class, () -> reducer.apply(before, tooEarly));
        assertTrue(before.knownOrders().isEmpty());
        var one = reducer.apply(before, release("unit-a", 10));
        var earlySecond = received(end("unit-b", 9, "freight_origin"), 2, 50);
        assertThrows(IllegalArgumentException.class, () -> reducer.apply(one, earlySecond));
        assertEquals(Map.of("unit-a", 10.0), one.knownOrders().get("order").releasedUnits());
    }

    @Test void actualReleaseAtOrAfterDeadlineIsStillRecordedIncludingAlreadyLateContracts() {
        var reducer = new KnownFreightReleaseState(List.of(contract("late-order", 20, 10, "late-unit"),
                contract("boundary-order", 0, 10, "boundary-unit")));
        var one = reducer.apply(reducer.initialState(), release("late-unit", 25));
        var two = reducer.apply(one, release("boundary-unit", 10));
        assertEquals(10, two.knownOrders().get("late-order").deadlineSeconds());
        assertEquals(Map.of("late-unit", 25.0), two.knownOrders().get("late-order").releasedUnits());
        assertEquals(Map.of("boundary-unit", 10.0), two.knownOrders().get("boundary-order").releasedUnits());
    }

    @Test void releaseUsesOccurrenceTimeNotReceiptTimeOrControllerThreadFlag() {
        var reducer = component("unit");
        var original = release("unit", 11);
        var delayed = new NativeEventPrefixObserver.ReceivedEvent(3, 11, 45, original.eventClass(),
                original.eventType(), original.attributes(), false);
        var after = reducer.apply(reducer.initialState(), delayed);
        assertEquals(Map.of("unit", 11.0), after.knownOrders().get("order").releasedUnits());
    }

    @Test void publicationOrderNeedNotBeOccurrenceTimeOrder() {
        var reducer = component("unit-a", "unit-b");
        var one = reducer.apply(reducer.initialState(), received(end("unit-a", 20, "freight_origin"), 5, 30));
        var two = reducer.apply(one, received(end("unit-b", 15, "freight_origin"), 6, 31));
        assertEquals(Map.of("unit-a", 20.0, "unit-b", 15.0), two.knownOrders().get("order").releasedUnits());
    }

    @Test void eachOrderBecomesKnownOnlyOnItsOwnFirstRelease() {
        var reducer = new KnownFreightReleaseState(List.of(contract("first", 0, 40, "unit-a", "unit-b"),
                contract("second", 0, 60, "unit-c")));
        var one = reducer.apply(reducer.initialState(), release("unit-b", 10));
        assertEquals(List.of("first"), new ArrayList<>(one.knownOrders().keySet()));
        var two = reducer.apply(one, release("unit-c", 11));
        assertEquals(List.of("first", "second"), new ArrayList<>(two.knownOrders().keySet()));
        assertEquals("first", two.knownOrders().get("first").orderId());
        assertEquals("second", two.knownOrders().get("second").orderId());
        assertEquals(Map.of("unit-c", 11.0), two.knownOrders().get("second").releasedUnits());
    }

    @Test void differentUnreleasedSuffixesProduceEqualKnownInformation() {
        var left = new KnownFreightReleaseState(List.of(contract("visible", 10, 60, "common", "left-suffix"),
                contract("left-private-order", 100, 200, "left-private-unit")));
        var right = new KnownFreightReleaseState(List.of(contract("visible", 15, 60, "common", "right-suffix"),
                contract("right-private-order", 500, 900, "right-private-unit-a", "right-private-unit-b")));
        var leftBefore = left.initialState();
        var rightBefore = right.initialState();
        assertEquals(leftBefore.knownOrders(), rightBefore.knownOrders());
        var leftKnown = left.apply(leftBefore, release("common", 20)).knownOrders();
        var rightKnown = right.apply(rightBefore, release("common", 20)).knownOrders();
        assertEquals(leftKnown, rightKnown);
        assertEquals(Map.of("common", 20.0), leftKnown.get("visible").releasedUnits());
        assertEquals(2, leftKnown.get("visible").contractedUnits());
        assertFalse(leftKnown.toString().contains("suffix"));
        assertFalse(leftKnown.toString().contains("private"));
    }

    @Test void stateAndAllPublicNestedCollectionsAreImmutable() {
        var reducer = component("unit-a", "unit-b");
        var initial = reducer.initialState();
        var state = reducer.apply(initial, release("unit-a", 10));
        var known = state.knownOrders().get("order");
        assertThrows(UnsupportedOperationException.class, () -> initial.knownOrders().put("order", known));
        assertThrows(UnsupportedOperationException.class, () -> state.knownOrders().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.knownOrders().entrySet().iterator().next().setValue(known));
        assertThrows(UnsupportedOperationException.class, () -> known.releasedUnits().put("unit-b", 20.0));
        assertThrows(UnsupportedOperationException.class, () -> known.releasedUnits().entrySet().iterator().next().setValue(20.0));
        assertEquals(Map.of("unit-a", 10.0), known.releasedUnits());
    }

    @Test void inputContractsListsAndKnownOrderMapsAreDefensivelyCopied() {
        var units = new ArrayList<>(List.of("unit-a", "unit-b"));
        var contract = new Contract("order", "o", "d", 0, 60, units, "freight_origin");
        var contracts = new ArrayList<>(List.of(contract));
        var reducer = new KnownFreightReleaseState(contracts);
        units.clear();
        contracts.clear();
        assertTrue(reducer.isFreightUnit("unit-b"));
        assertThrows(UnsupportedOperationException.class, () -> contract.unitIds().clear());
        assertEquals(2, reducer.apply(reducer.initialState(), release("unit-a", 10)).knownOrders().get("order").contractedUnits());
        var releases = new LinkedHashMap<>(Map.of("unit-a", 10.0));
        var known = new KnownOrder("order", "o", "d", 60, 2, releases);
        releases.put("unit-b", 20.0);
        assertEquals(Map.of("unit-a", 10.0), known.releasedUnits());
    }

    @Test void callerCannotInjectForgedKnownOrdersOrUseAnotherComponentsState() {
        var reducer = component("unit");
        var identicalOther = component("unit");
        var before = reducer.initialState();
        var foreign = identicalOther.apply(identicalOther.initialState(), release("unit", 10));
        assertThrows(IllegalArgumentException.class, () -> reducer.apply(foreign, release("unit", 11)));
        assertThrows(IllegalArgumentException.class, () -> reducer.apply(identicalOther.initialState(), release("unknown", 11)));
        assertTrue(Modifier.isFinal(State.class.getModifiers()));
        for (var constructor : State.class.getDeclaredConstructors()) assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        for (var field : State.class.getDeclaredFields()) assertTrue(Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers()));
        var forged = new KnownOrder("order", "fake-origin", "fake-destination", 0, 1, Map.of("unit", 999.0));
        assertThrows(UnsupportedOperationException.class, () -> before.knownOrders().put("order", forged));
        assertTrue(before.knownOrders().isEmpty());
    }

    @Test void independentBranchesDoNotMutateTheComponentOrConsumeUnitsGlobally() {
        var reducer = component("unit-a", "unit-b");
        var initial = reducer.initialState();
        var branchA = reducer.apply(initial, release("unit-a", 10));
        var branchB = reducer.apply(initial, release("unit-b", 20));
        assertEquals(Map.of("unit-a", 10.0), branchA.knownOrders().get("order").releasedUnits());
        assertEquals(Map.of("unit-b", 20.0), branchB.knownOrders().get("order").releasedUnits());
        assertTrue(initial.knownOrders().isEmpty());
        assertTrue(reducer.initialState().knownOrders().isEmpty());
    }

    @Test void copiedNativeEventsCannotBeChangedByLaterMutationOfTheOriginal() {
        var reducer = component("unit");
        var nativeEvent = end("unit", 12, "freight_origin");
        var copied = received(nativeEvent, 1, 12);
        nativeEvent.setTime(999);
        nativeEvent.setCoord(new Coord(999, 999));
        assertEquals(Map.of("unit", 12.0), reducer.apply(reducer.initialState(), copied).knownOrders().get("order").releasedUnits());
        assertEquals("12.0", copied.attributes().get("x"));
    }

    @Test void malformedActivityEndFieldsAreRejectedWithoutPublishingAnOrder() {
        var reducer = component("unit");
        var before = reducer.initialState();
        var event = release("unit", 12);
        for (String missing : List.of("time", "type", "person", "actType")) {
            var attributes = new LinkedHashMap<>(event.attributes());
            attributes.remove(missing);
            assertThrows(IllegalArgumentException.class, () -> reducer.apply(before, altered(event, attributes)), missing);
        }
        for (String invalidTime : List.of("not-a-time", "11", "NaN", "Infinity", "-1")) {
            var attributes = new LinkedHashMap<>(event.attributes());
            attributes.put("time", invalidTime);
            assertThrows(IllegalArgumentException.class, () -> reducer.apply(before, altered(event, attributes)), invalidTime);
        }
        var wrongType = new NativeEventPrefixObserver.ReceivedEvent(1, 12, 12, event.eventClass(), "actstart",
                event.attributes(), true);
        assertThrows(IllegalArgumentException.class, () -> reducer.apply(before, wrongType));
        assertTrue(before.knownOrders().isEmpty());
        assertEquals(1, reducer.apply(before, event).knownOrders().size());
    }

    @Test void duplicateOrdersAndGloballyReusedUnitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new KnownFreightReleaseState(List.of(
                contract("order", 0, 60, "a"), contract("order", 0, 60, "b"))));
        assertThrows(IllegalArgumentException.class, () -> new KnownFreightReleaseState(List.of(
                contract("first", 0, 60, "shared"), contract("second", 0, 60, "shared"))));
        assertThrows(IllegalArgumentException.class, () -> contract("order", 0, 60, "a", "a"));
    }

    @Test void contractsRejectEmptyIdentifiersUnitsAndInvalidTimes() {
        for (String invalid : List.of("", " ", "\t")) {
            assertThrows(IllegalArgumentException.class, () -> contract(invalid, 0, 60, "unit"));
            assertThrows(IllegalArgumentException.class, () -> contract("order", 0, 60, invalid));
            assertThrows(IllegalArgumentException.class, () -> new Contract("order", invalid, "d", 0, 60, List.of("unit"), "release"));
            assertThrows(IllegalArgumentException.class, () -> new Contract("order", "o", invalid, 0, 60, List.of("unit"), "release"));
            assertThrows(IllegalArgumentException.class, () -> new Contract("order", "o", "d", 0, 60, List.of("unit"), invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> contract("order", 0, 60));
        assertThrows(IllegalArgumentException.class, () -> contract(null, 0, 60, "unit"));
        assertThrows(NullPointerException.class, () -> new Contract("order", "o", "d", 0, 60, null, "release"));
        for (double invalid : new double[]{-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> contract("order", invalid, 60, "unit"));
            assertThrows(IllegalArgumentException.class, () -> contract("order", 0, invalid, "unit"));
        }
    }

    @Test void publicKnownOrderRejectsInvalidCountsIdentifiersAndTimes() {
        for (int invalid : new int[]{-1, 0}) {
            assertThrows(IllegalArgumentException.class, () -> new KnownOrder("order", "o", "d", 60, invalid, Map.of("u", 1.0)));
        }
        assertThrows(IllegalArgumentException.class, () -> new KnownOrder("order", "o", "d", 60, 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new KnownOrder("order", "o", "d", 60, 1, Map.of("a", 1.0, "b", 2.0)));
        assertThrows(IllegalArgumentException.class, () -> new KnownOrder("", "o", "d", 60, 1, Map.of("u", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> new KnownOrder("order", "", "d", 60, 1, Map.of("u", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> new KnownOrder("order", "o", "", 60, 1, Map.of("u", 1.0)));
        assertThrows(IllegalArgumentException.class, () -> new KnownOrder("order", "o", "d", 60, 1, Map.of("", 1.0)));
        for (double invalid : new double[]{-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new KnownOrder("order", "o", "d", invalid, 1, Map.of("u", 1.0)));
            assertThrows(IllegalArgumentException.class, () -> new KnownOrder("order", "o", "d", 60, 1, Map.of("u", invalid)));
        }
    }

    @Test void zeroTimeReleasesAreSupportedAndNegativeZeroIsCanonicalized() {
        var contract = contract("zero", -0.0, -0.0, "unit");
        var reducer = new KnownFreightReleaseState(List.of(contract));
        var known = reducer.apply(reducer.initialState(), release("unit", -0.0)).knownOrders().get("zero");
        assertEquals(0.0, contract.earliestReleaseSeconds());
        assertEquals(0.0, contract.deadlineSeconds());
        assertEquals(Map.of("unit", 0.0), known.releasedUnits());
    }

    @Test void emptyContractSetWorksButNullInputsAreRejected() {
        var reducer = new KnownFreightReleaseState(List.of());
        var initial = reducer.initialState();
        assertSame(initial, reducer.apply(initial, release("unit", 10)));
        assertFalse(reducer.isFreightUnit("unit"));
        assertThrows(NullPointerException.class, () -> new KnownFreightReleaseState(null));
        assertThrows(NullPointerException.class, () -> reducer.apply(null, release("unit", 10)));
        assertThrows(NullPointerException.class, () -> reducer.apply(initial, null));
    }
}

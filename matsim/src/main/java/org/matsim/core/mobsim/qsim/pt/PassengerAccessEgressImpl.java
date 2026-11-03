/* *********************************************************************** *
 * project: org.matsim.*
 * PassengerAccessEgressImpl
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2012 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */
package org.matsim.core.mobsim.qsim.pt;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonContinuesInVehicleEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.BoardingDeniedEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.framework.MobsimAgent;
import org.matsim.core.mobsim.framework.PassengerAgent;
import org.matsim.core.mobsim.qsim.InternalInterface;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.Vehicle;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 * @author dgrether
 */
class PassengerAccessEgressImpl implements PassengerAccessEgress {

	private final InternalInterface internalInterface;
	private final TransitStopAgentTracker agentTracker;
	private final boolean isGeneratingDeniedBoardingEvents;
	/**
	 * These agents are at the stop and relocate to another vehicle.
	 */
	private final Map<Id<TransitStopFacility>, List<PTPassengerAgent>> agentRelocating = new LinkedHashMap<>();
	private final Map<Id<Vehicle>, Set<PTPassengerAgent>> agentsDeniedToBoard;
	private final Scenario scenario;
	private final EventsManager eventsManager;
	private final TransitBoardingPolicy boardingPolicy;
	private final Map<Id<Vehicle>, Map<PTPassengerAgent, DeniedSnapshot>> denialSnapshots = new ConcurrentHashMap<>();

	PassengerAccessEgressImpl(InternalInterface internalInterface, TransitStopAgentTracker agentTracker, Scenario scenario, EventsManager eventsManager) {
		this.internalInterface = internalInterface;
		this.agentTracker = agentTracker;
		this.scenario = scenario;
		this.eventsManager = eventsManager;
		Object policy = scenario.getScenarioElement(TransitBoardingPolicy.SCENARIO_ELEMENT);
		if (policy != null && !(policy instanceof TransitBoardingPolicy)) {
			throw new IllegalStateException("Scenario element '" + TransitBoardingPolicy.SCENARIO_ELEMENT
					+ "' must implement TransitBoardingPolicy, got " + policy.getClass().getName());
		}
		this.boardingPolicy = (TransitBoardingPolicy) policy;
		this.isGeneratingDeniedBoardingEvents = this.scenario.getConfig().vspExperimental().isGeneratingBoardingDeniedEvents();
		this.agentsDeniedToBoard = isGeneratingDeniedBoardingEvents ? new ConcurrentHashMap<>() : null;
	}

	/**
	 * @return should be 0.0 or 1.0. Values greater than 1.0 may lead to buggy behavior, dependent on TransitStopHandler used
	 */
	/*package*/ double calculateStopTimeAndTriggerBoarding(TransitRoute transitRoute, TransitLine transitLine, final TransitVehicle vehicle,
														   final TransitStopFacility stop, List<TransitRouteStop> stopsToCome, final double now) {

		List<PTPassengerAgent> passengersLeaving = findPassengersLeaving(vehicle, stop);

		// Relocating passengers are only determined at the very last stop.
		List<PTPassengerAgent> passengersRelocating = stopsToCome.isEmpty() ? findPassengersRelocating(vehicle, stop) : List.of();


		int freeCapacity = vehicle.getPassengerCapacity() - vehicle.getPassengers().size() + passengersLeaving.size();

		List<PTPassengerAgent> passengersEntering = findPassengersEntering(transitRoute, transitLine, vehicle, stop,
				stopsToCome, passengersLeaving, freeCapacity, now);
		if (boardingPolicy != null) {
			boardingPolicy.observeStopInteraction(vehicle, transitLine, transitRoute, stop,
					passengersLeaving, passengersEntering);
		}

		TransitStopHandler stopHandler = vehicle.getStopHandler();
		double stopTime = stopHandler.handleTransitStop(stop, now, passengersLeaving, passengersEntering, passengersRelocating, this, vehicle);
		if (boardingPolicy != null) {
			stopTime = boardingPolicy.adjustStopTime(vehicle, transitLine, transitRoute, stop,
					passengersLeaving, passengersEntering, stopTime);
		}
		if (stopTime == 0.0) { // (de-)boarding is complete when the additional stopTime is 0.0
			if (this.isGeneratingDeniedBoardingEvents) {
				this.fireBoardingDeniedEvents(vehicle, now);
				this.agentsDeniedToBoard.remove(vehicle.getId());
				this.denialSnapshots.remove(vehicle.getId());
			}
		}
		return stopTime;
	}

	private void fireBoardingDeniedEvents(TransitVehicle vehicle, double now) {
		Id<Vehicle> vehicleId = vehicle.getId();
		// A stop handler can return a nonzero duration and be called again later.
		// Completing one vehicle's stop must never flush another vehicle's denials.
		Map<PTPassengerAgent, DeniedSnapshot> vehicleSnapshots = denialSnapshots.getOrDefault(vehicleId, Map.of());
		for (PTPassengerAgent agent : this.agentsDeniedToBoard.getOrDefault(vehicleId, Set.of())) {
			Id<Person> agentId = agent.getId();
			this.eventsManager.processEvent(
				new BoardingDeniedEvent(now, agentId, vehicleId)
			);
			DeniedSnapshot snapshot = vehicleSnapshots.get(agent);
			if (snapshot != null) {
				this.eventsManager.processEvent(new TransitBoardingDeniedDetailEvent(now, agentId, vehicleId,
						snapshot.line(), snapshot.route(), snapshot.departure(), snapshot.stop(),
						snapshot.reason(), snapshot.details()));
			}
		}
	}


	private List<PTPassengerAgent> findPassengersEntering(TransitRoute transitRoute, TransitLine transitLine, TransitVehicle vehicle,
												  final TransitStopFacility stop, List<TransitRouteStop> stopsToCome,
												  List<PTPassengerAgent> passengersLeaving, int freeCapacity, double now) {
		ArrayList<PTPassengerAgent> passengersEntering = new ArrayList<>();
		ArrayList<PTPassengerAgent> candidates = new ArrayList<>();
		for (PTPassengerAgent agent : this.agentTracker.getAgentsAtFacility(stop.getId())) {
			if (agent.getEnterTransitRoute(transitLine, transitRoute, stopsToCome, vehicle)) {
				candidates.add(agent);
			}
		}
		if (boardingPolicy != null && candidates.stream().anyMatch(agent ->
				boardingPolicy.indivisibleBoardingGroup(agent, vehicle, transitLine, transitRoute, stop) != null)) {
			return findWholeOrderEntrants(candidates, transitRoute, transitLine, vehicle, stop,
					passengersLeaving, freeCapacity, now);
		}
		if (boardingPolicy != null) {
			Comparator<PTPassengerAgent> ordering = Comparator.comparingInt((PTPassengerAgent agent) -> boardingPolicy.priority(
					agent, vehicle, transitLine, transitRoute, stop)).reversed();
			if (boardingPolicy.usesQueueArrivalTime()) {
				// Read the native queue, not an asynchronous event mirror or timetable.
				Map<PTPassengerAgent, TransitStopAgentTracker.QueueArrival> arrivals = new HashMap<>();
				for (PTPassengerAgent candidate : candidates)
					arrivals.put(candidate, agentTracker.getQueueArrival(candidate, stop.getId()));
				ordering = ordering.thenComparingDouble(agent -> arrivals.get(agent).readyTimeSeconds())
						.thenComparingLong(agent -> arrivals.get(agent).sequence());
			}
			candidates.sort(ordering);
		}

		if (this.isGeneratingDeniedBoardingEvents) {
			for (PTPassengerAgent agent : candidates) {
				// Physical capacity precedes every policy rule. A diagnostic snapshot may
				// observe a native denial, but cannot turn it into a policy decision.
				TransitBoardingPolicy.BoardingDecision decision = freeCapacity < 1
						? TransitBoardingPolicy.BoardingDecision.deny(TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY)
						: boardingPolicy == null ? TransitBoardingPolicy.BoardingDecision.allow()
						: policyDecisionAtTime(agent, vehicle, transitLine, transitRoute, stop,
								passengersLeaving, passengersEntering, now);
				if (decision.allowed()) {
					passengersEntering.add(agent);
					freeCapacity--;
				} else {
					rememberDenial(agent, vehicle, transitLine, transitRoute, stop, stop.getId(),
							decision, passengersLeaving, passengersEntering);
				}
			}
		} else {
			for (PTPassengerAgent agent : candidates) {
				if (freeCapacity <= 0) {
					break;
				}
				if (boardingPolicy == null || policyDecisionAtTime(
						agent, vehicle, transitLine, transitRoute, stop, passengersLeaving, passengersEntering, now).allowed()) {
					passengersEntering.add(agent);
					freeCapacity--;
				}
			}
		}

		return passengersEntering;
	}

	/** Groups are selected transactionally; missing members are never replaced by a smaller batch. */
	private List<PTPassengerAgent> findWholeOrderEntrants(List<PTPassengerAgent> candidates,
			TransitRoute route, TransitLine line, TransitVehicle vehicle, TransitStopFacility stop,
			List<PTPassengerAgent> leaving, int freeCapacity, double now) {
		Map<Id<Person>, PTPassengerAgent> eligible = new LinkedHashMap<>();
		for (PTPassengerAgent agent : candidates) {
			if (eligible.putIfAbsent(agent.getId(), agent) != null)
				throw new IllegalStateException("Duplicate native queue candidate: " + agent.getId());
		}
		Set<Id<Person>> queuedIds = new HashSet<>();
		for (PTPassengerAgent agent : agentTracker.getAgentsAtFacility(stop.getId())) queuedIds.add(agent.getId());
		List<QueueBatch> batches = new ArrayList<>();
		Set<String> seenGroups = new HashSet<>();
		for (PTPassengerAgent candidate : candidates) {
			var group = boardingPolicy.indivisibleBoardingGroup(candidate, vehicle, line, route, stop);
			List<PTPassengerAgent> members = new ArrayList<>();
			TransitBoardingPolicy.DenialReason incomplete = null;
			if (group == null) {
				members.add(candidate);
			} else {
				if (!group.memberIds().contains(candidate.getId()))
					throw new IllegalStateException("Candidate is absent from its authoritative boarding group: " + candidate.getId());
				if (!seenGroups.add(group.groupId())) continue;
				for (Id<Person> id : group.memberIds()) {
					PTPassengerAgent member = eligible.get(id);
					if (member != null) {
						if (!group.equals(boardingPolicy.indivisibleBoardingGroup(member, vehicle, line, route, stop)))
							throw new IllegalStateException("Inconsistent boarding group membership: " + group.groupId());
						members.add(member);
					} else if (!queuedIds.contains(id)) {
						incomplete = TransitBoardingPolicy.DenialReason.WHOLE_ORDER_NOT_READY;
					} else if (incomplete == null) {
						incomplete = TransitBoardingPolicy.DenialReason.WHOLE_ORDER_INELIGIBLE;
					}
				}
				Id<TransitStopFacility> destination = members.getFirst().getDesiredDestinationStopId();
				if (members.stream().anyMatch(m -> !Objects.equals(destination, m.getDesiredDestinationStopId())))
					incomplete = TransitBoardingPolicy.DenialReason.WHOLE_ORDER_INELIGIBLE;
			}
			int priority = boardingPolicy.priority(candidate, vehicle, line, route, stop);
			if (members.stream().anyMatch(m -> boardingPolicy.priority(m, vehicle, line, route, stop) != priority))
				throw new IllegalStateException("An indivisible boarding group must have a single actor priority");
			double ready = Double.NEGATIVE_INFINITY;
			long sequence = Long.MIN_VALUE;
			if (boardingPolicy.usesQueueArrivalTime()) {
				for (PTPassengerAgent member : members) {
					var arrival = agentTracker.getQueueArrival(member, stop.getId());
					if (arrival.readyTimeSeconds() > ready) {
						ready = arrival.readyTimeSeconds(); sequence = arrival.sequence();
					} else if (arrival.readyTimeSeconds() == ready) sequence = Math.max(sequence, arrival.sequence());
				}
			}
			batches.add(new QueueBatch(List.copyOf(members), group != null, priority, ready, sequence, incomplete));
		}
		Comparator<QueueBatch> order = Comparator.comparingInt(QueueBatch::priority).reversed();
		if (boardingPolicy.usesQueueArrivalTime())
			order = order.thenComparingDouble(QueueBatch::ready).thenComparingLong(QueueBatch::sequence);
		batches.sort(order);
		List<PTPassengerAgent> accepted = new ArrayList<>();
		boolean blocked = false;
		for (QueueBatch batch : batches) {
			// Window/service ineligibility is checked even after a FIFO blockage, so an
			// outside-window object is not relabelled as a physical capacity contender.
			var service = batch.incomplete() == null
					? policyBatchDecisionAtTime(batch.members(), vehicle, line, route, stop, leaving, accepted, now, true)
					: TransitBoardingPolicy.BoardingDecision.deny(batch.incomplete());
			TransitBoardingPolicy.BoardingDecision decision;
			if (batch.incomplete() != null || !service.allowed()) {
				decision = service;
			} else if (blocked) {
				decision = TransitBoardingPolicy.BoardingDecision.deny(TransitBoardingPolicy.DenialReason.WHOLE_ORDER_QUEUE_BLOCKED);
			} else if (freeCapacity < batch.members().size()) {
				decision = TransitBoardingPolicy.BoardingDecision.deny(TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY);
			} else {
				decision = policyBatchDecisionAtTime(batch.members(), vehicle, line, route, stop, leaving, accepted, now, false);
			}
			if (decision.allowed()) {
				accepted.addAll(batch.members()); freeCapacity -= batch.members().size();
			} else {
				for (PTPassengerAgent member : batch.members())
					rememberDenial(member, vehicle, line, route, stop, stop.getId(), decision, leaving, accepted);
				if (batch.indivisible() && boardingPolicy.blocksFollowingCandidatesAfterGroupDenial(decision.denialReason()))
					blocked = true;
			}
		}
		return accepted;
	}

	private record QueueBatch(List<PTPassengerAgent> members, boolean indivisible, int priority,
			double ready, long sequence, TransitBoardingPolicy.DenialReason incomplete) { }

	private TransitBoardingPolicy.BoardingDecision policyBatchDecisionAtTime(List<PTPassengerAgent> batch,
			TransitVehicle vehicle, TransitLine line, TransitRoute route, TransitStopFacility stop,
			List<PTPassengerAgent> leaving, List<PTPassengerAgent> accepted, double now, boolean timeOnly) {
		List<PTPassengerAgent> projected = new ArrayList<>(accepted);
		for (PTPassengerAgent member : batch) {
			var decision = timeOnly ? eligibilityDecisionAtTime(member, vehicle, line, route, stop, now)
					: policyDecisionAtTime(member, vehicle, line, route, stop, leaving, projected, now);
			if (!decision.allowed()) return decision;
			projected.add(member);
		}
		return TransitBoardingPolicy.BoardingDecision.allow();
	}

	private TransitBoardingPolicy.BoardingDecision eligibilityDecisionAtTime(PTPassengerAgent passenger,
			TransitVehicle vehicle, TransitLine line, TransitRoute route, TransitStopFacility stop, double now) {
		var temporal = boardingPolicy.boardingTimeDecision(passenger, vehicle, line, route, stop, now);
		return temporal.allowed() ? boardingPolicy.boardingServiceEligibilityDecision(passenger, vehicle, line, route, stop)
				: temporal;
	}

	private TransitBoardingPolicy.BoardingDecision policyDecisionAtTime(PTPassengerAgent passenger,
			TransitVehicle vehicle, TransitLine line, TransitRoute route, TransitStopFacility stop,
			List<PTPassengerAgent> leaving, List<PTPassengerAgent> accepted, double now) {
		var temporal = boardingPolicy.boardingTimeDecision(passenger, vehicle, line, route, stop, now);
		return temporal.allowed() ? boardingPolicy.decideBoarding(passenger, vehicle, line, route, stop, leaving, accepted)
				: temporal;
	}

	private void rememberDenial(PTPassengerAgent passenger, TransitVehicle vehicle, TransitLine line,
			TransitRoute route, TransitStopFacility stop, Id<TransitStopFacility> stopId,
			TransitBoardingPolicy.BoardingDecision decision, List<PTPassengerAgent> leaving,
			List<PTPassengerAgent> accepted) {
		if (!isGeneratingDeniedBoardingEvents) return;
		agentsDeniedToBoard.computeIfAbsent(vehicle.getId(), ignored -> new HashSet<>()).add(passenger);
		if (boardingPolicy != null) {
			Id<Departure> departureId = vehicle.getDriver() instanceof AbstractTransitDriverAgent driver
					? driver.getDeparture().getId() : null;
			denialSnapshots.computeIfAbsent(vehicle.getId(), ignored -> new LinkedHashMap<>()).putIfAbsent(passenger,
					new DeniedSnapshot(line == null ? null : line.getId(), route == null ? null : route.getId(),
							departureId, stopId, decision.denialReason(),
							boardingPolicy.denialDetails(passenger, vehicle, line, route, stop, leaving, accepted)));
		}
	}

	private record DeniedSnapshot(Id<TransitLine> line, Id<TransitRoute> route, Id<Departure> departure,
			Id<TransitStopFacility> stop, TransitBoardingPolicy.DenialReason reason,
			TransitBoardingPolicy.DenialDetails details) { }

	private List<PTPassengerAgent> findPassengersLeaving(TransitVehicle vehicle,
														 final TransitStopFacility stop) {
		List<PTPassengerAgent> passengersLeaving = new ArrayList<>();
		for (PassengerAgent passenger : vehicle.getPassengers()) {
			if (((PTPassengerAgent) passenger).getExitAtStop(stop)) {
				passengersLeaving.add((PTPassengerAgent) passenger);
			}
		}
		return passengersLeaving;
	}

	private List<PTPassengerAgent> findPassengersRelocating(TransitVehicle vehicle, final TransitStopFacility stop) {

		List<PTPassengerAgent> relocatingPassengers = new ArrayList<>();
		for (PassengerAgent passenger : vehicle.getPassengers()) {
			if (((PTPassengerAgent) passenger).getRelocationAtStop(stop)) {
				relocatingPassengers.add((PTPassengerAgent) passenger);
			}
		}

		return relocatingPassengers;
	}

	@Override
	public boolean handlePassengerEntering(PTPassengerAgent passenger, TransitVehicle vehicle, Id<TransitStopFacility> fromStopFacilityId, double time) {
		if (boardingPolicy != null) {
			var driver = vehicle.getDriver();
			var line = driver == null ? null : driver.getTransitLine();
			var route = driver == null ? null : driver.getTransitRoute();
			var stop = scenario.getTransitSchedule().getFacilities().get(fromStopFacilityId);
			var group = boardingPolicy.indivisibleBoardingGroup(passenger, vehicle, line, route, stop);
			if (group != null && group.memberIds().size() > 1)
				throw new IllegalStateException("Indivisible freight must use handlePassengersEntering, not single-unit entry: " + group.groupId());
			var temporal = boardingPolicy.boardingTimeDecision(passenger, vehicle, line, route, stop, time);
			if (!temporal.allowed()) {
				rememberDenial(passenger, vehicle, line, route, stop, fromStopFacilityId,
						temporal, List.of(), List.of());
				return false;
			}
		}
		boolean handled = vehicle.addPassenger(passenger);
		if (handled) {
			commitPassengerEntry(passenger, vehicle, fromStopFacilityId, time);
		}
		return handled;
	}

	@Override
	public int handlePassengersEntering(List<PTPassengerAgent> entering, TransitVehicle vehicle,
			Id<TransitStopFacility> stopId, double time) {
		if (entering.isEmpty()) return 0;
		var driver = vehicle.getDriver();
		var line = driver == null ? null : driver.getTransitLine();
		var route = driver == null ? null : driver.getTransitRoute();
		var stop = scenario.getTransitSchedule().getFacilities().get(stopId);
		var group = boardingPolicy == null ? null
				: boardingPolicy.indivisibleBoardingGroup(entering.getFirst(), vehicle, line, route, stop);
		if (group == null) return handlePassengerEntering(entering.getFirst(), vehicle, stopId, time) ? 1 : 0;
		if (driver == null || line == null || route == null || stop == null)
			throw new IllegalStateException("Atomic entry requires a native driver, line, route and registered boarding stop");
		int count = group.memberIds().size();
		List<PTPassengerAgent> batch = new ArrayList<>(entering.subList(0, Math.min(count, entering.size())));
		Set<Id<Person>> ids = new HashSet<>();
		for (PTPassengerAgent member : batch) ids.add(member.getId());
		if (batch.size() != count || ids.size() != count || !ids.equals(new HashSet<>(group.memberIds()))
				|| !agentTracker.getAgentsAtFacility(stopId).containsAll(batch)) {
			for (PTPassengerAgent member : batch) {
				if (group.memberIds().contains(member.getId())) rememberDenial(member, vehicle, line, route, stop, stopId,
						TransitBoardingPolicy.BoardingDecision.deny(TransitBoardingPolicy.DenialReason.WHOLE_ORDER_NOT_READY), List.of(), List.of());
			}
			return 0;
		}
		for (PTPassengerAgent member : batch) {
			if (!group.equals(boardingPolicy.indivisibleBoardingGroup(member, vehicle, line, route, stop)))
				throw new IllegalStateException("Inconsistent native batch identity: " + group.groupId());
		}
		Id<TransitStopFacility> destination = batch.getFirst().getDesiredDestinationStopId();
		if (batch.stream().anyMatch(member -> !Objects.equals(destination, member.getDesiredDestinationStopId()))) {
			for (PTPassengerAgent member : batch) rememberDenial(member, vehicle, line, route, stop, stopId,
					TransitBoardingPolicy.BoardingDecision.deny(TransitBoardingPolicy.DenialReason.WHOLE_ORDER_INELIGIBLE), List.of(), List.of());
			return 0;
		}
		synchronized (vehicle) {
			var temporal = policyBatchDecisionAtTime(batch, vehicle, line, route, stop, List.of(), List.of(), time, true);
			var decision = !temporal.allowed() ? temporal
					: vehicle.getPassengerCapacity() - vehicle.getPassengers().size() < count
					? TransitBoardingPolicy.BoardingDecision.deny(TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY)
					: policyBatchDecisionAtTime(batch, vehicle, line, route, stop, List.of(), List.of(), time, false);
			if (!decision.allowed()) {
				for (PTPassengerAgent member : batch)
					rememberDenial(member, vehicle, line, route, stop, stopId, decision, List.of(), List.of());
				return 0;
			}
			List<PTPassengerAgent> added = new ArrayList<>();
			try {
				for (PTPassengerAgent member : batch) {
					if (vehicle.getPassengers().contains(member))
						throw new IllegalStateException("Atomic batch member is already on this vehicle: " + member.getId());
					if (!vehicle.addPassenger(member)) {
						break;
					}
					added.add(member);
				}
			} catch (RuntimeException failure) {
				try {
					rollbackUncommittedEntries(added, vehicle);
				} catch (RuntimeException rollbackFailure) {
					failure.addSuppressed(rollbackFailure);
				}
				throw failure;
			}
			if (added.size() != count) {
				rollbackUncommittedEntries(added, vehicle);
				for (PTPassengerAgent rejected : batch) rememberDenial(rejected, vehicle, line, route, stop, stopId,
						TransitBoardingPolicy.BoardingDecision.deny(TransitBoardingPolicy.DenialReason.NATIVE_CAPACITY), List.of(), List.of());
				return 0;
			}
			// No queue removal, assignment or enter event occurs until every native add
			// has succeeded. The handler charges count * native access time afterward.
			for (PTPassengerAgent member : batch) commitPassengerEntry(member, vehicle, stopId, time);
			return count;
		}
	}

	private void rollbackUncommittedEntries(List<PTPassengerAgent> added, TransitVehicle vehicle) {
		for (int i = added.size() - 1; i >= 0; i--) {
			if (!vehicle.removePassenger(added.get(i)))
				throw new IllegalStateException("Unable to roll back an uncommitted atomic entry: " + added.get(i).getId());
			added.remove(i);
		}
	}

	private void commitPassengerEntry(PTPassengerAgent passenger, TransitVehicle vehicle,
			Id<TransitStopFacility> stopId, double time) {
		// A parallel-door handler can temporarily retry before same-stop alighting.
		// Successful entry at this stop is not a denied departure and must not incur its penalty.
		if (isGeneratingDeniedBoardingEvents) {
			var denied = agentsDeniedToBoard.get(vehicle.getId());
			if (denied != null) denied.remove(passenger);
			var snapshots = denialSnapshots.get(vehicle.getId());
			if (snapshots != null) snapshots.remove(passenger);
		}
		agentTracker.removeAgentFromStop(passenger, stopId);
		internalInterface.unregisterAdditionalAgentOnLink(passenger.getId(), passenger.getCurrentLinkId());
		passenger.setVehicle(vehicle);
		var driver = vehicle.getDriver();
		eventsManager.processEvent(new PersonEntersPtVehicleEvent(time, passenger.getId(), vehicle.getId(),
				driver.getTransitLine().getId(), driver.getTransitRoute().getId()));
	}

	@Override
	public boolean handlePassengerLeaving(PTPassengerAgent passenger, TransitVehicle vehicle, Id<Link> toLinkId, double time) {
		boolean handled = vehicle.removePassenger(passenger);
		if (handled) {
			passenger.setVehicle(null);
			var driver = vehicle.getDriver();
			var e = new PersonLeavesPtVehicleEvent(time, passenger.getId(), vehicle.getId(), driver.getTransitLine().getId(), driver.getTransitRoute().getId());
			eventsManager.processEvent(e);

			// from here on works only if PassengerAgent can be cast into MobsimAgent ... but this is how it was before.
			// kai, sep'12

			MobsimAgent agent = (MobsimAgent) passenger;
			agent.notifyArrivalOnLinkByNonNetworkMode(toLinkId);
			agent.endLegAndComputeNextState(time);
			this.internalInterface.arrangeNextAgentState(agent);
			// (cannot set trEngine to TransitQSimEngine because there are tests where this will not work. kai, dec'11)
		}
		return handled;
	}

	@Override
	public void handlePassengerRelocating(PTPassengerAgent passenger, TransitVehicle vehicle, Id<TransitStopFacility> stopFacilityId, double time) {
		if (boardingPolicy != null && boardingPolicy.indivisibleBoardingGroup(passenger, vehicle,
				vehicle.getDriver().getTransitLine(), vehicle.getDriver().getTransitRoute(),
				scenario.getTransitSchedule().getFacilities().get(stopFacilityId)) != null) {
			throw new IllegalStateException("WHOLE_ORDER does not yet support chained vehicle continuation; use an explicit stop-transfer plan");
		}

		boolean handled = vehicle.removePassenger(passenger);
		if (handled) {
			// Store passengers wanting to relocate at the stop facility
			agentRelocating.computeIfAbsent(stopFacilityId, _ -> new ArrayList<>()).add(passenger);

		} else
			throw new IllegalStateException("Agent " + passenger.getId() + " was not removed from vehicle " + vehicle.getId() + " when relocating.");
	}

	@Override
	public void relocatePassengers(TransitDriverAgentImpl vehicle, List<ChainedDeparture> departures, double time) {

		TransitRouteStop stop = vehicle.getTransitRoute().getStops().getLast();
		List<PTPassengerAgent> passengers = agentRelocating.getOrDefault(stop.getStopFacility().getId(), List.of());

		for (ChainedDeparture chain : departures) {
			TransitRoute route = scenario.getTransitSchedule().getTransitLines()
				.get(chain.getChainedTransitLineId())
				.getRoutes().get(chain.getChainedRouteId());

			Departure departure = route.getDepartures().get(chain.getChainedDepartureId());

			Id<Vehicle> newVehicle = departure.getVehicleId();

			boolean sameVehicle = newVehicle.equals(vehicle.getVehicle().getId());

			// The next vehicle is waiting for its activity to end so that it can start departing
			var nextVehicle = internalInterface.getMobsim().getVehicles().get(newVehicle);
			// The code I found below assumes that we have a transit driver. This means we can also assume that we have a TransitVehicle. janek feb' 26
			assert nextVehicle instanceof TransitVehicle : "Expected TransitVehicle, but got " + nextVehicle.getClass().getSimpleName();
			var nextTransitVehicle = (TransitVehicle) nextVehicle;
			var nextDriver = nextTransitVehicle.getDriver();

			if (!sameVehicle) {

				int left = agentTracker.trackVehicleArrival(chain.getChainedDepartureId());
				// Depart if all required trains for this departure have arrived
				if (left <= 0) {
					// It would be nicer to have this functionality in the TransitDriver interface. Yet, I don't want to refactor everything here,
					// so we leave it as is for now. janek feb' 26
					//AbstractTransitDriverAgent driver = (AbstractTransitDriverAgent) nextTransitVehicle.getDriver();
					((AbstractTransitDriverAgent) nextDriver).setReadyForDeparture(time);
					internalInterface.getMobsim().rescheduleActivityEnd(nextDriver);
				}
			}

			Iterator<PTPassengerAgent> it = passengers.iterator();

			while (it.hasNext()) {

				PTPassengerAgent passenger = it.next();

				// Use the chained departure if it contains a stop the passenger uses as an exit stop
				if (route.getStops().stream().map(TransitRouteStop::getStopFacility).noneMatch(passenger::getExitAtStop))
					continue;


				// Native arrival has already detached the driver in a same-vehicle chain.
				// The resolved chain identifies the target service without reading that driver.
				eventsManager.processEvent(new PersonContinuesInVehicleEvent(time, passenger.getId(), vehicle.getVehicle().getId(), newVehicle,
					route.getStops().getFirst().getStopFacility().getId(),
					chain.getChainedTransitLineId(),
					route.getId()
				));

				nextTransitVehicle.addPassenger(passenger);
				passenger.setVehicle(nextTransitVehicle);
				it.remove();
			}
		}

		if (!passengers.isEmpty()) {
			throw new IllegalStateException("There are still passengers at stop " + stop.getStopFacility().getId() + " that were not relocated to the next vehicle: " + passengers);
		}
	}
}

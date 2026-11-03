/* *********************************************************************** *
 * project: org.matsim.*
 * TransitStop.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2009 by the members listed in the COPYING,        *
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

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.core.api.experimental.events.AgentWaitingForPtEvent;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.mobsim.qsim.AgentTracker;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author mrieser
 */
public class TransitStopAgentTracker implements AgentTracker {

	private final static Logger log = LogManager.getLogger(TransitStopAgentTracker.class);

	private final EventsManager events;
	private final Map<Id<TransitStopFacility>, List<PTPassengerAgent>> agentsAtStops = new ConcurrentHashMap<>();
	private final Map<Id<TransitStopFacility>, Map<PTPassengerAgent, QueueArrival>> queueArrivals = new ConcurrentHashMap<>();
	private final AtomicLong queueSequence = new AtomicLong();

	/** One physical waiting episode, retained when a train cannot take the agent. */
	public record QueueArrival(double readyTimeSeconds, long sequence) {
		public QueueArrival {
			if (!Double.isFinite(readyTimeSeconds) || readyTimeSeconds < 0 || sequence < 0)
				throw new IllegalArgumentException("Queue arrival requires a finite nonnegative time and sequence");
		}
	}

	/**
	 * Map of departure ids to the number of chained departures that need to arrive.
	 */
	private final Object2IntMap<Id<Departure>> waitingDepartures;

	public TransitStopAgentTracker(EventsManager events) {
		this.events = events;
		this.waitingDepartures = new Object2IntLinkedOpenHashMap<>();
	}

	public TransitStopAgentTracker(final EventsManager events, TransitSchedule schedule) {
		this.events = events;
		this.waitingDepartures = UmlaufCache.calculateDeparturesDependingOnChains(schedule);
	}

	public void addAgentToStop(final double now, final PTPassengerAgent agent, final Id<TransitStopFacility> stopId) {
		if (stopId == null) {
			throw new NullPointerException("stop must not be null.");
		}
		List<PTPassengerAgent> agents = this.agentsAtStops.computeIfAbsent(stopId,
				ignored -> new CopyOnWriteArrayList<>());
		// Publish metadata before the candidate becomes visible to a vehicle. The
		// same stop lock makes repeated registration idempotent: neither the queue
		// entry, its original clock nor the waiting event may be duplicated.
		synchronized (agents) {
			Map<PTPassengerAgent, QueueArrival> arrivals = queueArrivals.computeIfAbsent(stopId,
					ignored -> new ConcurrentHashMap<>());
			if (arrivals.containsKey(agent)) return;
			arrivals.put(agent, new QueueArrival(now, queueSequence.getAndIncrement()));
			agents.add(agent);
		}
		Id<TransitStopFacility> destinationStopId = agent.getDesiredDestinationStopId();
		events.processEvent(new AgentWaitingForPtEvent(now, agent.getId(), stopId, destinationStopId));
	}

	public void removeAgentFromStop(final PTPassengerAgent agent, final Id<TransitStopFacility> stopId) {
		if (stopId == null) {
			throw new NullPointerException("stopId must not be null.");
		}
		List<PTPassengerAgent> agents = this.agentsAtStops.get(stopId);
		if (agents != null) {
			synchronized (agents) {
				if (!agents.remove(agent)) {
					log.error("Agent " + agent.getId() + " could not be removed from waiting at stop " + stopId);
				} else {
					Map<PTPassengerAgent, QueueArrival> arrivals = queueArrivals.get(stopId);
					if (arrivals != null) arrivals.remove(agent);
				}
			}
		} else {
			log.error("Agent " + agent.getId() + " could not be removed from waiting at stop " + stopId + " since agents list was null.");
		}
	}

	/** Missing metadata is an error for explicit arrival-based policies, never an ID fallback. */
	public QueueArrival getQueueArrival(PTPassengerAgent agent, Id<TransitStopFacility> stopId) {
		Map<PTPassengerAgent, QueueArrival> arrivals = queueArrivals.get(stopId);
		QueueArrival arrival = arrivals == null ? null : arrivals.get(agent);
		if (arrival == null)
			throw new IllegalStateException("No native queue arrival for agent " + agent.getId() + " at stop " + stopId);
		return arrival;
	}

	/** Clears waiting episodes and their clocks together at the end of a simulation day. */
	public void clearWaitingAgents() {
		agentsAtStops.clear();
		queueArrivals.clear();
		queueSequence.set(0);
	}

	/**
	 * Tracks the arrival of a vehicle required for a chained departure and decreases the number of chained departures that need to arrive.
	 * @return number of chained departures that still need to arrive for this departure.
	 */
	public int trackVehicleArrival(Id<Departure> chainedDepartureId) {
		return waitingDepartures.mergeInt(chainedDepartureId, -1, Integer::sum);
	}


	@Override
	public List<PTPassengerAgent> getAgentsAtFacility(final Id<TransitStopFacility> stopId) {
		List<PTPassengerAgent> agents = this.agentsAtStops.get(stopId);
		if (agents == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(agents);
	}

	public Map<Id<TransitStopFacility>, List<PTPassengerAgent>> getAgentsAtStop() {
		return this.agentsAtStops;
	}
}

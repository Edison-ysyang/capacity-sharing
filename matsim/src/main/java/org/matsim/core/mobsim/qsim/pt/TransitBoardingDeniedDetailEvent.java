package org.matsim.core.mobsim.qsim.pt;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.HasPersonId;
import org.matsim.api.core.v01.population.Person;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

import java.util.Map;
import java.util.Objects;

/**
 * Diagnostic counterpart of one native BoardingDeniedEvent, not an additional denial.
 * Writers attach experiment/iteration context. Missing departure IDs remain unavailable.
 * Occupancy is the snapshot at the failed admission, including earlier accepted candidates;
 * event time follows the native final-stop-call denial event time.
 */
public final class TransitBoardingDeniedDetailEvent extends Event implements HasPersonId {
	public static final String EVENT_TYPE = "TransitBoardingDeniedDetail";
	public static final String SCHEMA_VERSION = "1";
	private final Id<Person> personId;
	private final Id<Vehicle> vehicleId;
	private final Id<TransitLine> lineId;
	private final Id<TransitRoute> routeId;
	private final Id<Departure> departureId;
	private final Id<TransitStopFacility> stopId;
	private final TransitBoardingPolicy.DenialReason reason;
	private final TransitBoardingPolicy.DenialDetails details;

	public TransitBoardingDeniedDetailEvent(double time, Id<Person> personId, Id<Vehicle> vehicleId,
			Id<TransitLine> lineId, Id<TransitRoute> routeId, Id<Departure> departureId,
			Id<TransitStopFacility> stopId, TransitBoardingPolicy.DenialReason reason,
			TransitBoardingPolicy.DenialDetails details) {
		super(time);
		this.personId = Objects.requireNonNull(personId);
		this.vehicleId = Objects.requireNonNull(vehicleId);
		this.lineId = lineId;
		this.routeId = routeId;
		this.departureId = departureId;
		this.stopId = Objects.requireNonNull(stopId);
		this.reason = Objects.requireNonNull(reason);
		this.details = Objects.requireNonNull(details);
	}

	@Override public String getEventType() { return EVENT_TYPE; }
	@Override public Id<Person> getPersonId() { return personId; }
	public Id<Vehicle> getVehicleId() { return vehicleId; }
	public Id<TransitLine> getLineId() { return lineId; }
	public Id<TransitRoute> getRouteId() { return routeId; }
	public Id<Departure> getDepartureId() { return departureId; }
	public Id<TransitStopFacility> getStopId() { return stopId; }
	public TransitBoardingPolicy.DenialReason getReason() { return reason; }
	public TransitBoardingPolicy.DenialDetails getDetails() { return details; }

	@Override
	public Map<String, String> getAttributes() {
		Map<String, String> attributes = super.getAttributes();
		attributes.put("schemaVersion", SCHEMA_VERSION);
		attributes.put("person", personId.toString());
		attributes.put("vehicle", vehicleId.toString());
		attributes.put("transitLine", lineId == null ? "" : lineId.toString());
		attributes.put("transitRoute", routeId == null ? "" : routeId.toString());
		attributes.put("departure", departureId == null ? "" : departureId.toString());
		attributes.put("stop", stopId.toString());
		attributes.put("denialReason", reason.name());
		attributes.put("actorClass", details.actorClass().name());
		attributes.put("passengerOccupancy", Integer.toString(details.passengers()));
		attributes.put("freightOccupancy", Integer.toString(details.freight()));
		attributes.put("nativeCapacity", Integer.toString(details.nativeCapacity()));
		attributes.put("freightShareCap", finiteOrUnavailable(details.freightShareCap()));
		attributes.put("passengerLoadGate", finiteOrUnavailable(details.passengerLoadGate()));
		return attributes;
	}

	private static String finiteOrUnavailable(double value) {
		return Double.isFinite(value) ? Double.toString(value) : "";
	}
}

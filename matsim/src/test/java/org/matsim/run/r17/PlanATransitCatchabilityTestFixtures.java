package org.matsim.run.r17;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.List;

final class PlanATransitCatchabilityTestFixtures {
	private PlanATransitCatchabilityTestFixtures() {
	}

	static TransitSchedule emptySchedule() {
		return new TransitScheduleFactoryImpl().createTransitSchedule();
	}

	static void addRoute(
			TransitSchedule schedule,
			String lineName,
			String routeName,
			String accessName,
			String egressName,
			double accessArrivalOffset,
			double accessDepartureOffset,
			double egressArrivalOffset,
			boolean awaitDeparture,
			double... departureBaseTimes) {
		TransitScheduleFactory factory = schedule.getFactory();
		Id<TransitLine> lineId = Id.create(lineName, TransitLine.class);
		TransitLine line = schedule.getTransitLines().get(lineId);
		if (line == null) {
			line = factory.createTransitLine(lineId);
			schedule.addTransitLine(line);
		}
		TransitStopFacility access = facility(schedule, accessName, 0.0);
		TransitStopFacility egress = facility(schedule, egressName, 100.0);
		TransitRouteStop accessStop = factory.createTransitRouteStopBuilder(access)
				.arrivalOffset(accessArrivalOffset)
				.departureOffset(accessDepartureOffset)
				.awaitDepartureTime(awaitDeparture)
				.allowBoarding(true)
				.allowAlighting(false)
				.build();
		TransitRouteStop egressStop = factory.createTransitRouteStopBuilder(egress)
				.arrivalOffset(egressArrivalOffset)
				.departureOffset(egressArrivalOffset)
				.awaitDepartureTime(true)
				.allowBoarding(false)
				.allowAlighting(true)
				.build();
		TransitRoute route = factory.createTransitRoute(
				Id.create(routeName, TransitRoute.class),
				null,
				List.of(accessStop, egressStop),
				"rail");
		for (int index = 0; index < departureBaseTimes.length; index++) {
			Departure departure = factory.createDeparture(
					Id.create(routeName + "-departure-" + index, Departure.class),
					departureBaseTimes[index]);
			route.addDeparture(departure);
		}
		line.addRoute(route);
	}

	static DefaultTransitPassengerRoute passengerRoute(
			String lineName,
			String routeName,
			String accessName,
			String egressName,
			double boardingTime,
			double plannedTravelTime) {
		DefaultTransitPassengerRoute route = new DefaultTransitPassengerRoute(
				null,
				null,
				Id.create(accessName, TransitStopFacility.class),
				Id.create(egressName, TransitStopFacility.class),
				Id.create(lineName, TransitLine.class),
				Id.create(routeName, TransitRoute.class));
		route.setBoardingTime(boardingTime);
		route.setTravelTime(plannedTravelTime);
		return route;
	}

	static void addArrivalOnlyRoute(
			TransitSchedule schedule,
			String lineName,
			String routeName,
			String accessName,
			String egressName,
			double departureBaseTime) {
		TransitScheduleFactory factory = schedule.getFactory();
		TransitLine line = factory.createTransitLine(Id.create(lineName, TransitLine.class));
		TransitStopFacility access = facility(schedule, accessName, 0.0);
		TransitStopFacility egress = facility(schedule, egressName, 100.0);
		TransitRouteStop accessStop = factory.createTransitRouteStop(
				access,
				OptionalTime.defined(10.0),
				OptionalTime.undefined());
		accessStop.setAllowBoarding(true);
		accessStop.setAllowAlighting(false);
		accessStop.setAwaitDepartureTime(true);
		TransitRouteStop egressStop = factory.createTransitRouteStop(egress, 60.0, 60.0);
		egressStop.setAllowBoarding(false);
		egressStop.setAllowAlighting(true);
		egressStop.setAwaitDepartureTime(true);
		TransitRoute route = factory.createTransitRoute(
				Id.create(routeName, TransitRoute.class),
				null,
				List.of(accessStop, egressStop),
				"rail");
		route.addDeparture(factory.createDeparture(
				Id.create(routeName + "-departure", Departure.class), departureBaseTime));
		line.addRoute(route);
		schedule.addTransitLine(line);
	}

	private static TransitStopFacility facility(
			TransitSchedule schedule,
			String name,
			double x) {
		Id<TransitStopFacility> id = Id.create(name, TransitStopFacility.class);
		TransitStopFacility existing = schedule.getFacilities().get(id);
		if (existing != null) {
			return existing;
		}
		TransitStopFacility facility =
				schedule.getFactory().createTransitStopFacility(id, new Coord(x, 0.0), false);
		schedule.addStopFacility(facility);
		return facility;
	}
}

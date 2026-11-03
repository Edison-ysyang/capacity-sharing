package org.matsim.contrib.cotransport;

import ch.sbb.matsim.routing.pt.raptor.OccupancyData;
import ch.sbb.matsim.routing.pt.raptor.RaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorParametersForPerson;
import ch.sbb.matsim.routing.pt.raptor.RaptorRouteSelector;
import ch.sbb.matsim.routing.pt.raptor.RaptorStopFinder;
import ch.sbb.matsim.routing.pt.raptor.RaptorTransferCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorUtils;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptor;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorRoutingModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.router.RoutingModule;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.MinimalTransferTimes;
import org.matsim.pt.router.TransitScheduleChangedEvent;
import org.matsim.pt.router.TransitScheduleChangedEventHandler;

public final class PassengerOnlyPtRoutingModuleProvider implements Provider<RoutingModule>, TransitScheduleChangedEventHandler {
	private final Scenario scenario;
	private final RoutingModule transitWalkRouter;
	private final RaptorParametersForPerson raptorParametersForPerson;
	private final RaptorRouteSelector routeSelector;
	private final Provider<RaptorStopFinder> stopFinderProvider;
	private final OccupancyData occupancyData;
	private final RaptorInVehicleCostCalculator inVehicleCostCalculator;
	private final RaptorTransferCostCalculator transferCostCalculator;
	private final Config config;
	private final CoTransportSettings settings;
	private final Provider<SwissRailRaptor> baselineRaptorProvider;
	private SwissRailRaptorData passengerOnlyData;
	private TransitSchedule passengerOnlySchedule;

	@Inject
	public PassengerOnlyPtRoutingModuleProvider(Scenario scenario, @Named(TransportMode.walk) RoutingModule transitWalkRouter,
			RaptorParametersForPerson raptorParametersForPerson, RaptorRouteSelector routeSelector,
			Provider<RaptorStopFinder> stopFinderProvider, OccupancyData occupancyData,
			RaptorInVehicleCostCalculator inVehicleCostCalculator, RaptorTransferCostCalculator transferCostCalculator,
			Config config, CoTransportSettings settings, Provider<SwissRailRaptor> baselineRaptorProvider) {
		this.scenario = scenario;
		this.transitWalkRouter = transitWalkRouter;
		this.raptorParametersForPerson = raptorParametersForPerson;
		this.routeSelector = routeSelector;
		this.stopFinderProvider = stopFinderProvider;
		this.occupancyData = occupancyData;
		this.inVehicleCostCalculator = inVehicleCostCalculator;
		this.transferCostCalculator = transferCostCalculator;
		this.config = config;
		this.settings = settings;
		this.baselineRaptorProvider = baselineRaptorProvider;
	}

	@Override
	public synchronized RoutingModule get() {
		TransitSchedule live = scenario.getTransitSchedule();
		if (!hasDedicatedLine(live)) {
			// Pre-activation must use the same factory, data cache and routing
			// semantics as Control.  A hand-built but logically equivalent Raptor
			// changed initial road paths and violated the exact fingerprint gate.
			return new SwissRailRaptorRoutingModule(baselineRaptorProvider.get(), live,
					scenario.getNetwork(), transitWalkRouter);
		}
		// Match SwissRailRaptorFactory's lifecycle: cache only immutable prepared
		// data, then create one mutable Raptor search instance per routing module.
		// PlanRouter invokes this provider concurrently; sharing a single Raptor
		// made its per-search labels race and produced null boarding/from labels.
		if (passengerOnlyData == null) {
			passengerOnlySchedule = createPassengerOnlySchedule();
			passengerOnlyData = SwissRailRaptorData.create(passengerOnlySchedule, scenario.getTransitVehicles(),
					RaptorUtils.createStaticConfig(config), scenario.getNetwork(), occupancyData);
		}
		SwissRailRaptor raptor = new SwissRailRaptor(passengerOnlyData, raptorParametersForPerson, routeSelector,
				stopFinderProvider.get(), inVehicleCostCalculator, transferCostCalculator);
		return new SwissRailRaptorRoutingModule(raptor, passengerOnlySchedule, scenario.getNetwork(), transitWalkRouter);
	}

	@Override
	public synchronized void handleEvent(TransitScheduleChangedEvent event) {
		passengerOnlyData = null;
		passengerOnlySchedule = null;
	}

	private TransitSchedule createPassengerOnlySchedule() {
		TransitSchedule original = scenario.getTransitSchedule();
		// Before the intervention is activated the live schedule is exactly the
		// baseline passenger schedule.  Returning it directly is both semantically
		// correct (there is no freight-only line to exclude) and preserves the
		// proven Raptor schedule graph for pre-activation replanning.  Constructing
		// a shallow filtered copy here made an otherwise byte-identical baseline
		// fail inside SwissRailRaptor during iteration-1 route choice.
		if (!hasDedicatedLine(original)) {
			return original;
		}
		Id<TransitLine> dedicatedLineId = Id.create(settings.dedicatedFreightLineId(), TransitLine.class);
		TransitSchedule filtered = original.getFactory().createTransitSchedule();
		for (var facility : original.getFacilities().values()) {
			filtered.addStopFacility(facility);
		}
		for (TransitLine line : original.getTransitLines().values()) {
			if (!line.getId().equals(dedicatedLineId)) {
				filtered.addTransitLine(line);
			}
		}
		copyTransfersAndAttributes(original, filtered);
		return filtered;
	}

	private boolean hasDedicatedLine(TransitSchedule schedule) {
		String lineId = settings.dedicatedFreightLineId();
		return lineId != null && !lineId.isBlank()
				&& schedule.getTransitLines().containsKey(Id.create(lineId, TransitLine.class));
	}

	static void copyTransfersAndAttributes(TransitSchedule source, TransitSchedule target) {
		MinimalTransferTimes.MinimalTransferTimesIterator iterator = source.getMinimalTransferTimes().iterator();
		while (iterator.hasNext()) {
			iterator.next();
			target.getMinimalTransferTimes().set(iterator.getFromStopId(), iterator.getToStopId(),
					iterator.getSeconds());
		}
		source.getAttributes().getAsMap().forEach(target.getAttributes()::putAttribute);
	}
}

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
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.RoutingModule;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.router.TransitScheduleChangedEvent;
import org.matsim.pt.router.TransitScheduleChangedEventHandler;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class FreightOnlyPtRoutingModuleProvider implements Provider<RoutingModule>, TransitScheduleChangedEventHandler {
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
	private final TransitSchedule activatedFreightSchedule;
	private SwissRailRaptorData freightOnlyData;
	private TransitSchedule freightOnlySchedule;
	private final AtomicLong scheduleGeneration = new AtomicLong();

	@Inject
	public FreightOnlyPtRoutingModuleProvider(Scenario scenario,
			@Named(TransportMode.walk) RoutingModule transitWalkRouter,
			RaptorParametersForPerson raptorParametersForPerson, RaptorRouteSelector routeSelector,
			Provider<RaptorStopFinder> stopFinderProvider, OccupancyData occupancyData,
			RaptorInVehicleCostCalculator inVehicleCostCalculator,
			RaptorTransferCostCalculator transferCostCalculator, Config config, CoTransportSettings settings,
			Provider<SwissRailRaptor> baselineRaptorProvider) {
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
		// Revisioned scenarios deliberately start with the Control-identical baseline.
		// Retain a private activated view for freight routing without forcing the live
		// scenario to contain the dedicated line before activation.
		this.activatedFreightSchedule = settings.activatedTransitScheduleFile() == null
				? createFreightOnlySchedule(scenario.getTransitSchedule(), true)
				: createFreightOnlySchedule(loadActivatedSchedule(settings), true);
	}

	@Override
	public RoutingModule get() {
		/*
		 * TripRouter eagerly calls every registered RoutingModule provider when it is
		 * constructed, including freight_pt while freight is inactive. Creating a
		 * Raptor here used to create a DefaultRaptorStopFinder and consume a global,
		 * order-dependent MatsimRandom instance before QSim. That changed otherwise
		 * Control-identical pre-activation road trajectories. This wrapper is inert
		 * until a freight leg actually asks to be routed.
		 */
		return new DeferredRoutingModule(scheduleGeneration::get, this::createFreightRoutingModule);
	}

	private synchronized RoutingModule createFreightRoutingModule() {
		if (!usesDedicatedLine()) {
			// Shared freight uses the original native routing factory and live
			// passenger schedule, but only when a freight route is requested.
			return new SwissRailRaptorRoutingModule(baselineRaptorProvider.get(),
					scenario.getTransitSchedule(), scenario.getNetwork(), transitWalkRouter);
		}
		// Keep prepared timetable data shared, but never share a mutable Raptor
		// search instance across concurrent PlanRouter workers.
		if (freightOnlyData == null) {
			freightOnlySchedule = createFreightOnlySchedule();
			freightOnlyData = SwissRailRaptorData.create(freightOnlySchedule,
					scenario.getTransitVehicles(), RaptorUtils.createStaticConfig(config),
					scenario.getNetwork(), occupancyData);
		}
		SwissRailRaptor raptor = new SwissRailRaptor(freightOnlyData, raptorParametersForPerson, routeSelector,
				stopFinderProvider.get(), inVehicleCostCalculator, transferCostCalculator);
		return new SwissRailRaptorRoutingModule(raptor, freightOnlySchedule,
				scenario.getNetwork(), transitWalkRouter);
	}

	@Override
	public synchronized void handleEvent(TransitScheduleChangedEvent event) {
		freightOnlyData = null;
		freightOnlySchedule = null;
		scheduleGeneration.incrementAndGet();
	}

	private TransitSchedule createFreightOnlySchedule() {
		TransitSchedule live = scenario.getTransitSchedule();
		if (!usesDedicatedLine()) {
			return live;
		}
		Id<TransitLine> dedicatedLineId = Id.create(settings.dedicatedFreightLineId(), TransitLine.class);
		if (!live.getTransitLines().containsKey(dedicatedLineId)) {
			return activatedFreightSchedule;
		}
		return createFreightOnlySchedule(live, false);
	}

	private TransitSchedule createFreightOnlySchedule(TransitSchedule original, boolean initialCapture) {
		if (!usesDedicatedLine()) {
			return original;
		}
		TransitSchedule filtered = original.getFactory().createTransitSchedule();
		for (var facility : original.getFacilities().values()) {
			filtered.addStopFacility(facility);
		}
		Id<TransitLine> dedicatedLineId = Id.create(settings.dedicatedFreightLineId(), TransitLine.class);
		TransitLine dedicatedLine = original.getTransitLines().get(dedicatedLineId);
		if (dedicatedLine == null) {
			throw new IllegalStateException((initialCapture ? "Activated" : "Live")
					+ " dedicated freight line not found: " + dedicatedLineId);
		}
		filtered.addTransitLine(dedicatedLine);
		PassengerOnlyPtRoutingModuleProvider.copyTransfersAndAttributes(original, filtered);
		return filtered;
	}

	private boolean usesDedicatedLine() {
		return settings.dedicatedFreightLineId() != null && !settings.dedicatedFreightLineId().isBlank();
	}

	private static TransitSchedule loadActivatedSchedule(CoTransportSettings settings) {
		Scenario holder = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new TransitScheduleReader(holder).readFile(settings.activatedTransitScheduleFile().toString());
		return holder.getTransitSchedule();
	}

	/**
	 * One instance is created per TripRouter, but its expensive routing delegate
	 * is created only if that TripRouter actually routes a freight leg. Schedule
	 * changes invalidate an already-created delegate without touching dormant
	 * wrappers during a pre-activation phase.
	 */
	static final class DeferredRoutingModule implements RoutingModule {
		private final LongSupplier generationSupplier;
		private final Supplier<RoutingModule> delegateFactory;
		private long installedGeneration = Long.MIN_VALUE;
		private RoutingModule delegate;

		DeferredRoutingModule(LongSupplier generationSupplier, Supplier<RoutingModule> delegateFactory) {
			this.generationSupplier = generationSupplier;
			this.delegateFactory = delegateFactory;
		}

		@Override
		public List<? extends org.matsim.api.core.v01.population.PlanElement> calcRoute(
				org.matsim.core.router.RoutingRequest request) {
			return delegateForCurrentGeneration().calcRoute(request);
		}

		private synchronized RoutingModule delegateForCurrentGeneration() {
			long currentGeneration = generationSupplier.getAsLong();
			if (delegate == null || installedGeneration != currentGeneration) {
				delegate = delegateFactory.get();
				installedGeneration = currentGeneration;
			}
			return delegate;
		}
	}
}

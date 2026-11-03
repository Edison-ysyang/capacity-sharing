package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.matsim.core.router.RoutingModule;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FreightOnlyPtRoutingModuleProviderTest {
	@Test
	void deferredFreightModuleDoesNotCreateRaptorDelegateUntilAFreightLegIsRouted() {
		AtomicLong generation = new AtomicLong(4);
		AtomicInteger delegateCreations = new AtomicInteger();
		AtomicInteger routedFreightLegs = new AtomicInteger();
		RoutingModule deferred = new FreightOnlyPtRoutingModuleProvider.DeferredRoutingModule(
				generation::get,
				() -> {
					delegateCreations.incrementAndGet();
					return request -> {
						routedFreightLegs.incrementAndGet();
						return List.of();
					};
				});

		// This is the TripRouter-construction state: no Raptor / StopFinder delegate exists yet.
		assertEquals(0, delegateCreations.get());

		deferred.calcRoute(null);
		deferred.calcRoute(null);
		assertEquals(1, delegateCreations.get());
		assertEquals(2, routedFreightLegs.get());

		// Activation or deactivation emits TransitScheduleChangedEvent and advances the generation.
		generation.incrementAndGet();
		deferred.calcRoute(null);
		assertEquals(2, delegateCreations.get());
		assertEquals(3, routedFreightLegs.get());
	}
}

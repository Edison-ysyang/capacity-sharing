package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FreightOrderRegistryTest {
	@TempDir
	Path tempDirectory;

	@Test
	void derivesCapacityFromEtaOnlyWhenCsvDoesNotSpecifyUnits() throws Exception {
		Path orders = tempDirectory.resolve("implicit.csv");
		Files.writeString(orders,
				"order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority\n"
						+ "implicit,08:00:00,09:00:00,0.41,2.0,standard,1\n");
		FreightOrderRegistry registry = new FreightOrderRegistry(CoTransportSettings.builder(orders)
				.freightEquivalentPassengersPerM3(5.0)
				.build());

		assertEquals(3, registry.orderForPerson(Id.createPersonId("implicit")).capacityUnits());
		assertTrue(registry.isFreightPerson(Id.createPersonId("implicit_cap_02")));
		assertTrue(registry.isFreightPerson(Id.createPersonId("implicit_cap_03")));
		assertFalse(registry.isFreightPerson(Id.createPersonId("implicit_cap_04")));
	}

	@Test
	void explicitCapacityUnitsOverrideEta() throws Exception {
		Path orders = tempDirectory.resolve("explicit.csv");
		Files.writeString(orders,
				"order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
						+ "explicit,08:00:00,09:00:00,0.01,2.0,standard,1,4\n");
		FreightOrderRegistry registry = new FreightOrderRegistry(CoTransportSettings.builder(orders)
				.freightEquivalentPassengersPerM3(999.0)
				.build());

		assertEquals(4, registry.orderForPerson(Id.createPersonId("explicit")).capacityUnits());
		assertTrue(registry.isFreightPerson(Id.createPersonId("explicit_cap_04")));
		assertFalse(registry.isFreightPerson(Id.createPersonId("explicit_cap_05")));
	}

	@Test
	void populationValidationRejectsMissingUnitsWithoutMaterializingPersons() throws Exception {
		FreightOrderRegistry registry = twoUnitRegistry();
		Population population = population("parcel");
		assertThrows(IllegalStateException.class, () -> registry.validatePopulation(population));
		assertEquals(1, population.getPersons().size());
		assertEquals(2, registry.unitIds().size());
		assertThrows(UnsupportedOperationException.class, () -> registry.unitIds().clear());
	}

	@Test
	void populationValidationRejectsExtraFreightAndPreservesOrdinaryPassengers() throws Exception {
		FreightOrderRegistry registry = twoUnitRegistry();
		Population population = population("parcel", "parcel_cap_02", "ordinary");
		assertEquals(2, registry.validatePopulation(population).prebuiltUnits());
		assertEquals(3, population.getPersons().size());
		population.getPersons().get(Id.createPersonId("ordinary")).getAttributes().putAttribute("subpopulation", "freight");
		assertThrows(IllegalStateException.class, () -> registry.validatePopulation(population));
	}

	@Test
	void fixedPlanValidationRequiresOnePrebuiltSelectedPlanAndDoesNotCreateIt() throws Exception {
		FreightOrderRegistry registry = twoUnitRegistry();
		Population population = population("parcel", "parcel_cap_02");
		population.getPersons().values().forEach(person ->
				person.getAttributes().putAttribute("subpopulation", "freight"));
		assertThrows(IllegalStateException.class, () -> registry.validateFixedPlans(population));
		for (var person : population.getPersons().values()) {
			var plan = population.getFactory().createPlan();
			plan.addActivity(population.getFactory().createActivityFromLinkId("freight_destination", Id.createLinkId("link")));
			person.addPlan(plan);
			person.setSelectedPlan(plan);
		}
		registry.validateFixedPlans(population);
		population.getPersons().get(Id.createPersonId("parcel")).addPlan(population.getFactory().createPlan());
		assertThrows(IllegalStateException.class, () -> registry.validateFixedPlans(population));
	}

	@Test
	void fixedPlanValidationAcceptsFreightSubpopulationWithoutMutatingPlans() throws Exception {
		FreightOrderRegistry registry = twoUnitRegistry();
		Population population = fixedFreightPopulation();
		Runnable assertUnchanged = snapshotPopulation(population);
		registry.validateFixedPlans(population);
		assertUnchanged.run();
	}

	@Test
	void fixedPlanValidationRejectsMissingFreightSubpopulationWithoutMutatingPlans() throws Exception {
		assertInvalidFreightSubpopulation(null, false);
	}

	@Test
	void fixedPlanValidationRejectsNullFreightSubpopulationWithoutMutatingPlans() throws Exception {
		assertInvalidFreightSubpopulation(null, true);
	}

	@Test
	void fixedPlanValidationRejectsPassengerSubpopulationWithoutMutatingPlans() throws Exception {
		assertInvalidFreightSubpopulation("passenger", true);
	}

	@Test
	void fixedPlanValidationRejectsEmptyFreightSubpopulationWithoutMutatingPlans() throws Exception {
		assertInvalidFreightSubpopulation("", true);
	}

	@Test
	void fixedPlanValidationRejectsWhitespaceFreightSubpopulationWithoutMutatingPlans() throws Exception {
		assertInvalidFreightSubpopulation(" \t", true);
	}

	@Test
	void fixedPlanValidationRejectsNonStringFreightSubpopulationWithoutMutatingPlans() throws Exception {
		assertInvalidFreightSubpopulation(42, true);
	}

	@Test
	void injectedUnitizerCannotViolateCountOrIdentityConservation() throws Exception {
		var registry = twoUnitRegistry();
		Path orders = tempDirectory.resolve("two_units.csv");
		for (var ids : java.util.List.of(java.util.List.of("parcel"), java.util.List.of("parcel", "parcel"),
				java.util.List.of("wrong", "other"))) {
			FreightUnitizer broken = new FreightUnitizer() {
				public int capacityUnits(double volume, Integer explicit) { return 2; }
				public java.util.List<String> unitIds(String orderId, int count) { return ids; }
			};
			assertThrows(IllegalStateException.class, () -> new FreightOrderRegistry(CoTransportSettings.builder(orders).build(), broken));
		}
		assertEquals(2, registry.unitIds().size());
	}

	private FreightOrderRegistry twoUnitRegistry() throws Exception {
		Path orders = tempDirectory.resolve("two_units.csv");
		Files.writeString(orders, "order_id,release_time,deadline_time,volume_m3,mass_kg,service_class,priority,capacity_units\n"
				+ "parcel,08:00:00,09:00:00,1,2,standard,1,2\n");
		return new FreightOrderRegistry(CoTransportSettings.builder(orders).build());
	}

	private void assertInvalidFreightSubpopulation(Object value, boolean attributePresent) throws Exception {
		FreightOrderRegistry registry = twoUnitRegistry();
		// Check both the primary person and a secondary unit, not just the first registry entry.
		for (String unitId : List.of("parcel", "parcel_cap_02")) {
			Population population = fixedFreightPopulation();
			var attributes = population.getPersons().get(Id.createPersonId(unitId)).getAttributes();
			if (attributePresent) attributes.putAttribute("subpopulation", value);
			else attributes.removeAttribute("subpopulation");
			Runnable assertUnchanged = snapshotPopulation(population);
			try {
				// ID conservation is a separate responsibility from fixed-plan strategy eligibility.
				// The native attribute getter casts non-String values; the fixed-plan guard must
				// reject that case with its own diagnostic rather than leaking ClassCastException.
				if (value == null || value instanceof String) {
					assertEquals(2, registry.validatePopulation(population).prebuiltUnits());
				}
				IllegalStateException error = assertThrows(IllegalStateException.class,
						() -> registry.validateFixedPlans(population));
				assertTrue(error.getMessage().contains(unitId));
				assertTrue(error.getMessage().contains("subpopulation"));
			} finally {
				assertUnchanged.run();
			}
		}
	}

	private static Population fixedFreightPopulation() {
		Population population = population("parcel", "parcel_cap_02");
		for (Person person : population.getPersons().values()) {
			person.getAttributes().putAttribute("subpopulation", "freight");
			Plan plan = population.getFactory().createPlan();
			plan.addActivity(population.getFactory().createActivityFromLinkId("freight_destination", Id.createLinkId("link")));
			plan.setScore(3.5);
			plan.getAttributes().putAttribute("audit_marker", "preserve");
			person.addPlan(plan);
			person.setSelectedPlan(plan);
		}
		return population;
	}

	/** Retain identities and contents so validation cannot repair, replace or reselect plans. */
	private static Runnable snapshotPopulation(Population population) {
		Map<Id<Person>, Person> persons = new LinkedHashMap<>(population.getPersons());
		List<PersonSnapshot> snapshots = new ArrayList<>();
		for (Person person : persons.values()) {
			List<PlanSnapshot> plans = new ArrayList<>();
			for (Plan plan : person.getPlans()) {
				plans.add(new PlanSnapshot(plan, plan.getScore(), new HashMap<>(plan.getAttributes().getAsMap()),
						List.copyOf(plan.getPlanElements())));
			}
			snapshots.add(new PersonSnapshot(person, new HashMap<>(person.getAttributes().getAsMap()),
					person.getSelectedPlan(), plans));
		}
		return () -> {
			assertEquals(new ArrayList<>(persons.keySet()), new ArrayList<>(population.getPersons().keySet()));
			for (PersonSnapshot snapshot : snapshots) {
				Person person = snapshot.person();
				assertSame(person, population.getPersons().get(person.getId()));
				assertEquals(snapshot.attributes(), person.getAttributes().getAsMap());
				assertSame(snapshot.selectedPlan(), person.getSelectedPlan());
				assertEquals(snapshot.plans().size(), person.getPlans().size());
				for (int index = 0; index < snapshot.plans().size(); index++) {
					PlanSnapshot previous = snapshot.plans().get(index);
					Plan plan = person.getPlans().get(index);
					assertSame(previous.plan(), plan);
					assertEquals(previous.score(), plan.getScore());
					assertEquals(previous.attributes(), plan.getAttributes().getAsMap());
					assertEquals(previous.elements().size(), plan.getPlanElements().size());
					for (int element = 0; element < previous.elements().size(); element++) {
						assertSame(previous.elements().get(element), plan.getPlanElements().get(element));
					}
				}
			}
		};
	}

	private record PersonSnapshot(Person person, Map<String, Object> attributes, Plan selectedPlan,
			List<PlanSnapshot> plans) { }

	private record PlanSnapshot(Plan plan, Double score, Map<String, Object> attributes,
			List<PlanElement> elements) { }

	private static Population population(String... ids) {
		Population population = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
		for (String id : ids) population.addPerson(population.getFactory().createPerson(Id.createPersonId(id)));
		return population;
	}
}

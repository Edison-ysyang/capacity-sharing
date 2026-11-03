package org.matsim.contrib.cotransport;

import com.google.inject.Inject;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.scoring.ExperiencedPlansService;
import org.matsim.core.scoring.EventsToLegs;
import org.matsim.core.router.TripStructureUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PassengerOnlyIterationStats implements IterationEndsListener {
	private static final List<String> MODES = List.of("bike", "bus", "car", "pt", "ride", "walk");

	private final Population population;
	private final AnalysisMainModeIdentifier mainModeIdentifier;
	private final OutputDirectoryHierarchy controllerIO;
	private final int firstIteration;
	private final int referenceIteration;
	private final ExperiencedPlansService experiencedPlansService;
	private final Map<String, String> referenceModesByTrip = new LinkedHashMap<>();
	private final Map<String, Double> referenceDepartureTimesByTrip = new LinkedHashMap<>();

	@Inject
	public PassengerOnlyIterationStats(Population population, AnalysisMainModeIdentifier mainModeIdentifier,
			OutputDirectoryHierarchy controllerIO, ControllerConfigGroup controllerConfigGroup,
			ExperiencedPlansService experiencedPlansService, CoTransportSettings settings) {
		this.population = population;
		this.mainModeIdentifier = mainModeIdentifier;
		this.controllerIO = controllerIO;
		this.firstIteration = controllerConfigGroup.getFirstIteration();
		this.referenceIteration = referenceIteration(firstIteration, settings.activationIteration());
		this.experiencedPlansService = experiencedPlansService;
	}

	static int referenceIteration(int firstIteration, int activationIteration) {
		return Math.max(firstIteration, activationIteration - 1);
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Map<String, String> currentModesByTrip = new LinkedHashMap<>();
		Map<String, Double> currentDepartureTimesByTrip = new LinkedHashMap<>();
		for (String mode : MODES) {
			counts.put(mode, 0);
		}
		double scoreSum = 0.0;
		int scoredPersons = 0;
		int passengerPersons = 0;
		for (Person person : population.getPersons().values()) {
			if (!isPassenger(person)) {
				continue;
			}
			passengerPersons++;
			int tripIndex = 0;
			for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
				String mode = mainModeIdentifier.identifyMainMode(trip.getTripElements());
				counts.computeIfPresent(mode, (key, value) -> value + 1);
				String tripKey = person.getId() + "|" + tripIndex++;
				currentModesByTrip.put(tripKey, mode);
				if (trip.getOriginActivity().getEndTime().isDefined()) {
					currentDepartureTimesByTrip.put(tripKey, trip.getOriginActivity().getEndTime().seconds());
				}
			}
			Double score = person.getSelectedPlan().getScore();
			if (score != null && Double.isFinite(score)) {
				scoreSum += score;
				scoredPersons++;
			}
		}
		int totalTrips = counts.values().stream().mapToInt(Integer::intValue).sum();
		writeModeStats(event.getIteration(), counts, totalTrips);
		writeCompletedModeStats(event.getIteration(), passengerPersons);
		writeScoreStats(event.getIteration(), passengerPersons, scoredPersons,
				scoredPersons == 0 ? Double.NaN : scoreSum / scoredPersons);
		writePassengerServiceStats(event.getIteration());
		writeDepartureBins(event.getIteration(), currentModesByTrip, currentDepartureTimesByTrip);
		if (event.getIteration() == referenceIteration) {
			referenceModesByTrip.clear();
			referenceModesByTrip.putAll(currentModesByTrip);
			referenceDepartureTimesByTrip.clear();
			referenceDepartureTimesByTrip.putAll(currentDepartureTimesByTrip);
		}
		writeAdaptationStats(event.getIteration(), currentModesByTrip, currentDepartureTimesByTrip);
		writeModeTransitions(event.getIteration(), currentModesByTrip);
	}

	private void writePassengerServiceStats(int iteration) {
		List<Double> doorToDoorMinutes = new ArrayList<>();
		List<Double> ptDoorToDoorMinutes = new ArrayList<>();
		List<Double> ptWaitMinutes = new ArrayList<>();
		List<Double> ptInVehicleMinutes = new ArrayList<>();
		for (Map.Entry<org.matsim.api.core.v01.Id<Person>, Plan> entry
				: experiencedPlansService.getExperiencedPlans().entrySet()) {
			Person original = population.getPersons().get(entry.getKey());
			if (original == null || !isPassenger(original)) {
				continue;
			}
			for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(entry.getValue())) {
				if (!trip.getOriginActivity().getEndTime().isDefined()
						|| !trip.getDestinationActivity().getStartTime().isDefined()) {
					continue;
				}
				double doorSeconds = trip.getDestinationActivity().getStartTime().seconds()
						- trip.getOriginActivity().getEndTime().seconds();
				if (!Double.isFinite(doorSeconds) || doorSeconds < 0.0) {
					continue;
				}
				doorToDoorMinutes.add(doorSeconds / 60.0);
				String mode = mainModeIdentifier.identifyMainMode(trip.getTripElements());
				if (!"pt".equals(mode)) {
					continue;
				}
				double waitSeconds = 0.0;
				double inVehicleSeconds = 0.0;
				for (Leg leg : trip.getLegsOnly()) {
					Object boarding = leg.getAttributes().getAttribute(EventsToLegs.ENTER_VEHICLE_TIME_ATTRIBUTE_NAME);
					if (!(boarding instanceof Number number) || !leg.getDepartureTime().isDefined()) {
						continue;
					}
					double legWait = Math.max(0.0, number.doubleValue() - leg.getDepartureTime().seconds());
					double legTravel = leg.getTravelTime().isDefined() ? leg.getTravelTime().seconds() : 0.0;
					waitSeconds += legWait;
					inVehicleSeconds += Math.max(0.0, legTravel - legWait);
				}
				ptDoorToDoorMinutes.add(doorSeconds / 60.0);
				ptWaitMinutes.add(waitSeconds / 60.0);
				ptInVehicleMinutes.add(inVehicleSeconds / 60.0);
			}
		}
		Path path = Path.of(controllerIO.getOutputFilename("passenger_service_stats.csv"));
		try (BufferedWriter writer = writer(path, iteration)) {
			if (iteration == firstIteration) {
				writer.write("iteration;completed_passenger_trips;completed_pt_trips"
						+ ";door_to_door_mean_min;door_to_door_p50_min;door_to_door_p95_min"
						+ ";pt_door_to_door_mean_min;pt_door_to_door_p50_min;pt_door_to_door_p95_min"
						+ ";pt_wait_mean_min;pt_wait_p50_min;pt_wait_p95_min"
						+ ";pt_in_vehicle_mean_min;pt_in_vehicle_p50_min;pt_in_vehicle_p95_min\n");
			}
			writer.write(iteration + ";" + doorToDoorMinutes.size() + ";" + ptDoorToDoorMinutes.size()
					+ statistics(doorToDoorMinutes) + statistics(ptDoorToDoorMinutes)
					+ statistics(ptWaitMinutes) + statistics(ptInVehicleMinutes) + "\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String statistics(List<Double> values) {
		if (values.isEmpty()) {
			return ";;;";
		}
		List<Double> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
		return ";" + mean + ";" + quantile(sorted, 0.50) + ";" + quantile(sorted, 0.95);
	}

	private static double quantile(List<Double> sorted, double probability) {
		int index = Math.max(0, Math.min(sorted.size() - 1,
				(int) Math.ceil(probability * sorted.size()) - 1));
		return sorted.get(index);
	}

	private void writeDepartureBins(int iteration, Map<String, String> modes,
			Map<String, Double> departureTimes) {
		Map<String, Integer> bins = new LinkedHashMap<>();
		for (String mode : MODES) {
			for (int hour = 0; hour < 30; hour++) {
				bins.put(mode + "|" + hour, 0);
			}
		}
		for (Map.Entry<String, Double> entry : departureTimes.entrySet()) {
			String mode = modes.get(entry.getKey());
			int hour = Math.max(0, Math.min(29, (int) Math.floor(entry.getValue() / 3600.0)));
			String key = mode + "|" + hour;
			bins.computeIfPresent(key, (ignored, count) -> count + 1);
		}
		Path path = Path.of(controllerIO.getOutputFilename("passenger_departure_bins.csv"));
		try (BufferedWriter writer = writer(path, iteration)) {
			if (iteration == firstIteration) {
				writer.write("iteration;mode;bin_start_hour;bin_end_hour;selected_trip_count\n");
			}
			for (String mode : MODES) {
				for (int hour = 0; hour < 30; hour++) {
					writer.write(iteration + ";" + mode + ";" + hour + ";" + (hour + 1)
							+ ";" + bins.get(mode + "|" + hour) + "\n");
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void writeAdaptationStats(int iteration, Map<String, String> modes,
			Map<String, Double> departureTimes) {
		int compared = 0;
		int modeChanged = 0;
		int departureChanged = 0;
		List<Double> absoluteDepartureShiftMinutes = new ArrayList<>();
		if (!referenceModesByTrip.isEmpty()) {
			for (Map.Entry<String, String> entry : referenceModesByTrip.entrySet()) {
				String currentMode = modes.get(entry.getKey());
				if (currentMode == null) {
					continue;
				}
				compared++;
				if (!entry.getValue().equals(currentMode)) {
					modeChanged++;
				}
				Double referenceDeparture = referenceDepartureTimesByTrip.get(entry.getKey());
				Double currentDeparture = departureTimes.get(entry.getKey());
				if (referenceDeparture != null && currentDeparture != null) {
					double shift = Math.abs(currentDeparture - referenceDeparture) / 60.0;
					absoluteDepartureShiftMinutes.add(shift);
					if (shift > 1.0 / 60.0) {
						departureChanged++;
					}
				}
			}
		}
		Path path = Path.of(controllerIO.getOutputFilename("passenger_adaptation_stats.csv"));
		try (BufferedWriter writer = writer(path, iteration)) {
			if (iteration == firstIteration) {
				writer.write("iteration;reference_iteration;reference_available;compared_trips;mode_changed_trips"
						+ ";departure_changed_trips;mean_abs_departure_shift_min;p95_abs_departure_shift_min\n");
			}
			String mean = absoluteDepartureShiftMinutes.isEmpty() ? "" : Double.toString(
					absoluteDepartureShiftMinutes.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN));
			String p95 = absoluteDepartureShiftMinutes.isEmpty() ? "" : Double.toString(quantile(
					absoluteDepartureShiftMinutes.stream().sorted().toList(), 0.95));
			writer.write(iteration + ";" + referenceIteration + ";" + !referenceModesByTrip.isEmpty()
					+ ";" + compared + ";" + modeChanged + ";" + departureChanged + ";" + mean + ";" + p95 + "\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void writeModeTransitions(int iteration, Map<String, String> currentModes) {
		Map<String, Integer> transitions = new LinkedHashMap<>();
		for (String from : MODES) {
			for (String to : MODES) {
				transitions.put(from + "|" + to, 0);
			}
		}
		for (Map.Entry<String, String> entry : referenceModesByTrip.entrySet()) {
			String current = currentModes.get(entry.getKey());
			String key = entry.getValue() + "|" + current;
			transitions.computeIfPresent(key, (ignored, count) -> count + 1);
		}
		Path path = Path.of(controllerIO.getOutputFilename("passenger_mode_transitions.csv"));
		try (BufferedWriter writer = writer(path, iteration)) {
			if (iteration == firstIteration) {
				writer.write("iteration;reference_iteration;from_mode;to_mode;selected_trip_count\n");
			}
			if (!referenceModesByTrip.isEmpty()) {
				for (String from : MODES) {
					for (String to : MODES) {
						writer.write(iteration + ";" + referenceIteration + ";" + from + ";" + to
								+ ";" + transitions.get(from + "|" + to) + "\n");
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void writeCompletedModeStats(int iteration, int passengerPersons) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String mode : MODES) {
			counts.put(mode, 0);
		}
		int completedPassengerPersons = 0;
		for (Map.Entry<org.matsim.api.core.v01.Id<Person>, Plan> entry
				: experiencedPlansService.getExperiencedPlans().entrySet()) {
			Person original = population.getPersons().get(entry.getKey());
			if (original == null || !isPassenger(original)) {
				continue;
			}
			int personCompletedTrips = 0;
			for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(entry.getValue())) {
				String mode = mainModeIdentifier.identifyMainMode(trip.getTripElements());
				if (counts.containsKey(mode)) {
					counts.compute(mode, (key, value) -> value + 1);
					personCompletedTrips++;
				}
			}
			if (personCompletedTrips > 0) {
				completedPassengerPersons++;
			}
		}
		int completedTrips = counts.values().stream().mapToInt(Integer::intValue).sum();
		Path path = Path.of(controllerIO.getOutputFilename("passenger_completed_modestats.csv"));
		try (BufferedWriter writer = writer(path, iteration)) {
			if (iteration == firstIteration) {
				writer.write("iteration;passenger_persons;completed_passenger_persons;completed_passenger_trips");
				for (String mode : MODES) {
					writer.write(";" + mode);
				}
				writer.write("\n");
			}
			writer.write(iteration + ";" + passengerPersons + ";" + completedPassengerPersons + ";" + completedTrips);
			for (String mode : MODES) {
				double share = completedTrips == 0 ? 0.0 : (double) counts.get(mode) / completedTrips;
				writer.write(";" + share);
			}
			writer.write("\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private boolean isPassenger(Person person) {
		String subpopulation = PopulationUtils.getSubpopulation(person);
		String id = person.getId().toString();
		return !"freight".equals(subpopulation)
				&& !id.startsWith("hd_cargo_")
				&& !id.startsWith("gw_cargo_");
	}

	private void writeModeStats(int iteration, Map<String, Integer> counts, int totalTrips) {
		Path path = Path.of(controllerIO.getOutputFilename("passenger_modestats.csv"));
		try (BufferedWriter writer = writer(path, iteration)) {
			if (iteration == firstIteration) {
				writer.write("iteration;passenger_trips");
				for (String mode : MODES) {
					writer.write(";" + mode);
				}
				writer.write("\n");
			}
			writer.write(Integer.toString(iteration));
			writer.write(";" + totalTrips);
			for (String mode : MODES) {
				double share = totalTrips == 0 ? 0.0 : (double) counts.get(mode) / totalTrips;
				writer.write(";" + share);
			}
			writer.write("\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void writeScoreStats(int iteration, int passengerPersons, int scoredPersons, double averageScore) {
		Path path = Path.of(controllerIO.getOutputFilename("passenger_scorestats.csv"));
		try (BufferedWriter writer = writer(path, iteration)) {
			if (iteration == firstIteration) {
				writer.write("iteration;passenger_persons;scored_persons;avg_selected_score\n");
			}
			writer.write(iteration + ";" + passengerPersons + ";" + scoredPersons + ";"
					+ (Double.isNaN(averageScore) ? "" : Double.toString(averageScore)) + "\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private BufferedWriter writer(Path path, int iteration) throws IOException {
		Files.createDirectories(path.getParent());
		if (iteration == firstIteration) {
			return Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		}
		return Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}
}

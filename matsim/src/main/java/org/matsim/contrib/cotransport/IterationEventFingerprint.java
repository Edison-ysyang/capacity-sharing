package org.matsim.contrib.cotransport;

import com.google.inject.Inject;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.events.handler.BasicEventHandler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Writes deterministic QSim-event fingerprints for the 30-iteration
 * pre-treatment window and the activation checkpoint.
 *
 * <p>Events sharing a timestamp are sorted before hashing, so harmless handler ordering
 * does not change the digest. Coordinates are omitted because they are redundant with
 * link/facility ids and can contain platform-specific floating-point formatting. The
 * fingerprint is a balance/whitelist audit artifact; it is not used as an estimand.</p>
 */
public final class IterationEventFingerprint implements BasicEventHandler, IterationEndsListener {
	private final OutputDirectoryHierarchy controllerIO;
	private final int firstFingerprintIteration;
	private final int lastFingerprintIteration;
	private MessageDigest digest = newDigest();
	private final List<String> sameTimeEvents = new ArrayList<>();
	private double currentTime = Double.NaN;
	private long eventCount;
	private boolean acceptingEvents;

	@Inject
	public IterationEventFingerprint(OutputDirectoryHierarchy controllerIO, CoTransportSettings settings) {
		this.controllerIO = controllerIO;
		this.firstFingerprintIteration = settings.activationIteration() - 30;
		this.lastFingerprintIteration = settings.activationIteration();
	}

	@Override
	public void handleEvent(Event event) {
		if (!acceptingEvents) {
			return;
		}
		if (!Double.isNaN(currentTime) && event.getTime() < currentTime) {
			throw new IllegalStateException("Event time moved backwards while building R17 fingerprint: "
					+ event.getTime() + " < " + currentTime);
		}
		if (Double.isNaN(currentTime) || event.getTime() != currentTime) {
			flushSameTimeEvents();
			currentTime = event.getTime();
		}
		sameTimeEvents.add(canonical(event));
		eventCount++;
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		if (!acceptingEvents) {
			return;
		}
		flushSameTimeEvents();
		Path path = Path.of(controllerIO.getOutputFilename("r17_event_fingerprint.csv"));
		try {
			Files.createDirectories(path.getParent());
			boolean header = !Files.exists(path) || Files.size(path) == 0L;
			try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND)) {
				if (header) {
					writer.write("iteration,event_count,event_sha256,canonicalization_version\n");
				}
				writer.write(event.getIteration() + "," + eventCount + "," + hex(digest.digest())
						+ ",r17-time-bucket-v1\n");
			}
		} catch (IOException error) {
			throw new UncheckedIOException(error);
		}
		acceptingEvents = false;
	}

	@Override
	public void reset(int iteration) {
		digest = newDigest();
		sameTimeEvents.clear();
		currentTime = Double.NaN;
		eventCount = 0L;
		acceptingEvents = iteration >= firstFingerprintIteration && iteration <= lastFingerprintIteration;
	}

	private void flushSameTimeEvents() {
		if (sameTimeEvents.isEmpty()) {
			return;
		}
		digest.update(("time=" + Double.toHexString(currentTime) + "\n").getBytes(StandardCharsets.UTF_8));
		sameTimeEvents.sort(Comparator.naturalOrder());
		for (String event : sameTimeEvents) {
			digest.update(event.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '\n');
		}
		sameTimeEvents.clear();
	}

	private static String canonical(Event event) {
		List<String> attributes = new ArrayList<>();
		for (Map.Entry<String, String> entry : event.getAttributes().entrySet()) {
			if (Event.ATTRIBUTE_TIME.equals(entry.getKey())
					|| Event.ATTRIBUTE_X.equals(entry.getKey()) || Event.ATTRIBUTE_Y.equals(entry.getKey())) {
				continue;
			}
			attributes.add(entry.getKey() + "=" + entry.getValue());
		}
		attributes.sort(Comparator.naturalOrder());
		return String.join("|", attributes);
	}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256 is unavailable", error);
		}
	}

	private static String hex(byte[] bytes) {
		StringBuilder value = new StringBuilder(bytes.length * 2);
		for (byte item : bytes) {
			value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
		}
		return value.toString();
	}
}

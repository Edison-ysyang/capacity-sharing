package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.GenericEvent;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ShutdownEvent;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Manually supplied events and lifecycle callbacks only; never creates or runs a simulation. */
class ScientificEventArchiveTest {
    @TempDir Path temporary;

    @Test void earlyIterationsHaveCountsAndCoverageButNoRawEventFile() throws Exception {
        var archive = new ScientificEventArchive(temporary, 1, 2);
        start(archive, 0, 2);
        archive.handleEvent(new LinkEnterEvent(10, Id.createVehicleId("car"), Id.createLinkId("road")));
        archive.handleEvent(new GenericEvent("actstart", 11));
        end(archive, 0, 2);
        assertFalse(Files.exists(events(0)));
        String receipt = Files.readString(receipt(0));
        assertTrue(receipt.contains("\"profile\": \"counts-only\""));
        assertTrue(receipt.contains("\"observed_events\": 2"));
        assertTrue(receipt.contains("\"written_events\": 0"));
        assertTrue(receipt.contains("\"event_file\": null"));
        assertTrue(Files.readString(temporary.resolve("scientific_event_archive_counts.csv")).contains("0,counts-only"));
    }

    @Test void reportRetainsAllNonLinkTraversalAttributesWithNativeXmlEscaping() throws Exception {
        var archive = new ScientificEventArchive(temporary, 1, 2);
        start(archive, 0, 2); end(archive, 0, 2);
        start(archive, 1, 2);
        var activity = new GenericEvent("actstart", 100);
        activity.getAttributes().put("person", "cargo<&\"unit");
        activity.getAttributes().put("actType", "freight_delivered");
        archive.handleEvent(activity);
        archive.handleEvent(new GenericEvent("PersonEntersVehicle", 101));
        archive.handleEvent(new GenericEvent("personScore", 102));
        archive.handleEvent(new LinkEnterEvent(103, Id.createVehicleId("train"), Id.createLinkId("rail")));
        archive.handleEvent(new LinkLeaveEvent(104, Id.createVehicleId("train"), Id.createLinkId("rail")));
        end(archive, 1, 2);
        String xml = xml(1);
        assertTrue(xml.contains("freight_delivered"));
        assertTrue(xml.contains("personScore"));
        assertFalse(xml.contains("entered link"));
        assertFalse(xml.contains("left link"));
        assertEquals(3, javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getElementsByTagName("event").getLength());
        assertTrue(Files.readString(receipt(1)).contains("\"observed_events\": 5"));
        assertTrue(Files.readString(receipt(1)).contains("\"written_events\": 3"));
        assertTrue(Files.readString(receipt(1)).contains("\"full_native_event_stream\": false"));
        assertEquals("cargo<&\"unit", activity.getAttributes().get("person"));
    }

    @Test void finalIterationKeepsEveryEventAndSuccessfulShutdownRequiresAllIterations() throws Exception {
        var archive = new ScientificEventArchive(temporary, 0, 0);
        start(archive, 0, 0);
        archive.handleEvent(new LinkEnterEvent(1, Id.createVehicleId("train"), Id.createLinkId("rail")));
        archive.handleEvent(new LinkLeaveEvent(2, Id.createVehicleId("train"), Id.createLinkId("rail")));
        archive.handleEvent(new GenericEvent("actstart", 3));
        end(archive, 0, 0);
        archive.notifyShutdown(new ShutdownEvent(null, false, 0, null));
        assertTrue(xml(0).contains("entered link"));
        assertTrue(xml(0).contains("left link"));
        assertTrue(Files.readString(receipt(0)).contains("\"full_native_event_stream\": true"));
        assertTrue(Files.readString(receipt(0)).contains("\"written_events\": 3"));
        byte[] bytes = Files.readAllBytes(events(0));
        String expectedHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertTrue(Files.readString(receipt(0)).contains(expectedHash));
    }

    @Test void nativeResetAfterIterationStartDoesNotDiscardEventsOrCounts() throws Exception {
        var archive = new ScientificEventArchive(temporary, 0, 0);
        start(archive, 0, 0);
        archive.handleEvent(new GenericEvent("beforeReset", 1));
        archive.reset(0);
        archive.handleEvent(new GenericEvent("afterReset", 2));
        end(archive, 0, 0);
        assertTrue(xml(0).contains("beforeReset"));
        assertTrue(xml(0).contains("afterReset"));
        assertTrue(Files.readString(receipt(0)).contains("\"written_events\": 2"));
    }

    @Test void neverOverwritesAnExistingArchive() throws Exception {
        Files.writeString(temporary.resolve("scientific_event_archive_counts.csv"), "existing evidence");
        var archive = new ScientificEventArchive(temporary, 0, 0);
        assertThrows(UncheckedIOException.class, () -> start(archive, 0, 0));
        assertEquals("existing evidence", Files.readString(temporary.resolve("scientific_event_archive_counts.csv")));
        assertFalse(Files.exists(events(0)));
    }

    @Test void abortedIterationGetsOnlyAnExplicitIncompleteMarker() throws Exception {
        var archive = new ScientificEventArchive(temporary, 0, 1);
        start(archive, 0, 1); archive.handleEvent(new GenericEvent("actstart", 1));
        archive.notifyShutdown(new ShutdownEvent(null, true, 0, new IllegalStateException("test abort")));
        assertFalse(Files.exists(receipt(0)));
        assertTrue(Files.exists(temporary.resolve("ITERS/it.0/0.scientific_event_archive_incomplete.txt")));
        assertTrue(xml(0).endsWith("</events>"));
    }

    @Test void invalidLifecycleAndOutOfIterationEventsFailClosed() {
        var archive = new ScientificEventArchive(temporary, 1, 2);
        assertThrows(IllegalArgumentException.class, () -> new ScientificEventArchive(temporary, 3, 2));
        assertThrows(IllegalStateException.class, () -> archive.handleEvent(new GenericEvent("outside", 0)));
        assertThrows(IllegalStateException.class, () -> start(archive, 1, 2));
        start(archive, 0, 2);
        assertThrows(IllegalStateException.class, () -> start(archive, 0, 2));
        assertThrows(IllegalStateException.class, () -> end(archive, 1, 2));
        end(archive, 0, 2);
        assertThrows(IllegalStateException.class, () -> archive.notifyShutdown(new ShutdownEvent(null, false, 0, null)));
    }

    private Path events(int iteration) { return temporary.resolve("ITERS/it." + iteration + "/" + iteration + ".events.xml.gz"); }
    private Path receipt(int iteration) { return temporary.resolve("ITERS/it." + iteration + "/" + iteration + ".scientific_event_archive.json"); }
    private String xml(int iteration) throws Exception {
        try (var input = new GZIPInputStream(Files.newInputStream(events(iteration)))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static void start(ScientificEventArchive archive, int iteration, int last) {
        archive.notifyIterationStarts(new IterationStartsEvent(null, iteration, iteration == last));
    }
    private static void end(ScientificEventArchive archive, int iteration, int last) {
        archive.notifyIterationEnds(new IterationEndsEvent(null, iteration, iteration == last));
    }
}

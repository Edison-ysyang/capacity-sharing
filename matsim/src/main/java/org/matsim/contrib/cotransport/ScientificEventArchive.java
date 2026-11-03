package org.matsim.contrib.cotransport;

import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.events.handler.BasicEventHandler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/**
 * Observational, resource-bounded event archive. It does not consume random numbers, alter
 * plans, invoke a predictor, or allocate capacity. All event types are counted throughout;
 * reporting iterations keep every native event except LinkEnter/LinkLeave, and the final
 * iteration keeps every event. Native serialization and a per-iteration coverage receipt
 * make filtered files distinguishable from complete event streams.
 */
public final class ScientificEventArchive implements BasicEventHandler, IterationStartsListener,
        IterationEndsListener, ShutdownListener {
    private final Path output;
    private final int reportFirst;
    private final int last;
    private final Map<String, Counts> counts = new TreeMap<>();
    private final StringBuilder eventXml = new StringBuilder(1024);
    private BufferedWriter events;
    private Path eventPath;
    private int iteration = -1;
    private int completedIteration = -1;
    private boolean active;
    private boolean initialized;

    public ScientificEventArchive(Path output, int reportFirst, int last) {
        if (output == null || reportFirst < 0 || last < reportFirst)
            throw new IllegalArgumentException("Archive requires output and 0 <= reportFirst <= last");
        this.output = output.toAbsolutePath().normalize();
        this.reportFirst = reportFirst;
        this.last = last;
    }

    public static void install(Controler controller, Path output, int reportFirst, int last) {
        ScientificEventArchive archive = new ScientificEventArchive(output, reportFirst, last);
        controller.addOverridingModule(new AbstractModule() {
            @Override public void install() {
                addEventHandlerBinding().toInstance(archive);
                addControllerListenerBinding().toInstance(archive);
            }
        });
    }

    /** Close after ordinary event-producing iteration-end listeners and score observers. */
    @Override public double priority() { return -2000.0; }

    /** Native before-Mobsim resets occur after IterationStarts; do not erase its open stream. */
    @Override public void reset(int iteration) { }

    @Override public synchronized void notifyIterationStarts(IterationStartsEvent event) {
        if (active || event.getIteration() != completedIteration + 1 || event.getIteration() > last)
            throw new IllegalStateException("Archive cannot overwrite or skip an iteration: " + event.getIteration());
        iteration = event.getIteration();
        counts.clear();
        eventPath = null;
        try {
            Files.createDirectories(iterationDirectory());
            if (!initialized) {
                Files.writeString(output.resolve("scientific_event_archive_counts.csv"),
                        "iteration,profile,event_type,observed_events,written_events\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                initialized = true;
            }
            if (iteration >= reportFirst) {
                eventPath = iterationDirectory().resolve(iteration + ".events.xml.gz");
                events = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(
                        eventPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)), StandardCharsets.UTF_8));
                events.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<events version=\"1.0\">\n");
            }
            active = true;
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    @Override public synchronized void handleEvent(Event event) {
        if (!active) throw new IllegalStateException("Event outside an open scientific archive iteration: " + event);
        Counts type = counts.computeIfAbsent(event.getEventType(), ignored -> new Counts());
        type.observed++;
        boolean keep = events != null && (iteration == last || !isLinkTraversal(event));
        if (!keep) return;
        try {
            eventXml.setLength(0);
            event.writeAsXML(eventXml);
            events.append(eventXml);
            type.written++;
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    @Override public synchronized void notifyIterationEnds(IterationEndsEvent event) {
        if (!active || event.getIteration() != iteration)
            throw new IllegalStateException("Archive end does not match an open iteration");
        try {
            closeEvents();
            long observed = counts.values().stream().mapToLong(c -> c.observed).sum();
            long written = counts.values().stream().mapToLong(c -> c.written).sum();
            if (iteration == last && observed != written)
                throw new IllegalStateException("Final native event archive lost events");
            StringBuilder receipt = new StringBuilder("{\n  \"schema\": \"scientific-event-archive-v1\",\n");
            receipt.append("  \"iteration\": ").append(iteration).append(",\n")
                    .append("  \"profile\": ").append(json(profile())).append(",\n")
                    .append("  \"completed\": true,\n  \"full_native_event_stream\": ")
                    .append(iteration == last).append(",\n  \"observed_events\": ").append(observed)
                    .append(",\n  \"written_events\": ").append(written).append(",\n  \"event_file\": ")
                    .append(eventPath == null ? "null" : json(output.relativize(eventPath).toString()))
                    .append(",\n  \"event_file_sha256\": ")
                    .append(eventPath == null ? "null" : json(sha256(eventPath)))
                    .append(",\n  \"event_type_counts\": {\n");
            boolean first = true;
            for (var entry : counts.entrySet()) {
                if (!first) receipt.append(",\n");
                first = false;
                receipt.append("    ").append(json(entry.getKey())).append(": {\"observed\": ")
                        .append(entry.getValue().observed).append(", \"written\": ")
                        .append(entry.getValue().written).append('}');
            }
            receipt.append("\n  }\n}\n");
            Files.writeString(iterationDirectory().resolve(iteration + ".scientific_event_archive.json"),
                    receipt, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("scientific_event_archive_counts.csv"),
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
                for (var entry : counts.entrySet()) writer.write(iteration + "," + profile() + "," + csv(entry.getKey())
                        + "," + entry.getValue().observed + "," + entry.getValue().written + "\n");
            }
            completedIteration = iteration;
            active = false;
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    @Override public synchronized void notifyShutdown(ShutdownEvent event) {
        try {
            if (active) {
                // A closed partial gzip is recoverable evidence, never a completed iteration receipt.
                closeEvents();
                Files.writeString(iterationDirectory().resolve(iteration + ".scientific_event_archive_incomplete.txt"),
                        "Unexpected stop before iteration archive acceptance; do not analyze as complete.\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                active = false;
            }
            if (!event.isUnexpected() && completedIteration != last)
                throw new IllegalStateException("Normal shutdown without all registered archive iterations");
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private Path iterationDirectory() { return output.resolve("ITERS").resolve("it." + iteration); }
    private String profile() { return iteration < reportFirst ? "counts-only"
            : iteration == last ? "full-native" : "native-except-link-traversals"; }
    private static boolean isLinkTraversal(Event event) {
        return event instanceof LinkEnterEvent || event instanceof LinkLeaveEvent;
    }
    private void closeEvents() throws IOException {
        if (events != null) {
            events.write("</events>");
            events.close();
            events = null;
        }
    }
    private static String csv(String value) { return '"' + value.replace("\"", "\"\"") + '"'; }
    private static String json(String value) {
        StringBuilder encoded = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') encoded.append('\\').append(c);
            else if (c < 32) encoded.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else encoded.append(c);
        }
        return encoded.append('"').toString();
    }
    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static final class Counts { long observed; long written; }
}

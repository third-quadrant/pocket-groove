package io.pocketgroove;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Standard MIDI File (format 1) export for the selected bar range. */
final class MidiExporter {
    private static final int DIVISION = 480;
    private static final int CHANNEL = 9;

    private MidiExporter() {}

    static void export(Path destination, Beat.Project project, int startBar, int endBar) throws IOException {
        if (startBar < 1 || endBar < startBar || endBar > project.bars())
            throw new IllegalArgumentException("내보낼 마디 범위가 올바르지 않습니다.");
        double startBeat = (startBar - 1) * 4.0, endBeat = endBar * 4.0;
        int endTick = (int) Math.round((endBeat - startBeat) * DIVISION);

        var parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        var file = new ByteArrayOutputStream();
        writeChunk(file, "MThd", new byte[]{
                0, 1, (byte) (0 >> 8), (byte) (1 + project.tracks().size()),
                (byte) (DIVISION >> 8), (byte) DIVISION});

        var conductor = new EventWriter();
        conductor.meta(0, 0x03, "Pocket Groove".getBytes(StandardCharsets.UTF_8));
        int microseconds = (int) Math.round(60_000_000.0 / project.bpm());
        conductor.meta(0, 0x51, new byte[]{
                (byte) (microseconds >> 16), (byte) (microseconds >> 8), (byte) microseconds});
        conductor.meta(0, 0x58, new byte[]{4, 2, 24, 8});
        conductor.finish(endTick);
        writeChunk(file, "MTrk", conductor.bytes());

        for (int trackIndex = 0; trackIndex < project.tracks().size(); trackIndex++) {
            var writer = new EventWriter();
            writer.meta(0, 0x03, project.tracks().get(trackIndex).name()
                    .getBytes(StandardCharsets.UTF_8));
            var events = new ArrayList<NoteEvent>();
            int firstCycle = (int) Math.floor(startBeat / project.length());
            int lastCycle = (int) Math.ceil(endBeat / project.length());
            for (int cycle = firstCycle; cycle <= lastCycle; cycle++) {
                double cycleStart = cycle * project.length();
                for (var hit : project.hits()) {
                    if (hit.track() != trackIndex) continue;
                    double beat = cycleStart + hit.beat();
                    if (beat < startBeat - 1e-9 || beat >= endBeat - 1e-9) continue;
                    int tick = (int) Math.round((beat - startBeat) * DIVISION);
                    int pitch = (int) Math.round(project.pitch()
                            + project.tracks().get(trackIndex).pitch() + hit.pitch());
                    int note = clamp(baseNote(project.tracks().get(trackIndex).name(), trackIndex) + pitch);
                    int velocity = Math.max(1, Math.min(127, (int) Math.round(hit.velocity() * 127)));
                    events.add(new NoteEvent(tick, true, note, velocity));
                    events.add(new NoteEvent(Math.min(endTick, tick + 30), false, note, 0));
                }
            }
            events.sort(Comparator.comparingInt(NoteEvent::tick)
                    .thenComparing(event -> event.on ? 1 : 0)
                    .thenComparingInt(NoteEvent::note));
            for (var event : events) writer.note(event.tick, event.on, event.note, event.velocity);
            writer.finish(endTick);
            writeChunk(file, "MTrk", writer.bytes());
        }
        Files.write(destination, file.toByteArray());
    }

    static int baseNote(String name, int trackIndex) {
        String value = name.toLowerCase(Locale.ROOT);
        if (value.contains("kick")) return 36;
        if (value.contains("snare")) return 38;
        if (value.contains("clap")) return 39;
        if (value.contains("open") && value.contains("hat")) return 46;
        if (value.contains("hat")) return 42;
        if (value.contains("tom")) return 45;
        if (value.contains("ride")) return 51;
        if (value.contains("crash")) return 57;
        if (value.contains("perc")) return 49;
        return 36 + trackIndex % 24;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(127, value));
    }

    private static void writeChunk(ByteArrayOutputStream out, String id, byte[] body) throws IOException {
        out.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[]{
                (byte) (body.length >> 24), (byte) (body.length >> 16),
                (byte) (body.length >> 8), (byte) body.length});
        out.writeBytes(body);
    }

    private record NoteEvent(int tick, boolean on, int note, int velocity) {}

    private static final class EventWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int tick;

        void meta(int at, int type, byte[] data) {
            delta(at);
            out.write(0xFF);
            out.write(type);
            varLength(data.length);
            out.writeBytes(data);
        }

        void note(int at, boolean on, int note, int velocity) {
            delta(at);
            out.write((on ? 0x90 : 0x80) | CHANNEL);
            out.write(note);
            out.write(velocity);
        }

        void finish(int endTick) {
            meta(endTick, 0x2F, new byte[0]);
        }

        byte[] bytes() { return out.toByteArray(); }

        private void delta(int at) {
            varLength(Math.max(0, at - tick));
            tick = Math.max(tick, at);
        }

        private void varLength(int value) {
            int buffer = value & 0x7F;
            while ((value >>= 7) > 0) buffer = ((buffer << 8) | ((value & 0x7F) | 0x80));
            while (true) {
                out.write(buffer & 0xFF);
                if ((buffer & 0x80) != 0) buffer >>= 8;
                else break;
            }
        }
    }
}

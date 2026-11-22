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
    private static final int DRUM_CHANNEL = 9;

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

        int melodyChannel = 0;
        for (int trackIndex = 0; trackIndex < project.tracks().size(); trackIndex++) {
            var track = project.tracks().get(trackIndex);
            int channel;
            if (track.instrument() == Beat.Instrument.MELODY) {
                channel = melodyChannel;
                melodyChannel = nextMelodicChannel(melodyChannel);
            } else {
                channel = DRUM_CHANNEL;
            }
            var writer = new EventWriter();
            writer.meta(0, 0x03, track.name().getBytes(StandardCharsets.UTF_8));
            var events = new ArrayList<NoteEvent>();
            for (var hit : SongTimeline.expand(project.tracks(), project.hits(),
                    project.length(), project.bars())) {
                if (hit.track() != trackIndex) continue;
                if (hit.beat() < startBeat - 1e-9 || hit.beat() >= endBeat - 1e-9) continue;
                int tick = (int) Math.round((hit.beat() - startBeat) * DIVISION);
                int pitch = (int) Math.round(project.pitch() + track.pitch() + hit.pitch());
                int note = clamp(track.baseMidiNote() + pitch);
                int velocity = Math.max(1, Math.min(127, (int) Math.round(hit.velocity() * 127)));
                int lengthTicks = Math.max(1, (int) Math.round(hit.length() * DIVISION));
                int offTick = Math.min(endTick, tick + lengthTicks);
                events.add(new NoteEvent(tick, true, note, velocity, channel));
                events.add(new NoteEvent(offTick, false, note, 0, channel));
            }
            events.sort(Comparator.comparingInt(NoteEvent::tick)
                    .thenComparing(event -> event.on ? 1 : 0)
                    .thenComparingInt(NoteEvent::note));
            for (var event : events)
                writer.note(event.tick, event.on, event.note, event.velocity, event.channel);
            writer.finish(endTick);
            writeChunk(file, "MTrk", writer.bytes());
        }
        Files.write(destination, file.toByteArray());
    }

    /** Legacy name/index fallback used only when reading v1-v6 projects. */
    static int baseNote(String name, int trackIndex) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
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

    private static int nextMelodicChannel(int channel) {
        int next = channel + 1;
        if (next == DRUM_CHANNEL) next++;
        return next > 15 ? 0 : next;
    }

    private static void writeChunk(ByteArrayOutputStream out, String id, byte[] body) throws IOException {
        out.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[]{
                (byte) (body.length >> 24), (byte) (body.length >> 16),
                (byte) (body.length >> 8), (byte) body.length});
        out.writeBytes(body);
    }

    private record NoteEvent(int tick, boolean on, int note, int velocity, int channel) {}

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

        void note(int at, boolean on, int note, int velocity, int channel) {
            delta(at);
            out.write((on ? 0x90 : 0x80) | channel);
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

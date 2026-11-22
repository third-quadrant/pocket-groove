package io.pocketgroove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MidiExporterTest {
    @TempDir Path dir;

    private static Beat.Project project() {
        var track = new Beat.Track("Kick", "1", "wav", new byte[]{1}, 0);
        return new Beat.Project(List.of(track), List.of(
                new Beat.Hit(0, 0, .8, 2),
                new Beat.Hit(0, 1.0, .5, 0)), 4, 100, 0, 2);
    }

    @Test void writesAReadableFormatOneFileWithTempoAndNotePitch() throws Exception {
        Path midi = dir.resolve("song.mid");
        MidiExporter.export(midi, project(), 1, 2);
        Sequence sequence = MidiSystem.getSequence(midi.toFile());
        assertEquals(480, sequence.getResolution());
        assertEquals(2, sequence.getTracks().length);
        assertEquals(3840, sequence.getTickLength());

        long tempo = 0;
        int noteOns = 0, noteOffs = 0, pitchedKick = 0;
        for (var track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x51) {
                    byte[] data = meta.getData();
                    tempo = ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
                }
                if (event.getMessage() instanceof ShortMessage message && message.getChannel() == 9) {
                    if (message.getCommand() == ShortMessage.NOTE_ON) {
                        noteOns++;
                        if (message.getData1() == 38) pitchedKick++;
                    }
                    if (message.getCommand() == ShortMessage.NOTE_OFF) noteOffs++;
                }
            }
        }
        assertEquals(600000, tempo, "100 BPM should be 600000 microseconds per quarter");
        assertEquals(4, noteOns, "two hits repeat across two bars");
        assertEquals(4, noteOffs);
        assertEquals(2, pitchedKick, "step pitch +2 should raise the kick note from 36 to 38");
    }

    @Test void rangeSelectsOnlyTheRequestedBar() throws Exception {
        Path midi = dir.resolve("bar2.mid");
        MidiExporter.export(midi, project(), 2, 2);
        Sequence sequence = MidiSystem.getSequence(midi.toFile());
        int noteOns = 0;
        for (var track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof ShortMessage message
                        && message.getCommand() == ShortMessage.NOTE_ON) noteOns++;
            }
        }
        assertEquals(2, noteOns);
        assertEquals(1920, sequence.getTickLength());
    }

    @Test void melodyChordsUseTheBaseNoteAndTheEditedNoteLength() throws Exception {
        var track = new Beat.Track("Cello", "1", "wav", new byte[]{1}, 0,
                Beat.PitchMode.PITCH_SHIFT, Beat.Instrument.MELODY, 60, true);
        var project = new Beat.Project(List.of(track), List.of(
                new Beat.Hit(0, 0, .8, 0, .5),
                new Beat.Hit(0, 0, .7, 4, .5),
                new Beat.Hit(0, 0, .6, 7, 1.0)), 4, 120, 0, 1);
        Path midi = dir.resolve("chord.mid");
        MidiExporter.export(midi, project, 1, 1);
        Sequence sequence = MidiSystem.getSequence(midi.toFile());
        var ons = new java.util.TreeMap<Integer, Long>();
        var offs = new java.util.TreeMap<Integer, Long>();
        for (var midiTrack : sequence.getTracks()) {
            for (int i = 0; i < midiTrack.size(); i++) {
                MidiEvent event = midiTrack.get(i);
                if (event.getMessage() instanceof ShortMessage message
                        && message.getChannel() == 0) {
                    if (message.getCommand() == ShortMessage.NOTE_ON)
                        ons.put(message.getData1(), event.getTick());
                    if (message.getCommand() == ShortMessage.NOTE_OFF)
                        offs.put(message.getData1(), event.getTick());
                }
            }
        }
        assertEquals(List.of(60, 64, 67), List.copyOf(ons.keySet()));
        assertEquals(240L, offs.get(60) - ons.get(60), "half-beat note = 240 ticks");
        assertEquals(240L, offs.get(64) - ons.get(64));
        assertEquals(480L, offs.get(67) - ons.get(67), "one-beat note = 480 ticks");
    }

    @Test void invalidRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MidiExporter.export(
                dir.resolve("bad.mid"), project(), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> MidiExporter.export(
                dir.resolve("bad.mid"), project(), 2, 3));
    }
}

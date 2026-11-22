package io.pocketgroove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WavExporterTest {
    @TempDir Path dir;

    @Test void exportsSampleExactFloatWavForTheRequestedBars() throws Exception {
        int rate = 44100;
        int frames = rate / 4;
        float[] tone = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            float value = (float) (Math.sin(2 * Math.PI * 440 * i / rate) * .5);
            tone[i * 2] = value;
            tone[i * 2 + 1] = value;
        }
        var track = new Beat.Track("tone", "1", "wav",
                Sounds.wavBytes(new Sounds.Pcm(tone, rate)), 0);
        var project = new Beat.Project(List.of(track),
                List.of(new Beat.Hit(0, 0, .8, 0)), 4, 100, 0, 2);
        Path wav = dir.resolve("two-bars.wav");
        WavExporter.export(wav, project, .75, 1, 2);
        long expectedFrames = Math.round(2 * 4 * 60.0 / 100 * 48000);

        try (var audio = AudioSystem.getAudioInputStream(wav.toFile())) {
            assertEquals(48000, audio.getFormat().getSampleRate());
            assertEquals(2, audio.getFormat().getChannels());
            assertEquals(32, audio.getFormat().getSampleSizeInBits());
            assertEquals(AudioFormat.Encoding.PCM_FLOAT, audio.getFormat().getEncoding());
            assertEquals(expectedFrames, audio.getFrameLength());
        }
        assertEquals(44 + expectedFrames * 8, Files.size(wav));
    }

    @Test void rangeSelectsOnlyTheRequestedBar() throws Exception {
        var track = Sounds.defaults().get(0);
        var project = new Beat.Project(List.of(track), List.of(), 4, 120, 0, 3);
        Path wav = dir.resolve("bar2.wav");
        WavExporter.export(wav, project, 1, 2, 2);
        try (var audio = AudioSystem.getAudioInputStream(wav.toFile())) {
            assertEquals(48000L * 2, audio.getFrameLength(), "one 120 BPM bar is 2 seconds");
        }
    }

    @Test void invalidRangeIsRejected() {
        var track = new Beat.Track("Kick", "1", "wav", new byte[]{1}, 0);
        var project = new Beat.Project(List.of(track), List.of(), 4, 120, 0, 1);
        assertThrows(IllegalArgumentException.class,
                () -> WavExporter.export(dir.resolve("bad.wav"), project, 1, 0, 1));
    }
}

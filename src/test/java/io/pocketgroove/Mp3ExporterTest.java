package io.pocketgroove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Mp3ExporterTest {
    @TempDir Path dir;

    private static Beat.Track tone() throws Exception {
        int rate = 44100;
        int frames = rate / 4;
        float[] samples = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            float value = (float) (Math.sin(2 * Math.PI * 440 * i / rate) * .5);
            samples[i * 2] = value;
            samples[i * 2 + 1] = value;
        }
        return new Beat.Track("tone", "1", "wav",
                Sounds.wavBytes(new Sounds.Pcm(samples, rate)), 12);
    }

    private static double seconds(Path path) throws Exception {
        var decoded = Sounds.decode(new Beat.Track("check", "1", "mp3", Files.readAllBytes(path), 0));
        assertEquals(48000, decoded.sampleRate(), "MP3 should keep the engine's 48 kHz rate");
        return decoded.stereo().length / 2.0 / decoded.sampleRate();
    }

    @Test void exportsTheRequestedBarsWithExactBpmTiming() throws Exception {
        var project = new Beat.Project(List.of(tone()),
                List.of(new Beat.Hit(0, 0, .8, 7), new Beat.Hit(0, .5, .5, -5)),
                4, 100, 0, 4);
        Path mp3 = dir.resolve("loop.mp3");
        Mp3Exporter.export(mp3, project, .8, 2, 2);
        assertTrue(Files.size(mp3) > 2_000);
        byte[] bytes = Files.readAllBytes(mp3);
        assertEquals('I', bytes[0]);
        assertEquals('D', bytes[1]);
        assertEquals('3', bytes[2]);
        String tags = new String(bytes, 0, Math.min(512, bytes.length),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(tags.contains("TBPM"));
        assertTrue(tags.contains("100"));
        double oneBar = seconds(mp3);
        assertTrue(oneBar > 2.3 && oneBar < 2.6, "one 100 BPM bar should last 2.4s: " + oneBar);

        Path fourBars = dir.resolve("four.mp3");
        Mp3Exporter.export(fourBars, project, .8, 1, 4);
        double duration = seconds(fourBars);
        assertTrue(duration > 9.4 && duration < 9.9, "four 100 BPM bars should last 9.6s: " + duration);
    }

    @Test void rejectsAnEmptyOrOutOfRangeProjectMix() {
        var track = new Beat.Track("tone", "1", "wav", new byte[]{1}, 0);
        var project = new Beat.Project(List.of(track), List.of(), 1, 60, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> Mp3Exporter.export(
                dir.resolve("bad.mp3"), project, 2));
        assertThrows(IllegalArgumentException.class, () -> Mp3Exporter.export(
                dir.resolve("bad-range.mp3"), project, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> Mp3Exporter.export(
                dir.resolve("bad-range.mp3"), project, 1, 2, 1));
    }
}

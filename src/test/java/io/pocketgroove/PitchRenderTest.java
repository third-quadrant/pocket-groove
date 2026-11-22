package io.pocketgroove;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PitchRenderTest {
    private static final int RATE = 48000;

    @BeforeAll static void loadNativeLibrary() {
        String path = "target/native/pocketgroove_audio.dll";
        System.setProperty("groove.native.path", path);
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(path)),
                "native audio library is not built; run scripts/build-native.ps1");
    }

    private static float[] tone(double frequency, double seconds) {
        int frames = (int) Math.round(seconds * RATE);
        float[] stereo = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            float value = (float) (Math.sin(2 * Math.PI * frequency * i / RATE) * .5);
            stereo[i * 2] = value;
            stereo[i * 2 + 1] = value;
        }
        return stereo;
    }

    private static double rms(float[] pcm) {
        double sum = 0;
        for (float value : pcm) sum += value * value;
        return Math.sqrt(sum / pcm.length);
    }

    private static double frequency(float[] pcm) {
        int crossings = 0;
        for (int i = 2; i < pcm.length; i += 2) {
            if (pcm[i - 2] <= 0 && pcm[i] > 0) crossings++;
        }
        return crossings * (double) RATE / (pcm.length / 2.0);
    }

    @Test void c4RendersToC3AndC5WithTheOriginalLength() {
        float[] c4 = tone(261.63, 1.0);
        float[] c3 = PitchRender.render(c4, RATE, -12, false);
        float[] c5 = PitchRender.render(c4, RATE, 12, false);
        assertEquals(c4.length, c3.length);
        assertEquals(c4.length, c5.length);
        assertTrue(rms(c3) > .01 && rms(c5) > .01);
        assertEquals(130.81, frequency(c3), 130.81 * .05);
        assertEquals(523.25, frequency(c5), 523.25 * .05);
    }

    @Test void twelveSemitoneStepsKeepTheirFrequencyRatio() {
        float[] c4 = tone(261.63, .5);
        for (int step = -12; step <= 12; step++) {
            float[] shifted = PitchRender.render(c4, RATE, step, false);
            assertEquals(c4.length, shifted.length, "step " + step + " length");
            assertTrue(rms(shifted) > .01, "step " + step + " energy");
            assertEquals(261.63 * Math.pow(2, step / 12.0), frequency(shifted), 17, "step " + step);
        }
    }

    @Test void rejectsMalformedPcmAndOutOfRangeSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> PitchRender.render(new float[0], RATE, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> PitchRender.render(new float[3], RATE, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> PitchRender.render(new float[]{0, 0}, 4000, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> PitchRender.render(new float[]{0, 0}, RATE, 49, false));
        assertThrows(IllegalArgumentException.class,
                () -> PitchRender.render(new float[]{Float.NaN, 0}, RATE, 0, false));
    }
}

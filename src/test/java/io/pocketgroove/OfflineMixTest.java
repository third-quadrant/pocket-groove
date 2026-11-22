package io.pocketgroove;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfflineMixTest {
    @BeforeAll static void loadNativeLibrary() {
        String path = "target/native/pocketgroove_audio.dll";
        System.setProperty("groove.native.path", path);
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(path)),
                "native audio library is not built; run scripts/build-native.ps1");
    }

    private static Beat.Track cello() throws Exception {
        int rate = 44100;
        int frames = rate / 10;
        float[] stereo = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            float value = (float) (Math.sin(2 * Math.PI * 261.63 * i / rate) * .35);
            stereo[i * 2] = value;
            stereo[i * 2 + 1] = value * .8f;
        }
        var pcm = new Sounds.Pcm(stereo, rate);
        return new Beat.Track("Cello", "1", "wav", Sounds.wavBytes(pcm), 0,
                Beat.PitchMode.PITCH_SHIFT, Beat.Instrument.MELODY, 60, false);
    }

    @Test void wavRenderUsesTheSamePreparedPcmAsRealtimeAndIsDeterministic() throws Exception {
        var track = cello();
        var project = new Beat.Project(List.of(track), List.of(
                new Beat.Hit(0, 0, 1.0, 0, .25),
                new Beat.Hit(0, 0, 1.0, 4, .25),
                new Beat.Hit(0, 0, 1.0, 7, .25)), 4, 240, 0, 1);
        var cache = new PitchCache();
        var prepared = cache.prepare(project.tracks(), project.hits(), project.pitch());
        assertEquals(3, prepared.tracks().get(0).variants().size());
        int renders = cache.renderedCount();

        var first = render(project, cache);
        assertEquals(48000 * 2, first.length, "one 4-beat bar at 240 BPM is exactly one second");
        assertEquals(renders, cache.renderedCount(), "the WAV path must reuse cached variants");

        var second = render(project, cache);
        assertArrayEquals(first, second, "resampling and pitch rendering must be deterministic");
        for (float sample : first) assertTrue(Float.isFinite(sample));

        float expectedLeft = 0, expectedRight = 0;
        for (var variant : prepared.tracks().get(0).variants()) {
            expectedLeft += variant.pcm()[0];
            expectedRight += variant.pcm()[1];
        }
        assertEquals(expectedLeft, first[0], 1e-6);
        assertEquals(expectedRight, first[1], 1e-6);
    }

    private static float[] render(Beat.Project project, PitchCache cache) throws Exception {
        var pcm = Sounds.decodeAll(project.tracks());
        var chunks = new ArrayList<float[]>();
        OfflineMix.render(project, pcm, 1.0, 1, 1, cache, (samples, frames) ->
                chunks.add(java.util.Arrays.copyOf(samples, frames * 2)));
        int total = chunks.stream().mapToInt(chunk -> chunk.length).sum();
        float[] result = new float[total];
        int at = 0;
        for (var chunk : chunks) {
            System.arraycopy(chunk, 0, result, at, chunk.length);
            at += chunk.length;
        }
        return result;
    }
}

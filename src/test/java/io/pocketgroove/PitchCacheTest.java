package io.pocketgroove;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PitchCacheTest {
    @BeforeAll static void loadNativeLibrary() {
        String path = "target/native/pocketgroove_audio.dll";
        System.setProperty("groove.native.path", path);
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(path)),
                "native audio library is not built; run scripts/build-native.ps1");
    }

    private static Beat.Track toneTrack(int rate, double seconds, String name) throws Exception {
        int frames = (int) Math.round(rate * seconds);
        float[] stereo = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            float value = (float) (Math.sin(2 * Math.PI * 440 * i / rate) * .4);
            stereo[i * 2] = value;
            stereo[i * 2 + 1] = value;
        }
        var pcm = new Sounds.Pcm(stereo, rate);
        return new Beat.Track(name, "1", "wav", Sounds.wavBytes(pcm), 0,
                Beat.PitchMode.PITCH_SHIFT, Beat.Instrument.MELODY, 60, false);
    }

    @Test void resamplingKeepsDurationAndStereoShape() throws Exception {
        var track = toneTrack(44100, .2, "cello");
        var pcm = Sounds.decode(track);
        var resampled = Sounds.resample(pcm, PitchCache.RATE);
        assertEquals(PitchCache.RATE, resampled.sampleRate());
        assertEquals(Math.round(.2 * PitchCache.RATE), resampled.stereo().length / 2);
        for (int i = 0; i < resampled.stereo().length; i += 2) {
            assertEquals(resampled.stereo()[i], resampled.stereo()[i + 1], 0.00001);
            assertTrue(Float.isFinite(resampled.stereo()[i]));
        }
    }

    @Test void preparesAndReusesEveryNeededPitch() throws Exception {
        var track = toneTrack(44100, .2, "cello");
        var hits = List.of(
                new Beat.Hit(0, 0, .8, -12, .25),
                new Beat.Hit(0, 0, .8, 0, .25),
                new Beat.Hit(0, 0, .8, 12, .25));
        var cache = new PitchCache();
        var result = cache.prepare(List.of(track), hits, 0);
        assertEquals(0, result.missing());
        assertEquals(1, result.tracks().size());
        var variants = result.tracks().get(0).variants();
        assertEquals(List.of(-120, 0, 120), variants.stream().map(PitchCache.Variant::tenth).toList());
        int frames = Math.round(.2f * PitchCache.RATE);
        for (var variant : variants) assertEquals(frames * 2, variant.pcm().length);

        int rendered = cache.renderedCount();
        var again = cache.prepare(List.of(track), hits, 0);
        assertEquals(rendered, cache.renderedCount(), "same source/settings must not render twice");
        assertEquals(3, again.tracks().get(0).variants().size());
        assertSame(variants.get(2).pcm(), cache.get(0, track, 120));
    }

    @Test void cacheLimitIsAStrictCeilingAndProtectedVariantsStay() throws Exception {
        var track = toneTrack(44100, .2, "cello");
        var hits = List.of(
                new Beat.Hit(0, 0, .8, -12, .25),
                new Beat.Hit(0, 0, .8, 0, .25),
                new Beat.Hit(0, 0, .8, 12, .25));
        var cache = new PitchCache(100_000);
        var result = cache.prepare(List.of(track), hits, 0);
        assertTrue(result.bytes() <= cache.limitBytes());
        assertTrue(result.capped());
        assertTrue(result.missing() > 0);
        assertEquals(1, result.tracks().get(0).variants().size(),
                "protected variants cannot be evicted to make room");
    }

    @Test void changingTheSoundInvalidatesOnlyThatTrack() throws Exception {
        var first = toneTrack(44100, .1, "cello");
        var hits = List.of(new Beat.Hit(0, 0, .8, 0, .25));
        var cache = new PitchCache();
        cache.prepare(List.of(first), hits, 0);
        int rendered = cache.renderedCount();
        var second = toneTrack(44100, .1, "cello");
        var result = cache.prepare(List.of(second), hits, 0);
        assertEquals(1, result.tracks().get(0).variants().size());
        assertTrue(cache.renderedCount() > rendered, "a new track identity re-renders");
    }

    @Test void varispeedTracksDoNotAllocatePitchVariants() throws Exception {
        var track = new Beat.Track("Kick", "1", "wav", new byte[]{1}, 0);
        var cache = new PitchCache();
        var result = cache.prepare(List.of(track), List.of(new Beat.Hit(0, 0, 1, 0, .25)), 0);
        assertTrue(result.tracks().get(0).variants().isEmpty());
        assertEquals(0, cache.bytes());
    }
}

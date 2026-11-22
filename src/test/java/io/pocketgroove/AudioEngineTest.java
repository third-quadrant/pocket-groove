package io.pocketgroove;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "groove.audioTest", matches = "true")
class AudioEngineTest {
    @BeforeAll static void loadNativeLibrary() {
        System.setProperty("groove.native.path", "target/native/pocketgroove_audio.dll");
    }

    @Test void nativeBoundaryHandlesDynamicTracksVelocityLoopUpdatesAndRollback() throws Exception {
        assertFalse(AudioEngine.devices().isEmpty(), "WASAPI output devices should be discoverable");
        var tracks = Sounds.defaults();
        var pcm = Sounds.decodeAll(tracks);
        assertEquals(7, pcm.size());
        var pitches = tracks.stream().map(Beat.Track::pitch).toList();
        var modes = tracks.stream().map(track -> false).toList();
        try (var engine = AudioEngine.open(-1, false, 256)) {
            engine.install(pcm, pitches, modes, List.of(new Beat.Hit(0, 0, 0.8)), 4, 120);
            engine.parameters(.75, 0);
            engine.playLoop();
            assertTrue(engine.description().contains("공유"));
            assertTrue(engine.state().outputMs() >= 0);
            assertEquals(1, engine.snapshot().hits().size());
            assertEquals(0.8, engine.snapshot().hits().get(0).velocity(), 0.00001);

            engine.setLoop(List.of(new Beat.Hit(0, 0.25, 1.0, 5),
                    new Beat.Hit(6, 0.5, 1.0, -7)), 4, 96);
            assertEquals(2, engine.snapshot().hits().size());
            assertEquals(5, engine.snapshot().hits().get(0).pitch(), 0.00001);
            assertEquals(-7, engine.snapshot().hits().get(1).pitch(), 0.00001);

            assertThrows(IllegalArgumentException.class, () -> engine.hit(32, 1, 0));
            assertThrows(IllegalArgumentException.class, () -> engine.hit(0, 1, 25));
            assertThrows(IllegalArgumentException.class, () -> engine.tempo(Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> engine.parameters(1, 40));
            assertThrows(IllegalArgumentException.class, () -> engine.trackPitch(32, 0));
            assertThrows(IllegalArgumentException.class, () -> engine.trackPitch(0, 40));
            assertThrows(IllegalArgumentException.class, () -> engine.trackMode(32, true));
            assertThrows(IllegalArgumentException.class, () -> engine.setLoop(
                    List.of(new Beat.Hit(7, 0, 1)), 4, 120));

            engine.trackPitch(6, -5);
            engine.trackMode(6, true);
            engine.record(6, 4, 120);
            engine.hit(0, 1, 0);
            engine.hit(6, .6, 9);
            engine.finishRecording();
            var recorded = engine.snapshot();
            assertEquals(2, recorded.hits().size());
            assertTrue(recorded.hits().stream().anyMatch(hit -> hit.track() == 0 && hit.beat() == 0.25),
                    "an unrecorded track keeps its old hits");
            assertTrue(recorded.hits().stream().anyMatch(hit -> hit.track() == 6
                    && Math.abs(hit.velocity() - .6) < 0.00001
                    && Math.abs(hit.pitch() - 9) < 0.00001));

            engine.stopAll();
            assertFalse(engine.state().playing());
            engine.reconfigure(-1, false, 128);
            assertTrue(engine.description().contains("128 samples"));

            var manyPcm = new java.util.ArrayList<Sounds.Pcm>();
            for (int i = 0; i < Beat.MAX_TRACKS; i++) manyPcm.add(pcm.get(i % pcm.size()));
            var manyPitches = java.util.Collections.nCopies(Beat.MAX_TRACKS, 0.0);
            var manyModes = java.util.Collections.nCopies(Beat.MAX_TRACKS, false);
            engine.install(manyPcm, manyPitches, manyModes,
                    List.of(new Beat.Hit(31, 0, 1.0)), 4, 120);
            assertEquals(31, engine.snapshot().hits().get(0).track(),
                    "the native mixer accepts all 32 tracks");
        }
    }

    @Test void closedHandleIsRejectedRatherThanDereferenced() throws Exception {
        var engine = AudioEngine.open(-1, false, 256);
        engine.close();
        assertThrows(IllegalStateException.class, () -> engine.hit(0, 1, 0));
        engine.close();
    }
}

package io.pocketgroove;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BeatTest {
    @Test void loopBoundaryFiresOnceAndPreservesSimultaneousHits() {
        var hits = List.of(new Beat.Hit(0, 0), new Beat.Hit(1, 0), new Beat.Hit(2, 3.5));
        assertEquals(List.of(0, 1), Beat.crossed(hits, 4, -0.001, 0));
        assertEquals(List.of(2, 0, 1), Beat.crossed(hits, 4, 3, 4));
        assertTrue(Beat.crossed(hits, 4, 4, 4.1).isEmpty());
    }
    @Test void tempoControlsEventSpacingWithoutChangingPitch() {
        assertEquals(2, Beat.elapsedBeats(1_000_000_000L, 120));
        assertEquals(1, Beat.elapsedBeats(1_000_000_000L, 60));
        assertEquals(2, Beat.pitchRate(12), 0.00001);
        assertEquals(0.5, Beat.pitchRate(-12), 0.00001);
    }
    @Test void invalidEventsAndNonFiniteTempoAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Beat.Hit(6, 0));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Hit(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Project(List.of(), List.of(), 4, Double.NaN, 0));
    }
}

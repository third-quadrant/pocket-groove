package io.pocketgroove;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LoopPlayerTest {
    @Test void liveTempoChangePreservesPositionAndStopPreventsFurtherHits() {
        var fired = new ArrayList<Integer>();
        var p = ProjectIOTest.project();
        try (var player = new LoopPlayer(fired::add, false)) {
            player.play(p, 0);
            player.tick(500_000_000L);
            assertEquals(1, player.position(), .00001);
            player.tempo(60, 500_000_000L);
            player.tick(1_000_000_000L);
            assertEquals(List.of(2), fired);
            player.stop();
            player.tick(10_000_000_000L);
            assertEquals(List.of(2), fired);
        }
    }
}

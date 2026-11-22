package io.pocketgroove;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BeatTest {
    @Test void projectsMayHaveOneToThirtyTwoTracks() {
        var track = new Beat.Track("Kick", "1", "wav", new byte[]{1});
        assertDoesNotThrow(() -> new Beat.Project(List.of(track), List.of(), 4, 120, 0));

        var tracks = new java.util.ArrayList<Beat.Track>();
        for (int i = 0; i < Beat.MAX_TRACKS; i++) {
            tracks.add(new Beat.Track("Track " + i, Integer.toHexString(i), "wav", new byte[]{1}));
        }
        assertDoesNotThrow(() -> new Beat.Project(tracks, List.of(), 4, 120, 0));
        tracks.add(new Beat.Track("Too many", "x", "wav", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Project(tracks, List.of(), 4, 120, 0));
    }

    @Test void hitsCarryTrackBeatAndVelocityAndAreSorted() {
        var track = new Beat.Track("Kick", "1", "wav", new byte[]{1});
        var other = new Beat.Track("Snare", "2", "wav", new byte[]{1});
        var project = new Beat.Project(List.of(track, other), List.of(
                new Beat.Hit(1, 2.0, 0.4),
                new Beat.Hit(0, 1.0, 0.9),
                new Beat.Hit(0, 1.0, 0.7)), 4, 120, 0);
        assertEquals(0.9, project.hits().get(0).velocity(), 0.00001);
        assertEquals(0.7, project.hits().get(1).velocity(), 0.00001);
        assertEquals(1, project.hits().get(2).track());
    }

    @Test void invalidTracksHitsAndSettingsAreRejected() {
        var track = new Beat.Track("Kick", "1", "wav", new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> new Beat.Hit(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Hit(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Hit(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Hit(0, 0, 1.1));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Hit(0, 0, 1, 25));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Hit(0, 0, 1, -25));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Project(List.of(track),
                List.of(new Beat.Hit(1, 0)), 4, 120, 0));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Project(List.of(track, track),
                List.of(), 4, 120, 0));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Project(List.of(track),
                List.of(new Beat.Hit(0, 64)), 4, 120, 0));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Project(List.of(track),
                List.of(), 8, 120, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Project(List.of(track),
                List.of(), 4, 120, 0, 513));
    }
}

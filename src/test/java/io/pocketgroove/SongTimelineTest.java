package io.pocketgroove;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SongTimelineTest {
    private static Beat.Track drum(String key) {
        return new Beat.Track("Drum", key, "wav", new byte[]{1}, 0);
    }

    private static Beat.Track melody(String key) {
        return new Beat.Track("Cello", key, "wav", new byte[]{1}, 0,
                Beat.PitchMode.PITCH_SHIFT, Beat.Instrument.MELODY, 60, false);
    }

    @Test void repeatTrackExpandsAcrossTheSongAndIndividualTrackStaysAbsolute() {
        var tracks = List.of(drum("1"), melody("2"));
        var hits = List.of(
                new Beat.Hit(0, 0, 1, 0),
                new Beat.Hit(0, 2, 1, 0),
                new Beat.Hit(1, 5.5, 1, 7));
        var expanded = SongTimeline.expand(tracks, hits, 4, 2);
        assertEquals(List.of(0.0, 2.0, 4.0, 5.5, 6.0),
                expanded.stream().map(Beat.Hit::beat).toList());
        assertEquals(5, expanded.size());
    }

    @Test void repeatTrackUsesThePatternBarAndIndividualTrackUsesTheSongBar() {
        var tracks = List.of(drum("1"), melody("2"));
        var hits = List.of(
                new Beat.Hit(0, 4.25, 1, 0),
                new Beat.Hit(1, 4.25, 1, 3),
                new Beat.Hit(1, 6.0, 1, 5));
        assertEquals(1, SongTimeline.barHits(tracks, hits, 0, 1, 8).size());
        assertEquals(1, SongTimeline.barHits(tracks, hits, 0, 3, 8).size());
        assertEquals(2, SongTimeline.barHits(tracks, hits, 1, 1, 8).size());
        assertEquals(0, SongTimeline.barHits(tracks, hits, 1, 2, 8).size());
    }

    @Test void hitsBeyondTheRepeatPatternArePreservedButNotPlayed() {
        var repeat = drum("1");
        var project = new Beat.Project(List.of(repeat),
                List.of(new Beat.Hit(0, 5, 1, 0)), 4, 120, 0, 4);
        assertEquals(1, project.hits().size());
        assertTrue(SongTimeline.expand(project).isEmpty());
        var individual = melody("2");
        var individualProject = new Beat.Project(List.of(individual),
                List.of(new Beat.Hit(0, 5, 1, 0)), 4, 120, 0, 4);
        assertEquals(1, SongTimeline.expand(individualProject).size());
    }
}

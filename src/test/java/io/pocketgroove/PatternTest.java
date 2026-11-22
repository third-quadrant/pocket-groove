package io.pocketgroove;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatternTest {
    private static Beat.Hit hit(int track, double beat, double velocity) {
        return new Beat.Hit(track, beat, velocity);
    }

    @Test void sixteenStepsPerBeatQuarterAndCellsOwnOneSixteenth() {
        assertEquals(16, Pattern.stepCount(4));
        assertEquals(64, Pattern.stepCount(16));
        assertEquals(0, Pattern.stepBeat(0), 0.00001);
        assertEquals(0.25, Pattern.stepBeat(1), 0.00001);
        assertEquals(3.75, Pattern.stepBeat(15), 0.00001);
    }

    @Test void toggleAddsAndRemovesEveryHitInTheSameCellOnly() {
        var initial = List.of(hit(0, 0.25, 0.4), hit(1, 0.25, 1.0), hit(0, 0.5, 0.8));
        var removed = Pattern.toggle(initial, 0, 1, 4, 0.75);
        assertEquals(List.of(1, 0), removed.stream().map(Beat.Hit::track).toList());
        assertEquals(0.5, removed.get(1).beat(), 0.00001);

        var added = Pattern.toggle(removed, 0, 1, 4, 0.75);
        assertEquals(3, added.size());
        assertTrue(added.stream().anyMatch(h -> h.track() == 0 && h.beat() == 0.25 && h.velocity() == 0.75));
    }

    @Test void cellReportsActiveAndLoudestVelocityAndVelocityEditIsClamped() {
        var initial = List.of(hit(0, 0.25, 0.4), hit(0, 0.25, 0.7));
        assertEquals(new Pattern.Cell(true, 0.7, 0), Pattern.cell(initial, 0, 1));
        assertEquals(new Pattern.Cell(false, 0.0, 0), Pattern.cell(initial, 1, 1));

        var louder = Pattern.setVelocity(initial, 0, 1, 1.0);
        assertTrue(louder.stream().filter(h -> Math.abs(h.beat() - 0.25) < 0.001)
                .allMatch(h -> h.velocity() == 1.0));
        var empty = Pattern.setVelocity(initial, 2, 0, 0.1);
        assertTrue(empty.stream().anyMatch(h -> h.track() == 2 && h.beat() == 0 && h.velocity() == 0.1));
    }

    @Test void eachStepCanCarryItsOwnNotePitchForMelodies() {
        var initial = List.of(hit(0, 0.25, 0.8), hit(0, 0.5, 0.8));
        var tuned = Pattern.setPitch(initial, 0, 1, 7);
        assertEquals(7, Pattern.cell(tuned, 0, 1).pitch(), 0.00001);
        assertEquals(0, Pattern.cell(tuned, 0, 2).pitch(), 0.00001);
        var added = Pattern.setPitch(tuned, 0, 3, -5);
        assertEquals(new Pattern.Cell(true, 0.8, -5), Pattern.cell(added, 0, 3));
        var fine = Pattern.setPitch(added, 0, 3, 3.4);
        assertEquals(3.4, Pattern.cell(fine, 0, 3).pitch(), 0.00001);
        assertThrows(IllegalArgumentException.class, () -> Pattern.setPitch(added, 0, 0, 25));
    }

    @Test void oneStepStoresAndEditsMultipleNotes() {
        var empty = List.<Beat.Hit>of();
        var c = Pattern.addNote(empty, 0, 1, 0, .8, .5);
        var e = Pattern.addNote(c, 0, 1, 4, .7, .5);
        var g = Pattern.addNote(e, 0, 1, 7, .6, .75);
        assertEquals(3, Pattern.chordSize(g, 0, 1));
        assertEquals(List.of(0.0, 4.0, 7.0), Pattern.notes(g, 0, 1).stream()
                .map(Beat.Hit::pitch).toList());

        var updated = Pattern.updateNote(g, 0, 1, 4, 5, .9, 1.0);
        assertEquals(List.of(0.0, 5.0, 7.0), Pattern.notes(updated, 0, 1).stream()
                .map(Beat.Hit::pitch).toList());
        assertEquals(1.0, Pattern.notes(updated, 0, 1).get(1).length(), 0.00001);

        var removed = Pattern.removeNote(updated, 0, 1, 5);
        assertEquals(2, Pattern.chordSize(removed, 0, 1));
        assertTrue(Pattern.notes(removed, 0, 1).stream().noneMatch(h -> h.pitch() == 5));
    }

    @Test void transposingAChordMovesEveryNoteByTheSameAmount() {
        var chord = List.of(
                new Beat.Hit(0, 0.25, .8, 0, .25),
                new Beat.Hit(0, 0.25, .7, 4, .25),
                new Beat.Hit(0, 0.25, .6, 7, .25));
        var moved = Pattern.setPitch(chord, 0, 1, 2);
        assertEquals(List.of(2.0, 6.0, 9.0), Pattern.notes(moved, 0, 1).stream()
                .map(Beat.Hit::pitch).toList());
        assertEquals(3, Pattern.notes(moved, 0, 1).size());
    }

    @Test void outOfRangeStepsTracksAndTimesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Pattern.toggle(List.of(), 0, 16, 4, 0.8));
        assertThrows(IllegalArgumentException.class, () -> Pattern.toggle(List.of(), -1, 0, 4, 0.8));
        assertThrows(IllegalArgumentException.class, () -> Pattern.setVelocity(List.of(), 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Pattern.removeTrack(List.of(), -1));
    }

    @Test void removingATrackDropsItsHitsAndShiftsLaterTracks() {
        var initial = List.of(hit(0, 0, 1), hit(2, 1, 1), hit(1, 0.5, 1), hit(3, 2, 1));
        var removed = Pattern.removeTrack(initial, 1);
        assertEquals(List.of(0, 1, 2), removed.stream().map(Beat.Hit::track).toList());
        assertEquals(List.of(0.0, 1.0, 2.0), removed.stream().map(Beat.Hit::beat).toList());
    }

    @Test void setActivePaintsRepeatedDragsOnlyWhenTheCellChanges() {
        var initial = List.of(hit(0, 0, 1));
        var painted = Pattern.setActive(initial, 0, 1, true, 4, 0.8);
        assertEquals(2, painted.size());
        assertSame(painted, Pattern.setActive(painted, 0, 1, true, 4, 0.8));
        var erased = Pattern.setActive(painted, 0, 0, false, 4, 0.8);
        assertEquals(List.of(new Beat.Hit(0, 0.25, 0.8)), erased);
    }
}

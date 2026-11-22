package io.pocketgroove;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure pattern edits shared by the step grid, timeline, piano editor and tests. */
final class Pattern {
    static final double STEPS_PER_BEAT = 4;
    static final double CELL = 1.0 / STEPS_PER_BEAT;
    private static final double PITCH_EPSILON = 0.001;

    record Cell(boolean active, double velocity, double pitch) {}

    private Pattern() {}

    static int stepCount(double length) {
        if (!Double.isFinite(length) || length <= 0) throw new IllegalArgumentException("잘못된 루프 길이입니다.");
        return (int) Math.round(length * STEPS_PER_BEAT);
    }

    static double stepBeat(int step) {
        return step * CELL;
    }

    static Cell cell(List<Beat.Hit> hits, int track, int step) {
        requireTrack(track);
        requireStep(step);
        double center = stepBeat(step);
        boolean active = false;
        double velocity = 0;
        double pitch = 0;
        for (var hit : hits) {
            if (hit.track() == track && inCell(hit.beat(), center)) {
                active = true;
                if (hit.velocity() >= velocity) {
                    velocity = hit.velocity();
                    pitch = hit.pitch();
                }
            }
        }
        return new Cell(active, velocity, pitch);
    }

    /** Every note in one step, sorted low to high, so chords are visible and editable. */
    static List<Beat.Hit> notes(List<Beat.Hit> hits, int track, int step) {
        requireTrack(track);
        requireStep(step);
        double center = stepBeat(step);
        var result = new ArrayList<Beat.Hit>();
        for (var hit : hits) {
            if (hit.track() == track && inCell(hit.beat(), center)) result.add(hit);
        }
        result.sort(Comparator.comparingDouble(Beat.Hit::pitch)
                .thenComparingDouble(Beat.Hit::velocity));
        return List.copyOf(result);
    }

    static int chordSize(List<Beat.Hit> hits, int track, int step) {
        return notes(hits, track, step).size();
    }

    static List<Beat.Hit> addNote(List<Beat.Hit> hits, int track, int step,
                                  double pitch, double velocity, double length) {
        requireTrack(track);
        requireStep(step);
        validatePitch(pitch);
        validateVelocity(velocity);
        validateLength(length);
        var result = new ArrayList<Beat.Hit>();
        boolean replaced = false;
        for (var hit : hits) {
            if (hit.track() == track && inCell(hit.beat(), stepBeat(step))
                    && close(hit.pitch(), pitch)) {
                result.add(new Beat.Hit(track, hit.beat(), velocity, pitch, length));
                replaced = true;
            } else {
                result.add(hit);
            }
        }
        if (!replaced) result.add(new Beat.Hit(track, stepBeat(step), velocity, pitch, length));
        return sorted(result);
    }

    static List<Beat.Hit> removeNote(List<Beat.Hit> hits, int track, int step, double pitch) {
        requireTrack(track);
        requireStep(step);
        var result = new ArrayList<Beat.Hit>();
        for (var hit : hits) {
            if (hit.track() == track && inCell(hit.beat(), stepBeat(step))
                    && close(hit.pitch(), pitch)) continue;
            result.add(hit);
        }
        return sorted(result);
    }

    static List<Beat.Hit> updateNote(List<Beat.Hit> hits, int track, int step,
                                     double oldPitch, double newPitch,
                                     double velocity, double length) {
        requireTrack(track);
        requireStep(step);
        validatePitch(newPitch);
        validateVelocity(velocity);
        validateLength(length);
        double center = stepBeat(step);
        var result = new ArrayList<Beat.Hit>();
        for (var hit : hits) {
            if (hit.track() == track && inCell(hit.beat(), center)
                    && close(hit.pitch(), oldPitch)) {
                result.add(new Beat.Hit(track, hit.beat(), velocity, newPitch, length));
            } else {
                result.add(hit);
            }
        }
        return sorted(result);
    }

    static List<Beat.Hit> toggle(List<Beat.Hit> hits, int track, int step,
                                double length, double velocity) {
        requireTrack(track);
        requireStep(step, stepCount(length));
        validateVelocity(velocity);
        double center = stepBeat(step);
        var result = new ArrayList<Beat.Hit>();
        boolean removed = false;
        for (var hit : hits) {
            if (hit.track() == track && inCell(hit.beat(), center)) {
                removed = true;
            } else {
                result.add(hit);
            }
        }
        if (!removed) result.add(new Beat.Hit(track, center, velocity));
        return sorted(result);
    }

    static List<Beat.Hit> setActive(List<Beat.Hit> hits, int track, int step, boolean active,
                                    double length, double velocity) {
        Cell current = cell(hits, track, step);
        if (current.active() == active) return hits;
        return toggle(hits, track, step, length, velocity);
    }

    static List<Beat.Hit> setVelocity(List<Beat.Hit> hits, int track, int step, double velocity) {
        requireTrack(track);
        requireStep(step);
        validateVelocity(velocity);
        double center = stepBeat(step);
        var result = new ArrayList<Beat.Hit>();
        boolean found = false;
        for (var hit : hits) {
            if (hit.track() == track && inCell(hit.beat(), center)) {
                result.add(new Beat.Hit(track, hit.beat(), velocity, hit.pitch(), hit.length()));
                found = true;
            } else {
                result.add(hit);
            }
        }
        if (!found) result.add(new Beat.Hit(track, center, velocity));
        return sorted(result);
    }

    /** Moves the whole chord in parallel so its intervals are not flattened. */
    static List<Beat.Hit> setPitch(List<Beat.Hit> hits, int track, int step, double pitch) {
        requireTrack(track);
        requireStep(step);
        validatePitch(pitch);
        double center = stepBeat(step);
        Cell current = cell(hits, track, step);
        double delta = current.active() ? pitch - current.pitch() : 0;
        var result = new ArrayList<Beat.Hit>();
        boolean found = false;
        for (var hit : hits) {
            if (hit.track() == track && inCell(hit.beat(), center)) {
                double moved = clamp(hit.pitch() + delta, -Beat.MAX_NOTE_PITCH, Beat.MAX_NOTE_PITCH);
                result.add(new Beat.Hit(track, hit.beat(), hit.velocity(), moved, hit.length()));
                found = true;
            } else {
                result.add(hit);
            }
        }
        if (!found) result.add(new Beat.Hit(track, center, 0.8, pitch));
        return sorted(result);
    }

    static List<Beat.Hit> setLength(List<Beat.Hit> hits, int track, int step, double length) {
        requireTrack(track);
        requireStep(step);
        validateLength(length);
        double center = stepBeat(step);
        var result = new ArrayList<Beat.Hit>();
        boolean found = false;
        for (var hit : hits) {
            if (hit.track() == track && inCell(hit.beat(), center)) {
                result.add(new Beat.Hit(track, hit.beat(), hit.velocity(), hit.pitch(), length));
                found = true;
            } else {
                result.add(hit);
            }
        }
        if (!found) result.add(new Beat.Hit(track, center, 0.8, 0, length));
        return sorted(result);
    }

    static List<Beat.Hit> removeTrack(List<Beat.Hit> hits, int track) {
        requireTrack(track);
        var result = new ArrayList<Beat.Hit>();
        for (var hit : hits) {
            if (hit.track() == track) continue;
            int next = hit.track() > track ? hit.track() - 1 : hit.track();
            result.add(new Beat.Hit(next, hit.beat(), hit.velocity(), hit.pitch(), hit.length()));
        }
        return sorted(result);
    }

    static List<Beat.Hit> clampToLength(List<Beat.Hit> hits, double length) {
        if (!Double.isFinite(length) || length <= 0) throw new IllegalArgumentException("잘못된 루프 길이입니다.");
        return hits.stream().filter(h -> h.beat() < length).toList();
    }

    private static List<Beat.Hit> sorted(List<Beat.Hit> hits) {
        hits.sort(Comparator.comparingDouble(Beat.Hit::beat)
                .thenComparingInt(Beat.Hit::track)
                .thenComparingDouble(Beat.Hit::pitch));
        return List.copyOf(hits);
    }

    private static boolean inCell(double beat, double center) {
        return Math.abs(beat - center) < CELL / 2;
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < PITCH_EPSILON;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void validatePitch(double pitch) {
        if (!Double.isFinite(pitch) || pitch < -Beat.MAX_NOTE_PITCH || pitch > Beat.MAX_NOTE_PITCH)
            throw new IllegalArgumentException("잘못된 음높이입니다.");
    }

    private static void validateVelocity(double velocity) {
        if (!Double.isFinite(velocity) || velocity < 0.05 || velocity > 1) {
            throw new IllegalArgumentException("잘못된 타격 세기입니다.");
        }
    }

    private static void validateLength(double length) {
        if (!Double.isFinite(length) || length < 0.01 || length > 64) {
            throw new IllegalArgumentException("잘못된 음표 길이입니다.");
        }
    }

    private static void requireTrack(int track) {
        if (track < 0) throw new IllegalArgumentException("잘못된 트랙입니다.");
    }

    private static void requireStep(int step) {
        if (step < 0) throw new IllegalArgumentException("잘못된 스텝입니다.");
    }

    private static void requireStep(int step, int limit) {
        if (step < 0 || step >= limit) throw new IllegalArgumentException("잘못된 스텝입니다.");
    }
}

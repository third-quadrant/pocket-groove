package io.pocketgroove;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure pattern edits shared by the step grid, timeline and tests. */
final class Pattern {
    static final double STEPS_PER_BEAT = 4;
    static final double CELL = 1.0 / STEPS_PER_BEAT;

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

    static List<Beat.Hit> toggle(List<Beat.Hit> hits, int track, int step,
                                double length, double velocity) {
        requireTrack(track);
        requireStep(step, stepCount(length));
        if (!Double.isFinite(velocity) || velocity < 0.05 || velocity > 1) {
            throw new IllegalArgumentException("잘못된 타격 세기입니다.");
        }
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
        result.sort(Comparator.comparingDouble(Beat.Hit::beat).thenComparingInt(Beat.Hit::track));
        return List.copyOf(result);
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
        if (!Double.isFinite(velocity) || velocity < 0.05 || velocity > 1) {
            throw new IllegalArgumentException("잘못된 타격 세기입니다.");
        }
        double center = stepBeat(step);
        var result = new ArrayList<Beat.Hit>();
        boolean found = false;
        for (var hit : hits) {
            if (hit.track() == track && inCell(hit.beat(), center)) {
                result.add(new Beat.Hit(track, hit.beat(), velocity, hit.pitch()));
                found = true;
            } else {
                result.add(hit);
            }
        }
        if (!found) result.add(new Beat.Hit(track, center, velocity));
        result.sort(Comparator.comparingDouble(Beat.Hit::beat).thenComparingInt(Beat.Hit::track));
        return List.copyOf(result);
    }

    static List<Beat.Hit> setPitch(List<Beat.Hit> hits, int track, int step, double pitch) {
        requireTrack(track);
        requireStep(step);
        if (!Double.isFinite(pitch) || pitch < -Beat.MAX_NOTE_PITCH || pitch > Beat.MAX_NOTE_PITCH) {
            throw new IllegalArgumentException("잘못된 음높이입니다.");
        }
        double center = stepBeat(step);
        var result = new ArrayList<Beat.Hit>();
        boolean found = false;
        for (var hit : hits) {
            if (hit.track() == track && inCell(hit.beat(), center)) {
                result.add(new Beat.Hit(track, hit.beat(), hit.velocity(), pitch));
                found = true;
            } else {
                result.add(hit);
            }
        }
        if (!found) result.add(new Beat.Hit(track, center, 0.8, pitch));
        result.sort(Comparator.comparingDouble(Beat.Hit::beat).thenComparingInt(Beat.Hit::track));
        return List.copyOf(result);
    }

    static List<Beat.Hit> removeTrack(List<Beat.Hit> hits, int track) {
        requireTrack(track);
        var result = new ArrayList<Beat.Hit>();
        for (var hit : hits) {
            if (hit.track() == track) continue;
            int next = hit.track() > track ? hit.track() - 1 : hit.track();
            result.add(new Beat.Hit(next, hit.beat(), hit.velocity(), hit.pitch()));
        }
        result.sort(Comparator.comparingDouble(Beat.Hit::beat).thenComparingInt(Beat.Hit::track));
        return List.copyOf(result);
    }

    static List<Beat.Hit> clampToLength(List<Beat.Hit> hits, double length) {
        if (!Double.isFinite(length) || length <= 0) throw new IllegalArgumentException("잘못된 루프 길이입니다.");
        return hits.stream().filter(h -> h.beat() < length).toList();
    }

    private static boolean inCell(double beat, double center) {
        return Math.abs(beat - center) < CELL / 2;
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

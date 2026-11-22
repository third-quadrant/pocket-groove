package io.pocketgroove;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Expands repeat tracks and keeps non-repeat tracks absolute for song-length playback and export. */
final class SongTimeline {
    private SongTimeline() {}

    static List<Beat.Hit> expand(Beat.Project project) {
        return expand(project.tracks(), project.hits(), project.length(), project.bars());
    }

    static List<Beat.Hit> expand(List<Beat.Track> tracks, List<Beat.Hit> hits,
                                 double patternBeats, int bars) {
        double songBeats = Math.max(4.0, bars * 4.0);
        var result = new ArrayList<Beat.Hit>();
        for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
            var track = tracks.get(trackIndex);
            for (var hit : hits) {
                if (hit.track() != trackIndex) continue;
                if (!track.repeat()) {
                    if (hit.beat() < songBeats) result.add(hit);
                    continue;
                }
                if (hit.beat() >= patternBeats) continue;
                for (double start = 0; start < songBeats; start += patternBeats) {
                    double beat = start + hit.beat();
                    if (beat >= songBeats) break;
                    result.add(new Beat.Hit(trackIndex, beat, hit.velocity(), hit.pitch(), hit.length()));
                    if (result.size() > 100_000)
                        throw new IllegalArgumentException("곡이 너무 길거나 반복 타격이 너무 많습니다. 마디 수를 줄이세요.");
                }
            }
        }
        result.sort(Comparator.comparingDouble(Beat.Hit::beat)
                .thenComparingInt(Beat.Hit::track)
                .thenComparingDouble(Beat.Hit::pitch));
        return List.copyOf(result);
    }

    static List<Beat.Hit> barHits(List<Beat.Track> tracks, List<Beat.Hit> hits,
                                  int trackIndex, int bar, double patternBeats) {
        if (trackIndex < 0 || trackIndex >= tracks.size()) return List.of();
        var track = tracks.get(trackIndex);
        double start = bar * 4.0, end = start + 4.0;
        if (track.repeat()) {
            int patternBars = Math.max(1, (int) Math.round(patternBeats / 4.0));
            start = Math.floorMod(bar, patternBars) * 4.0;
            end = start + 4.0;
        }
        var result = new ArrayList<Beat.Hit>();
        for (var hit : hits) {
            if (hit.track() == trackIndex && hit.beat() >= start && hit.beat() < end) result.add(hit);
        }
        return List.copyOf(result);
    }
}

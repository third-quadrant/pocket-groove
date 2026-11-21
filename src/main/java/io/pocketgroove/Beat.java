package io.pocketgroove;

import java.util.*;

public final class Beat {
    public static final int MAX_SAMPLE_BYTES = 16 * 1024 * 1024;
    public record Hit(int pad, double beat) {
        public Hit {
            if (pad < 0 || pad >= 6 || !Double.isFinite(beat) || beat < 0)
                throw new IllegalArgumentException("잘못된 패드 또는 연주 시간입니다.");
        }
    }
    public record Pad(String name, String key, String format, byte[] audio) {
        public Pad {
            if (name == null || name.isBlank() || name.length() > 200 || key == null || key.isBlank()
                    || key.length() > 100 || !Set.of("wav", "mp3", "aiff").contains(format)
                    || audio == null || audio.length == 0 || audio.length > MAX_SAMPLE_BYTES)
                throw new IllegalArgumentException("소리 파일 또는 키 지정이 올바르지 않습니다. 파일은 16 MB 이하여야 합니다.");
            audio = audio.clone();
        }
        @Override public byte[] audio() { return audio.clone(); }
    }
    public record Project(List<Pad> pads, List<Hit> hits, double length, double bpm, double pitch) {
        public Project {
            if (pads == null || pads.size() != 6 || pads.stream().anyMatch(Objects::isNull)
                    || pads.stream().map(Pad::key).distinct().count() != 6
                    || hits == null || hits.size() > 100_000 || hits.stream().anyMatch(Objects::isNull)
                    || !Double.isFinite(length) || length <= 0 || length > 512
                    || !Double.isFinite(bpm) || bpm < 40 || bpm > 240
                    || !Double.isFinite(pitch) || pitch < -12 || pitch > 12
                    || hits.stream().anyMatch(h -> h.beat() >= length))
                throw new IllegalArgumentException("프로젝트의 패드, 키, 템포 또는 반복 구간이 올바르지 않습니다.");
            pads = List.copyOf(pads);
            hits = hits.stream().sorted(Comparator.comparingDouble(Hit::beat)).toList();
        }
    }
    public static double elapsedBeats(long nanos, double bpm) { return nanos / 60_000_000_000.0 * bpm; }
    public static double pitchRate(double semitones) { return Math.pow(2, semitones / 12); }

    /** Hits in (from, to], including each new cycle's beat zero exactly once. */
    public static List<Integer> crossed(List<Hit> hits, double length, double from, double to) {
        var result = new ArrayList<Integer>();
        if (to < from) return result;
        // shortcut: after suspension, resume at the latest cycle instead of replaying a backlog.
        from = Math.max(from, to - length);
        for (long cycle = Math.max(0, (long)Math.floor(from / length)); cycle <= (long)Math.floor(to / length); cycle++) {
            for (Hit hit : hits) {
                double at = cycle * length + hit.beat();
                if (at > from && at <= to) result.add(hit.pad());
            }
        }
        return result;
    }
}

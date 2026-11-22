package io.pocketgroove;

import java.util.*;

public final class Beat {
    public static final int MAX_SAMPLE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_TRACKS = 32;
    public static final int MAX_NOTE_PITCH = 24;

    public enum PitchMode { VARISPEED, PITCH_SHIFT }

    public record Hit(int track, double beat, double velocity, double pitch) {
        public Hit {
            if (track < 0 || !Double.isFinite(beat) || beat < 0
                    || !Double.isFinite(velocity) || velocity < 0.05 || velocity > 1.0
                    || !Double.isFinite(pitch) || pitch < -MAX_NOTE_PITCH || pitch > MAX_NOTE_PITCH)
                throw new IllegalArgumentException("잘못된 트랙, 타격 시간, 세기 또는 음높이입니다.");
        }
        public Hit(int track, double beat, double velocity) { this(track, beat, velocity, 0); }
        public Hit(int track, double beat) { this(track, beat, 1.0, 0); }
    }

    public record Track(String name, String key, String format, byte[] audio,
                        double pitch, PitchMode pitchMode) {
        public Track {
            if (name == null || name.isBlank() || name.length() > 200
                    || key == null || key.isBlank() || key.length() > 100
                    || !Set.of("wav", "mp3", "aiff").contains(format)
                    || audio == null || audio.length == 0 || audio.length > MAX_SAMPLE_BYTES
                    || !Double.isFinite(pitch) || pitch < -12 || pitch > 12)
                throw new IllegalArgumentException("트랙 이름, 단축키, 피치 또는 소리 파일이 올바르지 않습니다. 파일은 16 MB 이하여야 합니다.");
            audio = audio.clone();
            pitchMode = pitchMode == null ? PitchMode.VARISPEED : pitchMode;
        }
        public Track(String name, String key, String format, byte[] audio, double pitch) {
            this(name, key, format, audio, pitch, PitchMode.VARISPEED);
        }
        public Track(String name, String key, String format, byte[] audio) {
            this(name, key, format, audio, 0, PitchMode.VARISPEED);
        }
        @Override public byte[] audio() { return audio.clone(); }
    }

    public record Project(List<Track> tracks, List<Hit> hits,
                          double length, double bpm, double pitch, int bars) {
        public Project {
            if (tracks == null || tracks.size() < 1 || tracks.size() > MAX_TRACKS
                    || tracks.stream().anyMatch(Objects::isNull)
                    || tracks.stream().map(Track::key).distinct().count() != tracks.size()
                    || hits == null || hits.size() > 100_000 || hits.stream().anyMatch(Objects::isNull)
                    || !Double.isFinite(length) || length <= 0 || length > 512
                    || !Double.isFinite(bpm) || bpm < 40 || bpm > 240
                    || !Double.isFinite(pitch) || pitch < -12 || pitch > 12
                    || bars < Math.max(1, (int) Math.ceil(length / 4.0)) || bars > 512)
                throw new IllegalArgumentException("트랙, 단축키, 타격, 루프 또는 전체 마디 설정이 올바르지 않습니다.");
            var frozenTracks = List.copyOf(tracks);
            if (hits.stream().anyMatch(h -> h.track() >= frozenTracks.size() || h.beat() >= length))
                throw new IllegalArgumentException("트랙, 단축키, 타격 또는 루프 설정이 올바르지 않습니다.");
            tracks = frozenTracks;
            hits = hits.stream()
                    .sorted(Comparator.comparingDouble(Hit::beat).thenComparingInt(Hit::track))
                    .toList();
        }

        public Project(List<Track> tracks, List<Hit> hits, double length, double bpm, double pitch) {
            this(tracks, hits, length, bpm, pitch, Math.max(16, (int) Math.ceil(length / 4.0)));
        }
    }
}

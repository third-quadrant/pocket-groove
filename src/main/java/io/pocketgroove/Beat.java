package io.pocketgroove;

import java.util.*;

public final class Beat {
    public static final int MAX_SAMPLE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_TRACKS = 32;
    public static final int MAX_NOTE_PITCH = 24;
    public static final double DEFAULT_NOTE_LENGTH = 0.25;

    public enum PitchMode { VARISPEED, PITCH_SHIFT }
    public enum Instrument { DRUM, MELODY }

    public record Hit(int track, double beat, double velocity, double pitch, double length) {
        public Hit {
            if (track < 0 || !Double.isFinite(beat) || beat < 0
                    || !Double.isFinite(velocity) || velocity < 0.05 || velocity > 1.0
                    || !Double.isFinite(pitch) || pitch < -MAX_NOTE_PITCH || pitch > MAX_NOTE_PITCH
                    || !Double.isFinite(length) || length < 0.01 || length > 64)
                throw new IllegalArgumentException("잘못된 트랙, 타격 시간, 세기, 음높이 또는 길이입니다.");
        }
        public Hit(int track, double beat, double velocity, double pitch) {
            this(track, beat, velocity, pitch, DEFAULT_NOTE_LENGTH);
        }
        public Hit(int track, double beat, double velocity) { this(track, beat, velocity, 0); }
        public Hit(int track, double beat) { this(track, beat, 1.0, 0); }
    }

    public record Track(String name, String key, String format, byte[] audio,
                        double pitch, PitchMode pitchMode, Instrument instrument,
                        int baseMidiNote, boolean formantPreserve, boolean repeat) {
        public Track {
            if (name == null || name.isBlank() || name.length() > 200
                    || key == null || key.isBlank() || key.length() > 100
                    || !Set.of("wav", "mp3", "aiff").contains(format)
                    || audio == null || audio.length == 0 || audio.length > MAX_SAMPLE_BYTES
                    || !Double.isFinite(pitch) || pitch < -12 || pitch > 12
                    || baseMidiNote < 0 || baseMidiNote > 127)
                throw new IllegalArgumentException("트랙 이름, 단축키, 피치, 기준 음 또는 소리 파일이 올바르지 않습니다. 파일은 16 MB 이하여야 합니다.");
            audio = audio.clone();
            pitchMode = pitchMode == null ? PitchMode.VARISPEED : pitchMode;
            instrument = instrument == null ? Instrument.DRUM : instrument;
        }
        public Track(String name, String key, String format, byte[] audio,
                     double pitch, PitchMode pitchMode, Instrument instrument,
                     int baseMidiNote, boolean formantPreserve) {
            this(name, key, format, audio, pitch, pitchMode, instrument, baseMidiNote,
                    formantPreserve, instrument == Instrument.MELODY ? false : true);
        }
        public Track(String name, String key, String format, byte[] audio,
                     double pitch, PitchMode pitchMode) {
            this(name, key, format, audio, pitch, pitchMode, Instrument.DRUM,
                    defaultDrumNote(name), false);
        }
        public Track(String name, String key, String format, byte[] audio, double pitch) {
            this(name, key, format, audio, pitch, PitchMode.VARISPEED);
        }
        public Track(String name, String key, String format, byte[] audio) {
            this(name, key, format, audio, 0, PitchMode.VARISPEED);
        }
        @Override public byte[] audio() { return audio.clone(); }

        public Track withPitch(double value) {
            return new Track(name, key, format, audio, value, pitchMode, instrument,
                    baseMidiNote, formantPreserve, repeat);
        }
        public Track withPitchMode(PitchMode mode) {
            return new Track(name, key, format, audio, pitch, mode, instrument,
                    baseMidiNote, formantPreserve, repeat);
        }
        public Track withFormantPreserve(boolean value) {
            return new Track(name, key, format, audio, pitch, pitchMode, instrument,
                    baseMidiNote, value, repeat);
        }
        public Track withAudio(String newName, String newFormat, byte[] newAudio) {
            return new Track(newName, key, newFormat, newAudio, pitch, pitchMode, instrument,
                    baseMidiNote, formantPreserve, repeat);
        }
        public Track withKey(String newKey) {
            return new Track(name, newKey, format, audio, pitch, pitchMode, instrument,
                    baseMidiNote, formantPreserve, repeat);
        }
        public Track withInstrument(Instrument newInstrument, int newBaseMidiNote) {
            return new Track(name, key, format, audio, pitch, pitchMode, newInstrument,
                    newBaseMidiNote, formantPreserve, repeat);
        }
        public Track withRepeat(boolean value) {
            return new Track(name, key, format, audio, pitch, pitchMode, instrument,
                    baseMidiNote, formantPreserve, value);
        }
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
            double songBeats = bars * 4.0;
            if (hits.stream().anyMatch(h -> h.track() >= frozenTracks.size() || h.beat() >= songBeats))
                throw new IllegalArgumentException("트랙, 단축키, 타격 또는 루프 설정이 올바르지 않습니다.");
            tracks = frozenTracks;
            hits = hits.stream()
                    .sorted(Comparator.comparingDouble(Hit::beat)
                            .thenComparingInt(Hit::track)
                            .thenComparingDouble(Hit::pitch))
                    .toList();
        }

        public Project(List<Track> tracks, List<Hit> hits, double length, double bpm, double pitch) {
            this(tracks, hits, length, bpm, pitch, Math.max(16, (int) Math.ceil(length / 4.0)));
        }

        public double songBeats() {
            return bars * 4.0;
        }
    }

    static int defaultDrumNote(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (value.contains("kick")) return 36;
        if (value.contains("snare")) return 38;
        if (value.contains("clap")) return 39;
        if (value.contains("open") && value.contains("hat")) return 46;
        if (value.contains("hat")) return 42;
        if (value.contains("tom")) return 45;
        if (value.contains("ride")) return 51;
        if (value.contains("crash")) return 57;
        if (value.contains("perc")) return 49;
        return 36;
    }
}

package io.pocketgroove;

import java.util.*;

/**
 * Background-generated 48 kHz Signalsmith pitch variants shared by realtime playback and WAV export.
 * Keys are track identity + tenth-semitone + formant, so a changed sound or setting re-renders.
 */
final class PitchCache {
    static final int RATE = 48000;
    static final long DEFAULT_LIMIT_BYTES = 256L * 1024 * 1024;

    record Variant(int tenth, float[] pcm) {}
    record TrackVariants(int track, List<Variant> variants) {}
    record Result(List<TrackVariants> tracks, int missing, long bytes, boolean capped, int rendered) {}

    private record Key(int track, int identity, int tenth, boolean formant) {}
    private record Source(Beat.Track track, Sounds.Pcm pcm) {}

    private final long limitBytes;
    private final LinkedHashMap<Key, float[]> entries = new LinkedHashMap<>(32, .75f, true);
    private final Map<Integer, Source> sources = new HashMap<>();
    private final Set<Key> protectedKeys = new HashSet<>();
    private long bytes;
    private int rendered;

    PitchCache() { this(DEFAULT_LIMIT_BYTES); }

    PitchCache(long limitBytes) {
        if (limitBytes < 1) throw new IllegalArgumentException("캐시 상한이 올바르지 않습니다.");
        this.limitBytes = limitBytes;
    }

    synchronized Result prepare(List<Beat.Track> tracks, List<Beat.Hit> hits, double projectPitch) throws java.io.IOException {
        return prepare(tracks, hits, projectPitch, null);
    }

    synchronized Result prepare(List<Beat.Track> tracks, List<Beat.Hit> hits, double projectPitch,
                                List<Sounds.Pcm> decoded) throws java.io.IOException {
        if (!Double.isFinite(projectPitch) || projectPitch < -12 || projectPitch > 12)
            throw new IllegalArgumentException("전체 피치가 올바르지 않습니다.");
        syncSources(tracks, decoded);
        var needed = new LinkedHashMap<Integer, java.util.LinkedHashSet<Integer>>();
        for (int i = 0; i < tracks.size(); i++) needed.put(i, new java.util.LinkedHashSet<>());
        for (var hit : hits) {
            if (hit.track() < 0 || hit.track() >= tracks.size()) continue;
            var track = tracks.get(hit.track());
            if (track.pitchMode() != Beat.PitchMode.PITCH_SHIFT) continue;
            int tenth = tenth(projectPitch + track.pitch() + hit.pitch());
            needed.get(hit.track()).add(tenth);
        }
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).pitchMode() == Beat.PitchMode.PITCH_SHIFT
                    && !needed.get(i).contains(0)) needed.get(i).add(0);
        }

        protectedKeys.clear();
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).pitchMode() != Beat.PitchMode.PITCH_SHIFT) continue;
            for (int tenth : needed.get(i))
                protectedKeys.add(new Key(i, System.identityHashCode(tracks.get(i)),
                        tenth, tracks.get(i).formantPreserve()));
        }

        int missing = 0;
        boolean capped = false;
        int before = rendered;
        var result = new ArrayList<TrackVariants>(tracks.size());
        for (int i = 0; i < tracks.size(); i++) {
            var track = tracks.get(i);
            if (track.pitchMode() != Beat.PitchMode.PITCH_SHIFT) {
                result.add(new TrackVariants(i, List.of()));
                continue;
            }
            var list = new ArrayList<Integer>(needed.get(i));
            Collections.sort(list);
            var variants = new ArrayList<Variant>();
            for (int tenth : list) {
                var pcm = render(i, track, tenth);
                if (pcm == null) {
                    missing++;
                    capped = true;
                } else {
                    variants.add(new Variant(tenth, pcm));
                }
            }
            result.add(new TrackVariants(i, List.copyOf(variants)));
        }
        return new Result(List.copyOf(result), missing, bytes, capped, rendered - before);
    }

    /** On-demand access for the WAV renderer; renders on the calling (background) thread. */
    synchronized float[] get(int trackIndex, Beat.Track track, int tenth) throws java.io.IOException {
        var pcm = render(trackIndex, track, tenth);
        if (pcm == null) throw new IllegalStateException("피치 캐시 메모리 상한을 넘었습니다.");
        return pcm;
    }

    synchronized void invalidateTrack(int trackIndex) {
        var source = sources.remove(trackIndex);
        if (source == null) return;
        entries.entrySet().removeIf(entry -> {
            if (entry.getKey().track() != trackIndex) return false;
            bytes -= sizeOf(entry.getValue());
            return true;
        });
        protectedKeys.removeIf(key -> key.track() == trackIndex);
    }

    synchronized void clear() {
        entries.clear();
        sources.clear();
        protectedKeys.clear();
        bytes = 0;
    }

    synchronized long bytes() { return bytes; }
    synchronized int size() { return entries.size(); }
    synchronized int renderedCount() { return rendered; }
    synchronized long limitBytes() { return limitBytes; }

    static int tenth(double semitones) {
        return (int) Math.round(semitones * 10);
    }

    private void syncSources(List<Beat.Track> tracks, List<Sounds.Pcm> decoded) throws java.io.IOException {
        var present = new HashSet<Integer>();
        for (int i = 0; i < tracks.size(); i++) {
            var track = tracks.get(i);
            if (track.pitchMode() != Beat.PitchMode.PITCH_SHIFT) {
                invalidateTrack(i);
                continue;
            }
            present.add(i);
            var source = sources.get(i);
            if (source != null && source.track() == track) continue;
            invalidateTrack(i);
            var pcm = decoded != null && i < decoded.size() ? decoded.get(i) : Sounds.decode(track);
            sources.put(i, new Source(track, Sounds.resample(pcm, RATE)));
        }
        var stale = new ArrayList<>(sources.keySet());
        for (int index : stale) if (!present.contains(index)) invalidateTrack(index);
    }

    private float[] render(int trackIndex, Beat.Track track, int tenth) throws java.io.IOException {
        var source = sources.get(trackIndex);
        if (source == null || source.track() != track) {
            invalidateTrack(trackIndex);
            source = new Source(track, Sounds.resample(Sounds.decode(track), RATE));
            sources.put(trackIndex, source);
        }
        var key = new Key(trackIndex, System.identityHashCode(track), tenth, track.formantPreserve());
        var cached = entries.get(key);
        if (cached != null) return cached;

        float[] pcm = PitchRender.render(source.pcm().stereo(), RATE, tenth / 10.0,
                track.formantPreserve());
        long size = sizeOf(pcm);
        if (size > limitBytes) return null;
        while (bytes + size > limitBytes) {
            if (!evictOne()) return null;
        }
        entries.put(key, pcm);
        bytes += size;
        rendered++;
        return pcm;
    }

    private boolean evictOne() {
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (protectedKeys.contains(entry.getKey())) continue;
            bytes -= sizeOf(entry.getValue());
            iterator.remove();
            return true;
        }
        return false;
    }

    private static long sizeOf(float[] pcm) {
        return (long) pcm.length * Float.BYTES;
    }
}

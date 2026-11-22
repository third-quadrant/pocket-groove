package io.pocketgroove;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Java owns files and controls; the native callback owns playback and musical time. */
final class AudioEngine implements AutoCloseable {
    record Device(int id, String name) { @Override public String toString() { return name; } }
    record State(double position, double length, int hits, boolean recording, boolean playing,
                 double outputMs, long underruns, long dropped, double callbackMs) {}
    record Recording(List<Beat.Hit> hits, double length) {}

    private long handle;

    private AudioEngine(long handle) { this.handle = handle; }

    static {
        NativeLibrary.load();
    }

    static AudioEngine open(int device, boolean exclusive, int frames) throws IOException {
        if (device < -1 || !Set.of(64, 128, 256, 512).contains(frames))
            throw new IllegalArgumentException("잘못된 출력 설정입니다.");
        return new AudioEngine(nOpen(device, exclusive, frames));
    }

    static List<Device> devices() throws IOException {
        String[] entries = nDevices();
        var result = new ArrayList<Device>();
        result.add(new Device(-1, "기본 출력 장치"));
        for (String entry : entries) {
            int split = entry.indexOf('\t');
            result.add(new Device(Integer.parseInt(entry.substring(0, split)), entry.substring(split + 1)));
        }
        return List.copyOf(result);
    }

    synchronized void install(List<Sounds.Pcm> samples, List<Double> pitches,
                              List<Boolean> pitchShift, List<Beat.Hit> hits,
                              double length, double bpm) throws IOException {
        requireOpen();
        if (samples.size() < 1 || samples.size() > Beat.MAX_TRACKS
                || pitches.size() != samples.size() || pitchShift.size() != samples.size())
            throw new IllegalArgumentException("트랙, 피치, 피치 방식은 1–32개로 같아야 합니다.");
        var data = new float[samples.size()][];
        var rates = new int[samples.size()];
        var tune = new double[samples.size()];
        var modes = new boolean[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            data[i] = samples.get(i).stereo();
            rates[i] = samples.get(i).sampleRate();
            tune[i] = pitches.get(i);
            modes[i] = pitchShift.get(i);
            validate(tune[i], -12, 12);
        }
        nInstall(handle, data, rates, tune, modes, tracks(hits), beats(hits), velocities(hits),
                notePitches(hits), length, bpm);
    }

    synchronized void setLoop(List<Beat.Hit> hits, double length, double bpm) throws IOException {
        requireOpen();
        validateLength(length);
        validate(bpm, 40, 240);
        nSetLoop(handle, tracks(hits), beats(hits), velocities(hits), notePitches(hits), length, bpm);
    }

    synchronized void reconfigure(int device, boolean exclusive, int frames) throws IOException {
        requireOpen();
        if (device < -1 || !Set.of(64, 128, 256, 512).contains(frames))
            throw new IllegalArgumentException("잘못된 출력 설정입니다.");
        nReconfigure(handle, device, exclusive, frames);
    }

    synchronized void hit(int track, double velocity, double pitch) {
        if (track < 0 || track >= Beat.MAX_TRACKS) throw new IllegalArgumentException("잘못된 트랙입니다.");
        validate(velocity, 0.05, 1);
        validate(pitch, -Beat.MAX_NOTE_PITCH, Beat.MAX_NOTE_PITCH);
        command(0, track, velocity, pitch, false);
    }

    synchronized void stopAll() { command(1, 0, 0, 0, true); }
    synchronized void playLoop() { command(2, 0, 0, 0, true); }
    synchronized void record(int track, double beats, double bpm) {
        if (track < 0 || track >= Beat.MAX_TRACKS) throw new IllegalArgumentException("잘못된 트랙입니다.");
        validate(beats, 1, 512);
        validate(bpm, 40, 240);
        command(3, track, beats, bpm, true);
    }
    synchronized void finishRecording() { command(4, 0, 0, 0, true); }
    synchronized void tempo(double bpm) { validate(bpm, 40, 240); command(5, 0, bpm, 0, false); }
    synchronized void parameters(double gain, double pitch) { validate(gain, 0, 1); validate(pitch, -12, 12); command(6, 0, gain, pitch, false); }
    synchronized void trackPitch(int track, double pitch) {
        if (track < 0 || track >= Beat.MAX_TRACKS) throw new IllegalArgumentException("잘못된 트랙입니다.");
        validate(pitch, -12, 12);
        command(7, track, pitch, 0, false);
    }

    synchronized void trackMode(int track, boolean pitchShift) {
        if (track < 0 || track >= Beat.MAX_TRACKS) throw new IllegalArgumentException("잘못된 트랙입니다.");
        command(8, track, pitchShift ? 1 : 0, 0, false);
    }

    synchronized State state() {
        requireOpen();
        double[] s = nState(handle);
        return new State(s[0], s[1], (int) s[2], s[3] == 1, s[3] == 2,
                s[4] * 1000, (long) s[5], (long) s[6], s[7] * 1000);
    }

    synchronized Recording snapshot() throws IOException {
        requireOpen();
        double[] data = nRecording(handle);
        var hits = new ArrayList<Beat.Hit>();
        for (int i = 1; i < data.length; i += 4)
            hits.add(new Beat.Hit((int) data[i], data[i + 1], data[i + 2], data[i + 3]));
        return new Recording(List.copyOf(hits), data[0]);
    }

    synchronized String description() { requireOpen(); return nDescription(handle); }

    private void command(int type, int track, double value, double extra, boolean wait) {
        requireOpen();
        nCommand(handle, type, track, value, extra, wait);
    }

    private static void validateLength(double length) {
        validate(length, 0.01, 512);
    }

    private static void validate(double n, double min, double max) {
        if (!Double.isFinite(n) || n < min || n > max)
            throw new IllegalArgumentException("오디오 설정 범위를 벗어났습니다.");
    }

    private static int[] tracks(List<Beat.Hit> hits) {
        if (hits == null || hits.size() > 100_000) throw new IllegalArgumentException("타격 수가 너무 많습니다.");
        return hits.stream().mapToInt(Beat.Hit::track).toArray();
    }

    private static double[] beats(List<Beat.Hit> hits) { return hits.stream().mapToDouble(Beat.Hit::beat).toArray(); }
    private static double[] velocities(List<Beat.Hit> hits) { return hits.stream().mapToDouble(Beat.Hit::velocity).toArray(); }
    private static double[] notePitches(List<Beat.Hit> hits) { return hits.stream().mapToDouble(Beat.Hit::pitch).toArray(); }

    private void requireOpen() {
        if (handle == 0) throw new IllegalStateException("오디오 엔진이 닫혔습니다.");
    }

    @Override public synchronized void close() {
        if (handle != 0) {
            nClose(handle);
            handle = 0;
        }
    }

    private static native long nOpen(int device, boolean exclusive, int frames) throws IOException;
    private static native void nReconfigure(long handle, int device, boolean exclusive, int frames) throws IOException;
    private static native String[] nDevices() throws IOException;
    private static native void nInstall(long handle, float[][] samples, int[] rates, double[] trackPitches,
                                        boolean[] pitchShift, int[] tracks, double[] beats,
                                        double[] velocities, double[] notePitches,
                                        double length, double bpm) throws IOException;
    private static native void nSetLoop(long handle, int[] tracks, double[] beats, double[] velocities,
                                       double[] notePitches, double length, double bpm) throws IOException;
    private static native void nCommand(long handle, int type, int track, double value, double extra, boolean wait);
    private static native double[] nState(long handle);
    private static native double[] nRecording(long handle) throws IOException;
    private static native String nDescription(long handle);
    private static native void nClose(long handle);
}

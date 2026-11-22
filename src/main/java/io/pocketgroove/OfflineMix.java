package io.pocketgroove;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Shared offline renderer for WAV; output is 48 kHz stereo float. */
final class OfflineMix {
    static final int SAMPLE_RATE = 48000;
    private static final int CHUNK = 4096;

    @FunctionalInterface
    interface Sink {
        void write(float[] stereo, int frames) throws IOException;
    }

    private OfflineMix() {}

    static long totalFrames(Beat.Project project, int startBar, int endBar) {
        validate(project, startBar, endBar);
        return Math.max(1, Math.round((endBar - startBar + 1) * 4.0
                * 60.0 / project.bpm() * SAMPLE_RATE));
    }

    static void render(Beat.Project project, List<Sounds.Pcm> pcm, double gain,
                       int startBar, int endBar, Sink sink) throws IOException {
        render(project, pcm, gain, startBar, endBar, null, sink);
    }

    static void render(Beat.Project project, List<Sounds.Pcm> pcm, double gain,
                       int startBar, int endBar, PitchCache cache, Sink sink) throws IOException {
        validate(project, startBar, endBar);
        if (!Double.isFinite(gain) || gain < 0 || gain > 1)
            throw new IllegalArgumentException("출력 음량이 올바르지 않습니다.");
        double startBeat = (startBar - 1) * 4.0, endBeat = endBar * 4.0;
        long totalFrames = totalFrames(project, startBar, endBar);
        var events = new ArrayList<Event>();
        for (var hit : SongTimeline.expand(project.tracks(), project.hits(),
                project.length(), project.bars())) {
            if (hit.beat() < startBeat - 1e-9 || hit.beat() >= endBeat - 1e-9) continue;
            long frame = Math.round((hit.beat() - startBeat) * 60.0 / project.bpm() * SAMPLE_RATE);
            if (frame < 0 || frame >= totalFrames) continue;
            events.add(new Event((int) frame, hit.track(), hit.velocity(), hit.pitch()));
        }
        events.sort((a, b) -> Integer.compare(a.frame, b.frame));
        var voices = new ArrayList<Voice>();
        int nextEvent = 0;
        long frame = 0;
        float[] buffer = new float[CHUNK * 2];
        while (frame < totalFrames) {
            int fill = (int) Math.min(CHUNK, totalFrames - frame);
            for (int i = 0; i < fill; i++) {
                long currentFrame = frame + i;
                while (nextEvent < events.size() && events.get(nextEvent).frame <= currentFrame) {
                    var event = events.get(nextEvent++);
                    var sample = pcm.get(event.track());
                    var track = project.tracks().get(event.track());
                    double pitch = project.pitch() + track.pitch() + event.pitch;
                    double ratio = Math.pow(2.0, pitch / 12.0);
                    double scale = sample.sampleRate() / (double) SAMPLE_RATE;
                    boolean pitchShift = track.pitchMode() == Beat.PitchMode.PITCH_SHIFT;
                    if (pitchShift) {
                        if (cache == null) throw new IOException(
                                "Pitch Shift를 렌더링하려면 피치 캐시가 필요합니다.");
                        int tenth = PitchCache.tenth(pitch);
                        var prepared = cache.get(event.track(), track, tenth);
                        voices.add(Voice.prepared(prepared, gain * event.velocity));
                    } else {
                        voices.add(new Voice(scale * ratio, gain * event.velocity, sample.stereo()));
                    }
                }
                float l = 0, r = 0;
                for (var voice : voices) {
                    if (voice.finished()) continue;
                    if (voice.variant) {
                        int index = (int) voice.position;
                        int next = Math.min(index + 1, voice.pcm.length / 2 - 1);
                        float fraction = (float) (voice.position - index);
                        l += (voice.pcm[index * 2]
                                + (voice.pcm[next * 2] - voice.pcm[index * 2]) * fraction) * voice.gain;
                        r += (voice.pcm[index * 2 + 1]
                                + (voice.pcm[next * 2 + 1] - voice.pcm[index * 2 + 1]) * fraction) * voice.gain;
                        voice.position += 1;
                    } else {
                        int index = (int) voice.position;
                        int next = Math.min(index + 1, voice.pcm.length / 2 - 1);
                        float fraction = (float) (voice.position - index);
                        l += (voice.pcm[index * 2]
                                + (voice.pcm[next * 2] - voice.pcm[index * 2]) * fraction) * voice.gain;
                        r += (voice.pcm[index * 2 + 1]
                                + (voice.pcm[next * 2 + 1] - voice.pcm[index * 2 + 1]) * fraction) * voice.gain;
                        voice.position += voice.step;
                    }
                }
                buffer[i * 2] = l;
                buffer[i * 2 + 1] = r;
            }
            sink.write(buffer, fill);
            frame += fill;
            voices.removeIf(Voice::finished);
        }
    }

    private static void validate(Beat.Project project, int startBar, int endBar) {
        if (project == null || startBar < 1 || endBar < startBar || endBar > project.bars())
            throw new IllegalArgumentException("내보낼 마디 범위가 올바르지 않습니다.");
    }

    private record Event(int frame, int track, double velocity, double pitch) {}

    private static final class Voice {
        final double step, gain;
        final boolean variant;
        final float[] pcm;
        double position;

        Voice(double step, double gain, float[] pcm) {
            this(step, false, gain, pcm);
        }

        Voice(double step, boolean variant, double gain, float[] pcm) {
            this.step = step;
            this.variant = variant;
            this.gain = gain;
            this.pcm = pcm;
        }

        static Voice prepared(float[] pcm, double gain) {
            return new Voice(1, true, gain, pcm);
        }

        boolean finished() {
            return position >= pcm.length / 2.0;
        }
    }
}

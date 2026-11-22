package io.pocketgroove;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Shared offline renderer for WAV and MP3; output is 48 kHz stereo float. */
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
        validate(project, startBar, endBar);
        if (!Double.isFinite(gain) || gain < 0 || gain > 1)
            throw new IllegalArgumentException("출력 음량이 올바르지 않습니다.");
        double startBeat = (startBar - 1) * 4.0, endBeat = endBar * 4.0;
        long totalFrames = totalFrames(project, startBar, endBar);
        var events = new ArrayList<Event>();
        int firstCycle = (int) Math.floor(startBeat / project.length());
        int lastCycle = (int) Math.ceil(endBeat / project.length());
        for (int cycle = firstCycle; cycle <= lastCycle; cycle++) {
            double cycleStart = cycle * project.length();
            for (var hit : project.hits()) {
                double beat = cycleStart + hit.beat();
                if (beat < startBeat - 1e-9 || beat >= endBeat - 1e-9) continue;
                long frame = Math.round((beat - startBeat) * 60.0 / project.bpm() * SAMPLE_RATE);
                if (frame < 0 || frame >= totalFrames) continue;
                events.add(new Event((int) frame, hit.track(), hit.velocity(), hit.pitch()));
            }
        }
        events.sort((a, b) -> Integer.compare(a.frame, b.frame));
        var voices = new ArrayList<Voice>();
        int nextEvent = 0;
        long frame = 0;
        float[] buffer = new float[CHUNK * 2];
        while (frame < totalFrames) {
            while (nextEvent < events.size() && events.get(nextEvent).frame <= frame) {
                var event = events.get(nextEvent++);
                var sample = pcm.get(event.track());
                var track = project.tracks().get(event.track());
                double pitch = project.pitch() + track.pitch() + event.pitch;
                double ratio = Math.pow(2.0, pitch / 12.0);
                double scale = sample.sampleRate() / (double) SAMPLE_RATE;
                boolean pitchShift = track.pitchMode() == Beat.PitchMode.PITCH_SHIFT;
                voices.add(new Voice(pitchShift ? 1 : scale * ratio, ratio, scale,
                        pitchShift, gain * event.velocity, sample.stereo()));
            }
            int fill = (int) Math.min(CHUNK, totalFrames - frame);
            for (int i = 0; i < fill; i++) {
                float l = 0, r = 0;
                for (var voice : voices) {
                    if (voice.finished()) continue;
                    if (voice.pitchShift) {
                        double hop = voice.hop;
                        double grain = Math.floor(voice.position / hop);
                        double fraction = voice.position - grain * hop;
                        double weight = grain < 1 ? 1 : 0.5 + 0.5 * (fraction / hop);
                        double frames = voice.pcm.length / 2.0;
                        double maxStart = Math.max(0, frames - hop * voice.ratio * voice.scale);
                        double currentStart = Math.min(grain * hop * voice.scale, maxStart);
                        double previousStart = Math.min((grain - 1) * hop * voice.scale, maxStart);
                        double current = currentStart + fraction * voice.ratio * voice.scale;
                        l += read(voice.pcm, current, 0) * weight * voice.gain;
                        r += read(voice.pcm, current, 1) * weight * voice.gain;
                        if (grain >= 1) {
                            double previous = previousStart
                                    + (hop + fraction) * voice.ratio * voice.scale;
                            l += read(voice.pcm, previous, 0) * (1 - weight) * voice.gain;
                            r += read(voice.pcm, previous, 1) * (1 - weight) * voice.gain;
                        }
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

    private static float read(float[] pcm, double position, int channel) {
        int frames = pcm.length / 2;
        if (position < 0 || position >= frames) return 0;
        int index = (int) position;
        int next = Math.min(index + 1, frames - 1);
        float fraction = (float) (position - index);
        return pcm[index * 2 + channel]
                + (pcm[next * 2 + channel] - pcm[index * 2 + channel]) * fraction;
    }

    private record Event(int frame, int track, double velocity, double pitch) {}

    private static final class Voice {
        final double step, ratio, scale, gain, hop;
        final boolean pitchShift;
        final float[] pcm;
        double position;

        Voice(double step, double ratio, double scale, boolean pitchShift,
              double gain, float[] pcm) {
            this.step = step;
            this.ratio = ratio;
            this.scale = scale;
            this.pitchShift = pitchShift;
            this.gain = gain;
            this.pcm = pcm;
            double outputLength = pcm.length / 2.0 / scale;
            this.hop = Math.min(512, Math.max(16, outputLength / 4));
        }

        boolean finished() {
            return pitchShift ? position >= pcm.length / 2.0 / scale
                    : position >= pcm.length / 2.0;
        }
    }
}

package io.pocketgroove;

import de.sciss.jump3r.lowlevel.LameEncoder;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Offline render of repeated project bars, then LAME encoding to MP3. */
final class Mp3Exporter {
    private static final int SAMPLE_RATE = 48000;

    private Mp3Exporter() {}

    static void export(Path destination, Beat.Project project, double gain) throws IOException {
        export(destination, project, gain, 1, project.bars());
    }

    static void export(Path destination, Beat.Project project, double gain,
                       int startBar, int endBar) throws IOException {
        if (!Double.isFinite(gain) || gain < 0 || gain > 1)
            throw new IllegalArgumentException("출력 음량이 올바르지 않습니다.");
        if (startBar < 1 || endBar < startBar || endBar > project.bars())
            throw new IllegalArgumentException("내보낼 마디 범위가 올바르지 않습니다.");
        double startBeat = (startBar - 1) * 4.0, endBeat = endBar * 4.0;
        long totalFrames = Math.max(1, Math.round(
                (endBeat - startBeat) * 60.0 / project.bpm() * SAMPLE_RATE));
        var pcm = Sounds.decodeAll(project.tracks());
        var parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        var raw = Files.createTempFile(parent, ".pocket-groove-", ".raw");
        var tagged = Files.createTempFile(destination.toAbsolutePath().getParent(), ".pocket-groove-", ".mp3");
        try {
            try (var out = new BufferedOutputStream(Files.newOutputStream(raw))) {
                encode(out, project, pcm, gain, startBeat, endBeat, totalFrames);
            }
            byte[] tag = id3Tag(fileName(destination), project.bpm());
            try (var input = Files.newInputStream(raw);
                 var out = new BufferedOutputStream(Files.newOutputStream(tagged))) {
                out.write(tag);
                input.transferTo(out);
            }
            try {
                Files.move(tagged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tagged, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(raw);
            Files.deleteIfExists(tagged);
        }
    }

    private static String fileName(Path path) {
        String name = path.getFileName().toString();
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3")
                ? name.substring(0, name.length() - 4) : name;
    }

    /** Minimal ID3v2.3 tag so players can show the title and BPM metadata. */
    private static byte[] id3Tag(String title, double bpm) {
        var frames = new ByteArrayOutputStream();
        byte[] titleBytes = title.getBytes(StandardCharsets.UTF_16);
        writeFrame(frames, "TIT2", new byte[0], concat(new byte[]{1}, titleBytes));
        byte[] bpmBytes = Long.toString(Math.round(bpm)).getBytes(StandardCharsets.ISO_8859_1);
        writeFrame(frames, "TBPM", new byte[0], concat(new byte[]{0}, bpmBytes));
        byte[] body = frames.toByteArray();
        var tag = new ByteArrayOutputStream(body.length + 10);
        tag.writeBytes(new byte[]{'I', 'D', '3', 3, 0, 0});
        tag.writeBytes(syncSafe(body.length));
        tag.writeBytes(body);
        return tag.toByteArray();
    }

    private static void writeFrame(ByteArrayOutputStream out, String id, byte[] flags, byte[] body) {
        out.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[]{
                (byte) (body.length >> 24), (byte) (body.length >> 16),
                (byte) (body.length >> 8), (byte) body.length});
        out.writeBytes(flags);
        out.writeBytes(body);
    }

    private static byte[] syncSafe(int size) {
        return new byte[]{(byte) ((size >> 21) & 0x7f), (byte) ((size >> 14) & 0x7f),
                (byte) ((size >> 7) & 0x7f), (byte) (size & 0x7f)};
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static void encode(OutputStream out, Beat.Project project, List<Sounds.Pcm> pcm,
                               double gain, double startBeat, double endBeat,
                               long totalFrames) throws IOException {
        var encoder = new LameEncoder(new AudioFormat(SAMPLE_RATE, 16, 2, true, false));
        try {
            int chunkFrames = Math.max(1152, encoder.getPCMBufferSize() / 4);
            byte[] pcmBytes = new byte[chunkFrames * 4];
            byte[] mp3 = new byte[Math.max(7200, encoder.getMP3BufferSize())];
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
                int fill = (int) Math.min(chunkFrames, totalFrames - frame);
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
                            l += (voice.pcm[index * 2] + (voice.pcm[next * 2] - voice.pcm[index * 2]) * fraction) * voice.gain;
                            r += (voice.pcm[index * 2 + 1] + (voice.pcm[next * 2 + 1] - voice.pcm[index * 2 + 1]) * fraction) * voice.gain;
                            voice.position += voice.step;
                        }
                    }
                    short left = toShort(l), right = toShort(r);
                    pcmBytes[i * 4] = (byte) left;
                    pcmBytes[i * 4 + 1] = (byte) (left >> 8);
                    pcmBytes[i * 4 + 2] = (byte) right;
                    pcmBytes[i * 4 + 3] = (byte) (right >> 8);
                }
                int written = encoder.encodeBuffer(pcmBytes, 0, fill * 4, mp3);
                if (written < 0) throw new IOException("MP3를 인코딩하지 못했습니다.");
                out.write(mp3, 0, written);
                frame += fill;
                voices.removeIf(Voice::finished);
            }
            int written = encoder.encodeFinish(mp3);
            if (written < 0) throw new IOException("MP3 마무리를 하지 못했습니다.");
            out.write(mp3, 0, written);
        } finally {
            encoder.close();
        }
    }

    private static short toShort(float value) {
        return (short) Math.round(Math.max(-1f, Math.min(1f, value)) * 32767);
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

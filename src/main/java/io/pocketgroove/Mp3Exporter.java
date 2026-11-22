package io.pocketgroove;

import de.sciss.jump3r.lowlevel.LameEncoder;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Offline render of repeated project bars, then LAME encoding to MP3. */
final class Mp3Exporter {
    private Mp3Exporter() {}

    static void export(Path destination, Beat.Project project, double gain) throws IOException {
        export(destination, project, gain, 1, project.bars());
    }

    static void export(Path destination, Beat.Project project, double gain,
                       int startBar, int endBar) throws IOException {
        OfflineMix.totalFrames(project, startBar, endBar);
        if (!Double.isFinite(gain) || gain < 0 || gain > 1)
            throw new IllegalArgumentException("출력 음량이 올바르지 않습니다.");
        var pcm = Sounds.decodeAll(project.tracks());
        var parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        var raw = Files.createTempFile(parent, ".pocket-groove-", ".raw");
        var tagged = Files.createTempFile(parent, ".pocket-groove-", ".mp3");
        try {
            var encoder = new LameEncoder(new AudioFormat(OfflineMix.SAMPLE_RATE, 16, 2, true, false));
            try {
                byte[] mp3 = new byte[Math.max(7200, encoder.getMP3BufferSize())];
                try (var out = new BufferedOutputStream(Files.newOutputStream(raw))) {
                    OfflineMix.render(project, pcm, gain, startBar, endBar, (samples, frames) -> {
                        byte[] bytes16 = new byte[frames * 4];
                        for (int i = 0; i < frames * 2; i++) {
                            short value = toShort(samples[i]);
                            bytes16[i * 2] = (byte) value;
                            bytes16[i * 2 + 1] = (byte) (value >> 8);
                        }
                        int written = encoder.encodeBuffer(bytes16, 0, bytes16.length, mp3);
                        if (written < 0) throw new IOException("MP3를 인코딩하지 못했습니다.");
                        out.write(mp3, 0, written);
                    });
                    int written = encoder.encodeFinish(mp3);
                    if (written < 0) throw new IOException("MP3 마무리를 하지 못했습니다.");
                    out.write(mp3, 0, written);
                }
            } finally {
                encoder.close();
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

    private static short toShort(float value) {
        return (short) Math.round(Math.max(-1f, Math.min(1f, value)) * 32767);
    }

    /** Minimal ID3v2.3 tag so players can show the title and BPM metadata. */
    private static byte[] id3Tag(String title, double bpm) {
        var frames = new ByteArrayOutputStream();
        byte[] titleBytes = title.getBytes(StandardCharsets.UTF_16);
        writeFrame(frames, "TIT2", concat(new byte[]{1}, titleBytes));
        byte[] bpmBytes = Long.toString(Math.round(bpm)).getBytes(StandardCharsets.ISO_8859_1);
        writeFrame(frames, "TBPM", concat(new byte[]{0}, bpmBytes));
        byte[] body = frames.toByteArray();
        var tag = new ByteArrayOutputStream(body.length + 10);
        tag.writeBytes(new byte[]{'I', 'D', '3', 3, 0, 0});
        tag.writeBytes(syncSafe(body.length));
        tag.writeBytes(body);
        return tag.toByteArray();
    }

    private static void writeFrame(ByteArrayOutputStream out, String id, byte[] body) {
        out.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[]{
                (byte) (body.length >> 24), (byte) (body.length >> 16),
                (byte) (body.length >> 8), (byte) body.length});
        out.writeBytes(new byte[2]);
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
}

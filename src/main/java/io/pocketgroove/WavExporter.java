package io.pocketgroove;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Sample-exact 32-bit float WAV export for DAW placement. */
final class WavExporter {
    private WavExporter() {}

    static void export(Path destination, Beat.Project project, double gain) throws IOException {
        export(destination, project, gain, 1, project.bars());
    }

    static void export(Path destination, Beat.Project project, double gain,
                       int startBar, int endBar) throws IOException {
        export(destination, project, gain, startBar, endBar, null);
    }

    static void export(Path destination, Beat.Project project, double gain,
                       int startBar, int endBar, PitchCache cache) throws IOException {
        long frames = OfflineMix.totalFrames(project, startBar, endBar);
        if (!Double.isFinite(gain) || gain < 0 || gain > 1)
            throw new IllegalArgumentException("출력 음량이 올바르지 않습니다.");
        long dataSize = frames * 8;
        if (dataSize > Integer.MAX_VALUE - 36)
            throw new IOException("WAV 파일이 너무 큽니다. 내보낼 마디 범위를 줄이세요.");
        var pcm = Sounds.decodeAll(project.tracks());
        var parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        var temp = Files.createTempFile(parent, ".pocket-groove-", ".wav");
        try {
            try (var out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                writeHeader(out, (int) dataSize);
                OfflineMix.render(project, pcm, gain, startBar, endBar, cache, (samples, count) -> {
                    byte[] bytes = new byte[count * 8];
                    for (int i = 0; i < count * 2; i++) {
                        int bits = Float.floatToIntBits(samples[i]);
                        bytes[i * 4] = (byte) bits;
                        bytes[i * 4 + 1] = (byte) (bits >> 8);
                        bytes[i * 4 + 2] = (byte) (bits >> 16);
                        bytes[i * 4 + 3] = (byte) (bits >> 24);
                    }
                    out.write(bytes);
                });
            }
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void writeHeader(OutputStream out, int dataSize) throws IOException {
        writeAscii(out, "RIFF");
        writeInt(out, 36 + dataSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeInt(out, 16);
        writeShort(out, 3); // IEEE float
        writeShort(out, 2);
        writeInt(out, OfflineMix.SAMPLE_RATE);
        writeInt(out, OfflineMix.SAMPLE_RATE * 8);
        writeShort(out, 8);
        writeShort(out, 32);
        writeAscii(out, "data");
        writeInt(out, dataSize);
    }

    private static void writeAscii(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write(value);
        out.write(value >> 8);
        out.write(value >> 16);
        out.write(value >> 24);
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value);
        out.write(value >> 8);
    }
}

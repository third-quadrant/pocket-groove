package io.pocketgroove;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class ProjectIO {
    private static final int VERSION = 7;

    public static void save(Path destination, Beat.Project project) throws IOException {
        Path target = destination.toAbsolutePath();
        Path temp = Files.createTempFile(target.getParent(), ".pocket-groove-", ".tmp");
        try {
            var props = new Properties();
            props.setProperty("version", Integer.toString(VERSION));
            props.setProperty("tracks", Integer.toString(project.tracks().size()));
            props.setProperty("bpm", Double.toString(project.bpm()));
            props.setProperty("pitch", Double.toString(project.pitch()));
            props.setProperty("length", Double.toString(project.length()));
            props.setProperty("bars", Integer.toString(project.bars()));
            props.setProperty("hits", Integer.toString(project.hits().size()));
            try (var zip = new ZipOutputStream(Files.newOutputStream(temp))) {
                for (int i = 0; i < project.tracks().size(); i++) {
                    var track = project.tracks().get(i);
                    props.setProperty("track." + i + ".name", track.name());
                    props.setProperty("track." + i + ".key", track.key());
                    props.setProperty("track." + i + ".format", track.format());
                    props.setProperty("track." + i + ".pitch", Double.toString(track.pitch()));
                    props.setProperty("track." + i + ".mode", track.pitchMode().name());
                    props.setProperty("track." + i + ".instrument", track.instrument().name());
                    props.setProperty("track." + i + ".base", Integer.toString(track.baseMidiNote()));
                    props.setProperty("track." + i + ".formant", Boolean.toString(track.formantPreserve()));
                    zip.putNextEntry(new ZipEntry("sounds/" + i));
                    zip.write(track.audio());
                    zip.closeEntry();
                }
                for (int i = 0; i < project.hits().size(); i++) {
                    var hit = project.hits().get(i);
                    props.setProperty("hit." + i, hit.track() + "," + hit.beat() + "," + hit.velocity()
                            + "," + hit.pitch() + "," + hit.length());
                }
                zip.putNextEntry(new ZipEntry("project.properties"));
                props.store(zip, "Pocket Groove project v7");
                zip.closeEntry();
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static Beat.Project load(Path path) throws IOException {
        try (var zip = new ZipFile(path.toFile())) {
            var props = new Properties();
            props.load(new ByteArrayInputStream(read(zip, "project.properties", 8 * 1024 * 1024)));
            int version = integer(props, "version", "지원하지 않는 프로젝트 버전입니다.", 1, VERSION);
            int count = version == 1 ? 6 : integer(props, "tracks", "트랙 수가 올바르지 않습니다.", 1, Beat.MAX_TRACKS);
            String prefix = version == 1 ? "pad." : "track.";
            var tracks = new ArrayList<Beat.Track>();
            for (int i = 0; i < count; i++) {
                double trackPitch = version >= 3
                        ? finite(props, prefix + i + ".pitch", "트랙 피치가 올바르지 않습니다.", -12, 12)
                        : 0;
                Beat.PitchMode mode = Beat.PitchMode.VARISPEED;
                if (version >= 6) {
                    try {
                        mode = Beat.PitchMode.valueOf(props.getProperty(prefix + i + ".mode"));
                    } catch (RuntimeException e) {
                        throw new IOException("트랙 피치 방식이 올바르지 않습니다.", e);
                    }
                }
                Beat.Instrument instrument = Beat.Instrument.DRUM;
                if (version >= 7 && props.getProperty(prefix + i + ".instrument") != null) {
                    try {
                        instrument = Beat.Instrument.valueOf(props.getProperty(prefix + i + ".instrument"));
                    } catch (RuntimeException e) {
                        throw new IOException("트랙 악기 종류가 올바르지 않습니다.", e);
                    }
                }
                String name = props.getProperty(prefix + i + ".name");
                String key = props.getProperty(prefix + i + ".key");
                int base = version >= 7 && props.getProperty(prefix + i + ".base") != null
                        ? integer(props, prefix + i + ".base", "기준 MIDI 음이 올바르지 않습니다.", 0, 127)
                        : instrument == Beat.Instrument.MELODY ? 60 : MidiExporter.baseNote(name, i);
                boolean formant = version >= 7
                        && bool(props, prefix + i + ".formant", "Formant 설정이 올바르지 않습니다.");
                tracks.add(new Beat.Track(name, key,
                        props.getProperty(prefix + i + ".format"),
                        read(zip, "sounds/" + i, Beat.MAX_SAMPLE_BYTES),
                        trackPitch, mode, instrument, base, formant));
            }
            int hitCount = integer(props, "hits", "타격 수가 올바르지 않습니다.", 0, 100_000);
            var hits = new ArrayList<Beat.Hit>();
            for (int i = 0; i < hitCount; i++) {
                String[] parts = props.getProperty("hit." + i, "").split(",");
                if (parts.length < 2 || parts.length > 5) throw new IOException("손상된 연주 기록입니다.");
                double velocity = parts.length >= 3 ? Double.parseDouble(parts[2]) : 1.0;
                double pitch = parts.length >= 4 ? Double.parseDouble(parts[3]) : 0;
                double length = parts.length >= 5 ? Double.parseDouble(parts[4]) : Beat.DEFAULT_NOTE_LENGTH;
                hits.add(new Beat.Hit(Integer.parseInt(parts[0]), Double.parseDouble(parts[1]),
                        velocity, pitch, length));
            }
            double length = finite(props, "length", "루프 길이가 올바르지 않습니다.", 0.01, 512);
            int bars = props.getProperty("bars") == null
                    ? Math.max(16, (int) Math.ceil(length / 4.0))
                    : integer(props, "bars", "전체 마디 수가 올바르지 않습니다.", 1, 512);
            return new Beat.Project(tracks, hits, length,
                    finite(props, "bpm", "템포가 올바르지 않습니다.", 40, 240),
                    finite(props, "pitch", "피치가 올바르지 않습니다.", -12, 12), bars);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("프로젝트 내용이 손상되었거나 지원하지 않는 값이 있습니다.", e);
        }
    }

    private static int integer(Properties props, String key, String error, int min, int max)
            throws IOException {
        try {
            int value = Integer.parseInt(props.getProperty(key, ""));
            if (value < min || value > max) throw new IOException(error);
            return value;
        } catch (NumberFormatException e) {
            throw new IOException(error, e);
        }
    }

    private static double finite(Properties props, String key, String error, double min, double max)
            throws IOException {
        try {
            double value = Double.parseDouble(props.getProperty(key, ""));
            if (!Double.isFinite(value) || value < min || value > max) throw new IOException(error);
            return value;
        } catch (NumberFormatException e) {
            throw new IOException(error, e);
        }
    }

    private static boolean bool(Properties props, String key, String error) throws IOException {
        String value = props.getProperty(key);
        if (value == null) return false;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IOException(error);
    }

    private static byte[] read(ZipFile zip, String name, int limit) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > limit)
            throw new IOException("프로젝트 파일이 누락되었거나 크기 제한을 초과했습니다.");
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("압축 해제 크기 제한을 초과했습니다.");
            return bytes;
        }
    }
}

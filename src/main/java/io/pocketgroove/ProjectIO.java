package io.pocketgroove;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class ProjectIO {
    public static void save(Path destination, Beat.Project project) throws IOException {
        Path target = destination.toAbsolutePath();
        Path temp = Files.createTempFile(target.getParent(), ".pocket-groove-", ".tmp");
        try {
            var props = new Properties();
            props.setProperty("version", "1");
            props.setProperty("bpm", Double.toString(project.bpm()));
            props.setProperty("pitch", Double.toString(project.pitch()));
            props.setProperty("length", Double.toString(project.length()));
            props.setProperty("hits", Integer.toString(project.hits().size()));
            try (var zip = new ZipOutputStream(Files.newOutputStream(temp))) {
                for (int i = 0; i < 6; i++) {
                    var pad = project.pads().get(i);
                    props.setProperty("pad." + i + ".name", pad.name());
                    props.setProperty("pad." + i + ".key", pad.key());
                    props.setProperty("pad." + i + ".format", pad.format());
                    zip.putNextEntry(new ZipEntry("sounds/" + i)); zip.write(pad.audio()); zip.closeEntry();
                }
                for (int i = 0; i < project.hits().size(); i++) {
                    var hit = project.hits().get(i);
                    props.setProperty("hit." + i, hit.pad() + "," + hit.beat());
                }
                zip.putNextEntry(new ZipEntry("project.properties")); props.store(zip, "Pocket Groove project v1"); zip.closeEntry();
            }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    public static Beat.Project load(Path path) throws IOException {
        try (var zip = new ZipFile(path.toFile())) {
            var props = new Properties();
            props.load(new ByteArrayInputStream(read(zip, "project.properties", 8 * 1024 * 1024)));
            if (!"1".equals(props.getProperty("version"))) throw new IOException("지원하지 않는 프로젝트 버전입니다.");
            var pads = new ArrayList<Beat.Pad>();
            for (int i=0;i<6;i++) pads.add(new Beat.Pad(props.getProperty("pad."+i+".name"), props.getProperty("pad."+i+".key"),
                    props.getProperty("pad."+i+".format"), read(zip, "sounds/"+i, Beat.MAX_SAMPLE_BYTES)));
            int count = Integer.parseInt(props.getProperty("hits"));
            if (count < 0 || count > 100_000) throw new IOException("연주 기록이 너무 큽니다.");
            var hits = new ArrayList<Beat.Hit>();
            for (int i=0;i<count;i++) {
                String[] parts = props.getProperty("hit."+i).split(",");
                if (parts.length != 2) throw new IOException("손상된 연주 기록입니다.");
                hits.add(new Beat.Hit(Integer.parseInt(parts[0]),Double.parseDouble(parts[1])));
            }
            return new Beat.Project(pads,hits,Double.parseDouble(props.getProperty("length")),
                    Double.parseDouble(props.getProperty("bpm")),Double.parseDouble(props.getProperty("pitch")));
        } catch (IllegalArgumentException | NullPointerException e) { throw new IOException("프로젝트 내용이 손상되었거나 지원하지 않는 값이 있습니다.",e); }
    }
    private static byte[] read(ZipFile zip, String name, int limit) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > limit) throw new IOException("프로젝트 파일이 누락되었거나 크기 제한을 초과했습니다.");
        try (var input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("압축 해제 크기 제한을 초과했습니다.");
            return bytes;
        }
    }
}

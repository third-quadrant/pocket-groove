package io.pocketgroove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectIOTest {
    @TempDir Path dir;
    static Beat.Project project() {
        var pads = new ArrayList<Beat.Pad>();
        for (int i=0;i<6;i++) pads.add(new Beat.Pad("소리 " + i, "DIGIT" + (i+1), "wav", new byte[]{1,2,3}));
        return new Beat.Project(pads, List.of(new Beat.Hit(2, 1.5)), 4, 120, -3);
    }
    @Test void portableProjectRetainsSamplesKeysAndBeatTiming() throws Exception {
        Path path=dir.resolve("비트.mdrum");
        ProjectIO.save(path, project());
        var loaded=ProjectIO.load(path);
        assertEquals(project().hits(), loaded.hits());
        assertEquals("소리 2", loaded.pads().get(2).name());
        assertEquals("DIGIT3", loaded.pads().get(2).key());
        assertArrayEquals(new byte[]{1,2,3}, loaded.pads().get(2).audio());
        assertEquals(-3, loaded.pitch());
    }
    @Test void malformedArchiveIsRejectedWithoutExtractingPaths() throws Exception {
        Path path=dir.resolve("bad.mdrum");
        try (var z=new ZipOutputStream(Files.newOutputStream(path))) {
            z.putNextEntry(new ZipEntry("../escaped.txt")); z.write(1); z.closeEntry();
        }
        assertThrows(IOException.class, () -> ProjectIO.load(path));
        assertFalse(Files.exists(dir.getParent().resolve("escaped.txt")));
    }
    @Test void standardZipDirectoryEntriesDoNotInvalidateProject() throws Exception {
        Path original=dir.resolve("original.mdrum"), repacked=dir.resolve("repacked.mdrum");
        ProjectIO.save(original,project());
        try(var input=new ZipFile(original.toFile());var output=new ZipOutputStream(Files.newOutputStream(repacked))) {
            output.putNextEntry(new ZipEntry("sounds/"));output.closeEntry();
            for(var entry:Collections.list(input.entries())) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                try(var data=input.getInputStream(entry)){data.transferTo(output);}
                output.closeEntry();
            }
        }
        assertEquals(project().hits(),ProjectIO.load(repacked).hits());
    }
    @Test void outOfRangeHitsAndDuplicateBindingsAreRejected() {
        var p=project();
        assertThrows(IllegalArgumentException.class, () -> new Beat.Project(p.pads(), List.of(new Beat.Hit(0,4)),4,120,0));
        var pads=new ArrayList<>(p.pads()); pads.set(1,pads.get(0));
        assertThrows(IllegalArgumentException.class, () -> new Beat.Project(pads,p.hits(),4,120,0));
    }
    @Test void failedSaveDoesNotReplaceExistingDestination() throws Exception {
        Path path=dir.resolve("folder.mdrum"); Files.createDirectory(path); Files.writeString(path.resolve("keep"),"safe");
        assertThrows(IOException.class, () -> ProjectIO.save(path, project()));
        assertEquals("safe",Files.readString(path.resolve("keep")));
    }
}

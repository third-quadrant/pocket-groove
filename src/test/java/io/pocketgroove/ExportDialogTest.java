package io.pocketgroove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExportDialogTest {
    @TempDir Path dir;

    @Test void parsesWholeSingleAndExplicitBarRanges() {
        assertArrayEquals(new int[]{1, 16}, ExportDialog.parseRange("전체", 16).orElseThrow());
        assertArrayEquals(new int[]{3, 3}, ExportDialog.parseRange("3", 16).orElseThrow());
        assertArrayEquals(new int[]{1, 10}, ExportDialog.parseRange(" 1 - 10 ", 16).orElseThrow());
        assertTrue(ExportDialog.parseRange("0", 16).isEmpty());
        assertTrue(ExportDialog.parseRange("5-2", 16).isEmpty());
        assertTrue(ExportDialog.parseRange("17", 16).isEmpty());
        assertTrue(ExportDialog.parseRange("abc", 16).isEmpty());
    }

    @Test void cleansNamesAndResolvesOnlyWavOrMidiPaths() {
        assertEquals("song", ExportDialog.cleanName("song.wav"));
        assertEquals("song", ExportDialog.cleanName("song.mid"));
        assertEquals("song", ExportDialog.cleanName("song.midi"));
        assertEquals("a_b_c", ExportDialog.cleanName(" a/b:c "));
        assertEquals("PocketGroove", ExportDialog.cleanName("  "));
        assertEquals(dir.resolve("mix.wav"), new ExportDialog.Request(
                "mix", dir, 1, 2, ExportDialog.Format.WAV).path());
        assertEquals(dir.resolve("mix.mid"), new ExportDialog.Request(
                "mix", dir, 1, 2, ExportDialog.Format.MIDI).path());
    }

    @Test void resolvesFoldersWithoutCreatingThem() throws Exception {
        Path missing = dir.resolve("new-folder");
        assertEquals(missing.toAbsolutePath().normalize(),
                ExportDialog.parseFolder(" " + missing + " ", dir).orElseThrow());
        Path file = Files.writeString(dir.resolve("file.txt"), "x");
        assertTrue(ExportDialog.parseFolder(file.toString(), dir).isEmpty());
        assertEquals(dir.toAbsolutePath().normalize(),
                ExportDialog.parseFolder("", dir).orElseThrow());
    }
}

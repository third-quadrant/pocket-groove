package io.pocketgroove;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Mp3ExportDialogTest {
    @Test void rangeSupportsAllSingleBarsAndRanges() {
        assertArrayEquals(new int[]{1, 16}, Mp3ExportDialog.parseRange("전체", 16).orElseThrow());
        assertArrayEquals(new int[]{3, 3}, Mp3ExportDialog.parseRange("3", 16).orElseThrow());
        assertArrayEquals(new int[]{1, 10}, Mp3ExportDialog.parseRange(" 1 - 10 ", 16).orElseThrow());
        assertTrue(Mp3ExportDialog.parseRange("0", 16).isEmpty());
        assertTrue(Mp3ExportDialog.parseRange("5-2", 16).isEmpty());
        assertTrue(Mp3ExportDialog.parseRange("17", 16).isEmpty());
        assertTrue(Mp3ExportDialog.parseRange("abc", 16).isEmpty());
    }

    @Test void fileNamesLoseTheExtensionAndUnsafeCharacters() {
        assertEquals("song", Mp3ExportDialog.cleanName("song.mp3"));
        assertEquals("song", Mp3ExportDialog.cleanName("song.mid"));
        assertEquals("song", Mp3ExportDialog.cleanName("song.midi"));
        assertEquals("a_b_c", Mp3ExportDialog.cleanName(" a/b:c "));
        assertEquals("PocketGroove", Mp3ExportDialog.cleanName("  "));
    }

    @Test void saveFolderCanBeTypedAndIsNormalised(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        var missing = dir.resolve("new folder");
        assertEquals(missing.toAbsolutePath().normalize(),
                Mp3ExportDialog.parseFolder(" " + missing + " ", dir).orElseThrow());
        var file = dir.resolve("not-a-folder.txt");
        java.nio.file.Files.writeString(file, "x");
        assertTrue(Mp3ExportDialog.parseFolder(file.toString(), dir).isEmpty());
        assertEquals(dir.toAbsolutePath().normalize(),
                Mp3ExportDialog.parseFolder("", dir).orElseThrow());
        var request = new Mp3ExportDialog.Request("mix", dir, 1, 2, Mp3ExportDialog.Format.MIDI);
        assertEquals(dir.resolve("mix.mid"), request.path());
        assertEquals(dir.resolve("mix.wav"), new Mp3ExportDialog.Request(
                "mix", dir, 1, 2, Mp3ExportDialog.Format.WAV).path());
    }
}

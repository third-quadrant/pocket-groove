package io.pocketgroove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectIOTest {
    @TempDir Path dir;

    static Beat.Project project() {
        var tracks = new ArrayList<Beat.Track>();
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "Q"};
        for (int i = 0; i < keys.length; i++) {
            tracks.add(new Beat.Track("Track " + i, keys[i], "wav",
                    new byte[]{(byte) (i + 1), 2, 3}, i - 3,
                    i == 2 ? Beat.PitchMode.PITCH_SHIFT : Beat.PitchMode.VARISPEED));
        }
        return new Beat.Project(tracks, List.of(
                new Beat.Hit(2, 1.5, 0.35, 7),
                new Beat.Hit(7, 0.25, 1.0, -12)), 4, 120, -3, 12);
    }

    @Test void portableProjectRetainsDynamicTracksSamplesKeysVelocityAndTiming() throws Exception {
        Path path = dir.resolve("8 tracks.mdrum");
        ProjectIO.save(path, project());
        var loaded = ProjectIO.load(path);
        assertEquals(8, loaded.tracks().size());
        assertEquals(project().hits(), loaded.hits());
        assertEquals("Track 2", loaded.tracks().get(2).name());
        assertEquals("3", loaded.tracks().get(2).key());
        assertEquals("Q", loaded.tracks().get(7).key());
        assertArrayEquals(new byte[]{3, 2, 3}, loaded.tracks().get(2).audio());
        assertEquals(-1, loaded.tracks().get(2).pitch());
        assertEquals(Beat.PitchMode.PITCH_SHIFT, loaded.tracks().get(2).pitchMode());
        assertEquals(Beat.PitchMode.VARISPEED, loaded.tracks().get(3).pitchMode());
        assertEquals(4, loaded.tracks().get(7).pitch());
        assertEquals(7, loaded.hits().get(1).pitch(), 0.00001);
        assertEquals(-12, loaded.hits().get(0).pitch(), 0.00001);
        assertEquals(-3, loaded.pitch());
        assertEquals(12, loaded.bars());
    }

    @Test void versionOneProjectsStillLoadAsSixTracksWithFullVelocityHits() throws Exception {
        Path path = dir.resolve("legacy.mdrum");
        var props = new java.util.Properties();
        props.setProperty("version", "1");
        props.setProperty("bpm", "100");
        props.setProperty("pitch", "0");
        props.setProperty("length", "16");
        props.setProperty("hits", "1");
        props.setProperty("hit.0", "3,2.5");
        for (int i = 0; i < 6; i++) {
            props.setProperty("pad." + i + ".name", "Old " + i);
            props.setProperty("pad." + i + ".key", Integer.toString(i + 1));
            props.setProperty("pad." + i + ".format", "wav");
        }
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (int i = 0; i < 6; i++) {
                zip.putNextEntry(new ZipEntry("sounds/" + i)); zip.write(new byte[]{1, 2}); zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("project.properties")); props.store(zip, "v1"); zip.closeEntry();
        }
        var loaded = ProjectIO.load(path);
        assertEquals(6, loaded.tracks().size());
        assertEquals(0, loaded.tracks().get(0).pitch());
        assertEquals(Beat.PitchMode.VARISPEED, loaded.tracks().get(0).pitchMode());
        assertEquals(16, loaded.bars());
        assertEquals(List.of(new Beat.Hit(3, 2.5, 1.0)), loaded.hits());
    }

    @Test void versionTwoProjectsLoadWithZeroTrackPitch() throws Exception {
        Path path = dir.resolve("v2.mdrum");
        var props = new java.util.Properties();
        props.setProperty("version", "2");
        props.setProperty("tracks", "1");
        props.setProperty("bpm", "100");
        props.setProperty("pitch", "0");
        props.setProperty("length", "4");
        props.setProperty("hits", "1");
        props.setProperty("hit.0", "0,1.0,0.8");
        props.setProperty("track.0.name", "Old Kick");
        props.setProperty("track.0.key", "1");
        props.setProperty("track.0.format", "wav");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("sounds/0")); zip.write(new byte[]{1, 2}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("project.properties")); props.store(zip, "v2"); zip.closeEntry();
        }
        var loaded = ProjectIO.load(path);
        assertEquals(0, loaded.tracks().get(0).pitch());
        assertEquals(Beat.Instrument.DRUM, loaded.tracks().get(0).instrument());
        assertEquals(36, loaded.tracks().get(0).baseMidiNote());
        assertFalse(loaded.tracks().get(0).formantPreserve());
        assertTrue(loaded.tracks().get(0).repeat());
        assertEquals(Beat.DEFAULT_NOTE_LENGTH, loaded.hits().get(0).length(), 0.00001);
        assertEquals(16, loaded.bars());
        assertEquals(0.8, loaded.hits().get(0).velocity(), 0.00001);
    }

    @Test void versionEightStoresRepeatMelodyBaseFormantAndChordLengths() throws Exception {
        Path path = dir.resolve("melody.mdrum");
        var track = new Beat.Track("Cello", "1", "wav", new byte[]{1, 2, 3}, 2,
                Beat.PitchMode.PITCH_SHIFT, Beat.Instrument.MELODY, 60, true);
        var chord = List.of(
                new Beat.Hit(0, 0.25, 0.8, 0, 0.5),
                new Beat.Hit(0, 0.25, 0.7, 4, 0.5),
                new Beat.Hit(0, 0.25, 0.6, 7, 0.75),
                new Beat.Hit(0, 5.5, 0.9, 12, 1.0));
        ProjectIO.save(path, new Beat.Project(List.of(track), chord, 4, 120, 0, 16));
        var loaded = ProjectIO.load(path);
        var restored = loaded.tracks().get(0);
        assertEquals(Beat.Instrument.MELODY, restored.instrument());
        assertEquals(60, restored.baseMidiNote());
        assertTrue(restored.formantPreserve());
        assertFalse(restored.repeat());
        assertEquals(Beat.PitchMode.PITCH_SHIFT, restored.pitchMode());
        assertEquals(4, loaded.hits().size());
        assertEquals(List.of(0.0, 4.0, 7.0, 12.0),
                loaded.hits().stream().map(Beat.Hit::pitch).toList());
        assertEquals(List.of(0.5, 0.5, 0.75, 1.0),
                loaded.hits().stream().map(Beat.Hit::length).toList());
        assertEquals(5.5, loaded.hits().get(3).beat(), 0.00001);
    }

    @Test void damagedChordLengthIsRejectedWithoutLoading() throws Exception {
        Path path = dir.resolve("bad-length.mdrum");
        var props = new java.util.Properties();
        props.setProperty("version", "7");
        props.setProperty("tracks", "1");
        props.setProperty("bpm", "100");
        props.setProperty("pitch", "0");
        props.setProperty("length", "4");
        props.setProperty("hits", "1");
        props.setProperty("hit.0", "0,0,1,0,0");
        props.setProperty("track.0.name", "Kick");
        props.setProperty("track.0.key", "1");
        props.setProperty("track.0.format", "wav");
        props.setProperty("track.0.instrument", "DRUM");
        props.setProperty("track.0.base", "36");
        props.setProperty("track.0.formant", "false");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("sounds/0")); zip.write(new byte[]{1, 2}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("project.properties")); props.store(zip, "v7"); zip.closeEntry();
        }
        assertThrows(IOException.class, () -> ProjectIO.load(path));
    }

    @Test void versionSixProjectsGetDrumDefaultsAndKeepTheirPitchMode() throws Exception {
        Path path = dir.resolve("v6.mdrum");
        var props = new java.util.Properties();
        props.setProperty("version", "6");
        props.setProperty("tracks", "1");
        props.setProperty("bpm", "110");
        props.setProperty("pitch", "2");
        props.setProperty("length", "4");
        props.setProperty("bars", "16");
        props.setProperty("hits", "1");
        props.setProperty("hit.0", "0,1.5,0.7,5");
        props.setProperty("track.0.name", "Old Snare");
        props.setProperty("track.0.key", "2");
        props.setProperty("track.0.format", "wav");
        props.setProperty("track.0.pitch", "3");
        props.setProperty("track.0.mode", "PITCH_SHIFT");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("sounds/0")); zip.write(new byte[]{1, 2}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("project.properties")); props.store(zip, "v6"); zip.closeEntry();
        }
        var loaded = ProjectIO.load(path);
        var track = loaded.tracks().get(0);
        assertEquals(Beat.Instrument.DRUM, track.instrument());
        assertEquals(38, track.baseMidiNote());
        assertFalse(track.formantPreserve());
        assertTrue(track.repeat());
        assertEquals(Beat.PitchMode.PITCH_SHIFT, track.pitchMode());
        assertEquals(3, track.pitch(), 0.00001);
        assertEquals(5, loaded.hits().get(0).pitch(), 0.00001);
        assertEquals(Beat.DEFAULT_NOTE_LENGTH, loaded.hits().get(0).length(), 0.00001);
    }

    @Test void versionThreeFourAndFiveKeepTheirSchemaMeaning() throws Exception {
        Path v3 = dir.resolve("v3.mdrum");
        writeLegacy(v3, 3, "0,1.0,0.8", null);
        var v3Loaded = ProjectIO.load(v3);
        assertEquals(-4, v3Loaded.tracks().get(0).pitch(), 0.00001);
        assertEquals(Beat.PitchMode.VARISPEED, v3Loaded.tracks().get(0).pitchMode());
        assertEquals(0, v3Loaded.hits().get(0).pitch(), 0.00001);

        Path v4 = dir.resolve("v4.mdrum");
        writeLegacy(v4, 4, "0,1.0,0.8,7", null);
        var v4Loaded = ProjectIO.load(v4);
        assertEquals(7, v4Loaded.hits().get(0).pitch(), 0.00001);
        assertEquals(16, v4Loaded.bars());

        Path v5 = dir.resolve("v5.mdrum");
        writeLegacy(v5, 5, "0,1.0,0.8,7", "20");
        var v5Loaded = ProjectIO.load(v5);
        assertEquals(20, v5Loaded.bars());
        assertEquals(Beat.PitchMode.VARISPEED, v5Loaded.tracks().get(0).pitchMode());
    }

    private void writeLegacy(Path path, int version, String hit, String bars) throws Exception {
        var props = new java.util.Properties();
        props.setProperty("version", Integer.toString(version));
        props.setProperty("tracks", "1");
        props.setProperty("bpm", "100");
        props.setProperty("pitch", "0");
        props.setProperty("length", "4");
        props.setProperty("hits", "1");
        props.setProperty("hit.0", hit);
        props.setProperty("track.0.name", "Old Kick");
        props.setProperty("track.0.key", "1");
        props.setProperty("track.0.format", "wav");
        if (version >= 3) props.setProperty("track.0.pitch", "-4");
        if (bars != null) props.setProperty("bars", bars);
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("sounds/0")); zip.write(new byte[]{1, 2}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("project.properties")); props.store(zip, "v" + version); zip.closeEntry();
        }
    }

    @Test void malformedArchiveIsRejectedWithoutExtractingPaths() throws Exception {
        Path path = dir.resolve("bad.mdrum");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("../escaped.txt")); zip.write(1); zip.closeEntry();
        }
        assertThrows(IOException.class, () -> ProjectIO.load(path));
        assertFalse(Files.exists(dir.getParent().resolve("escaped.txt")));
    }

    @Test void standardZipDirectoryEntriesDoNotInvalidateProject() throws Exception {
        Path original = dir.resolve("original.mdrum"), repacked = dir.resolve("repacked.mdrum");
        ProjectIO.save(original, project());
        try (var input = new ZipFile(original.toFile());
             var output = new ZipOutputStream(Files.newOutputStream(repacked))) {
            output.putNextEntry(new ZipEntry("sounds/")); output.closeEntry();
            for (var entry : Collections.list(input.entries())) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                try (var data = input.getInputStream(entry)) { data.transferTo(output); }
                output.closeEntry();
            }
        }
        assertEquals(project().hits(), ProjectIO.load(repacked).hits());
    }

    @Test void outOfRangeHitsAndDuplicateBindingsAreRejected() {
        var p = project();
        assertThrows(IllegalArgumentException.class,
                () -> new Beat.Project(p.tracks(), List.of(new Beat.Hit(8, 1)), 4, 120, 0));
        var tracks = new ArrayList<>(p.tracks());
        tracks.set(1, new Beat.Track("duplicate", "1", "wav", new byte[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> new Beat.Project(tracks, p.hits(), 4, 120, 0));
    }

    @Test void failedSaveDoesNotReplaceExistingDestination() throws Exception {
        Path path = dir.resolve("folder.mdrum");
        Files.createDirectory(path);
        Files.writeString(path.resolve("keep"), "safe");
        assertThrows(IOException.class, () -> ProjectIO.save(path, project()));
        assertEquals("safe", Files.readString(path.resolve("keep")));
    }
}

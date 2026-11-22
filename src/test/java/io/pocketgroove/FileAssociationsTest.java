package io.pocketgroove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileAssociationsTest {
    @TempDir Path dir;

    @Test void registryCommandsAssociateTheExeWithMdrumFiles() {
        var exe = Path.of("C:\\Program Files\\Pocket Groove\\PocketGroove.exe");
        var commands = FileAssociations.registryCommands(exe);
        assertTrue(commands.stream().flatMap(List::stream).anyMatch(value ->
                value.equals("HKCU\\Software\\Classes\\.mdrum")));
        assertTrue(commands.stream().flatMap(List::stream).anyMatch(value ->
                value.equals(FileAssociations.PROG_ID)));
        assertTrue(commands.stream().flatMap(List::stream).anyMatch(value ->
                value.contains("\"%1\"") && value.contains("PocketGroove.exe")));
    }

    @Test void startupArgumentOpensOnlyExistingMdrumFiles() throws Exception {
        Path project = dir.resolve("song.mdrum");
        Files.write(project, new byte[]{1});
        Path plain = dir.resolve("photo.png");
        Files.write(plain, new byte[]{1});
        assertEquals(project.toAbsolutePath(), FileAssociations.startupFile(List.of(
                "--flag", plain.toString(), project.toString())));
        assertNull(FileAssociations.startupFile(List.of()));
        assertNull(FileAssociations.startupFile(List.of(dir.resolve("missing.mdrum").toString())));
    }
}

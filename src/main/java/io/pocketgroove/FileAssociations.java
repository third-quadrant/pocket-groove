package io.pocketgroove;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/** Registers this packaged EXE as the per-user opener for .mdrum files. */
final class FileAssociations {
    static final String PROG_ID = "PocketGroove.mdrum";
    private static final String EXTENSION = ".mdrum";

    private FileAssociations() {}

    static void registerIfNeededAsync() {
        Path exe = packagedExe();
        if (exe == null) return;
        try {
            var prefs = Preferences.userNodeForPackage(FileAssociations.class);
            if (exe.toString().equals(prefs.get("registeredExe", ""))) return;
            var thread = new Thread(() -> {
                if (!register(exe)) return;
                try {
                    prefs.put("registeredExe", exe.toString());
                } catch (RuntimeException ignored) {
                    // Registration already succeeded; the guard is only an optimization.
                }
            }, "file-association");
            thread.setDaemon(true);
            thread.start();
        } catch (RuntimeException ignored) {
            // Preferences can be unavailable under locked-down Windows policies.
        }
    }

    static boolean register(Path exe) {
        for (var command : registryCommands(exe)) {
            try {
                var process = new ProcessBuilder(command).redirectErrorStream(true).start();
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return false;
            } catch (IOException | InterruptedException e) {
                return false;
            }
        }
        return true;
    }

    static List<List<String>> registryCommands(Path exe) {
        String quotedExe = "\"" + exe.toAbsolutePath() + "\"";
        String open = quotedExe + " \"%1\"";
        String classes = "HKCU\\Software\\Classes\\";
        return List.of(
                List.of("reg", "add", classes + PROG_ID, "/ve", "/d", "Pocket Groove Project", "/f"),
                List.of("reg", "add", classes + PROG_ID + "\\DefaultIcon", "/ve", "/d",
                        quotedExe + ",0", "/f"),
                List.of("reg", "add", classes + PROG_ID + "\\shell\\open\\command", "/ve", "/d",
                        open, "/f"),
                List.of("reg", "add", classes + EXTENSION, "/ve", "/d", PROG_ID, "/f"));
    }

    static Path startupFile(List<String> arguments) {
        if (arguments == null) return null;
        for (String value : arguments) {
            if (value == null || !value.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) continue;
            try {
                var path = Path.of(value).toAbsolutePath();
                if (Files.isRegularFile(path)) return path;
            } catch (RuntimeException ignored) {
                // A malformed shell argument should not stop the app from starting.
            }
        }
        return null;
    }

    static Path packagedExe() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        String appPath = System.getProperty("jpackage.app-path", "");
        if (appPath.toLowerCase(Locale.ROOT).endsWith(".exe")) return Path.of(appPath);
        String command = ProcessHandle.current().info().command().orElse("");
        if (command.toLowerCase(Locale.ROOT).endsWith("pocketgroove.exe")) return Path.of(command);
        return null;
    }

}

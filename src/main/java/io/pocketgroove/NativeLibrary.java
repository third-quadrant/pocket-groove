package io.pocketgroove;

import java.nio.file.Files;
import java.nio.file.Path;

/** Loads the native audio library once for the engine and the pitch renderer. */
final class NativeLibrary {
    private static final boolean loaded = initialize();

    private NativeLibrary() {}

    static void load() {}

    private static boolean initialize() {
        Path path = Path.of(System.getProperty("groove.native.path", defaultNativePath()));
        if (Files.isRegularFile(path)) System.load(path.toAbsolutePath().toString());
        else System.loadLibrary("pocketgroove_audio");
        return true;
    }

    private static String defaultNativePath() {
        try {
            Path location = Path.of(NativeLibrary.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return location.getParent().resolve("pocketgroove_audio.dll").toString();
        } catch (Exception e) {
            return "target/native/pocketgroove_audio.dll";
        }
    }
}

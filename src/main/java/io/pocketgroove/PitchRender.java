package io.pocketgroove;

/** Offline Signalsmith pitch rendering. Only the background cache may call this. */
final class PitchRender {
    static {
        NativeLibrary.load();
    }

    private PitchRender() {}

    static float[] render(float[] stereo, int sampleRate, double semitones, boolean preserveFormants) {
        return nRenderPitch(stereo, sampleRate, semitones, preserveFormants);
    }

    private static native float[] nRenderPitch(float[] stereo, int sampleRate,
                                               double semitones, boolean preserveFormants);
}

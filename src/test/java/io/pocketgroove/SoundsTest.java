package io.pocketgroove;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SoundsTest {
    @Test void wavAndAiffDecodeToStereoPcmBeforePlayback() throws Exception {
        byte[] raw = {0, 64, 0, 32, 0, 0, 0, (byte) 192};
        for (var type : List.of(AudioFileFormat.Type.WAVE, AudioFileFormat.Type.AIFF)) {
            var bytes = new ByteArrayOutputStream();
            AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(raw),
                    new AudioFormat(44100, 16, 1, true, false), 4), type, bytes);
            var pcm = Sounds.decode(new Beat.Track("test", "A",
                    type == AudioFileFormat.Type.WAVE ? "wav" : "aiff", bytes.toByteArray()));
            assertEquals(44100, pcm.sampleRate());
            assertEquals(8, pcm.stereo().length);
            assertEquals(.5, pcm.stereo()[0], .00001);
            assertEquals(pcm.stereo()[0], pcm.stereo()[1]);
            assertEquals(-.5, pcm.stereo()[6], .00001);
        }
    }

    @Test void allFactoryTracksDecodeAndCorruptAudioIsRejected() throws Exception {
        var defaults = Sounds.defaults();
        assertEquals(7, defaults.size());
        for (var track : defaults) assertTrue(Sounds.decode(track).stereo().length > 1000);
        assertThrows(java.io.IOException.class,
                () -> Sounds.decode(new Beat.Track("bad", "A", "wav", new byte[]{1, 2, 3})));
    }

    @Test void mp3DecodesThroughBundledJavaSoundProvider() throws Exception {
        try (var input = getClass().getResourceAsStream("/audio/cc0-toast.mp3")) {
            var track = new Beat.Track("toast", "A", "mp3", input.readAllBytes());
            var pcm = Sounds.decode(track);
            assertTrue(pcm.sampleRate() >= 8000);
            assertTrue(pcm.stereo().length > 10_000);
        }
    }

    @Test void waveformTrimCropsBothEndsAndWritesPlayableWav() throws Exception {
        var pcm = new Sounds.Pcm(new float[]{0, 0, .5f, .5f, .4f, .4f, 0, 0}, 44100);
        assertArrayEquals(new int[]{1, 3}, Sounds.silenceBounds(pcm));
        var cropped = Sounds.crop(pcm, 1, 3);
        assertEquals(4, cropped.stereo().length);
        assertEquals(.5f, cropped.stereo()[0], .00001);
        var decoded = Sounds.decode(new Beat.Track("trim", "A", "wav", Sounds.wavBytes(cropped)));
        assertEquals(2, decoded.stereo().length / 2);
        assertEquals(.4f, decoded.stereo()[2], .001);
        assertThrows(IllegalArgumentException.class, () -> Sounds.crop(pcm, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> Sounds.crop(pcm, 0, 5));
    }

    @Test void allSilentSoundKeepsItsFullRangeInsteadOfVanishing() {
        var pcm = new Sounds.Pcm(new float[32], 48000);
        assertArrayEquals(new int[]{0, 16}, Sounds.silenceBounds(pcm));
    }

    @Test void newTracksUseTheFirstFreePrintableKey() {
        var tracks = List.of(
                new Beat.Track("Kick", "1", "wav", new byte[]{1}),
                new Beat.Track("Snare", "2", "wav", new byte[]{1}));
        assertEquals("3", Sounds.nextFreeKey(tracks));
        assertEquals("8", Sounds.nextFreeKey(List.of(
                new Beat.Track("Kick", "1", "wav", new byte[]{1}),
                new Beat.Track("Snare", "2", "wav", new byte[]{1}),
                new Beat.Track("Hat", "3", "wav", new byte[]{1}),
                new Beat.Track("Open", "4", "wav", new byte[]{1}),
                new Beat.Track("Clap", "5", "wav", new byte[]{1}),
                new Beat.Track("Tom", "6", "wav", new byte[]{1}),
                new Beat.Track("Perc", "7", "wav", new byte[]{1}))));
        assertEquals("Q", Sounds.nextFreeKey(List.of(
                new Beat.Track("0", "0", "wav", new byte[]{1}),
                new Beat.Track("1", "1", "wav", new byte[]{1}),
                new Beat.Track("2", "2", "wav", new byte[]{1}),
                new Beat.Track("3", "3", "wav", new byte[]{1}),
                new Beat.Track("4", "4", "wav", new byte[]{1}),
                new Beat.Track("5", "5", "wav", new byte[]{1}),
                new Beat.Track("6", "6", "wav", new byte[]{1}),
                new Beat.Track("7", "7", "wav", new byte[]{1}),
                new Beat.Track("8", "8", "wav", new byte[]{1}),
                new Beat.Track("9", "9", "wav", new byte[]{1}))));
    }
}

package io.pocketgroove;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeyBindingsTest {
    private static KeyEvent pressed(KeyCode code, boolean ctrl, boolean alt, boolean shift) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, false);
    }
    private static KeyEvent typed(String text) {
        return new KeyEvent(KeyEvent.KEY_TYPED, text, "", KeyCode.UNDEFINED, false, false, false, false);
    }

    @Test void topRowAndNumpadDigitsAreInterchangeable() {
        assertTrue(KeyBindings.matches("1", pressed(KeyCode.DIGIT1, false, false, false)));
        assertTrue(KeyBindings.matches("1", pressed(KeyCode.NUMPAD1, false, false, false)));
        assertTrue(KeyBindings.matches("NUMPAD1", pressed(KeyCode.DIGIT1, false, false, false)));
        assertTrue(KeyBindings.same("1", "Numpad1"));
    }

    @Test void macroInjectedTextMatchesWithoutAPhysicalKeyCode() {
        assertTrue(KeyBindings.matches("1", typed("1")));
        assertTrue(KeyBindings.matches("A", typed("a")));
        assertEquals("1", KeyBindings.capture(typed("1")));
        assertEquals("1", KeyBindings.capture(pressed(KeyCode.DIGIT1, false, false, false)));
        assertEquals("1", KeyBindings.capture(pressed(KeyCode.NUMPAD1, false, false, false)));
    }

    @Test void modifiedCombinationsAreDistinctAndMatchOnlyWithTheSameModifier() {
        var binding = KeyBindings.capture(pressed(KeyCode.DIGIT1, true, false, false));
        assertEquals("Ctrl+1", binding);
        assertTrue(KeyBindings.matches(binding, pressed(KeyCode.DIGIT1, true, false, false)));
        assertTrue(KeyBindings.matches(binding, pressed(KeyCode.NUMPAD1, true, false, false)));
        assertFalse(KeyBindings.matches(binding, pressed(KeyCode.DIGIT1, false, false, false)));
        assertEquals("Space", KeyBindings.capture(pressed(KeyCode.SPACE, false, false, false)));
        assertEquals("F13", KeyBindings.capture(pressed(KeyCode.F13, false, false, false)));
    }

    @Test void eachEventFindsAtMostOneTrackAndUnknownKeysFindNone() {
        var tracks = List.of(
                new Beat.Track("Kick", "1", "wav", new byte[]{1}),
                new Beat.Track("Snare", "A", "wav", new byte[]{1}));
        assertEquals(0, KeyBindings.indexFor(tracks, pressed(KeyCode.NUMPAD1, false, false, false)));
        assertEquals(1, KeyBindings.indexFor(tracks, typed("a")));
        assertEquals(-1, KeyBindings.indexFor(tracks, pressed(KeyCode.DIGIT9, false, false, false)));
    }
}

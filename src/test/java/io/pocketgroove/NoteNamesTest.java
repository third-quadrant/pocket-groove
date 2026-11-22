package io.pocketgroove;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoteNamesTest {
    @Test void formatsAndParsesScientificPitchNames() {
        assertEquals("C4", NoteNames.name(60));
        assertEquals("D4", NoteNames.name(62));
        assertEquals("E4", NoteNames.name(64));
        assertEquals("G4", NoteNames.name(67));
        assertEquals(60, NoteNames.parse("C4"));
        assertEquals(61, NoteNames.parse("C#4"));
        assertEquals(61, NoteNames.parse("Db4"));
        assertEquals(70, NoteNames.parse("Bb4"));
        assertEquals(0, NoteNames.parse("C-1"));
        assertEquals(127, NoteNames.parse("G9"));
    }

    @Test void rejectsMalformedAndOutOfRangeNames() {
        assertThrows(IllegalArgumentException.class, () -> NoteNames.parse("60"));
        assertThrows(IllegalArgumentException.class, () -> NoteNames.parse("H4"));
        assertThrows(IllegalArgumentException.class, () -> NoteNames.parse("C10"));
        assertThrows(IllegalArgumentException.class, () -> NoteNames.parse(""));
        assertThrows(IllegalArgumentException.class, () -> NoteNames.name(128));
    }
}

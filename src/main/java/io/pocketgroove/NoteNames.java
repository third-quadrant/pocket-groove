package io.pocketgroove;

import java.util.Locale;
import java.util.regex.Pattern;

/** Scientific pitch notation with MIDI 60 = C4. */
final class NoteNames {
    private static final String[] NAMES = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final Pattern PATTERN = Pattern.compile("^([A-Ga-g])([#b]?)(-?\\d+)$");

    private NoteNames() {}

    static String name(int midi) {
        if (midi < 0 || midi > 127) throw new IllegalArgumentException("MIDI 음은 0–127이어야 합니다.");
        return NAMES[midi % 12] + (midi / 12 - 1);
    }

    static int parse(String text) {
        String value = text == null ? "" : text.trim();
        var matcher = PATTERN.matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("음이름을 확인하세요. 예: C4, F#3, Bb5");
        int pitchClass = switch (matcher.group(1).toUpperCase(Locale.ROOT)) {
            case "C" -> 0;
            case "D" -> 2;
            case "E" -> 4;
            case "F" -> 5;
            case "G" -> 7;
            case "A" -> 9;
            case "B" -> 11;
            default -> throw new IllegalArgumentException("음이름을 확인하세요.");
        };
        if (matcher.group(2).equals("#")) pitchClass++;
        else if (matcher.group(2).equals("b")) pitchClass--;
        int octave;
        try {
            octave = Integer.parseInt(matcher.group(3));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("옥타브를 확인하세요.", e);
        }
        int midi = (octave + 1) * 12 + pitchClass;
        if (midi < 0 || midi > 127) throw new IllegalArgumentException("MIDI 0–127 범위를 벗어났습니다.");
        return midi;
    }
}

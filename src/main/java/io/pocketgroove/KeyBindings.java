package io.pocketgroove;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import java.util.List;
import java.util.Locale;

/** Key-learning and matching shared by the scene filters and tests. */
final class KeyBindings {
    private KeyBindings() {}

    static int indexFor(List<Beat.Track> tracks, KeyEvent event) {
        for (int i = 0; i < tracks.size(); i++) {
            if (matches(tracks.get(i).key(), event)) return i;
        }
        return -1;
    }

    static boolean matches(String binding, KeyEvent event) {
        if (binding == null || event == null) return false;
        if (event.getEventType() == KeyEvent.KEY_TYPED) {
            if (event.isControlDown() || event.isAltDown() || event.isMetaDown()) return false;
            String text = event.getCharacter();
            if (text == null || text.isBlank()) return false;
            return simple(binding).equals(canonicalText(text));
        }
        if (event.getCode() == KeyCode.UNDEFINED) {
            String text = event.getText();
            return text != null && !text.isBlank() && simple(binding).equals(canonicalText(text));
        }
        if (event.getCode().isModifierKey()) return false;

        String modifier = modifierPrefix(event);
        if (!modifier.isEmpty()) {
            String candidates = modifier + canonicalCode(event.getCode());
            return canonicalCombo(binding).equals(candidates);
        }
        return simple(binding).equals(canonicalCode(event.getCode()));
    }

    static String capture(KeyEvent event) {
        if (event == null) return null;
        if (event.getEventType() == KeyEvent.KEY_TYPED) {
            String text = event.getCharacter();
            if (text == null || text.isBlank()) return null;
            return canonicalText(text);
        }
        if (event.getCode().isModifierKey()) return null;
        if (event.getCode() == KeyCode.UNDEFINED) {
            String text = event.getText();
            return text == null || text.isBlank() ? null : canonicalText(text);
        }
        String modifier = modifierPrefix(event);
        return modifier + canonicalCode(event.getCode());
    }

    static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        return canonicalCombo(a).equals(canonicalCombo(b));
    }

    static String display(String binding) {
        if (binding == null) return "";
        String value = binding.replace("Numpad", "Num ").replace("NUMPAD", "Num ");
        return value;
    }

    private static String modifierPrefix(KeyEvent event) {
        var prefix = new StringBuilder();
        if (event.isControlDown()) prefix.append("Ctrl+");
        if (event.isAltDown()) prefix.append("Alt+");
        if (event.isShiftDown()) prefix.append("Shift+");
        if (event.isMetaDown()) prefix.append("Meta+");
        return prefix.toString();
    }

    private static String canonicalCombo(String binding) {
        String value = binding.trim();
        String[] parts = value.split("\\+");
        if (parts.length == 1) return simple(value);
        String key = parts[parts.length - 1];
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            result.append(canonicalModifier(parts[i])).append('+');
        }
        return result.append(canonicalCodeName(key)).toString();
    }

    private static String canonicalModifier(String text) {
        String lower = text.trim().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "control", "ctrl" -> "Ctrl";
            case "option", "alt" -> "Alt";
            case "shift" -> "Shift";
            case "meta", "cmd", "command" -> "Meta";
            default -> text.trim();
        };
    }

    private static String canonicalCode(KeyCode code) {
        if (code == null) return "";
        if (code.isDigitKey()) {
            String name = code.name();
            return name.substring(name.length() - 1);
        }
        if (code.isLetterKey()) return code.name();
        return switch (code) {
            case SPACE -> "Space";
            case ENTER -> "Enter";
            case TAB -> "Tab";
            case ESCAPE -> "Escape";
            case BACK_SPACE -> "Backspace";
            case DELETE -> "Delete";
            case LEFT -> "Left";
            case RIGHT -> "Right";
            case UP -> "Up";
            case DOWN -> "Down";
            default -> code.name();
        };
    }

    private static String canonicalCodeName(String text) {
        String value = text.trim();
        for (KeyCode code : KeyCode.values()) {
            if (code.name().equalsIgnoreCase(value) || code.getName().equalsIgnoreCase(value)
                    || canonicalCode(code).equalsIgnoreCase(value)) {
                return canonicalCode(code);
            }
        }
        if (value.length() == 1) return canonicalText(value);
        return value;
    }

    private static String canonicalText(String text) {
        if (text == null) return "";
        String value = text.trim();
        if (value.length() != 1) return value;
        return value.toUpperCase(Locale.ROOT);
    }

    private static String simple(String binding) {
        return canonicalCodeName(binding);
    }
}

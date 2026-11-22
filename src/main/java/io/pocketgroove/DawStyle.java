package io.pocketgroove;

import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/** Visual tokens shared by the Canvas views and the CSS surface. */
final class DawStyle {
    static final Color BG = Color.web("#0F1114");
    static final Color PANEL = Color.web("#15181D");
    static final Color LANE = Color.web("#1A1E24");
    static final Color LANE_ALT = Color.web("#181C21");
    static final Color HEADER = Color.web("#12151A");
    static final Color RAISED = Color.web("#20242B");
    static final Color RAISED_HOVER = Color.web("#272C35");
    static final Color TEXT = Color.web("#E8EAF0");
    static final Color MUTED = Color.web("#8B929D");
    static final Color DIM = Color.web("#5F6774");
    static final Color LINE = Color.web("#262B33");
    static final Color LINE_STRONG = Color.web("#343B46");
    static final Color ACCENT = Color.web("#E0A64E");
    static final Color PLAYHEAD = Color.web("#FFE4A8");
    static final Color RECORD = Color.web("#CC5C4D");
    static final Color OK = Color.web("#86B88D");
    static final Color MISMATCH = Color.web("#D27C6F");

    static final String DISPLAY = "Barlow SemiBold";
    static final String UI = "NanumGothic";

    private static final Color[] TRACK_COLORS = {
            Color.web("#D07A5D"), Color.web("#C7A04F"), Color.web("#62A38F"),
            Color.web("#5F8FB6"), Color.web("#A47CAA"), Color.web("#83A55E"),
            Color.web("#B7A98C"), Color.web("#C9827D"), Color.web("#6F9AA6"),
            Color.web("#B28F63")
    };

    private DawStyle() {}

    static Color track(int index) {
        if (index < TRACK_COLORS.length) return TRACK_COLORS[index];
        Color base = TRACK_COLORS[index % TRACK_COLORS.length];
        double shift = ((index / TRACK_COLORS.length) % 3) * 0.025;
        return Color.hsb((base.getHue() + shift * 360) % 360,
                Math.max(.15, base.getSaturation() - shift * 2),
                Math.min(.88, base.getBrightness() + shift));
    }

    static Color alpha(Color color, double opacity) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                Math.max(0, Math.min(1, opacity)));
    }

    static Font display(double size) { return Font.font(DISPLAY, size); }
    static Font ui(double size) { return Font.font(UI, size); }
}

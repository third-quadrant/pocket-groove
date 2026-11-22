package io.pocketgroove;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/** Track headers and the looping pattern clips share one canvas so they scroll as one workspace. */
final class ArrangementView extends Region {
    static final double HEADER_W = 214;
    static final double RULER_H = 30;
    static final double ROW_H = 52;
    private static final double SCROLL = 14;

    private final Canvas canvas = new Canvas();
    private final ScrollBar vertical = new ScrollBar();
    private final ScrollBar horizontal = new ScrollBar();
    private List<Beat.Track> tracks = List.of();
    private List<Beat.Hit> hits = List.of();
    private List<List<Beat.Hit>> notesByTrack = List.of();
    private Consumer<Integer> onTrackSelected = ignored -> {};
    private IntConsumer onAudition = ignored -> {};
    private DoubleConsumer onEditBeat = ignored -> {};
    private BiConsumer<Integer, Beat.PitchMode> onPitchModeChanged = (track, mode) -> {};
    private double loopBeats = 4;
    private int bars = 16;
    private double zoom = 28;
    private double editBeat;
    private double playhead;
    private double lastPosition;
    private int playheadCycle;
    private int selectedTrack;
    private int hoverTrack = -1;
    private double hoverBeat;
    private boolean playing;
    private boolean updatingBars;
    private boolean panning;
    private double panX, panY, panBeat, panTrack;
    private int flashTrack = -1;

    ArrangementView() {
        getStyleClass().add("arrangement");
        setFocusTraversable(true);
        setMinHeight(210);
        vertical.setOrientation(javafx.geometry.Orientation.VERTICAL);
        horizontal.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        getChildren().addAll(canvas, vertical, horizontal);
        vertical.valueProperty().addListener((o, a, b) -> draw());
        horizontal.valueProperty().addListener((o, a, b) -> draw());
        canvas.setOnMouseMoved(this::mouseMoved);
        canvas.setOnMouseExited(e -> { hoverTrack = -1; draw(); });
        canvas.setOnMousePressed(this::mousePressed);
        canvas.setOnMouseDragged(this::mouseDragged);
        canvas.setOnMouseReleased(e -> {
            panning = false;
            canvas.setCursor(javafx.scene.Cursor.DEFAULT);
        });
        canvas.setOnScroll(e -> {
            if (e.isControlDown()) {
                zoomAround(zoom * (e.getDeltaY() > 0 ? 1.14 : 0.88), e.getX());
            } else if (e.isShiftDown() || Math.abs(e.getDeltaX()) > Math.abs(e.getDeltaY())) {
                horizontal.setValue(clamp(horizontal.getValue() - e.getDeltaX() / 80, 0, horizontal.getMax()));
            } else {
                vertical.setValue(clamp(vertical.getValue() - e.getDeltaY() / 80, 0, vertical.getMax()));
            }
            e.consume();
        });
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN) {
                int step = e.getCode() == KeyCode.UP ? -1 : 1;
                selectTrack(Math.max(0, Math.min(tracks.size() - 1, selectedTrack + step)));
                e.consume();
            } else if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT) {
                double amount = e.isShiftDown() ? 1 : 0.25;
                editBeat = clamp(editBeat + (e.getCode() == KeyCode.LEFT ? -amount : amount), 0, loopBeats - 0.01);
                onEditBeat.accept(editBeat);
                draw();
                e.consume();
            }
        });
        focusedProperty().addListener((o, a, b) -> draw());
        widthProperty().addListener((o, a, b) -> requestLayout());
        heightProperty().addListener((o, a, b) -> requestLayout());
    }

    void setOnTrackSelected(Consumer<Integer> listener) { onTrackSelected = listener == null ? ignored -> {} : listener; }
    void setOnAudition(IntConsumer listener) { onAudition = listener == null ? ignored -> {} : listener; }
    void setOnEditBeat(DoubleConsumer listener) { onEditBeat = listener == null ? ignored -> {} : listener; }
    void setOnPitchModeChanged(BiConsumer<Integer, Beat.PitchMode> listener) {
        onPitchModeChanged = listener == null ? (track, mode) -> {} : listener;
    }

    void flash(int track) {
        flashTrack = track;
        draw();
    }

    void clearFlash() {
        flashTrack = -1;
        draw();
    }

    void setData(List<Beat.Track> tracks, List<Beat.Hit> hits, double loopBeats, int bars,
                 int selectedTrack, double editBeat) {
        this.tracks = List.copyOf(tracks);
        this.hits = List.copyOf(hits);
        var grouped = new ArrayList<List<Beat.Hit>>(this.tracks.size());
        for (int i = 0; i < this.tracks.size(); i++) grouped.add(new ArrayList<>());
        for (var hit : this.hits) {
            if (hit.track() >= 0 && hit.track() < grouped.size()) grouped.get(hit.track()).add(hit);
        }
        var frozen = new ArrayList<List<Beat.Hit>>(grouped.size());
        for (var group : grouped) {
            group.sort(java.util.Comparator.comparingDouble(Beat.Hit::beat));
            frozen.add(List.copyOf(group));
        }
        this.notesByTrack = List.copyOf(frozen);
        this.loopBeats = Math.max(0.25, loopBeats);
        this.bars = Math.max(1, bars);
        this.selectedTrack = Math.max(0, Math.min(this.tracks.size() - 1, selectedTrack));
        this.editBeat = clamp(editBeat, 0, this.loopBeats - 0.001);
        setAccessibleText("트랙 타임라인, " + this.bars + "마디, " + this.tracks.size() + "개 트랙, 선택 "
                + (this.tracks.isEmpty() ? "없음" : this.tracks.get(this.selectedTrack).name()));
        updateBars();
        ensureTrackVisible(this.selectedTrack);
        draw();
    }

    void setSelectedTrack(int index) {
        selectedTrack = Math.max(0, Math.min(tracks.size() - 1, index));
        ensureTrackVisible(selectedTrack);
        draw();
    }

    private void ensureTrackVisible(int index) {
        double rows = visibleRows();
        if (index < vertical.getValue()) vertical.setValue(index);
        else if (index >= vertical.getValue() + rows) vertical.setValue(index - rows + 1);
    }

    void setEditBeat(double beat) {
        editBeat = clamp(beat, 0, loopBeats - 0.001);
        draw();
    }

    void setZoom(double value) {
        zoom = clamp(value, 14, 120);
        updateBars();
        draw();
    }

    double zoom() { return zoom; }

    void zoomIn() { zoomAround(zoom * 1.18, canvas.getWidth() / 2); }
    void zoomOut() { zoomAround(zoom / 1.18, canvas.getWidth() / 2); }

    void updatePlayhead(double position, boolean playing) {
        if (playing && !this.playing) {
            playheadCycle = 0;
            lastPosition = 0;
        }
        if (this.playing && playing && position + 0.01 < lastPosition) {
            playheadCycle = (playheadCycle + 1) % loopCycles();
        }
        playheadCycle = Math.floorMod(playheadCycle, loopCycles());
        lastPosition = position;
        playhead = position;
        this.playing = playing;
        if (playing) follow(playheadCycle * loopBeats + playhead);
        draw();
    }

    int selectedTrack() { return selectedTrack; }
    int trackCount() { return tracks.size(); }
    double horizontalMaxForTest() { return horizontal.getMax(); }
    double horizontalValueForTest() { return horizontal.getValue(); }
    void incrementHorizontalForTest() { horizontal.increment(); }
    void scrollHorizontalForTest(double value) { horizontal.setValue(value); }

    private void follow(double beat) {
        double view = viewBeats();
        double start = horizontal.getValue(), end = start + view;
        if (beat < start + view * .12 || beat > end - view * .12) {
            horizontal.setValue(clamp(beat - view * .22, 0, horizontal.getMax()));
        }
    }

    private void zoomAround(double next, double anchorX) {
        double anchor = offsetBeat() + Math.max(0, anchorX - HEADER_W) / zoom;
        zoom = clamp(next, 14, 120);
        updateBars();
        double wanted = clamp(anchor - Math.max(0, anchorX - HEADER_W) / zoom,
                0, horizontal.getMax());
        horizontal.setValue(wanted);
        draw();
    }

    private void selectTrack(int index) {
        if (tracks.isEmpty()) return;
        selectedTrack = index;
        onTrackSelected.accept(index);
        draw();
    }

    private void showPitchModeMenu(int index, double screenX, double screenY) {
        var track = tracks.get(index);
        var group = new ToggleGroup();
        var varispeed = new RadioMenuItem("Varispeed (속도 변경)");
        var pitchShift = new RadioMenuItem("Pitch Shift (음높이만)");
        varispeed.setToggleGroup(group);
        pitchShift.setToggleGroup(group);
        varispeed.setSelected(track.pitchMode() == Beat.PitchMode.VARISPEED);
        pitchShift.setSelected(track.pitchMode() == Beat.PitchMode.PITCH_SHIFT);
        varispeed.setOnAction(e -> onPitchModeChanged.accept(index, Beat.PitchMode.VARISPEED));
        pitchShift.setOnAction(e -> onPitchModeChanged.accept(index, Beat.PitchMode.PITCH_SHIFT));
        var menu = new ContextMenu(varispeed, pitchShift,
                new SeparatorMenuItem(),
                new MenuItemHeader("길이 유지: 음높이만 변경 / 속도 변경: 피치에 따라 길이도 변경"));
        menu.show(canvas, screenX, screenY);
    }

    private static final class MenuItemHeader extends javafx.scene.control.MenuItem {
        MenuItemHeader(String text) {
            super(text);
            setDisable(true);
            getStyleClass().add("menu-note");
        }
    }

    private void mouseMoved(javafx.scene.input.MouseEvent e) {
        int row = trackAt(e.getY());
        double beat = beatAt(e.getX());
        boolean changed = row != hoverTrack || Math.abs(beat - hoverBeat) > 0.001;
        hoverTrack = row;
        hoverBeat = beat;
        canvas.setCursor(e.getX() < HEADER_W ? javafx.scene.Cursor.HAND
                : javafx.scene.Cursor.CROSSHAIR);
        if (changed) draw();
    }

    private void mousePressed(javafx.scene.input.MouseEvent e) {
        requestFocus();
        int row = trackAt(e.getY());
        if (e.getButton() == MouseButton.SECONDARY && row >= 0 && e.getX() < HEADER_W) {
            selectTrack(row);
            showPitchModeMenu(row, e.getScreenX(), e.getScreenY());
            return;
        }
        if (e.getButton() == MouseButton.MIDDLE || e.getButton() == MouseButton.SECONDARY) {
            panning = true;
            panX = e.getX();
            panY = e.getY();
            panBeat = horizontal.getValue();
            panTrack = vertical.getValue();
            canvas.setCursor(javafx.scene.Cursor.CLOSED_HAND);
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY) return;
        if (row >= 0) {
            selectTrack(row);
            if (e.getX() < HEADER_W) onAudition.accept(row);
            if (e.getX() >= HEADER_W) {
                editBeat = snap(beatAt(e.getX()));
                onEditBeat.accept(editBeat);
            }
        } else if (e.getX() >= HEADER_W) {
            editBeat = snap(beatAt(e.getX()));
            onEditBeat.accept(editBeat);
        }
        draw();
    }

    private void mouseDragged(javafx.scene.input.MouseEvent e) {
        if (panning) {
            horizontal.setValue(clamp(panBeat - (e.getX() - panX) / zoom, 0, horizontal.getMax()));
            vertical.setValue(clamp(panTrack - (e.getY() - panY) / ROW_H, 0, vertical.getMax()));
        }
    }

    private int trackAt(double y) {
        if (y < RULER_H) return -1;
        int row = (int) Math.floor(offsetTrack() + (y - RULER_H) / ROW_H);
        return row >= 0 && row < tracks.size() ? row : -1;
    }

    private double beatAt(double x) {
        return offsetBeat() + Math.max(0, x - HEADER_W) / zoom;
    }

    private static double snap(double beat) {
        return Math.round(beat * Pattern.STEPS_PER_BEAT) / Pattern.STEPS_PER_BEAT;
    }

    private double offsetBeat() { return horizontal.getValue(); }
    private double offsetTrack() { return vertical.getValue(); }
    private double totalBeats() { return bars * 4.0; }
    private int loopCycles() {
        return Math.max(1, (int) Math.ceil(totalBeats() / loopBeats));
    }
    private double viewBeats() {
        return Math.max(4, (Math.max(1, canvas.getWidth()) - HEADER_W) / zoom);
    }
    private double visibleRows() {
        return Math.max(1, (Math.max(1, canvas.getHeight()) - RULER_H) / ROW_H);
    }

    @Override protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        double cw = Math.max(1, w - SCROLL), ch = Math.max(1, h - SCROLL);
        canvas.setWidth(cw);
        canvas.setHeight(ch);
        canvas.relocate(0, 0);
        vertical.resizeRelocate(w - SCROLL, 0, SCROLL, ch);
        horizontal.resizeRelocate(0, h - SCROLL, cw, SCROLL);
        updateBars();
        draw();
    }

    private void updateBars() {
        if (updatingBars) return;
        updatingBars = true;
        try {
            double view = viewBeats();
            horizontal.setMin(0);
            horizontal.setMax(Math.max(0, totalBeats() - view));
            horizontal.setVisibleAmount(view);
            horizontal.setBlockIncrement(view * .7);
            horizontal.setValue(clamp(horizontal.getValue(), 0, horizontal.getMax()));
            double rows = visibleRows();
            vertical.setMin(0);
            vertical.setMax(Math.max(0, tracks.size() - rows));
            vertical.setVisibleAmount(rows);
            vertical.setBlockIncrement(rows);
            vertical.setValue(clamp(vertical.getValue(), 0, vertical.getMax()));
        } finally {
            updatingBars = false;
        }
    }

    private void draw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        var gc = canvas.getGraphicsContext2D();
        gc.setFill(DawStyle.BG);
        gc.fillRect(0, 0, w, h);
        drawRuler(gc, w);
        drawLanes(gc, w, h);
        drawGrid(gc, w, h);
        drawClips(gc);
        drawCursors(gc, w, h);
        if (isFocused()) {
            gc.setStroke(DawStyle.alpha(DawStyle.ACCENT, .55));
            gc.setLineWidth(1);
            gc.strokeRect(.5, .5, Math.max(0, w - 1), Math.max(0, h - 1));
        }
    }

    private void drawRuler(GraphicsContext gc, double w) {
        gc.setFill(DawStyle.HEADER);
        gc.fillRect(0, 0, w, RULER_H);
        gc.setFill(DawStyle.PANEL);
        gc.fillRect(0, 0, HEADER_W, RULER_H);
        gc.setFill(DawStyle.LINE_STRONG);
        gc.fillRect(0, RULER_H - 1, w, 1);
        gc.fillRect(HEADER_W - 1, 0, 1, canvas.getHeight());

        gc.setFont(DawStyle.display(10));
        gc.setFill(DawStyle.MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("TRACKS", 14, 19);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText(String.format("%02d", tracks.size()), HEADER_W - 12, 19);

        double start = offsetBeat(), end = Math.min(totalBeats(), start + viewBeats());
        int firstBeat = Math.max(0, (int) Math.floor(start));
        int lastBeat = (int) Math.ceil(end);
        for (int beat = firstBeat; beat <= lastBeat; beat++) {
            double x = xForBeat(beat);
            if (x < HEADER_W - 1) continue;
            boolean bar = beat % 4 == 0;
            gc.setStroke(bar ? DawStyle.LINE_STRONG : DawStyle.LINE);
            gc.setLineWidth(1);
            gc.strokeLine(x + .5, bar ? 12 : 20, x + .5, RULER_H);
            if (bar) {
                gc.setFill(DawStyle.MUTED);
                gc.setFont(DawStyle.display(10));
                gc.setTextAlign(TextAlignment.LEFT);
                gc.fillText(Integer.toString(beat / 4 + 1), x + 5, 18);
            }
        }
    }

    private void drawLanes(GraphicsContext gc, double w, double h) {
        int first = (int) Math.floor(offsetTrack());
        int last = Math.min(tracks.size(), first + (int) Math.ceil(visibleRows()) + 1);
        for (int i = first; i < last; i++) {
            final int laneIndex = i;
            double y = yForTrack(i);
            if (y > h || y + ROW_H < RULER_H) continue;
            boolean selected = i == selectedTrack;
            boolean hovered = i == hoverTrack;
            gc.setFill(i % 2 == 0 ? DawStyle.LANE : DawStyle.LANE_ALT);
            gc.fillRect(0, y, w, ROW_H);
            if (hovered) {
                gc.setFill(DawStyle.alpha(DawStyle.TEXT, .025));
                gc.fillRect(0, y, w, ROW_H);
            }
            if (selected) {
                gc.setFill(DawStyle.alpha(DawStyle.track(i), .085));
                gc.fillRect(0, y, w, ROW_H);
            }
            if (i == flashTrack) {
                gc.setFill(DawStyle.alpha(DawStyle.track(i), .17));
                gc.fillRect(0, y, w, ROW_H);
            }
            gc.setFill(selected ? DawStyle.RAISED : DawStyle.PANEL);
            gc.fillRect(0, y, HEADER_W, ROW_H);
            if (selected) {
                gc.setFill(DawStyle.track(i));
                gc.fillRect(HEADER_W - 2, y, 2, ROW_H);
            }
            gc.setStroke(DawStyle.LINE);
            gc.strokeLine(0, y + ROW_H - .5, w, y + ROW_H - .5);

            var track = tracks.get(i);
            Color color = DawStyle.track(i);
            gc.setFill(color);
            gc.fillOval(14, y + 17, 8, 8);

            gc.setFont(DawStyle.display(9));
            gc.setFill(DawStyle.DIM);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText(String.format("%02d", i + 1), 30, y + 17);

            gc.setFont(DawStyle.display(13));
            gc.setFill(selected ? DawStyle.TEXT : Color.web("#D1D5DD"));
            drawClippedText(gc, track.name(), 45, y + 32, HEADER_W - 92);

            int count = notesByTrack.get(laneIndex).size();
            gc.setFont(DawStyle.ui(9));
            gc.setFill(DawStyle.DIM);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText(count == 0 ? "empty lane" : count + " hits", 45, y + 45);
            gc.setFont(DawStyle.display(8));
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText(track.pitchMode() == Beat.PitchMode.PITCH_SHIFT ? "PITCH" : "VAR",
                    HEADER_W - 12, y + 45);

            gc.setFill(selected ? DawStyle.RAISED_HOVER : DawStyle.RAISED);
            gc.fillRoundRect(HEADER_W - 49, y + 17, 33, 19, 2, 2);
            gc.setFont(DawStyle.display(10));
            gc.setFill(selected ? DawStyle.TEXT : DawStyle.MUTED);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(fitKey(track.key()), HEADER_W - 32.5, y + 30.5);
        }
        gc.setFill(DawStyle.LINE_STRONG);
        gc.fillRect(HEADER_W - 1, RULER_H, 1, h - RULER_H);
    }

    private void drawGrid(GraphicsContext gc, double w, double h) {
        double start = offsetBeat(), end = Math.min(totalBeats(), start + viewBeats());
        int firstStep = Math.max(0, (int) Math.floor(start / .25));
        int lastStep = (int) Math.ceil(end / .25);
        for (int step = firstStep; step <= lastStep; step++) {
            double beat = step * .25;
            double x = xForBeat(beat);
            if (x < HEADER_W) continue;
            boolean bar = step % 16 == 0;
            boolean beatLine = step % 4 == 0;
            if (!bar && !beatLine && zoom < 40) continue;
            gc.setStroke(bar ? DawStyle.LINE_STRONG : beatLine ? DawStyle.LINE : DawStyle.alpha(DawStyle.LINE, .55));
            gc.setLineWidth(1);
            gc.strokeLine(x + .5, RULER_H, x + .5, h);
        }
        gc.setFill(DawStyle.LINE);
        gc.fillRect(HEADER_W, RULER_H, w - HEADER_W, 1);
    }

    private void drawClips(GraphicsContext gc) {
        for (int trackIndex = 0; trackIndex < tracks.size(); trackIndex++) {
            double y = yForTrack(trackIndex) + 9;
            double rowH = ROW_H - 18;
            if (y + rowH < RULER_H || y > canvas.getHeight()) continue;
            Color color = DawStyle.track(trackIndex);
            boolean selected = trackIndex == selectedTrack;
            for (int cycle = 0; cycle < loopCycles(); cycle++) {
                double clipStart = cycle * loopBeats;
                if (clipStart >= totalBeats()) break;
                double x1 = xForBeat(clipStart), x2 = xForBeat(clipStart + loopBeats);
                if (x2 < HEADER_W || x1 > canvas.getWidth()) continue;
                double left = Math.max(HEADER_W + 1, x1);
                double right = Math.min(canvas.getWidth(), Math.min(x2, xForBeat(totalBeats())));
                double width = Math.max(0, right - left - 2);
                if (width < 1) continue;
                gc.setFill(DawStyle.alpha(color, selected ? .22 : .12));
                gc.fillRoundRect(left + 1, y, width, rowH, 2, 2);
                gc.setStroke(DawStyle.alpha(selected ? DawStyle.ACCENT : color, selected ? .68 : .32));
                gc.setLineWidth(1);
                gc.strokeRoundRect(left + 1.5, y + .5, Math.max(0, width - 1), rowH - 1, 2, 2);
                if (width > 42) {
                    gc.setFill(DawStyle.alpha(color, .9));
                    gc.setFont(DawStyle.display(8));
                    gc.setTextAlign(TextAlignment.LEFT);
                    gc.fillText("A", left + 7, y + 12);
                }
                var trackNotes = trackIndex < notesByTrack.size() ? notesByTrack.get(trackIndex) : List.<Beat.Hit>of();
                for (var hit : trackNotes) {
                    if (hit.beat() >= loopBeats) continue;
                    double x = xForBeat(clipStart + hit.beat());
                    if (x < left + 1 || x > right - 1) continue;
                    double noteHeight = 5 + hit.velocity() * 9;
                    double pitchOffset = -hit.pitch() / (double) Beat.MAX_NOTE_PITCH * Math.min(10, rowH * .28);
                    double noteY = clamp(y + rowH / 2 - noteHeight / 2 + pitchOffset,
                            y + 2, y + rowH - noteHeight - 2);
                    gc.setFill(DawStyle.alpha(color, .95));
                    gc.fillRoundRect(x, noteY, Math.max(1.5, Math.min(3, zoom * .1)),
                            noteHeight, 1.5, 1.5);
                }
            }
        }
    }

    private void drawCursors(GraphicsContext gc, double w, double h) {
        if (hoverTrack >= 0 && !playing) {
            double y = yForTrack(hoverTrack);
            gc.setStroke(DawStyle.alpha(DawStyle.TEXT, .05));
            gc.setLineWidth(1);
            gc.strokeLine(HEADER_W, Math.max(RULER_H, y), w, Math.max(RULER_H, y));
        }
        double editX = xForBeat(editBeat);
        if (editX >= HEADER_W && editX <= w) {
            gc.setStroke(DawStyle.alpha(DawStyle.TEXT, .34));
            gc.setLineWidth(1);
            gc.setLineDashes(2, 4);
            gc.strokeLine(editX + .5, RULER_H, editX + .5, h);
            gc.setLineDashes();
        }
        if (!playing) return;
        double beat = playheadCycle * loopBeats + playhead;
        double x = xForBeat(beat);
        if (x < HEADER_W || x > w) return;
        gc.setStroke(DawStyle.PLAYHEAD);
        gc.setLineWidth(2);
        gc.strokeLine(x, RULER_H - 3, x, h);
        gc.setFill(DawStyle.PLAYHEAD);
        gc.fillPolygon(new double[]{x - 4, x + 4, x}, new double[]{RULER_H - 8, RULER_H - 8, RULER_H}, 3);
    }

    private double xForBeat(double beat) {
        return HEADER_W + (beat - offsetBeat()) * zoom;
    }

    private double yForTrack(int index) {
        return RULER_H + (index - offsetTrack()) * ROW_H;
    }

    private static void drawClippedText(GraphicsContext gc, String text, double x, double y, double maxWidth) {
        gc.save();
        gc.beginPath();
        gc.rect(x, y - 13, Math.max(0, maxWidth), 16);
        gc.clip();
        gc.fillText(text, x, y);
        gc.restore();
    }

    private static String fitKey(String key) {
        if (key.length() <= 4) return key;
        return key.substring(0, 4);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

package io.pocketgroove;

import javafx.geometry.Orientation;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** A 16th-note step editor with an integrated velocity lane. */
final class StepSequencerView extends Region {
    private static final double PAD = 12;
    private static final double SCROLL = 11;
    private static final double HEADER_H = 42;
    private static final double STEP_TOP = 52;
    private static final double STEP_H = 78;
    private static final double VEL_TOP = 138;
    private static final double VEL_H = 44;
    private static final double PITCH_TOP = 190;
    private static final double PITCH_H = 46;
    private static final double MIN_CELL = 17;
    private static final double MAX_CELL = 88;

    private final Canvas canvas = new Canvas();
    private final ScrollBar horizontal = new ScrollBar();
    private Consumer<List<Beat.Hit>> onHitsChanged = ignored -> {};
    private IntConsumer onStepSelected = ignored -> {};
    private List<Beat.Hit> hits = List.of();
    private Beat.Track track;
    private int trackIndex;
    private double loopBeats = 4;
    private int selectedStep;
    private int hoverStep = -1;
    private int playheadStep = -1;
    private boolean playing;
    private boolean draggingGrid;
    private boolean paintActive;
    private int lastPaintedStep = -1;
    private boolean draggingVelocity;
    private int lastVelocityStep = -1;
    private boolean draggingPitch;
    private int lastPitchStep = -1;
    private double cellWidth = 40;
    private double startX = PAD;
    private boolean updatingBar;

    StepSequencerView() {
        getStyleClass().add("step-sequencer");
        setFocusTraversable(true);
        setMinHeight(258);
        setPrefHeight(274);
        horizontal.setOrientation(Orientation.HORIZONTAL);
        getChildren().addAll(canvas, horizontal);
        horizontal.valueProperty().addListener((o, a, b) -> draw());
        canvas.setOnMouseMoved(this::mouseMoved);
        canvas.setOnMouseExited(e -> { hoverStep = -1; draw(); });
        canvas.setOnMousePressed(this::mousePressed);
        canvas.setOnMouseDragged(this::mouseDragged);
        canvas.setOnMouseReleased(e -> {
            draggingGrid = false;
            draggingVelocity = false;
            draggingPitch = false;
            lastPaintedStep = -1;
            lastVelocityStep = -1;
            lastPitchStep = -1;
        });
        setOnKeyPressed(this::keyPressed);
        focusedProperty().addListener((o, a, b) -> draw());
        widthProperty().addListener((o, a, b) -> requestLayout());
        heightProperty().addListener((o, a, b) -> requestLayout());
    }

    void setHandlers(Consumer<List<Beat.Hit>> onHitsChanged, IntConsumer onStepSelected) {
        this.onHitsChanged = onHitsChanged;
        this.onStepSelected = onStepSelected;
    }

    void setData(int trackIndex, Beat.Track track, List<Beat.Hit> hits, double loopBeats) {
        this.trackIndex = trackIndex;
        this.track = track;
        this.hits = List.copyOf(hits);
        this.loopBeats = loopBeats;
        selectedStep = Math.min(selectedStep, Pattern.stepCount(loopBeats) - 1);
        updateBar();
        updateAccessible();
        draw();
    }

    void setHits(List<Beat.Hit> hits) {
        this.hits = List.copyOf(hits);
        updateAccessible();
        draw();
    }

    void setSelectedStep(int step) {
        selectedStep = clampInt(step, 0, stepCount() - 1);
        onStepSelected.accept(selectedStep);
        updateAccessible();
        draw();
    }

    int stepCount() { return Pattern.stepCount(loopBeats); }
    int selectedStep() { return selectedStep; }

    void setPlayhead(double position, boolean playing) {
        this.playing = playing;
        int next = playing ? Math.floorMod((int) Math.floor(position * Pattern.STEPS_PER_BEAT), stepCount()) : -1;
        if (next != playheadStep || playing) {
            playheadStep = next;
            draw();
        }
    }

    @Override protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        double cw = Math.max(1, w), ch = Math.max(1, h - SCROLL);
        canvas.setWidth(cw);
        canvas.setHeight(ch);
        canvas.relocate(0, 0);
        horizontal.resizeRelocate(0, h - SCROLL, cw, SCROLL);
        updateBar();
        draw();
    }

    private void updateBar() {
        if (updatingBar) return;
        updatingBar = true;
        try {
            double available = Math.max(1, canvas.getWidth() - PAD * 2);
            cellWidth = clamp(available / Math.max(1, stepCount()), MIN_CELL, MAX_CELL);
            double total = cellWidth * stepCount();
            double max = Math.max(0, total - available);
            horizontal.setMin(0);
            horizontal.setMax(max);
            horizontal.setVisibleAmount(available);
            horizontal.setBlockIncrement(Math.max(1, available * .8));
            horizontal.setValue(clamp(horizontal.getValue(), 0, max));
            horizontal.setVisible(max > 0);
            startX = PAD + Math.max(0, (available - total) / 2) - horizontal.getValue();
        } finally {
            updatingBar = false;
        }
    }

    private void draw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) return;
        var gc = canvas.getGraphicsContext2D();
        gc.setFill(DawStyle.BG);
        gc.fillRect(0, 0, w, h);
        gc.setFill(DawStyle.PANEL);
        gc.fillRect(0, 0, w, HEADER_H);
        gc.setFill(DawStyle.LINE_STRONG);
        gc.fillRect(0, HEADER_H - 1, w, 1);
        var cells = snapshotCells();
        drawHeader(gc, w, cells);
        drawGrid(gc, w, cells);
        drawVelocity(gc, w, cells);
        drawPitch(gc, w, cells);
        drawFooter(gc, w, h);
        if (isFocused()) {
            gc.setStroke(DawStyle.alpha(DawStyle.ACCENT, .55));
            gc.setLineWidth(1);
            gc.strokeRect(.5, .5, Math.max(0, w - 1), Math.max(0, h - 1));
        }
    }

    private void drawHeader(GraphicsContext gc, double w, Cells cells) {
        if (track == null) return;
        Color color = DawStyle.track(trackIndex);
        gc.setFill(color);
        gc.fillOval(14, 16, 8, 8);
        gc.setFont(DawStyle.display(14));
        gc.setFill(DawStyle.TEXT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(track.name(), 30, 26);
        gc.setFont(DawStyle.display(9));
        gc.setFill(DawStyle.MUTED);
        if (track.instrument() == Beat.Instrument.MELODY) {
            gc.fillText("MELODY · BASE MIDI " + track.baseMidiNote(), 30, 37);
        } else {
            gc.fillText("DRUM · PATTERN A", 30, 37);
        }

        boolean active = cells.active()[selectedStep];
        double velocity = cells.velocity()[selectedStep];
        double notePitch = cells.pitch()[selectedStep];
        int chord = Pattern.chordSize(hits, trackIndex, selectedStep);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setFont(DawStyle.display(10));
        gc.setFill(DawStyle.MUTED);
        int bar = selectedStep / 16 + 1;
        int step = selectedStep % 16 + 1;
        gc.fillText(String.format("STEP %02d · BAR %02d   %s   NOTE %s   %d%%%s", step, bar,
                active ? "ON" : "OFF", formatPitch(notePitch), Math.round(velocity * 100),
                chord > 1 ? "   CHORD " + chord : ""), w - 16, 20);
        gc.setFont(DawStyle.ui(9));
        gc.setFill(DawStyle.DIM);
        gc.fillText((int) (loopBeats / 4) + " BAR · " + stepCount() + " STEPS", w - 16, 34);
    }

    private void drawGrid(GraphicsContext gc, double w, Cells cells) {
        double top = STEP_TOP, height = STEP_H;
        for (int step = 0; step < stepCount(); step++) {
            double x = startX + step * cellWidth;
            if (x + cellWidth < 0 || x > w) continue;
            boolean group = (step / 4) % 2 == 0;
            boolean active = cells.active()[step];
            Color color = DawStyle.track(trackIndex);
            gc.setFill(active ? DawStyle.alpha(color, .78) : group ? DawStyle.RAISED : DawStyle.LANE);
            gc.fillRoundRect(x + 1.5, top, Math.max(1, cellWidth - 3), height, 2, 2);
            if (!active) {
                gc.setStroke(DawStyle.LINE);
                gc.setLineWidth(1);
                gc.strokeRoundRect(x + 2, top + .5, Math.max(0, cellWidth - 4), height - 1, 2, 2);
            }
            if (hoverStep == step) {
                gc.setFill(DawStyle.alpha(DawStyle.TEXT, .055));
                gc.fillRoundRect(x + 1.5, top, Math.max(1, cellWidth - 3), height, 2, 2);
            }
            if (step == selectedStep) {
                gc.setStroke(DawStyle.ACCENT);
                gc.setLineWidth(1.5);
                gc.strokeRoundRect(x + 1.5, top + .75, Math.max(0, cellWidth - 3), height - 1.5, 2, 2);
            }
            if (playing && step == playheadStep) {
                gc.setFill(DawStyle.PLAYHEAD);
                gc.fillRect(x + 3, top + 2, Math.max(1, cellWidth - 6), 2);
            }
            gc.setFont(DawStyle.display(cellWidth > 30 ? 10 : 8));
            gc.setFill(active ? Color.web("#141619") : DawStyle.DIM);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(Integer.toString(step + 1), x + cellWidth / 2, top + height - 8);
            if (step % 16 == 0 && cellWidth > 22) {
                gc.setFill(DawStyle.alpha(DawStyle.TEXT, .16));
                gc.fillRect(x + 1.5, top, 1, height);
            }
            if (step % 4 == 0) {
                gc.setFill(DawStyle.alpha(DawStyle.ACCENT, step % 16 == 0 ? .35 : .16));
                gc.fillRect(x + 1.5, top - 4, Math.max(1, cellWidth - 3), 2);
            }
        }
    }

    private void drawVelocity(GraphicsContext gc, double w, Cells cells) {
        gc.setFill(DawStyle.PANEL);
        gc.fillRect(0, VEL_TOP - 15, w, VEL_H + 15);
        gc.setFont(DawStyle.display(9));
        gc.setFill(DawStyle.MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("VELOCITY", PAD, VEL_TOP - 4);
        for (double value : new double[]{0.25, 0.5, 1.0}) {
            double y = VEL_TOP + VEL_H - value * VEL_H;
            gc.setStroke(DawStyle.alpha(DawStyle.LINE, value == 1.0 ? 1 : .7));
            gc.setLineWidth(1);
            gc.strokeLine(PAD, y + .5, w - PAD, y + .5);
        }
        for (int step = 0; step < stepCount(); step++) {
            double x = startX + step * cellWidth;
            if (x + cellWidth < 0 || x > w) continue;
            boolean active = cells.active()[step];
            double velocity = cells.velocity()[step];
            if (active) {
                double bar = Math.max(2, velocity * VEL_H);
                gc.setFill(DawStyle.alpha(DawStyle.track(trackIndex), .84));
                gc.fillRoundRect(x + 4, VEL_TOP + VEL_H - bar, Math.max(1, cellWidth - 8), bar, 2, 2);
            } else {
                gc.setFill(DawStyle.alpha(DawStyle.DIM, .45));
                gc.fillRect(x + 4, VEL_TOP + VEL_H - 2, Math.max(1, cellWidth - 8), 1);
            }
            if (hoverStep == step || step == selectedStep) {
                gc.setStroke(step == selectedStep ? DawStyle.ACCENT : DawStyle.alpha(DawStyle.TEXT, .25));
                gc.setLineWidth(1);
                gc.strokeRect(x + 2.5, VEL_TOP - 1, Math.max(0, cellWidth - 5), VEL_H + 1);
            }
        }
    }

    private void drawPitch(GraphicsContext gc, double w, Cells cells) {
        gc.setFill(DawStyle.PANEL);
        gc.fillRect(0, PITCH_TOP - 13, w, PITCH_H + 13);
        gc.setFont(DawStyle.display(9));
        gc.setFill(DawStyle.MUTED);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("NOTE PITCH · ±24   현재 " + formatPitch(cells.pitch()[selectedStep]),
                PAD, PITCH_TOP - 3);
        double zero = pitchY(0);
        for (int semitone : new int[]{-24, -12, 0, 12, 24}) {
            double y = pitchY(semitone);
            gc.setStroke(semitone == 0 ? DawStyle.LINE_STRONG : DawStyle.alpha(DawStyle.LINE, .7));
            gc.setLineWidth(1);
            gc.strokeLine(PAD, y + .5, w - PAD, y + .5);
        }
        for (int step = 0; step < stepCount(); step++) {
            double x = startX + step * cellWidth;
            if (x + cellWidth < 0 || x > w) continue;
            boolean active = cells.active()[step];
            if (active) {
                double y = pitchY(cells.pitch()[step]);
                gc.setFill(DawStyle.alpha(DawStyle.track(trackIndex), .92));
                gc.fillRoundRect(x + 4, y - 2, Math.max(1, cellWidth - 8), 4, 2, 2);
                gc.setFill(DawStyle.alpha(DawStyle.track(trackIndex), .28));
                gc.fillRect(x + cellWidth / 2 - .5, Math.min(y, zero), 1, Math.abs(y - zero));
                if (step == selectedStep) {
                    gc.setFont(DawStyle.display(9));
                    gc.setFill(DawStyle.TEXT);
                    gc.setTextAlign(TextAlignment.CENTER);
                    double labelY = y > PITCH_TOP + 12 ? y - 6 : y + 13;
                    gc.fillText(formatPitch(cells.pitch()[step]), x + cellWidth / 2, labelY);
                }
            }
            if (hoverStep == step || step == selectedStep) {
                gc.setStroke(step == selectedStep ? DawStyle.ACCENT : DawStyle.alpha(DawStyle.TEXT, .25));
                gc.setLineWidth(1);
                gc.strokeRect(x + 2.5, PITCH_TOP - 1, Math.max(0, cellWidth - 5), PITCH_H + 1);
            }
        }
    }

    private double pitchY(double pitch) {
        return PITCH_TOP + (Beat.MAX_NOTE_PITCH - pitch) / (2.0 * Beat.MAX_NOTE_PITCH) * PITCH_H;
    }

    private double pitchAt(double y, boolean fine) {
        double value = Beat.MAX_NOTE_PITCH
                - (y - PITCH_TOP) / PITCH_H * 2.0 * Beat.MAX_NOTE_PITCH;
        double step = fine ? 0.1 : 1;
        value = Math.round(value / step) * step;
        return clamp(value, -Beat.MAX_NOTE_PITCH, Beat.MAX_NOTE_PITCH);
    }

    private static String formatPitch(double pitch) {
        if (Math.abs(pitch - Math.rint(pitch)) < 0.001) {
            return (pitch > 0 ? "+" : "") + (int) Math.rint(pitch);
        }
        return String.format(java.util.Locale.ROOT, "%+.1f", pitch);
    }

    private Cells snapshotCells() {
        int count = stepCount();
        boolean[] active = new boolean[count];
        double[] velocity = new double[count];
        double[] pitch = new double[count];
        for (var hit : hits) {
            if (hit.track() != trackIndex) continue;
            int step = (int) Math.round(hit.beat() / Pattern.CELL);
            if (step < 0 || step >= count) continue;
            if (Math.abs(hit.beat() - Pattern.stepBeat(step)) >= Pattern.CELL / 2) continue;
            active[step] = true;
            if (hit.velocity() >= velocity[step]) {
                velocity[step] = hit.velocity();
                pitch[step] = hit.pitch();
            }
        }
        return new Cells(active, velocity, pitch);
    }

    private record Cells(boolean[] active, double[] velocity, double[] pitch) {}

    private void drawFooter(GraphicsContext gc, double w, double h) {
        gc.setFont(DawStyle.ui(9));
        gc.setFill(DawStyle.DIM);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("스텝 클릭·드래그 · NOTE: 1반음 · Ctrl+드래그: 0.1반음 · ↑↓ 세기 · Shift+↑↓ 음높이",
                PAD, h - 9);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("PATTERN A", w - PAD, h - 9);
    }

    private void mouseMoved(javafx.scene.input.MouseEvent e) {
        int step = stepAt(e.getX());
        boolean inPitch = e.getY() >= PITCH_TOP && e.getY() <= PITCH_TOP + PITCH_H;
        canvas.setCursor(step >= 0 ? (inPitch ? javafx.scene.Cursor.V_RESIZE : javafx.scene.Cursor.HAND)
                : javafx.scene.Cursor.DEFAULT);
        if (step != hoverStep) {
            hoverStep = step;
            draw();
        }
    }

    private void mousePressed(javafx.scene.input.MouseEvent e) {
        requestFocus();
        if (e.getButton() != MouseButton.PRIMARY) return;
        int step = stepAt(e.getX());
        if (step < 0) return;
        selectStep(step);
        if (e.getY() >= STEP_TOP && e.getY() <= STEP_TOP + STEP_H) {
            var cell = Pattern.cell(hits, trackIndex, step);
            paintActive = !cell.active();
            hits = Pattern.setActive(hits, trackIndex, step, paintActive, loopBeats, 0.8);
            draggingGrid = true;
            lastPaintedStep = step;
            commit();
        } else if (e.getY() >= VEL_TOP && e.getY() <= VEL_TOP + VEL_H) {
            draggingVelocity = true;
            lastVelocityStep = step;
            hits = Pattern.setVelocity(hits, trackIndex, step, velocityAt(e.getY()));
            commit();
        } else if (e.getY() >= PITCH_TOP && e.getY() <= PITCH_TOP + PITCH_H) {
            draggingPitch = true;
            lastPitchStep = step;
            hits = Pattern.setPitch(hits, trackIndex, step, pitchAt(e.getY(), e.isControlDown()));
            commit();
        }
    }

    private void mouseDragged(javafx.scene.input.MouseEvent e) {
        int step = stepAt(e.getX());
        if (step < 0) return;
        if (draggingGrid && step != lastPaintedStep) {
            selectStep(step);
            var cell = Pattern.cell(hits, trackIndex, step);
            if (cell.active() != paintActive) {
                hits = Pattern.setActive(hits, trackIndex, step, paintActive, loopBeats, 0.8);
                lastPaintedStep = step;
                commit();
            }
        } else if (draggingVelocity) {
            selectStep(step);
            if (step != lastVelocityStep) lastVelocityStep = step;
            hits = Pattern.setVelocity(hits, trackIndex, step, velocityAt(e.getY()));
            commit();
        } else if (draggingPitch) {
            selectStep(step);
            if (step != lastPitchStep) lastPitchStep = step;
            hits = Pattern.setPitch(hits, trackIndex, step, pitchAt(e.getY(), e.isControlDown()));
            commit();
        }
    }

    private void keyPressed(javafx.scene.input.KeyEvent e) {
        if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT) {
            int amount = e.isShiftDown() ? 4 : 1;
            selectStep(clampInt(selectedStep + (e.getCode() == KeyCode.LEFT ? -amount : amount),
                    0, stepCount() - 1));
            e.consume();
        } else if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) {
            var cell = Pattern.cell(hits, trackIndex, selectedStep);
            hits = Pattern.setActive(hits, trackIndex, selectedStep, !cell.active(), loopBeats, 0.8);
            commit();
            e.consume();
        } else if (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN) {
            var cell = Pattern.cell(hits, trackIndex, selectedStep);
            double amount = e.isShiftDown() ? 1 : .05;
            if (e.isShiftDown()) {
                double pitch = clamp(cell.pitch() + (e.getCode() == KeyCode.UP ? amount : -amount),
                        -Beat.MAX_NOTE_PITCH, Beat.MAX_NOTE_PITCH);
                hits = Pattern.setPitch(hits, trackIndex, selectedStep, pitch);
            } else {
                double velocity = clamp(cell.velocity() + (e.getCode() == KeyCode.UP ? amount : -amount),
                        .05, 1);
                hits = Pattern.setVelocity(hits, trackIndex, selectedStep, velocity);
            }
            commit();
            e.consume();
        }
    }

    private void selectStep(int step) {
        selectedStep = clampInt(step, 0, stepCount() - 1);
        onStepSelected.accept(selectedStep);
        updateAccessible();
        draw();
    }

    private int stepAt(double x) {
        double local = x - startX;
        if (local < 0) return -1;
        int step = (int) Math.floor(local / cellWidth);
        return step >= 0 && step < stepCount() ? step : -1;
    }

    private double velocityAt(double y) {
        return clamp((VEL_TOP + VEL_H - y) / VEL_H, .05, 1);
    }

    private void commit() {
        updateAccessible();
        onHitsChanged.accept(hits);
        draw();
    }

    private void updateAccessible() {
        if (track == null) return;
        setAccessibleText("스텝 시퀀서, " + track.name() + ", "
                + (loopBeats / 4) + "마디, " + stepCount() + "스텝, 선택 " + (selectedStep + 1));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

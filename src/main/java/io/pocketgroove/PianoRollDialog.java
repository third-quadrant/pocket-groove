package io.pocketgroove;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Modeless-ish piano roll for one melody track; edits are applied to the shared project immediately. */
final class PianoRollDialog {
    private PianoRollDialog() {}

    static Stage show(Window owner, Beat.Track track, int trackIndex, List<Beat.Hit> initial,
                      double loopBeats, int projectPitch,
                      Consumer<List<Beat.Hit>> onChange,
                      BiConsumer<Integer, Boolean> onSettings) {
        var view = new PianoRollView(trackIndex, initial, loopBeats,
                track.baseMidiNote() + projectPitch + (int) Math.round(track.pitch()));
        var lengthBox = new ComboBox<Double>();
        lengthBox.getItems().addAll(.25, .5, 1.0, 2.0, 4.0);
        lengthBox.setValue(.25);
        lengthBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public Double fromString(String text) {
                return switch (text) {
                    case "1/4" -> 1.0;
                    case "1/2" -> 2.0;
                    case "1" -> 4.0;
                    case "1/8" -> .5;
                    default -> .25;
                };
            }
            @Override public String toString(Double value) {
                if (value == null) return "";
                if (value == .25) return "1/16";
                if (value == .5) return "1/8";
                if (value == 1.0) return "1/4";
                if (value == 2.0) return "1/2";
                return "1";
            }
        });
        lengthBox.setPrefWidth(78);
        var velocity = new Slider(.05, 1, .8);
        velocity.setPrefWidth(110);
        var velocityValue = new Label("80%");
        velocityValue.getStyleClass().add("value");
        velocity.valueProperty().addListener((o, a, value) -> {
            velocityValue.setText(Math.round(value.doubleValue() * 100) + "%");
            view.setNewNote(lengthBox.getValue(), value.doubleValue());
        });
        lengthBox.valueProperty().addListener((o, a, value) ->
                view.setNewNote(value, velocity.getValue()));

        var base = new Spinner<Integer>(0, 127, track.baseMidiNote());
        base.setEditable(true);
        base.setPrefWidth(72);
        var formant = new CheckBox("Formant 보존");
        formant.setSelected(track.formantPreserve());
        base.valueProperty().addListener((o, a, value) -> {
            view.setBaseMidiNote(value);
            onSettings.accept(value, formant.isSelected());
        });
        formant.selectedProperty().addListener((o, a, value) ->
                onSettings.accept(base.getValue(), value));

        var delete = new Button("선택 삭제");
        delete.setOnAction(e -> view.deleteSelected());
        var close = new Button("닫기");
        close.setOnAction(e -> ((Stage) close.getScene().getWindow()).close());
        var help = new Label("클릭: 추가 · 드래그: 이동 · 오른쪽 끝 드래그: 길이 · Del: 삭제");
        help.getStyleClass().add("dialog-note");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var toolbar = new HBox(8, new Label("BASE"), base, formant, new Label("길이"),
                lengthBox, new Label("세기"), velocity, velocityValue, spacer, help, delete, close);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8, 10, 8, 10));
        toolbar.getStyleClass().add("piano-toolbar");

        view.setOnChange(updated -> {
            onChange.accept(updated);
            view.setData(updated);
        });

        var root = new BorderPane(view);
        root.setTop(toolbar);
        root.setStyle("-fx-background-color: #111419;");
        var scene = new Scene(root, 980, 620);
        scene.getStylesheets().add(PianoRollDialog.class.getResource("/drum.css").toExternalForm());
        var stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("음표 편집 — " + track.name());
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(480);
        stage.show();
        view.requestFocus();
        return stage;
    }

    private static final class PianoRollView extends Region {
        private static final double KEY_W = 72;
        private static final double RULER_H = 26;
        private static final double ROW_H = 12;
        private static final double STEP_W = 24;
        private static final int MIN_PITCH = -Beat.MAX_NOTE_PITCH;
        private static final int MAX_PITCH = Beat.MAX_NOTE_PITCH;
        private static final int ROWS = MAX_PITCH - MIN_PITCH + 1;

        private final Canvas canvas = new Canvas();
        private final ScrollBar horizontal = new ScrollBar();
        private final int trackIndex;
        private final double loopBeats;
        private List<Beat.Hit> hits;
        private Consumer<List<Beat.Hit>> onChange = ignored -> {};
        private double newLength = .25;
        private double newVelocity = .8;
        private int noteBase;
        private int baseMidiNote;
        private Selected selected;
        private Drag drag;
        private int hoverStep = -1;
        private int hoverPitch = Integer.MIN_VALUE;

        private record Selected(int step, double pitch) {}
        private record Drag(Selected origin, Beat.Hit hit, boolean resize, int startX) {}

        PianoRollView(int trackIndex, List<Beat.Hit> initial,
                      double loopBeats, int noteBase) {
            this.trackIndex = trackIndex;
            this.hits = List.copyOf(initial);
            this.loopBeats = loopBeats;
            this.noteBase = noteBase;
            this.baseMidiNote = noteBase;
            getStyleClass().add("piano-roll");
            setFocusTraversable(true);
            getChildren().addAll(canvas, horizontal);
            horizontal.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
            horizontal.valueProperty().addListener((o, a, b) -> draw());
            canvas.setOnMouseMoved(this::mouseMoved);
            canvas.setOnMouseExited(e -> {
                hoverStep = -1;
                hoverPitch = Integer.MIN_VALUE;
                draw();
            });
            canvas.setOnMousePressed(this::mousePressed);
            canvas.setOnMouseDragged(this::mouseDragged);
            canvas.setOnMouseReleased(e -> drag = null);
            canvas.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.SECONDARY) {
                    var hit = hitAt(e.getX(), e.getY());
                    if (hit != null) {
                        remove(hit);
                        e.consume();
                    }
                }
            });
            setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                    deleteSelected();
                    e.consume();
                }
            });
            widthProperty().addListener((o, a, b) -> requestLayout());
            heightProperty().addListener((o, a, b) -> requestLayout());
        }

        void setOnChange(Consumer<List<Beat.Hit>> listener) {
            onChange = listener == null ? ignored -> {} : listener;
        }

        void setData(List<Beat.Hit> updated) {
            hits = List.copyOf(updated);
            if (selected != null && Pattern.notes(hits, trackIndex, selected.step()).stream()
                    .noneMatch(hit -> close(hit.pitch(), selected.pitch()))) selected = null;
            draw();
        }

        void setNewNote(double length, double velocity) {
            newLength = length;
            newVelocity = velocity;
        }

        void setBaseMidiNote(int value) {
            noteBase += value - baseMidiNote;
            baseMidiNote = value;
            draw();
        }

        void deleteSelected() {
            if (selected == null) return;
            hits = Pattern.removeNote(hits, trackIndex, selected.step(), selected.pitch());
            selected = null;
            onChange.accept(hits);
            draw();
        }

        @Override protected void layoutChildren() {
            double w = getWidth(), h = getHeight();
            double cw = Math.max(1, w);
            double ch = Math.max(1, h - 14);
            canvas.setWidth(cw);
            canvas.setHeight(ch);
            canvas.relocate(0, 0);
            horizontal.resizeRelocate(0, h - 14, cw, 14);
            updateBar();
            draw();
        }

        private void updateBar() {
            double available = Math.max(1, canvas.getWidth() - KEY_W);
            double total = stepCount() * STEP_W;
            double max = Math.max(0, total - available);
            horizontal.setMin(0);
            horizontal.setMax(max);
            horizontal.setVisibleAmount(available);
            horizontal.setBlockIncrement(Math.max(1, available * .8));
            horizontal.setValue(Math.max(0, Math.min(max, horizontal.getValue())));
            horizontal.setVisible(max > 0);
        }

        private int stepCount() { return Math.max(1, Pattern.stepCount(loopBeats)); }

        private void draw() {
            double w = canvas.getWidth(), h = canvas.getHeight();
            if (w <= 0 || h <= 0) return;
            var gc = canvas.getGraphicsContext2D();
            gc.setFill(DawStyle.BG);
            gc.fillRect(0, 0, w, h);
            drawRuler(gc, w);
            drawKeys(gc);
            drawGrid(gc, w, h);
            drawNotes(gc, w);
            gc.setFont(DawStyle.ui(9));
            gc.setFill(DawStyle.DIM);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText("음표 " + hits.stream().filter(hit -> hit.track() == trackIndex).count()
                    + "개 · " + (int) (loopBeats / 4) + " BAR", w - 10, h - 5);
        }

        private void drawRuler(GraphicsContext gc, double w) {
            gc.setFill(DawStyle.PANEL);
            gc.fillRect(0, 0, w, RULER_H);
            gc.setFill(DawStyle.LINE_STRONG);
            gc.fillRect(0, RULER_H - 1, w, 1);
            gc.setFont(DawStyle.display(9));
            gc.setFill(DawStyle.MUTED);
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText("PITCH", 10, 17);
            for (int step = 0; step <= stepCount(); step++) {
                double x = xForStep(step);
                if (x < KEY_W || x > w) continue;
                boolean bar = step % 16 == 0;
                gc.setFill(bar ? DawStyle.alpha(DawStyle.TEXT, .2) : DawStyle.alpha(DawStyle.LINE, .7));
                gc.fillRect(x, bar ? 4 : RULER_H - 8, 1, bar ? RULER_H - 4 : 8);
                if (bar) {
                    gc.setFill(DawStyle.TEXT);
                    gc.fillText(Integer.toString(step / 16 + 1), x + 4, 14);
                }
            }
        }

        private void drawKeys(GraphicsContext gc) {
            gc.setFill(DawStyle.PANEL);
            gc.fillRect(0, RULER_H, KEY_W, canvas.getHeight());
            gc.setFont(DawStyle.display(8));
            for (int offset = MAX_PITCH; offset >= MIN_PITCH; offset--) {
                double y = yForPitch(offset);
                int midi = midiFor(offset);
                int pitchClass = Math.floorMod(midi, 12);
                boolean black = isBlack(pitchClass);
                gc.setFill(black ? Color.web("#1b1f25") : Color.web("#d8dce0"));
                gc.fillRect(0, y, KEY_W - 1, ROW_H - .5);
                gc.setStroke(DawStyle.LINE);
                gc.strokeLine(KEY_W - 6, y + ROW_H - .5, KEY_W, y + ROW_H - .5);
                if (pitchClass == 0) {
                    gc.setFill(black ? DawStyle.MUTED : Color.web("#22262b"));
                    gc.fillText(noteName(midi), 6, y + ROW_H - 2);
                }
                if (midi == midiFor(0)) {
                    gc.setFill(DawStyle.ACCENT);
                    gc.fillRect(KEY_W - 4, y, 3, ROW_H);
                }
            }
            gc.setFill(DawStyle.LINE_STRONG);
            gc.fillRect(KEY_W - 1, RULER_H, 1, canvas.getHeight() - RULER_H);
        }

        private void drawGrid(GraphicsContext gc, double w, double h) {
            for (int step = 0; step <= stepCount(); step++) {
                double x = xForStep(step);
                if (x < KEY_W || x > w) continue;
                boolean bar = step % 16 == 0;
                boolean beat = step % 4 == 0;
                gc.setFill(bar ? DawStyle.alpha(DawStyle.TEXT, .16)
                        : beat ? DawStyle.alpha(DawStyle.LINE, .9) : DawStyle.alpha(DawStyle.LINE, .45));
                gc.fillRect(x, RULER_H, 1, h - RULER_H);
            }
            for (int offset = MAX_PITCH; offset >= MIN_PITCH; offset--) {
                double y = yForPitch(offset);
                int pc = Math.floorMod(midiFor(offset), 12);
                if (isBlack(pc)) {
                    gc.setFill(DawStyle.alpha(Color.BLACK, .18));
                    gc.fillRect(KEY_W, y, w - KEY_W, ROW_H);
                }
                gc.setFill(DawStyle.alpha(DawStyle.LINE, .35));
                gc.fillRect(KEY_W, y + ROW_H - .5, w - KEY_W, 1);
            }
            if (hoverStep >= 0 && hoverStep < stepCount()) {
                double x = xForStep(hoverStep);
                gc.setFill(DawStyle.alpha(DawStyle.TEXT, .05));
                gc.fillRect(x, RULER_H, STEP_W, h - RULER_H);
            }
        }

        private void drawNotes(GraphicsContext gc, double w) {
            Color color = DawStyle.track(trackIndex);
            for (var hit : hits) {
                if (hit.track() != trackIndex) continue;
                double x = xForBeat(hit.beat());
                double width = Math.max(4, hit.length() * Pattern.STEPS_PER_BEAT * STEP_W);
                if (x + width < KEY_W || x > w) continue;
                double y = yForPitch(hit.pitch()) + 1.5;
                boolean isSelected = selected != null
                        && selected.step() == stepOf(hit.beat())
                        && close(selected.pitch(), hit.pitch());
                gc.setFill(DawStyle.alpha(color, isSelected ? .98 : .78));
                gc.fillRoundRect(Math.max(KEY_W, x), y, width - 1, ROW_H - 3, 3, 3);
                if (isSelected) {
                    gc.setStroke(DawStyle.ACCENT);
                    gc.setLineWidth(1.5);
                    gc.strokeRoundRect(Math.max(KEY_W, x) + .5, y + .5, width - 2, ROW_H - 4, 3, 3);
                }
                if (width > 30) {
                    gc.setFont(DawStyle.display(8));
                    gc.setFill(Color.web("#141619"));
                    gc.setTextAlign(TextAlignment.LEFT);
                    gc.fillText(noteName(midiFor(hit.pitch())), Math.max(KEY_W, x) + 4, y + ROW_H - 5);
                }
            }
        }

        private void mouseMoved(javafx.scene.input.MouseEvent e) {
            int step = stepAt(e.getX());
            int pitch = pitchAt(e.getY());
            if (step != hoverStep || pitch != hoverPitch) {
                hoverStep = step;
                hoverPitch = pitch;
                canvas.setCursor(step >= 0 && pitch != Integer.MIN_VALUE
                        ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);
                draw();
            }
        }

        private void mousePressed(javafx.scene.input.MouseEvent e) {
            requestFocus();
            if (e.getButton() != MouseButton.PRIMARY) return;
            var hit = hitAt(e.getX(), e.getY());
            if (hit != null) {
                selected = new Selected(stepOf(hit.beat()), hit.pitch());
                boolean resize = e.getX() >= xForBeat(hit.beat())
                        + hit.length() * Pattern.STEPS_PER_BEAT * STEP_W - 7;
                drag = new Drag(selected, hit, resize, (int) e.getX());
                draw();
                return;
            }
            int step = stepAt(e.getX());
            int pitch = pitchAt(e.getY());
            if (step < 0 || pitch == Integer.MIN_VALUE) return;
            selected = new Selected(step, pitch);
            double length = Math.min(newLength, loopBeats - Pattern.stepBeat(step));
            if (length < .01) return;
            hits = Pattern.addNote(hits, trackIndex, step, pitch, newVelocity, length);
            drag = new Drag(selected, hitAtPitch(step, pitch), false, (int) e.getX());
            onChange.accept(hits);
            draw();
        }

        private void mouseDragged(javafx.scene.input.MouseEvent e) {
            if (drag == null) return;
            int step = stepAt(e.getX());
            if (step < 0) return;
            if (drag.resize()) {
                double raw = (e.getX() - xForBeat(drag.hit().beat())) / (STEP_W * 4.0);
                double length = Math.max(.25, Math.round(raw * 4) / 4.0);
                length = Math.min(length, loopBeats - drag.hit().beat());
                var updated = Pattern.updateNote(hits, trackIndex, drag.origin().step(),
                        drag.origin().pitch(), drag.hit().pitch(), drag.hit().velocity(), length);
                hits = updated;
                selected = new Selected(drag.origin().step(), drag.hit().pitch());
                onChange.accept(hits);
                drag = new Drag(selected, hitAtPitch(stepOf(drag.hit().beat()), drag.hit().pitch()),
                        true, (int) e.getX());
                draw();
                return;
            }
            int pitch = pitchAt(e.getY());
            if (pitch == Integer.MIN_VALUE) return;
            double beat = Pattern.stepBeat(step);
            double length = Math.min(drag.hit().length(), loopBeats - beat);
            if (length < .01) return;
            hits = removeExact(hits, drag.origin().step(), drag.origin().pitch());
            hits = Pattern.addNote(hits, trackIndex, step, pitch, drag.hit().velocity(), length);
            selected = new Selected(step, pitch);
            var moved = hitAtPitch(step, pitch);
            drag = new Drag(selected, moved, false, (int) e.getX());
            onChange.accept(hits);
            draw();
        }

        private Beat.Hit hitAt(double x, double y) {
            int pitch = pitchAt(y);
            if (pitch == Integer.MIN_VALUE) return null;
            for (var hit : hits) {
                if (hit.track() != trackIndex || !close(hit.pitch(), pitch)) continue;
                double left = xForBeat(hit.beat());
                double right = left + Math.max(4, hit.length() * Pattern.STEPS_PER_BEAT * STEP_W);
                if (x >= left && x <= right) return hit;
            }
            return null;
        }

        private Beat.Hit hitAtPitch(int step, double pitch) {
            return hits.stream().filter(hit -> hit.track() == trackIndex
                            && stepOf(hit.beat()) == step && close(hit.pitch(), pitch))
                    .findFirst().orElse(null);
        }

        private void remove(Beat.Hit hit) {
            var notes = Pattern.notes(hits, trackIndex, stepOf(hit.beat()));
            if (notes.isEmpty()) return;
            hits = Pattern.removeNote(hits, trackIndex, stepOf(hit.beat()), hit.pitch());
            selected = null;
            onChange.accept(hits);
            draw();
        }

        private List<Beat.Hit> removeExact(List<Beat.Hit> source, int step, double pitch) {
            var result = new ArrayList<Beat.Hit>();
            for (var hit : source) {
                if (hit.track() == trackIndex && stepOf(hit.beat()) == step
                        && close(hit.pitch(), pitch)) continue;
                result.add(hit);
            }
            return List.copyOf(result);
        }

        private double xForStep(int step) { return KEY_W + step * STEP_W - horizontal.getValue(); }

        private double xForBeat(double beat) { return KEY_W + beat / Pattern.CELL * STEP_W - horizontal.getValue(); }

        private double yForPitch(double pitch) { return RULER_H + (MAX_PITCH - pitch) * ROW_H; }

        private int stepAt(double x) {
            if (x < KEY_W) return -1;
            int step = (int) Math.floor((x - KEY_W + horizontal.getValue()) / STEP_W);
            return step >= 0 && step < stepCount() ? step : -1;
        }

        private int pitchAt(double y) {
            if (y < RULER_H) return Integer.MIN_VALUE;
            int offset = MAX_PITCH - (int) Math.floor((y - RULER_H) / ROW_H);
            return offset < MIN_PITCH || offset > MAX_PITCH ? Integer.MIN_VALUE : offset;
        }

        private int midiFor(double pitch) {
            return Math.max(0, Math.min(127, noteBase + (int) Math.round(pitch)));
        }

        private static int stepOf(double beat) {
            return (int) Math.round(beat / Pattern.CELL);
        }

        private static boolean close(double a, double b) {
            return Math.abs(a - b) < .001;
        }

        private static boolean isBlack(int pitchClass) {
            return pitchClass == 1 || pitchClass == 3 || pitchClass == 6
                    || pitchClass == 8 || pitchClass == 10;
        }

        private static String noteName(int midi) {
            String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
            return names[Math.floorMod(midi, 12)] + (midi / 12 - 1);
        }
    }
}

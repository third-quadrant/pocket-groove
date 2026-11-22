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
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.TextAlignment;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Modeless-ish piano roll for one melody track; edits are applied to the shared project immediately. */
final class PianoRollDialog {
    static final double KEY_W = 124;
    static final double RULER_H = 26;
    static final double ROW_H = 15;

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
        base.setPrefWidth(66);
        base.setTooltip(new javafx.scene.control.Tooltip("이 악기의 기준 MIDI 음 (C4 = 60)"));
        var formant = new CheckBox("Formant 보존");
        formant.setSelected(track.formantPreserve());
        formant.setTooltip(new javafx.scene.control.Tooltip("피치를 올려도 악기 고유의 포먼트를 유지합니다."));
        base.valueProperty().addListener((o, a, value) -> {
            view.setBaseMidiNote(value);
            onSettings.accept(value, formant.isSelected());
        });
        formant.selectedProperty().addListener((o, a, value) ->
                onSettings.accept(base.getValue(), value));

        var delete = new Button("선택 삭제");
        delete.getStyleClass().add("action-button");
        delete.setOnAction(e -> view.deleteSelected());
        var close = new Button("닫기");
        close.getStyleClass().add("action-button");
        close.setOnAction(e -> ((Stage) close.getScene().getWindow()).close());
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var dot = new javafx.scene.shape.Circle(4);
        dot.setFill(DawStyle.track(trackIndex));
        var title = new Label(track.name());
        title.getStyleClass().add("value");
        var kind = new Label("MELODY");
        kind.getStyleClass().add("field-label");
        var baseLabel = new Label("BASE");
        baseLabel.getStyleClass().add("field-label");
        var lengthLabel = new Label("길이");
        lengthLabel.getStyleClass().add("field-label");
        var velocityLabel = new Label("세기");
        velocityLabel.getStyleClass().add("field-label");
        velocityValue.setMinWidth(34);
        var separator = new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL);
        var separator2 = new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL);

        var toolbar = new HBox(9, dot, title, kind, separator, baseLabel, base, formant,
                separator2, lengthLabel, lengthBox, velocityLabel, velocity, velocityValue,
                spacer, delete, close);
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
        var scene = new Scene(root, 1120, 720);
        scene.getStylesheets().add(PianoRollDialog.class.getResource("/drum.css").toExternalForm());
        var stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("음표 편집 — " + track.name());
        stage.setScene(scene);
        stage.setMinWidth(820);
        stage.setMinHeight(560);
        stage.show();
        view.requestFocus();
        return stage;
    }

    private static final class PianoRollView extends Region {
        private static final double MIN_STEP_WIDTH = 18;
        private static final double MAX_STEP_WIDTH = 64;
        private static final int MIN_PITCH = -Beat.MAX_NOTE_PITCH;
        private static final int MAX_PITCH = Beat.MAX_NOTE_PITCH;
        private static final int ROWS = MAX_PITCH - MIN_PITCH + 1;

        private final Canvas canvas = new Canvas();
        private final ScrollBar horizontal = new ScrollBar();
        private final ScrollBar vertical = new ScrollBar();
        private final Label footer = new Label();
        private final int trackIndex;
        private final double loopBeats;
        private List<Beat.Hit> hits;
        private Consumer<List<Beat.Hit>> onChange = ignored -> {};
        private double newLength = .25;
        private double newVelocity = .8;
        private double stepWidth = 24;
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
            getChildren().addAll(canvas, vertical, horizontal, footer);
            horizontal.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
            vertical.setOrientation(javafx.geometry.Orientation.VERTICAL);
            horizontal.valueProperty().addListener((o, a, b) -> draw());
            vertical.valueProperty().addListener((o, a, b) -> draw());
            footer.getStyleClass().addAll("dialog-note", "piano-footer");
            footer.setPadding(new Insets(0, 10, 0, 10));
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
            double footerH = 20, scroll = 13;
            double cw = Math.max(1, w - scroll);
            double ch = Math.max(1, h - footerH - scroll);
            canvas.setWidth(cw);
            canvas.setHeight(ch);
            canvas.relocate(0, 0);
            vertical.resizeRelocate(cw, 0, scroll, ch);
            horizontal.resizeRelocate(0, ch, cw, scroll);
            footer.resizeRelocate(0, h - footerH, w, footerH);
            updateBar();
            draw();
        }

        private void updateBar() {
            double available = Math.max(1, canvas.getWidth() - KEY_W);
            stepWidth = Math.max(MIN_STEP_WIDTH, Math.min(MAX_STEP_WIDTH, available / stepCount()));
            double total = stepCount() * stepWidth;
            double max = Math.max(0, total - available);
            horizontal.setMin(0);
            horizontal.setMax(max);
            horizontal.setVisibleAmount(available);
            horizontal.setBlockIncrement(Math.max(1, available * .8));
            horizontal.setValue(Math.max(0, Math.min(max, horizontal.getValue())));
            horizontal.setVisible(max > 0);

            double rowsHeight = Math.max(1, canvas.getHeight() - RULER_H);
            double rowMax = Math.max(0, ROWS * ROW_H - rowsHeight);
            vertical.setMin(0);
            vertical.setMax(rowMax);
            vertical.setVisibleAmount(rowsHeight);
            vertical.setBlockIncrement(Math.max(1, rowsHeight * .8));
            vertical.setValue(Math.max(0, Math.min(rowMax, vertical.getValue())));
            vertical.setVisible(rowMax > 0);
            if (!vertical.isVisible()) vertical.setValue(0);
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
            updateFooter();
        }

        private void updateFooter() {
            long count = hits.stream().filter(hit -> hit.track() == trackIndex).count();
            var hit = selectedHit();
            String info = hit == null
                    ? "음표 " + count + "개"
                    : noteName(midiFor(hit.pitch())) + " · " + lengthLabel(hit.length())
                            + " · " + Math.round(hit.velocity() * 100) + "%";
            footer.setText(info + "   ·   클릭: 추가 · 드래그: 이동 · 오른쪽 끝: 길이"
                    + " · Del/오른쪽 클릭: 삭제");
        }

        private Beat.Hit selectedHit() {
            if (selected == null) return null;
            return Pattern.notes(hits, trackIndex, selected.step()).stream()
                    .filter(hit -> close(hit.pitch(), selected.pitch()))
                    .findFirst().orElse(null);
        }

        private void drawRuler(GraphicsContext gc, double w) {
            gc.setFill(DawStyle.PANEL);
            gc.fillRect(0, 0, w, RULER_H);
            gc.setFill(DawStyle.LINE_STRONG);
            gc.fillRect(0, RULER_H - 1, w, 1);
            gc.setFont(DawStyle.display(9));
            gc.setFill(DawStyle.MUTED);
            gc.setTextAlign(TextAlignment.LEFT);
            for (int step = 0; step <= stepCount(); step++) {
                double x = xForStep(step);
                if (x < KEY_W || x > w) continue;
                boolean bar = step % 16 == 0;
                boolean beat = step % 4 == 0;
                if (bar) {
                    gc.setFill(DawStyle.alpha(DawStyle.ACCENT, .16));
                    gc.fillRoundRect(x + 2, 5, 24, 15, 3, 3);
                    gc.setFill(DawStyle.TEXT);
                    gc.fillText(Integer.toString(step / 16 + 1), x + 6, 16);
                } else {
                    gc.setFill(beat ? DawStyle.alpha(DawStyle.TEXT, .34)
                            : DawStyle.alpha(DawStyle.LINE, .75));
                    gc.fillRect(x, beat ? RULER_H - 9 : RULER_H - 5, 1,
                            beat ? 9 : 5);
                }
            }
            gc.setFill(DawStyle.alpha(DawStyle.BG, .98));
            gc.fillRect(0, 0, KEY_W, RULER_H);
            gc.setFill(DawStyle.LINE_STRONG);
            gc.fillRect(KEY_W - 1, 0, 1, RULER_H);
            gc.setFont(DawStyle.display(9));
            gc.setFill(DawStyle.MUTED);
            gc.fillText("PITCH", 10, 17);
        }

        private void drawKeys(GraphicsContext gc) {
            double h = canvas.getHeight();
            gc.save();
            gc.beginPath();
            gc.rect(0, RULER_H, KEY_W, Math.max(0, h - RULER_H));
            gc.clip();
            gc.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.web("#F1F3F5")), new Stop(1, Color.web("#C9CFD6"))));
            gc.fillRect(0, RULER_H, KEY_W, Math.max(0, h - RULER_H));
            gc.setFont(DawStyle.display(8));
            for (int offset = MAX_PITCH; offset >= MIN_PITCH; offset--) {
                double y = yForPitch(offset);
                if (y + ROW_H < RULER_H || y > h) continue;
                int midi = midiFor(offset);
                int pitchClass = Math.floorMod(midi, 12);
                boolean black = isBlack(pitchClass);
                if (hoverPitch == offset) {
                    gc.setFill(DawStyle.alpha(DawStyle.ACCENT, .15));
                    gc.fillRect(0, y, KEY_W, ROW_H - .5);
                }
                if (selected != null && close(selected.pitch(), offset)) {
                    gc.setFill(DawStyle.alpha(DawStyle.ACCENT, .10));
                    gc.fillRect(0, y, KEY_W, ROW_H - 1);
                }
                if (black) {
                    double blackWidth = KEY_W * .58;
                    gc.setFill(DawStyle.alpha(Color.BLACK, .28));
                    gc.fillRect(blackWidth, y + 1, 2, ROW_H - 2);
                    gc.setFill(Color.web("#171A1F"));
                    gc.fillRect(0, y, blackWidth, ROW_H - 1);
                    gc.setFill(Color.web("#30353C"));
                    gc.fillRect(0, y, blackWidth, 1);
                }
                if (!black) {
                    gc.setTextAlign(TextAlignment.RIGHT);
                    gc.setFill(offset == 0 ? DawStyle.ACCENT : Color.web("#3B4148"));
                    gc.fillText(noteName(midi), KEY_W - 9, y + ROW_H / 2 + 3);
                }
                if (offset == 0) {
                    gc.setFill(DawStyle.ACCENT);
                    gc.fillRect(KEY_W - 3, y + 1, 2, ROW_H - 2);
                }
                gc.setFill(DawStyle.alpha(Color.web("#AEB5BE"), .68));
                gc.fillRect(0, y + ROW_H - 1, KEY_W, 1);
            }
            gc.setTextAlign(TextAlignment.LEFT);
            gc.restore();
            gc.setFill(DawStyle.alpha(Color.BLACK, .30));
            gc.fillRect(KEY_W - 3, RULER_H, 3, Math.max(0, h - RULER_H));
            gc.setFill(DawStyle.LINE_STRONG);
            gc.fillRect(KEY_W - 1, RULER_H, 1, Math.max(0, h - RULER_H));
        }

        private void drawGrid(GraphicsContext gc, double w, double h) {
            gc.save();
            gc.beginPath();
            gc.rect(KEY_W, RULER_H, Math.max(0, w - KEY_W), Math.max(0, h - RULER_H));
            gc.clip();
            for (int step = 0; step <= stepCount(); step++) {
                double x = xForStep(step);
                if (x < KEY_W || x > w) continue;
                boolean bar = step % 16 == 0;
                boolean beat = step % 4 == 0;
                gc.setFill(bar ? DawStyle.alpha(DawStyle.TEXT, .17)
                        : beat ? DawStyle.alpha(DawStyle.LINE_STRONG, .95)
                        : DawStyle.alpha(DawStyle.LINE, .46));
                gc.fillRect(x, RULER_H, 1, h - RULER_H);
            }
            for (int bar = 0; bar < (int) Math.ceil(loopBeats / 4.0); bar++) {
                if (bar % 2 == 0) continue;
                double left = Math.max(KEY_W, xForStep(bar * 16));
                double right = Math.min(w, xForStep((bar + 1) * 16));
                if (right > left) {
                    gc.setFill(DawStyle.alpha(DawStyle.TEXT, .018));
                    gc.fillRect(left, RULER_H, right - left, h - RULER_H);
                }
            }
            gc.setFont(DawStyle.display(8));
            for (int offset = MAX_PITCH; offset >= MIN_PITCH; offset--) {
                double y = yForPitch(offset);
                if (y + ROW_H < RULER_H || y > h) continue;
                int midi = midiFor(offset);
                int pc = Math.floorMod(midi, 12);
                if (isBlack(pc)) {
                    gc.setFill(DawStyle.alpha(Color.BLACK, .20));
                    gc.fillRect(KEY_W, y, w - KEY_W, ROW_H);
                }
                gc.setFill(pc == 0 ? DawStyle.alpha(DawStyle.LINE_STRONG, .9)
                        : DawStyle.alpha(DawStyle.LINE, .34));
                gc.fillRect(KEY_W, y + ROW_H - .5, w - KEY_W, pc == 0 ? 1 : .5);
                if (pc == 0) {
                    gc.setFill(DawStyle.alpha(DawStyle.MUTED, .48));
                    gc.fillText(noteName(midi), KEY_W + 6, y + ROW_H - 2);
                }
                if (hoverPitch == offset) {
                    gc.setFill(DawStyle.alpha(DawStyle.ACCENT, .055));
                    gc.fillRect(KEY_W, y, w - KEY_W, ROW_H);
                }
                if (selected != null && close(selected.pitch(), offset)) {
                    gc.setFill(DawStyle.alpha(DawStyle.ACCENT, .032));
                    gc.fillRect(KEY_W, y, w - KEY_W, ROW_H);
                }
            }
            if (selected != null) {
                double x = xForStep(selected.step());
                gc.setFill(DawStyle.alpha(DawStyle.ACCENT, .035));
                gc.fillRect(x, RULER_H, stepWidth, h - RULER_H);
            }
            if (hoverStep >= 0 && hoverStep < stepCount()) {
                double x = xForStep(hoverStep);
                gc.setFill(DawStyle.alpha(DawStyle.TEXT, .045));
                gc.fillRect(x, RULER_H, stepWidth, h - RULER_H);
            }
            gc.restore();
            gc.setFill(DawStyle.LINE_STRONG);
            gc.fillRect(KEY_W - 1, RULER_H, 1, h - RULER_H);
        }

        private void drawNotes(GraphicsContext gc, double w) {
            Color color = DawStyle.track(trackIndex);
            gc.save();
            gc.beginPath();
            gc.rect(KEY_W, RULER_H, Math.max(0, w - KEY_W), Math.max(0, canvas.getHeight() - RULER_H));
            gc.clip();
            for (var hit : hits) {
                if (hit.track() != trackIndex) continue;
                double x = xForBeat(hit.beat());
                double width = Math.max(5, hit.length() * Pattern.STEPS_PER_BEAT * stepWidth);
                if (x + width < KEY_W || x > w) continue;
                double y = yForPitch(hit.pitch()) + 1.5;
                boolean isSelected = selected != null
                        && selected.step() == stepOf(hit.beat())
                        && close(selected.pitch(), hit.pitch());
                double opacity = .55 + hit.velocity() * .42;
                gc.setFill(DawStyle.alpha(Color.BLACK, .38));
                gc.fillRoundRect(Math.max(KEY_W, x) + 1, y + 2, width - 1, ROW_H - 3, 3, 3);
                gc.setFill(DawStyle.alpha(color, isSelected ? 1 : opacity));
                gc.fillRoundRect(Math.max(KEY_W, x), y, width - 1, ROW_H - 3, 3, 3);
                gc.setFill(DawStyle.alpha(Color.WHITE, .14));
                gc.fillRoundRect(Math.max(KEY_W, x) + 2, y + 1.5,
                        Math.max(1, width - 5), 2, 2, 2);
                if (isSelected) {
                    gc.setStroke(DawStyle.ACCENT);
                    gc.setLineWidth(1.5);
                    gc.strokeRoundRect(Math.max(KEY_W, x) + .5, y + .5,
                            width - 2, ROW_H - 4, 3, 3);
                    gc.setFill(DawStyle.ACCENT);
                    gc.fillRoundRect(Math.max(KEY_W, x) + width - 4, y + 3.5,
                            2.5, Math.max(1, ROW_H - 7), 1, 1);
                }
                if (width > 42) {
                    gc.setFont(DawStyle.display(8));
                    gc.setFill(Color.web("#141619"));
                    gc.setTextAlign(TextAlignment.LEFT);
                    gc.fillText(noteName(midiFor(hit.pitch())), Math.max(KEY_W, x) + 5,
                            y + ROW_H - 5);
                }
            }
            gc.restore();
        }

        private String lengthLabel(double beats) {
            if (Math.abs(beats - .25) < .001) return "1/16";
            if (Math.abs(beats - .5) < .001) return "1/8";
            if (Math.abs(beats - 1) < .001) return "1/4";
            if (Math.abs(beats - 2) < .001) return "1/2";
            if (Math.abs(beats - 4) < .001) return "1";
            return String.format(Locale.ROOT, "%.2f b", beats);
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
                        + hit.length() * Pattern.STEPS_PER_BEAT * stepWidth - 7;
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
                double raw = (e.getX() - xForBeat(drag.hit().beat())) / (stepWidth * 4.0);
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
                double right = left + Math.max(4, hit.length() * Pattern.STEPS_PER_BEAT * stepWidth);
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

        private double xForStep(int step) { return KEY_W + step * stepWidth - horizontal.getValue(); }

        private double xForBeat(double beat) { return KEY_W + beat / Pattern.CELL * stepWidth - horizontal.getValue(); }

        private double yForPitch(double pitch) {
            return RULER_H + (MAX_PITCH - pitch) * ROW_H - vertical.getValue();
        }

        private int stepAt(double x) {
            if (x < KEY_W) return -1;
            int step = (int) Math.floor((x - KEY_W + horizontal.getValue()) / stepWidth);
            return step >= 0 && step < stepCount() ? step : -1;
        }

        private int pitchAt(double y) {
            if (y < RULER_H) return Integer.MIN_VALUE;
            int offset = MAX_PITCH - (int) Math.floor((y - RULER_H + vertical.getValue()) / ROW_H);
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

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
                      BiConsumer<Integer, Boolean> onSettings,
                      BiConsumer<Double, Double> onPreview) {
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

        var base = new javafx.scene.control.TextField(NoteNames.name(track.baseMidiNote()));
        base.setId("piano-base");
        base.setPrefWidth(76);
        base.setTooltip(new javafx.scene.control.Tooltip(
                "기준 음이름 (C4 = MIDI 60, 예: C4, F#3, Bb5)"));
        var formant = new CheckBox("Formant 보존");
        formant.setSelected(track.formantPreserve());
        formant.setTooltip(new javafx.scene.control.Tooltip("피치를 올려도 악기 고유의 포먼트를 유지합니다."));
        int[] currentBase = {track.baseMidiNote()};
        Runnable commitBase = () -> {
            try {
                int midi = NoteNames.parse(base.getText());
                base.setText(NoteNames.name(midi));
                if (midi != currentBase[0]) {
                    currentBase[0] = midi;
                    view.setBaseMidiNote(midi);
                    onSettings.accept(midi, formant.isSelected());
                }
            } catch (IllegalArgumentException ex) {
                base.setText(NoteNames.name(currentBase[0]));
            }
        };
        base.setOnAction(e -> commitBase.run());
        base.focusedProperty().addListener((o, a, focused) -> {
            if (!focused) commitBase.run();
        });
        formant.selectedProperty().addListener((o, a, value) ->
                onSettings.accept(currentBase[0], value));

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
        view.setOnPreview(onPreview);

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
        private final List<Note> selection = new ArrayList<>();
        private Mode mode = Mode.NONE;
        private Note pressNote;
        private int pressStep = -1;
        private int pressPitch = Integer.MIN_VALUE;
        private double pressX;
        private double pressY;
        private List<Beat.Hit> dragFull = List.of();
        private List<Beat.Hit> dragNotes = List.of();
        private List<Note> dragSelection = List.of();
        private Beat.Hit resizeHit;
        private int rangeStep0 = -1;
        private int rangeStep1 = -1;
        private int rangePitch0 = Integer.MIN_VALUE;
        private int rangePitch1 = Integer.MIN_VALUE;
        private boolean rangeSet;
        private List<Clip> clipboard = List.of();
        private double clipboardPitch = 0;
        private String notice;
        private BiConsumer<Double, Double> onPreview = (pitch, velocity) -> {};
        private int hoverStep = -1;
        private int hoverPitch = Integer.MIN_VALUE;

        private enum Mode { NONE, MARQUEE, MOVE, RESIZE }

        private record Note(int step, double pitch) {}
        private record Clip(int step, double pitch, double velocity, double length) {}

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
            canvas.setOnMouseReleased(this::mouseReleased);
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
                } else if (e.isControlDown() && e.getCode() == KeyCode.C) {
                    copySelection();
                    e.consume();
                } else if (e.isControlDown() && e.getCode() == KeyCode.V) {
                    pasteSelection();
                    e.consume();
                } else if (e.getCode() == KeyCode.ESCAPE) {
                    selection.clear();
                    rangeSet = false;
                    draw();
                    e.consume();
                }
            });
            widthProperty().addListener((o, a, b) -> requestLayout());
            heightProperty().addListener((o, a, b) -> requestLayout());
        }

        void setOnChange(Consumer<List<Beat.Hit>> listener) {
            onChange = listener == null ? ignored -> {} : listener;
        }

        void setOnPreview(BiConsumer<Double, Double> listener) {
            onPreview = listener == null ? (pitch, velocity) -> {} : listener;
        }

        void setData(List<Beat.Hit> updated) {
            hits = List.copyOf(updated);
            selection.removeIf(note -> !hasNote(note.step(), note.pitch()));
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
            if (selection.isEmpty()) return;
            var updated = hits;
            for (var note : List.copyOf(selection)) {
                updated = Pattern.removeNote(updated, trackIndex, note.step(), note.pitch());
            }
            hits = updated;
            selection.clear();
            notice = "음표를 삭제했습니다.";
            onChange.accept(hits);
            draw();
        }

        private void copySelection() {
            if (selection.isEmpty()) {
                notice = "복사할 음표를 먼저 선택하세요.";
                draw();
                return;
            }
            double minPitch = selection.stream().mapToDouble(Note::pitch).min().orElse(0);
            int minStep = selection.stream().mapToInt(Note::step).min().orElse(0);
            var copied = new ArrayList<Clip>();
            for (var hit : hits) {
                if (hit.track() != trackIndex || !selected(hit)) continue;
                copied.add(new Clip(stepOf(hit.beat()) - minStep, hit.pitch() - minPitch,
                        hit.velocity(), hit.length()));
            }
            clipboard = List.copyOf(copied);
            clipboardPitch = minPitch;
            notice = copied.size() + "개 음표를 복사했습니다.";
            draw();
        }

        private void pasteSelection() {
            if (clipboard.isEmpty()) {
                notice = "복사한 음표가 없습니다.";
                draw();
                return;
            }
            int targetStep;
            double targetPitch;
            if (rangeSet) {
                targetStep = Math.min(rangeStep0, rangeStep1);
                targetPitch = Math.min(rangePitch0, rangePitch1);
            } else if (!selection.isEmpty()) {
                targetStep = selection.stream().mapToInt(Note::step).min().orElse(0);
                targetPitch = selection.stream().mapToDouble(Note::pitch).min().orElse(0);
            } else {
                targetStep = stepAfterLastNote();
                targetPitch = clipboardPitch;
            }
            int firstStep = clipboard.stream().mapToInt(Clip::step).min().orElse(0);
            int lastStep = 0;
            for (var clip : clipboard) {
                lastStep = Math.max(lastStep,
                        clip.step() + Math.max(1, (int) Math.ceil(clip.length() / Pattern.CELL)));
            }
            int span = lastStep - firstStep + 1;
            if (span > stepCount()) {
                notice = "패턴보다 긴 음표 묶음은 붙여넣을 수 없습니다.";
                draw();
                return;
            }
            targetStep = Math.max(0, Math.min(stepCount() - span, targetStep));
            double lowPitch = clipboard.stream().mapToDouble(Clip::pitch).min().orElse(0);
            double highPitch = clipboard.stream().mapToDouble(Clip::pitch).max().orElse(0);
            targetPitch = Math.max(MIN_PITCH - lowPitch, Math.min(MAX_PITCH - highPitch, targetPitch));

            var updated = hits;
            selection.clear();
            for (var clip : clipboard) {
                int step = targetStep + clip.step() - firstStep;
                double pitch = targetPitch + clip.pitch();
                updated = Pattern.addNote(updated, trackIndex, step, pitch,
                        clip.velocity(), clip.length());
                selection.add(new Note(step, pitch));
            }
            hits = updated;
            rangeSet = false;
            notice = clipboard.size() + "개 음표를 붙여넣었습니다.";
            onChange.accept(hits);
            draw();
        }

        private int stepAfterLastNote() {
            int last = 0;
            for (var hit : hits) {
                if (!visible(hit)) continue;
                last = Math.max(last, stepOf(hit.beat())
                        + Math.max(1, (int) Math.ceil(hit.length() / Pattern.CELL)));
            }
            return last;
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
            drawMarquee(gc, w, h);
            updateFooter();
        }

        private boolean visible(Beat.Hit hit) {
            return hit.track() == trackIndex && hit.beat() >= 0 && hit.beat() < loopBeats;
        }

        private void updateFooter() {
            long count = hits.stream().filter(this::visible).count();
            var hit = selectedHit();
            String info = notice != null ? notice
                    : selection.size() > 1 ? selection.size() + "개 선택"
                    : hit == null ? "음표 " + count + "개"
                    : NoteNames.name(midiFor(hit.pitch())) + " · " + lengthLabel(hit.length())
                            + " · " + Math.round(hit.velocity() * 100) + "%";
            footer.setText(info + "   ·   클릭: 추가/삭제 · 드래그: 선택 · 음표 드래그: 이동"
                    + " · 오른쪽 끝: 길이 · Ctrl+C/V · Del/오른쪽 클릭: 삭제");
        }

        private Beat.Hit selectedHit() {
            if (selection.size() != 1) return null;
            var note = selection.get(0);
            return Pattern.notes(hits, trackIndex, note.step()).stream()
                    .filter(hit -> close(hit.pitch(), note.pitch()))
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
                if (pitchSelected(offset)) {
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
                    gc.fillText(NoteNames.name(midi), KEY_W - 9, y + ROW_H / 2 + 3);
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
                    gc.fillText(NoteNames.name(midi), KEY_W + 6, y + ROW_H - 2);
                }
                if (hoverPitch == offset) {
                    gc.setFill(DawStyle.alpha(DawStyle.ACCENT, .055));
                    gc.fillRect(KEY_W, y, w - KEY_W, ROW_H);
                }
                if (pitchSelected(offset)) {
                    gc.setFill(DawStyle.alpha(DawStyle.ACCENT, .032));
                    gc.fillRect(KEY_W, y, w - KEY_W, ROW_H);
                }
            }
            for (int step : selectedSteps()) {
                double x = xForStep(step);
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
                if (!visible(hit)) continue;
                double x = xForBeat(hit.beat());
                double width = Math.max(5, hit.length() * Pattern.STEPS_PER_BEAT * stepWidth);
                if (x + width < KEY_W || x > w) continue;
                double y = yForPitch(hit.pitch()) + 1.5;
                boolean isSelected = selected(hit);
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
                    gc.fillText(NoteNames.name(midiFor(hit.pitch())), Math.max(KEY_W, x) + 5,
                            y + ROW_H - 5);
                }
            }
            gc.restore();
        }

        private void drawMarquee(GraphicsContext gc, double w, double h) {
            if (!rangeSet) return;
            double left = Math.max(KEY_W, xForStep(Math.min(rangeStep0, rangeStep1)));
            double right = Math.min(w, xForStep(Math.max(rangeStep0, rangeStep1) + 1));
            double top = Math.max(RULER_H, yForPitch(Math.max(rangePitch0, rangePitch1)));
            double bottom = Math.min(h, yForPitch(Math.min(rangePitch0, rangePitch1)) + ROW_H);
            if (right <= left || bottom <= top) return;
            gc.setFill(DawStyle.alpha(DawStyle.ACCENT, .09));
            gc.fillRect(left, top, right - left, bottom - top);
            gc.setStroke(DawStyle.alpha(DawStyle.ACCENT, .85));
            gc.setLineWidth(1);
            gc.setLineDashes(3, 3);
            gc.strokeRect(left + .5, top + .5, right - left - 1, bottom - top - 1);
            gc.setLineDashes();
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
                canvas.setCursor(pitch != Integer.MIN_VALUE && (step >= 0 || e.getX() < KEY_W)
                        ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);
                draw();
            }
        }

        private void mousePressed(javafx.scene.input.MouseEvent e) {
            requestFocus();
            if (e.getButton() != MouseButton.PRIMARY) return;
            notice = null;
            double x = e.getX(), y = e.getY();
            if (x < KEY_W) {
                int pitch = pitchAt(y);
                if (pitch != Integer.MIN_VALUE) onPreview.accept((double) pitch, 1.0);
                return;
            }
            int step = stepAt(x);
            int pitch = pitchAt(y);
            if (step < 0 || pitch == Integer.MIN_VALUE) return;
            pressX = x;
            pressY = y;
            pressStep = step;
            pressPitch = pitch;
            var hit = hitAt(x, y);
            if (hit != null) {
                pressNote = new Note(stepOf(hit.beat()), hit.pitch());
                if (!selected(pressNote.step(), pressNote.pitch())) {
                    selection.clear();
                    selection.add(pressNote);
                }
                boolean resize = x >= xForBeat(hit.beat())
                        + hit.length() * Pattern.STEPS_PER_BEAT * stepWidth - 7;
                dragFull = hits;
                dragNotes = hits.stream()
                        .filter(note -> note.track() == trackIndex && selected(note)).toList();
                dragSelection = List.copyOf(selection);
                resizeHit = hit;
                mode = resize ? Mode.RESIZE : Mode.MOVE;
            } else {
                pressNote = null;
                mode = Mode.MARQUEE;
                setRange(step, pitch);
                selection.clear();
            }
            draw();
        }

        private void mouseDragged(javafx.scene.input.MouseEvent e) {
            if (mode == Mode.NONE || !moved(e)) return;
            if (mode == Mode.MARQUEE) {
                rangeStep1 = clampedStep(e.getX());
                rangePitch1 = clampedPitch(e.getY());
                rangeSet = true;
                selectInRange();
                draw();
                return;
            }
            if (mode == Mode.RESIZE) {
                if (resizeHit == null) return;
                double raw = (e.getX() - xForBeat(resizeHit.beat())) / (stepWidth * 4.0);
                double length = Math.max(.25, Math.round(raw * 4) / 4.0);
                length = Math.min(length, loopBeats - resizeHit.beat());
                if (length < .01) return;
                hits = Pattern.updateNote(hits, trackIndex, stepOf(resizeHit.beat()),
                        resizeHit.pitch(), resizeHit.pitch(), resizeHit.velocity(), length);
                notice = "길이 " + lengthLabel(length);
                onChange.accept(hits);
                draw();
                return;
            }
            if (mode != Mode.MOVE || dragNotes.isEmpty()) return;
            int step = clampedStep(e.getX());
            int pitch = clampedPitch(e.getY());
            int dStep = step - pressStep;
            int dPitch = pitch - pressPitch;
            int minStep = Integer.MAX_VALUE, maxStep = Integer.MIN_VALUE;
            double minPitch = Double.MAX_VALUE, maxPitch = -Double.MAX_VALUE;
            for (var hit : dragNotes) {
                int hitStep = stepOf(hit.beat());
                minStep = Math.min(minStep, hitStep);
                maxStep = Math.max(maxStep, hitStep);
                minPitch = Math.min(minPitch, hit.pitch());
                maxPitch = Math.max(maxPitch, hit.pitch());
            }
            dStep = Math.max(-minStep, Math.min(stepCount() - 1 - maxStep, dStep));
            dPitch = (int) Math.max(Math.ceil(MIN_PITCH - minPitch),
                    Math.min(Math.floor(MAX_PITCH - maxPitch), dPitch));
            var updated = removeNotes(dragFull, dragSelection);
            selection.clear();
            for (var hit : dragNotes) {
                int nextStep = stepOf(hit.beat()) + dStep;
                double nextPitch = hit.pitch() + dPitch;
                updated = Pattern.addNote(updated, trackIndex, nextStep, nextPitch,
                        hit.velocity(), hit.length());
                selection.add(new Note(nextStep, nextPitch));
            }
            hits = updated;
            notice = selection.size() + "개 음표 이동";
            onChange.accept(hits);
            draw();
        }

        private void mouseReleased(javafx.scene.input.MouseEvent e) {
            if (e.getButton() != MouseButton.PRIMARY) {
                mode = Mode.NONE;
                return;
            }
            var released = mode;
            mode = Mode.NONE;
            if (released == Mode.MARQUEE && !moved(e)) {
                var existing = hitAtPitch(pressStep, pressPitch);
                if (existing != null) {
                    hits = removeNotes(hits, List.of(new Note(pressStep, pressPitch)));
                    selection.removeIf(note -> note.step() == pressStep
                            && close(note.pitch(), pressPitch));
                    notice = "음표를 삭제했습니다.";
                    onChange.accept(hits);
                } else {
                    double length = Math.min(newLength, loopBeats - Pattern.stepBeat(pressStep));
                    if (length >= .01) {
                        hits = Pattern.addNote(hits, trackIndex, pressStep, pressPitch,
                                newVelocity, length);
                        selection.clear();
                        selection.add(new Note(pressStep, pressPitch));
                        notice = "음표 추가 · " + NoteNames.name(midiFor(pressPitch));
                        onChange.accept(hits);
                        onPreview.accept((double) pressPitch, newVelocity);
                    }
                }
            } else if (released == Mode.MOVE && !moved(e) && pressNote != null) {
                hits = removeNotes(hits, List.of(pressNote));
                selection.removeIf(note -> note.step() == pressNote.step()
                        && close(note.pitch(), pressNote.pitch()));
                notice = "음표를 삭제했습니다.";
                onChange.accept(hits);
            }
            pressNote = null;
            dragFull = List.of();
            dragNotes = List.of();
            dragSelection = List.of();
            resizeHit = null;
            draw();
        }

        private boolean moved(javafx.scene.input.MouseEvent e) {
            return Math.abs(e.getX() - pressX) > 3 || Math.abs(e.getY() - pressY) > 3;
        }

        private void setRange(int step, int pitch) {
            rangeStep0 = step;
            rangeStep1 = step;
            rangePitch0 = pitch;
            rangePitch1 = pitch;
            rangeSet = true;
        }

        private void selectInRange() {
            int firstStep = Math.min(rangeStep0, rangeStep1);
            int lastStep = Math.max(rangeStep0, rangeStep1);
            int lowPitch = Math.min(rangePitch0, rangePitch1);
            int highPitch = Math.max(rangePitch0, rangePitch1);
            selection.clear();
            for (var hit : hits) {
                if (!visible(hit)) continue;
                int step = stepOf(hit.beat());
                double pitch = hit.pitch();
                if (step >= firstStep && step <= lastStep
                        && pitch >= lowPitch - 1e-9 && pitch <= highPitch + 1e-9) {
                    selection.add(new Note(step, pitch));
                }
            }
        }

        private boolean selected(Beat.Hit hit) {
            return selected(stepOf(hit.beat()), hit.pitch());
        }

        private boolean selected(int step, double pitch) {
            for (var note : selection) {
                if (note.step() == step && close(note.pitch(), pitch)) return true;
            }
            return false;
        }

        private boolean pitchSelected(double pitch) {
            for (var note : selection) if (close(note.pitch(), pitch)) return true;
            return false;
        }

        private List<Integer> selectedSteps() {
            var steps = new ArrayList<Integer>();
            for (var note : selection) if (!steps.contains(note.step())) steps.add(note.step());
            return steps;
        }

        private boolean hasNote(int step, double pitch) {
            for (var hit : hits) {
                if (visible(hit) && stepOf(hit.beat()) == step && close(hit.pitch(), pitch)) {
                    return true;
                }
            }
            return false;
        }

        private List<Beat.Hit> removeNotes(List<Beat.Hit> source, List<Note> notes) {
            var result = new ArrayList<Beat.Hit>();
            for (var hit : source) {
                boolean drop = hit.track() == trackIndex && notes.stream()
                        .anyMatch(note -> note.step() == stepOf(hit.beat())
                                && close(note.pitch(), hit.pitch()));
                if (!drop) result.add(hit);
            }
            return List.copyOf(result);
        }

        private Beat.Hit hitAt(double x, double y) {
            int pitch = pitchAt(y);
            if (pitch == Integer.MIN_VALUE) return null;
            for (var hit : hits) {
                if (!visible(hit) || !close(hit.pitch(), pitch)) continue;
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
            hits = Pattern.removeNote(hits, trackIndex, stepOf(hit.beat()), hit.pitch());
            selection.removeIf(note -> note.step() == stepOf(hit.beat())
                    && close(note.pitch(), hit.pitch()));
            notice = "음표를 삭제했습니다.";
            onChange.accept(hits);
            draw();
        }

        private int clampedStep(double x) {
            int step = (int) Math.floor((x - KEY_W + horizontal.getValue()) / stepWidth);
            return Math.max(0, Math.min(stepCount() - 1, step));
        }

        private int clampedPitch(double y) {
            int offset = MAX_PITCH - (int) Math.floor((y - RULER_H + vertical.getValue()) / ROW_H);
            return Math.max(MIN_PITCH, Math.min(MAX_PITCH, offset));
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
    }
}

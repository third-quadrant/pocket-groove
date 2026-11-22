package io.pocketgroove;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.io.IOException;
import java.util.Locale;

/** Shows the imported waveform and returns only the selected frame range. */
final class WaveformTrimDialog {
    private static final int WIDTH = 720;
    private static final int HEIGHT = 180;

    private WaveformTrimDialog() {}

    static Sounds.Pcm show(Window owner, Sounds.Pcm pcm, String name) {
        int frames = pcm.stereo().length / 2;
        int[] bounds = Sounds.silenceBounds(pcm);
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(owner);
        dialog.setTitle("소리 구간 선택");
        dialog.setHeaderText(name + " · 앞뒤 무음을 지우거나 양 끝을 드래그하세요.");
        dialog.getDialogPane().getStylesheets().add(
                WaveformTrimDialog.class.getResource("/drum.css").toExternalForm());
        dialog.getDialogPane().getButtonTypes().addAll(
                new ButtonType("이 구간 사용", ButtonType.OK.getButtonData()),
                ButtonType.CANCEL);

        var canvas = new Canvas(WIDTH, HEIGHT);
        var info = new Label();
        info.getStyleClass().add("value");
        var auto = new Button("앞뒤 무음 제거");
        auto.getStyleClass().add("action-button");
        var all = new Button("전체 선택");
        all.getStyleClass().add("action-button");
        var total = new Label(String.format(Locale.ROOT, "원본 %.2f초 · %d Hz",
                frames / (double) pcm.sampleRate(), pcm.sampleRate()));
        total.setId("trim-total");
        total.getStyleClass().add("dialog-note");
        var preview = new TrimPreview();
        var previewButton = new Button("미리 듣기");
        previewButton.setId("trim-preview");
        previewButton.getStyleClass().add("action-button");
        previewButton.setTooltip(new Tooltip("선택한 구간만 재생합니다."));
        Runnable stopPreview = () -> {
            preview.stop();
            previewButton.setText("미리 듣기");
        };
        previewButton.setOnAction(e -> {
            if (preview.isPlaying()) {
                stopPreview.run();
                return;
            }
            try {
                preview.play(Sounds.crop(pcm, bounds[0], bounds[1]),
                        () -> Platform.runLater(stopPreview));
                previewButton.setText("정지");
            } catch (Exception ex) {
                stopPreview.run();
                total.setText("미리 듣기를 열 수 없습니다. 다른 출력 장치를 사용 중인지 확인하세요.");
            }
        });
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var tools = new HBox(7, previewButton, auto, all, spacer, info);
        tools.setAlignment(Pos.CENTER_LEFT);
        boolean[] muted = {false};

        Runnable draw = () -> {
            var gc = canvas.getGraphicsContext2D();
            gc.setFill(DawStyle.PANEL);
            gc.fillRect(0, 0, WIDTH, HEIGHT);
            gc.setFill(DawStyle.alpha(DawStyle.ACCENT, .07));
            double x1 = bounds[0] * WIDTH / (double) frames;
            double x2 = bounds[1] * WIDTH / (double) frames;
            gc.fillRect(x1, 0, Math.max(1, x2 - x1), HEIGHT);
            gc.setFill(DawStyle.alpha(DawStyle.track(0), .9));
            int columns = WIDTH;
            for (int x = 0; x < columns; x++) {
                int from = (int) ((long) x * frames / columns);
                int to = Math.max(from + 1, (int) ((long) (x + 1) * frames / columns));
                float peak = 0;
                for (int i = from; i < Math.min(to, frames); i++) {
                    peak = Math.max(peak, Math.max(Math.abs(pcm.stereo()[i * 2]),
                            Math.abs(pcm.stereo()[i * 2 + 1])));
                }
                double height = Math.max(1, peak * (HEIGHT - 28));
                gc.fillRect(x, HEIGHT / 2.0 - height / 2, 1, height);
            }
            gc.setFill(DawStyle.LINE_STRONG);
            gc.fillRect(0, HEIGHT / 2.0, WIDTH, 1);
            for (int handle = 0; handle < 2; handle++) {
                double x = handle == 0 ? x1 : x2;
                gc.setStroke(DawStyle.ACCENT);
                gc.setLineWidth(2);
                gc.strokeLine(x, 0, x, HEIGHT);
                gc.setFill(DawStyle.ACCENT);
                gc.fillPolygon(new double[]{x - 5, x + 5, x},
                        new double[]{HEIGHT - 12, HEIGHT - 12, HEIGHT}, 3);
            }
            gc.setFill(DawStyle.MUTED);
            gc.setFont(DawStyle.display(10));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText("0.00 s", 6, HEIGHT - 5);
            gc.setTextAlign(TextAlignment.RIGHT);
            gc.fillText(String.format(Locale.ROOT, "%.2f s", frames / (double) pcm.sampleRate()),
                    WIDTH - 6, HEIGHT - 5);
            if (muted[0]) {
                gc.setFill(DawStyle.RECORD);
                gc.setTextAlign(TextAlignment.CENTER);
                gc.fillText("무음", WIDTH / 2.0, 16);
            }
            info.setText(String.format(Locale.ROOT, "%.3f – %.3f초 · %.3f초 사용",
                    bounds[0] / (double) pcm.sampleRate(), bounds[1] / (double) pcm.sampleRate(),
                    (bounds[1] - bounds[0]) / (double) pcm.sampleRate()));
            muted[0] = false;
        };
        draw.run();

        final int[] dragging = {-1};
        canvas.setOnMouseMoved(e -> {
            double x1 = bounds[0] * WIDTH / (double) frames, x2 = bounds[1] * WIDTH / (double) frames;
            canvas.setCursor(Math.min(Math.abs(e.getX() - x1), Math.abs(e.getX() - x2)) < 14
                    ? javafx.scene.Cursor.H_RESIZE : javafx.scene.Cursor.DEFAULT);
        });
        canvas.setOnMousePressed(e -> {
            double x1 = bounds[0] * WIDTH / (double) frames, x2 = bounds[1] * WIDTH / (double) frames;
            double toStart = Math.abs(e.getX() - x1), toEnd = Math.abs(e.getX() - x2);
            if (Math.min(toStart, toEnd) > 18) {
                dragging[0] = -1;
                return;
            }
            dragging[0] = toStart <= toEnd ? 0 : 1;
            stopPreview.run();
            dragTo(e.getX(), bounds, frames, dragging[0], muted, draw);
        });
        canvas.setOnMouseDragged(e -> {
            if (dragging[0] >= 0) dragTo(e.getX(), bounds, frames, dragging[0], muted, draw);
        });
        canvas.setOnMouseReleased(e -> dragging[0] = -1);
        auto.setOnAction(e -> {
            stopPreview.run();
            int[] silent = Sounds.silenceBounds(pcm);
            bounds[0] = silent[0];
            bounds[1] = silent[1];
            muted[0] = silent[0] == 0 && silent[1] == frames;
            draw.run();
        });
        all.setOnAction(e -> {
            stopPreview.run();
            bounds[0] = 0;
            bounds[1] = frames;
            draw.run();
        });

        var content = new VBox(9, canvas, tools, total);
        content.setPadding(new Insets(2, 0, 0, 0));
        content.setStyle("-fx-background-color: #0F1114;");
        dialog.getDialogPane().setContent(content);
        dialog.setOnHidden(e -> preview.stop());
        var result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        preview.stop();
        if (result == ButtonType.CANCEL) return null;
        if (bounds[0] <= 0 && bounds[1] >= frames) return pcm;
        return Sounds.crop(pcm, bounds[0], bounds[1]);
    }

    private static final class TrimPreview {
        private Clip clip;

        boolean isPlaying() {
            return clip != null && clip.isRunning();
        }

        void play(Sounds.Pcm pcm, Runnable onEnd) throws Exception {
            stop();
            byte[] bytes = Sounds.pcmBytes(pcm);
            var format = new AudioFormat(pcm.sampleRate(), 16, 2, true, false);
            var next = AudioSystem.getClip();
            next.open(format, bytes, 0, bytes.length);
            next.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) onEnd.run();
            });
            clip = next;
            next.start();
        }

        synchronized void stop() {
            if (clip == null) return;
            var old = clip;
            clip = null;
            old.stop();
            old.close();
        }
    }

    private static void dragTo(double x, int[] bounds, int frames, int handle,
                               boolean[] muted, Runnable draw) {
        int frame = (int) Math.max(0, Math.min(frames, Math.round(x * frames / WIDTH)));
        if (handle == 0) bounds[0] = Math.min(frame, bounds[1] - 1);
        else bounds[1] = Math.max(frame, bounds[0] + 1);
        muted[0] = false;
        draw.run();
    }
}

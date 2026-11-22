package io.pocketgroove;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "groove.uiTest", matches = "true")
class DrumAppSmokeTest {
    private static <T> T fx(java.util.concurrent.Callable<T> work) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try { result.complete(work.call()); }
            catch (Throwable e) { result.completeExceptionally(e); }
        });
        return result.get(25, TimeUnit.SECONDS);
    }

    @Test void dawWorkflowSupportsDynamicTracksMacroKeysStepsAndCompactLayout() throws Exception {
        Platform.startup(() -> {});
        Platform.setImplicitExit(false);
        var ready = new CompletableFuture<Void>();
        var app = new DrumApp();
        Stage stage = fx(() -> {
            var s = new Stage();
            app.start(s);
            ((Label) s.getScene().lookup("#status")).textProperty().addListener((o, a, b) -> {
                if (b.startsWith("비트를 만들 준비")) ready.complete(null);
            });
            return s;
        });
        try {
            ready.get(30, TimeUnit.SECONDS);
            fx(() -> {
                Scene scene = stage.getScene();
                assertEquals("KEY 1", ((Label) scene.lookup("#selected-key")).getText());
                assertEquals(List.of("새로 만들기", "열기", "저장", "다른 이름으로 저장", "내보내기"),
                        app.fileMenuLabelsForTest());
                assertEquals(7, ((ArrangementView) scene.lookup(".arrangement")).trackCount());
                assertEquals(16, ((StepSequencerView) scene.lookup(".step-sequencer")).stepCount());
                @SuppressWarnings("unchecked")
                var songBars = (Spinner<Integer>) scene.lookup("#song-bars");
                assertEquals(16, songBars.getValue());
                songBars.getValueFactory().setValue(20);
                assertEquals(20, app.currentProjectForTest().bars());
                ((Button) scene.lookup("#add-bar")).fire();
                assertEquals(21, app.currentProjectForTest().bars());

                ((Button) scene.lookup("#learn-key")).fire();
                Event.fireEvent(scene, pressed(KeyCode.A));
                assertEquals("KEY A", ((Label) scene.lookup("#selected-key")).getText());

                ((Button) scene.lookup("#transport-record")).fire();
                press(scene, KeyCode.A);
                release(scene, KeyCode.A);
                press(scene, KeyCode.A);
                release(scene, KeyCode.A);
                ((Button) scene.lookup("#transport-record")).fire();
                assertEquals(2, app.currentProjectForTest().hits().size(),
                        "recording should only replace the selected Kick track");

                app.selectTrackForTest(1);
                ((Button) scene.lookup("#transport-record")).fire();
                press(scene, KeyCode.DIGIT2);
                release(scene, KeyCode.DIGIT2);
                ((Button) scene.lookup("#transport-record")).fire();
                assertEquals(3, app.currentProjectForTest().hits().size(),
                        "the Kick recording should survive while Snare is recorded");
                return null;
            });

            // The macro keyboard path: a printable KEY_TYPED event with no physical key code.
            Thread.sleep(90);
            fx(() -> {
                var scene = stage.getScene();
                app.selectTrackForTest(2);
                ((Button) scene.lookup("#transport-record")).fire();
                Event.fireEvent(scene, typed("3"));
                ((Button) scene.lookup("#transport-record")).fire();
                assertEquals(4, app.currentProjectForTest().hits().size());
                ((Button) scene.lookup("#transport-stop")).fire();
                return null;
            });

            fx(() -> {
                var scene = stage.getScene();
                var sequencer = (StepSequencerView) scene.lookup(".step-sequencer");
                sequencer.requestFocus();
                int before = app.currentProjectForTest().hits().size();
                fireKey(sequencer, KeyCode.SPACE);
                for (int i = 0; i < 3; i++) {
                    fireKey(sequencer, KeyCode.RIGHT);
                    fireKey(sequencer, KeyCode.RIGHT);
                    fireKey(sequencer, KeyCode.RIGHT);
                    fireKey(sequencer, KeyCode.RIGHT);
                    fireKey(sequencer, KeyCode.SPACE);
                }
                int edited = app.currentProjectForTest().hits().size();
                assertTrue(edited > before);
                assertTrue(((Label) scene.lookup("#status")).getText().contains("스텝"));
                Event.fireEvent(scene, pressed(KeyCode.Z, true, false));
                assertEquals(before, app.currentProjectForTest().hits().size(),
                        "Ctrl+Z should undo the step edits");
                Event.fireEvent(scene, pressed(KeyCode.Z, true, true));
                assertEquals(edited, app.currentProjectForTest().hits().size(),
                        "Ctrl+Shift+Z should redo the step edits");
                fireKey(sequencer, KeyCode.UP, true);
                assertTrue(app.currentProjectForTest().hits().stream().anyMatch(hit -> hit.pitch() != 0),
                        "Shift+Up should give the selected step its own note pitch");
                ((Button) scene.lookup("#transport-play")).fire();
                return null;
            });
            Thread.sleep(600);
            fx(() -> {
                String now = ((Label) stage.getScene().lookup("#position")).getText();
                var playButton = (Button) stage.getScene().lookup("#transport-play");
                assertNotEquals("001 · 01 · 01", now,
                        "the transport position should follow playback; status="
                                + ((Label) stage.getScene().lookup("#status")).getText()
                                + " playDisabled=" + playButton.isDisabled());
                capture(stage.getScene(), "desktop");
                ((Button) stage.getScene().lookup("#transport-stop")).fire();
                return null;
            });

            fx(() -> {
                stage.setWidth(1060);
                stage.setHeight(720);
                return null;
            });
            Thread.sleep(220);
            fx(() -> {
                Scene scene = stage.getScene();
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                capture(scene, "compact");
                var menu = scene.lookup("#file-menu");
                assertTrue(menu.localToScene(menu.getBoundsInLocal()).getMaxX() <= scene.getWidth(),
                        "toolbar must fit compact window");
                var output = scene.lookup("#output-settings");
                assertTrue(output.localToScene(output.getBoundsInLocal()).getMaxX() <= scene.getWidth(),
                        "transport must fit compact window");
                var arrangement = (ArrangementView) scene.lookup(".arrangement");
                assertTrue(arrangement.getHeight() > 180,
                        "arrangement should remain usable at compact height");
                assertTrue(arrangement.horizontalMaxForTest() > 0,
                        "the timeline must be horizontally scrollable");
                assertTrue(arrangement.horizontalVisibleForTest() < arrangement.horizontalMaxForTest(),
                        "the horizontal scroll thumb must not fill the whole track");
                double beforeScroll = arrangement.horizontalValueForTest();
                arrangement.incrementHorizontalForTest();
                assertTrue(arrangement.horizontalValueForTest() > beforeScroll,
                        "the horizontal scrollbar must move");
                arrangement.scrollHorizontalForTest(arrangement.horizontalMaxForTest());
                return null;
            });

            Path sample = Path.of("target/ui-add-track.wav");
            try (var input = getClass().getResourceAsStream("/sounds/clap.wav")) {
                Files.copy(input, sample, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            fx(() -> { app.addTrackFromFile(sample); return null; });
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline) {
                int count = fx(() -> ((ArrangementView) stage.getScene().lookup(".arrangement")).trackCount());
                if (count == 8) break;
                Thread.sleep(60);
            }
            fx(() -> {
                var scene = stage.getScene();
                assertEquals(8, ((ArrangementView) scene.lookup(".arrangement")).trackCount());
                assertTrue(((Label) scene.lookup("#selected-track")).getText().contains("ui-add-track"));
                Event.fireEvent(scene, pressed(KeyCode.ESCAPE));
                assertEquals("KEY 1", ((Label) scene.lookup("#selected-key")).getText());
                app.removeTrackAt(7);
                return null;
            });
            deadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < deadline) {
                int count = fx(() -> ((ArrangementView) stage.getScene().lookup(".arrangement")).trackCount());
                if (count == 7) break;
                Thread.sleep(60);
            }
            fx(() -> {
                var scene = stage.getScene();
                assertEquals(7, ((ArrangementView) scene.lookup(".arrangement")).trackCount());
                assertEquals("KEY 7", ((Label) scene.lookup("#selected-key")).getText());
                assertTrue(((Label) scene.lookup("#selected-track")).getText().contains("Percussion"));
                app.selectTrackForTest(0);
                @SuppressWarnings("unchecked")
                var trackPitch = (Spinner<Integer>) scene.lookup("#track-pitch");
                trackPitch.getValueFactory().setValue(7);
                assertEquals(7, app.currentProjectForTest().tracks().get(0).pitch());
                app.setPitchModeForTest(0, Beat.PitchMode.PITCH_SHIFT);
                assertEquals(Beat.PitchMode.PITCH_SHIFT,
                        app.currentProjectForTest().tracks().get(0).pitchMode());
                return null;
            });

            Path saved = Path.of("target/ui-save.mdrum");
            Files.deleteIfExists(saved);
            fx(() -> { app.saveProjectToForTest(saved); return null; });
            long saveDeadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < saveDeadline
                    && (app.currentFileForTest() == null || app.busyForTest() || !Files.exists(saved))) {
                Thread.sleep(60);
            }
            final int[] expectedHits = new int[1];
            fx(() -> {
                var scene = stage.getScene();
                var sequencer = (StepSequencerView) scene.lookup(".step-sequencer");
                sequencer.requestFocus();
                fireKey(sequencer, KeyCode.RIGHT);
                int before = app.currentProjectForTest().hits().size();
                fireKey(sequencer, KeyCode.SPACE);
                expectedHits[0] = before + 1;
                assertEquals(expectedHits[0], app.currentProjectForTest().hits().size());
                app.saveProjectToForTest(saved);
                return null;
            });
            saveDeadline = System.nanoTime() + 20_000_000_000L;
            while (System.nanoTime() < saveDeadline) {
                try {
                    if (ProjectIO.load(saved).hits().size() == expectedHits[0]) break;
                } catch (Exception ignored) {
                }
                Thread.sleep(60);
            }
            String saveStatus = fx(() -> ((Label) stage.getScene().lookup("#status")).getText());
            assertEquals(expectedHits[0], ProjectIO.load(saved).hits().size(),
                    "Ctrl+S path should overwrite the current .mdrum file without a chooser; status="
                            + saveStatus + " file=" + app.currentFileForTest());

            fx(() -> {
                float[] waveform = new float[44100 * 2 / 2];
                for (int i = 0; i < waveform.length / 2; i++) {
                    float envelope = i > 8000 && i < 15000 ? 1f : 0f;
                    waveform[i * 2] = (float) Math.sin(i * .08) * .4f * envelope;
                    waveform[i * 2 + 1] = waveform[i * 2];
                }
                var pcm = new Sounds.Pcm(waveform, 44100);
                var previewState = new String[1];
                var pause = new javafx.animation.PauseTransition(
                        javafx.util.Duration.millis(900));
                pause.setOnFinished(e -> {
                    for (var window : List.copyOf(javafx.stage.Window.getWindows())) {
                        if (window.isShowing() && window != stage) {
                            var preview = (Button) window.getScene().lookup("#trim-preview");
                            assertNotNull(preview, "trim preview button");
                            preview.fire();
                            previewState[0] = preview.getText();
                            var hide = new javafx.animation.PauseTransition(
                                    javafx.util.Duration.millis(250));
                            hide.setOnFinished(x -> {
                                try {
                                    capture(window.getScene(), "trim");
                                } catch (Exception ignored) {
                                }
                                window.hide();
                            });
                            hide.play();
                        }
                    }
                });
                pause.play();
                assertNull(WaveformTrimDialog.show(stage, pcm, "trim-test"));
                assertEquals("정지", previewState[0], "trim preview should start playback");
                return null;
            });

            fx(() -> {
                var values = new String[4];
                var pause = new javafx.animation.PauseTransition(
                        javafx.util.Duration.millis(700));
                pause.setOnFinished(e -> {
                    for (var window : List.copyOf(javafx.stage.Window.getWindows())) {
                        if (window.isShowing() && window != stage) {
                            values[0] = ((javafx.scene.control.TextField)
                                    window.getScene().lookup("#export-name")).getText();
                            values[1] = ((javafx.scene.control.TextField)
                                    window.getScene().lookup("#export-range")).getText();
                            values[2] = String.valueOf(((javafx.scene.control.ComboBox<?>)
                                    window.getScene().lookup("#export-format")).getValue());
                            values[3] = ((javafx.scene.control.TextField)
                                    window.getScene().lookup("#export-folder")).getText();
                            try {
                                capture(window.getScene(), "export");
                            } catch (Exception ignored) {
                            }
                            window.hide();
                        }
                    }
                });
                pause.play();
                Path exportFolder = Path.of("target");
                assertNull(Mp3ExportDialog.show(stage, "ui-save",
                        app.currentProjectForTest().bars(), 100, exportFolder));
                assertEquals("ui-save", values[0]);
                assertEquals("전체", values[1]);
                assertEquals("WAV", values[2]);
                assertEquals(exportFolder.toAbsolutePath().normalize().toString(), values[3]);
                return null;
            });
        } finally {
            fx(() -> { app.stop(); stage.hide(); return null; });
            Platform.exit();
        }
    }

    private static void fireKey(javafx.scene.Node target, KeyCode code) {
        fireKey(target, code, false);
    }

    private static void fireKey(javafx.scene.Node target, KeyCode code, boolean shift) {
        Event.fireEvent(target, pressed(code, false, shift));
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, shift, false, false, false));
    }

    private static void press(Scene scene, KeyCode code) { Event.fireEvent(scene, pressed(code)); }
    private static void release(Scene scene, KeyCode code) { Event.fireEvent(scene, released(code)); }

    private static KeyEvent pressed(KeyCode code) {
        return pressed(code, false, false);
    }

    private static KeyEvent pressed(KeyCode code, boolean control, boolean shift) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false);
    }

    private static KeyEvent released(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false);
    }

    private static KeyEvent typed(String text) {
        return new KeyEvent(KeyEvent.KEY_TYPED, text, "", KeyCode.UNDEFINED, false, false, false, false);
    }

    private static void capture(Scene scene, String name) throws Exception {
        var image = scene.snapshot(null);
        var buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        Files.createDirectories(Path.of("target/screenshots"));
        ImageIO.write(buffered, "png", Path.of("target/screenshots/" + name + ".png").toFile());
    }
}

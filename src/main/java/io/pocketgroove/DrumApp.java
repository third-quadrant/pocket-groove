package io.pocketgroove;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DrumApp extends Application {
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "groove-files");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<KeyCode> held = EnumSet.noneOf(KeyCode.class);

    private volatile AudioEngine audio;
    private List<Beat.Track> tracks = List.of();
    private List<Beat.Hit> hits = List.of();
    private List<AudioEngine.Device> devices = List.of();
    private int selectedTrack;
    private int selectedStep;
    private double length = 4;
    private int projectBars = 16;
    private Path currentFile;

    private Stage stage;
    private Scene scene;
    private VBox root;
    private ArrangementView arrangement;
    private StepSequencerView sequencer;
    private Label status;
    private Label fileLabel;
    private Label position;
    private Label selected;
    private Label keyChip;
    private Label inputState;
    private Label masterValue;
    private Label audioDescription;
    private Button record;
    private Button play;
    private Button stop;
    private Button learn;
    private Button changeSound;
    private Button addTrack;
    private Button removeTrack;
    private Button addBar;
    private MenuButton fileMenu;
    private Button undoButton;
    private Button redoButton;
    private Button outputSettings;
    private Button zoomIn;
    private Button zoomOut;
    private Spinner<Integer> bpm;
    private Spinner<Integer> pitch;
    private Spinner<Integer> trackPitch;
    private Spinner<Integer> songBars;
    private Slider master;
    private ComboBox<Integer> loopBars;

    private final Deque<History> undoStack = new ArrayDeque<>();
    private final Deque<History> redoStack = new ArrayDeque<>();
    private static final int HISTORY_LIMIT = 64;
    private String lastEditKey;
    private long lastEditAt;
    private boolean restoringHistory;

    private boolean busy;
    private boolean recording;
    private boolean playing;
    private boolean dirty;
    private boolean restoring = true;
    private boolean learning;
    private boolean closed;
    private boolean focused;
    private int selectedDeviceId = -1;
    private boolean selectedExclusive;
    private int selectedFrames = 256;
    private long lastPoll;
    private long lastKeyAt;
    private AnimationTimer animation;
    private PauseTransition inputHint;
    private PauseTransition hitFlash;
    private PauseTransition loopSyncDelay;

    @Override public void start(Stage stage) {
        this.stage = stage;
        javafx.scene.text.Font.loadFont(getClass().getResourceAsStream("/fonts/Barlow-SemiBold.ttf"), 14);
        javafx.scene.text.Font.loadFont(getClass().getResourceAsStream("/fonts/NanumGothic-Regular.ttf"), 13);

        arrangement = new ArrangementView();
        arrangement.setOnTrackSelected(this::selectTrack);
        arrangement.setOnAudition(this::hit);
        arrangement.setOnPitchModeChanged(this::changeTrackPitchMode);
        arrangement.setOnEditBeat(beat -> selectStep((int) Math.round(beat * Pattern.STEPS_PER_BEAT)));
        sequencer = new StepSequencerView();
        sequencer.setHandlers(this::patternEdited, this::selectStepFromSequencer);

        var top = new VBox(buildTransport(), buildToolbar());
        top.getStyleClass().add("top-shell");
        var workspace = new VBox(arrangement, sequencer);
        workspace.getStyleClass().add("workspace");
        VBox.setVgrow(arrangement, Priority.ALWAYS);
        arrangement.setMaxHeight(Double.MAX_VALUE);
        var footer = buildFooter();

        var border = new BorderPane();
        border.setTop(top);
        border.setCenter(workspace);
        border.setBottom(footer);
        border.getStyleClass().add("app");
        root = new VBox(border);
        VBox.setVgrow(border, Priority.ALWAYS);
        border.setMaxHeight(Double.MAX_VALUE);
        root.getStyleClass().add("app-root");

        scene = new Scene(root, 1440, 900);
        scene.getStylesheets().add(getClass().getResource("/drum.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        scene.addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> held.remove(e.getCode()));

        stage.setTitle("Pocket Groove");
        stage.setScene(scene);
        stage.setMinWidth(1060);
        stage.setMinHeight(720);
        stage.setOnCloseRequest(e -> {
            if (busy || !confirmDiscard()) e.consume();
        });
        stage.show();
        var visual = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(stage.getWidth(), visual.getWidth() - 24));
        stage.setHeight(Math.min(stage.getHeight(), visual.getHeight() - 24));
        stage.centerOnScreen();

        inputHint = new PauseTransition(Duration.seconds(2.4));
        inputHint.setOnFinished(e -> resetInputHint());
        hitFlash = new PauseTransition(Duration.millis(95));
        hitFlash.setOnFinished(e -> arrangement.clearFlash());
        loopSyncDelay = new PauseTransition(Duration.millis(45));
        loopSyncDelay.setOnFinished(e -> syncLoopNow());

        stage.focusedProperty().addListener((o, a, nowFocused) -> {
            focused = nowFocused;
            held.clear();
            if (!nowFocused) {
                cancelLearning();
                inputState.setText("포커스 없음 · 창을 클릭하세요");
                inputState.getStyleClass().setAll("input-state", "idle");
            } else {
                inputState.setText("입력 대기 · 숫자패드와 문자 입력 지원");
                inputState.getStyleClass().setAll("input-state", "idle");
            }
        });
        focused = stage.isFocused();
        inputState.setText(focused ? "입력 대기 · 숫자패드와 문자 입력 지원" : "포커스 없음 · 창을 클릭하세요");

        animation = new AnimationTimer() {
            @Override public void handle(long now) {
                if (audio != null && now - lastPoll > 40_000_000L) {
                    lastPoll = now;
                    pollAudio();
                }
            }
        };
        animation.start();

        FileAssociations.registerIfNeededAsync();
        var startupFile = startupProjectFile();
        clearHistory();
        if (startupFile != null) {
            loadAsync(() -> ProjectIO.load(startupFile), false, startupFile, () -> {
                clearHistory();
                status.setText("프로젝트를 열었습니다 · " + startupFile.getFileName());
                resetInputHint();
            });
        } else {
            loadAsync(this::blankProject, false, null, () -> {
                clearHistory();
                status.setText("비트를 만들 준비가 됐습니다 · 스텝을 클릭하거나 REC로 녹음하세요");
                resetInputHint();
            });
        }
    }

    private Path startupProjectFile() {
        try {
            var parameters = getParameters();
            return parameters == null ? null : FileAssociations.startupFile(parameters.getRaw());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private HBox buildTransport() {
        Label brand = new Label("POCKET GROOVE");
        brand.getStyleClass().add("brand");
        Label sub = new Label("DRUM WORKSTATION");
        sub.getStyleClass().add("brand-sub");
        VBox brandBox = new VBox(0, brand, sub);
        brandBox.setAlignment(Pos.CENTER_LEFT);
        brandBox.setMinWidth(152);

        record = new Button("REC");
        record.setGraphic(new Circle(3.5, DawStyle.RECORD));
        record.setId("transport-record");
        record.getStyleClass().addAll("transport-button", "record-button");
        record.setAccessibleText("녹음");
        record.setTooltip(new Tooltip("녹음 시작/끝내기"));
        record.setOnAction(e -> toggleRecording());

        play = iconButton("transport-play", new Polygon(0, 0, 11, 7, 0, 14), "재생");
        play.setOnAction(e -> startPlaying());
        stop = iconButton("transport-stop", new Rectangle(9, 9), "정지");
        stop.setOnAction(e -> stopEverything());

        var transport = new HBox(5, record, play, stop);
        transport.setAlignment(Pos.CENTER);
        transport.getStyleClass().add("transport-buttons");

        position = new Label("001 · 01 · 01");
        position.setId("position");
        position.getStyleClass().add("timecode");
        var positionBox = field("POSITION", position);
        positionBox.setMinWidth(122);

        bpm = new Spinner<>(40, 240, 100);
        bpm.setId("bpm");
        bpm.setEditable(true);
        bpm.setPrefWidth(84);
        bpm.setMaxWidth(84);
        bpm.setAccessibleText("템포 BPM");
        configureSpinnerEditor(bpm);
        bpm.valueProperty().addListener((o, a, b) -> {
            if (restoring) return;
            remember(new Beat.Project(tracks, hits, length, a,
                            pitch == null ? 0 : pitch.getValue(), projectBars),
                    selectedTrack, selectedStep, "bpm");
            if (audio != null) audio.tempo(b);
            markDirty();
        });
        var bpmBox = field("BPM", bpm);
        bpmBox.setMinWidth(78);

        Label signature = new Label("4 / 4");
        signature.getStyleClass().add("signature");
        var signatureBox = field("SIGNATURE", signature);
        signatureBox.setMinWidth(54);

        loopBars = new ComboBox<>();
        loopBars.setId("loop-bars");
        loopBars.getItems().addAll(1, 2, 4, 8);
        loopBars.setValue(1);
        loopBars.setPrefWidth(88);
        loopBars.setConverter(new javafx.util.StringConverter<>() {
            @Override public Integer fromString(String text) { return Integer.valueOf(text.replace(" BAR", "")); }
            @Override public String toString(Integer value) { return value == null ? "" : value + " BAR"; }
        });
        loopBars.setAccessibleText("루프 길이");
        loopBars.valueProperty().addListener((o, a, bars) -> {
            if (!restoring && bars != null) changeLoopLength(bars);
        });
        var loopBox = field("LOOP", loopBars);
        loopBox.setMinWidth(82);

        songBars = new Spinner<>(1, 512, 16);
        songBars.setId("song-bars");
        songBars.setEditable(true);
        songBars.setPrefWidth(70);
        songBars.setMaxWidth(70);
        songBars.setAccessibleText("전체 마디 수");
        songBars.setTooltip(new Tooltip("곡 전체 길이입니다. 늘리면 기본 패턴이 그대로 반복됩니다."));
        configureSpinnerEditor(songBars);
        songBars.valueProperty().addListener((o, a, bars) -> {
            if (!restoring && bars != null) changeSongBars(bars);
        });

        master = new Slider(0, 1, .75);
        master.setId("master");
        master.setPrefWidth(78);
        master.setMaxWidth(78);
        master.setAccessibleText("출력 음량");
        masterValue = new Label("75%");
        masterValue.getStyleClass().add("value");
        master.valueProperty().addListener((o, a, volume) -> {
            masterValue.setText(Math.round(volume.doubleValue() * 100) + "%");
            if (audio != null) audio.parameters(volume.doubleValue(), pitch == null ? 0 : pitch.getValue());
        });
        var masterBox = field("MASTER", new HBox(6, master, masterValue));
        masterBox.setMinWidth(104);

        pitch = new Spinner<>(-12, 12, 0);
        pitch.setId("pitch");
        pitch.setEditable(true);
        pitch.setPrefWidth(72);
        pitch.setMaxWidth(72);
        pitch.setAccessibleText("피치 반음");
        configureSpinnerEditor(pitch);
        pitch.valueProperty().addListener((o, a, value) -> {
            if (restoring) return;
            remember(new Beat.Project(tracks, hits, length, bpm.getValue(), a, projectBars),
                    selectedTrack, selectedStep, "master-pitch");
            if (audio != null) audio.parameters(master.getValue(), value);
            markDirty();
        });
        var pitchBox = field("PITCH", pitch);
        pitchBox.setMinWidth(72);

        outputSettings = new Button("OUTPUT");
        outputSettings.setId("output-settings");
        outputSettings.getStyleClass().addAll("action-button", "output-button");
        outputSettings.setMinWidth(58);
        outputSettings.setAccessibleText("오디오 출력 설정");
        outputSettings.setTooltip(new Tooltip("오디오 출력 장치와 지연 설정"));
        outputSettings.setOnAction(e -> showOutputSettings());

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var row = new HBox(8, brandBox, transport, positionBox, bpmBox, signatureBox,
                loopBox, spacer, masterBox, pitchBox, outputSettings);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("transport");
        return row;
    }

    private HBox buildToolbar() {
        var menuNew = new MenuItem("새로 만들기");
        var menuOpen = new MenuItem("열기");
        var menuSave = new MenuItem("저장");
        var menuSaveAs = new MenuItem("다른 이름으로 저장");
        var menuExport = new MenuItem("내보내기");
        menuNew.setOnAction(e -> newProject());
        menuOpen.setOnAction(e -> openProject());
        menuSave.setOnAction(e -> saveProject());
        menuSaveAs.setOnAction(e -> saveProjectAs());
        menuExport.setOnAction(e -> exportMp3());
        fileMenu = new MenuButton("☰");
        fileMenu.setId("file-menu");
        fileMenu.getStyleClass().add("file-menu");
        fileMenu.setTooltip(new Tooltip("새로 만들기 · 열기 · 저장 · 내보내기"));
        fileMenu.setAccessibleText("파일 메뉴");
        fileMenu.getItems().addAll(menuNew, menuOpen, new SeparatorMenuItem(),
                menuSave, menuSaveAs, new SeparatorMenuItem(), menuExport);

        undoButton = actionButton("↶", "undo", this::undo);
        redoButton = actionButton("↷", "redo", this::redo);
        undoButton.getStyleClass().add("small-action");
        redoButton.getStyleClass().add("small-action");
        undoButton.setTooltip(new Tooltip("실행 취소 · Ctrl+Z"));
        redoButton.setTooltip(new Tooltip("다시 실행 · Ctrl+Shift+Z"));
        undoButton.setAccessibleText("실행 취소");
        redoButton.setAccessibleText("다시 실행");

        selected = new Label("—");
        selected.setId("selected-track");
        selected.getStyleClass().add("selected-track");
        selected.setMinWidth(112);
        selected.setMaxWidth(185);
        keyChip = new Label("KEY —");
        keyChip.setId("selected-key");
        keyChip.getStyleClass().add("key-chip");

        trackPitch = new Spinner<>(-12, 12, 0);
        trackPitch.setId("track-pitch");
        trackPitch.setEditable(true);
        trackPitch.setPrefWidth(66);
        trackPitch.setMaxWidth(66);
        trackPitch.setAccessibleText("선택 트랙 피치 반음");
        trackPitch.setTooltip(new Tooltip("선택한 트랙만 피치를 바꿉니다."));
        configureSpinnerEditor(trackPitch);
        trackPitch.valueProperty().addListener((o, a, value) -> {
            if (restoring || tracks.isEmpty()) return;
            remember(new Beat.Project(tracks, hits, length, bpm.getValue(), pitch.getValue(), projectBars),
                    selectedTrack, selectedStep, "track-pitch");
            changeTrackPitch(selectedTrack, value);
        });
        Label trackPitchLabel = new Label("TRACK PITCH");
        trackPitchLabel.getStyleClass().add("field-label");

        learn = actionButton("키 지정", "learn-key", this::startLearning);
        changeSound = actionButton("소리 변경", "change-sound", this::changeSound);
        learn.getStyleClass().add("selected-action");
        changeSound.getStyleClass().add("selected-action");
        addTrack = actionButton("+ 트랙", "add-track", this::addTrack);
        removeTrack = actionButton("− 트랙", "remove-track", this::removeTrack);
        addBar = actionButton("+ 바", "add-bar", () -> changeSongBars(projectBars + 1));
        addBar.setTooltip(new Tooltip("마디를 하나 늘립니다. 현재 패턴이 그대로 복사됩니다."));

        zoomOut = actionButton("−", "zoom-out", () -> arrangement.zoomOut());
        zoomIn = actionButton("+", "zoom-in", () -> arrangement.zoomIn());
        zoomOut.getStyleClass().add("small-action");
        zoomIn.getStyleClass().add("small-action");
        Label zoomLabel = new Label("ZOOM");
        zoomLabel.getStyleClass().add("field-label");
        Label songLabel = new Label("BARS");
        songLabel.getStyleClass().add("field-label");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var row = new HBox(5, fileMenu, undoButton, redoButton,
                sep(), selected, keyChip, trackPitchLabel, trackPitch, learn, changeSound,
                sep(), addTrack, removeTrack, songLabel, songBars, addBar,
                spacer, zoomLabel, zoomOut, zoomIn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("toolbar");
        return row;
    }

    private HBox buildFooter() {
        status = new Label("오디오와 데모 사운드를 준비하는 중입니다…");
        status.setId("status");
        status.getStyleClass().add("status");
        status.setMinWidth(0);
        HBox.setHgrow(status, Priority.ALWAYS);
        fileLabel = new Label("UNTITLED");
        fileLabel.getStyleClass().add("file-name");
        fileLabel.setMaxWidth(220);
        audioDescription = new Label("WASAPI");
        audioDescription.getStyleClass().add("audio-summary");
        audioDescription.setMaxWidth(280);
        inputState = new Label("입력 대기");
        inputState.setId("input-state");
        inputState.getStyleClass().addAll("input-state", "idle");
        inputState.setMinWidth(0);
        var row = new HBox(14, status, fileLabel, audioDescription, inputState);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("footer");
        return row;
    }

    private VBox field(String caption, Node control) {
        var label = new Label(caption);
        label.getStyleClass().add("field-label");
        var box = new VBox(2, label, control);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Button iconButton(String id, Shape shape, String accessible) {
        shape.setFill(DawStyle.TEXT);
        var button = new Button();
        button.setGraphic(shape);
        button.setId(id);
        button.getStyleClass().addAll("transport-button", "icon-button");
        button.setAccessibleText(accessible);
        button.setTooltip(new Tooltip(accessible));
        return button;
    }

    private Button actionButton(String text, String id, Runnable action) {
        var button = new Button(text);
        button.setId(id);
        button.getStyleClass().add("action-button");
        button.setOnAction(e -> action.run());
        return button;
    }

    private Separator sep() {
        var separator = new Separator(Orientation.VERTICAL);
        separator.getStyleClass().add("tool-separator");
        return separator;
    }

    private void configureSpinnerEditor(Spinner<Integer> spinner) {
        spinner.getEditor().setOnAction(e -> commitSpinner(spinner));
        spinner.getEditor().focusedProperty().addListener((o, a, focusedNow) -> {
            if (!focusedNow) commitSpinner(spinner);
        });
    }

    private void commitSpinner(Spinner<Integer> spinner) {
        try {
            int value = Integer.parseInt(spinner.getEditor().getText());
            if (spinner.getValueFactory() instanceof javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory factory) {
                value = Math.max(factory.getMin(), Math.min(factory.getMax(), value));
            }
            spinner.getValueFactory().setValue(value);
            spinner.getEditor().setText(Integer.toString(value));
        } catch (RuntimeException e) {
            spinner.getEditor().setText(Integer.toString(spinner.getValue()));
        }
    }

    private void selectTrack(int index) {
        if (tracks.isEmpty()) return;
        selectedTrack = Math.max(0, Math.min(tracks.size() - 1, index));
        arrangement.setSelectedTrack(selectedTrack);
        updateTrackHeader();
        updateTrackPitchEditor();
        sequencer.setData(selectedTrack, tracks.get(selectedTrack), hits, length);
        sequencer.setSelectedStep(selectedStep);
        syncControlState();
    }

    void selectTrackForTest(int index) { selectTrack(index); }

    void setPitchModeForTest(int index, Beat.PitchMode mode) { changeTrackPitchMode(index, mode); }

    Beat.Project currentProjectForTest() { return projectSnapshot(); }

    List<String> fileMenuLabelsForTest() {
        return fileMenu.getItems().stream().map(MenuItem::getText).filter(text -> text != null).toList();
    }

    void saveProjectToForTest(Path path) { saveTo(path, true); }

    Path currentFileForTest() { return currentFile; }

    boolean busyForTest() { return busy; }

    private void updateTrackPitchEditor() {
        if (trackPitch == null || tracks.isEmpty()) return;
        boolean wasRestoring = restoring;
        restoring = true;
        trackPitch.getValueFactory().setValue((int) Math.round(tracks.get(selectedTrack).pitch()));
        restoring = wasRestoring;
    }

    private void changeTrackPitchMode(int index, Beat.PitchMode mode) {
        if (index < 0 || index >= tracks.size() || mode == null) return;
        var old = tracks.get(index);
        if (old.pitchMode() == mode) return;
        remember(projectSnapshot(), selectedTrack, selectedStep, "pitch-mode");
        var updated = new ArrayList<>(tracks);
        updated.set(index, new Beat.Track(old.name(), old.key(), old.format(), old.audio(),
                old.pitch(), mode));
        tracks = List.copyOf(updated);
        if (audio != null) {
            try {
                audio.trackMode(index, mode == Beat.PitchMode.PITCH_SHIFT);
            } catch (RuntimeException e) {
                status.setText("트랙 피치 방식을 오디오에 반영하지 못했습니다.");
            }
        }
        markDirty();
        refreshUi();
        status.setText(old.name() + " · " + (mode == Beat.PitchMode.PITCH_SHIFT
                ? "Pitch Shift (길이 유지)" : "Varispeed (속도 변경)"));
    }

    private void changeTrackPitch(int index, double value) {
        if (index < 0 || index >= tracks.size()) return;
        var old = tracks.get(index);
        if (old.pitch() == value) return;
        var updated = new ArrayList<>(tracks);
        updated.set(index, new Beat.Track(old.name(), old.key(), old.format(), old.audio(),
                value, old.pitchMode()));
        tracks = List.copyOf(updated);
        if (audio != null) {
            try {
                audio.trackPitch(index, value);
            } catch (RuntimeException e) {
                status.setText("트랙 피치를 오디오에 반영하지 못했습니다.");
            }
        }
        markDirty();
        refreshUi();
        status.setText(old.name() + " 트랙 피치 " + (value > 0 ? "+" : "") + (int) value + "반음");
    }

    private void selectStep(int step) {
        int max = Math.max(0, Pattern.stepCount(length) - 1);
        selectedStep = Math.max(0, Math.min(max, step));
        arrangement.setEditBeat(Pattern.stepBeat(selectedStep));
        sequencer.setSelectedStep(selectedStep);
    }

    private void selectStepFromSequencer(int step) {
        selectedStep = step;
        arrangement.setEditBeat(Pattern.stepBeat(selectedStep));
    }

    private void patternEdited(List<Beat.Hit> updated) {
        if (busy || recording) return;
        remember(projectSnapshot(), selectedTrack, selectedStep, "steps");
        hits = List.copyOf(updated);
        markDirty();
        arrangement.setData(tracks, hits, length, projectBars, selectedTrack, Pattern.stepBeat(selectedStep));
        scheduleLoopSync();
        status.setText("스텝을 편집했습니다 · " + hits.size() + " hits");
    }

    private void scheduleLoopSync() {
        if (loopSyncDelay != null) {
            loopSyncDelay.stop();
            loopSyncDelay.playFromStart();
        }
    }

    private void syncLoopNow() {
        var engine = audio;
        if (engine == null || tracks.isEmpty()) return;
        var snapshot = List.copyOf(hits);
        double snapshotLength = length;
        int snapshotBpm = bpm == null ? 100 : bpm.getValue();
        io.submit(() -> {
            try {
                engine.setLoop(snapshot, snapshotLength, snapshotBpm);
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("스텝 변경을 오디오에 반영하지 못했습니다."));
            }
        });
    }

    private void changeLoopLength(int bars) {
        if (recording || playing || busy) return;
        double next = bars * 4.0;
        if (Math.abs(next - length) < 0.0001) return;
        var outside = hits.stream().filter(h -> h.beat() >= next).toList();
        if (!outside.isEmpty() && !confirm("루프 길이 변경", outside.size()
                + "개의 타격이 새 루프 밖으로 나갑니다. 잘라낼까요?")) {
            restoring = true;
            loopBars.setValue((int) Math.max(1, Math.round(length / 4)));
            restoring = false;
            return;
        }
        remember(projectSnapshot(), selectedTrack, selectedStep, "loop-length");
        hits = Pattern.clampToLength(hits, next);
        length = next;
        projectBars = Math.max(projectBars, (int) Math.ceil(length / 4.0));
        selectedStep = Math.min(selectedStep, Math.max(0, Pattern.stepCount(length) - 1));
        markDirty();
        refreshUi();
        scheduleLoopSync();
    }

    private void changeSongBars(int bars) {
        if (busy || recording) return;
        int minimum = (int) Math.ceil(length / 4.0);
        int next = Math.max(minimum, Math.min(512, bars));
        if (next == projectBars) {
            if (songBars.getValue() != next) {
                restoring = true;
                songBars.getValueFactory().setValue(next);
                restoring = false;
            }
            return;
        }
        remember(projectSnapshot(), selectedTrack, selectedStep, "song-bars");
        projectBars = next;
        markDirty();
        refreshUi();
        status.setText("전체 " + projectBars + "마디 · 기본 패턴이 그대로 반복됩니다.");
    }

    private void updateTrackHeader() {
        if (tracks.isEmpty()) {
            selected.setText("—");
            keyChip.setText("KEY —");
            return;
        }
        var track = tracks.get(selectedTrack);
        selected.setText(String.format(Locale.ROOT, "%02d  %s", selectedTrack + 1, track.name()));
        keyChip.setText("KEY " + KeyBindings.display(track.key()));
        selected.setStyle("-selected-color: " + toHex(DawStyle.track(selectedTrack)) + ";");
    }

    private static String toHex(javafx.scene.paint.Color color) {
        return String.format("#%02x%02x%02x", Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
    }

    private void refreshUi() {
        int maxStep = Math.max(0, Pattern.stepCount(length) - 1);
        selectedStep = Math.max(0, Math.min(maxStep, selectedStep));
        selectedTrack = tracks.isEmpty() ? 0 : Math.max(0, Math.min(tracks.size() - 1, selectedTrack));
        arrangement.setData(tracks, hits, length, projectBars, selectedTrack, Pattern.stepBeat(selectedStep));
        if (!tracks.isEmpty()) {
            sequencer.setData(selectedTrack, tracks.get(selectedTrack), hits, length);
            sequencer.setSelectedStep(selectedStep);
        }
        restoring = true;
        int bars = (int) Math.max(1, Math.round(length / 4.0));
        if (!loopBars.getItems().contains(bars)) loopBars.getItems().add(bars);
        loopBars.setValue(bars);
        songBars.getValueFactory().setValue(projectBars);
        restoring = false;
        updateTrackHeader();
        updateTrackPitchEditor();
        updateTitle();
        syncControlState();
    }

    private void syncControlState() {
        boolean noAudio = audio == null;
        if (record != null) {
            record.setDisable(busy || noAudio);
            record.setText(recording ? "STOP" : "REC");
            setStyleClass(record, "recording", recording);
        }
        if (play != null) {
            play.setDisable(busy || noAudio || recording || playing || hits.isEmpty());
            setStyleClass(play, "playing", playing);
        }
        if (stop != null) stop.setDisable(busy);
        if (bpm != null) bpm.setDisable(busy || recording);
        if (pitch != null) pitch.setDisable(busy || recording);
        if (trackPitch != null) trackPitch.setDisable(busy || recording || tracks.isEmpty());
        if (songBars != null) songBars.setDisable(busy || recording);
        if (undoButton != null) undoButton.setDisable(busy || recording || undoStack.isEmpty());
        if (redoButton != null) redoButton.setDisable(busy || recording || redoStack.isEmpty());
        if (fileMenu != null) fileMenu.setDisable(busy || recording);
        if (loopBars != null) loopBars.setDisable(busy || recording || playing);
        if (learn != null) learn.setDisable(busy || recording || playing);
        if (changeSound != null) changeSound.setDisable(busy || recording || playing);
        if (addTrack != null) addTrack.setDisable(busy || recording || playing || tracks.size() >= Beat.MAX_TRACKS);
        if (removeTrack != null) removeTrack.setDisable(busy || recording || playing || tracks.size() <= 1);
        if (addBar != null) addBar.setDisable(busy || recording || projectBars >= 512);
        if (outputSettings != null) outputSettings.setDisable(busy || recording || playing);
        if (sequencer != null) sequencer.setDisable(busy || recording);
    }

    private static void setStyleClass(Node node, String style, boolean active) {
        if (active && !node.getStyleClass().contains(style)) node.getStyleClass().add(style);
        else if (!active) node.getStyleClass().remove(style);
    }

    private void toggleRecording() {
        if (recording) finishRecording(true);
        else startRecording();
    }

    private void startRecording() {
        if (busy || audio == null) {
            status.setText("먼저 오디오 출력을 연결하세요.");
            return;
        }
        if (tracks.isEmpty()) return;
        var track = tracks.get(selectedTrack);
        boolean hadHits = hits.stream().anyMatch(hit -> hit.track() == selectedTrack);
        if (hadHits && !confirm("트랙 녹음", "\"" + track.name()
                + "\" 트랙의 기존 타격만 새로 녹음합니다. 다른 트랙은 그대로 유지됩니다.")) return;
        remember(projectSnapshot(), selectedTrack, selectedStep, "record");
        cancelLearning();
        stopEverythingQuietly();
        try {
            audio.record(selectedTrack, length, bpm.getValue());
        } catch (RuntimeException e) {
            status.setText("녹음을 시작하지 못했습니다. 오디오 출력을 확인하세요.");
            return;
        }
        hits = hits.stream().filter(hit -> hit.track() != selectedTrack).toList();
        selectedStep = 0;
        recording = true;
        refreshUi();
        status.setText("REC · " + track.name() + " 트랙만 녹음합니다. 다른 트랙은 계속 재생됩니다.");
        syncControlState();
    }

    private void finishRecording(boolean manual) {
        if (!recording) return;
        try {
            if (manual && audio != null) audio.finishRecording();
            captureNativeRecording();
        } catch (Exception e) {
            status.setText("녹음을 마무리하지 못했습니다.");
        }
        recording = false;
        refreshUi();
        status.setText(hits.isEmpty() ? "녹음된 타격이 없습니다. REC를 다시 눌러보세요."
                : "녹음을 마쳤습니다 · " + hits.size() + " hits");
        syncControlState();
    }

    private void stopEverythingQuietly() {
        if (recording && audio != null) {
            try { audio.finishRecording(); captureNativeRecording(); } catch (Exception ignored) {}
        }
        if (audio != null) audio.stopAll();
        recording = false;
        playing = false;
        position.setText(positionText(0));
        arrangement.updatePlayhead(0, false);
        sequencer.setPlayhead(0, false);
        setStyleClass(record, "recording", false);
        setStyleClass(play, "playing", false);
        syncControlState();
    }

    private void stopEverything() {
        stopEverythingQuietly();
        status.setText("정지");
    }

    private void startPlaying() {
        if (busy || recording || audio == null || hits.isEmpty()) return;
        cancelLearning();
        try {
            audio.playLoop();
        } catch (RuntimeException e) {
            status.setText("재생을 시작하지 못했습니다.");
            return;
        }
        playing = true;
        arrangement.updatePlayhead(0, true);
        sequencer.setPlayhead(0, true);
        status.setText("반복 재생 중 · BPM과 피치를 조절할 수 있습니다.");
        syncControlState();
    }

    private void pollAudio() {
        var engine = audio;
        if (engine == null) return;
        try {
            var state = engine.state();
            boolean wasRecording = recording;
            recording = state.recording();
            playing = state.playing();
            if (wasRecording && !recording) {
                captureNativeRecording();
                status.setText(hits.isEmpty() ? "녹음된 타격이 없습니다."
                        : "녹음 완료 · 반복 재생 중입니다.");
            }
            position.setText(positionText(state.position()));
            arrangement.updatePlayhead(state.position(), playing || recording);
            sequencer.setPlayhead(state.position(), playing || recording);
            if (recording) status.setText("REC · " + state.hits() + " hits");
            syncControlState();
        } catch (RuntimeException e) {
            status.setText("오디오 엔진 상태를 읽지 못했습니다.");
        }
    }

    private static String positionText(double beat) {
        int sixteenth = (int) Math.floor(Math.max(0, beat) * 4);
        int bar = sixteenth / 16 + 1;
        int quarter = (sixteenth % 16) / 4 + 1;
        int sub = sixteenth % 4 + 1;
        return String.format(Locale.ROOT, "%03d · %02d · %02d", bar, quarter, sub);
    }

    private void captureNativeRecording() {
        if (audio == null || audio.state().recording()) return;
        try {
            var captured = audio.snapshot();
            hits = captured.hits();
            length = captured.length();
            selectedStep = Math.min(selectedStep, Math.max(0, Pattern.stepCount(length) - 1));
            refreshUi();
            markDirty();
        } catch (Exception e) {
            status.setText("녹음 내용을 읽지 못했습니다.");
        }
    }

    private void onKeyPressed(KeyEvent event) {
        if (busy) return;
        if (event.isControlDown() && !event.isAltDown() && event.getCode() == KeyCode.S) {
            if (isTextInputFocused()) return;
            event.consume();
            if (event.isShiftDown()) saveProjectAs();
            else saveProject();
            return;
        }
        if (event.isControlDown() && !event.isAltDown()
                && (event.getCode() == KeyCode.Z || event.getCode() == KeyCode.Y)) {
            if (isTextInputFocused()) return;
            event.consume();
            if (event.getCode() == KeyCode.Y || event.isShiftDown()) redo();
            else undo();
            return;
        }
        if (event.getCode().isModifierKey()) return;
        if (learning) {
            event.consume();
            if (event.getCode() == KeyCode.ESCAPE) {
                cancelLearning();
                return;
            }
            String captured = KeyBindings.capture(event);
            if (captured != null) {
                finishLearning(captured);
                lastKeyAt = System.nanoTime();
            }
            return;
        }
        if (isTextInputFocused()) return;
        int match = KeyBindings.indexFor(tracks, event);
        showInput(event, match);
        if (match >= 0) {
            event.consume();
            lastKeyAt = System.nanoTime();
            if (event.getCode() == KeyCode.UNDEFINED) {
                hit(match);
            } else if (held.add(event.getCode())) {
                hit(match);
            }
        }
    }

    private void onKeyTyped(KeyEvent event) {
        if (busy) return;
        if (learning) {
            String captured = KeyBindings.capture(event);
            if (captured != null) {
                event.consume();
                finishLearning(captured);
                lastKeyAt = System.nanoTime();
            }
            return;
        }
        if (isTextInputFocused()) return;
        if (System.nanoTime() - lastKeyAt < 35_000_000L) return;
        int match = KeyBindings.indexFor(tracks, event);
        showInput(event, match);
        if (match >= 0) {
            event.consume();
            hit(match);
        }
    }

    private boolean isTextInputFocused() {
        return scene != null && scene.getFocusOwner() instanceof TextInputControl;
    }

    private void hit(int index) {
        if (audio == null || index < 0 || index >= tracks.size()) return;
        try {
            audio.hit(index, 1.0, 0);
            arrangement.flash(index);
            if (hitFlash != null) {
                hitFlash.stop();
                hitFlash.playFromStart();
            }
        } catch (RuntimeException e) {
            status.setText("오디오가 준비되지 않았습니다. OUTPUT에서 출력을 다시 연결하세요.");
        }
    }

    private void showInput(KeyEvent event, int trackIndex) {
        if (inputState == null) return;
        String raw;
        if (event.getEventType() == KeyEvent.KEY_TYPED) {
            raw = "KEY_TYPED \"" + printable(event.getCharacter()) + "\"";
        } else {
            raw = "KEY_PRESSED " + event.getCode().getName();
            if (event.getText() != null && !event.getText().isBlank()) raw += " \"" + printable(event.getText()) + "\"";
        }
        String target = trackIndex >= 0 ? tracks.get(trackIndex).name() : "미지정";
        inputState.setText(raw + "  →  " + target);
        inputState.getStyleClass().setAll("input-state", trackIndex >= 0 ? "accepted" : "missed");
        if (inputHint != null) {
            inputHint.stop();
            inputHint.playFromStart();
        }
    }

    private String printable(String value) {
        if (value == null) return "";
        return value.replace("\n", "\\n").replace("\r", "");
    }

    private void resetInputHint() {
        if (inputState == null) return;
        inputState.setText(focused ? "입력 대기 · 숫자패드와 문자 입력 지원" : "포커스 없음 · 창을 클릭하세요");
        inputState.getStyleClass().setAll("input-state", "idle");
    }

    private void startLearning() {
        if (busy || recording || playing || tracks.isEmpty()) return;
        learning = true;
        learn.setText("키 입력 대기…");
        status.setText("새 단축키를 누르세요 · Esc로 취소");
        inputState.setText("키 학습 중 · 새 단축키를 누르세요");
        inputState.getStyleClass().setAll("input-state", "learning");
        arrangement.requestFocus();
    }

    private void cancelLearning() {
        if (!learning) return;
        learning = false;
        if (learn != null) learn.setText("키 지정");
        if (status != null) status.setText("키 학습을 취소했습니다.");
        resetInputHint();
    }

    private void finishLearning(String captured) {
        if (!learning || tracks.isEmpty()) return;
        for (int i = 0; i < tracks.size(); i++) {
            if (i != selectedTrack && KeyBindings.same(tracks.get(i).key(), captured)) {
                status.setText("이 단축키는 다른 트랙에서 사용 중입니다. 다른 키를 누르세요.");
                inputState.setText("중복 단축키 · " + KeyBindings.display(captured));
                inputState.getStyleClass().setAll("input-state", "missed");
                return;
            }
        }
        remember(projectSnapshot(), selectedTrack, selectedStep, "key");
        var old = tracks.get(selectedTrack);
        var updated = new ArrayList<>(tracks);
        updated.set(selectedTrack, new Beat.Track(old.name(), captured, old.format(), old.audio(),
                old.pitch(), old.pitchMode()));
        tracks = List.copyOf(updated);
        learning = false;
        learn.setText("키 지정");
        markDirty();
        refreshUi();
        status.setText(KeyBindings.display(captured) + " 키를 " + old.name() + " 트랙에 연결했습니다.");
        inputState.setText("입력 대기 · 숫자패드와 문자 입력 지원");
        inputState.getStyleClass().setAll("input-state", "idle");
    }

    private void newProject() {
        if (busy || recording) return;
        if (!confirmDiscard()) return;
        stopEverythingQuietly();
        clearHistory();
        loadAsync(this::blankProject, false, null, () -> {
            clearHistory();
            status.setText("새 비트");
            resetInputHint();
        });
    }

    private void openProject() {
        if (busy || recording) return;
        if (!confirmDiscard()) return;
        var file = chooser("Pocket Groove 열기", "Pocket Groove 프로젝트", "*.mdrum").showOpenDialog(stage);
        if (file == null) return;
        stopEverythingQuietly();
        clearHistory();
        loadAsync(() -> ProjectIO.load(file.toPath()), false, file.toPath(), () -> {
            clearHistory();
            status.setText("프로젝트를 열었습니다.");
            resetInputHint();
        });
    }

    private void saveProject() {
        if (busy || recording) return;
        if (currentFile == null) {
            saveProjectAs();
            return;
        }
        saveTo(currentFile, false);
    }

    private void saveProjectAs() {
        if (busy || recording) return;
        var chooser = chooser("다른 이름으로 저장", "Pocket Groove 프로젝트", "*.mdrum");
        if (currentFile != null) chooser.setInitialFileName(currentFile.getFileName().toString());
        var file = chooser.showSaveDialog(stage);
        if (file == null) return;
        Path path = file.toPath();
        if (!path.toString().toLowerCase(Locale.ROOT).endsWith(".mdrum")) path = Path.of(path + ".mdrum");
        if (!path.equals(currentFile) && Files.exists(path)
                && !confirm("파일 덮어쓰기", "같은 이름의 프로젝트를 바꿀까요?")) return;
        saveTo(path, true);
    }

    private void saveTo(Path destination, boolean renamed) {
        final Beat.Project project;
        try {
            project = projectSnapshot();
        } catch (RuntimeException e) {
            error(e);
            return;
        }
        setBusy(true, "프로젝트를 저장하는 중입니다…");
        io.submit(() -> {
            try {
                ProjectIO.save(destination, project);
                Platform.runLater(() -> {
                    if (closed) return;
                    currentFile = destination;
                    dirty = false;
                    setBusy(false, "");
                    updateTitle();
                    status.setText(renamed
                            ? "다른 이름으로 저장했습니다 · " + destination.getFileName()
                            : "저장했습니다 · " + destination.getFileName());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (closed) return;
                    setBusy(false, "");
                    error(e);
                });
            }
        });
    }

    private void addTrack() {
        if (busy || recording || playing || tracks.size() >= Beat.MAX_TRACKS) return;
        var file = chooser("새 트랙 소리", "오디오 (최대 16 MB)", "*.wav", "*.mp3", "*.aiff", "*.aif")
                .showOpenDialog(stage);
        if (file != null) addTrackFromFile(file.toPath(), true);
    }

    void addTrackFromFile(Path path) { addTrackFromFile(path, false); }

    private void addTrackFromFile(Path path, boolean askTrim) {
        if (busy || recording || playing || tracks.size() >= Beat.MAX_TRACKS) return;
        String key = Sounds.nextFreeKey(tracks);
        var base = projectSnapshot();
        importWithTrim(path, key, askTrim, imported -> {
            setBusy(true, "새 트랙의 소리를 준비하는 중입니다…");
            io.submit(() -> {
                try {
                    var newTracks = new ArrayList<>(base.tracks());
                    newTracks.add(imported);
                    var next = new Beat.Project(newTracks, base.hits(), base.length(), base.bpm(),
                            base.pitch(), base.bars());
                    var prepared = prepare(next);
                    Platform.runLater(() -> {
                        if (closed) {
                            if (prepared.created) prepared.engine.close();
                            return;
                        }
                        pushHistory(base, selectedTrack, selectedStep);
                        applyPrepared(prepared, true, currentFile, newTracks.size() - 1, () -> {
                            startLearning();
                            status.setText("새 트랙을 추가했습니다 · 소리와 키를 확인하세요.");
                        });
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        if (closed) return;
                        setBusy(false, "");
                        error(e);
                    });
                }
            });
        });
    }

    /** Reads and decodes the sound, optionally shows the trim dialog, then returns a WAV track. */
    private void importWithTrim(Path path, String key, boolean askTrim, Consumer<Beat.Track> done) {
        setBusy(true, "소리 파일을 읽는 중입니다…");
        io.submit(() -> {
            try {
                var imported = Sounds.importFile(path, key);
                if (!askTrim) {
                    Platform.runLater(() -> {
                        setBusy(false, "");
                        done.accept(imported);
                    });
                    return;
                }
                var pcm = Sounds.decode(imported);
                Platform.runLater(() -> {
                    if (closed) return;
                    setBusy(false, "");
                    var trimmed = WaveformTrimDialog.show(stage, pcm, imported.name());
                    if (trimmed == null) {
                        status.setText("소리 가져오기를 취소했습니다.");
                        return;
                    }
                    io.submit(() -> {
                        try {
                            byte[] wav = Sounds.wavBytes(trimmed);
                            var result = new Beat.Track(imported.name(), key, "wav", wav, imported.pitch());
                            Platform.runLater(() -> done.accept(result));
                        } catch (Exception e) {
                            Platform.runLater(() -> {
                                if (!closed) error(e);
                            });
                        }
                    });
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (closed) return;
                    setBusy(false, "");
                    error(e);
                });
            }
        });
    }

    private void removeTrack() {
        if (busy || recording || playing || tracks.size() <= 1) return;
        var track = tracks.get(selectedTrack);
        if (!confirm("트랙 삭제", "\"" + track.name() + "\" 트랙과 이 트랙의 타격을 삭제할까요?")) return;
        removeTrackAt(selectedTrack);
    }

    void removeTrackAt(int index) {
        if (busy || recording || playing || tracks.size() <= 1 || index < 0 || index >= tracks.size()) return;
        var newTracks = new ArrayList<>(tracks);
        newTracks.remove(index);
        var newHits = Pattern.removeTrack(hits, index);
        int nextSelection = Math.max(0, Math.min(newTracks.size() - 1,
                index >= newTracks.size() ? newTracks.size() - 1 : index));
        var project = new Beat.Project(newTracks, newHits, length, bpm.getValue(), pitch.getValue(), projectBars);
        var base = projectSnapshot();
        setBusy(true, "트랙을 정리하는 중입니다…");
        io.submit(() -> {
            try {
                var prepared = prepare(project);
                Platform.runLater(() -> {
                    if (closed) {
                        if (prepared.created) prepared.engine.close();
                        return;
                    }
                    pushHistory(base, selectedTrack, selectedStep);
                    applyPrepared(prepared, true, currentFile, nextSelection,
                            () -> status.setText("트랙을 삭제했습니다."));
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (closed) return;
                    setBusy(false, "");
                    error(e);
                });
            }
        });
    }

    private void changeSound() {
        if (busy || recording || playing || tracks.isEmpty()) return;
        var file = chooser("트랙 소리 변경", "오디오 (최대 16 MB)", "*.wav", "*.mp3", "*.aiff", "*.aif")
                .showOpenDialog(stage);
        if (file == null) return;
        var base = projectSnapshot();
        int index = selectedTrack;
        var old = tracks.get(index);
        importWithTrim(file.toPath(), old.key(), true, imported -> {
            var replacement = new Beat.Track(imported.name(), old.key(), imported.format(),
                    imported.audio(), old.pitch(), old.pitchMode());
            setBusy(true, "소리를 바꾸는 중입니다…");
            io.submit(() -> {
                try {
                    var newTracks = new ArrayList<>(base.tracks());
                    newTracks.set(index, replacement);
                    var next = new Beat.Project(newTracks, base.hits(), base.length(), base.bpm(),
                            base.pitch(), base.bars());
                    var prepared = prepare(next);
                    Platform.runLater(() -> {
                        if (closed) {
                            if (prepared.created) prepared.engine.close();
                            return;
                        }
                        pushHistory(base, selectedTrack, selectedStep);
                        applyPrepared(prepared, true, currentFile, index,
                                () -> status.setText("트랙 소리를 바꿨습니다."));
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        if (closed) return;
                        setBusy(false, "");
                        error(e);
                    });
                }
            });
        });
    }

    private void remember(Beat.Project project, int track, int step, String key) {
        if (restoring || restoringHistory || project == null) return;
        long now = System.nanoTime();
        if (key != null && key.equals(lastEditKey) && now - lastEditAt < 700_000_000L) {
            lastEditAt = now;
            return;
        }
        lastEditKey = key;
        lastEditAt = now;
        pushHistory(project, track, step);
    }

    private void pushHistory(Beat.Project project, int track, int step) {
        if (restoringHistory) return;
        undoStack.push(new History(project, track, step));
        while (undoStack.size() > HISTORY_LIMIT) undoStack.removeLast();
        redoStack.clear();
        syncControlState();
    }

    private void clearHistory() {
        undoStack.clear();
        redoStack.clear();
        lastEditKey = null;
        syncControlState();
    }

    private void undo() {
        if (busy || recording) return;
        if (undoStack.isEmpty()) {
            status.setText("되돌릴 편집이 없습니다.");
            return;
        }
        redoStack.push(new History(projectSnapshot(), selectedTrack, selectedStep));
        restoreHistory(undoStack.pop(), "편집을 되돌렸습니다.");
    }

    private void redo() {
        if (busy || recording) return;
        if (redoStack.isEmpty()) {
            status.setText("다시 실행할 편집이 없습니다.");
            return;
        }
        undoStack.push(new History(projectSnapshot(), selectedTrack, selectedStep));
        restoreHistory(redoStack.pop(), "편집을 다시 실행했습니다.");
    }

    private void restoreHistory(History history, String message) {
        var project = history.project;
        if (audio != null && sameTrackObjects(tracks, project.tracks())) {
            tracks = project.tracks();
            hits = project.hits();
            length = project.length();
            projectBars = project.bars();
            selectedTrack = Math.max(0, Math.min(tracks.size() - 1, history.track));
            selectedStep = Math.max(0, Math.min(Pattern.stepCount(length) - 1, history.step));
            restoring = true;
            bpm.getValueFactory().setValue((int) Math.round(project.bpm()));
            pitch.getValueFactory().setValue((int) Math.round(project.pitch()));
            restoring = false;
            try {
                audio.setLoop(hits, length, project.bpm());
                audio.parameters(master.getValue(), project.pitch());
            } catch (Exception e) {
                status.setText("되돌린 편집을 오디오에 반영하지 못했습니다.");
            }
            dirty = true;
            refreshUi();
            status.setText(message);
            syncControlState();
            return;
        }
        restoringHistory = true;
        setBusy(true, "편집을 되돌리는 중입니다…");
        io.submit(() -> {
            try {
                var prepared = prepare(project);
                Platform.runLater(() -> {
                    if (closed) {
                        if (prepared.created) prepared.engine.close();
                        restoringHistory = false;
                        return;
                    }
                    applyPrepared(prepared, true, currentFile, history.track, () -> {
                        restoringHistory = false;
                        selectStep(history.step);
                        status.setText(message);
                    });
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (closed) return;
                    restoringHistory = false;
                    setBusy(false, "");
                    error(e);
                });
            }
        });
    }

    private static boolean sameTrackObjects(List<Beat.Track> a, List<Beat.Track> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private void exportMp3() {
        if (busy || recording || playing) return;
        if (hits.isEmpty()) {
            status.setText("먼저 스텝을 입력하거나 녹음하세요.");
            return;
        }
        String base = currentFile == null ? "PocketGroove" : currentFile.getFileName().toString();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        var folder = currentFile != null && currentFile.getParent() != null
                ? currentFile.getParent() : defaultExportFolder();
        var request = Mp3ExportDialog.show(stage, base, projectBars,
                bpm == null ? 100 : bpm.getValue());
        if (request == null) return;

        var chooser = chooser("내보내기", request.format() == Mp3ExportDialog.Format.MP3
                ? "MP3 오디오" : "MIDI 파일", request.format() == Mp3ExportDialog.Format.MP3 ? "*.mp3" : "*.mid");
        chooser.setInitialDirectory(folder.toFile());
        chooser.setInitialFileName(request.name()
                + (request.format() == Mp3ExportDialog.Format.MP3 ? ".mp3" : ".mid"));
        var file = chooser.showSaveDialog(stage);
        if (file == null) return;
        Path destination = file.toPath();
        String lower = destination.toString().toLowerCase(Locale.ROOT);
        boolean mp3 = request.format() == Mp3ExportDialog.Format.MP3;
        if (mp3 && !lower.endsWith(".mp3")) destination = Path.of(destination + ".mp3");
        if (!mp3 && !lower.endsWith(".mid") && !lower.endsWith(".midi"))
            destination = Path.of(destination + ".mid");
        if (Files.exists(destination)
                && !confirm("파일 덮어쓰기", destination.getFileName() + " 파일을 바꿀까요?")) return;
        var project = projectSnapshot();
        double gain = master.getValue();
        Path target = destination;
        setBusy(true, mp3 ? "MP3로 인코딩하는 중입니다…" : "MIDI 파일을 만드는 중입니다…");
        io.submit(() -> {
            try {
                if (mp3) Mp3Exporter.export(target, project, gain, request.startBar(), request.endBar());
                else MidiExporter.export(target, project, request.startBar(), request.endBar());
                long size = Files.size(target);
                Platform.runLater(() -> {
                    if (closed) return;
                    setBusy(false, "");
                    String range = request.startBar() == request.endBar()
                            ? request.startBar() + "마디"
                            : request.startBar() + "–" + request.endBar() + "마디";
                    status.setText((mp3 ? "MP3" : "MIDI") + "를 저장했습니다 · " + target.getFileName()
                            + " · " + range + " · " + String.format(Locale.ROOT, "%.1f MB",
                            size / 1048576.0));
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (closed) return;
                    setBusy(false, "");
                    error(e);
                });
            }
        });
    }

    private static Path defaultExportFolder() {
        var music = Path.of(System.getProperty("user.home"), "Music");
        return Files.isDirectory(music) ? music : Path.of(System.getProperty("user.home"));
    }

    private void loadAsync(Callable<Beat.Project> source, boolean dirtyAfter, Path path, Runnable after) {
        setBusy(true, "사운드와 프로젝트를 준비하는 중입니다…");
        io.submit(() -> {
            try {
                var project = source.call();
                var prepared = prepare(project);
                Platform.runLater(() -> {
                    if (closed) {
                        if (prepared.created) prepared.engine.close();
                        return;
                    }
                    applyPrepared(prepared, dirtyAfter, path, 0, after);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (closed) return;
                    setBusy(false, "");
                    error(e);
                });
            }
        });
    }

    private Prepared prepare(Beat.Project project) throws IOException {
        var pcm = Sounds.decodeAll(project.tracks());
        var found = AudioEngine.devices();
        var engine = audio;
        boolean created = false;
        if (engine == null) {
            engine = AudioEngine.open(selectedDeviceId, selectedExclusive, selectedFrames);
            created = true;
        }
        try {
            var pitches = project.tracks().stream().map(Beat.Track::pitch).toList();
            var modes = project.tracks().stream()
                    .map(track -> track.pitchMode() == Beat.PitchMode.PITCH_SHIFT).toList();
            engine.install(pcm, pitches, modes, project.hits(), project.length(), project.bpm());
        } catch (Exception e) {
            if (created) engine.close();
            throw e;
        }
        return new Prepared(project, engine, found, created);
    }

    private void applyPrepared(Prepared prepared, boolean dirtyAfter, Path path, int select, Runnable after) {
        if (audio != null && audio != prepared.engine) audio.close();
        if (prepared.created) {
            selectedDeviceId = -1;
            selectedExclusive = false;
            selectedFrames = 256;
        }
        audio = prepared.engine;
        devices = prepared.devices;
        var project = prepared.project;
        tracks = project.tracks();
        hits = project.hits();
        length = project.length();
        projectBars = project.bars();
        selectedTrack = Math.max(0, Math.min(tracks.size() - 1, select));
        selectedStep = 0;
        currentFile = path;
        this.dirty = dirtyAfter;
        restoring = true;
        bpm.getValueFactory().setValue((int) Math.round(project.bpm()));
        pitch.getValueFactory().setValue((int) Math.round(project.pitch()));
        restoring = false;
        if (audio != null) audio.parameters(master.getValue(), pitch.getValue());
        refreshUi();
        setBusy(false, "");
        if (after != null) after.run();
        updateTitle();
        audioDescription.setText(audio == null ? "WASAPI" : audio.description());
    }

    private void showOutputSettings() {
        if (busy || recording || playing) return;
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(stage);
        dialog.setTitle("오디오 출력");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/drum.css").toExternalForm());
        dialog.getDialogPane().getStyleClass().add("audio-dialog");

        var deviceBox = new ComboBox<AudioEngine.Device>();
        deviceBox.getItems().addAll(devices);
        deviceBox.setMaxWidth(Double.MAX_VALUE);
        var current = devices.stream().filter(d -> d.id() == selectedDeviceId).findFirst()
                .orElse(devices.isEmpty() ? null : devices.get(0));
        deviceBox.setValue(current);
        var exclusiveBox = new CheckBox("빠른 연주 · 이 앱에서 단독 사용");
        exclusiveBox.setSelected(selectedExclusive);
        var bufferBox = new ComboBox<Integer>();
        bufferBox.getItems().addAll(64, 128, 256, 512);
        bufferBox.setValue(selectedFrames);
        bufferBox.setConverter(new javafx.util.StringConverter<>() {
            @Override public Integer fromString(String text) { return Integer.valueOf(text.replaceAll("[^0-9]", "")); }
            @Override public String toString(Integer value) {
                if (value == null) return "";
                return switch (value) {
                    case 64 -> "빠름 · 64";
                    case 128 -> "균형 · 128";
                    case 512 -> "안정 · 512";
                    default -> "기본 · 256";
                };
            }
        });
        var info = new Label((audio == null ? "오디오 엔진 연결 안 됨" : audio.description())
                + "\n드라이버 보고 지연은 실제 타건-출력 지연의 실측값이 아닙니다.");
        info.setWrapText(true);
        info.getStyleClass().add("dialog-note");

        var grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(4, 2, 2, 2));
        grid.addRow(0, new Label("출력 장치"), deviceBox);
        grid.addRow(1, new Label("모드"), exclusiveBox);
        grid.addRow(2, new Label("반응 속도"), bufferBox);
        grid.add(info, 0, 3, 2, 1);
        var columns = new ColumnConstraints();
        columns.setMinWidth(76);
        grid.getColumnConstraints().add(columns);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(
                new ButtonType("적용", ButtonType.OK.getButtonData()),
                ButtonType.CANCEL);
        dialog.showAndWait().filter(button -> button.getButtonData() == ButtonType.OK.getButtonData())
                .ifPresent(button -> applyOutputSettings(deviceBox.getValue(), exclusiveBox.isSelected(),
                        bufferBox.getValue()));
    }

    private void applyOutputSettings(AudioEngine.Device device, boolean exclusive, int frames) {
        if (device == null) return;
        setBusy(true, "오디오 출력을 다시 연결하는 중입니다…");
        var current = audio;
        var project = projectSnapshot();
        io.submit(() -> {
            try {
                var pcm = Sounds.decodeAll(project.tracks());
                var engine = current;
                boolean created = false;
                if (engine == null) {
                    engine = AudioEngine.open(device.id(), exclusive, frames);
                    created = true;
                } else {
                    engine.reconfigure(device.id(), exclusive, frames);
                }
                try {
                    var pitches = project.tracks().stream().map(Beat.Track::pitch).toList();
                    var modes = project.tracks().stream()
                            .map(track -> track.pitchMode() == Beat.PitchMode.PITCH_SHIFT).toList();
                    engine.install(pcm, pitches, modes, project.hits(), project.length(), project.bpm());
                } catch (Exception e) {
                    if (created) engine.close();
                    throw e;
                }
                var found = AudioEngine.devices();
                final var target = engine;
                final boolean wasCreated = created;
                Platform.runLater(() -> {
                    if (closed) {
                        if (wasCreated) target.close();
                        return;
                    }
                    if (audio != null && audio != target) audio.close();
                    audio = target;
                    devices = found;
                    selectedDeviceId = device.id();
                    selectedExclusive = exclusive;
                    selectedFrames = frames;
                    audioDescription.setText(target.description());
                    setBusy(false, "");
                    status.setText("오디오 출력을 다시 연결했습니다.");
                    syncControlState();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (closed) return;
                    setBusy(false, "");
                    error(e);
                });
            }
        });
    }

    private Beat.Project blankProject() throws IOException {
        return new Beat.Project(Sounds.defaults(), List.of(), 4, 100, 0, 16);
    }

    private Beat.Project projectSnapshot() {
        return new Beat.Project(tracks, hits, length,
                bpm == null ? 100 : bpm.getValue(), pitch == null ? 0 : pitch.getValue(), projectBars);
    }

    private void markDirty() {
        if (restoring) return;
        dirty = true;
        updateTitle();
    }

    private void updateTitle() {
        String name = currentFile == null ? "UNTITLED" : currentFile.getFileName().toString();
        stage.setTitle((dirty ? "* " : "") + name + " — Pocket Groove");
        if (fileLabel != null) fileLabel.setText(name);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        root.setDisable(value);
        if (!message.isBlank()) status.setText(message);
        if (value) {
            if (loopSyncDelay != null) loopSyncDelay.stop();
        } else {
            syncControlState();
        }
    }

    private boolean confirm(String title, String message) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.getDialogPane().getStylesheets().add(getClass().getResource("/drum.css").toExternalForm());
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private boolean confirmDiscard() {
        return !dirty || confirm("저장하지 않은 변경 사항", "변경 사항을 버릴까요? 보관하려면 취소한 뒤 SAVE를 누르세요.");
    }

    private void error(Throwable throwable) {
        String message = throwable.getMessage() == null ? "작업을 완료하지 못했습니다." : throwable.getMessage();
        status.setText(message);
        var alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.initOwner(stage);
        alert.setTitle("확인 필요");
        alert.setHeaderText("작업을 완료하지 못했습니다.");
        alert.getDialogPane().getStylesheets().add(getClass().getResource("/drum.css").toExternalForm());
        alert.showAndWait();
    }

    private FileChooser chooser(String title, String description, String... extensions) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, extensions));
        return chooser;
    }

    private record Prepared(Beat.Project project, AudioEngine engine,
                            List<AudioEngine.Device> devices, boolean created) {}

    private record History(Beat.Project project, int track, int step) {}

    @Override public void stop() {
        closed = true;
        if (animation != null) animation.stop();
        if (audio != null) audio.close();
        io.shutdownNow();
    }
}

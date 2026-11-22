package io.pocketgroove;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Asks for the MP3 name and bar range without opening a file explorer. */
final class Mp3ExportDialog {
    private static final Pattern RANGE = Pattern.compile("^(\\d+)(?:\\s*-\\s*(\\d+))?$");

    enum Format { MP3, MIDI }

    record Request(String name, int startBar, int endBar, Format format) {}

    private Mp3ExportDialog() {}

    static Request show(Window owner, String defaultName, int totalBars, int bpm) {
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(owner);
        dialog.setTitle("MP3 내보내기");
        dialog.setHeaderText("BPM " + bpm + " · 전체 " + totalBars + "마디");
        dialog.getDialogPane().getStylesheets().add(
                Mp3ExportDialog.class.getResource("/drum.css").toExternalForm());

        var name = new TextField(cleanName(defaultName));
        name.setId("export-name");
        name.setPrefColumnCount(30);
        var range = new TextField("전체");
        range.setId("export-range");
        range.setPromptText("전체");
        var error = new Label();
        error.setId("export-error");
        error.getStyleClass().add("export-error");
        error.setWrapText(true);
        var format = new ComboBox<Format>();
        format.setId("export-format");
        format.getItems().addAll(Format.values());
        format.setValue(Format.MP3);
        format.setMaxWidth(Double.MAX_VALUE);
        var help = new Label("범위: 전체, 1-10, 3처럼 입력합니다.  전체는 1–" + totalBars + "마디입니다.");
        help.getStyleClass().add("dialog-note");
        help.setWrapText(true);
        var tempoHelp = new Label("DAW에서 가져올 때 프로젝트 BPM을 " + Math.round(bpm)
                + "으로 맞추세요. MIDI에는 템포가 직접 저장됩니다.");
        tempoHelp.getStyleClass().add("dialog-note");
        tempoHelp.setWrapText(true);

        var grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(9);
        grid.setPadding(new Insets(4, 2, 2, 2));
        grid.addRow(0, new Label("파일 이름"), name);
        grid.addRow(1, new Label("형식"), format);
        grid.addRow(2, new Label("마디 범위"), range);
        grid.add(help, 1, 3);
        grid.add(tempoHelp, 1, 4);
        grid.add(error, 0, 5, 2, 1);
        GridPane.setHgrow(name, Priority.ALWAYS);
        GridPane.setHgrow(range, Priority.ALWAYS);
        GridPane.setHgrow(format, Priority.ALWAYS);

        var content = new VBox(8, grid);
        content.setStyle("-fx-background-color: #15181D;");
        dialog.getDialogPane().setContent(content);
        var export = new ButtonType("내보내기", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(export,
                new ButtonType("취소", ButtonBar.ButtonData.CANCEL_CLOSE));

        var ok = dialog.getDialogPane().lookupButton(export);
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            var parsed = parseRange(range.getText(), totalBars);
            if (parsed.isEmpty()) {
                error.setText("마디 범위를 확인하세요. 예: 전체, 1-10, 3");
                event.consume();
                return;
            }
        });

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get().getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE) return null;
        var parsed = parseRange(range.getText(), totalBars).orElseThrow();
        return new Request(cleanName(name.getText()), parsed[0], parsed[1], format.getValue());
    }

    static Optional<int[]> parseRange(String text, int totalBars) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty() || value.equals("전체")) return Optional.of(new int[]{1, totalBars});
        var matcher = RANGE.matcher(value);
        if (!matcher.matches()) return Optional.empty();
        try {
            int start = Integer.parseInt(matcher.group(1));
            int end = matcher.group(2) == null ? start : Integer.parseInt(matcher.group(2));
            if (start < 1 || end < start || end > totalBars) return Optional.empty();
            return Optional.of(new int[]{start, end});
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) name = name.substring(0, name.length() - 4).trim();
        else if (lower.endsWith(".midi")) name = name.substring(0, name.length() - 5).trim();
        else if (lower.endsWith(".mid")) name = name.substring(0, name.length() - 4).trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name.isBlank() ? "PocketGroove" : name;
    }
}

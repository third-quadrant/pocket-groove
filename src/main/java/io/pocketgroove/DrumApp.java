package io.pocketgroove;

import javafx.animation.*;
import javafx.application.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;
import javafx.util.Duration;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class DrumApp extends Application {
    private static final String[] COLORS={"#f4b39e","#c2b7da","#afd1c4","#e8cd83","#a9cadd","#d8b5ce"};
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"groove-files");t.setDaemon(true);return t;});
    private final Set<KeyCode> held=EnumSet.noneOf(KeyCode.class);
    private final List<Beat.Hit> hits=new ArrayList<>();
    private final Button[] pads=new Button[6], bindings=new Button[6];
    private final Label[] names=new Label[6];
    private final long[] flashed=new long[6];
    private List<Beat.Pad> kit=List.of();
    private volatile Sounds sounds;
    private volatile double pitchValue, volumeValue=.75;
    private LoopPlayer player;
    private Stage stage;
    private VBox root;
    private final Label status=new Label("기본 소리를 준비하고 있습니다…"), fileName=new Label("저장하지 않은 비트"), state=new Label("준비"), count=new Label("0 hits");
    private final Button record=new Button("녹음 시작"), play=new Button("반복 재생"), stop=new Button("정지");
    private final Slider tempo=new Slider(40,240,100), pitch=new Slider(-12,12,0), volume=new Slider(0,1,.75);
    private final ComboBox<Integer> bars=new ComboBox<>();
    private final Canvas tape=new Canvas(900,116);
    private boolean busy, recording, playing, dirty, restoring, closed;
    private int learning=-1;
    private double length=16;
    private long recordStart;
    private Path currentFile;

    @Override public void start(Stage stage) {
        this.stage=stage;
        javafx.scene.text.Font.loadFont(getClass().getResourceAsStream("/fonts/Barlow-SemiBold.ttf"),14);
        javafx.scene.text.Font.loadFont(getClass().getResourceAsStream("/fonts/NanumGothic-Regular.ttf"),13);
        player=new LoopPlayer(i->{
            var bank=sounds;
            if(bank!=null) bank.play(i,volumeValue,pitchValue);
            Platform.runLater(()->flash(i));
        },true);
        root=new VBox(20); root.setMinWidth(0);root.getStyleClass().add("app");
        root.getChildren().addAll(header(),instrument(),loopStrip(),footer());
        var scroll=new ScrollPane(root);scroll.setFitToWidth(true);scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        var scene=new Scene(scroll,1120,880);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/drum.css")).toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED,this::keyPressed);
        scene.addEventFilter(KeyEvent.KEY_RELEASED,e->held.remove(e.getCode()));
        stage.focusedProperty().addListener((o,a,focused)->{
            held.clear();
            if(!focused && learning>=0) cancelLearning();
        });
        stage.setTitle("Pocket Groove"); stage.setScene(scene); stage.setMinWidth(960); stage.setMinHeight(760);
        stage.setOnCloseRequest(e->{ if(busy || !confirmDiscard()) e.consume(); });
        stage.show();
        var screen=Screen.getPrimary().getVisualBounds();
        stage.setHeight(Math.min(stage.getHeight(),screen.getHeight()-32));
        stage.setWidth(Math.min(stage.getWidth(),screen.getWidth()-32));
        stage.centerOnScreen();
        loadAsync(()->new Beat.Project(Sounds.defaults(),List.of(),16,100,0),false,null);
        new AnimationTimer() {
            @Override public void handle(long now) {
                if(recording && Beat.elapsedBeats(now-recordStart,tempo.getValue())>=length) finishRecording(false);
                for(int i=0;i<6;i++) if(flashed[i]!=0 && now-flashed[i]>100_000_000L) {
                    pads[i].pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("hit"),false); flashed[i]=0;
                }
                drawTape(now);
            }
        }.start();
    }
    private HBox header() {
        Label brand=new Label("Pocket Groove"); brand.getStyleClass().add("brand");
        Label subtitle=new Label("여섯 개의 키, 나만의 리듬."); subtitle.getStyleClass().add("muted");
        VBox identity=new VBox(3,brand,subtitle);
        Button open=button("열기",()->openProject()); open.setId("open");
        Button save=button("비트 저장",()->saveProject()); save.setId("save"); save.getStyleClass().add("primary");
        HBox row=new HBox(10,identity,spacer(),open,save); row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
    private HBox instrument() {
        Label title=new Label("Drum kit"); title.getStyleClass().add("section-title");
        Label hint=new Label("패드를 누르거나 지정한 키로 연주하세요."); hint.getStyleClass().add("muted");
        GridPane grid=new GridPane(); grid.setHgap(14); grid.setVgap(14);
        for(int c=0;c<3;c++){var col=new ColumnConstraints();col.setPercentWidth(100.0/3);col.setHgrow(Priority.ALWAYS);grid.getColumnConstraints().add(col);}
        for(int i=0;i<6;i++) {
            final int index=i;
            Label number=new Label("PAD "+(i+1)); number.getStyleClass().add("pad-number");
            names[i]=new Label(Sounds.NAMES[i]); names[i].getStyleClass().add("pad-name"); names[i].setMaxWidth(Double.MAX_VALUE);
            var glyph=new Canvas(120,48); drawGlyph(glyph,i);
            VBox content=new VBox(12,number,glyph,names[i]); content.setAlignment(Pos.CENTER_LEFT); content.setMouseTransparent(true);
            pads[i]=new Button(); pads[i].setId("pad-"+i); pads[i].setGraphic(content); pads[i].getStyleClass().add("pad");
            pads[i].setStyle("-pad-color: "+COLORS[i]+";"); pads[i].setMaxWidth(Double.MAX_VALUE); pads[i].setPrefHeight(158);
            pads[i].setOnAction(e->hit(index));
            bindings[i]=button("키 "+(i+1),()->learn(index)); bindings[i].getStyleClass().add("key-button"); bindings[i].setMaxWidth(Double.MAX_VALUE);
            Button replace=button("소리 변경",()->replaceSound(index)); replace.getStyleClass().add("small-button");
            HBox actions=new HBox(6,bindings[i],replace); HBox.setHgrow(bindings[i],Priority.ALWAYS);
            VBox cell=new VBox(8,pads[i],actions); grid.add(cell,i%3,i/3);
        }
        VBox left=new VBox(10,title,hint,grid);left.setMinWidth(0); HBox.setHgrow(left,Priority.ALWAYS);
        VBox controls=new VBox(10); controls.getStyleClass().add("controls"); controls.setPrefWidth(272); controls.setMinWidth(252);
        Label session=new Label("Session"); session.getStyleClass().add("section-title");
        controls.getChildren().addAll(session,sliderBlock("템포",tempo," BPM"),sliderBlock("피치",pitch," st"));
        pitch.setMajorTickUnit(1); pitch.setMinorTickCount(0); pitch.setSnapToTicks(true);
        tempo.valueProperty().addListener((o,a,b)->{if(!restoring){player.tempo(b.doubleValue(),System.nanoTime());markDirty();}});
        pitch.valueProperty().addListener((o,a,b)->{pitchValue=b.doubleValue();if(!restoring)markDirty();});
        volume.valueProperty().addListener((o,a,b)->volumeValue=b.doubleValue());
        bars.getItems().addAll(1,2,4,8); bars.setValue(4); bars.setMaxWidth(Double.MAX_VALUE);
        bars.setConverter(new javafx.util.StringConverter<>() {
            public String toString(Integer n){return n==null?"":n+" 마디 ("+(n*4)+"박)";}
            public Integer fromString(String s){return Integer.valueOf(s);}
        });
        Label loopLabel=new Label("녹음 길이"); loopLabel.getStyleClass().add("control-label");
        controls.getChildren().addAll(new Separator(),new VBox(7,loopLabel,bars));
        record.getStyleClass().add("record"); record.setId("record"); record.setMaxWidth(Double.MAX_VALUE);
        record.setOnAction(e->{if(recording)finishRecording(true);else startRecording();});
        play.setId("play"); play.setOnAction(e->startPlaying()); play.setMaxWidth(Double.MAX_VALUE);
        stop.setOnAction(e->stopEverything()); stop.setId("stop");
        HBox transport=new HBox(8,play,stop); HBox.setHgrow(play,Priority.ALWAYS);
        controls.getChildren().addAll(record,transport,sliderBlock("출력 음량",volume,"%"));
        Label note=new Label("피치는 소리 길이도 바꿉니다.\n템포는 반복 속도를 바꿉니다."); note.getStyleClass().add("fine-print"); note.setWrapText(true);note.setMinHeight(36);
        controls.getChildren().add(note);
        HBox body=new HBox(24,left,controls); VBox.setVgrow(body,Priority.ALWAYS); return body;
    }
    private VBox sliderBlock(String text, Slider slider, String unit) {
        Label label=new Label(text); label.getStyleClass().add("control-label");
        Label value=new Label(); value.getStyleClass().add("value");
        Runnable update=()->value.setText(Math.round(slider.getValue()*(unit.equals("%")?100:1))+unit);
        slider.valueProperty().addListener((o,a,b)->update.run()); update.run();
        slider.setAccessibleText(text); slider.setBlockIncrement(unit.equals("%")?.05:1);
        return new VBox(5,new HBox(8,label,spacer(),value),slider);
    }
    private VBox loopStrip() {
        Label title=new Label("Loop tape"); title.getStyleClass().add("section-title");
        state.getStyleClass().add("state"); count.getStyleClass().add("muted");
        HBox head=new HBox(12,title,state,spacer(),count); head.setAlignment(Pos.CENTER_LEFT);
        StackPane wrap=new StackPane(tape);wrap.setMinWidth(0); tape.widthProperty().bind(wrap.widthProperty());
        VBox strip=new VBox(12,head,wrap); strip.getStyleClass().add("loop-strip"); return strip;
    }
    private HBox footer() {
        status.getStyleClass().add("status"); status.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(status,Priority.ALWAYS);
        fileName.getStyleClass().add("fine-print"); fileName.setMaxWidth(220);
        return new HBox(16,status,fileName);
    }
    private static Region spacer(){Region r=new Region();HBox.setHgrow(r,Priority.ALWAYS);return r;}
    private static Button button(String text,Runnable action){Button b=new Button(text);b.setOnAction(e->action.run());return b;}
    private void drawGlyph(Canvas canvas,int index) {
        var g=canvas.getGraphicsContext2D();g.setStroke(Color.web("#30273f"));g.setLineWidth(2);
        for(int j=0;j<3;j++) {
            double width=30+j*18;
            if(index==0 || index==4) g.strokeOval(58-width/2,24-width/4,width,width/2);
            else if(index==2 || index==5) {g.strokeLine(10,12+j*12,108,12+j*12);g.strokeLine(45+j*13,5,45+j*13,43);}
            else for(int k=0;k<6;k++) g.strokeLine(8+k*18,12+j*12,15+k*18,6+j*12);
        }
    }
    private void keyPressed(KeyEvent e) {
        if(busy || kit.isEmpty())return;
        if(learning>=0){
            e.consume();
            if(e.getCode()==KeyCode.ESCAPE){cancelLearning();return;}
            if(e.getCode().isModifierKey() || e.getCode()==KeyCode.UNDEFINED)return;
            var modifiers=new ArrayList<KeyCombination.Modifier>();
            if(e.isControlDown())modifiers.add(KeyCombination.CONTROL_DOWN);
            if(e.isAltDown())modifiers.add(KeyCombination.ALT_DOWN);
            if(e.isShiftDown())modifiers.add(KeyCombination.SHIFT_DOWN);
            if(e.isMetaDown())modifiers.add(KeyCombination.META_DOWN);
            String key=new KeyCodeCombination(e.getCode(),modifiers.toArray(KeyCombination.Modifier[]::new)).getName();
            for(int i=0;i<6;i++)if(i!=learning && kit.get(i).key().equals(key)){status.setText("이 키는 다른 패드에서 사용 중입니다. 다른 키를 누르세요.");return;}
            var updated=new ArrayList<>(kit);var old=kit.get(learning);
            updated.set(learning,new Beat.Pad(old.name(),key,old.format(),old.audio()));kit=List.copyOf(updated);
            held.add(e.getCode()); cancelLearning(); refreshPads(); markDirty(); status.setText("키를 연결했습니다. 기기의 기존 설정은 유지됩니다.");return;
        }
        for(int i=0;i<6;i++)if(KeyCombination.valueOf(kit.get(i).key()).match(e)){
            e.consume();if(held.add(e.getCode()))hit(i);return;
        }
    }
    private void learn(int index){if(recording || playing || busy)return;learning=index;bindings[index].setText("키를 누르세요");status.setText("패드 "+(index+1)+"에 연결할 키를 누르세요. Esc로 취소합니다.");root.requestFocus();}
    private void cancelLearning(){learning=-1;refreshPads();}
    private void refreshPads(){if(kit.isEmpty())return;for(int i=0;i<6;i++){
        names[i].setText(kit.get(i).name());bindings[i].setText("키  "+kit.get(i).key());
        pads[i].setAccessibleText(kit.get(i).name()+", 키 "+kit.get(i).key());
        pads[i].setTooltip(new Tooltip(kit.get(i).name()));
    }}
    private void hit(int index){
        if(busy || sounds==null)return;
        sounds.play(index,volumeValue,pitchValue);flash(index);
        if(recording){double beat=Beat.elapsedBeats(System.nanoTime()-recordStart,tempo.getValue());
            if(beat<length && hits.size()<100_000){hits.add(new Beat.Hit(index,beat));count.setText(hits.size()+" hits");markDirty();}}
    }
    private void flash(int index){flashed[index]=System.nanoTime();pads[index].pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("hit"),true);}
    private void startRecording(){
        if(busy || sounds==null)return;
        if(!hits.isEmpty() && !confirm("새로 녹음할까요?","현재 연주 기록을 새 녹음으로 바꿉니다. 소리와 키 지정은 유지됩니다."))return;
        stopEverything();cancelLearning();hits.clear();length=bars.getValue()*4;recordStart=System.nanoTime();recording=true;markDirty();
        record.setText("녹음 끝내기");state.setText("녹음 중");state.setStyle("-fx-text-fill: #ad344f;");
        status.setText("녹음 중입니다. 지정한 마디가 끝나면 자동으로 반복합니다.");updateControls();
    }
    private void finishRecording(boolean manual){
        if(!recording)return;
        if(manual)length=Math.min(length,Math.max(1,Math.ceil(Beat.elapsedBeats(System.nanoTime()-recordStart,tempo.getValue()))));
        recording=false;record.setText("녹음 시작");updateControls();
        if(hits.isEmpty()){state.setText("준비");status.setText("녹음된 연주가 없습니다. 녹음을 시작하고 패드를 눌러보세요.");}
        else startPlaying();
    }
    private void startPlaying(){if(busy || recording || hits.isEmpty())return;cancelLearning();player.play(snapshot(),System.nanoTime());playing=true;state.setText("반복 중");state.setStyle("");status.setText("반복 재생 중 · 템포와 피치를 조절할 수 있습니다.");updateControls();}
    private void stopEverything(){
        if(recording)finishRecording(true);
        player.stop();playing=false;if(sounds!=null)sounds.stopAll();state.setText("정지");state.setStyle("");updateControls();
    }
    private void updateControls(){play.setDisable(recording || hits.isEmpty() || busy);tempo.setDisable(recording);bars.setDisable(recording);for(var b:bindings)b.setDisable(recording||playing);}
    private Beat.Project snapshot(){return new Beat.Project(kit,hits,length,tempo.getValue(),pitch.getValue());}
    private void markDirty(){if(restoring)return;dirty=true;updateTitle();}
    private void updateTitle(){stage.setTitle((dirty?"* ":"")+(currentFile==null?"새 비트":currentFile.getFileName())+" — Pocket Groove");fileName.setText((dirty?"수정됨 · ":"")+(currentFile==null?"저장하지 않은 비트":currentFile.getFileName()));}
    private boolean confirm(String title,String message){Alert a=new Alert(Alert.AlertType.CONFIRMATION,message,ButtonType.OK,ButtonType.CANCEL);a.initOwner(stage);a.setTitle(title);a.setHeaderText(title);return a.showAndWait().orElse(ButtonType.CANCEL)==ButtonType.OK;}
    private boolean confirmDiscard(){return !dirty || confirm("저장하지 않은 변경 사항","변경 사항을 버릴까요? 보관하려면 취소한 뒤 ‘비트 저장’을 누르세요.");}
    private void error(Throwable e){status.setText("작업을 완료하지 못했습니다.");Alert a=new Alert(Alert.AlertType.ERROR,e.getMessage(),ButtonType.OK);a.initOwner(stage);a.setTitle("파일 확인");a.setHeaderText("파일을 확인한 뒤 다시 시도하세요.");a.showAndWait();}
    private FileChooser chooser(String title,String description,String... extensions){FileChooser c=new FileChooser();c.setTitle(title);c.getExtensionFilters().add(new FileChooser.ExtensionFilter(description,extensions));return c;}
    private void replaceSound(int index){
        if(busy || recording || playing){status.setText("소리를 바꾸려면 먼저 연주를 정지하세요.");return;}
        File file=chooser("패드 소리 선택","오디오 (최대 16 MB)","*.wav","*.mp3","*.aiff","*.aif").showOpenDialog(stage);
        if(file==null)return;
        var before=snapshot();loadAsync(()->{var changed=new ArrayList<>(before.pads());changed.set(index,Sounds.importFile(file.toPath(),before.pads().get(index).key()));
            return new Beat.Project(changed,before.hits(),before.length(),before.bpm(),before.pitch());},true,currentFile);
    }
    private void openProject(){
        if(busy || recording)return;
        if(!confirmDiscard())return;
        File file=chooser("비트 열기","Pocket Groove 프로젝트","*.mdrum").showOpenDialog(stage);
        if(file!=null){stopEverything();loadAsync(()->ProjectIO.load(file.toPath()),false,file.toPath());}
    }
    private void saveProject(){
        if(busy || recording)return;
        var chooser=chooser("비트 저장","Pocket Groove 프로젝트","*.mdrum");chooser.setInitialFileName(currentFile==null?"My groove.mdrum":currentFile.getFileName().toString());
        File file=chooser.showSaveDialog(stage);if(file==null)return;
        Path path=file.toPath();if(!path.toString().toLowerCase(Locale.ROOT).endsWith(".mdrum")){
            path=Path.of(path+".mdrum");if(Files.exists(path)&&!confirm("파일 덮어쓰기","같은 이름의 프로젝트를 바꿀까요?"))return;}
        final Path destination=path;var project=snapshot();setBusy(true,"소리와 비트를 저장하고 있습니다…");
        runIO(()->{ProjectIO.save(destination,project);return destination;},p->{currentFile=p;dirty=false;updateTitle();status.setText("저장했습니다. 이 프로젝트 파일에 사용한 소리도 포함되어 있습니다.");});
    }
    private record Loaded(Beat.Project project,Sounds bank){}
    private void loadAsync(Callable<Beat.Project> source,boolean changed,Path path){
        setBusy(true,"소리 파일을 준비하고 있습니다…");
        runIO(()->{var p=source.call();for(var pad:p.pads())KeyCombination.valueOf(pad.key());return new Loaded(p,new Sounds(p.pads()));},loaded->{
            player.stop();playing=false;if(sounds!=null)sounds.close();sounds=loaded.bank();
            var p=loaded.project();kit=p.pads();hits.clear();hits.addAll(p.hits());length=p.length();
            restoring=true;tempo.setValue(p.bpm());pitch.setValue(p.pitch());restoring=false;
            currentFile=path;dirty=changed;updateTitle();refreshPads();count.setText(hits.size()+" hits");updateControls();
            status.setText("준비되었습니다. 처음 사용한다면 각 패드의 ‘키’ 버튼으로 연결하세요.");
        });
    }
    private <T> void runIO(Callable<T> task,Consumer<T> success){
        io.submit(()->{try{T result=task.call();Platform.runLater(()->{if(closed){if(result instanceof Loaded l)l.bank().close();return;}setBusy(false,"");success.accept(result);});}
        catch(Exception e){Platform.runLater(()->{if(!closed){setBusy(false,"");error(e);}});}});
    }
    private void setBusy(boolean value,String message){busy=value;root.setDisable(value);if(!message.isEmpty())status.setText(message);}
    private void drawTape(long now){
        var g=tape.getGraphicsContext2D();double w=tape.getWidth(),left=54,usable=w-left-8;
        g.clearRect(0,0,w,116);g.setFont(javafx.scene.text.Font.font("Malgun Gothic",10));
        for(int i=0;i<6;i++){g.setFill(Color.web("#6c6379"));g.fillText(Sounds.NAMES[i],0,19+i*17);g.setStroke(Color.web("#e4e1e9"));g.strokeLine(left,15+i*17,w,15+i*17);}
        int ticks=(int)Math.min(32,Math.ceil(length));
        for(int i=0;i<=ticks;i++){double x=left+usable*i/ticks;g.setStroke(Color.web(i%4==0?"#d1cbd9":"#e7e4ec"));g.strokeLine(x,5,x,109);}
        for(var hit:hits){g.setFill(Color.web(COLORS[hit.pad()]).darker());g.fillRoundRect(left+usable*hit.beat()/length,9+hit.pad()*17,5,12,3,3);}
        if(hits.isEmpty()&&!recording){g.setFill(Color.web("#635a70"));g.fillText("녹음하면 이곳에 여섯 개의 리듬이 쌓입니다.",left+24,58);}
        if(playing||recording){double position=recording?Beat.elapsedBeats(now-recordStart,tempo.getValue()):player.position()%length;
            double x=left+usable*Math.min(1,position/length);g.setStroke(Color.web(recording?"#ad344f":"#55436e"));g.setLineWidth(2);g.strokeLine(x,2,x,112);g.setLineWidth(1);}
        count.setText(hits.size()+" hits  /  "+String.format(Locale.ROOT,"%.0f",length)+" beats");
    }
    @Override public void stop(){closed=true;if(player!=null)player.close();if(sounds!=null)sounds.close();io.shutdownNow();}
}

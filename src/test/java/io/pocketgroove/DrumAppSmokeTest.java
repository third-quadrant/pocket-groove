package io.pocketgroove;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="groove.uiTest",matches="true")
class DrumAppSmokeTest {
    private static <T> T fx(Callable<T> work) throws Exception {
        var result=new CompletableFuture<T>();
        Platform.runLater(()->{try{result.complete(work.call());}catch(Throwable e){result.completeExceptionally(e);}});
        return result.get(20,TimeUnit.SECONDS);
    }
    @Test void sixPadsKeyLearningRecordingAndNativeLayoutWork() throws Exception {
        Platform.startup(()->{});Platform.setImplicitExit(false);
        var ready=new CompletableFuture<Void>();
        var app=new DrumApp();
        Stage stage=fx(()->{
            var s=new Stage();app.start(s);
            s.getScene().lookup(".app").disabledProperty().addListener((o,a,b)->{if(!b)ready.complete(null);});
            return s;
        });
        try {
            ready.get(30,TimeUnit.SECONDS);
            fx(()->{
                Scene scene=stage.getScene();
                assertEquals(6,scene.getRoot().lookupAll(".pad").size());
                assertEquals(6,scene.getRoot().lookupAll(".key-button").size());
                capture(scene,"desktop");
                var key=(Button)scene.getRoot().lookupAll(".key-button").stream().filter(n->((Button)n).getText().equals("키  1")).findFirst().orElseThrow();
                key.fire();
                press(scene,KeyCode.A);
                assertEquals("키  A",key.getText());
                Event.fireEvent(scene,new KeyEvent(KeyEvent.KEY_RELEASED,"","",KeyCode.A,false,false,false,false));
                ((Button)scene.lookup("#record")).fire();
                press(scene,KeyCode.A);press(scene,KeyCode.A);
                Event.fireEvent(scene,new KeyEvent(KeyEvent.KEY_RELEASED,"","",KeyCode.A,false,false,false,false));
                press(scene,KeyCode.DIGIT2);
                ((Button)scene.lookup("#stop")).fire();
                assertTrue(scene.getRoot().lookupAll(".label").stream().map(n->((Label)n).getText()).anyMatch(t->t.startsWith("2 hits")),"held keys must not repeat");
                ((Button)scene.lookup("#play")).fire();
                assertTrue(scene.getRoot().lookupAll(".label").stream().map(n->((Label)n).getText()).anyMatch("반복 중"::equals));
                ((Button)scene.lookup("#stop")).fire();
                var invalid=new java.util.ArrayList<>(Sounds.defaults());
                invalid.set(0,new Beat.Pad("Invalid","A","wav",new byte[]{1,2,3}));
                assertThrows(java.io.IOException.class,()->new Sounds(invalid));
                stage.setWidth(960);stage.setHeight(760);
                return null;
            });
            fx(()->{
                var scene=stage.getScene();scene.getRoot().applyCss();scene.getRoot().layout();capture(scene,"compact");
                var save=scene.lookup("#save");
                assertTrue(save.localToScene(save.getBoundsInLocal()).getMaxX()<=scene.getWidth(),"file actions must fit narrow window");
                return null;
            });
        } finally {fx(()->{app.stop();stage.hide();return null;});Platform.exit();}
    }
    private static void press(Scene scene,KeyCode code){Event.fireEvent(scene,new KeyEvent(KeyEvent.KEY_PRESSED,"","",code,false,false,false,false));}
    private static void capture(Scene scene,String name) throws Exception {
        var image=scene.snapshot(null);
        var buffered=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<buffered.getHeight();y++)for(int x=0;x<buffered.getWidth();x++)buffered.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        Files.createDirectories(Path.of("target/screenshots"));
        ImageIO.write(buffered,"png",Path.of("target/screenshots/"+name+".png").toFile());
    }
}

package io.pocketgroove;

import javafx.scene.media.AudioClip;
import java.io.*;
import java.nio.file.*;
import java.util.*;

final class Sounds implements AutoCloseable {
    static final String[] NAMES={"Kick", "Snare", "Hi-hat", "Clap", "Tom", "Ride"};
    static final String[] FILES={"kick", "snare", "hat", "clap", "tom", "ride"};
    private final Path directory;
    private final AudioClip[] clips=new AudioClip[6];

    Sounds(List<Beat.Pad> pads) throws IOException {
        directory=Files.createTempDirectory("pocket-groove-");
        try {
            for (int i=0;i<6;i++) {
                Path file=directory.resolve(i+"."+pads.get(i).format());
                Files.write(file,pads.get(i).audio());
                clips[i]=new AudioClip(file.toUri().toString());
            }
        } catch (Exception e) { close(); throw new IOException("소리를 열 수 없습니다. PCM WAV, MP3 또는 AIFF 파일을 선택하세요.",e); }
    }
    static List<Beat.Pad> defaults() throws IOException {
        var pads=new ArrayList<Beat.Pad>();
        for (int i=0;i<6;i++) {
            try (var input=Sounds.class.getResourceAsStream("/sounds/"+FILES[i]+".wav")) {
                if(input==null) throw new IOException("기본 소리가 누락되었습니다.");
                pads.add(new Beat.Pad(NAMES[i],Integer.toString(i+1),"wav",input.readAllBytes()));
            }
        }
        return pads;
    }
    static Beat.Pad importFile(Path file, String key) throws IOException {
        String name=file.getFileName().toString();
        int dot=name.lastIndexOf('.');
        String format=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
        if (format.equals("aif")) format="aiff";
        try (var input=Files.newInputStream(file)) {
            return new Beat.Pad(name,key,format,input.readNBytes(Beat.MAX_SAMPLE_BYTES+1));
        }
    }
    void play(int pad, double volume, double pitch) { clips[pad].play(volume,0,Beat.pitchRate(pitch),0,0); }
    void stopAll() { for(var clip:clips) if(clip!=null) clip.stop(); }
    @Override public void close() {
        stopAll();
        try (var files=Files.list(directory)) {
            for(Path path:files.toList()) Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        } catch(IOException e) { directory.toFile().deleteOnExit(); }
    }
}

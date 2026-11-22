package io.pocketgroove;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import javax.sound.sampled.*;

final class Sounds {
    record Pcm(float[] stereo, int sampleRate) {}
    static final int MAX_PCM_BYTES=64*1024*1024;
    static List<Pcm> decodeAll(List<Beat.Track> tracks) throws IOException {
        var decoded=new ArrayList<Pcm>();long bytes=0;
        for(var track:tracks){var pcm=decode(track);bytes+=(long)pcm.stereo().length*4;
            if(bytes>192L*1024*1024)throw new IOException("소리 전체가 너무 큽니다. 짧은 오디오 구간을 선택하세요 (전체 PCM 192 MB 제한).");
            decoded.add(pcm);
        }
        return List.copyOf(decoded);
    }
    static Pcm decode(Beat.Track track) throws IOException {
        try(var source=AudioSystem.getAudioInputStream(new BufferedInputStream(new ByteArrayInputStream(track.audio())))) {
            var format=source.getFormat();int channels=format.getChannels();float rate=format.getSampleRate();
            if(channels<1 || channels>2 || !Float.isFinite(rate) || rate<8000 || rate>192000)
                throw new IOException("모노 또는 스테레오, 8–192 kHz 오디오를 선택하세요.");
            var target=new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,rate,16,channels,channels*2,rate,false);
            try(var pcm=AudioSystem.getAudioInputStream(target,source)) {
                int maxRaw=MAX_PCM_BYTES/8*channels*2;
                byte[] bytes=pcm.readNBytes(maxRaw+channels*2);
                if(bytes.length>maxRaw)throw new IOException("소리가 너무 깁니다. 더 짧은 파일을 선택하세요 (PCM 64 MB 제한).");
                int frames=bytes.length/(channels*2);
                if(frames==0 || bytes.length%(channels*2)!=0)throw new IOException("오디오 내용이 비어 있거나 손상되었습니다.");
                float[] stereo=new float[frames*2];
                for(int i=0;i<frames;i++)for(int c=0;c<2;c++){
                    int at=(i*channels+Math.min(c,channels-1))*2;
                    stereo[i*2+c]=(short)((bytes[at]&255)|(bytes[at+1]<<8))/32768f;
                }
                return new Pcm(stereo,Math.round(rate));
            }
        } catch(UnsupportedAudioFileException | IllegalArgumentException e){
            throw new IOException("소리를 읽지 못했습니다. PCM WAV, MP3 또는 AIFF 파일을 확인하세요.",e);
        }
    }
    static int[] silenceBounds(Pcm pcm) {
        int frames=pcm.stereo().length/2;
        int first=-1,last=-1;
        for(int i=0;i<frames;i++){
            if(Math.abs(pcm.stereo()[i*2])>0.012 || Math.abs(pcm.stereo()[i*2+1])>0.012){
                if(first<0)first=i;
                last=i;
            }
        }
        return first<0 ? new int[]{0,frames} : new int[]{first,last+1};
    }

    static Pcm crop(Pcm pcm, int startFrame, int endFrame) {
        int frames=pcm.stereo().length/2;
        if(startFrame<0 || endFrame>frames || endFrame<=startFrame)
            throw new IllegalArgumentException("자를 오디오 구간이 올바르지 않습니다.");
        return new Pcm(Arrays.copyOfRange(pcm.stereo(),startFrame*2,endFrame*2),pcm.sampleRate());
    }

    static byte[] pcmBytes(Pcm pcm) {
        int frames=pcm.stereo().length/2;
        byte[] raw=new byte[frames*4];
        for(int i=0;i<pcm.stereo().length;i++){
            int value=Math.round(Math.max(-1f,Math.min(1f,pcm.stereo()[i]))*32767);
            raw[i*2]=(byte)value;
            raw[i*2+1]=(byte)(value>>8);
        }
        return raw;
    }

    static byte[] wavBytes(Pcm pcm) throws IOException {
        int frames=pcm.stereo().length/2;
        byte[] raw=pcmBytes(pcm);
        var format=new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,pcm.sampleRate(),16,2,4,pcm.sampleRate(),false);
        var out=new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(raw),format,frames),
                AudioFileFormat.Type.WAVE,out);
        if(out.size()>Beat.MAX_SAMPLE_BYTES)
            throw new IOException("자를 구간이 너무 깁니다. 16 MB 이하로 선택하세요.");
        return out.toByteArray();
    }

    static final String[] NAMES={"Kick", "Snare", "Closed Hi-Hat", "Open Hi-Hat", "Clap", "Tom", "Percussion"};
    static final String[] FILES={"kick", "snare", "hat", "openhat", "clap", "tom", "perc"};
    static List<Beat.Track> defaults() throws IOException {
        var tracks=new ArrayList<Beat.Track>();
        for (int i=0;i<NAMES.length;i++) {
            try (var input=Sounds.class.getResourceAsStream("/sounds/"+FILES[i]+".wav")) {
                if(input==null) throw new IOException("기본 소리가 누락되었습니다.");
                tracks.add(new Beat.Track(NAMES[i],Integer.toString(i+1),"wav",input.readAllBytes()));
            }
        }
        return tracks;
    }
    static Beat.Track importFile(java.nio.file.Path file, String key) throws IOException {
        String name=file.getFileName().toString();
        int dot=name.lastIndexOf('.');
        String format=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
        if (format.equals("aif")) format="aiff";
        String display=dot<0?name:name.substring(0,dot);
        try (var input=Files.newInputStream(file)) {
            return new Beat.Track(display,key,format,input.readNBytes(Beat.MAX_SAMPLE_BYTES+1));
        }
    }
    static String nextFreeKey(List<Beat.Track> tracks) {
        String candidates="1234567890QWERTYUIOPASDFGHJKLZXCVBNM";
        for (int i=0;i<candidates.length();i++) {
            String key=String.valueOf(candidates.charAt(i));
            if (tracks.stream().noneMatch(t -> KeyBindings.same(t.key(),key))) return key;
        }
        return "F12";
    }
}

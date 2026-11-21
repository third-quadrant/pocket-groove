package io.pocketgroove;

import java.util.concurrent.*;
import java.util.function.IntConsumer;

/** The audio clock runs independently of JavaFX painting. */
final class LoopPlayer implements AutoCloseable {
    private final ScheduledExecutorService clock;
    private final IntConsumer trigger;
    private Beat.Project project;
    private double position, previous, bpm;
    private long last;
    private boolean running;

    LoopPlayer(IntConsumer trigger, boolean realtime) {
        this.trigger = trigger;
        clock = Executors.newSingleThreadScheduledExecutor(r -> { var t=new Thread(r,"groove-clock"); t.setDaemon(true); return t; });
        if (realtime) clock.scheduleAtFixedRate(() -> tick(System.nanoTime()),0,3,TimeUnit.MILLISECONDS);
    }
    synchronized void play(Beat.Project project, long now) {
        this.project=project; bpm=project.bpm(); last=now; position=0; previous=-0.000000001; running=true;
        tick(now);
    }
    synchronized void tick(long now) {
        if (!running) return;
        position += Beat.elapsedBeats(Math.max(0,now-last),bpm); last=now;
        for (int pad : Beat.crossed(project.hits(),project.length(),previous,position)) trigger.accept(pad);
        previous=position;
    }
    synchronized void tempo(double bpm, long now) { tick(now); this.bpm=bpm; }
    synchronized double position() { return position; }
    synchronized void stop() { running=false; position=0; }
    @Override public void close() { stop(); clock.shutdownNow(); }
}

package com.feiyu.nightterminal;

import java.util.ArrayList;

/** Accessed on the main thread; queued service starts and draining workers still own capture. */
final class MicrophoneHandoff {
    private boolean startPending, conversation;
    private int workers;
    private final ArrayList<Runnable> callbacks = new ArrayList<>();

    void queueStart() { startPending = true; conversation = false; }
    void startResolved() { startPending = false; acknowledge(); }
    void pause(Runnable done) {
        conversation = true;
        if (done != null) callbacks.add(done);
        acknowledge();
    }
    void conversationEnded() { conversation = false; }
    boolean canCapture() { return !startPending && !conversation && workers == 0; }
    void workerStarted() {
        if (!canCapture()) throw new IllegalStateException("microphone already owned");
        workers++;
    }
    void workerReleased() {
        if (workers == 0) throw new IllegalStateException("no microphone owner");
        workers--;
        acknowledge();
    }
    private void acknowledge() {
        if (startPending || workers != 0) return;
        ArrayList<Runnable> ready = new ArrayList<>(callbacks);
        callbacks.clear();
        for (Runnable callback : ready) callback.run();
    }
}

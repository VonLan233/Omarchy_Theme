package com.feiyu.nightterminal;

public final class MicrophoneHandoffCheck {
    public static void main(String[] args) {
        MicrophoneHandoff queued = new MicrophoneHandoff();
        int[] ready = {0};
        queued.queueStart();
        queued.pause(() -> ready[0]++);
        check(ready[0] == 0 && !queued.canCapture(), "queued start acknowledged before creation");
        queued.startResolved();
        check(ready[0] == 1 && !queued.canCapture(), "queued start must remain paused for browser capture");
        queued.conversationEnded();
        check(queued.canCapture(), "explicit conversation end should allow standby");

        MicrophoneHandoff draining = new MicrophoneHandoff();
        int[] released = {0};
        draining.workerStarted();
        draining.pause(() -> released[0]++);
        check(released[0] == 0, "destroyed service's draining worker still owns microphone");
        draining.queueStart();
        draining.pause(() -> released[0]++);
        draining.startResolved();
        check(released[0] == 0 && !draining.canCapture(), "new service cannot acknowledge over old worker");
        draining.workerReleased();
        check(released[0] == 2 && !draining.canCapture(), "acknowledge only after old native resources release");
        draining.conversationEnded();
        check(draining.canCapture(), "new service may capture after conversation releases ownership");
        System.out.println("Microphone handoff checks passed: queued start, draining destroy, replacement service, release order.");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package com.feiyu.nightterminal;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.util.UUID;
import java.util.function.Consumer;

public class WakeWordService extends Service {
    private static final String STOP = "stop";
    private static WakeWordService current;
    private static String wakeToken;
    private static Consumer<String> listener;
    private static final MicrophoneHandoff handoff = new MicrophoneHandoff();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean paused = true;
    private volatile AudioRecord recorder;
    private Thread worker;
    private boolean destroyed;
    private String state = "stopped";

    static String state() { return current == null ? "stopped" : current.state; }
    static void listen(Consumer<String> next) { listener = next; }
    static void begin(Context context) {
        handoff.queueStart();
        try { context.startForegroundService(new Intent(context, WakeWordService.class)); }
        catch (RuntimeException failure) { handoff.startResolved(); throw failure; }
    }
    static void stop(Context context) {
        if (current != null) { current.destroyed = true; current.pauseCapture(); }
        context.stopService(new Intent(context, WakeWordService.class));
    }
    static void pauseForConversation(Runnable done) {
        handoff.pause(done);
        if (current != null) current.pauseCapture();
    }
    static void conversationEnded() {
        handoff.conversationEnded();
        if (current != null && !current.destroyed) {
            current.capture();
        }
    }
    static Intent wakeIntent(Context context) {
        wakeToken = UUID.randomUUID().toString();
        return new Intent(context, VoiceActivity.class).putExtra("wake_token", wakeToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    static boolean consumeWake(Intent intent) {
        String token = intent.getStringExtra("wake_token");
        if (wakeToken == null || !wakeToken.equals(token)) return false;
        wakeToken = null;
        return true;
    }

    @Override public void onCreate() {
        super.onCreate();
        current = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("local_wake", "大肥鱼本地待机", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel("wake_ready", "大肥鱼唤醒", NotificationManager.IMPORTANCE_HIGH));
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        handoff.startResolved();
        if (intent == null || STOP.equals(intent.getAction())) {
            destroyed = true; pauseCapture(); stopSelf(); return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY;
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel("local_wake").getImportance() == NotificationManager.IMPORTANCE_NONE ||
                notifications.getNotificationChannel("wake_ready").getImportance() == NotificationManager.IMPORTANCE_NONE) {
            if (listener != null) listener.accept("notification_permission");
            stopSelf(); return START_NOT_STICKY;
        }
        startForeground(71, notification(false), Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
        capture();
        return START_NOT_STICKY;
    }
    private Notification notification(boolean wake) {
        Intent open = wake ? wakeIntent(this) : new Intent(this, VoiceActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent show = PendingIntent.getActivity(this, 71, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 72, new Intent(this, WakeWordService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = wake ? "已听到「大肥鱼」· 点按对话" : "大肥鱼 · 本地后台待机";
        String detail = "ready".equals(state) ? "麦克风只供离线检测 · 点按停止可关闭" :
                "loading".equals(state) ? "正在加载本地模型…" : "对话期间暂停本地检测 · 结束后继续待机";
        return new Notification.Builder(this, wake ? "wake_ready" : "local_wake")
                .setSmallIcon(R.drawable.pixel_voice).setContentTitle(title).setContentText(detail)
                .setContentIntent(show).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "停止待机", stop).build()).build();
    }
    private void publish(String next) {
        state = next;
        if (listener != null) listener.accept(next);
        if (!destroyed) getSystemService(NotificationManager.class).notify(71, notification("wake".equals(next)));
    }
    private void capture() {
        if (destroyed || worker != null || !handoff.canCapture()) return;
        handoff.workerStarted();
        paused = false;
        publish("loading");
        worker = new Thread(() -> {
            boolean detected = false;
            String error = null;
            PowerManager.WakeLock lock = getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":local-wake");
            AudioRecord audio = null;
            try (LocalKeywordDetector detector = new LocalKeywordDetector(getAssets())) {
                if (!paused) {
                    int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    if (minimum <= 0) throw new IllegalStateException("unsupported microphone");
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        throw new SecurityException("microphone permission revoked");
                    audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 6400));
                    if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("microphone initialization failed");
                    recorder = audio;
                    lock.acquire();
                    if (!paused) audio.startRecording();
                    main.post(() -> { if (!paused && !destroyed) publish("ready"); });
                    short[] pcm = new short[1600];
                    while (!paused) {
                        int count = audio.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                        if (paused) break;
                        if (count <= 0) throw new IllegalStateException("microphone read failed");
                        float[] samples = new float[count];
                        for (int i = 0; i < count; i++) samples[i] = pcm[i] / 32768f;
                        if (detector.accept(samples)) { detected = true; paused = true; }
                    }
                }
            } catch (Exception | LinkageError failure) {
                if (!paused) error = "error";
            } finally {
                if (audio != null) {
                    try { audio.stop(); } catch (IllegalStateException ignored) { }
                    audio.release();
                }
                recorder = null;
                if (lock.isHeld()) lock.release();
            }
            boolean wake = detected;
            String failed = error;
            main.post(() -> {
                worker = null;
                if (destroyed) {
                    if (current == this) current = null;
                    handoff.workerReleased();
                    if (current != null && !current.destroyed) current.capture();
                    return;
                }
                if (wake) handoff.pause(null);
                if (failed != null) {
                    destroyed = true; publish(failed); stopSelf(); handoff.workerReleased(); return;
                }
                handoff.workerReleased();
                publish(wake ? "wake" : "paused");
                if (wake && !VoiceActivity.visible && !FeiyuAssistantService.showWake(this)) {
                    getSystemService(NotificationManager.class).notify(71, notification(true));
                }
                capture();
            });
        }, "local-chinese-wake");
        worker.start();
    }
    private void pauseCapture() {
        paused = true;
        AudioRecord audio = recorder;
        if (audio != null) try { audio.stop(); } catch (IllegalStateException ignored) { }
    }
    @Override public void onDestroy() {
        destroyed = true;
        pauseCapture();
        if (current == this && worker == null) current = null;
        state = "stopped";
        if (listener != null) listener.accept("stopped");
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (worker == null) handoff.startResolved();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

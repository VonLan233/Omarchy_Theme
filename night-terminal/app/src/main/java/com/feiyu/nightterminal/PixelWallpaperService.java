package com.feiyu.nightterminal;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

public class PixelWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() { return new PixelEngine(); }

    final class PixelEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final PixelMotion motion = new PixelMotion(PixelWallpaperService.this);
        private final KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        private boolean visible, surfaceReady, destroyed, wasLocked, pendingUnlock, animate;
        private final Runnable frame = this::drawFrame;
        private final ContentObserver scaleObserver = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) { updateMotion(); drawFrame(); }
        };
        private final BroadcastReceiver events = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                updateMotion();
                if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    pendingUnlock = true; wasLocked = false;
                }
                drawFrame();
            }
        };

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            wasLocked = keyguard != null && keyguard.isKeyguardLocked();
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(events, filter);
            getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleObserver);
        }

        @Override public void onVisibilityChanged(boolean value) {
            visible = value;
            boolean locked = keyguard != null && keyguard.isKeyguardLocked();
            if (wasLocked && !locked) pendingUnlock = true;
            wasLocked = locked;
            if (value) updateMotion();
            drawFrame();
        }

        private void updateMotion() {
            animate = PixelMotion.animationAllowed(PixelWallpaperService.this);
            motion.setAnimated(animate);
        }

        @Override public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder); surfaceReady = true; drawFrame();
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height); drawFrame();
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false; handler.removeCallbacks(frame); super.onSurfaceDestroyed(holder);
        }

        @Override public void onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN && visible) {
                motion.pulse(event.getX(), event.getY()); drawFrame();
            }
            super.onTouchEvent(event);
        }

        private void drawFrame() {
            handler.removeCallbacks(frame);
            if (!visible || !surfaceReady || destroyed) return;
            if (pendingUnlock) { motion.unlock(); pendingUnlock = false; }
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) motion.draw(canvas, canvas.getWidth(), canvas.getHeight(), SystemClock.uptimeMillis());
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // A surface can disappear between its lifecycle callback and lockCanvas.
            } finally {
                if (canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas); }
                    catch (IllegalArgumentException | IllegalStateException ignored) {
                        // Surface teardown may also race with posting the completed frame.
                    }
                }
            }
            if (animate && visible && surfaceReady && !destroyed) handler.postDelayed(frame, PixelMotion.FRAME_MS);
        }

        @Override public void onDestroy() {
            destroyed = true; visible = false; handler.removeCallbacks(frame);
            unregisterReceiver(events); getContentResolver().unregisterContentObserver(scaleObserver);
            motion.recycle(); super.onDestroy();
        }
    }
}

package com.feiyu.nightterminal;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

public class MotionPreviewActivity extends Activity {
    private Preview preview;
    private Button replay;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ContentObserver scaleObserver = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { preview.updateMotion(); preview.invalidate(); }
    };
    private final BroadcastReceiver powerEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { preview.updateMotion(); preview.invalidate(); }
    };

    static void applyWallpaper(Activity activity) {
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(activity, PixelWallpaperService.class));
        try { activity.startActivity(intent); }
        catch (android.content.ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, "请在系统壁纸设置中选择 Midnight Raster 动态壁纸", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.BG);
        TextView heading = new TextView(this);
        heading.setTypeface(getResources().getFont(R.font.pixel_body));
        heading.setText("设计预览 · Midnight Raster\n时钟与解锁布局为预演，系统负责真实解锁");
        heading.setTextColor(MainActivity.WHITE);
        heading.setTextSize(14);
        heading.setPadding(dp(20), dp(14), dp(20), dp(12));
        root.addView(heading);
        preview = new Preview();
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(this);
        controls.setPadding(dp(12), dp(6), dp(12), dp(10));
        replay = control("预演解锁", () -> {
            preview.started = SystemClock.uptimeMillis();
            if (!preview.animate) preview.started -= PixelMotion.BURST_MS;
            preview.motion.unlock();
            replay.setText("重播预演");
            preview.describe(); preview.invalidate();
        });
        controls.addView(replay, new LinearLayout.LayoutParams(0, dp(52), 1));
        controls.addView(control("应用动态壁纸", () -> applyWallpaper(this)), new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(controls);
        setContentView(root);
        IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(powerEvents, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(powerEvents, filter);
        getContentResolver().registerContentObserver(Settings.Global.getUriFor(
            Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleObserver);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private Button control(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(13); button.setAllCaps(false);
        button.setTypeface(getResources().getFont(R.font.pixel_body));
        button.setTextColor(MainActivity.WHITE); button.setOnClickListener(v -> action.run());
        return button;
    }

    @Override protected void onResume() { super.onResume(); preview.resumed = true; preview.updateMotion(); preview.invalidate(); }
    @Override protected void onPause() {
        preview.resumed = false; preview.removeCallbacks(preview.frame); super.onPause();
    }
    @Override protected void onDestroy() {
        unregisterReceiver(powerEvents); getContentResolver().unregisterContentObserver(scaleObserver);
        preview.removeCallbacks(preview.frame); preview.motion.recycle();
        for (Bitmap icon : preview.iconCache.values()) icon.recycle();
        super.onDestroy();
    }

    final class Preview extends View {
        private final PixelMotion motion = new PixelMotion(MotionPreviewActivity.this);
        private final Paint ink = new Paint();
        private final Typeface body = getResources().getFont(R.font.pixel_body);
        private final Typeface display = getResources().getFont(R.font.pixel_display);
        private final String[] labels = new String[MainActivity.SLOTS.length];
        private final Bitmap[] icons = new Bitmap[labels.length];
        private final Map<String, Bitmap> iconCache = new HashMap<>();
        private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final SimpleDateFormat date = new SimpleDateFormat("M月d日 EEE", Locale.SIMPLIFIED_CHINESE);
        private final Date currentDate = new Date();
        private final Rect clockBounds = new Rect();
        private final RectF iconTarget = new RectF();
        private final Runnable frame = this::invalidate;
        private long started = -1;
        private long clockMinute = -1, spokenMinute = -1;
        private boolean resumed, animate;
        private String clockValue, dateValue;
        private int spokenState = -1;
        private float clockSize;
        private Shader clockGradient;

        Preview() {
            super(MotionPreviewActivity.this);
            setFocusable(true);
            for (int i = 0; i < icons.length; i++) {
                String saved = getSharedPreferences("desktop", MODE_PRIVATE).getString("slot" + i, "");
                ComponentName component = ComponentName.unflattenFromString(saved);
                ResolveInfo chosen = component == null ? null : getPackageManager().resolveActivity(Intent.makeMainActivity(component), 0);
                labels[i] = chosen == null ? MainActivity.SLOTS[i][1] : chosen.loadLabel(getPackageManager()).toString();
                String pkg = chosen == null ? MainActivity.SLOTS[i][2] : chosen.activityInfo.packageName;
                icons[i] = ((BitmapDrawable) MainActivity.pixelIcon(MotionPreviewActivity.this, pkg, chosen, iconCache)).getBitmap();
            }
            ink.setFilterBitmap(false); ink.setAntiAlias(false); describe();
        }

        private void describe() {
            updateStatus();
            int state = started < 0 ? 0 : PixelMotion.progress(SystemClock.uptimeMillis(), started) < 1 ? 1 : 2;
            if (state == spokenState && clockMinute == spokenMinute) return;
            spokenState = state; spokenMinute = clockMinute;
            setContentDescription("设计预览，" + clockValue + "，" + dateValue + "，"
                + (state == 0 ? "锁屏布局" : state == 1 ? "正在预演解锁" : "桌面布局")
                + "，使用下方按钮预演与重播解锁");
        }

        private void updateStatus() {
            long wallTime = System.currentTimeMillis();
            if (clockMinute == wallTime / 60000) return;
            clockMinute = wallTime / 60000; currentDate.setTime(wallTime);
            clockValue = clock.format(currentDate); dateValue = date.format(currentDate);
            ink.setTypeface(display); ink.setTextSize(56);
            clockSize = Math.min(56, 56 * 338 / ink.measureText(clockValue));
            ink.setTextSize(clockSize); ink.getTextBounds(clockValue, 0, clockValue.length(), clockBounds);
            clockGradient = new LinearGradient(0, 205 + clockBounds.top, 0, 205 + clockBounds.bottom,
                new int[]{MainActivity.WHITE, MainActivity.WHITE, MainActivity.MUTED, MainActivity.MUTED,
                    0xFF74627F, 0xFF74627F, MainActivity.LINE, MainActivity.LINE},
                new float[]{0, .30f, .30f, .55f, .55f, .80f, .80f, 1}, Shader.TileMode.CLAMP);
        }

        private void updateMotion() {
            animate = PixelMotion.animationAllowed(MotionPreviewActivity.this);
            motion.setAnimated(animate);
        }

        private void text(Canvas canvas, String value, float x, float y, float size, int color, boolean center) {
            ink.setShader(null); ink.setTypeface(body); ink.setTextSize(size); ink.setColor(color);
            ink.setTextAlign(center ? Paint.Align.CENTER : Paint.Align.LEFT);
            canvas.drawText(value, x, y, ink);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            removeCallbacks(frame);
            long now = SystemClock.uptimeMillis();
            if (!animate && started >= 0) started = Math.min(started, now - PixelMotion.BURST_MS);
            motion.draw(canvas, getWidth(), getHeight(), now);
            int save = canvas.save();
            canvas.scale(getWidth() / 420f, getHeight() / 900f);
            float progress = PixelMotion.progress(now, started);
            describe();
            text(canvas, "feiyu@oppo", 24, 52, 16, MainActivity.MUTED, false);
            text(canvas, started < 0 ? "LOCK / 预览" : progress < 1 ? "REASSEMBLE" : "HOME / 预览",
                348, 52, 11, MainActivity.GREEN, true);
            ink.setTypeface(display); ink.setTextSize(clockSize); ink.setColor(MainActivity.WHITE); ink.setTextAlign(Paint.Align.CENTER);
            ink.setShader(clockGradient);
            canvas.save(); canvas.scale(1, 1.45f, 210, 205); canvas.drawText(clockValue, 210, 205, ink); canvas.restore();
            text(canvas, dateValue, 210, 264, 16, MainActivity.MUTED, true);
            if (started < 0 || progress < 1) {
                int lockLayer = canvas.saveLayerAlpha(0, 0, 420, 900, Math.round(255 * (1 - progress)));
                text(canvas, "MIDNIGHT RASTER", 210, 485, 16, MainActivity.WHITE, true);
                text(canvas, "像素在边缘流动", 210, 520, 14, MainActivity.MUTED, true);
                ink.setColor(MainActivity.GREEN);
                iconTarget.set(186, 652, 234, 700); canvas.drawBitmap(icons[0], null, iconTarget, ink);
                text(canvas, "点下方按钮，预演解锁", 210, 752, 14, MainActivity.MUTED, true);
                canvas.restoreToCount(lockLayer);
            }
            if (started >= 0) {
                int homeLayer = canvas.saveLayerAlpha(0, 0, 420, 900, Math.round(255 * progress));
                for (int i = 0; i < icons.length; i++) {
                    float x = i % 2 == 0 ? 128 : 288;
                    float y = 350 + (i / 2) * 145 + (1 - progress) * 28;
                    ink.setShader(null); ink.setColor(Color.WHITE);
                    iconTarget.set(x - 31, y, x + 31, y + 62); canvas.drawBitmap(icons[i], null, iconTarget, ink);
                    text(canvas, labels[i], x, y + 100, 16, MainActivity.WHITE, true);
                }
                text(canvas, "⌕  搜索应用 █", 210, 835, 16, MainActivity.WHITE, true);
                canvas.restoreToCount(homeLayer);
            }
            ink.setAlpha(255); ink.setShader(null); canvas.restoreToCount(save);
            if (resumed && getWindowVisibility() == VISIBLE && isShown())
                postDelayed(frame, animate ? PixelMotion.FRAME_MS : 15000);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                motion.pulse(event.getX(), event.getY()); invalidate(); return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) { performClick(); return true; }
            return super.onTouchEvent(event);
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != VISIBLE) removeCallbacks(frame);
            else if (resumed) invalidate();
        }
    }
}

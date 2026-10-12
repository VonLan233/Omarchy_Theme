package com.feiyu.nightterminal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.ArrayList;

/** The original raster, with its edge tiles lifted for gentle drift and reassembly. */
final class PixelMotion {
    static final long BURST_MS = 900;
    static final long FRAME_MS = 1000 / 24;
    private static final int CELL = 14;
    private final Bitmap raster, backdrop;
    private final ArrayList<Rect> tiles = new ArrayList<>();
    private final ArrayList<Bitmap> sprites = new ArrayList<>();
    private final Paint paint = new Paint();
    private final RectF target = new RectF();
    private final long origin = SystemClock.uptimeMillis();
    private long unlockAt = -1, pulseAt = -1;
    private float pulseX, pulseY;
    private boolean animated = true;

    PixelMotion(Context context) {
        raster = BitmapFactory.decodeResource(context.getResources(), R.drawable.midnight_wallpaper);
        backdrop = raster.copy(Bitmap.Config.ARGB_8888, true);
        Canvas clean = new Canvas(backdrop);
        int width = raster.getWidth(), height = raster.getHeight();
        int[] pixels = new int[width * height];
        raster.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int[] box : wholeTiles(pixels, width, height)) {
            Rect tile = new Rect(box[0] - 2, box[1] - 2, box[2] + 2, box[3] + 2);
            tiles.add(tile);
            Bitmap sprite = Bitmap.createBitmap(tile.width(), tile.height(), Bitmap.Config.ARGB_8888);
            for (int y = tile.top; y < tile.bottom; y++) {
                int background = pixels[y * width + tile.left - 3];
                int other = pixels[y * width + tile.right + 3];
                if ((other & 255) < (background & 255)) background = other;
                // shortcut: hidden texture is sampled locally; retain connected edge art rather than guessing its tiles.
                paint.setColor(background); clean.drawRect(tile.left, y, tile.right, y + 1, paint);
                for (int x = tile.left; x < tile.right; x++) {
                    int color = pixels[y * width + x];
                    // Transparent dark padding prevents moving crops from cutting into other whole squares.
                    if ((color & 255) >= 23) sprite.setPixel(x - tile.left, y - tile.top, color);
                }
            }
            sprites.add(sprite);
        }
        paint.setColor(Color.WHITE);
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
    }

    /** Select complete isolated source squares; connected edge regions remain untouched. */
    static ArrayList<int[]> wholeTiles(int[] pixels, int width, int height) {
        ArrayList<int[]> result = new ArrayList<>();
        boolean[] visited = new boolean[pixels.length];
        int[] queue = new int[pixels.length];
        for (int seed = 0; seed < pixels.length; seed++) {
            if (visited[seed] || (pixels[seed] & 255) < 36) continue;
            int read = 0, count = 1, left = width, top = height, right = 0, bottom = 0;
            queue[0] = seed; visited[seed] = true;
            while (read < count) {
                int pixel = queue[read++], x = pixel % width, y = pixel / width;
                left = Math.min(left, x); top = Math.min(top, y);
                right = Math.max(right, x + 1); bottom = Math.max(bottom, y + 1);
                for (int direction = 0; direction < 4; direction++) {
                    if (direction == 0 && x == 0 || direction == 1 && x == width - 1
                        || direction == 2 && y == 0 || direction == 3 && y == height - 1) continue;
                    int next = direction == 0 ? pixel - 1 : direction == 1 ? pixel + 1
                        : direction == 2 ? pixel - width : pixel + width;
                    if (!visited[next] && (pixels[next] & 255) >= 36) {
                        visited[next] = true; queue[count++] = next;
                    }
                }
            }
            int w = right - left, h = bottom - top;
            if (w < 7 || h < 7 || w > 32 || h > 32 || Math.abs(w - h) > 2 || count < w * h * .9f) continue;
            if (left < 8 || top < 8 || right + 8 >= width || bottom + 8 >= height) continue;
            boolean isolated = true;
            for (int y = top - 8; y < bottom + 8 && isolated; y++) {
                for (int x = left - 8; x < right + 8; x++) {
                    if (x >= left && x < right && y >= top && y < bottom) continue;
                    if ((pixels[y * width + x] & 255) >= 36) { isolated = false; break; }
                }
            }
            if (isolated) result.add(new int[]{left, top, right, bottom});
        }
        return result;
    }

    static boolean animationAllowed(float scale, boolean powerSave) { return scale > 0 && !powerSave; }

    static boolean animationAllowed(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return animationAllowed(Settings.Global.getFloat(context.getContentResolver(),
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f), power != null && power.isPowerSaveMode());
    }

    static float progress(long now, long start) {
        return start < 0 ? 0 : Math.max(0, Math.min(1, (now - start) / (float) BURST_MS));
    }

    static float scatter(float progress) { return progress <= 0 || progress >= 1 ? 0 : (float) Math.sin(Math.PI * progress); }

    void setAnimated(boolean enabled) {
        animated = enabled;
        if (!enabled) { unlockAt = -1; pulseAt = -1; }
    }

    void pulse(float x, float y) {
        if (!animated) return;
        pulseX = x; pulseY = y; pulseAt = SystemClock.uptimeMillis();
    }

    void unlock() { if (animated) unlockAt = SystemClock.uptimeMillis(); }

    void draw(Canvas canvas, int width, int height, long now) {
        target.set(0, 0, width, height);
        if (!animated) { canvas.drawBitmap(raster, null, target, paint); return; }
        canvas.drawBitmap(backdrop, null, target, paint);
        float sx = width / (float) raster.getWidth(), sy = height / (float) raster.getHeight();
        float time = (now - origin) / 1000f;
        float burst = scatter(progress(now, unlockAt));
        float pulse = pulseAt < 0 ? 2 : (now - pulseAt) / 800f;
        for (int i = 0; i < tiles.size(); i++) {
            Rect tile = tiles.get(i);
            float x = tile.left * sx, y = tile.top * sy;
            float phase = i * 2.399963f;
            float dx = (float) Math.sin(time * .7f + phase) * CELL * sx * .35f;
            float dy = (float) Math.cos(time * .55f + phase) * CELL * sy * .45f;
            // Scatter toward the perimeter, leaving the clock and shortcut center empty.
            dx += (x < width / 2f ? -1 : 1) * burst * CELL * sx * (2 + i % 5);
            dy += (float) Math.sin(phase) * burst * CELL * sy * (2 + i % 4);
            if (pulse >= 0 && pulse < 1) {
                float vx = x - pulseX, vy = y - pulseY;
                float distance = (float) Math.hypot(vx, vy);
                float ring = Math.max(0, 1 - Math.abs(distance - pulse * Math.max(width, height)) / (CELL * sx * 5));
                float push = ring * (1 - pulse) * CELL * sx * 2;
                if (distance > 0) { dx += vx / distance * push; dy += vy / distance * push; }
            }
            // Snap to the raster grid so motion remains chunky instead of blurred.
            float step = Math.max(1, CELL * sx / 4);
            dx = Math.round(dx / step) * step; dy = Math.round(dy / step) * step;
            target.set(x + dx, y + dy, x + dx + tile.width() * sx, y + dy + tile.height() * sy);
            canvas.drawBitmap(sprites.get(i), null, target, paint);
        }
    }

    void recycle() {
        for (Bitmap sprite : sprites) sprite.recycle();
        raster.recycle(); backdrop.recycle();
    }
}

package com.feiyu.nightterminal;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.WallpaperManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

/** Debug-only check of the real home window; it never changes the user's wallpaper. */
public class WallpaperVisibilityInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            boolean supported = getTargetContext().getSystemService(WallpaperManager.class).isWallpaperSupported();
            Activity home = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(() -> {
                int flags = home.getWindow().getAttributes().flags;
                results.putInt("window_flags", flags);
                results.putBoolean("show_wallpaper", (flags & WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) != 0);
            });
            results.putBoolean("wallpaper_supported", supported);
            if (results.getBoolean("show_wallpaper") != supported)
                throw new AssertionError("home wallpaper visibility does not match platform support");
            results.putString("result", "PASS: home wallpaper flag matches platform support; check Engine visibility with dumpsys");
            finish(Activity.RESULT_OK, results);
        } catch (Exception | AssertionError failure) {
            results.putString("error", failure.toString());
            finish(Activity.RESULT_CANCELED, results);
        }
    }
}

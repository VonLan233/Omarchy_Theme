package com.feiyu.nightterminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    static final int BG = 0xFF0F0E18, PANEL = 0xF2191624;
    static final int YELLOW = 0xFFC1AFD4, GREEN = 0xFF87BCAD;
    static final int WHITE = 0xFFDAD7CA, MUTED = 0xFFAAA0BA;
    static final int LINE = 0xFF3B2C47;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ResolveInfo> apps = new ArrayList<>();
    private SharedPreferences prefs;
    private TextView clock, date;
    private Typeface pixelBody;
    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private boolean drawer;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!drawer) updateStatus();
            handler.postDelayed(this, 15000);
        }
    };
    static final String[][] SLOTS = {
        {">_", "终端", "com.termux"}, {"NET", "Chrome", "com.android.chrome"},
        {"TG", "Telegram", "org.telegram.messenger"}, {"PAY", "支付宝", "com.eg.android.AlipayGphone"},
        {"DIR", "文件", "com.coloros.filemanager"}, {"SYS", "设置", "com.android.settings"}
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        pixelBody = getResources().getFont(R.font.pixel_body);
        prefs = getSharedPreferences("desktop", MODE_PRIVATE);
        loadApps();
        showEntryPoint();
    }

    @Override protected void onResume() {
        super.onResume();
        loadApps();
        // Refresh shortcut labels after an app is removed or renamed while away.
        if (!drawer) showHome();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showEntryPoint();
    }

    private void showEntryPoint() {
        ComponentName entry = getIntent().getComponent();
        if (entry != null && entry.getClassName().equals(getPackageName() + ".SearchActivity")) showApps();
        else showHome();
    }

    @Override public void onBackPressed() {
        if (drawer) showHome();
    }

    private void loadApps() {
        apps.clear();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        for (ResolveInfo app : getPackageManager().queryIntentActivities(query, 0)) {
            boolean voice = app.activityInfo.name.equals(VoiceActivity.class.getName());
            if ((!app.activityInfo.packageName.equals(getPackageName()) || voice) && seen.add(component(app))) apps.add(app);
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        apps.sort((a, b) -> collator.compare(label(a), label(b)));
    }

    private String label(ResolveInfo app) { return app.loadLabel(getPackageManager()).toString(); }
    private String component(ResolveInfo app) {
        return new ComponentName(app.activityInfo.packageName, app.activityInfo.name).flattenToString();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("tnum");
        view.setTypeface(pixelBody);
        view.setIncludeFontPadding(false);
        return view;
    }
    private Drawable surface(int fill, int border) {
        return new RippleDrawable(ColorStateList.valueOf(0x334E3E68), new ColorDrawable(fill), null);
    }
    private TextView button(String title, int color, Runnable action) {
        TextView view = text(title, 14, color);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48));
        view.setPadding(dp(10), dp(12), dp(10), dp(12));
        view.setBackground(surface(PANEL, LINE));
        view.setOnClickListener(v -> action.run());
        view.setFocusable(true);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        return view;
    }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private void weighted(LinearLayout row, View v, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        p.setMarginEnd(dp(margin)); row.addView(v, p);
    }

    private Drawable wallpaper(int resource) {
        BitmapDrawable art = (BitmapDrawable) getDrawable(resource);
        art.setFilterBitmap(false);
        art.setGravity(Gravity.FILL);
        return art;
    }

    private void stretch(LinearLayout parent, int minimum, float weight) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(minimum), weight));
    }

    private void showHome() {
        drawer = false;
        if (getCurrentFocus() != null) getSystemService(InputMethodManager.class)
            .hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        if (getSystemService(WallpaperManager.class).isWallpaperSupported()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            scroll.setBackgroundColor(Color.TRANSPARENT);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            getWindow().setBackgroundDrawable(new ColorDrawable(BG));
            scroll.setBackground(wallpaper(R.drawable.midnight_wallpaper));
        }
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout home = column();
        home.setPadding(dp(22), dp(12), dp(22), dp(16));
        scroll.addView(home);
        setContentView(scroll);

        TextView identity = text("feiyu@oppo", 16, MUTED);
        identity.setMinHeight(dp(48));
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setContentDescription("feiyu@oppo，桌面配置");
        identity.setOnClickListener(v -> showSettings());
        identity.setFocusable(true);
        home.addView(identity);
        stretch(home, 32, .8f);

        clock = new TextView(this) {
            @Override protected void onDraw(Canvas canvas) {
                android.graphics.Rect ink = new android.graphics.Rect();
                getPaint().getTextBounds(getText().toString(), 0, getText().length(), ink);
                float baseline = getLayout() == null ? getBaseline() : getLayout().getLineBaseline(0);
                float top = baseline + ink.top;
                float bottom = baseline + ink.bottom;
                getPaint().setShader(new LinearGradient(0, top, 0, bottom,
                    new int[]{WHITE, WHITE, 0xFFAAA0BA, 0xFFAAA0BA, 0xFF74627F, 0xFF74627F, LINE, LINE},
                    new float[]{0, .30f, .30f, .55f, .55f, .80f, .80f, 1}, Shader.TileMode.CLAMP));
                super.onDraw(canvas);
            }
        };
        clock.setTypeface(getResources().getFont(R.font.pixel_display));
        clock.setScaleY(1.45f);
        clock.setIncludeFontPadding(false);
        clock.setGravity(Gravity.CENTER);
        clock.setTextColor(WHITE);
        clock.setAutoSizeTextTypeUniformWithConfiguration(28, 56, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        clock.setMaxLines(1);
        clock.setOnClickListener(v -> open(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)));
        clock.setFocusable(true);
        home.addView(clock, new LinearLayout.LayoutParams(-1, dp(92)));
        date = text("", 16, MUTED);
        date.setGravity(Gravity.CENTER);
        home.addView(date, new LinearLayout.LayoutParams(-1, dp(28)));
        stretch(home, 32, .7f);

        for (int r = 0; r < 3; r++) {
            LinearLayout shortcuts = row();
            shortcuts.setPadding(dp(30), 0, dp(30), 0);
            for (int col = 0; col < 2; col++) {
                int slot = r * 2 + col;
                String stored = prefs.getString("slot" + slot, "");
                ResolveInfo chosen = apps.stream().filter(a -> component(a).equals(stored)).findFirst().orElse(null);
                String title = chosen == null ? SLOTS[slot][1] : label(chosen);
                String pkg = chosen == null ? SLOTS[slot][2] : chosen.activityInfo.packageName;
                LinearLayout key = column();
                key.setGravity(Gravity.CENTER);
                key.setPadding(dp(6), dp(8), dp(6), dp(8));
                key.setBackground(surface(Color.TRANSPARENT, 0));
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(pixelIcon(pkg, chosen));
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                key.addView(icon, new LinearLayout.LayoutParams(dp(62), dp(62)));
                gap(key, 12);
                TextView name = text(title, 16, WHITE);
                name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
                name.setGravity(Gravity.CENTER);
                key.addView(name, new LinearLayout.LayoutParams(-1, dp(24)));
                key.setContentDescription(title + "，长按替换");
                key.setFocusable(true);
                key.setOnClickListener(v -> launchSlot(slot));
                key.setOnLongClickListener(v -> { chooseApp(slot); return true; });
                weighted(shortcuts, key, col == 0 ? 14 : 0);
            }
            home.addView(shortcuts);
            if (r < 2) gap(home, 12);
        }
        stretch(home, 16, .7f);
        LinearLayout search = row();
        search.setGravity(Gravity.CENTER);
        search.setBackground(surface(Color.TRANSPARENT, 0));
        search.setContentDescription("搜索应用");
        search.setFocusable(true);
        search.setOnClickListener(v -> showApps());
        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageDrawable(pixelIcon("feiyu.search", null));
        searchIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams searchIconSize = new LinearLayout.LayoutParams(dp(24), dp(24));
        searchIconSize.setMarginEnd(dp(12));
        search.addView(searchIcon, searchIconSize);
        search.addView(text("搜索应用 █", 16, WHITE));
        home.addView(search, new LinearLayout.LayoutParams(-1, dp(56)));
        updateStatus();
    }

    private void updateStatus() {
        if (clock == null) return;
        Date now = new Date();
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
        clock.setText(time);
        clock.setContentDescription(time + "，打开闹钟");
        date.setText(new SimpleDateFormat("M月d日 EEE", Locale.SIMPLIFIED_CHINESE).format(now));
    }

    private Drawable pixelIcon(String pkg, ResolveInfo app) {
        return pixelIcon(this, pkg, app, iconCache);
    }

    static Drawable pixelIcon(Context context, String pkg, ResolveInfo app, Map<String, Bitmap> cache) {
        String key = app == null ? pkg : new ComponentName(app.activityInfo.packageName, app.activityInfo.name).flattenToString();
        Bitmap pixels = cache.get(key);
        if (pixels == null) {
            int resource = 0;
            switch (pkg) {
                case "com.termux": resource = R.drawable.pixel_terminal; break;
                case "com.android.chrome":
                case "org.mozilla.firefox": resource = R.drawable.pixel_browser; break;
                case "org.telegram.messenger": resource = R.drawable.pixel_telegram; break;
                case "com.tencent.mm": resource = R.drawable.pixel_wechat; break;
                case "com.eg.android.AlipayGphone": resource = R.drawable.pixel_alipay; break;
                case "com.coloros.filemanager": resource = R.drawable.pixel_folder; break;
                case "com.android.settings": resource = R.drawable.pixel_settings; break;
                case "feiyu.search": resource = R.drawable.pixel_search; break;
            }
            Drawable source = resource != 0 ? context.getDrawable(resource) : app.loadIcon(context.getPackageManager());
            pixels = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            source.setBounds(0, 0, 24, 24);
            source.draw(new Canvas(pixels));
            int[] original = new int[24 * 24];
            pixels.getPixels(original, 0, 24, 0, 0, 24, 24);
            for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                int color = original[y * 24 + x];
                if (pkg.equals("com.termux")) {
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < 24 && ny >= 0 && ny < 24 && Color.alpha(original[ny * 24 + nx]) > Color.alpha(color))
                            color = original[ny * 24 + nx];
                    }
                }
                if (resource != 0) {
                    int tone = pkg.equals("com.termux") ? GREEN : y < 9 ? WHITE : y < 16 ? 0xFFB1A4BF : 0xFF8C7D9E;
                    pixels.setPixel(x, y, Color.alpha(color) < 100 ? Color.TRANSPARENT : tone);
                } else {
                    // Preserve each installed app's recognizable artwork, quantized to the shared palette.
                    int light = (Color.red(color) * 3 + Color.green(color) * 6 + Color.blue(color)) / 10;
                    int tone = light < 65 ? 0xFF2D233A : light < 130 ? 0xFF675478 : light < 195 ? 0xFFA494B4 : WHITE;
                    pixels.setPixel(x, y, Color.alpha(color) < 100 ? Color.TRANSPARENT : tone);
                }
            }
            cache.put(key, pixels);
        }
        BitmapDrawable icon = new BitmapDrawable(context.getResources(), pixels);
        icon.setFilterBitmap(false); icon.setAntiAlias(false);
        return icon;
    }

    private void launchSlot(int slot) {
        String saved = prefs.getString("slot" + slot, "");
        if (saved.isEmpty()) launchPackage(SLOTS[slot][2]);
        else {
            ComponentName component = ComponentName.unflattenFromString(saved);
            if (component == null) { toast("入口无效，请长按重新选择"); return; }
            open(Intent.makeMainActivity(component));
        }
    }
    private void launchPackage(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) { toast("未找到应用，请在全部应用中选择或长按替换入口"); return; }
        open(intent);
    }
    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException | SecurityException e) { toast("无法打开此入口，请从全部应用中重选"); }
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void chooseApp(int slot) { showApps(slot); }
    private void showApps() { showApps(-1); }

    private void showApps(int slot) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        drawer = true; loadApps();
        LinearLayout panel = column();
        panel.setPadding(dp(22), dp(14), dp(22), dp(8));
        panel.setBackground(wallpaper(R.drawable.midnight_wallpaper));
        LinearLayout header = row();
        weighted(header, text(slot < 0 ? "全部应用" : "替换快捷入口", 24, WHITE), 8);
        header.addView(button(slot < 0 ? "返回" : "取消", MUTED, this::showHome));
        panel.addView(header); gap(panel, 20);
        EditText search = new EditText(this);
        search.setTypeface(pixelBody);
        search.setSingleLine(true); search.setTextSize(16); search.setTextColor(WHITE);
        search.setHintTextColor(MUTED); search.setHint("搜索应用 / search");
        search.setSelectAllOnFocus(true); search.setPadding(dp(14), dp(12), dp(14), dp(12));
        search.setBackground(surface(PANEL, 0)); search.setMinHeight(dp(52));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        panel.addView(search); gap(panel, 12);
        TextView count = text("", 12, MUTED); panel.addView(count); gap(panel, 8);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = column(); scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Runnable render = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            list.removeAllViews(); int matches = 0;
            for (ResolveInfo app : apps) {
                String name = label(app);
                if (!name.toLowerCase(Locale.ROOT).contains(query) && !app.activityInfo.packageName.toLowerCase(Locale.ROOT).contains(query)) continue;
                matches++;
                LinearLayout item = row();
                item.setPadding(dp(12), dp(12), dp(12), dp(12));
                item.setBackground(surface(0xBF0F0E18, 0)); item.setMinimumHeight(dp(72));
                ImageView icon = new ImageView(this); icon.setImageDrawable(pixelIcon(app.activityInfo.packageName, app));
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40)); ip.setMarginEnd(dp(20));
                item.addView(icon, ip);
                TextView title = text(name, 16, WHITE); title.setMaxLines(2);
                weighted(item, title, 0);
                item.setContentDescription((slot < 0 ? "打开 " : "选择 ") + name); item.setFocusable(true);
                item.setOnClickListener(v -> {
                    if (slot < 0) open(Intent.makeMainActivity(ComponentName.unflattenFromString(component(app))));
                    else {
                        SharedPreferences.Editor edit = prefs.edit();
                        if (app.activityInfo.packageName.equals(SLOTS[slot][2])) edit.remove("slot" + slot);
                        else edit.putString("slot" + slot, component(app));
                        // Persist the choice before leaving the picker, including immediate process restarts.
                        if (edit.commit()) showHome();
                        else toast("入口保存失败，请重试");
                    }
                });
                list.addView(item); gap(list, 4);
            }
            count.setText(matches + " 个应用入口");
            if (matches == 0) { gap(list, 28); list.addView(text("没有匹配的应用", 16, MUTED)); }
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
            public void afterTextChanged(Editable e) {}
        });
        if (slot < 0) {
            gap(panel, 8);
            panel.addView(button("桌面配置", MUTED, this::showSettings));
        }
        setContentView(panel); panel.setFocusableInTouchMode(true); panel.requestFocus(); render.run();
    }

    private void showSettings() {
        String[] options = {"设为默认桌面", "应用像素锁屏壁纸", "应用像素主屏幕壁纸", "切换回 ColorOS 桌面", "系统设置", "添加像素时钟到系统桌面", "像素语音助手", "Midnight Raster 动效预览", "应用 Midnight Raster 动态壁纸"};
        new AlertDialog.Builder(this).setTitle("桌面配置")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    RoleManager roles = getSystemService(RoleManager.class);
                    if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                        // The system needs a result caller to identify the requesting launcher.
                        try { startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 1); }
                        catch (android.content.ActivityNotFoundException | SecurityException e) {
                            open(new Intent(Settings.ACTION_HOME_SETTINGS));
                        }
                    } else open(new Intent(Settings.ACTION_HOME_SETTINGS));
                } else if (which == 1 || which == 2) {
                    int target = which == 1 ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM;
                    new AlertDialog.Builder(this).setTitle("应用壁纸")
                        .setMessage("将替换当前" + (which == 1 ? "锁屏" : "主屏幕") + "壁纸。可在系统壁纸设置中重新选择原壁纸。")
                        .setPositiveButton("应用", (d, w) -> applyWallpaper(target)).setNegativeButton("取消", null).show();
                } else if (which == 5) {
                    android.appwidget.AppWidgetManager widgets = android.appwidget.AppWidgetManager.getInstance(this);
                    if (widgets.isRequestPinAppWidgetSupported())
                        widgets.requestPinAppWidget(new ComponentName(this, PixelClockWidget.class), null, null);
                    else toast("请在系统桌面长按空白处，从小组件中添加夜城像素时钟");
                } else if (which == 6) {
                    startActivity(new Intent(this, VoiceActivity.class));
                } else if (which == 7) {
                    startActivity(new Intent(this, MotionPreviewActivity.class));
                } else if (which == 8) {
                    MotionPreviewActivity.applyWallpaper(this);
                } else open(new Intent(which == 3 ? Settings.ACTION_HOME_SETTINGS : Settings.ACTION_SETTINGS));
            }).setNegativeButton("关闭", null).show();
    }

    private void applyWallpaper(int target) {
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                int resource = target == WallpaperManager.FLAG_LOCK
                    ? R.drawable.midnight_lock_wallpaper : R.drawable.midnight_wallpaper;
                bitmap = BitmapFactory.decodeResource(getResources(), resource);
                getSystemService(WallpaperManager.class).setBitmap(bitmap, null, true, target);
                runOnUiThread(() -> toast("像素壁纸已应用"));
            } catch (Exception e) { runOnUiThread(() -> toast("壁纸未更改，请通过系统壁纸设置重试")); }
            finally { if (bitmap != null) bitmap.recycle(); }
        }, "wallpaper").start();
    }
}

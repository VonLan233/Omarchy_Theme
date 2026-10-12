package com.feiyu.nightterminal;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Collections;

public class VoiceActivity extends Activity {
    private static final String ORIGIN = "http://127.0.0.1:8771";
    private WebView web;
    private PermissionRequest microphone;
    static boolean visible;
    private boolean pageReady, autoStart, nativePermission, nativeStartOnResume;
    private void wakeState(String state) {
        if (!visible) return;
        if ("wake".equals(state)) {
            autoStart = true;
            startAfterWake();
        } else voiceState(state);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        autoStart = WakeWordService.consumeWake(getIntent());
        openVoice();
    }

    private void openVoice() {
        String token = getSharedPreferences("voice", MODE_PRIVATE).getString("token", "");
        if (token.isEmpty()) { showUnavailable(); return; }
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(15, 14, 24));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new NativeVoice(), "NativeVoice");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !trusted(request.getUrl());
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showUnavailable();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!trusted(Uri.parse(url))) return;
                pageReady = true;
                voiceState(WakeWordService.state());
                startAfterWake();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                boolean audio = false;
                for (String resource : request.getResources())
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audio = true;
                if (!trusted(request.getOrigin()) || !audio || isFinishing()) { request.deny(); return; }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    if (microphone != null) microphone.deny();
                    microphone = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
                }
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (microphone == request) microphone = null;
            }
        });
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        addStandbyControls(panel);
        panel.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(panel);
        web.loadUrl(ORIGIN + "/", Collections.singletonMap("Authorization", "Bearer " + token));
    }

    private boolean trusted(Uri uri) {
        return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == 8771;
    }
    private boolean trustedPage() { return web != null && web.getUrl() != null && trusted(Uri.parse(web.getUrl())); }
    private void voiceState(String state) {
        if (pageReady && trustedPage()) web.evaluateJavascript("window.nativeVoiceState?.(" + org.json.JSONObject.quote(state == null ? "stopped" : state) + ")", null);
    }
    private void startAfterWake() {
        if (!autoStart || !visible || !pageReady || !trustedPage()) return;
        autoStart = false;
        web.evaluateJavascript("window.nativeVoiceWake?.()", null);
    }
    private void addStandbyControls(LinearLayout panel) {
        LinearLayout row = new LinearLayout(this);
        Button assistant = new Button(this);
        assistant.setText("设为系统助手");
        assistant.setTypeface(getResources().getFont(R.font.pixel_body));
        assistant.setOnClickListener(view -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)); }
            catch (ActivityNotFoundException unavailable) {
                Toast.makeText(this, "请在系统设置中打开默认数字助理应用。", Toast.LENGTH_LONG).show();
            }
        });
        Button stop = new Button(this); stop.setText("停止后台待机");
        stop.setTypeface(getResources().getFont(R.font.pixel_body));
        row.setBackgroundColor(Color.rgb(15, 14, 24));
        for (Button button : new Button[]{assistant, stop}) {
            button.setTextColor(Color.rgb(239, 231, 211));
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(37, 30, 53)));
        }
        stop.setOnClickListener(view -> { nativePermission = nativeStartOnResume = false; WakeWordService.stop(this); });
        row.addView(assistant, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(row);
    }
    private void startStandby() {
        if (!visible || !trustedPage()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            nativePermission = true;
            requestPermissions(Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.RECORD_AUDIO}, 8);
            return;
        }
        if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) { voiceState("notification_permission"); return; }
        try { WakeWordService.begin(this); }
        catch (IllegalStateException | SecurityException unavailable) { voiceState("error"); }
    }
    private class NativeVoice {
        @JavascriptInterface public void startStandby() { runOnUiThread(() -> VoiceActivity.this.startStandby()); }
        @JavascriptInterface public void stopStandby() { runOnUiThread(() -> {
            if (!trustedPage()) return;
            nativePermission = nativeStartOnResume = false; WakeWordService.stop(VoiceActivity.this);
        }); }
        @JavascriptInterface public void prepareConversation(int generation) { runOnUiThread(() -> {
            if (!visible || !trustedPage()) return;
            nativePermission = false;
            WakeWordService.pauseForConversation(() -> {
                if (visible && trustedPage()) web.evaluateJavascript("window.nativeVoiceConversationReady?.(" + generation + ")", null);
            });
        }); }
        @JavascriptInterface public void conversationEnded() { runOnUiThread(() -> {
            if (trustedPage()) WakeWordService.conversationEnded();
        }); }
        @JavascriptInterface public boolean assistantSelected() { return FeiyuAssistantService.selected(VoiceActivity.this); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 7 && microphone != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && !isFinishing())
                microphone.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else microphone.deny();
            microphone = null;
        }
        if (code == 8 && nativePermission) {
            nativePermission = false;
            boolean granted = results.length > 0;
            for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                if (visible) startStandby();
                else nativeStartOnResume = true;
            }
            else voiceState("permission");
        }
    }

    private void showUnavailable() {
        pageReady = false;
        if (web != null) { web.destroy(); web = null; }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(28 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding * 2, padding, padding);
        TextView text = new TextView(this);
        text.setText("语音终端\n\n本机语音服务尚未运行。\n\n请在 Termux 运行：\nfeiyu-voice\n\n返回这里后点按重试。");
        text.setTextSize(20);
        text.setTypeface(getResources().getFont(R.font.pixel_body));
        panel.addView(text);
        addStandbyControls(panel);
        Button retry = new Button(this); retry.setText("重新连接");
        retry.setOnClickListener(view -> openVoice()); panel.addView(retry);
        setContentView(panel);
    }

    @Override protected void onPause() {
        visible = false;
        WakeWordService.listen(null);
        // The first microphone permission dialog pauses the Activity before capture starts.
        if (microphone == null) stopVoice();
        super.onPause();
    }
    private void stopVoice() {
        if (web != null) {
            web.evaluateJavascript("if(typeof finish==='function')finish()", null);
            web.onPause();
        }
    }
    @Override protected void onStop() {
        stopVoice();
        super.onStop();
    }
    @Override protected void onResume() {
        super.onResume();
        visible = true;
        WakeWordService.listen(this::wakeState);
        if (web != null) web.onResume();
        voiceState(WakeWordService.state());
        startAfterWake();
        if (nativeStartOnResume) { nativeStartOnResume = false; startStandby(); }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        autoStart = WakeWordService.consumeWake(intent);
        startAfterWake();
    }
    @Override protected void onDestroy() {
        nativePermission = nativeStartOnResume = false;
        if (microphone != null) { microphone.deny(); microphone = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}

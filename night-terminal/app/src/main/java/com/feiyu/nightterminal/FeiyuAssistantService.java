package com.feiyu.nightterminal;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;

public class FeiyuAssistantService extends VoiceInteractionService {
    private static FeiyuAssistantService current;
    @Override public void onReady() {
        super.onReady();
        current = this;
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST | VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
    }
    static boolean selected(Context context) {
        return isActiveService(context, new ComponentName(context, FeiyuAssistantService.class));
    }
    static boolean showWake(Context context) {
        if (current == null || !selected(context) || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) return false;
        Bundle args = new Bundle();
        args.putParcelable("voice_intent", WakeWordService.wakeIntent(context));
        try { current.showSession(args, 0); return true; }
        catch (IllegalStateException | SecurityException unavailable) { return false; }
    }
    @Override public void onShutdown() {
        current = null;
        WakeWordService.stop(this);
        super.onShutdown();
    }
    @Override public void onDestroy() { current = null; super.onDestroy(); }
}

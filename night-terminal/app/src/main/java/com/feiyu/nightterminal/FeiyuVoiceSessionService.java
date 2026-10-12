package com.feiyu.nightterminal;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public class FeiyuVoiceSessionService extends VoiceInteractionSessionService {
    @Override public VoiceInteractionSession onNewSession(Bundle args) {
        return new VoiceInteractionSession(this) {
            @Override public void onCreate() {
                super.onCreate();
                setUiEnabled(false);
                setDisabledShowContext(SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT);
            }
            @Override public void onShow(Bundle args, int flags) {
                super.onShow(args, flags);
                Intent intent = args == null ? null : args.getParcelable("voice_intent");
                if (intent == null) intent = new Intent(FeiyuVoiceSessionService.this, VoiceActivity.class);
                startVoiceActivity(intent);
            }
            @Override public void onTaskStarted(Intent intent, int taskId) { hide(); }
        };
    }
}

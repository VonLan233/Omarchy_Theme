package com.feiyu.nightterminal;

import android.content.Intent;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/** Android requires this entry for a default assistant; this app provides keyword detection, not dictation. */
public class LocalWakeRecognitionService extends RecognitionService {
    @Override protected void onStartListening(Intent intent, Callback callback) {
        try { callback.error(SpeechRecognizer.ERROR_CLIENT); } catch (RemoteException ignored) { }
    }
    @Override protected void onStopListening(Callback callback) { }
    @Override protected void onCancel(Callback callback) { }
}

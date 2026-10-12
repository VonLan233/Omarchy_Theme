package com.feiyu.nightterminal;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;

/** Debug-only check without redistributing voice recordings. */
public class WakeWordInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            try (LocalKeywordDetector detector = new LocalKeywordDetector(getTargetContext().getAssets())) {
                for (int i = 0; i < 30; i++)
                    if (detector.accept(new float[1600])) throw new AssertionError("silence triggered");
            }
            if (WakeWordService.consumeWake(new Intent().putExtra("wake_token", "forged")))
                throw new AssertionError("forged auto-start");
            Intent genuine = WakeWordService.wakeIntent(getTargetContext());
            int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP;
            if ((genuine.getFlags() & flags) != flags || !VoiceActivity.class.getName().equals(genuine.getComponent().getClassName()))
                throw new AssertionError("wake notification must foreground VoiceActivity and deliver its token");
            if (!WakeWordService.consumeWake(genuine) || WakeWordService.consumeWake(genuine))
                throw new AssertionError("wake token reuse");
            results.putString("result", "PASS: silence does not trigger; forged and reused handoffs are rejected");
            finish(Activity.RESULT_OK, results);
        } catch (Exception | AssertionError | LinkageError failure) {
            results.putString("error", failure.toString());
            finish(Activity.RESULT_CANCELED, results);
        }
    }
}

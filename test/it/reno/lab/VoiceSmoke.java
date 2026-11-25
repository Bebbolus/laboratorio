package it.reno.lab;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

import java.io.InputStream;

public final class VoiceSmoke extends Instrumentation {
    @Override public void onCreate(Bundle options) {
        super.onCreate(options);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try (InputStream sample = getTargetContext().getAssets().open("dettatura-test.pcm")) {
            String text = new OfflineVoice(getTargetContext()).transcribeSample(sample);
            result.putString("transcript", text);
            finish(text.contains("prova") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        } catch (Exception | LinkageError problem) {
            result.putString("error", problem.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}

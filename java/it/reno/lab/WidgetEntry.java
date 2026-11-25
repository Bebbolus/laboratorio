package it.reno.lab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.util.UUID;

public final class WidgetEntry extends Activity {
    static final String CHAT = "it.reno.lab.CHAT";
    static final String VOICE = "it.reno.lab.VOICE";
    static final String TOKEN = UUID.randomUUID().toString();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent chat = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("widget-entry", TOKEN)
                .putExtra("widget-voice", VOICE.equals(getIntent().getAction()));
        startActivity(chat);
        finish();
    }
}

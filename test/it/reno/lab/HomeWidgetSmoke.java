package it.reno.lab;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.widget.FrameLayout;

import java.util.List;

public final class HomeWidgetSmoke extends Instrumentation {
    @Override public void onCreate(Bundle options) {
        super.onCreate(options);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        try {
            AppWidgetManager manager = context.getSystemService(AppWidgetManager.class);
            List<AppWidgetProviderInfo> widgets = manager.getInstalledProvidersForPackage(
                    context.getPackageName(), Process.myUserHandle());
            AppWidgetProviderInfo provider = widgets.stream()
                    .filter(widget -> LabWidget.class.getName().equals(widget.provider.getClassName()))
                    .findFirst().orElseThrow();
            if (provider.initialLayout != R.layout.lab_widget || provider.minHeight <= 0) {
                throw new IllegalStateException("Missing widget layout metadata");
            }
            runOnMainSync(() -> {
                android.view.View bar = LabWidget.widgetViews(context).apply(
                        context, new FrameLayout(context));
                if (bar.findViewById(R.id.widget_chat) == null || bar.findViewById(R.id.widget_voice) == null) {
                    throw new IllegalStateException("Missing widget controls");
                }
            });
            result.putBoolean("provider_and_controls_ok", true);
            result.putBoolean("launcher_accepts_pin_request", manager.isRequestPinAppWidgetSupported());
            finish(Activity.RESULT_OK, result);
        } catch (Exception failure) {
            result.putString("error", failure.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}

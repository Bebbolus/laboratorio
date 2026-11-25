package it.reno.lab;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public final class LabWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        manager.updateAppWidget(ids, widgetViews(context));
    }

    static RemoteViews widgetViews(Context context) {
        RemoteViews bar = new RemoteViews(context.getPackageName(), R.layout.lab_widget);
        bar.setOnClickPendingIntent(R.id.widget_chat, entry(context, WidgetEntry.CHAT, 31));
        bar.setOnClickPendingIntent(R.id.widget_voice, entry(context, WidgetEntry.VOICE, 32));
        return bar;
    }

    private static PendingIntent entry(Context context, String action, int id) {
        Intent open = new Intent(context, WidgetEntry.class).setAction(action);
        return PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

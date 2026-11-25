package it.reno.lab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class MainActivity extends Activity {
    private static final int MODEL_PICKER = 1;
    private static final int MICROPHONE = 2;
    private static final long MODEL_SIZE = 986_050_240L;
    private static final String MODEL_SHA =
            "539a29a046ea0a9c45b6600e14ca7e3cf5ed643ab582d94b4329a6d50c14a3e8";
    private static final String CHAT_RULES = "Sei l'assistente locale del Reno4. "
            + "Rispondi in italiano salvo richiesta diversa. Aiuta con traduzioni, testi e script. "
            + "Puoi suggerire comandi, ma non eseguirli e non dichiarare di avere accesso a dispositivi, "
            + "file o Internet. Mostra i comandi in modo leggibile. "
            + "Non inventare indirizzi .onion, link o fonti: l'app verifica gli indirizzi onion dal dominio "
            + "dichiarato e li apre in Tor Browser. Quando l'app ti fornisce risultati di ricerca web, "
            + "riassumili in italiano citando le fonti con [1], [2] e non inventare dati mancanti. "
            + "Se una richiesta è poco chiara, chiedi di ripeterla invece di rifiutare.";
    private static final int TOR_PORT = 19050;
    private static final String TOR_BROWSER = "org.torproject.torbrowser";
    private static final int BACKGROUND = Color.rgb(12, 16, 33);
    private static final int SURFACE = Color.rgb(22, 29, 51);
    private static final int LINE = Color.rgb(44, 58, 94);
    private static final int TEXT = Color.rgb(230, 236, 255);
    private static final int MUTED = Color.rgb(154, 166, 199);
    private static final int ACCENT = Color.rgb(162, 184, 255);
    private static final String SLOT_PREFIX = "slot.";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService netWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService voiceWorker = Executors.newSingleThreadExecutor();
    private final String accessKey = UUID.randomUUID().toString();
    private final Object torLock = new Object();

    private volatile Process server;
    private Process torProcess;
    private volatile boolean torReady;
    private volatile boolean closing;
    private volatile int port;
    private File model;
    private OfflineVoice capture;
    private Boolean widgetVoice;

    private FrameLayout host;
    private LinearLayout base;
    private View overlay;
    private ScrollView scroll;
    private LinearLayout messages;
    private LinearLayout welcome;
    private EditText input;
    private ImageButton send;
    private ImageButton mic;
    private TextView modelDot;
    private TextView notice;
    private TextView title;
    private TextView torDot;
    private LinearLayout currentContent;
    private TextView streamBody;
    private boolean modelReady;
    private boolean busy;

    private SharedPreferences prefs;
    private File chatFile;
    private final List<Chat> chats = new ArrayList<>();
    private Chat current;

    private static final class Chat {
        String id = UUID.randomUUID().toString();
        String title = "Nuova chat";
        boolean pinned;
        long updated = System.currentTimeMillis();
        JSONArray messages = new JSONArray();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("laboratorio", MODE_PRIVATE);
        chatFile = new File(getFilesDir(), "chats.json");
        loadChats();
        receiveWidgetEntry(getIntent());
        File models = getExternalFilesDir("models");
        model = new File(models == null ? getFilesDir() : models, "qwen2.5.gguf");
        buildUi();
        openMostRecent();
        worker.execute(() -> startModel(false));
        dispatchWidgetEntry();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        receiveWidgetEntry(intent);
        dispatchWidgetEntry();
    }

    private void receiveWidgetEntry(Intent intent) {
        if (WidgetEntry.TOKEN.equals(intent.getStringExtra("widget-entry"))) {
            widgetVoice = intent.getBooleanExtra("widget-voice", false);
            intent.removeExtra("widget-entry");
        }
    }

    private void dispatchWidgetEntry() {
        if (widgetVoice == null) return;
        boolean dictate = widgetVoice;
        widgetVoice = null;
        if (dictate) {
            requestVoice();
            return;
        }
        input.requestFocus();
        input.postDelayed(() -> getSystemService(InputMethodManager.class)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT), 140);
    }

    // ---------- conversations ----------

    private void loadChats() {
        chats.clear();
        try {
            JSONArray stored = new JSONArray(new String(
                    Files.readAllBytes(chatFile.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < stored.length(); i++) {
                JSONObject item = stored.getJSONObject(i);
                Chat chat = new Chat();
                chat.id = item.getString("id");
                chat.title = item.optString("title", "Nuova chat");
                chat.pinned = item.optBoolean("pinned", false);
                chat.updated = item.optLong("updated", 0);
                chat.messages = item.optJSONArray("messages");
                if (chat.messages == null) chat.messages = new JSONArray();
                chats.add(chat);
            }
        } catch (Exception ignored) {
        }
        sortChats();
    }

    private void saveChats() {
        try {
            JSONArray stored = new JSONArray();
            for (Chat chat : chats) {
                stored.put(new JSONObject().put("id", chat.id).put("title", chat.title)
                        .put("pinned", chat.pinned).put("updated", chat.updated)
                        .put("messages", chat.messages));
            }
            Files.write(chatFile.toPath(), stored.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void sortChats() {
        Collections.sort(chats, new Comparator<Chat>() {
            @Override public int compare(Chat left, Chat right) {
                if (left.pinned != right.pinned) return left.pinned ? -1 : 1;
                return Long.compare(right.updated, left.updated);
            }
        });
    }

    private void openMostRecent() {
        if (chats.isEmpty()) {
            newChat();
            return;
        }
        openChat(chats.get(0));
    }

    private void newChat() {
        Chat chat = new Chat();
        chats.add(chat);
        saveChats();
        openChat(chat);
        input.requestFocus();
    }

    private void openChat(Chat chat) {
        current = chat;
        title.setText(chat.title);
        renderMessages();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        chat.updated = System.currentTimeMillis();
    }

    private void renderMessages() {
        messages.removeAllViews();
        boolean empty = current.messages.length() == 0;
        welcome.setVisibility(empty ? View.VISIBLE : View.GONE);
        messages.addView(welcome);
        for (int i = 0; i < current.messages.length(); i++) {
            JSONObject item = current.messages.optJSONObject(i);
            if (item == null) continue;
            addMessage("user".equals(item.optString("role")), item.optString("content"), false);
        }
    }

    private void append(String role, String text) {
        try {
            current.messages.put(new JSONObject().put("role", role).put("content", text));
        } catch (Exception ignored) {
        }
        current.updated = System.currentTimeMillis();
        if (current.title.equals("Nuova chat") && "user".equals(role)) {
            current.title = text.length() > 42 ? text.substring(0, 42) + "…" : text;
            title.setText(current.title);
        }
        sortChats();
        saveChats();
    }

    // ---------- interface ----------

    private void buildUi() {
        host = new FrameLayout(this);
        host.setBackgroundColor(BACKGROUND);
        host.setFitsSystemWindows(true);
        base = new LinearLayout(this);
        base.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(7), dp(4), dp(7));
        bar.setBackgroundColor(Color.rgb(16, 20, 39));

        ImageButton discover = iconButton(R.drawable.ic_discover, Color.rgb(175, 198, 255));
        discover.setContentDescription("Scopri");
        discover.setOnClickListener(view -> discoverSheet());
        bar.addView(discover, new LinearLayout.LayoutParams(dp(38), dp(38)));

        title = label("Nuova chat", 15, TEXT, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(9), 0, dp(6), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        modelDot = label("●", 12, Color.rgb(110, 120, 150), true);
        modelDot.setPadding(dp(2), dp(4), dp(2), dp(4));
        modelDot.setContentDescription("Stato modello");
        modelDot.setOnClickListener(view -> {
            if (!modelReady) pickModel();
        });
        bar.addView(modelDot);

        torDot = label("●", 14, Color.rgb(110, 120, 150), true);
        torDot.setPadding(dp(4), dp(4), dp(4), dp(4));
        torDot.setContentDescription("Stato Tor");
        torDot.setOnClickListener(view -> torDialog());
        bar.addView(torDot);

        ImageButton list = iconButton(R.drawable.ic_chats, Color.rgb(175, 198, 255));
        list.setContentDescription("Conversazioni");
        list.setOnClickListener(view -> conversationsOverlay());
        bar.addView(list, new LinearLayout.LayoutParams(dp(38), dp(38)));

        ImageButton menu = iconButton(R.drawable.ic_more, Color.rgb(175, 198, 255));
        menu.setContentDescription("Menu");
        menu.setOnClickListener(this::openMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(38), dp(38)));
        base.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(14), dp(14), dp(14), dp(10));
        scroll.addView(messages, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        welcome = new LinearLayout(this);
        welcome.setOrientation(LinearLayout.VERTICAL);
        welcome.setGravity(Gravity.CENTER);
        welcome.setPadding(dp(18), dp(24), dp(18), dp(16));
        TextView promptMark = label("›_", 24, ACCENT, true);
        promptMark.setGravity(Gravity.CENTER);
        welcome.addView(promptMark);
        TextView heading = label("Come posso aiutarti?", 23, TEXT, false);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingParams.topMargin = dp(14);
        welcome.addView(heading, headingParams);
        TextView subtitle = label("Scrivi, detta o apri l'elenco app.", 13, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(6);
        welcome.addView(subtitle, subtitleParams);
        addSuggestion("Traduci in italiano questo testo: ");
        addSuggestion("Spiegami questo script, senza eseguirlo: ");
        addSuggestion("Aiutami a organizzare un laboratorio Linux personale.");
        messages.addView(welcome);
        base.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        notice = label("", 12, Color.rgb(228, 185, 144), false);
        notice.setPadding(dp(16), dp(2), dp(16), dp(4));
        notice.setVisibility(View.GONE);
        base.addView(notice);

        base.addView(composer(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        base.addView(launcherBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        host.addView(base, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(host);
        renderTorDot();
    }

    private LinearLayout composer() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(dp(10), dp(8), dp(10), dp(6));
        row.setBackgroundColor(Color.rgb(17, 23, 45));

        input = new EditText(this);
        input.setHint("Scrivi un messaggio…");
        input.setHintTextColor(Color.rgb(126, 138, 170));
        input.setTextColor(TEXT);
        input.setTextSize(15);
        input.setMaxLines(5);
        input.setPadding(dp(13), dp(11), dp(13), dp(11));
        input.setBackground(rounded(SURFACE, LINE, 12));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setOnKeyListener((view, code, event) -> {
            if (code == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN
                    && !event.isShiftPressed()) {
                sendPrompt();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputParams.setMarginEnd(dp(8));
        row.addView(input, inputParams);

        mic = iconButton(R.drawable.ic_widget_mic, Color.rgb(182, 201, 255));
        mic.setContentDescription("Detta un messaggio");
        mic.setOnClickListener(view -> {
            if (capture != null) {
                capture.stop(false);
                return;
            }
            requestVoice();
        });
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        micParams.setMarginEnd(dp(6));
        row.addView(mic, micParams);

        send = new ImageButton(this);
        send.setImageResource(R.drawable.ic_send);
        send.setContentDescription("Invia messaggio");
        send.setBackground(rounded(Color.rgb(164, 185, 250), Color.TRANSPARENT, 12));
        send.setPadding(dp(10), dp(10), dp(10), dp(10));
        send.setOnClickListener(view -> sendPrompt());
        row.addView(send, new LinearLayout.LayoutParams(dp(42), dp(42)));
        return row;
    }

    private LinearLayout launcherBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(dp(6), dp(4), dp(6), dp(8));
        row.setBackgroundColor(Color.rgb(17, 23, 45));

        ImageButton apps = iconButton(R.drawable.ic_apps, Color.rgb(175, 198, 255));
        apps.setContentDescription("Elenco applicazioni");
        apps.setOnClickListener(view -> appsOverlay());
        row.addView(apps, slotParams());

        for (int index = 0; index < 3; index++) {
            final int slot = index;
            ImageButton button = new ImageButton(this);
            button.setBackground(rounded(Color.TRANSPARENT, Color.TRANSPARENT, 10));
            button.setPadding(dp(9), dp(9), dp(9), dp(9));
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            button.setOnClickListener(view -> launchSlot(slot));
            button.setOnLongClickListener(view -> {
                configureSlot(slot);
                return true;
            });
            row.addView(button, slotParams());
            bindSlot(button, slot);
        }
        return row;
    }

    private LinearLayout.LayoutParams slotParams() {
        return new LinearLayout.LayoutParams(0, dp(48), 1f);
    }

    private void bindSlot(ImageButton button, int slot) {
        String pkg = prefs.getString(SLOT_PREFIX + slot, defaultSlot(slot));
        button.setTag(pkg);
        try {
            button.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
        } catch (Exception missing) {
            button.setImageResource(R.drawable.ic_apps);
            button.setColorFilter(Color.rgb(175, 198, 255));
        }
        button.setContentDescription(labelOf(pkg));
    }

    private String defaultSlot(int slot) {
        if (slot == 0) return "org.mozilla.fennec_fdroid";
        if (slot == 1) return "com.google.android.dialer";
        return "com.google.android.apps.messaging";
    }

    private void launchSlot(int slot) {
        String pkg = prefs.getString(SLOT_PREFIX + slot, defaultSlot(slot));
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            configureSlot(slot);
            return;
        }
        startActivity(launch);
    }

    private String labelOf(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception missing) {
            return pkg;
        }
    }

    // ---------- overlays ----------

    private void conversationsOverlay() {
        sortChats();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        SimpleDateFormat format = new SimpleDateFormat("d MMM HH:mm", Locale.ITALIAN);
        for (final Chat chat : chats) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(11), dp(12), dp(11));
            row.setBackground(rounded(SURFACE, LINE, 10));
            TextView name = label((chat.pinned ? "★ " : "") + chat.title, 14, TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name);
            TextView meta = label(format.format(new Date(chat.updated))
                    + " · " + chat.messages.length() + " messaggi", 11, MUTED, true);
            row.addView(meta);
            row.setOnClickListener(view -> {
                closeOverlay();
                openChat(chat);
            });
            row.setOnLongClickListener(view -> {
                chatActions(chat);
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            list.addView(row, params);
        }
        sheet("Conversazioni", list, "Nuova chat", this::newChat);
    }

    private void chatActions(Chat chat) {
        String[] actions = chat.pinned
                ? new String[]{"Togli il pin", "Sposta su", "Sposta giù", "Elimina"}
                : new String[]{"Fissa in alto", "Sposta su", "Sposta giù", "Elimina"};
        new AlertDialog.Builder(this)
                .setTitle(chat.title)
                .setItems(actions, (dialog, which) -> {
                    int at = chats.indexOf(chat);
                    if (which == 0) {
                        chat.pinned = !chat.pinned;
                        if (chat.pinned) chat.updated = System.currentTimeMillis();
                    } else if (which == 1 && at > 0) {
                        Collections.swap(chats, at, at - 1);
                    } else if (which == 2 && at >= 0 && at < chats.size() - 1) {
                        Collections.swap(chats, at, at + 1);
                    } else if (which == 3) {
                        chats.remove(chat);
                        if (current == chat) {
                            if (chats.isEmpty()) newChat();
                            else openChat(chats.get(0));
                        }
                    }
                    saveChats();
                    closeOverlay();
                    conversationsOverlay();
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void appsOverlay() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        PackageManager manager = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = manager.queryIntentActivities(launcherIntent,
                PackageManager.MATCH_ALL);
        Collections.sort(resolved, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo left, ResolveInfo right) {
                return left.loadLabel(manager).toString().compareToIgnoreCase(
                        right.loadLabel(manager).toString());
            }
        });
        LinearLayout row = null;
        for (int i = 0; i < resolved.size(); i++) {
            ResolveInfo info = resolved.get(i);
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final String pkg = info.activityInfo.packageName;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(6), dp(10), dp(6), dp(10));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(info.loadIcon(manager));
            cell.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            TextView name = label(info.loadLabel(manager).toString(), 10, TEXT, false);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameParams.topMargin = dp(5);
            cell.addView(name, nameParams);
            cell.setOnClickListener(view -> {
                closeOverlay();
                Intent launch = manager.getLaunchIntentForPackage(pkg);
                if (launch != null) startActivity(launch);
            });
            row.addView(cell, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        sheet("Applicazioni", grid, null, null);
    }

    private void sheet(String heading, LinearLayout content, String action, Runnable run) {
        closeOverlay();
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(Color.argb(204, 0, 0, 0));
        dim.setOnClickListener(view -> closeOverlay());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(10));
        panel.setBackground(rounded(Color.rgb(16, 20, 39), LINE, 14));
        panel.setOnClickListener(view -> { });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView headingView = label(heading, 16, TEXT, false);
        headingView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(headingView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            TextView actionView = label(action, 11, Color.rgb(225, 232, 255), true);
            actionView.setPadding(dp(10), dp(8), dp(10), dp(8));
            actionView.setBackground(rounded(Color.rgb(43, 58, 97), Color.rgb(75, 96, 145), 8));
            actionView.setOnClickListener(view -> {
                closeOverlay();
                run.run();
            });
            header.addView(actionView);
        }
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroller = new ScrollView(this);
        scroller.addView(content);
        LinearLayout.LayoutParams scrollerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollerParams.topMargin = dp(12);
        panel.addView(scroller, scrollerParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        panelParams.setMargins(dp(14), dp(14), dp(14), dp(14));
        dim.addView(panel, panelParams);
        host.addView(dim);
        overlay = dim;
    }

    private void closeOverlay() {
        if (overlay != null) {
            host.removeView(overlay);
            overlay = null;
        }
    }

    private void torDialog() {
        String state = torReady ? "Tor attivo (SOCKS su loopback)"
                : torProcess != null ? "Tor in avvio" : "Tor non avviato";
        new AlertDialog.Builder(this)
                .setTitle("Tor")
                .setMessage(state + "\n\nLa ricerca onion usa un processo Tor incorporato. "
                        + "Non è una VPN: non protegge il resto del telefono.")
                .setPositiveButton(torReady || torProcess != null ? "Ferma Tor" : "Chiudi",
                        (dialog, which) -> {
                            if (torReady || torProcess != null) {
                                stopTor();
                                renderTorDot();
                                setNotice("Tor fermato.");
                            }
                        })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void renderTorDot() {
        int color = torReady ? Color.rgb(168, 225, 186)
                : torProcess != null ? Color.rgb(231, 187, 128)
                : Color.rgb(110, 120, 150);
        torDot.setTextColor(color);
        torDot.setText(torReady ? "●" : "○");
    }

    private void configureSlot(int slot) {
        PackageManager manager = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = manager.queryIntentActivities(launcherIntent,
                PackageManager.MATCH_ALL);
        Collections.sort(resolved, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo left, ResolveInfo right) {
                return left.loadLabel(manager).toString().compareToIgnoreCase(
                        right.loadLabel(manager).toString());
            }
        });
        final String[] labels = new String[resolved.size()];
        final String[] packages = new String[resolved.size()];
        for (int i = 0; i < resolved.size(); i++) {
            labels[i] = resolved.get(i).loadLabel(manager).toString();
            packages[i] = resolved.get(i).activityInfo.packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle("Scegli app per lo spazio " + (slot + 1))
                .setItems(labels, (dialog, which) -> {
                    prefs.edit().putString(SLOT_PREFIX + slot, packages[which]).apply();
                    rebuild();
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void openMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Nuova chat");
        menu.getMenu().add(0, 2, 1, "Elenco applicazioni");
        menu.getMenu().add(0, 3, 2, "Sfondo Home");
        menu.getMenu().add(0, 4, 3, "Aggiungi widget");
        menu.getMenu().add(0, 5, 4, "Importa modello");
        menu.getMenu().add(0, 6, 5, "Cancella dati Laboratorio");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: newChat(); return true;
                case 2: appsOverlay(); return true;
                case 3: wallpaperPreview(); return true;
                case 4: pinWidget(); return true;
                case 5: pickModel(); return true;
                case 6: resetDialog(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void resetDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Cancellare i dati?")
                .setMessage("Verranno eliminati chat, pin, spazi personalizzati e dati Tor. "
                        + "Il modello locale resta sul telefono.")
                .setPositiveButton("Cancella", (dialog, which) -> {
                    chats.clear();
                    try {
                        Files.deleteIfExists(chatFile.toPath());
                    } catch (Exception ignored) {
                    }
                    prefs.edit().clear().apply();
                    deleteTree(new File(getFilesDir(), "tor"));
                    stopTor();
                    newChat();
                    rebuild();
                    setNotice("Dati Laboratorio cancellati.");
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private void rebuild() {
        host.removeAllViews();
        overlay = null;
        buildUi();
        if (current != null) {
            title.setText(current.title);
            renderMessages();
        }
    }

    @Override public void onBackPressed() {
        if (overlay != null) {
            closeOverlay();
            return;
        }
        // Home screen: back does nothing when no overlay is open.
    }

    private void addSuggestion(String prompt) {
        TextView chip = label(prompt, 11, Color.rgb(225, 232, 255), true);
        chip.setPadding(dp(12), dp(9), dp(12), dp(9));
        chip.setBackground(rounded(Color.rgb(32, 41, 71), Color.rgb(67, 81, 125), 8));
        chip.setOnClickListener(view -> {
            input.setText(prompt);
            input.setSelection(input.getText().length());
            input.requestFocus();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(14);
        welcome.addView(chip, params);
    }

    private LinearLayout addMessage(boolean fromUser, String text, boolean store) {
        if (store) append(fromUser ? "user" : "assistant", text);
        welcome.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(fromUser ? Gravity.END : Gravity.START);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(11));
        bubble.setBackground(rounded(
                fromUser ? Color.rgb(41, 57, 94) : SURFACE,
                fromUser ? Color.rgb(75, 96, 145) : LINE, 12));
        TextView tag = label(fromUser ? "Tu" : "Assistente", 10, Color.rgb(185, 201, 255), true);
        tag.setLetterSpacing(0.06f);
        bubble.addView(tag);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.topMargin = dp(4);
        bubble.addView(content, contentParams);
        showText(content, text);

        row.addView(bubble, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(12);
        messages.addView(row, rowParams);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        return content;
    }

    private void showText(LinearLayout content, String text) {
        content.removeAllViews();
        TextView body = label(text, 15, TEXT, false);
        body.setTextIsSelectable(true);
        content.addView(body);
    }

    private void progress(String text) {
        LinearLayout content = currentContent;
        if (content == null) return;
        runOnUiThread(() -> {
            if (content.getParent() != null) showText(content, text);
        });
    }

    private void setNotice(String text) {
        if (text == null || text.isEmpty()) {
            notice.setVisibility(View.GONE);
            return;
        }
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
    }

    private void setStatus(String state, String text) {
        int color = Color.rgb(231, 187, 128);
        if ("ready".equals(state)) color = Color.rgb(168, 225, 186);
        else if ("error".equals(state)) color = Color.rgb(239, 149, 170);
        modelDot.setTextColor(color);
        modelDot.setContentDescription(text);
        modelReady = "ready".equals(state);
        if ("ready".equals(state)) setNotice("");
        else if ("missing".equals(state) || "error".equals(state)) setNotice(text + " · tocca il punto per importare");
    }

    private void setBusy(boolean value) {
        busy = value;
        send.setEnabled(!value);
        send.setAlpha(value ? 0.5f : 1f);
        mic.setEnabled(!value);
        input.setEnabled(!value);
    }

    // ---------- conversation flow ----------

    private void sendPrompt() {
        String prompt = input.getText().toString().trim();
        if (prompt.isEmpty() || busy) return;
        if (prompt.matches("(?is).*\\b[a-z2-7]{16,56}\\.onion\\b.*")) {
            addMessage(true, prompt, true);
            addMessage(false, "Non apro indirizzi .onion scritti a mano: non posso verificarne la "
                    + "provenienza. Chiedimi l'onion di un dominio, per esempio "
                    + "«apri l'onion di torproject.org».", true);
            input.setText("");
            return;
        }
        String query = searchQuery(prompt);
        if (query != null) {
            input.setText("");
            if (query.isEmpty()) {
                setNotice("Cosa devo cercare?");
                return;
            }
            addMessage(true, prompt, true);
            currentContent = addMessage(false, "Cerco «" + query + "» via Tor…", false);
            setBusy(true);
            netWorker.execute(() -> runSearch(query));
            return;
        }
        String domain = onionTarget(prompt);
        input.setText("");
        setNotice("");
        if (domain != null) {
            addMessage(true, prompt, true);
            currentContent = addMessage(false, "Verifico l'indirizzo onion dichiarato da "
                    + domain + "…", false);
            setBusy(true);
            netWorker.execute(() -> resolveOnion(domain));
            return;
        }
        if (!modelReady) {
            setNotice("Attendi il modello locale o importalo per iniziare.");
            return;
        }
        addMessage(true, prompt, true);
        currentContent = addMessage(false, "Sto elaborando…", false);
        setBusy(true);
        worker.execute(this::reply);
    }

    private static String searchQuery(String prompt) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(?:cerca|cercami|ricerca|search)\\b\\s*(.*)$",
                        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                .matcher(prompt.trim());
        if (!matcher.matches()) return null;
        String query = matcher.group(1).trim();
        return query.replaceFirst("(?i)^(su\\s+internet|sul\\s+web|online|per)\\s*", "").trim();
    }

    private void runSearch(String query) {
        List<WebSearch.Result> results;
        String note = "";
        try {
            if (!ensureTor()) {
                results = new ArrayList<>();
                note = "Tor non è partito: nessuna ricerca, nessun ripiego in chiaro.";
            } else {
                progress("Tor pronto · interrogo DuckDuckGo…");
                results = WebSearch.search(query, this::torGet);
            }
        } catch (Exception failure) {
            results = new ArrayList<>();
            note = "Ricerca non riuscita (" + failure.getClass().getSimpleName() + ").";
        }
        if (!results.isEmpty()) progress("Trovate " + results.size()
                + " fonti · preparo la risposta…");
        String summary = null;
        if (!results.isEmpty()) {
            try {
                summary = summarize(query, results);
            } catch (Exception ignored) {
            }
        }
        final List<WebSearch.Result> found = results;
        final String detail = note;
        final String text = summary;
        runOnUiThread(() -> showSearch(query, found, detail, text));
    }

    private String summarize(String query, List<WebSearch.Result> results) throws Exception {
        if (server == null || !server.isAlive()) return null;
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < results.size() && i < 5; i++) {
            WebSearch.Result item = results.get(i);
            context.append('[').append(i + 1).append("] ").append(item.title)
                    .append(" (").append(WebSearch.hostOf(item.url)).append(")\n")
                    .append(item.snippet).append("\n\n");
        }
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", CHAT_RULES));
        messages.put(new JSONObject().put("role", "user").put("content",
                "Domanda: " + query + "\n\nRisultati di ricerca:\n" + context
                        + "Rispondi in italiano, massimo 120 parole, con una risposta diretta. "
                        + "Cita le fonti con [1], [2] subito dopo le frasi che le usano."));
        return streamChat(messages, 360, 0.2);
    }

    private String streamChat(JSONArray messages, int maxTokens, double temperature) throws Exception {
        if (server == null || !server.isAlive()) throw new IOException("Model is offline");
        byte[] payload = new JSONObject().put("messages", messages).put("max_tokens", maxTokens)
                .put("temperature", temperature).put("stream", true)
                .toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = connect("/v1/chat/completions", 180000);
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            if (connection.getResponseCode() != 200) throw new IOException("Model HTTP");
            LinearLayout target = currentContent;
            if (target != null) {
                runOnUiThread(() -> {
                    if (target.getParent() == null) return;
                    target.removeAllViews();
                    TextView body = label("", 15, TEXT, false);
                    body.setTextIsSelectable(true);
                    target.addView(body);
                    streamBody = body;
                });
            }
            StringBuilder answer = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null && !closing; ) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                try {
                    JSONObject chunk = new JSONObject(data);
                    String piece = chunk.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("delta").optString("content", "");
                    if (piece.isEmpty()) continue;
                    answer.append(piece);
                    final String text = piece;
                    runOnUiThread(() -> {
                        if (streamBody != null) {
                            streamBody.append(text);
                            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                        }
                    });
                } catch (Exception ignored) {
                }
            }
            return answer.toString().trim();
        } finally {
            connection.disconnect();
        }
    }

    private void showSearch(String query, List<WebSearch.Result> results,
                            String note, String summary) {
        LinearLayout content = currentContent;
        currentContent = null;
        if (content == null) content = addMessage(false, "", false);
        content.removeAllViews();

        boolean answered = summary != null && !summary.isEmpty();
        TextView head = label(answered ? "Risposta" : results.isEmpty()
                ? "Nessun risultato per «" + query + "»"
                : "Risultati per «" + query + "»", 13, MUTED, false);
        content.addView(head);

        if (answered) {
            TextView answer = label(summary, 15, TEXT, false);
            answer.setTextIsSelectable(true);
            LinearLayout.LayoutParams answerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            answerParams.topMargin = dp(4);
            content.addView(answer, answerParams);
        }

        if (!results.isEmpty()) {
            TextView sourcesTitle = label("Fonti", 12, MUTED, true);
            LinearLayout.LayoutParams sourcesParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sourcesParams.topMargin = dp(12);
            content.addView(sourcesTitle, sourcesParams);
            for (int i = 0; i < results.size(); i++) {
                WebSearch.Result item = results.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(9), dp(7), dp(9), dp(7));
                row.setBackground(rounded(Color.rgb(24, 33, 60), Color.rgb(67, 81, 125), 7));
                TextView title = label("[" + (i + 1) + "] " + item.title, 12,
                        Color.rgb(210, 222, 255), false);
                title.setMaxLines(2);
                row.addView(title);
                TextView host = label(WebSearch.hostOf(item.url), 10, MUTED, true);
                row.addView(host);
                row.setOnClickListener(view -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Link", item.url));
                    setNotice("Link copiato: aprilo in un browser.");
                });
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = dp(6);
                content.addView(row, rowParams);
            }
        }

        if (!note.isEmpty()) {
            TextView detail = label(note, 11, Color.rgb(228, 185, 144), false);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            detailParams.topMargin = dp(8);
            content.addView(detail, detailParams);
        }
        TextView source = label("Ricerca via Tor · fonti non verificate", 10, MUTED, false);
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceParams.topMargin = dp(8);
        content.addView(source, sourceParams);

        StringBuilder stored = new StringBuilder(answered ? summary : "Nessun risultato per " + query);
        for (int i = 0; i < results.size(); i++) {
            stored.append('\n').append('[').append(i + 1).append("] ")
                    .append(results.get(i).title).append(" — ").append(results.get(i).url);
        }
        append("assistant", stored.toString());
        setBusy(false);
        setNotice("");
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void discoverSheet() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(label("Carico notizie via Tor…", 12, MUTED, false));
        sheet("Scopri", list, "Aggiorna", this::discoverSheet);
        netWorker.execute(() -> {
            List<Discover.Item> tech = new ArrayList<>();
            List<Discover.Item> news = new ArrayList<>();
            StringBuilder note = new StringBuilder();
            try {
                if (!ensureTor()) {
                    note.append("Tor non è partito: nessun aggiornamento.");
                } else {
                    try {
                        tech = Discover.hackerNews(this::torGet, 8);
                    } catch (Exception failure) {
                        note.append("Hacker News non raggiungibile. ");
                    }
                    try {
                        news.addAll(Discover.rss("www.ansa.it",
                                "/sito/notizie/topnews/topnews_rss.xml", "ANSA", this::torGet, 5));
                    } catch (Exception failure) {
                        note.append("ANSA non raggiungibile. ");
                    }
                    try {
                        news.addAll(Discover.rss("feeds.bbci.co.uk",
                                "/news/world/rss.xml", "BBC World", this::torGet, 5));
                    } catch (Exception failure) {
                        note.append("BBC non raggiungibile. ");
                    }
                }
            } catch (Exception failure) {
                note.append("Aggiornamento non riuscito.");
            }
            final List<Discover.Item> techItems = tech;
            final List<Discover.Item> newsItems = news;
            final String message = note.toString().trim();
            runOnUiThread(() -> {
                list.removeAllViews();
                if (!message.isEmpty()) {
                    list.addView(label(message, 11, Color.rgb(228, 185, 144), false));
                }
                addSection(list, "Hacker News", techItems);
                addSection(list, "Notizie", newsItems);
                if (techItems.isEmpty() && newsItems.isEmpty() && message.isEmpty()) {
                    list.addView(label("Nessun contenuto disponibile.", 12, MUTED, false));
                }
            });
        });
    }

    private void addSection(LinearLayout list, String heading, List<Discover.Item> items) {
        if (items.isEmpty()) return;
        TextView header = label(heading, 12, MUTED, true);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = dp(12);
        headerParams.bottomMargin = dp(6);
        list.addView(header, headerParams);
        for (Discover.Item item : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            row.setBackground(rounded(SURFACE, LINE, 9));
            TextView title = label(item.title, 13, TEXT, false);
            row.addView(title);
            TextView meta = label(item.meta + " · " + WebSearch.hostOf(item.url), 10, MUTED, true);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaParams.topMargin = dp(3);
            row.addView(meta, metaParams);
            row.setOnClickListener(view -> openLink(item.url));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(6);
            list.addView(row, rowParams);
        }
    }

    private void openLink(String url) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
        } catch (Exception failure) {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("Link", url));
            setNotice("Link copiato.");
        }
    }

    private void reply() {
        if (closing) return;
        HttpURLConnection connection = null;
        try {
            if (server == null || !server.isAlive()) throw new IOException("Model is offline");
            JSONArray payloadMessages = new JSONArray();
            payloadMessages.put(new JSONObject().put("role", "system").put("content", CHAT_RULES));
            JSONArray history = current == null ? new JSONArray() : current.messages;
            int from = Math.max(0, history.length() - 5);
            for (int i = from; i < history.length(); i++) {
                JSONObject item = history.getJSONObject(i);
                String role = item.getString("role");
                if (!role.equals("assistant") && !role.equals("user")) continue;
                payloadMessages.put(new JSONObject().put("role", role)
                        .put("content", item.getString("content")));
            }
            String answer = streamChat(payloadMessages, 240, 0.35);
            if (answer.isEmpty()) throw new IOException("Empty reply");
            final String text = answer;
            runOnUiThread(() -> finishReply(text, true));
        } catch (Exception failure) {
            runOnUiThread(() -> finishReply(
                    "Il modello locale non ha risposto. Prova con un messaggio più breve.", false));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void finishReply(String text, boolean ok) {
        if (currentContent == null) return;
        showText(currentContent, text);
        currentContent = null;
        if (ok) append("assistant", text);
        setBusy(false);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------- onion ----------

    private static String onionTarget(String prompt) {
        if (!prompt.matches("(?is).*\\bonion\\b.*")) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:^|[\\s(<])((?:[a-z0-9-]+\\.)+[a-z]{2,63})(?=$|[\\s,;:!?)\\]>])",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(prompt);
        if (!matcher.find()) return null;
        String domain = matcher.group(1).toLowerCase(Locale.ROOT);
        return domain.endsWith(".onion") ? null : cleanDomain(domain);
    }

    private void resolveOnion(String raw) {
        String domain = cleanDomain(raw);
        if (domain == null) {
            runOnUiThread(() -> finishOnion("", "", "Dominio non riconosciuto."));
            return;
        }
        String onion = "";
        String note;
        try {
            if (!ensureTor()) {
                note = "Tor non è partito: non verifico e non ripiego in chiaro.";
            } else {
                onion = onionOverTor(domain);
                note = onion.isEmpty()
                        ? "Il sito non dichiara Onion-Location (verificato via Tor)."
                        : "Fonte: intestazione Onion-Location di https://" + domain + ", letta via Tor.";
            }
        } catch (Exception failure) {
            onion = "";
            note = "Verifica via Tor non riuscita (" + failure.getClass().getSimpleName() + ").";
        }
        final String result = onion;
        final String detail = note;
        runOnUiThread(() -> finishOnion(domain, result, detail));
    }

    private void finishOnion(String domain, String onion, String note) {
        if (currentContent == null) currentContent = addMessage(false, "", false);
        LinearLayout content = currentContent;
        currentContent = null;
        content.removeAllViews();

        TextView head = label(onion.isEmpty()
                ? "Nessun onion verificato per " + domain
                : "Onion verificato per " + domain, 15, TEXT, false);
        content.addView(head);

        if (!onion.isEmpty()) {
            TextView address = label(onion, 12, Color.rgb(210, 222, 255), true);
            address.setTextIsSelectable(true);
            address.setPadding(dp(9), dp(7), dp(9), dp(7));
            address.setBackground(rounded(Color.rgb(24, 33, 60), Color.rgb(67, 81, 125), 7));
            LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            addressParams.topMargin = dp(8);
            content.addView(address, addressParams);

            TextView open = label("Apri in Tor Browser", 11, Color.rgb(225, 232, 255), true);
            open.setGravity(Gravity.CENTER);
            open.setPadding(dp(12), dp(9), dp(12), dp(9));
            open.setBackground(rounded(Color.rgb(43, 58, 97), Color.rgb(75, 96, 145), 8));
            open.setOnClickListener(view -> launchTor(onion));
            LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            openParams.topMargin = dp(9);
            content.addView(open, openParams);
        }

        TextView source = label(note, 11, MUTED, false);
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceParams.topMargin = dp(9);
        content.addView(source, sourceParams);

        append("assistant", onion.isEmpty()
                ? "Nessun onion verificato per " + domain + "\n" + note
                : "Onion verificato per " + domain + "\n" + onion + "\n" + note);
        setBusy(false);
        setNotice("");
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private boolean ensureTor() {
        synchronized (torLock) {
            if (torReady && torProcess != null && torProcess.isAlive()) return true;
            stopTor();
            if (closing) return false;
            File binary = new File(getApplicationInfo().nativeLibraryDir, "liblabtor.so");
            if (!binary.canExecute()) {
                showTor(-1, "motore Tor assente");
                return false;
            }
            File data = new File(getFilesDir(), "tor");
            if (!data.exists() && !data.mkdirs()) {
                showTor(-1, "cartella Tor non scrivibile");
                return false;
            }
            File log = new File(getCacheDir(), "tor.log");
            try {
                ProcessBuilder launch = new ProcessBuilder(binary.getPath(),
                        "--SocksPort", "127.0.0.1:" + TOR_PORT,
                        "--DataDirectory", data.getAbsolutePath(),
                        "--ClientOnly", "1",
                        "--Log", "notice stdout");
                launch.directory(getFilesDir());
                launch.redirectErrorStream(true);
                launch.redirectOutput(log);
                torProcess = launch.start();
            } catch (Exception failure) {
                showTor(-1, "avvio non riuscito");
                return false;
            }
            int last = -1;
            for (int tick = 0; tick < 240 && !closing; tick++) {
                Process running = torProcess;
                if (running == null || !running.isAlive()) break;
                int percent = bootstrapPercent(log);
                if (percent > last) {
                    last = percent;
                    showTor(percent, bootstrapLabel(log));
                }
                if (percent >= 100) {
                    torReady = true;
                    showTor(100, "pronto");
                    return true;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException stop) {
                    break;
                }
            }
            stopTor();
            showTor(-1, "bootstrap non completato");
            return false;
        }
    }

    private void showTor(int percent, String label) {
        String text;
        if (percent < 0) text = "Tor non disponibile: " + label + ".";
        else if (percent < 100) text = "Tor " + percent + "% · " + label;
        else text = "";
        runOnUiThread(() -> {
            setNotice(text);
            renderTorDot();
        });
    }

    private void stopTor() {
        Process running = torProcess;
        torProcess = null;
        torReady = false;
        if (running != null) {
            running.destroy();
            try {
                running.waitFor();
            } catch (InterruptedException ignored) {
            }
        }
        runOnUiThread(this::renderTorDot);
    }

    private static int bootstrapPercent(File log) {
        int percent = -1;
        try {
            String text = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
            int at = 0;
            while ((at = text.indexOf("Bootstrapped ", at)) >= 0) {
                int value = 0;
                int index = at + 13;
                boolean digits = false;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    value = value * 10 + (text.charAt(index) - '0');
                    index++;
                    digits = true;
                }
                if (digits && value > percent) percent = value;
                at = index;
            }
        } catch (Exception ignored) {
        }
        return percent;
    }

    private static String bootstrapLabel(File log) {
        try {
            String text = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
            int at = text.lastIndexOf("Bootstrapped ");
            if (at < 0) return "";
            int open = text.indexOf('(', at);
            int close = text.indexOf(')', open);
            if (open > 0 && close > open) return text.substring(open + 1, close);
        } catch (Exception ignored) {
        }
        return "";
    }

    private static final class TorReply {
        int status;
        String location;
        String onion;
    }

    private String onionOverTor(String domain) throws Exception {
        String host = domain;
        for (int hop = 0; hop < 4; hop++) {
            TorReply reply = torRequest(host);
            if (reply.onion != null) {
                String candidate = new URL(new URL("https://" + host + "/"), reply.onion.trim()).toString();
                if (!OnionAddress.isValidUrl(candidate)) throw new IOException("Onion-Location non valido");
                return candidate;
            }
            if (reply.status >= 300 && reply.status < 400 && reply.location != null) {
                URL next = new URL(new URL("https://" + host + "/"), reply.location.trim());
                if (!"https".equals(next.getProtocol()) || next.getHost().isEmpty()) return "";
                host = next.getHost();
                continue;
            }
            return "";
        }
        return "";
    }

    private TorReply torRequest(String host) throws Exception {
        Socket raw = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", TOR_PORT)));
        try {
            raw.connect(InetSocketAddress.createUnresolved(host, 443), 25000);
            raw.setSoTimeout(25000);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tls = (SSLSocket) factory.createSocket(raw, host, 443, true);
            SSLParameters params = tls.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(params);
            tls.startHandshake();
            String request = "GET / HTTP/1.1\r\nHost: " + host
                    + "\r\nUser-Agent: Laboratorio/2.0\r\nAccept: text/html,*/*\r\nConnection: close\r\n\r\n";
            tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            tls.getOutputStream().flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(tls.getInputStream(), StandardCharsets.ISO_8859_1));
            TorReply reply = new TorReply();
            String statusLine = reader.readLine();
            if (statusLine == null) throw new IOException("risposta vuota");
            String[] parts = statusLine.split(" ");
            if (parts.length < 2) throw new IOException("stato non valido");
            reply.status = Integer.parseInt(parts[1]);
            for (String line; (line = reader.readLine()) != null && !line.isEmpty(); ) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (name.equals("onion-location")) reply.onion = value;
                else if (name.equals("location")) reply.location = value;
            }
            tls.close();
            return reply;
        } finally {
            raw.close();
        }
    }

    private String torGet(String host, String path) throws Exception {
        Socket raw = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", TOR_PORT)));
        try {
            raw.connect(InetSocketAddress.createUnresolved(host, 443), 30000);
            raw.setSoTimeout(30000);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tls = (SSLSocket) factory.createSocket(raw, host, 443, true);
            SSLParameters params = tls.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(params);
            tls.startHandshake();
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + host
                    + "\r\nUser-Agent: Mozilla/5.0 (Linux; Android 12; Laboratorio)\r\n"
                    + "Accept: text/html,application/json\r\nAccept-Language: it,en;q=0.8\r\n"
                    + "Connection: close\r\n\r\n";
            tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            tls.getOutputStream().flush();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(tls.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            if (statusLine == null) throw new IOException("risposta vuota");
            String[] parts = statusLine.split(" ");
            if (parts.length < 2) throw new IOException("stato non valido");
            int status = Integer.parseInt(parts[1]);
            for (String line; (line = reader.readLine()) != null && !line.isEmpty(); ) {
                // Skip headers without buffering the whole response.
            }
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            StringBuilder body = new StringBuilder();
            char[] block = new char[8192];
            for (int count; (count = reader.read(block)) != -1; ) {
                body.append(block, 0, count);
                if (body.length() > 512 * 1024) throw new IOException("risposta troppo grande");
            }
            return body.toString();
        } finally {
            raw.close();
        }
    }

    private void launchTor(String value) {
        if (!OnionAddress.isValidUrl(value)) {
            setNotice("Indirizzo onion non valido: non apro nulla.");
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(value.trim()));
        view.setPackage(TOR_BROWSER);
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException absent) {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("Indirizzo onion", value.trim()));
            setNotice("Indirizzo copiato: incollalo nella barra di Tor Browser.");
            try {
                Intent open = getPackageManager().getLaunchIntentForPackage(TOR_BROWSER);
                if (open != null) startActivity(open);
            } catch (Exception ignored) {
            }
        }
    }

    private static String cleanDomain(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(0, colon);
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        return value.matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")
                ? value : null;
    }

    // ---------- model ----------

    private String expectedSha() {
        return prefs.getString("model.sha", MODEL_SHA);
    }

    private long expectedSize() {
        return prefs.getLong("model.size", MODEL_SIZE);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] block = new byte[1024 * 1024];
            for (int count; (count = input.read(block)) != -1; ) digest.update(block, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value));
        return hex.toString();
    }

    private boolean correctModel(File file) throws Exception {
        if (file.length() != expectedSize()) return false;
        return !closing && expectedSha().contentEquals(sha256(file));
    }

    private void startModel(boolean verified) {
        if (closing) return;
        if (server != null && server.isAlive() && healthy()) {
            uiStatus("ready", "Modello locale");
            return;
        }
        if (!model.exists()) {
            uiStatus("missing", "Modello assente");
            return;
        }
        try {
            uiStatus("loading", "Avvio modello");
            if (!verified && !correctModel(model)) {
                uiStatus("unverified", "Modello non verificato");
                uiNotice("Modello diverso da quello previsto: avvio senza verifica SHA-256.");
            }
            if (closing) return;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                port = socket.getLocalPort();
            }
            File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
            ProcessBuilder launch = new ProcessBuilder(new File(nativeDir, "liblabexec.so").getPath(),
                    "-m", model.getAbsolutePath(), "--host", "127.0.0.1", "--port", Integer.toString(port),
                    "--api-key", accessKey, "-ngl", "0", "-t", "4", "-c", "2048", "-np", "1",
                    "--no-webui", "--log-disable");
            launch.directory(getFilesDir());
            launch.environment().put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
            launch.redirectErrorStream(true);
            File log = new File(getCacheDir(), "engine.log");
            launch.redirectOutput(log);
            server = launch.start();
            for (int retry = 0; retry < 125 && !closing && server.isAlive(); retry++) {
                if (healthy()) {
                    uiStatus("ready", "Modello locale");
                    return;
                }
                Thread.sleep(400);
            }
            stopServer();
            if (!closing) {
                uiStatus("error", "Motore non pronto");
                uiNotice("Il motore locale non si è avviato. Riprova dopo aver chiuso le altre app.");
            }
        } catch (Exception failure) {
            stopServer();
            if (!closing) uiStatus("error", "Motore non disponibile");
        }
    }

    private void uiStatus(String state, String text) {
        runOnUiThread(() -> setStatus(state, text));
    }

    private void uiNotice(String text) {
        runOnUiThread(() -> setNotice(text));
    }

    private HttpURLConnection connect(String route, int timeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + route).openConnection(Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(800);
        connection.setReadTimeout(timeout);
        connection.setRequestProperty("Authorization", "Bearer " + accessKey);
        return connection;
    }

    private boolean healthy() {
        HttpURLConnection connection = null;
        try {
            connection = connect("/health", 800);
            return connection.getResponseCode() == 200;
        } catch (IOException failure) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void pickModel() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), MODEL_PICKER);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != MODEL_PICKER || result != RESULT_OK || data == null || data.getData() == null) return;
        worker.execute(() -> {
            File temporary = new File(model.getParentFile(), "model.part");
            try {
                uiStatus("loading", "Importazione");
                model.getParentFile().mkdirs();
                try (InputStream input = getContentResolver().openInputStream(data.getData())) {
                    if (input == null) throw new IOException("Model unavailable");
                    try (java.io.OutputStream output = Files.newOutputStream(temporary.toPath())) {
                        byte[] block = new byte[1024 * 1024];
                        long total = 0;
                        for (int count; (count = input.read(block)) != -1; ) {
                            total += count;
                            if (closing || total > MODEL_SIZE) throw new IOException("Invalid model size");
                            output.write(block, 0, count);
                        }
                    }
                }
                long size = Files.size(temporary.toPath());
                if (size < 200L * 1024 * 1024 || size > 4L * 1024 * 1024 * 1024) {
                    throw new IOException("Invalid model size");
                }
                String checksum = sha256(temporary);
                Files.move(temporary.toPath(), model.toPath(), StandardCopyOption.REPLACE_EXISTING);
                prefs.edit().putLong("model.size", size).putString("model.sha", checksum).apply();
                startModel(true);
            } catch (Exception failure) {
                uiStatus("missing", "Importazione interrotta");
                uiNotice("Seleziona il file Qwen2.5 1.5B Instruct Q4_K_M distribuito dal progetto Qwen.");
            } finally {
                temporary.delete();
            }
        });
    }

    // ---------- voice ----------

    private void requestVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
            return;
        }
        if (capture != null) return;
        OfflineVoice current = new OfflineVoice(this);
        capture = current;
        micActive(true);
        setNotice("Preparo il riconoscimento vocale…");
        voiceWorker.execute(() -> {
            String state = "result";
            String value;
            try {
                value = current.listen(() -> runOnUiThread(() -> setNotice("Sto ascoltando…")));
                if (value.isEmpty()) {
                    state = "error";
                    value = "Nessuna frase riconosciuta.";
                }
            } catch (Exception | LinkageError failure) {
                state = "error";
                value = "Il microfono non è disponibile.";
            }
            String finalState = state;
            String finalValue = value;
            runOnUiThread(() -> {
                capture = null;
                micActive(false);
                if (current.discarded) return;
                setNotice("error".equals(finalState) ? finalValue : "");
                if ("result".equals(finalState)) {
                    String existing = input.getText().toString();
                    String merged = existing.isEmpty() ? finalValue : existing + " " + finalValue;
                    input.setText(merged);
                    input.setSelection(input.getText().length());
                    input.requestFocus();
                }
            });
        });
    }

    private void micActive(boolean active) {
        mic.setColorFilter(active ? Color.rgb(255, 159, 188) : Color.rgb(182, 201, 255));
        mic.setContentDescription(active ? "Ferma dettatura" : "Detta un messaggio");
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != MICROPHONE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestVoice();
        else setNotice("Consenti l'uso del microfono per dettare.");
    }

    // ---------- system actions ----------

    private void pinWidget() {
        AppWidgetManager widgets = getSystemService(AppWidgetManager.class);
        try {
            if (widgets.isRequestPinAppWidgetSupported() && widgets.requestPinAppWidget(
                    new ComponentName(this, LabWidget.class), null, null)) return;
        } catch (IllegalStateException unavailable) {
            // The launcher can reject requests while the activity is losing focus.
        }
        setNotice("Tieni premuto sulla Home e cerca Chat Laboratorio in Widget.");
    }

    private void wallpaperPreview() {
        Intent preview = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(this, NightWallpaper.class));
        try {
            startActivity(preview);
        } catch (ActivityNotFoundException unavailable) {
            setNotice("Scegli Tokyo Night · Laboratorio dagli sfondi animati di Android.");
        }
    }

    @Override protected void onStop() {
        if (capture != null) capture.stop(true);
        micActive(false);
        super.onStop();
    }

    private void stopServer() {
        Process process = server;
        server = null;
        if (process != null) process.destroy();
    }

    @Override protected void onDestroy() {
        closing = true;
        worker.shutdownNow();
        netWorker.shutdownNow();
        voiceWorker.shutdownNow();
        stopTor();
        stopServer();
        saveChats();
        super.onDestroy();
    }

    // ---------- small helpers ----------

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String text, int size, int color, boolean mono) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (mono) view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    private ImageButton iconButton(int drawable, int tint) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawable);
        button.setColorFilter(tint);
        button.setBackground(rounded(Color.TRANSPARENT, Color.TRANSPARENT, 8));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return button;
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(1), stroke);
        return shape;
    }
}

package it.reno.lab;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
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
    private static final long MODEL_SIZE = 1_117_320_736L;
    private static final String MODEL_SHA =
            "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e";
    private static final String CHAT_RULES = "Sei l'assistente locale del Reno4. "
            + "Rispondi in italiano salvo richiesta diversa. Aiuta con traduzioni, testi e script. "
            + "Puoi suggerire comandi, ma non eseguirli e non dichiarare di avere accesso a dispositivi, "
            + "file o Internet. Mostra i comandi in modo leggibile. "
            + "Non inventare indirizzi .onion, link o fonti: l'app verifica gli indirizzi onion dal dominio "
            + "dichiarato e li apre in Tor Browser. Se una richiesta è poco chiara, chiedi di ripeterla "
            + "invece di rifiutare.";
    private static final int TOR_PORT = 19050;
    private static final String TOR_BROWSER = "org.torproject.torbrowser";
    private static final int BACKGROUND = Color.rgb(12, 16, 33);
    private static final int SURFACE = Color.rgb(22, 29, 51);
    private static final int LINE = Color.rgb(44, 58, 94);
    private static final int TEXT = Color.rgb(230, 236, 255);
    private static final int MUTED = Color.rgb(154, 166, 199);
    private static final int ACCENT = Color.rgb(162, 184, 255);
    private static final int MINT = Color.rgb(137, 215, 208);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService netWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService voiceWorker = Executors.newSingleThreadExecutor();
    private final String accessKey = UUID.randomUUID().toString();
    private final Object torLock = new Object();
    private JSONArray history = new JSONArray();

    private volatile Process server;
    private Process torProcess;
    private volatile boolean torReady;
    private volatile boolean closing;
    private volatile int port;
    private File model;
    private OfflineVoice capture;
    private Boolean widgetVoice;

    private ScrollView scroll;
    private LinearLayout messages;
    private LinearLayout welcome;
    private EditText input;
    private ImageButton send;
    private ImageButton mic;
    private TextView status;
    private TextView notice;
    private LinearLayout currentContent;
    private boolean modelReady;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        receiveWidgetEntry(getIntent());
        File models = getExternalFilesDir("models");
        model = new File(models == null ? getFilesDir() : models, "qwen2.5.gguf");
        buildUi();
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

    // ---------- interface ----------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setFitsSystemWindows(true);
        int top = dp(8);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(6), dp(8));
        bar.setBackgroundColor(Color.rgb(16, 20, 39));

        TextView mark = label("⌁", 19, ACCENT, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(Color.rgb(24, 31, 60), Color.rgb(82, 102, 157), 6));
        bar.addView(mark, new LinearLayout.LayoutParams(dp(26), dp(26)));

        TextView title = label("Laboratorio", 16, TEXT, false);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMarginStart(dp(9));
        bar.addView(title, titleParams);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        status = label("● Verifica modello", 11, Color.rgb(231, 187, 128), true);
        status.setPadding(dp(6), dp(4), dp(6), dp(4));
        status.setOnClickListener(view -> {
            if (!modelReady) pickModel();
        });
        bar.addView(status);

        ImageButton menu = iconButton(R.drawable.ic_more, Color.rgb(175, 198, 255));
        menu.setContentDescription("Altre azioni");
        menu.setOnClickListener(this::openMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(38), dp(38)));

        root.addView(bar, new LinearLayout.LayoutParams(
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
        welcome.setPadding(dp(18), dp(28), dp(18), dp(20));
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
        TextView subtitle = label("Scrivi o usa il microfono per dettare.", 13, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(6);
        welcome.addView(subtitle, subtitleParams);

        addSuggestion("Traduci in italiano questo testo: ");
        addSuggestion("Spiegami questo script, senza eseguirlo: ");
        addSuggestion("Aiutami a organizzare un laboratorio Linux personale.");

        messages.addView(welcome, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        notice = label("", 12, Color.rgb(228, 185, 144), false);
        notice.setPadding(dp(16), dp(2), dp(16), dp(4));
        notice.setVisibility(View.GONE);
        root.addView(notice);

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(10), dp(8), dp(10), dp(10));
        composer.setBackgroundColor(Color.rgb(17, 23, 45));

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
        composer.addView(input, inputParams);

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
        composer.addView(mic, micParams);

        send = new ImageButton(this);
        send.setImageResource(R.drawable.ic_send);
        send.setContentDescription("Invia messaggio");
        send.setBackground(rounded(Color.rgb(164, 185, 250), Color.TRANSPARENT, 12));
        send.setPadding(dp(10), dp(10), dp(10), dp(10));
        send.setOnClickListener(view -> sendPrompt());
        composer.addView(send, new LinearLayout.LayoutParams(dp(42), dp(42)));

        root.addView(composer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
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

    private void openMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Nuova chat");
        menu.getMenu().add(0, 2, 1, "Aggiungi widget alla Home");
        menu.getMenu().add(0, 3, 2, "Sfondo Home");
        menu.getMenu().add(0, 4, 3, "Importa modello");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: newChat(); return true;
                case 2: pinWidget(); return true;
                case 3: wallpaperPreview(); return true;
                case 4: pickModel(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void newChat() {
        if (busy) return;
        history = new JSONArray();
        messages.removeAllViews();
        messages.addView(welcome);
        welcome.setVisibility(View.VISIBLE);
        setNotice("");
        input.requestFocus();
    }

    private LinearLayout addMessage(boolean fromUser, String text) {
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

    private void setNotice(String text) {
        if (text == null || text.isEmpty()) {
            notice.setVisibility(View.GONE);
            return;
        }
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
    }

    private void setStatus(String state, String text) {
        status.setText("● " + text);
        int color = Color.rgb(231, 187, 128);
        if ("ready".equals(state)) color = Color.rgb(168, 225, 186);
        else if ("error".equals(state)) color = Color.rgb(239, 149, 170);
        status.setTextColor(color);
        modelReady = "ready".equals(state);
    }

    private void setBusy(boolean value) {
        busy = value;
        send.setEnabled(!value);
        send.setAlpha(value ? 0.5f : 1f);
        mic.setEnabled(!value);
        input.setEnabled(!value);
    }

    // ---------- conversation ----------

    private void sendPrompt() {
        String prompt = input.getText().toString().trim();
        if (prompt.isEmpty() || busy) return;
        if (prompt.matches("(?is).*\\b[a-z2-7]{16,56}\\.onion\\b.*")) {
            addMessage(true, prompt);
            addMessage(false, "Non apro indirizzi .onion scritti a mano: non posso verificarne la provenienza. "
                    + "Chiedimi l'onion di un dominio, per esempio «apri l'onion di torproject.org».");
            input.setText("");
            return;
        }
        String domain = onionTarget(prompt);
        input.setText("");
        setNotice("");
        if (domain != null) {
            addMessage(true, prompt);
            currentContent = addMessage(false, "Verifico l'indirizzo onion dichiarato da " + domain + "…");
            setBusy(true);
            netWorker.execute(() -> resolveOnion(domain));
            return;
        }
        if (!modelReady) {
            setNotice("Attendi il modello locale o importalo per iniziare.");
            return;
        }
        addMessage(true, prompt);
        try {
            history.put(new JSONObject().put("role", "user").put("content", prompt));
        } catch (Exception ignored) {
        }
        currentContent = addMessage(false, "Sto elaborando…");
        setBusy(true);
        worker.execute(this::reply);
    }

    private void reply() {
        if (closing) return;
        HttpURLConnection connection = null;
        try {
            if (server == null || !server.isAlive()) throw new IOException("Model is offline");
            JSONArray payloadMessages = new JSONArray();
            payloadMessages.put(new JSONObject().put("role", "system").put("content", CHAT_RULES));
            int from = Math.max(0, history.length() - 5);
            for (int i = from; i < history.length(); i++) {
                JSONObject item = history.getJSONObject(i);
                String role = item.getString("role");
                if (!role.equals("assistant") && !role.equals("user")) continue;
                payloadMessages.put(new JSONObject().put("role", role)
                        .put("content", item.getString("content")));
            }
            byte[] payload = new JSONObject().put("messages", payloadMessages).put("max_tokens", 240)
                    .put("temperature", 0.35).put("stream", false)
                    .toString().getBytes(StandardCharsets.UTF_8);
            connection = connect("/v1/chat/completions", 120000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            if (connection.getResponseCode() != 200) {
                throw new IOException("Model HTTP " + connection.getResponseCode());
            }
            String raw;
            try (InputStream response = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] block = new byte[4096];
                for (int count; (count = response.read(block)) != -1; ) {
                    if (bytes.size() + count > 128 * 1024) throw new IOException("Reply too large");
                    bytes.write(block, 0, count);
                }
                raw = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
            String answer = new JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim();
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
        if (ok) {
            try {
                history.put(new JSONObject().put("role", "assistant").put("content", text));
            } catch (Exception ignored) {
            }
        }
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
        if (currentContent == null) {
            currentContent = addMessage(false, "");
        }
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
        runOnUiThread(() -> setNotice(text));
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
                    + "\r\nUser-Agent: Laboratorio/1.5\r\nAccept: text/html,*/*\r\nConnection: close\r\n\r\n";
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

    private boolean correctModel(File file) throws Exception {
        if (file.length() != MODEL_SIZE) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] block = new byte[1024 * 1024];
            for (int count; !closing && (count = input.read(block)) != -1; ) digest.update(block, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value));
        return !closing && MODEL_SHA.contentEquals(hex);
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
                uiStatus("missing", "Modello non valido");
                uiNotice("Importa la versione Qwen2.5 1.5B Instruct Q4_K_M prevista per questo Reno4.");
                return;
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
                if (!correctModel(temporary)) throw new IOException("Invalid model checksum");
                Files.move(temporary.toPath(), model.toPath(), StandardCopyOption.REPLACE_EXISTING);
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

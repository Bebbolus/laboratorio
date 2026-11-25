package it.reno.lab;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class OfflineVoice {
    private static final int SAMPLE_RATE = 16000;
    private static final String MODEL_FOLDER = "vosk-model-small-it-0.22";
    private final Context context;
    volatile boolean stopped;
    volatile boolean discarded;

    OfflineVoice(Context context) {
        this.context = context;
    }

    void stop(boolean discard) {
        if (discard) discarded = true;
        stopped = true;
    }

    String listen(Runnable onListening) throws Exception {
        try (Model model = new Model(modelPath().toString());
             Recognizer engine = new Recognizer(model, SAMPLE_RATE)) {
            if (stopped) return "";
            int minimum = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("Unsupported microphone format");
            AudioRecord audio = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 8192));
            try {
                if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
                audio.startRecording();
                onListening.run();
                long deadline = SystemClock.elapsedRealtime() + 25000;
                short[] samples = new short[1024];
                StringBuilder text = new StringBuilder();
                while (!stopped && SystemClock.elapsedRealtime() < deadline) {
                    int count = audio.read(samples, 0, samples.length);
                    if (count < 0) throw new IOException("Microphone error " + count);
                    if (count > 0 && engine.acceptWaveForm(samples, count)) append(text, engine.getResult());
                }
                if (!discarded) append(text, engine.getFinalResult());
                return discarded ? "" : text.toString();
            } finally {
                if (audio.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audio.stop();
                audio.release();
            }
        }
    }

    String transcribeSample(InputStream input) throws Exception {
        try (Model model = new Model(modelPath().toString());
             Recognizer engine = new Recognizer(model, SAMPLE_RATE)) {
            StringBuilder text = new StringBuilder();
            byte[] samples = new byte[4096];
            for (int count; (count = input.read(samples)) != -1;) {
                if (engine.acceptWaveForm(samples, count)) append(text, engine.getResult());
            }
            append(text, engine.getFinalResult());
            return text.toString();
        }
    }

    private static void append(StringBuilder transcript, String result) throws Exception {
        String segment = new JSONObject(result).optString("text").trim();
        if (!segment.isEmpty()) {
            if (transcript.length() > 0) transcript.append(' ');
            transcript.append(segment);
        }
    }

    private Path modelPath() throws IOException {
        Path model = context.getNoBackupFilesDir().toPath().resolve(MODEL_FOLDER);
        if (Files.isDirectory(model)) return model;
        Path temporary = Files.createTempDirectory(model.getParent(), "vocab-");
        try (ZipInputStream archive = new ZipInputStream(context.getAssets().open("voice-it.zip"))) {
            for (ZipEntry item; (item = archive.getNextEntry()) != null;) {
                Path output = temporary.resolve(item.getName()).normalize();
                if (!output.startsWith(temporary.resolve(MODEL_FOLDER))) throw new IOException("Unexpected voice asset");
                if (item.isDirectory()) Files.createDirectories(output);
                else {
                    Files.createDirectories(output.getParent());
                    try (OutputStream destination = Files.newOutputStream(output)) {
                        byte[] block = new byte[65536];
                        for (int count; (count = archive.read(block)) != -1;) destination.write(block, 0, count);
                    }
                }
                archive.closeEntry();
            }
            Files.move(temporary.resolve(MODEL_FOLDER), model);
            return model;
        } finally {
            try (Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
}

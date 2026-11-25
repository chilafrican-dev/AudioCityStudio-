package ug.audiocity.lyrics;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Luganda speech-to-text with Sunbird AI (https://sunbird.ai), a Ugandan language-technology organisation. The user
 * supplies their own API key. The song is sent to Sunbird in pieces of about 30 seconds (16 kHz mono WAV, cut at quiet
 * moments), so each request is small and quick, progress is visible, and every piece comes back with its rough time.
 */
final class Sunbird {
    static final String ENDPOINT = "https://api.sunbird.ai/tasks/audio/transcriptions";
    static final String LEGACY = "https://api.sunbird.ai/tasks/stt";
    static final String SIGNUP = "https://api.sunbird.ai";
    static final int READ_TIMEOUT_MS = 150_000;
    /** The endpoint that last worked (the newer one first; older API versions use the legacy path). */
    static volatile String endpoint = ENDPOINT;

    /** Text Sunbird heard in one piece of the song. */
    static final class Part {
        final long startMs, endMs;
        final String text;
        Part(long startMs, long endMs, String text) { this.startMs = startMs; this.endMs = endMs; this.text = text; }
    }

    /** Transcribes 16 kHz mono audio piece by piece. Throws with a readable message on failure, or CancellationException. */
    static ArrayList<Part> transcribe(float[] audio, String token, String language, VideoExporter.Listener progress) throws IOException {
        int[] cuts = AutoTiming.pieces(audio, Audio16k.RATE);
        int n = cuts.length - 1;
        ArrayList<Part> parts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (progress.cancelled()) throw new CancellationException();
            byte[] wav = wav(audio, cuts[i], cuts[i + 1]);
            String stage = "Sunbird is writing part " + (i + 1) + " of " + n;
            VideoExporter.Listener sub = AutoSync.scaled(progress, i / (float) n, (i + 1) / (float) n);
            String text;
            try { text = postWithFallback(wav, token, language, sub, stage); }
            catch (SocketTimeoutException e) {
                sub.progress(0, stage + " (trying again)…");
                try { text = postWithFallback(wav, token, language, sub, stage); }
                catch (SocketTimeoutException again) { throw new IOException("Sunbird did not answer for " + (READ_TIMEOUT_MS / 60000.0) + " minutes (part " + (i + 1) + " of " + n + "). Their service may be busy — try again later."); }
            }
            parts.add(new Part(cuts[i] * 1000L / Audio16k.RATE, cuts[i + 1] * 1000L / Audio16k.RATE, text));
        }
        return parts;
    }

    static String postWithFallback(byte[] wav, String token, String language, VideoExporter.Listener l, String stage) throws IOException {
        try { return post(endpoint, wav, token, language, l, stage); }
        catch (NotFound e) {
            String other = endpoint.equals(ENDPOINT) ? LEGACY : ENDPOINT;
            String text = post(other, wav, token, language, l, stage);
            endpoint = other;
            return text;
        }
    }

    static final class NotFound extends IOException { NotFound(String m) { super(m); } }

    static String post(String url, byte[] wav, String token, String language, VideoExporter.Listener l, String stage) throws IOException {
        String boundary = "----LyricVideoStudio" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n" + language + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"song.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        final HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        final boolean[] done = {false}, cancelled = {false};
        final long started = System.currentTimeMillis();
        final float[] fraction = {0};
        final String[] phase = {"uploading"};
        // Watchdog: shows that work is going on, and makes Cancel stop a request that is waiting for Sunbird.
        Thread watch = new Thread(() -> {
            while (!done[0]) {
                if (l.cancelled()) { cancelled[0] = true; http.disconnect(); return; }
                long s = (System.currentTimeMillis() - started) / 1000;
                l.progress(fraction[0], String.format(Locale.US, "%s · %s · %d:%02d", stage, phase[0], s / 60, s % 60));
                try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            }
        }, "sunbird-watch");
        watch.start();
        try {
            http.setDoOutput(true);
            http.setConnectTimeout(30_000);
            http.setReadTimeout(READ_TIMEOUT_MS);
            http.setFixedLengthStreamingMode((long) head.length + wav.length + tail.length);
            http.setRequestMethod("POST");
            http.setRequestProperty("Authorization", "Bearer " + token.trim());
            http.setRequestProperty("Accept", "application/json");
            http.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = http.getOutputStream()) {
                out.write(head);
                for (int i = 0; i < wav.length; i += 32768) {
                    out.write(wav, i, Math.min(32768, wav.length - i));
                    fraction[0] = .4f * (i + 32768f) / wav.length;
                }
                out.write(tail);
            }
            fraction[0] = .45f;
            phase[0] = "listening";
            int code = http.getResponseCode();
            String body = read(code >= 400 ? http.getErrorStream() : http.getInputStream());
            fraction[0] = 1;
            if (code == 404 || code == 405) throw new NotFound("Sunbird endpoint not found (HTTP " + code + ").");
            if (code == 401 || code == 403) throw new IOException("Sunbird did not accept the API key (HTTP " + code + "). Check the key in “Write the lyrics for me → Sunbird API key”.");
            if (code == 413) throw new IOException("Sunbird says the audio is too large (HTTP 413).");
            if (code == 422) throw new IOException("Sunbird could not read the request (HTTP 422): " + shorten(body));
            if (code == 429) throw new IOException("Sunbird's limit for your key was reached. Try again later.");
            if (code / 100 != 2) throw new IOException("Sunbird error (HTTP " + code + "): " + shorten(body));
            return text(body);
        } catch (IOException e) {
            if (cancelled[0]) throw new CancellationException();
            throw e;
        } finally {
            done[0] = true;
            watch.interrupt();
            http.disconnect();
        }
    }

    /** 16-bit mono WAV of audio[from..to). */
    static byte[] wav(float[] a, int from, int to) {
        int n = Math.max(0, to - from);
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + n * 2).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(Audio16k.RATE).putInt(Audio16k.RATE * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(n * 2);
        float peak = 1e-6f;
        for (int i = from; i < to; i++) peak = Math.max(peak, Math.abs(a[i]));
        float gain = Math.min(8f, .9f / peak); // even out quiet pieces
        for (int i = from; i < to; i++) b.putShort((short) Math.max(-32768, Math.min(32767, Math.round(a[i] * gain * 32767))));
        return b.array();
    }

    /** Finds the transcript in Sunbird's JSON reply (field names differ between API versions). */
    static String text(String body) throws IOException {
        try {
            JSONObject j = new JSONObject(body);
            for (String key : new String[]{"audio_transcription", "transcription", "text", "output"}) {
                if (!j.has(key)) continue;
                Object v = j.opt(key);
                if (v instanceof String) return ((String) v).trim();
                if (v instanceof JSONObject) { JSONObject o = (JSONObject) v; return o.optString("audio_transcription", o.optString("text", "")).trim(); }
            }
        } catch (org.json.JSONException ignored) {}
        throw new IOException("Sunbird replied without a transcript: " + shorten(body));
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in) { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = i.read(buf)) != -1) b.write(buf, 0, n); return new String(b.toByteArray(), StandardCharsets.UTF_8); }
    }

    static String shorten(String s) { s = s == null ? "" : s.trim(); return s.length() > 300 ? s.substring(0, 300) + "…" : s; }
}

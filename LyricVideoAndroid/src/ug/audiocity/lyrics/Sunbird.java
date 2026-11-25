package ug.audiocity.lyrics;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Luganda speech-to-text with Sunbird AI (https://sunbird.ai), a Ugandan language-technology organisation. The user
 * supplies their own API key. The song is uploaded to Sunbird's servers for transcription.
 */
final class Sunbird {
    static final String ENDPOINT = "https://api.sunbird.ai/tasks/audio/transcriptions";
    static final String LEGACY = "https://api.sunbird.ai/tasks/stt";
    static final String SIGNUP = "https://api.sunbird.ai";

    /** Transcribes the song; returns the text. Throws with a readable message on failure. */
    static String transcribe(File audio, String token, String language) throws IOException {
        try { return post(ENDPOINT, audio, token, language); }
        catch (NotFound e) { return post(LEGACY, audio, token, language); } // older accounts / API versions
    }

    static final class NotFound extends IOException { NotFound(String m) { super(m); } }

    static String post(String endpoint, File audio, String token, String language) throws IOException {
        String boundary = "----LyricVideoStudio" + UUID.randomUUID();
        HttpURLConnection http = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            http.setDoOutput(true);
            http.setConnectTimeout(30_000);
            http.setReadTimeout(15 * 60_000);
            http.setChunkedStreamingMode(1 << 16);
            http.setRequestMethod("POST");
            http.setRequestProperty("Authorization", "Bearer " + token.trim());
            http.setRequestProperty("Accept", "application/json");
            http.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream out = new BufferedOutputStream(http.getOutputStream())) {
                field(out, boundary, "language", language);
                String name = audio.getName();
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"" + name + "\"\r\nContent-Type: " + mime(name) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                try (InputStream in = new FileInputStream(audio)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code = http.getResponseCode();
            String body = read(code >= 400 ? http.getErrorStream() : http.getInputStream());
            if (code == 404 || code == 405) throw new NotFound("Sunbird endpoint not found (HTTP " + code + ").");
            if (code == 401 || code == 403) throw new IOException("Sunbird did not accept the API key (HTTP " + code + "). Check the key in “Write lyrics for me → Sunbird key”.");
            if (code == 413) throw new IOException("The song file is too large for Sunbird.");
            if (code == 429) throw new IOException("Sunbird's limit for your key was reached. Try again later.");
            if (code / 100 != 2) throw new IOException("Sunbird error (HTTP " + code + "): " + shorten(body));
            return text(body);
        } finally { http.disconnect(); }
    }

    /** Finds the transcript in Sunbird's JSON reply (field names differ between API versions). */
    static String text(String body) throws IOException {
        try {
            JSONObject j = new JSONObject(body);
            for (String key : new String[]{"audio_transcription", "transcription", "text", "output"}) {
                Object v = j.opt(key);
                if (v instanceof String && !((String) v).trim().isEmpty()) return ((String) v).trim();
                if (v instanceof JSONObject) { String inner = ((JSONObject) v).optString("audio_transcription", ((JSONObject) v).optString("text", "")); if (!inner.isEmpty()) return inner.trim(); }
            }
        } catch (org.json.JSONException ignored) {}
        throw new IOException("Sunbird replied without a transcript: " + shorten(body));
    }

    static void field(OutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    static String mime(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".mp4")) return "audio/mp4";
        if (n.endsWith(".ogg") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".flac")) return "audio/flac";
        return "application/octet-stream";
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in) { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n; while ((n = i.read(buf)) != -1) b.write(buf, 0, n); return new String(b.toByteArray(), StandardCharsets.UTF_8); }
    }

    static String shorten(String s) { s = s == null ? "" : s.trim(); return s.length() > 300 ? s.substring(0, 300) + "…" : s; }
}

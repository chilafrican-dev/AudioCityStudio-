package ug.audiocity.studio;

import org.json.*;
import java.io.*;
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.*;

/**
 * Online loop library: searches Freesound for CC0 (public-domain) loops, keeps only CC0 results, reads tempo and
 * length from the sound's name, tags and description, and records the credit. Platform independent; the download
 * and decoding live in the UI layer.
 */
final class ProLoops {
    private ProLoops() {}
    static final String API = "https://freesound.org/apiv2/search/text/", KEY_PAGE = "https://freesound.org/apiv2/apply";
    static final String[] GENRES = {"Afrobeat", "Amapiano", "Afro house", "Hip hop", "Trap", "Drill", "Dancehall", "Reggae", "R&B", "House", "Pop", "Lo-fi", "Gospel", "Jazz", "Rock"};
    static final String[] PARTS = {"Drums", "Percussion", "Bass", "Guitar", "Piano", "Keys", "Synth", "Pad", "Strings", "Brass", "Vocal chops", "FX"};

    static final class Loop {
        final int id; final String name, user, license, page, preview, tags; final double seconds; final int bpm, bars;
        Loop(int id, String name, String user, String license, String page, String preview, String tags, double seconds, int bpm, int bars) { this.id = id; this.name = name; this.user = user; this.license = license; this.page = page; this.preview = preview; this.tags = tags; this.seconds = seconds; this.bpm = bpm; this.bars = bars; }
        boolean timed() { return bpm > 0 && bars > 0; }
        String credit() { return "“" + name + "” by " + user + " · CC0 1.0 (public domain) · " + page; }
        String info() { return (bpm > 0 ? bpm + " BPM" : "Tempo unknown") + (bars > 0 ? " · " + bars + " bar" + (bars == 1 ? "" : "s") : "") + String.format(Locale.US, " · %.1f s · by %s", seconds, user); }
        JSONObject json() throws JSONException { return new JSONObject().put("id", id).put("name", name).put("user", user).put("license", license).put("page", page).put("preview", preview).put("tags", tags).put("seconds", seconds).put("bpm", bpm).put("bars", bars); }
        static Loop from(JSONObject j) throws JSONException { return new Loop(j.getInt("id"), j.getString("name"), j.getString("user"), j.getString("license"), j.getString("page"), j.getString("preview"), j.optString("tags", ""), j.getDouble("seconds"), j.optInt("bpm", 0), j.optInt("bars", 0)); }
    }

    static boolean cc0(String license) { if (license == null) return false; String l = license.toLowerCase(Locale.ROOT); return l.contains("publicdomain/zero") || l.equals("creative commons 0") || l.equals("cc0"); }

    /** Search URL. bpmNear > 0 adds the tempo to the words searched for (Freesound has no reliable tempo filter for every sound). */
    static String url(String words, String genre, String part, int page, String token) throws UnsupportedEncodingException {
        StringBuilder q = new StringBuilder(); for (String w : new String[]{genre, part, words}) if (w != null && !w.trim().isEmpty()) q.append(q.length() > 0 ? " " : "").append(w.trim());
        if (q.length() == 0) q.append("loop");
        String filter = "license:\"Creative Commons 0\" tag:loop duration:[1 TO 90]";
        return API + "?query=" + enc(q.toString()) + "&filter=" + enc(filter) + "&sort=rating_desc&page_size=30&page=" + Math.max(1, page)
                + "&fields=" + enc("id,name,username,license,url,previews,duration,tags,description") + "&token=" + enc(token.trim());
    }
    static String enc(String s) throws UnsupportedEncodingException { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }

    static final Pattern BPM = Pattern.compile("(?i)(?<![0-9])([4-9][0-9]|1[0-9][0-9]|2[0-3][0-9])\\s*-?\\s*bpm|bpm\\s*-?\\s*:?\\s*([4-9][0-9]|1[0-9][0-9]|2[0-3][0-9])(?![0-9])");
    /** Tempo stated by the uploader in the name, then tags, then description; 0 if none. */
    static int bpm(String name, String tags, String description) {
        for (String s : new String[]{name, tags, description}) { if (s == null) continue; Matcher m = BPM.matcher(s); if (m.find()) return Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)); }
        return 0;
    }
    /** Whole bars (1, 2, 4, 8 or 16) when the duration fits the tempo within 2 %, else 0. Halved and doubled tempos are tried too. */
    static int bars(double seconds, int bpm) {
        if (bpm <= 0 || seconds <= 0) return 0;
        for (int b : new int[]{1, 2, 4, 8, 16}) { double expected = b * 240.0 / bpm; if (Math.abs(seconds - expected) / expected < .02) return b; }
        return 0;
    }
    static int fixTempo(double seconds, int bpm) { if (bars(seconds, bpm) > 0) return bpm; if (bpm * 2 <= 240 && bars(seconds, bpm * 2) > 0) return bpm * 2; if (bpm / 2 >= 40 && bars(seconds, bpm / 2) > 0) return bpm / 2; return bpm; }

    static final class Page { final List<Loop> loops = new ArrayList<>(); int total, rejected; boolean more; }
    /** Parses a Freesound search response, dropping anything that is not CC0 or has no preview. */
    static Page parse(String body) throws JSONException {
        JSONObject j = new JSONObject(body); Page p = new Page(); p.total = j.optInt("count", 0); p.more = !j.isNull("next") && j.optString("next", "").length() > 0;
        JSONArray a = j.optJSONArray("results"); if (a == null) return p;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i); String license = r.optString("license", "");
            JSONObject pv = r.optJSONObject("previews"); String preview = pv == null ? "" : pv.optString("preview-hq-mp3", pv.optString("preview-lq-mp3", ""));
            if (!cc0(license) || !preview.startsWith("https://")) { p.rejected++; continue; }
            StringBuilder tags = new StringBuilder(); JSONArray t = r.optJSONArray("tags"); if (t != null) for (int k = 0; k < t.length(); k++) tags.append(k > 0 ? " " : "").append(t.optString(k));
            String name = r.optString("name", "Loop"); double sec = r.optDouble("duration", 0); int bpm = bpm(name, tags.toString(), r.optString("description", "")); bpm = fixTempo(sec, bpm);
            p.loops.add(new Loop(r.getInt("id"), clean(name), r.optString("username", "unknown"), license, r.optString("url", "https://freesound.org/s/" + r.getInt("id") + "/"), preview, tags.toString(), sec, bpm, bars(sec, bpm)));
        }
        return p;
    }
    static String clean(String name) { String n = name.replaceAll("(?i)\\.(wav|mp3|aiff?|flac|ogg)$", "").replace('_', ' ').trim(); return n.length() > 80 ? n.substring(0, 80) : n; }
    /** Error text from the API, in plain words. */
    static String problem(int code, String body) {
        if (code == 401 || code == 403) return "Freesound did not accept the API key. Check it was copied completely.";
        if (code == 429) return "Freesound's hourly search limit was reached. Try again later.";
        String detail = ""; try { detail = new JSONObject(body).optString("detail", ""); } catch (Exception ignored) {}
        return "Freesound answered with error " + code + (detail.isEmpty() ? "." : ": " + detail);
    }
    /** Downloaded loops kept in the app for offline use. */
    static List<Loop> library(File dir) {
        ArrayList<Loop> out = new ArrayList<>(); File[] files = dir.listFiles(); if (files == null) return out;
        for (File f : files) if (f.getName().endsWith(".json")) { try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[(int) Math.min(65536, f.length())]; int n = 0, r; while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r; Loop l = Loop.from(new JSONObject(new String(b, 0, n, "UTF-8"))); if (cc0(l.license) && new File(dir, l.id + ".mp3").isFile()) out.add(l); } catch (Exception ignored) {} }
        Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name)); return out;
    }
    static boolean mp3(byte[] head, int n) { return n >= 3 && ((head[0] == 'I' && head[1] == 'D' && head[2] == '3') || ((head[0] & 0xff) == 0xff && (head[1] & 0xe0) == 0xe0)); }
    /** MP3 decoding can add a few milliseconds of silence at the start; remove it (only as much as the file is too long), then cut or pad to whole bars. */
    static float[] trimToBars(float[] d, int bars, int bpm) {
        int want = (int) Math.round(Core.RATE * 240.0 / bpm * bars), have = d.length / 2, extra = have - want, lead = 0;
        if (extra > 0) { int limit = Math.min(extra, Core.RATE / 10); while (lead < limit && Math.abs(d[2 * lead]) < .003f && Math.abs(d[2 * lead + 1]) < .003f) lead++; }
        float[] out = new float[want * 2]; System.arraycopy(d, lead * 2, out, 0, Math.min(out.length, d.length - lead * 2)); return out;
    }
    static float[] exact(float[] d, int bars, int bpm) { int want = (int) Math.round(Core.RATE * 240.0 / bpm * bars); float[] out = new float[want * 2]; System.arraycopy(d, 0, out, 0, Math.min(out.length, d.length)); return out; }
}

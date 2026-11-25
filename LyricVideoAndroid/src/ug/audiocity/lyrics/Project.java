package ug.audiocity.lyrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

/**
 * One lyric video project, stored in its own folder: project.json, the copied song (song.*), background photos
 * (photo_000.jpg, photo_001.jpg… in slideshow order), the loudness envelope (envelope.bin) and an optional font (font.ttf).
 */
final class Project {
    static final int ENVELOPE_RATE = 100; // values per second

    File dir;
    String title = "Untitled song", artist = "", audioFile, audioName;
    long durationMs;
    long updated;
    final Lyrics lyrics = new Lyrics();
    Style style = new Style();
    /** Loudness 0..1 at {@link #ENVELOPE_RATE} values per second; empty until the song is analysed. */
    float[] envelope = new float[0];

    File audio() { return audioFile == null ? null : new File(dir, audioFile); }
    static final int MAX_PHOTOS = 20;

    /** Photos in slideshow order. */
    File[] photos() {
        File[] f = dir.listFiles((d, n) -> n.startsWith("photo_") && n.endsWith(".jpg"));
        if (f == null) return new File[0];
        java.util.Arrays.sort(f, (a, b) -> a.getName().compareTo(b.getName()));
        return f;
    }
    boolean hasPhotos() { return photos().length > 0; }

    /** File for a newly added photo, after the existing ones. */
    File nextPhoto() {
        File[] p = photos();
        int n = 0;
        for (File f : p) { try { n = Math.max(n, Integer.parseInt(f.getName().substring(6, 9)) + 1); } catch (RuntimeException ignored) {} }
        return new File(dir, String.format(java.util.Locale.ROOT, "photo_%03d.jpg", n));
    }

    /** Renames the photos so they play in the given order. */
    void orderPhotos(java.util.List<File> order) throws IOException {
        ArrayList<File> temp = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            File t = new File(dir, "reorder_" + i + ".tmp");
            if (!order.get(i).renameTo(t)) throw new IOException("Cannot reorder photos.");
            temp.add(t);
        }
        for (int i = 0; i < temp.size(); i++)
            if (!temp.get(i).renameTo(new File(dir, String.format(java.util.Locale.ROOT, "photo_%03d.jpg", i)))) throw new IOException("Cannot reorder photos.");
    }
    File font() { return new File(dir, "font.ttf"); }
    /** Square cover photo shown on top of the background. */
    File cover() { return new File(dir, "cover.jpg"); }
    boolean hasCover() { return cover().isFile(); }
    /** Background video (any container Android plays), stored as background-video.&lt;ext&gt;. */
    File video() {
        File[] f = dir.listFiles((d, n) -> n.startsWith("background-video."));
        return f != null && f.length > 0 ? f[0] : null;
    }
    boolean hasVideo() { File v = video(); return v != null && v.isFile(); }
    boolean hasAudio() { File a = audio(); return a != null && a.isFile() && durationMs > 0; }

    static File root(File filesDir) { File f = new File(filesDir, "projects"); f.mkdirs(); return f; }

    static Project create(File filesDir) throws IOException {
        Project p = new Project();
        p.dir = new File(root(filesDir), UUID.randomUUID().toString());
        if (!p.dir.mkdirs()) throw new IOException("Cannot create project storage.");
        p.save();
        return p;
    }

    static ArrayList<Project> list(File filesDir) {
        ArrayList<Project> out = new ArrayList<>();
        File[] dirs = root(filesDir).listFiles(File::isDirectory);
        if (dirs != null) for (File d : dirs) { try { out.add(load(d)); } catch (Exception ignored) {} }
        out.sort((a, b) -> Long.compare(b.updated, a.updated));
        return out;
    }

    void save() throws IOException {
        updated = System.currentTimeMillis();
        try {
            JSONObject o = new JSONObject();
            o.put("version", 2).put("title", title).put("artist", artist).put("durationMs", durationMs).put("updated", updated);
            if (audioFile != null) o.put("audioFile", audioFile);
            if (audioName != null) o.put("audioName", audioName);
            JSONArray ls = new JSONArray();
            for (Lyrics.Line l : lyrics.lines) {
                JSONObject j = new JSONObject().put("text", l.text).put("start", l.start);
                if (l.wordTimed()) { JSONArray w = new JSONArray(); for (long t : l.wordTimes) w.put(t); j.put("words", w); }
                ls.put(j);
            }
            o.put("lines", ls);
            Style s = style;
            o.put("style", new JSONObject().put("aspect", s.aspect).put("animation", s.animation).put("fontName", Style.FONT_NAMES[s.font]).put("background", s.background)
                    .put("palette", s.palette).put("position", s.position).put("quality", s.quality).put("solid", s.solid).put("textColor", s.textColor)
                    .put("highlight", s.highlight).put("textSize", (double) s.textSize).put("dim", (double) s.dim).put("visualizer", s.visualizer)
                    .put("titleCard", s.titleCard).put("shadow", s.shadow).put("uppercase", s.uppercase).put("kenBurns", s.kenBurns)
                    .put("progressBar", s.progressBar).put("fps", s.fps).put("letterSpacing", (double) s.letterSpacing).put("outline", s.outline)
                    .put("glow", s.glow).put("box", s.box).put("letterbox", s.letterbox).put("photoFit", s.photoFit).put("photoFilter", s.photoFilter)
                    .put("photoSeconds", s.photoSeconds).put("photoBlur", (double) s.photoBlur).put("cover", s.cover).put("coverPos", s.coverPos)
                    .put("coverShape", s.coverShape).put("coverSize", (double) s.coverSize).put("coverPulse", s.coverPulse).put("coverCaption", s.coverCaption).put("videoBlur", (double) s.videoBlur).put("videoFit", s.videoFit));
            File tmp = new File(dir, "project.json.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) { out.write(o.toString(1).getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(new File(dir, "project.json"))) throw new IOException("Cannot save the project.");
        } catch (org.json.JSONException e) { throw new IOException(e.getMessage()); }
    }

    static Project load(File dir) throws IOException {
        Project p = new Project();
        p.dir = dir;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(new File(dir, "project.json").toPath());
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            p.title = o.optString("title", p.title); p.artist = o.optString("artist", "");
            p.audioFile = o.has("audioFile") ? o.getString("audioFile") : null; p.audioName = o.has("audioName") ? o.getString("audioName") : null;
            p.durationMs = o.optLong("durationMs", 0); p.updated = o.optLong("updated", dir.lastModified());
            JSONArray ls = o.optJSONArray("lines");
            if (ls != null) for (int i = 0; i < ls.length(); i++) {
                JSONObject l = ls.getJSONObject(i);
                Lyrics.Line line = new Lyrics.Line(l.optString("text", ""), l.optLong("start", -1));
                JSONArray w = l.optJSONArray("words");
                if (w != null) { line.wordTimes = new long[w.length()]; for (int k = 0; k < w.length(); k++) line.wordTimes[k] = w.optLong(k, -1); }
                p.lyrics.lines.add(line);
            }
            p.lyrics.repairOrder();
            JSONObject s = o.optJSONObject("style");
            if (s != null) {
                Style d = p.style;
                d.aspect = s.optInt("aspect", d.aspect); d.animation = s.optInt("animation", d.animation);
                int named = Style.fontIndex(s.optString("fontName", ""));
                if (named >= 0) d.font = named;
                else { int old = s.optInt("font", 0); d.font = Style.OLD_FONTS[Math.max(0, Math.min(Style.OLD_FONTS.length - 1, old))]; }
                d.background = s.optInt("background", d.background); d.palette = s.optInt("palette", d.palette); d.position = s.optInt("position", d.position);
                d.quality = s.optInt("quality", d.quality); d.solid = s.optInt("solid", d.solid); d.textColor = s.optInt("textColor", d.textColor);
                d.highlight = s.optInt("highlight", d.highlight); d.textSize = (float) s.optDouble("textSize", d.textSize); d.dim = (float) s.optDouble("dim", d.dim);
                d.visualizer = s.optBoolean("visualizer", d.visualizer); d.titleCard = s.optBoolean("titleCard", d.titleCard); d.shadow = s.optBoolean("shadow", d.shadow);
                d.uppercase = s.optBoolean("uppercase", d.uppercase); d.kenBurns = s.optBoolean("kenBurns", d.kenBurns); d.progressBar = s.optBoolean("progressBar", d.progressBar);
                d.fps = s.optInt("fps", d.fps);
                d.letterSpacing = (float) s.optDouble("letterSpacing", d.letterSpacing); d.outline = s.optBoolean("outline", d.outline);
                d.glow = s.optBoolean("glow", d.glow); d.box = s.optBoolean("box", d.box); d.letterbox = s.optBoolean("letterbox", d.letterbox);
                d.photoFit = s.optInt("photoFit", d.photoFit); d.photoFilter = s.optInt("photoFilter", d.photoFilter); d.photoSeconds = s.optInt("photoSeconds", d.photoSeconds);
                d.photoBlur = (float) s.optDouble("photoBlur", d.photoBlur); d.cover = s.optBoolean("cover", d.cover); d.coverPos = s.optInt("coverPos", d.coverPos);
                d.coverShape = s.optInt("coverShape", d.coverShape); d.coverSize = (float) s.optDouble("coverSize", d.coverSize);
                d.coverPulse = s.optBoolean("coverPulse", d.coverPulse); d.coverCaption = s.optBoolean("coverCaption", d.coverCaption);
                d.videoBlur = (float) s.optDouble("videoBlur", d.videoBlur); d.videoFit = s.optInt("videoFit", d.videoFit);
                d.sanitise();
            }
        } catch (org.json.JSONException e) { throw new IOException("Damaged project file: " + e.getMessage()); }
        p.envelope = readEnvelope(new File(dir, "envelope.bin"));
        // Version 0.1 kept a single background.jpg: it becomes the first photo.
        File old = new File(dir, "background.jpg");
        if (old.isFile() && !p.hasPhotos()) old.renameTo(new File(dir, "photo_000.jpg"));
        return p;
    }

    void saveEnvelope(float[] env) throws IOException {
        envelope = env;
        ByteBuffer b = ByteBuffer.allocate(env.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : env) b.putFloat(v);
        try (OutputStream out = new FileOutputStream(new File(dir, "envelope.bin"))) { out.write(b.array()); }
    }

    static float[] readEnvelope(File f) {
        if (!f.isFile()) return new float[0];
        try {
            ByteBuffer b = ByteBuffer.wrap(java.nio.file.Files.readAllBytes(f.toPath())).order(ByteOrder.LITTLE_ENDIAN);
            float[] env = new float[b.remaining() / 4];
            for (int i = 0; i < env.length; i++) env[i] = b.getFloat();
            return env;
        } catch (IOException e) { return new float[0]; }
    }

    /** Loudness 0..1 at time t (linear interpolation). */
    float level(long t) {
        float[] e = envelope;
        if (e.length == 0 || t < 0) return 0;
        float pos = t * ENVELOPE_RATE / 1000f;
        int i = (int) pos;
        if (i >= e.length - 1) return i < e.length ? e[i] : 0;
        float f = pos - i;
        return e[i] * (1 - f) + e[i + 1] * f;
    }

    void delete() {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}

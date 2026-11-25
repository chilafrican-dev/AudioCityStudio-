package ug.audiocity.lyrics;

import android.content.Context;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CancellationException;

/**
 * Downloads the Luganda speech model once (from this project's GitHub release, built by the
 * "Luganda speech model" workflow) and keeps it in the app's private storage.
 */
final class ModelStore {
    static final String RELEASE = "https://github.com/chilafrican-dev/AudioCityStudio-/releases/download/luganda-aligner-v1/";
    static final String MODEL = "aligner_int8.onnx", CONFIG = "aligner.json", LICENCE = "MODEL_LICENSE.md";

    static File dir(Context c) { File d = new File(c.getFilesDir(), "models/luganda-v1"); d.mkdirs(); return d; }

    static boolean ready(Context c) {
        File d = dir(c);
        return new File(d, CONFIG).isFile() && new File(d, MODEL).length() > 50_000_000L;
    }

    static long sizeOnDisk(Context c) { File d = dir(c); return new File(d, MODEL).length() + new File(d, CONFIG).length(); }

    static void delete(Context c) { File[] f = dir(c).listFiles(); if (f != null) for (File x : f) x.delete(); }

    static void download(Context c, VideoExporter.Listener progress) throws IOException {
        File d = dir(c);
        fetch(RELEASE + CONFIG, new File(d, CONFIG), progress, 0, .01f);
        fetch(RELEASE + LICENCE, new File(d, LICENCE), progress, .01f, .02f);
        fetch(RELEASE + MODEL, new File(d, MODEL), progress, .02f, 1f);
    }

    private static void fetch(String url, File to, VideoExporter.Listener progress, float from, float until) throws IOException {
        File part = new File(to.getPath() + ".part");
        HttpURLConnection http = null;
        try {
            URL u = new URL(url);
            for (int hop = 0; hop < 6; hop++) { // GitHub release files redirect to a download host
                http = (HttpURLConnection) u.openConnection();
                http.setInstanceFollowRedirects(false);
                http.setConnectTimeout(30_000);
                http.setReadTimeout(60_000);
                http.setRequestProperty("User-Agent", "LyricVideoStudio");
                int code = http.getResponseCode();
                if (code / 100 == 3) { u = new URL(u, http.getHeaderField("Location")); http.disconnect(); continue; }
                if (code == 404) throw new IOException("The Luganda speech model is not published yet. Try again later.");
                if (code != 200) throw new IOException("Download failed (HTTP " + code + ").");
                break;
            }
            long total = http.getContentLengthLong(), done = 0;
            try (InputStream in = new BufferedInputStream(http.getInputStream()); OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
                byte[] buf = new byte[1 << 16];
                int n;
                long lastReport = 0;
                while ((n = in.read(buf)) != -1) {
                    if (progress.cancelled()) throw new CancellationException();
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0 && done - lastReport > 1_000_000) {
                        lastReport = done;
                        progress.progress(from + (until - from) * done / total, String.format(java.util.Locale.US, "Downloading the Luganda model… %.0f of %.0f MB", done / 1e6, total / 1e6));
                    }
                }
            }
            if (total > 0 && done != total) throw new IOException("The download was interrupted. Try again.");
            if (!part.renameTo(to)) throw new IOException("Cannot save the model.");
        } finally {
            if (http != null) http.disconnect();
            part.delete();
        }
    }
}

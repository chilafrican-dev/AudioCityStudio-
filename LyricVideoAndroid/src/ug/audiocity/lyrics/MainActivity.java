package ug.audiocity.lyrics;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Audio City Lyrics: make lyric videos on the phone. Song → lyrics → tap-to-sync → style → MP4. */
public class MainActivity extends Activity {
    static final int BG = 0xff111116, PANEL = 0xff1d1d26, PANEL2 = 0xff272733, LINE = 0xff3a3a48, TEXT = 0xfff2f2f5, DIM = 0xff9b9bab, ACCENT = 0xffffd166;
    static final int REQ_AUDIO = 1, REQ_IMAGE = 2, REQ_LYRICS = 3, REQ_FONT = 4, REQ_SAVE_LRC = 5;
    static final String[] TABS = {"Song", "Lyrics", "Sync", "Style"};

    final Handler handler = new Handler(Looper.getMainLooper());
    final ExecutorService jobs = Executors.newSingleThreadExecutor();
    final LyricRenderer renderer = new LyricRenderer();

    Project project;
    MediaPlayer player;
    boolean prepared, playing, dead, busy;
    long scrub;
    int tab;
    Bitmap previewPhoto;
    Typeface projectFont;

    FrameLayout content;
    PreviewView preview;
    SeekBar seek;
    TextView clock;
    Button playButton;
    Button[] tabButtons;

    // Sync tab state, refreshed by the ticker.
    Button tapButton;
    TextView syncStatus;
    final ArrayList<TextView> syncRows = new ArrayList<>();
    int highlightedRow = -2;
    EditText lyricsBox;
    boolean settingLyricsText;

    SharedPreferences prefs() { return getSharedPreferences("lyrics", MODE_PRIVATE); }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        String last = prefs().getString("open", null);
        if (last != null && new File(last, "project.json").isFile()) {
            try { open(Project.load(new File(last))); return; } catch (IOException ignored) {}
        }
        home();
    }

    // ================================================================== small UI helpers

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    GradientDrawable round(int color, float radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g; }

    TextView label(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    TextView heading(String s) { TextView t = label(s.toUpperCase(Locale.ROOT), 11, DIM, true); t.setLetterSpacing(.12f); t.setPadding(0, dp(16), 0, dp(6)); return t; }

    Button button(String s, Runnable r) { return button(s, r, PANEL2, TEXT); }
    Button button(String s, Runnable r, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextColor(fg); b.setTextSize(14);
        b.setBackground(round(bg, 12)); b.setStateListAnimator(null);
        b.setPadding(dp(12), 0, dp(12), 0); b.setMinHeight(dp(44)); b.setMinimumHeight(dp(44));
        b.setOnClickListener(v -> { try { r.run(); } catch (RuntimeException e) { error("Lyric video", e.getMessage()); } });
        return b;
    }

    LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
            if (i > 0) p.leftMargin = dp(8);
            r.addView(views[i], p);
        }
        r.setPadding(0, dp(4), 0, dp(4));
        return r;
    }

    LinearLayout card() { LinearLayout c = column(); c.setBackground(round(PANEL, 16)); c.setPadding(dp(14), dp(12), dp(14), dp(14)); return c; }

    void addCard(LinearLayout page, View card) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(10); page.addView(card, p); }

    EditText field(String text, String hint) {
        EditText e = new EditText(this);
        e.setText(text); e.setHint(hint); e.setTextColor(TEXT); e.setHintTextColor(0xff6c6c7c); e.setTextSize(16);
        e.setBackground(round(PANEL2, 10)); e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        return e;
    }

    interface Text { void changed(String s); }
    static TextWatcher watcher(Text t) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { t.changed(e.toString()); }
        };
    }

    interface Pick { void picked(int i); }
    Spinner spinner(String[] items, int selected, Pick pick) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int pos, View v, ViewGroup parent) { TextView t = (TextView) super.getView(pos, v, parent); t.setTextColor(TEXT); t.setTextSize(15); return t; }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setBackground(round(PANEL2, 10));
        final int[] last = {Math.max(0, Math.min(items.length - 1, selected))};
        s.setSelection(last[0], false);
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            // Android also calls this once when the spinner is first laid out: only react to real changes.
            public void onItemSelected(AdapterView<?> p, View v, int i, long id) { if (i == last[0]) return; last[0] = i; pick.picked(i); }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        return s;
    }

    interface Check { void set(boolean b); }
    Switch toggle(String text, boolean on, Check c) {
        Switch s = new Switch(this);
        s.setText(text); s.setTextColor(TEXT); s.setTextSize(15); s.setChecked(on);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setOnCheckedChangeListener((v, b) -> c.set(b));
        return s;
    }

    interface Slide { void set(float v); }
    SeekBar slider(float min, float max, float value, Slide s) {
        SeekBar b = new SeekBar(this);
        b.setMax(1000);
        b.setProgress(Math.round((value - min) / (max - min) * 1000));
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int p, boolean user) { if (user) s.set(min + (max - min) * p / 1000f); }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        return b;
    }

    interface Colour { void set(int c); }
    HorizontalScrollView swatches(int current, Colour pick) {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(this);
        sv.addView(r);
        ArrayList<View> all = new ArrayList<>();
        for (int c : Style.SWATCHES) {
            View v = new View(this);
            GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(c); g.setStroke(dp(c == current ? 3 : 1), c == current ? ACCENT : LINE);
            v.setBackground(g);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(38), dp(38)); p.rightMargin = dp(10);
            r.addView(v, p);
            all.add(v);
            v.setOnClickListener(x -> {
                for (int i = 0; i < all.size(); i++) { GradientDrawable d = (GradientDrawable) all.get(i).getBackground(); boolean on = all.get(i) == x; d.setStroke(dp(on ? 3 : 1), on ? ACCENT : LINE); }
                pick.set(c);
            });
        }
        sv.setPadding(0, dp(4), 0, dp(4));
        return sv;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    void error(String title, String message) { if (dead) return; new AlertDialog.Builder(this).setTitle(title).setMessage(message == null ? "Something went wrong." : message).setPositiveButton("OK", null).show(); }

    /** Android 15 draws apps edge to edge: pad for the system bars and the keyboard. Older versions already do this. */
    void insets(View root) {
        if (Build.VERSION.SDK_INT < 35) return;
        root.setOnApplyWindowInsetsListener((v, in) -> {
            Insets x = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(x.left, x.top, x.right, x.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    interface Job { void run() throws Exception; }
    /** Runs work off the UI thread with a simple progress dialog. */
    void work(String message, Job job, Runnable done) { work(message, job, done, null); }
    void work(String message, Job job, Runnable done, Runnable failed) {
        if (busy) return;
        busy = true;
        AlertDialog d = new AlertDialog.Builder(this).setMessage(message).setCancelable(false).create();
        d.show();
        jobs.execute(() -> {
            Throwable fail = null;
            try { job.run(); } catch (Throwable e) { fail = e; }
            final Throwable f = fail;
            runOnUiThread(() -> {
                busy = false;
                try { d.dismiss(); } catch (Exception ignored) {}
                if (f != null) { error("Lyric video", f instanceof OutOfMemoryError ? "Not enough memory on this phone for that file." : f.getMessage()); if (failed != null) failed.run(); }
                else if (done != null) done.run();
            });
        });
    }

    // ================================================================== home

    void home() {
        releasePlayer();
        project = null;
        prefs().edit().remove("open").apply();
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        insets(root);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));

        TextView brand = label("AUDIO CITY", 11, ACCENT, true); brand.setLetterSpacing(.25f); page.addView(brand);
        page.addView(label("Lyric Video Studio", 28, TEXT, true));
        page.addView(label("Turn any song into a lyric video: add the words, tap along to sync them, choose a look and export an MP4 for Reels, TikTok, Shorts, Status or YouTube.", 14, DIM, false));
        Button create = button("+  New lyric video", this::newProject, ACCENT, 0xff1a1a1a);
        create.setTextSize(17);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(56)); cp.topMargin = dp(18);
        page.addView(create, cp);

        ArrayList<Project> list = Project.list(getFilesDir());
        page.addView(heading(list.isEmpty() ? "No projects yet" : "Your projects"));
        for (Project p : list) {
            LinearLayout c = card();
            c.addView(label(p.title, 17, TEXT, true));
            String info = (p.artist.isEmpty() ? "" : p.artist + "  ·  ") + (p.hasAudio() ? clock(p.durationMs) : "no song") + "  ·  " + p.lyrics.lines.size() + " lines, " + p.lyrics.syncedCount() + " synced  ·  " + Style.ASPECTS[p.style.aspect].split(" ")[0];
            c.addView(label(info, 13, DIM, false));
            c.setOnClickListener(v -> open(p));
            c.setOnLongClickListener(v -> { projectMenu(p); return true; });
            addCard(page, c);
        }
        if (!list.isEmpty()) page.addView(label("Long-press a project to rename, duplicate or delete it.", 12, DIM, false));
        setContentView(root);
    }

    void newProject() {
        try { open(Project.create(getFilesDir())); tab(0); toast("Start by choosing the song."); }
        catch (IOException e) { error("New project", e.getMessage()); }
    }

    void projectMenu(Project p) {
        new AlertDialog.Builder(this).setTitle(p.title).setItems(new String[]{"Open", "Rename", "Duplicate", "Delete"}, (d, i) -> {
            if (i == 0) open(p);
            else if (i == 1) input("Song title", p.title, s -> { p.title = s; try { p.save(); } catch (IOException ignored) {} home(); });
            else if (i == 2) work("Duplicating…", () -> {
                Project c = Project.create(getFilesDir());
                File[] files = p.dir.listFiles();
                if (files != null) for (File f : files) if (!f.getName().equals("project.json")) copy(new FileInputStream(f), new File(c.dir, f.getName()));
                Project loaded = Project.load(p.dir);
                loaded.dir = c.dir; loaded.title = p.title + " (copy)";
                loaded.save();
            }, this::home);
            else new AlertDialog.Builder(this).setTitle("Delete “" + p.title + "”?").setMessage("The project and its copied song are removed from this phone. Exported videos are kept.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete", (x, y) -> { p.delete(); home(); }).show();
        }).show();
    }

    interface Input { void done(String s); }
    void input(String title, String initial, Input done) {
        EditText e = field(initial, "");
        FrameLayout box = new FrameLayout(this); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(e);
        new AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (d, w) -> { String s = e.getText().toString().trim(); if (!s.isEmpty()) done.done(s); }).show();
    }

    // ================================================================== editor

    void open(Project p) {
        releasePlayer();
        project = p;
        prefs().edit().putString("open", p.dir.getAbsolutePath()).apply();
        scrub = 0;
        loadAssets();
        buildEditor();
        loadPlayer();
    }

    void loadAssets() {
        if (previewPhoto != null) previewPhoto.recycle();
        previewPhoto = decodePhoto(project.image(), 1280);
        projectFont = LyricRenderer.loadFont(project.font());
        renderer.photo = previewPhoto;
        renderer.custom = projectFont;
        renderer.bind(project);
    }

    static Bitmap decodePhoto(File f, int maxSide) {
        if (!f.isFile()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
    }

    void buildEditor() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        insets(root);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(10), dp(6));
        Button back = button("‹", this::home, BG, TEXT); back.setTextSize(26);
        bar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView title = label(project.title, 18, TEXT, true);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(4), 0, dp(8), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button export = button("Export video", this::exportDialog, ACCENT, 0xff1a1a1a);
        bar.addView(export, new LinearLayout.LayoutParams(-2, dp(42)));
        root.addView(bar);

        preview = new PreviewView();
        int h = Math.round(getResources().getDisplayMetrics().heightPixels * .36f);
        root.addView(preview, new LinearLayout.LayoutParams(-1, h));

        LinearLayout transport = new LinearLayout(this);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        transport.setPadding(dp(12), dp(4), dp(12), dp(2));
        playButton = button("▶", this::togglePlay, PANEL2, TEXT); playButton.setTextSize(18);
        transport.addView(playButton, new LinearLayout.LayoutParams(dp(54), dp(44)));
        seek = new SeekBar(this);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) seekTo(p); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        transport.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));
        clock = label("0:00 / 0:00", 13, DIM, false);
        clock.setTypeface(Typeface.MONOSPACE);
        transport.addView(clock);
        root.addView(transport);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(12), dp(4), dp(12), dp(4));
        tabButtons = new Button[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            final int k = i;
            tabButtons[i] = button(TABS[i], () -> tab(k));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1);
            if (i > 0) p.leftMargin = dp(6);
            tabs.addView(tabButtons[i], p);
        }
        root.addView(tabs);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        tab(project.hasAudio() ? (project.lyrics.lines.isEmpty() ? 1 : project.lyrics.fullySynced() ? 3 : 2) : 0);
        updateTransport();
    }

    void tab(int k) {
        if (content == null) return;
        applyLyricsNow();
        tab = k;
        for (int i = 0; i < tabButtons.length; i++) {
            tabButtons[i].setBackground(round(i == k ? ACCENT : PANEL2, 12));
            tabButtons[i].setTextColor(i == k ? 0xff1a1a1a : TEXT);
        }
        content.removeAllViews();
        syncRows.clear(); tapButton = null; syncStatus = null; lyricsBox = null; highlightedRow = -2;
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = column();
        page.setPadding(dp(14), 0, dp(14), dp(40));
        scroll.addView(page);
        content.addView(scroll);
        switch (k) {
            case 0: songTab(page); break;
            case 1: lyricsTab(page); break;
            case 2: syncTab(page); break;
            default: styleTab(page); break;
        }
    }

    void changed() {
        renderer.bind(project);
        if (preview != null) preview.invalidate();
        handler.removeCallbacks(saveLater);
        handler.postDelayed(saveLater, 700);
    }
    final Runnable saveLater = () -> { if (project != null) try { project.save(); } catch (IOException e) { toast("Could not save: " + e.getMessage()); } };

    // ------------------------------------------------------------------ Song tab

    void songTab(LinearLayout page) {
        LinearLayout song = card();
        song.addView(label("Song", 13, DIM, true));
        song.addView(label(project.hasAudio() ? project.audioName + "  ·  " + clock(project.durationMs) : "No song chosen yet", 17, TEXT, true));
        song.addView(label("MP3, M4A/AAC, WAV, FLAC, OGG and Opus work. The file is copied into the project, so it keeps working if the original moves.", 12, DIM, false));
        song.addView(row(button(project.hasAudio() ? "Replace song…" : "Choose song…", () -> pick("audio/*", REQ_AUDIO), project.hasAudio() ? PANEL2 : ACCENT, project.hasAudio() ? TEXT : 0xff1a1a1a)));
        addCard(page, song);

        LinearLayout info = card();
        info.addView(label("Title card", 13, DIM, true));
        EditText title = field(project.title, "Song title");
        title.addTextChangedListener(watcher(s -> { project.title = s.trim().isEmpty() ? "Untitled song" : s.trim(); changed(); }));
        EditText artist = field(project.artist, "Artist (optional)");
        artist.addTextChangedListener(watcher(s -> { project.artist = s.trim(); changed(); }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8);
        info.addView(title, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, -2); lp2.topMargin = dp(8);
        info.addView(artist, lp2);
        info.addView(label("Shown before the first lyric (turn it off in Style).", 12, DIM, false));
        addCard(page, info);

        LinearLayout photo = card();
        photo.addView(label("Background photo", 13, DIM, true));
        boolean has = project.image().isFile();
        photo.addView(label(has ? "Photo added. It slowly zooms during the song (Ken Burns), darkened for readable lyrics." : "Optional: cover art or any picture. Without one, an animated gradient is used.", 12, DIM, false));
        if (has) photo.addView(row(button("Change photo…", () -> pick("image/*", REQ_IMAGE)), button("Remove", () -> {
            project.image().delete(); if (project.style.background == 2) project.style.background = 0; loadAssets(); changed(); tab(0);
        })));
        else photo.addView(row(button("Choose photo…", () -> pick("image/*", REQ_IMAGE))));
        addCard(page, photo);
    }

    void pick(String type, int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        if (request == REQ_LYRICS) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/octet-stream"});
        if (request == REQ_FONT) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"font/*", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream"});
        try { startActivityForResult(i, request); } catch (Exception e) { error("Choose file", "No file picker is available: " + e.getMessage()); }
    }

    String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) {}
        String p = uri.getLastPathSegment();
        return p == null ? "song" : p;
    }

    static void copy(InputStream in, File to) throws IOException {
        if (in == null) throw new IOException("Cannot read the chosen file.");
        try (InputStream i = in; OutputStream o = new BufferedOutputStream(new FileOutputStream(to))) {
            byte[] b = new byte[65536]; int n;
            while ((n = i.read(b)) != -1) o.write(b, 0, n);
        }
    }

    static String readText(InputStream in) throws IOException {
        if (in == null) throw new IOException("Cannot read the chosen file.");
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[16384]; int n; long total = 0;
            while ((n = i.read(b)) != -1) { o.write(b, 0, n); if ((total += n) > 2_000_000) throw new IOException("That text file is too large for lyrics."); }
            String s = new String(o.toByteArray(), StandardCharsets.UTF_8);
            return s.startsWith("﻿") ? s.substring(1) : s;
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || project == null) return;
        Uri uri = data.getData();
        final Project p = project;
        if (request == REQ_AUDIO) {
            String name = displayName(uri);
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") : "";
            if (ext.isEmpty() || ext.length() > 5) ext = "audio";
            final String file = "song." + ext, incoming = "incoming." + ext;
            releasePlayer();
            final long[] dur = {0};
            final float[][] env = {null};
            work("Copying and analysing the song…", () -> {
                File tmp = new File(p.dir, incoming);
                copy(getContentResolver().openInputStream(uri), tmp);
                env[0] = PcmDecoder.envelope(tmp, dur, null);
                if (dur[0] <= 0) dur[0] = PcmDecoder.duration(tmp);
                if (dur[0] <= 0) { tmp.delete(); throw new IOException("This file has no playable audio."); }
                if (p.audioFile != null && !p.audioFile.equals(file)) new File(p.dir, p.audioFile).delete();
                if (!tmp.renameTo(new File(p.dir, file))) throw new IOException("Cannot store the song.");
            }, () -> {
                p.audioFile = file; p.audioName = name; p.durationMs = dur[0];
                if (p.title.equals("Untitled song")) p.title = name.replaceAll("\\.[A-Za-z0-9]{1,5}$", "").replace('_', ' ').trim();
                try { p.saveEnvelope(env[0]); p.save(); } catch (IOException e) { toast(e.getMessage()); }
                scrub = 0;
                renderer.bind(p);
                buildEditor();
                loadPlayer();
                tab(p.lyrics.lines.isEmpty() ? 1 : 0);
                toast("Song ready (" + clock(p.durationMs) + "). Now add the lyrics.");
            }, () -> { if (project == p) loadPlayer(); }); // the previous song, if any, is untouched
        } else if (request == REQ_IMAGE) {
            work("Preparing the photo…", () -> {
                File tmp = new File(p.dir, "incoming-image");
                copy(getContentResolver().openInputStream(uri), tmp);
                Bitmap b = decodePhoto(tmp, 2160);
                tmp.delete();
                if (b == null) throw new IOException("That picture could not be opened.");
                try (OutputStream o = new FileOutputStream(p.image())) { b.compress(Bitmap.CompressFormat.JPEG, 92, o); }
                b.recycle();
            }, () -> { p.style.background = 2; loadAssets(); changed(); tab(tab); });
        } else if (request == REQ_LYRICS) {
            final String[] text = {null};
            work("Reading lyrics…", () -> text[0] = readText(getContentResolver().openInputStream(uri)), () -> {
                String[] meta = {null, null};
                Lyrics l = Lyrics.parse(text[0], meta);
                if (l.lines.isEmpty()) { toast("No lyric lines were found in that file."); return; }
                p.lyrics.lines.clear(); p.lyrics.lines.addAll(l.lines);
                if (meta[0] != null && !meta[0].isEmpty() && p.title.equals("Untitled song")) p.title = meta[0];
                if (meta[1] != null && !meta[1].isEmpty() && p.artist.isEmpty()) p.artist = meta[1];
                changed();
                tab(l.syncedCount() > 0 ? 3 : 2);
                toast(l.lines.size() + " lines imported" + (l.syncedCount() > 0 ? ", " + l.syncedCount() + " with timings." : ". Next: sync them."));
            });
        } else if (request == REQ_FONT) {
            work("Installing font…", () -> {
                File tmp = new File(p.dir, "incoming-font");
                copy(getContentResolver().openInputStream(uri), tmp);
                Typeface t = LyricRenderer.loadFont(tmp);
                if (t == null) { tmp.delete(); throw new IOException("That is not a TrueType or OpenType font."); }
                if (!tmp.renameTo(p.font())) throw new IOException("Cannot store the font.");
            }, () -> { p.style.font = 6; loadAssets(); changed(); tab(3); });
        } else if (request == REQ_SAVE_LRC) {
            String lrc = p.lyrics.toLrc(p.title, p.artist);
            work("Saving LRC…", () -> {
                try (OutputStream o = getContentResolver().openOutputStream(uri, "wt")) { if (o == null) throw new IOException("Cannot write there."); o.write(lrc.getBytes(StandardCharsets.UTF_8)); }
            }, () -> toast("Synced lyrics saved as LRC."));
        }
    }

    // ------------------------------------------------------------------ Lyrics tab

    final Runnable applyLyrics = this::applyLyricsNow;

    void applyLyricsNow() {
        handler.removeCallbacks(applyLyrics);
        if (lyricsBox == null || project == null) return;
        String text = lyricsBox.getText().toString();
        if (text.equals(project.lyrics.toText())) return;
        if (Lyrics.looksLikeLrc(text)) {
            String[] meta = {null, null};
            Lyrics l = Lyrics.parseLrc(text, meta);
            project.lyrics.lines.clear(); project.lyrics.lines.addAll(l.lines);
            if (meta[1] != null && project.artist.isEmpty()) project.artist = meta[1];
            settingLyricsText = true; lyricsBox.setText(project.lyrics.toText()); settingLyricsText = false;
            toast("LRC timings kept for " + l.syncedCount() + " lines.");
        } else project.lyrics.setText(text);
        changed();
    }

    void lyricsTab(LinearLayout page) {
        page.addView(heading("Lyrics · one line per row"));
        lyricsBox = new EditText(this);
        lyricsBox.setText(project.lyrics.toText());
        lyricsBox.setHint("Paste or type the lyrics here.\n\nEach row becomes one line on screen. Keep rows short (a phrase each) for the best look.\n\nPasting an LRC file keeps its timings.");
        lyricsBox.setHintTextColor(0xff6c6c7c);
        lyricsBox.setTextColor(TEXT);
        lyricsBox.setTextSize(16);
        lyricsBox.setGravity(Gravity.TOP);
        lyricsBox.setMinLines(12);
        lyricsBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        lyricsBox.setBackground(round(PANEL, 14));
        lyricsBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        lyricsBox.addTextChangedListener(watcher(s -> { if (!settingLyricsText) { handler.removeCallbacks(applyLyrics); handler.postDelayed(applyLyrics, 900); } }));
        page.addView(lyricsBox, new LinearLayout.LayoutParams(-1, -2));
        page.addView(label("Editing a line keeps the timings of the others.", 12, DIM, false));
        page.addView(row(button("Import .lrc / .txt", () -> pick("*/*", REQ_LYRICS)), button("Save as .lrc", this::saveLrc)));
        page.addView(row(button("Next: sync ▸", () -> tab(2), ACCENT, 0xff1a1a1a)));
    }

    void saveLrc() {
        applyLyricsNow();
        if (project.lyrics.lines.isEmpty()) { toast("Add lyrics first."); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, safeName(project.title) + ".lrc");
        try { startActivityForResult(i, REQ_SAVE_LRC); } catch (Exception e) { error("Save LRC", e.getMessage()); }
    }

    static String safeName(String s) { String n = s.replaceAll("[\\\\/:*?\"<>|]", "").trim(); return n.isEmpty() ? "Lyric video" : n; }

    // ------------------------------------------------------------------ Sync tab

    long tapOffset() { return prefs().getInt("tapOffset", 120); }

    void syncTab(LinearLayout page) {
        Lyrics l = project.lyrics;
        if (!project.hasAudio() || l.lines.isEmpty()) {
            LinearLayout c = card();
            c.addView(label(!project.hasAudio() ? "Choose a song first (Song tab)." : "Add lyrics first (Lyrics tab).", 16, TEXT, true));
            addCard(page, c);
            return;
        }
        syncStatus = label("", 13, DIM, false);
        syncStatus.setPadding(0, dp(10), 0, dp(4));
        page.addView(syncStatus);
        page.addView(row(button("⟲  Start over", this::syncFromStart), button("▶  Continue", this::syncContinue)));
        tapButton = button("", this::tap, ACCENT, 0xff1a1a1a);
        tapButton.setTextSize(18);
        tapButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(118)); tp.topMargin = dp(6);
        page.addView(tapButton, tp);
        page.addView(row(button("Undo last tap", this::undoTap), button("Shift all…", this::shiftAll)));
        TextView offset = label("", 12, DIM, false);
        Runnable showOffset = () -> offset.setText("Reaction compensation: taps count " + tapOffset() + " ms earlier.");
        showOffset.run();
        page.addView(offset);
        page.addView(slider(0, 400, tapOffset(), v -> { prefs().edit().putInt("tapOffset", Math.round(v / 10) * 10).apply(); showOffset.run(); }));
        page.addView(heading("Lines · tap one to fine-tune"));
        for (int i = 0; i < l.lines.size(); i++) {
            final int k = i;
            TextView r = label("", 15, TEXT, false);
            r.setPadding(dp(12), dp(10), dp(12), dp(10));
            r.setBackground(round(PANEL, 10));
            r.setOnClickListener(v -> editLine(k));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(4);
            page.addView(r, p);
            syncRows.add(r);
        }
        refreshSync();
    }

    void refreshSync() {
        if (syncStatus == null) return;
        Lyrics l = project.lyrics;
        int next = l.nextUnsynced();
        syncStatus.setText(l.syncedCount() + " of " + l.lines.size() + " lines synced. Play the song and tap the big button the moment each line starts.");
        tapButton.setText(next < 0 ? "All lines synced ✓\nPlay to check, or tap a line below to adjust" : "TAP when this starts\n“" + l.lines.get(next).text + "”");
        for (int i = 0; i < syncRows.size() && i < l.lines.size(); i++) {
            Lyrics.Line line = l.lines.get(i);
            syncRows.get(i).setText((line.start < 0 ? "  –:––.––" : Lyrics.stamp(line.start)) + "    " + line.text);
            syncRows.get(i).setTextColor(line.start < 0 ? DIM : TEXT);
        }
        highlightedRow = -2;
        tickSync();
    }

    void tickSync() {
        if (syncRows.isEmpty()) return;
        int cur = project.lyrics.lastStarted(now());
        if (cur == highlightedRow) return;
        if (highlightedRow >= 0 && highlightedRow < syncRows.size()) syncRows.get(highlightedRow).setBackground(round(PANEL, 10));
        if (cur >= 0 && cur < syncRows.size()) syncRows.get(cur).setBackground(round(0xff3b3520, 10));
        highlightedRow = cur;
    }

    void syncFromStart() {
        Runnable go = () -> { project.lyrics.clearTimes(); changed(); refreshSync(); seekTo(0); play(); };
        if (project.lyrics.syncedCount() == 0) { go.run(); return; }
        new AlertDialog.Builder(this).setTitle("Start syncing over?").setMessage("This clears all " + project.lyrics.syncedCount() + " timings and plays from the beginning.")
                .setNegativeButton("Cancel", null).setPositiveButton("Start over", (d, w) -> go.run()).show();
    }

    void syncContinue() {
        Lyrics l = project.lyrics;
        int last = -1;
        for (int i = 0; i < l.lines.size(); i++) if (l.lines.get(i).start >= 0) last = i;
        seekTo(last < 0 ? 0 : (int) Math.max(0, l.lines.get(last).start - 2500));
        play();
    }

    void tap() {
        Lyrics l = project.lyrics;
        int next = l.nextUnsynced();
        if (next < 0) { togglePlay(); return; }
        if (!playing) { play(); toast("Playing. Tap when the highlighted line starts."); return; }
        long t = Math.max(0, now() - tapOffset());
        for (int k = next - 1; k >= 0; k--) if (l.lines.get(k).start >= 0) { t = Math.max(t, l.lines.get(k).start + 100); break; }
        for (int k = next + 1; k < l.lines.size(); k++) if (l.lines.get(k).start >= 0) { if (t >= l.lines.get(k).start) { toast("This line would start after the next synced line. Use Start over or edit the times."); return; } break; }
        l.lines.get(next).start = t;
        tapButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        changed();
        refreshSync();
        if (l.nextUnsynced() < 0) toast("Every line is synced. Check it with play, then style and export.");
    }

    void undoTap() {
        Lyrics l = project.lyrics;
        for (int i = l.lines.size() - 1; i >= 0; i--) if (l.lines.get(i).start >= 0) {
            long t = l.lines.get(i).start;
            l.lines.get(i).start = -1;
            changed(); refreshSync();
            if (playing) seekTo((int) Math.max(0, t - 2500));
            return;
        }
        toast("No taps to undo.");
    }

    void shiftAll() {
        input("Move every timing by (seconds, e.g. -0.25)", "0.0", s -> {
            try { double v = Double.parseDouble(s.replace(',', '.')); project.lyrics.shift(Math.round(v * 1000)); changed(); refreshSync(); }
            catch (NumberFormatException e) { toast("Type a number of seconds, like 0.3 or -0.5."); }
        });
    }

    void editLine(int i) {
        Lyrics.Line line = project.lyrics.lines.get(i);
        LinearLayout box = column();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText text = field(line.text, "Line text");
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText time = field(line.start < 0 ? "" : Lyrics.stamp(line.start), "Start (m:ss.cc)");
        box.addView(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8);
        box.addView(time, lp);
        Runnable nudgeMinus = () -> nudge(time, -100), nudgePlus = () -> nudge(time, 100);
        box.addView(row(button("−0.1 s", nudgeMinus), button("+0.1 s", nudgePlus), button("= playhead", () -> time.setText(Lyrics.stamp(now())))));
        box.addView(row(button("▶ Play from here", () -> { long t = Lyrics.parseStamp(time.getText().toString()); seekTo((int) Math.max(0, (t < 0 ? now() : t) - 1500)); play(); }),
                button("Clear time", () -> time.setText(""))));
        AlertDialog[] dialog = {null};
        box.addView(row(button("Delete line", () -> {
            project.lyrics.lines.remove(i); changed(); dialog[0].dismiss(); tab(2);
        }, 0xff4a2028, TEXT), button("Insert line after", () -> {
            project.lyrics.lines.add(i + 1, new Lyrics.Line("New line", -1)); changed(); dialog[0].dismiss(); tab(2); editLine(i + 1);
        })));
        dialog[0] = new AlertDialog.Builder(this).setTitle("Line " + (i + 1)).setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
            String t = Lyrics.clean(text.getText().toString());
            if (!t.isEmpty()) line.text = t;
            String ts = time.getText().toString().trim();
            long st = ts.isEmpty() ? -1 : Lyrics.parseStamp(ts);
            if (!ts.isEmpty() && st < 0) toast("Time not understood; use m:ss.cc, for example 1:23.45.");
            else line.start = st;
            project.lyrics.sortByTime();
            changed();
            refreshSync();
        }).show();
    }

    void nudge(EditText time, long ms) {
        long t = Lyrics.parseStamp(time.getText().toString());
        if (t < 0) t = now();
        time.setText(Lyrics.stamp(Math.max(0, t + ms)));
    }

    // ------------------------------------------------------------------ Style tab

    void styleTab(LinearLayout page) {
        Style s = project.style;
        page.addView(heading("Format"));
        page.addView(spinner(Style.ASPECTS, s.aspect, i -> { s.aspect = i; changed(); }));
        page.addView(heading("Lyric animation"));
        page.addView(spinner(Style.ANIMATIONS, s.animation, i -> { s.animation = i; changed(); }));
        page.addView(heading("Font"));
        page.addView(spinner(Style.FONTS, s.font, i -> {
            if (i == 6 && projectFont == null) { pick("*/*", REQ_FONT); return; }
            s.font = i; changed();
        }));
        page.addView(row(button("Import a font (.ttf / .otf)…", () -> pick("*/*", REQ_FONT))));
        page.addView(heading("Text size"));
        page.addView(slider(.6f, 1.6f, s.textSize, v -> { s.textSize = v; changed(); }));
        page.addView(heading("Position"));
        page.addView(spinner(Style.POSITIONS, s.position, i -> { s.position = i; changed(); }));
        page.addView(heading("Text colour"));
        page.addView(swatches(s.textColor, c -> { s.textColor = c; changed(); }));
        page.addView(heading("Highlight colour (sung words, visualizer)"));
        page.addView(swatches(s.highlight, c -> { s.highlight = c; changed(); }));

        page.addView(heading("Background"));
        page.addView(spinner(Style.BACKGROUNDS, s.background, i -> {
            if (i == 2 && !project.image().isFile()) { pick("image/*", REQ_IMAGE); return; }
            s.background = i; changed();
        }));
        page.addView(label("Gradient colours", 12, DIM, false));
        page.addView(spinner(Style.PALETTE_NAMES, s.palette, i -> { s.palette = i; if (s.background == 1) s.background = 0; changed(); }));
        page.addView(label("Solid colour", 12, DIM, false));
        page.addView(swatches(s.solid, c -> { s.solid = c; s.background = 1; changed(); }));
        page.addView(label("Photo darkening", 12, DIM, false));
        page.addView(slider(0, .85f, s.dim, v -> { s.dim = v; changed(); }));

        page.addView(heading("Extras"));
        page.addView(toggle("Audio visualizer (moves with the music)", s.visualizer, b -> { s.visualizer = b; changed(); }));
        page.addView(toggle("Song progress bar", s.progressBar, b -> { s.progressBar = b; changed(); }));
        page.addView(toggle("Title card before the first line", s.titleCard, b -> { s.titleCard = b; changed(); }));
        page.addView(toggle("Text shadow", s.shadow, b -> { s.shadow = b; changed(); }));
        page.addView(toggle("ALL CAPS", s.uppercase, b -> { s.uppercase = b; changed(); }));
        page.addView(toggle("Slow zoom on photo", s.kenBurns, b -> { s.kenBurns = b; changed(); }));

        page.addView(heading("Video quality"));
        page.addView(spinner(Style.QUALITIES, s.quality, i -> { s.quality = i; changed(); }));
        int[] rates = {24, 30, 60};
        page.addView(spinner(new String[]{"24 fps (cinematic)", "30 fps (standard)", "60 fps (smoothest, slowest to export)"}, s.fps == 24 ? 0 : s.fps == 60 ? 2 : 1, i -> { s.fps = rates[i]; changed(); }));
        page.addView(row(button("Export video ▸", this::exportDialog, ACCENT, 0xff1a1a1a)));
    }

    // ================================================================== playback

    void loadPlayer() {
        releasePlayer();
        if (project == null || !project.hasAudio()) { updateTransport(); return; }
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(project.audio().getAbsolutePath());
            mp.setOnPreparedListener(p -> { if (player != p) return; prepared = true; if (scrub > 0) p.seekTo((int) scrub); updateTransport(); });
            mp.setOnCompletionListener(p -> { playing = false; scrub = 0; updateTransport(); });
            mp.setOnErrorListener((p, what, extra) -> { playing = false; prepared = false; toast("This phone cannot play the song (error " + what + ")."); updateTransport(); return true; });
            mp.prepareAsync();
        } catch (IOException e) { error("Song", "Cannot open the song: " + e.getMessage()); player = null; }
        updateTransport();
    }

    void releasePlayer() {
        handler.removeCallbacks(ticker);
        if (player != null) { try { player.release(); } catch (Exception ignored) {} }
        player = null; prepared = false; playing = false;
    }

    long now() {
        if (player != null && prepared) { try { return player.getCurrentPosition(); } catch (Exception ignored) {} }
        return scrub;
    }

    void play() {
        if (player == null || !prepared) { toast(project != null && project.hasAudio() ? "The song is still loading." : "Choose a song first."); return; }
        player.start();
        playing = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateTransport();
    }

    void pause() {
        if (player != null && prepared && playing) { player.pause(); scrub = player.getCurrentPosition(); }
        playing = false;
        if (!busy) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateTransport();
    }

    void togglePlay() { if (playing) pause(); else play(); }

    void seekTo(int ms) {
        scrub = Math.max(0, Math.min(ms, project == null ? 0 : project.durationMs));
        if (player != null && prepared) player.seekTo((int) scrub, MediaPlayer.SEEK_CLOSEST);
        highlightedRow = -2;
        updateTransport();
    }

    final Runnable ticker = new Runnable() { public void run() { if (dead) return; updateClock(); tickSync(); if (playing) handler.postDelayed(this, 60); } };

    void updateTransport() {
        if (playButton == null || project == null) return;
        playButton.setText(playing ? "❚❚" : "▶");
        seek.setMax((int) Math.max(1, project.durationMs));
        seek.setEnabled(project.hasAudio());
        updateClock();
        if (preview != null) preview.invalidate();
        handler.removeCallbacks(ticker);
        if (playing) handler.post(ticker);
    }

    void updateClock() {
        if (clock == null || project == null) return;
        long t = now();
        seek.setProgress((int) t);
        clock.setText(clock(t) + " / " + clock(project.durationMs));
    }

    static String clock(long ms) { long s = Math.max(0, ms) / 1000; return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60); }

    /** Live preview: draws the renderer letter-boxed in the frame's aspect ratio. */
    final class PreviewView extends View {
        final RectF frame = new RectF();
        final Path clip = new Path();
        PreviewView() {
            super(MainActivity.this);
            setOnClickListener(v -> togglePlay());
        }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(BG);
            if (project == null) return;
            float pad = dp(8), aw = getWidth() - 2 * pad, ah = getHeight() - 2 * pad, ratio = project.style.ratio();
            float w = Math.min(aw, ah * ratio), h = w / ratio;
            frame.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
            clip.reset();
            clip.addRoundRect(frame, dp(10), dp(10), Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.translate(frame.left, frame.top);
            renderer.draw(c, Math.round(w), Math.round(h), now());
            c.restore();
            if (playing) postInvalidateOnAnimation();
        }
    }

    // ================================================================== export

    void exportDialog() {
        applyLyricsNow();
        pause();
        if (!project.hasAudio()) { toast("Choose a song first."); tab(0); return; }
        Style s = project.style;
        int synced = project.lyrics.syncedCount(), total = project.lyrics.lines.size();
        String warn = total == 0 ? "\n\n⚠ There are no lyrics yet: the video will only show the title, background and visualizer."
                : synced < total ? "\n\n⚠ Only " + synced + " of " + total + " lines are synced. Unsynced lines will not appear." : "";
        long frames = project.durationMs * s.fps / 1000;
        String msg = s.width() + " × " + s.height() + "  ·  " + s.fps + " fps  ·  " + clock(project.durationMs) + "\n" + Style.ASPECTS[s.aspect] + "\n" + Style.ANIMATIONS[s.animation] + "\n\n"
                + "About " + frames + " frames to draw. Keep the app open while it renders; expect a few minutes for a full song (faster at 720p / 30 fps). The video is saved to Movies/Lyric Videos." + warn;
        new AlertDialog.Builder(this).setTitle("Export MP4").setMessage(msg).setNegativeButton("Cancel", null).setPositiveButton("Render video", (d, w) -> render()).show();
    }

    void render() {
        if (busy) return;
        busy = true;
        final Project p = project;
        try { p.save(); } catch (IOException ignored) {}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final boolean[] cancelled = {false};
        LinearLayout box = column();
        box.setPadding(dp(22), dp(16), dp(22), dp(6));
        TextView stage = label("Starting…", 15, 0xff222222, false);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        TextView pct = label("0 %", 13, 0xff555555, false);
        box.addView(stage); box.addView(bar, new LinearLayout.LayoutParams(-1, dp(28))); box.addView(pct);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Rendering lyric video").setView(box).setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> cancelled[0] = true).create();
        dialog.show();
        final long started = System.currentTimeMillis();
        jobs.execute(() -> {
            File out = new File(getCacheDir(), "render.mp4");
            Bitmap photo = null;
            Throwable fail = null;
            Uri saved = null;
            try {
                if (p.style.background == 2) photo = decodePhoto(p.image(), 2160);
                LyricRenderer r = LyricRenderer.snapshot(p, photo, LyricRenderer.loadFont(p.font()));
                VideoExporter.export(r, p.audio(), out, new VideoExporter.Listener() {
                    long lastUi;
                    public void progress(float f, String text) {
                        long t = System.currentTimeMillis();
                        if (t - lastUi < 150 && f < 1) return;
                        lastUi = t;
                        long elapsed = t - started;
                        String eta = f > .2f ? "  ·  about " + clock((long) (elapsed / f - elapsed)) + " left" : "";
                        runOnUiThread(() -> { stage.setText(text); bar.setProgress(Math.round(f * 1000)); pct.setText(Math.round(f * 100) + " %" + eta); });
                    }
                    public boolean cancelled() { return cancelled[0]; }
                });
                runOnUiThread(() -> stage.setText("Saving to your gallery…"));
                saved = publish(out, safeName(p.title) + (p.artist.isEmpty() ? "" : " - " + safeName(p.artist)) + " (lyric video).mp4");
            } catch (Throwable e) { fail = e; }
            finally { out.delete(); if (photo != null) photo.recycle(); }
            final Throwable f = fail;
            final Uri video = saved;
            final long took = System.currentTimeMillis() - started;
            runOnUiThread(() -> {
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try { dialog.dismiss(); } catch (Exception ignored) {}
                if (f instanceof CancellationException) toast("Export cancelled.");
                else if (f != null) error("Export failed", f instanceof OutOfMemoryError ? "Not enough memory. Try 720p." : f.getMessage());
                else done(video, took);
            });
        });
    }

    /** Copies the finished MP4 into the shared Movies collection (no storage permission needed on Android 10+). */
    Uri publish(File file, String name) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Lyric Videos");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = getContentResolver().insert(collection, v);
        if (uri == null) throw new IOException("Cannot create the video in your gallery.");
        try (InputStream in = new FileInputStream(file); OutputStream o = getContentResolver().openOutputStream(uri, "w")) {
            if (o == null) throw new IOException("Cannot write the video.");
            byte[] b = new byte[1 << 16]; int n;
            while ((n = in.read(b)) != -1) o.write(b, 0, n);
        } catch (IOException e) { getContentResolver().delete(uri, null, null); throw e; }
        v.clear();
        v.put(MediaStore.Video.Media.IS_PENDING, 0);
        getContentResolver().update(uri, v, null, null);
        return uri;
    }

    void done(Uri video, long took) {
        new AlertDialog.Builder(this).setTitle("Lyric video ready 🎬").setMessage("Saved to Movies/Lyric Videos in " + clock(took) + ".")
                .setNegativeButton("Close", null)
                .setNeutralButton("Watch", (d, w) -> { try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(video, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); } catch (Exception e) { toast("No video player found."); } })
                .setPositiveButton("Share", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, video).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share lyric video"));
                }).show();
    }

    // ================================================================== lifecycle

    @Override public void onBackPressed() {
        if (project != null) { applyLyricsNow(); saveLater.run(); home(); }
        else super.onBackPressed();
    }

    @Override protected void onPause() {
        super.onPause();
        applyLyricsNow();
        pause();
        handler.removeCallbacks(saveLater);
        saveLater.run();
    }

    @Override protected void onDestroy() {
        dead = true;
        releasePlayer();
        jobs.shutdownNow();
        super.onDestroy();
    }
}

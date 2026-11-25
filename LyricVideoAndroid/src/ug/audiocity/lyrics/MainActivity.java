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
    static final int REQ_AUDIO = 1, REQ_IMAGE = 2, REQ_LYRICS = 3, REQ_FONT = 4, REQ_SAVE_LRC = 5, REQ_COVER = 6;
    static final String[] TABS = {"Song", "Lyrics", "Sync", "Style"};

    final Handler handler = new Handler(Looper.getMainLooper());
    final ExecutorService jobs = Executors.newSingleThreadExecutor();
    final LyricRenderer renderer = new LyricRenderer();

    Project project;
    MediaPlayer player;
    boolean prepared, playing, dead, busy;
    long scrub;
    int tab;
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
        Fonts.assets = getAssets();
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
        TextView appName = label("Lyric Video Studio", 30, TEXT, false);
        appName.setTypeface(Fonts.get(0, null));
        page.addView(appName);
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
        projectFont = LyricRenderer.loadFont(project.font());
        renderer.photos = new PhotoSet(project.photos(), 1080);
        renderer.cover = PhotoSet.decode(project.cover(), 900);
        renderer.custom = projectFont;
        renderer.bind(project);
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

        LinearLayout cov = card();
        cov.addView(label("Cover photo (square)", 13, DIM, true));
        cov.addView(label(project.hasCover() ? "Shown on top of the background — left, centre, right or top, as a square, rounded, circle or spinning vinyl record. Set it up in Style → Cover photo."
                : "Optional: your single or album artwork, placed on top of the background beside or above the lyrics. Any picture is cropped to a square from its centre.", 12, DIM, false));
        if (project.hasCover()) {
            ImageView cv = new ImageView(this);
            cv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cv.setBackground(round(PANEL2, 10));
            cv.setClipToOutline(true);
            Bitmap th = PhotoSet.decode(project.cover(), 300);
            if (th != null) cv.setImageBitmap(th);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(96), dp(96)); cp.topMargin = dp(8);
            cov.addView(cv, cp);
            cov.addView(row(button("Change cover…", () -> pick("image/*", REQ_COVER)), button("Remove", () -> {
                project.cover().delete(); project.style.cover = false; if (project.style.background == 3) project.style.background = 0; loadAssets(); changed(); tab(0);
            })));
        } else cov.addView(row(button("Choose cover photo…", () -> pick("image/*", REQ_COVER))));
        addCard(page, cov);

        LinearLayout photo = card();
        File[] photos = project.photos();
        photo.addView(label("Background photos", 13, DIM, true));
        photo.addView(label(photos.length == 0 ? "Optional: cover art, artist shots or any pictures. One photo stays for the whole song; several play as a slideshow with smooth cross-fades. Without photos, an animated gradient is used."
                : photos.length + (photos.length == 1 ? " photo. Add more for a slideshow." : " photos in a slideshow. Tap one to move or remove it.") + " Look options are in Style → Background.", 12, DIM, false));
        if (photos.length > 0) {
            HorizontalScrollView strip = new HorizontalScrollView(this);
            strip.setHorizontalScrollBarEnabled(false);
            LinearLayout thumbs = new LinearLayout(this);
            strip.addView(thumbs);
            for (int i = 0; i < photos.length; i++) {
                final int k = i;
                ImageView v = new ImageView(this);
                v.setScaleType(ImageView.ScaleType.CENTER_CROP);
                v.setBackground(round(PANEL2, 8));
                v.setClipToOutline(true);
                Bitmap thumb = PhotoSet.decode(photos[i], 240);
                if (thumb != null) v.setImageBitmap(thumb);
                v.setOnClickListener(x -> photoMenu(k));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(74), dp(74)); tp.rightMargin = dp(8);
                thumbs.addView(v, tp);
            }
            strip.setPadding(0, dp(8), 0, dp(4));
            photo.addView(strip);
        }
        LinearLayout photoButtons = row(button(photos.length == 0 ? "Add photos…" : "Add more…", this::pickPhotos));
        if (photos.length > 0) photoButtons.addView(button("Remove all", () -> new AlertDialog.Builder(this).setTitle("Remove all photos?").setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> { for (File f : project.photos()) f.delete(); if (project.style.background == 2) project.style.background = 0; loadAssets(); changed(); tab(0); }).show()),
                weighted());
        photo.addView(photoButtons);
        addCard(page, photo);
    }

    LinearLayout.LayoutParams weighted() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1); p.leftMargin = dp(8); return p; }

    void pickPhotos() {
        if (project.photos().length >= Project.MAX_PHOTOS) { toast("Up to " + Project.MAX_PHOTOS + " photos per video."); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_IMAGE); } catch (Exception e) { error("Choose photos", "No file picker is available: " + e.getMessage()); }
    }

    void photoMenu(int k) {
        File[] photos = project.photos();
        if (k >= photos.length) return;
        new AlertDialog.Builder(this).setTitle("Photo " + (k + 1) + " of " + photos.length).setItems(new String[]{"Move earlier", "Move later", "Make it the first photo", "Remove"}, (d, which) -> {
            java.util.ArrayList<File> order = new java.util.ArrayList<>(java.util.Arrays.asList(photos));
            File f = order.get(k);
            if (which == 3) { f.delete(); if (project.photos().length == 0 && project.style.background == 2) project.style.background = 0; }
            else {
                order.remove(k);
                order.add(which == 0 ? Math.max(0, k - 1) : which == 1 ? Math.min(order.size(), k + 1) : 0, f);
                try { project.orderPhotos(order); } catch (IOException e) { error("Photos", e.getMessage()); }
            }
            loadAssets(); changed(); tab(0);
        }).show();
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
        if (result != RESULT_OK || data == null || project == null) return;
        final Project p = project;
        if (request == REQ_IMAGE) { addPhotos(p, data); return; }
        if (data.getData() == null) return;
        Uri uri = data.getData();
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
        } else if (request == REQ_COVER) {
            work("Preparing the cover…", () -> {
                File tmp = new File(p.dir, "incoming-cover");
                copy(getContentResolver().openInputStream(uri), tmp);
                Bitmap b = PhotoSet.decode(tmp, 1600);
                tmp.delete();
                if (b == null) throw new IOException("That picture could not be opened.");
                int side = Math.min(b.getWidth(), b.getHeight());
                Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
                Bitmap out = side > 1600 ? Bitmap.createScaledBitmap(sq, 1600, 1600, true) : sq;
                try (OutputStream o = new FileOutputStream(p.cover())) { out.compress(Bitmap.CompressFormat.JPEG, 93, o); }
            }, () -> { p.style.cover = true; loadAssets(); changed(); tab(tab); toast("Cover added. Choose its place and shape in Style → Cover photo."); });
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
            }, () -> { p.style.font = Style.FONT_IMPORTED; loadAssets(); changed(); tab(3); });
        } else if (request == REQ_SAVE_LRC) {
            String lrc = p.lyrics.toLrc(p.title, p.artist);
            work("Saving LRC…", () -> {
                try (OutputStream o = getContentResolver().openOutputStream(uri, "wt")) { if (o == null) throw new IOException("Cannot write there."); o.write(lrc.getBytes(StandardCharsets.UTF_8)); }
            }, () -> toast("Synced lyrics saved as LRC."));
        }
    }

    void addPhotos(Project p, Intent data) {
        final java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        final int[] added = {0};
        work("Adding " + uris.size() + (uris.size() == 1 ? " photo…" : " photos…"), () -> {
            for (Uri uri : uris) {
                if (p.photos().length >= Project.MAX_PHOTOS) break;
                File tmp = new File(p.dir, "incoming-image");
                copy(getContentResolver().openInputStream(uri), tmp);
                Bitmap b = PhotoSet.decode(tmp, 2560);
                tmp.delete();
                if (b == null) continue;
                try (OutputStream o = new FileOutputStream(p.nextPhoto())) { b.compress(Bitmap.CompressFormat.JPEG, 92, o); }
                b.recycle();
                added[0]++;
            }
            if (added[0] == 0) throw new IOException("Those pictures could not be opened.");
        }, () -> {
            p.style.background = 2; loadAssets(); changed(); tab(tab);
            toast(added[0] + (added[0] == 1 ? " photo added." : " photos added.") + (added[0] < uris.size() ? " Some were skipped (limit " + Project.MAX_PHOTOS + " or unreadable)." : ""));
        });
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

    TextView lyricsCount;

    void lyricsTab(LinearLayout page) {
        LinearLayout steps = card();
        steps.addView(label("Add the words in three easy ways", 15, TEXT, true));
        steps.addView(label("0. Don't have them? “Write the lyrics for me” listens to the song (Luganda) and drafts them for you to correct.\n1. Copy the lyrics from anywhere and tap Paste.\n2. Or type them: press Enter for each new line on screen.\n3. Or import a .lrc / .txt file (LRC keeps its timings).\n\nTip: Clean up removes labels like [Chorus], stray symbols and timestamps, and splits long rows so they fit on screen.", 13, DIM, false));
        addCard(page, steps);
        page.addView(row(button("📋  Paste", this::pasteLyrics, ACCENT, 0xff1a1a1a), button("✨  Clean up", this::cleanLyrics), button("Import file", () -> pick("*/*", REQ_LYRICS))));
        page.addView(row(button("✍️  Write the lyrics for me (AI)", this::writeLyricsMenu)));
        lyricsCount = label("", 12, DIM, false);
        lyricsCount.setPadding(0, dp(10), 0, dp(4));
        page.addView(lyricsCount);
        lyricsBox = new EditText(this);
        lyricsBox.setText(project.lyrics.toText());
        lyricsBox.setHint("Type or paste the lyrics here…\n\nOne row = one line on screen.\nKeep each row to a short phrase for the best look.");
        lyricsBox.setHintTextColor(0xff6c6c7c);
        lyricsBox.setTextColor(TEXT);
        lyricsBox.setTextSize(17);
        lyricsBox.setLineSpacing(dp(4), 1);
        lyricsBox.setGravity(Gravity.TOP);
        lyricsBox.setMinLines(12);
        lyricsBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        lyricsBox.setBackground(round(PANEL, 14));
        lyricsBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        lyricsBox.addTextChangedListener(watcher(s -> { updateLyricsCount(); if (!settingLyricsText) { handler.removeCallbacks(applyLyrics); handler.postDelayed(applyLyrics, 900); } }));
        page.addView(lyricsBox, new LinearLayout.LayoutParams(-1, -2));
        updateLyricsCount();
        page.addView(row(button("Clear all", () -> new AlertDialog.Builder(this).setTitle("Clear all lyrics?").setMessage("The words and their timings are removed.").setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> { settingLyricsText = true; lyricsBox.setText(""); settingLyricsText = false; project.lyrics.lines.clear(); changed(); updateLyricsCount(); }).show()),
                button("Save as .lrc", this::saveLrc)));
        page.addView(row(button("Next: sync the lines ▸", () -> tab(2), ACCENT, 0xff1a1a1a)));
    }

    void updateLyricsCount() {
        if (lyricsCount == null || lyricsBox == null) return;
        String t = lyricsBox.getText().toString();
        int lines = 0, longOnes = 0;
        for (String r : t.split("\n")) { String x = r.trim(); if (x.isEmpty()) continue; lines++; if (x.length() > Lyrics.LONG_LINE) longOnes++; }
        lyricsCount.setText(lines == 0 ? "No lyrics yet." : lines + " lines" + (longOnes > 0 ? "  ·  " + longOnes + " long — Clean up can split them" : "") + "  ·  " + project.lyrics.syncedCount() + " synced");
    }

    void pasteLyrics() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        CharSequence clip = null;
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) clip = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (clip == null || clip.toString().trim().isEmpty()) { toast("Copy the lyrics first (from a website, notes or messages), then tap Paste."); return; }
        String text = clip.toString();
        Runnable put = () -> {
            boolean messy = Lyrics.needsCleanUp(text) && !Lyrics.looksLikeLrc(text);
            String current = lyricsBox.getText().toString().trim();
            lyricsBox.setText(current.isEmpty() || !replaceOnPaste ? text : current + "\n" + text);
            applyLyricsNow();
            if (messy) new AlertDialog.Builder(this).setTitle("Tidy the pasted lyrics?").setMessage("They contain section labels, symbols or very long rows. Clean up removes labels like [Chorus] and splits long rows into readable lines.")
                    .setNegativeButton("Keep as is", null).setPositiveButton("Clean up", (d, w) -> cleanLyrics()).show();
        };
        replaceOnPaste = true;
        if (lyricsBox.getText().toString().trim().isEmpty()) put.run();
        else new AlertDialog.Builder(this).setTitle("Paste lyrics").setItems(new String[]{"Replace the current lyrics", "Add to the end"}, (d, i) -> { replaceOnPaste = i == 0; put.run(); }).show();
    }
    boolean replaceOnPaste = true;

    void cleanLyrics() {
        String before = lyricsBox.getText().toString();
        if (Lyrics.looksLikeLrc(before)) { applyLyricsNow(); before = lyricsBox.getText().toString(); }
        String after = Lyrics.cleanUp(before, Lyrics.LONG_LINE);
        if (after.equals(before.trim())) { toast("Already tidy ✓"); return; }
        lyricsBox.setText(after);
        applyLyricsNow();
        toast("Cleaned up: " + project.lyrics.lines.size() + " lines.");
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
        boolean ready = ModelStore.ready(this);
        LinearLayout ai = card();
        ai.addView(label("🎧  AI listens · Luganda", 16, TEXT, true));
        ai.addView(label(ready ? "The app listens to the song and finds when every line and every word is sung, block by block, word by word. Your lyrics stay on the phone. It takes a few minutes for a full song — keep the app open."
                : "Let the app listen to the song and time every line and word for you. First download the Luganda listening model once (355 MB — Wi-Fi recommended). After that it works offline and your songs never leave the phone.", 12, DIM, false));
        if (ready) {
            Button listen = button("🎧  Listen & sync every word", this::aiSync, ACCENT, 0xff1a1a1a);
            listen.setTextSize(16);
            LinearLayout.LayoutParams lpl = new LinearLayout.LayoutParams(-1, dp(56)); lpl.topMargin = dp(8);
            ai.addView(listen, lpl);
            if (!aiCheck.isEmpty()) ai.addView(label("⚠ " + aiCheck.size() + " line(s) the AI was unsure about are marked below — play them and adjust if needed.", 12, 0xffffb703, false));
        } else ai.addView(row(button("⬇  Download AI model (355 MB)", this::downloadModel, ACCENT, 0xff1a1a1a)));
        addCard(page, ai);
        syncStatus = label("", 13, DIM, false);
        syncStatus.setPadding(0, dp(10), 0, dp(4));
        page.addView(syncStatus);
        page.addView(heading("Or sync by tapping"));
        Button full = button("🎤  Sync mode (full screen)", this::openSyncMode, PANEL2, TEXT);
        full.setTextSize(17);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(64)); fp.topMargin = dp(4);
        page.addView(full, fp);
        page.addView(label("Big text, tap anywhere on the screen, slow-down playback and quick undo. The easiest way to sync.", 12, DIM, false));
        page.addView(row(button("⚡  Quick sync", this::quickSync), button("⟲  Start over", this::syncFromStart)));
        page.addView(label("Quick sync spreads the lines across the song in seconds; then play it and fix the few lines that are off.", 12, DIM, false));
        page.addView(heading("Or tap here"));
        tapButton = button("", () -> {}, PANEL2, TEXT);
        tapButton.setTextSize(16);
        tapButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        tapOnTouch(tapButton);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(100)); tp.topMargin = dp(6);
        page.addView(tapButton, tp);
        page.addView(row(button("▶  Continue", this::syncContinue), button("Undo last tap", this::undoTap), button("Shift all…", this::shiftAll)));
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

    /** Taps count the moment the finger lands (not when it lifts), so timings are tighter. */
    void tapOnTouch(View v) {
        v.setOnTouchListener((view, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { view.setPressed(true); tap(); }
            else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) view.setPressed(false);
            return true;
        });
    }

    // ------------------------------------------------------------------ full-screen sync mode

    FrameLayout syncOverlay;
    TextView ovPrev, ovNext, ovAfter, ovCount, ovHint;
    Button ovSpeed, ovPlay;
    float speed = 1f;

    void openSyncMode() {
        if (syncOverlay != null) return;
        FrameLayout o = new FrameLayout(this);
        o.setBackgroundColor(0xf70d0d12);
        insets(o);
        LinearLayout col = column();
        col.setPadding(dp(16), dp(10), dp(16), dp(16));
        o.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button done = button("✕  Done", this::closeSyncMode);
        ovSpeed = button("Speed 1×", this::cycleSpeed);
        Button back = button("⟲ 5 s", () -> seekTo((int) Math.max(0, now() - 5000)));
        Button undo = button("Undo", this::undoTap);
        for (Button b : new Button[]{done, ovSpeed, back, undo}) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1); p.rightMargin = dp(6); top.addView(b, p); }
        col.addView(top);

        ovCount = label("", 13, DIM, true);
        ovCount.setGravity(Gravity.CENTER);
        ovCount.setPadding(0, dp(14), 0, 0);
        col.addView(ovCount, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout middle = column();
        middle.setGravity(Gravity.CENTER);
        ovPrev = label("", 18, 0xff6f6f80, false);
        TextView nextTag = label("TAP WHEN THIS STARTS", 11, ACCENT, true);
        nextTag.setLetterSpacing(.2f);
        ovNext = label("", 30, TEXT, true);
        ovAfter = label("", 18, 0xff8a8a99, false);
        for (TextView t : new TextView[]{ovPrev, nextTag, ovNext, ovAfter}) { t.setGravity(Gravity.CENTER); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(12); middle.addView(t, p); }
        ovNext.setTypeface(Fonts.get(0, null));
        col.addView(middle, new LinearLayout.LayoutParams(-1, 0, 1));

        ovHint = label("", 15, DIM, false);
        ovHint.setGravity(Gravity.CENTER);
        col.addView(ovHint, new LinearLayout.LayoutParams(-1, -2));
        ovPlay = button("▶  Play", this::togglePlay, ACCENT, 0xff1a1a1a);
        ovPlay.setTextSize(17);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(56)); pp.topMargin = dp(12);
        col.addView(ovPlay, pp);

        // Anywhere that is not a button is the tap area.
        o.setOnTouchListener((v, e) -> { if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { tap(); v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } return true; });
        syncOverlay = o;
        addContentView(o, new ViewGroup.LayoutParams(-1, -1));
        o.requestApplyInsets();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshOverlay();
    }

    void closeSyncMode() {
        if (syncOverlay == null) return;
        pause();
        setSpeed(1f);
        ((ViewGroup) syncOverlay.getParent()).removeView(syncOverlay);
        syncOverlay = null;
        tab(2);
    }

    void cycleSpeed() { setSpeed(speed == 1f ? .75f : speed == .75f ? .5f : 1f); }

    void setSpeed(float s) {
        speed = s;
        if (ovSpeed != null) ovSpeed.setText("Speed " + (s == 1f ? "1" : s == .75f ? "0.75" : "0.5") + "×");
        if (player != null && prepared && playing) try { player.setPlaybackParams(player.getPlaybackParams().setSpeed(s)); } catch (Exception ignored) {}
    }

    void refreshOverlay() {
        if (syncOverlay == null) return;
        Lyrics l = project.lyrics;
        int next = l.nextUnsynced(), prev = -1;
        for (int i = (next < 0 ? l.lines.size() : next) - 1; i >= 0; i--) if (l.lines.get(i).start >= 0) { prev = i; break; }
        ovCount.setText(l.syncedCount() + " of " + l.lines.size() + " lines synced");
        ovPrev.setText(prev >= 0 ? "✓ " + l.lines.get(prev).text : "");
        ovNext.setText(next >= 0 ? l.lines.get(next).text : "All lines synced ✓");
        ovNext.setTextColor(next >= 0 ? ACCENT : 0xff7ee0a1);
        ovAfter.setText(next >= 0 && next + 1 < l.lines.size() ? l.lines.get(next + 1).text : "");
        ovHint.setText(next < 0 ? "Tap Done, then play the song to check it." : playing ? "Tap anywhere on the screen as the highlighted line begins" : "Press Play, then tap anywhere as each line begins");
        ovPlay.setText(playing ? "❚❚  Pause" : "▶  Play");
    }

    void quickSync() {
        Lyrics l = project.lyrics;
        long guess = 0;
        float[] env = project.envelope;
        for (int i = 0; i < env.length; i++) if (env[i] > .3f) { guess = i * 1000L / Project.ENVELOPE_RATE; break; }
        LinearLayout box = column();
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(label("Lines without a time are spread over the song by their length. Synced lines stay where they are.", 13, 0xff555555, false));
        EditText start = field(Lyrics.stamp(l.firstStart() >= 0 ? l.firstStart() : guess), "First line starts at");
        EditText end = field(Lyrics.stamp(Math.max(0, project.durationMs - 3000)), "Last line by");
        box.addView(label("First line starts at (m:ss)", 12, 0xff555555, true));
        box.addView(start);
        box.addView(label("Last line sung by (m:ss)", 12, 0xff555555, true));
        box.addView(end);
        box.addView(row(button("Use playhead for the start", () -> start.setText(Lyrics.stamp(now())))));
        new AlertDialog.Builder(this).setTitle("⚡ Quick sync").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Spread lines", (d, w) -> {
            long a = Lyrics.parseStamp(start.getText().toString()), b = Lyrics.parseStamp(end.getText().toString());
            if (a < 0 || b <= a) { toast("Check the two times (m:ss), the end must be after the start."); return; }
            int n = l.spread(a, Math.min(b, project.durationMs));
            changed(); refreshSync();
            toast(n == 0 ? "Every line already has a time." : n + " lines timed. Play it and tap any line that's off to adjust it.");
            seekTo((int) Math.max(0, a - 1500));
        }).show();
    }

    void refreshSync() {
        refreshOverlay();
        if (syncStatus == null) return;
        Lyrics l = project.lyrics;
        int next = l.nextUnsynced();
        syncStatus.setText(l.syncedCount() + " of " + l.lines.size() + " lines synced. Play the song and tap the big button the moment each line starts.");
        tapButton.setText(next < 0 ? "All lines synced ✓\nPlay to check, or tap a line below to adjust" : "TAP when this starts\n“" + l.lines.get(next).text + "”");
        tapButton.setBackground(round(next < 0 ? PANEL2 : 0xff3b3520, 12));
        for (int i = 0; i < syncRows.size() && i < l.lines.size(); i++) {
            Lyrics.Line line = l.lines.get(i);
            syncRows.get(i).setText((line.start < 0 ? "  –:––.––" : Lyrics.stamp(line.start)) + (line.wordTimed() ? "  ●" : "    ") + "  " + line.text + (aiCheck.contains(i) ? "   ⚠" : ""));
            syncRows.get(i).setTextColor(line.start < 0 ? DIM : aiCheck.contains(i) ? 0xffffcf70 : TEXT);
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
        // Reaction time is real time: at slower playback it covers less of the song.
        long t = Math.max(0, now() - Math.round(tapOffset() * speed));
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
        final long[] words = line.wordTimed() ? line.wordTimes.clone() : null;
        if (words != null) {
            box.addView(label("Words (● timed by AI) — tap a word to set it to the playhead", 12, 0xff555555, true));
            HorizontalScrollView sv = new HorizontalScrollView(this);
            LinearLayout chips = new LinearLayout(this);
            sv.addView(chips);
            String[] w = line.words();
            for (int k = 0; k < w.length; k++) {
                final int wk = k;
                Button chip = button(w[k] + "\n" + Lyrics.stamp(words[k]), () -> {}, PANEL2, TEXT);
                chip.setTextSize(12);
                chip.setOnClickListener(v -> {
                    long t = now();
                    long lo = wk > 0 ? words[wk - 1] + 20 : 0, hi = wk + 1 < words.length ? words[wk + 1] - 20 : Long.MAX_VALUE;
                    words[wk] = Math.max(lo, Math.min(hi, t));
                    ((Button) v).setText(w[wk] + "\n" + Lyrics.stamp(words[wk]));
                    if (wk == 0) time.setText(Lyrics.stamp(words[0]));
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(52)); cp.rightMargin = dp(6);
                chips.addView(chip, cp);
            }
            box.addView(sv);
        }
        AlertDialog[] dialog = {null};
        box.addView(row(button("Delete line", () -> {
            project.lyrics.lines.remove(i); changed(); dialog[0].dismiss(); tab(2);
        }, 0xff4a2028, TEXT), button("Insert line after", () -> {
            project.lyrics.lines.add(i + 1, new Lyrics.Line("New line", -1)); changed(); dialog[0].dismiss(); tab(2); editLine(i + 1);
        })));
        dialog[0] = new AlertDialog.Builder(this).setTitle("Line " + (i + 1)).setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
            String t = Lyrics.clean(text.getText().toString());
            boolean sameWords = t.isEmpty() || t.equals(line.text);
            if (!t.isEmpty()) line.text = t;
            if (words != null && sameWords) { line.wordTimes = words; line.start = words[0]; }
            if (!sameWords) line.wordTimes = null;
            String ts = time.getText().toString().trim();
            long st = ts.isEmpty() ? -1 : Lyrics.parseStamp(ts);
            if (!ts.isEmpty() && st < 0) toast("Time not understood; use m:ss.cc, for example 1:23.45.");
            else if (st != line.start) Lyrics.moveLine(line, st);
            aiCheck.remove(i);
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

    // ------------------------------------------------------------------ AI: listening model, word sync, writing lyrics

    /** Lines the AI was unsure about (indices), shown with ⚠ until edited. */
    final java.util.HashSet<Integer> aiCheck = new java.util.HashSet<>();
    /** What the model heard for the current song, kept so a second action does not listen again. */
    AutoSync.Heard heard;
    String heardFor;

    interface ProgressJob { void run(VideoExporter.Listener l) throws Exception; }

    /** Runs a long job with a progress bar and a Cancel button, keeping the screen on. */
    void progressJob(String title, ProgressJob job, Runnable done) {
        if (busy) return;
        busy = true;
        pause();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final boolean[] cancelled = {false};
        LinearLayout box = column();
        box.setPadding(dp(22), dp(16), dp(22), dp(6));
        TextView stage = label("Starting…", 15, 0xff222222, false);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        TextView pct = label("", 13, 0xff555555, false);
        box.addView(stage); box.addView(bar, new LinearLayout.LayoutParams(-1, dp(28))); box.addView(pct);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(false).setNegativeButton("Cancel", (d, w) -> cancelled[0] = true).create();
        dialog.show();
        final long started = System.currentTimeMillis();
        jobs.execute(() -> {
            Throwable fail = null;
            try {
                job.run(new VideoExporter.Listener() {
                    long last;
                    public void progress(float f, String text) {
                        long t = System.currentTimeMillis();
                        if (t - last < 150 && f < 1) return;
                        last = t;
                        long el = t - started;
                        String eta = f > .1f ? "  ·  about " + clock((long) (el / f - el)) + " left" : "";
                        runOnUiThread(() -> { stage.setText(text); bar.setProgress(Math.round(f * 1000)); pct.setText(Math.round(f * 100) + " %" + eta); });
                    }
                    public boolean cancelled() { return cancelled[0]; }
                });
            } catch (Throwable e) { fail = e; }
            final Throwable f = fail;
            runOnUiThread(() -> {
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try { dialog.dismiss(); } catch (Exception ignored) {}
                if (f instanceof CancellationException) toast("Cancelled.");
                else if (f != null) error(title, f instanceof OutOfMemoryError ? "Not enough memory on this phone for that." : f.getMessage());
                else if (done != null) done.run();
            });
        });
    }

    void downloadModel() {
        new AlertDialog.Builder(this).setTitle("Download the Luganda listening model?")
                .setMessage("355 MB, downloaded once from this app's release page. Wi-Fi recommended.\n\nThe model (wav2vec2 Luganda, Apache-2.0 licence) runs entirely on your phone: songs are never uploaded for word timing.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Download", (d, w) -> progressJob("Downloading the AI model", l -> ModelStore.download(this, l), () -> { toast("AI model ready ✓"); tab(tab); })).show();
    }

    /** Listens to the current song (or reuses what was heard), then hands the result over on the background thread. */
    void withHeard(String title, java.util.function.Consumer<AutoSync.Heard> then, Runnable done) {
        final Project p = project;
        final String key = p.dir.getAbsolutePath() + "/" + p.audioFile + "/" + p.durationMs;
        progressJob(title, l -> {
            AutoSync.Heard h = key.equals(heardFor) ? heard : null;
            if (h == null) {
                h = AutoSync.listen(ModelStore.dir(this), p.audio(), p.durationMs, l);
                heard = h; heardFor = key;
            }
            l.progress(.98f, "Matching words…");
            then.accept(h);
        }, done);
    }

    void aiSync() {
        applyLyricsNow();
        if (!project.hasAudio() || project.lyrics.lines.isEmpty()) { toast("Add the song and the lyrics first."); return; }
        if (!ModelStore.ready(this)) { downloadModel(); return; }
        Runnable go = () -> {
            final Lyrics work = new Lyrics();
            for (Lyrics.Line l : project.lyrics.lines) work.lines.add(new Lyrics.Line(l.text, -1));
            final AutoTiming.Result[] result = {null};
            withHeard("Listening to the song", h -> result[0] = AutoSync.timeLyrics(work, h), () -> {
                project.lyrics.lines.clear();
                project.lyrics.lines.addAll(work.lines);
                aiCheck.clear(); aiCheck.addAll(result[0].check);
                changed(); tab(2);
                AutoTiming.Result r = result[0];
                new AlertDialog.Builder(this).setTitle("Synced by listening 🎧")
                        .setMessage(r.lines + " lines and " + r.words + " words timed." + (r.unplaced > 0 ? "\n" + r.unplaced + " line(s) could not be found in the song and keep no time." : "")
                                + (r.check.isEmpty() ? "\n\nEverything matched clearly." : "\n\n" + r.check.size() + " line(s) are marked ⚠ — the AI was less sure there (fast singing, backing vocals or words spelled differently). Play them and adjust if needed.")
                                + "\n\nPress play to watch it word by word.")
                        .setPositiveButton("OK", null).show();
            });
        };
        if (project.lyrics.syncedCount() == 0) go.run();
        else new AlertDialog.Builder(this).setTitle("Replace the current timings?").setMessage("The AI will time every line and word again from the song.").setNegativeButton("Cancel", null).setPositiveButton("Listen & sync", (d, w) -> go.run()).show();
    }

    String sunbirdKey() { return prefs().getString("sunbirdKey", ""); }

    void writeLyricsMenu() {
        applyLyricsNow();
        if (!project.hasAudio()) { toast("Choose the song first (Song tab)."); return; }
        String[] items = {"Sunbird AI · Luganda (online, best words)", "On-phone AI · Luganda (offline, rough draft)", "Sunbird API key…"};
        new AlertDialog.Builder(this).setTitle("Write the lyrics for me").setItems(items, (d, i) -> {
            if (i == 0) { if (sunbirdKey().isEmpty()) sunbirdKeyDialog(this::sunbirdWrite); else sunbirdWrite(); }
            else if (i == 1) { if (!ModelStore.ready(this)) downloadModel(); else offlineWrite(); }
            else sunbirdKeyDialog(null);
        }).show();
    }

    void sunbirdKeyDialog(Runnable then) {
        LinearLayout box = column();
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(label("Sunbird AI (Uganda) writes Luganda from audio. Create a free account and API key at api.sunbird.ai, then paste the key here. It is stored only on this phone.\n\nWith Sunbird, the song is uploaded to Sunbird's servers for transcription.", 13, 0xff444444, false));
        EditText key = field(sunbirdKey(), "Paste your Sunbird API key");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        box.addView(key);
        box.addView(row(button("Open api.sunbird.ai", () -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Sunbird.SIGNUP))); } catch (Exception e) { toast("No browser found."); } })));
        new AlertDialog.Builder(this).setTitle("Sunbird API key").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
            String k = key.getText().toString().trim();
            prefs().edit().putString("sunbirdKey", k).apply();
            if (!k.isEmpty() && then != null) then.run();
        }).show();
    }

    void sunbirdWrite() {
        final Project p = project;
        final String key = sunbirdKey();
        final Lyrics[] timed = {null};
        final boolean canTime = ModelStore.ready(this);
        progressJob("Sunbird AI is writing the lyrics", l -> {
            float[] audio = AutoSync.songAudio(p.audio(), p.durationMs, AutoSync.scaled(l, 0, .05f));
            ArrayList<Sunbird.Part> parts = Sunbird.transcribe(audio, key, "lug", AutoSync.scaled(l, .05f, canTime ? .6f : 1f));
            StringBuilder all = new StringBuilder();
            java.util.List<long[]> spans = new ArrayList<>();
            java.util.List<String> texts = new ArrayList<>();
            for (Sunbird.Part part : parts) { all.append(part.text).append('\n'); spans.add(new long[]{part.startMs, part.endMs}); texts.add(part.text); }
            if (all.toString().trim().isEmpty()) throw new IOException("Sunbird returned no words for this song.");
            if (canTime) {
                // Time Sunbird's words exactly with the on-phone model, splitting lines at the singer's pauses.
                String cacheKey = p.dir.getAbsolutePath() + "/" + p.audioFile + "/" + p.durationMs;
                AutoSync.Heard h = cacheKey.equals(heardFor) ? heard : null;
                if (h == null) { h = AutoSync.listen(ModelStore.dir(this), audio, AutoSync.scaled(l, .6f, 1f)); heard = h; heardFor = cacheKey; }
                timed[0] = AutoSync.timeAndSplit(all.toString(), h);
            } else timed[0] = AutoTiming.fromPieces(spans, texts);
        }, () -> offerLyrics("Lyrics from Sunbird AI", timed[0],
                canTime ? "Split into lines at the singer's pauses and timed word by word on the phone." : "Roughly timed by 30-second piece. Download the AI model (Sync tab), then use “Listen & sync every word” for exact timing."));
    }

    void offlineWrite() {
        final Lyrics[] out = {null};
        withHeard("Listening to the song", h -> out[0] = AutoSync.transcribe(h),
                () -> offerLyrics("Lyrics heard on the phone", out[0], "A rough draft: singing over music is hard to read. Correct the words, then use “Listen & sync every word” to time the corrected lyrics."));
    }

    /** Shows written lyrics and lets the user use them (replacing what is there). */
    void offerLyrics(String title, Lyrics found, String note) {
        if (found == null || found.lines.isEmpty()) { error(title, "No words were recognised. Try the other option, or paste the lyrics."); return; }
        TextView t = label(found.toText(), 15, 0xff222222, false);
        t.setTextIsSelectable(true);
        LinearLayout box = column();
        box.setPadding(dp(20), dp(6), dp(20), 0);
        box.addView(label(found.lines.size() + " lines. " + note, 12, 0xff666666, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10);
        box.addView(t, lp);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle(title).setView(sv).setNegativeButton("Discard", null).setPositiveButton(project.lyrics.lines.isEmpty() ? "Use these lyrics" : "Replace my lyrics", (d, w) -> {
            project.lyrics.lines.clear();
            project.lyrics.lines.addAll(found.lines);
            aiCheck.clear();
            changed();
            tab(1);
            toast("Lyrics added — correct any words, then check the timing in Sync.");
        }).show();
    }

    // ------------------------------------------------------------------ Style tab

    /** Selectable chips laid out in rows; the chosen one is gold. */
    LinearLayout chips(String[] items, int selected, int cols, Pick pick) {
        LinearLayout box = column();
        Button[] all = new Button[items.length];
        LinearLayout row = null;
        for (int i = 0; i < items.length; i++) {
            if (i % cols == 0) { row = new LinearLayout(this); box.addView(row); row.setPadding(0, dp(3), 0, dp(3)); }
            final int k = i;
            Button b = button(items[i], () -> {
                for (int x = 0; x < all.length; x++) { all[x].setBackground(round(x == k ? ACCENT : PANEL2, 12)); all[x].setTextColor(x == k ? 0xff1a1a1a : TEXT); }
                pick.picked(k);
            });
            b.setTextSize(13);
            b.setPadding(dp(6), 0, dp(6), 0);
            b.setBackground(round(i == selected ? ACCENT : PANEL2, 12));
            b.setTextColor(i == selected ? 0xff1a1a1a : TEXT);
            all[i] = b;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (i % cols > 0) p.leftMargin = dp(6);
            row.addView(b, p);
        }
        if (items.length % cols != 0) for (int i = items.length % cols; i < cols; i++) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1); p.leftMargin = dp(6); row.addView(new View(this), p); }
        return box;
    }

    void fontPicker() {
        ScrollView sv = new ScrollView(this);
        LinearLayout list = column();
        list.setPadding(dp(12), dp(6), dp(12), dp(6));
        sv.addView(list);
        AlertDialog[] dlg = {null};
        for (int i = 0; i < Style.FONT_NAMES.length; i++) {
            final int k = i;
            if (i == Style.FONT_IMPORTED && projectFont == null) continue;
            TextView t = label(Style.FONT_NAMES[i], 24, i == project.style.font ? ACCENT : TEXT, false);
            t.setTypeface(Fonts.get(i, projectFont));
            t.setPadding(dp(14), dp(12), dp(14), dp(12));
            t.setBackground(round(i == project.style.font ? 0xff3b3520 : PANEL, 10));
            t.setOnClickListener(v -> { project.style.font = k; changed(); dlg[0].dismiss(); tab(3); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(6);
            list.addView(t, lp);
        }
        Button imp = button("Import your own font (.ttf / .otf)…", () -> { dlg[0].dismiss(); pick("*/*", REQ_FONT); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48)); lp.topMargin = dp(10);
        list.addView(imp, lp);
        dlg[0] = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar).setView(sv).setNegativeButton("Close", null).create();
        dlg[0].show();
    }

    void styleTab(LinearLayout page) {
        Style s = project.style;
        page.addView(heading("One-tap looks"));
        page.addView(chips(Style.PRESETS, -1, 2, i -> {
            if (i >= 8 && !project.hasCover()) { toast("Choose a cover photo for this look."); pick("image/*", REQ_COVER); }
            s.preset(i); if (!project.hasCover()) { s.cover = false; if (s.background == 3) s.background = 0; }
            changed(); tab(3); toast("“" + Style.PRESETS[i] + "” applied. Adjust anything below.");
        }));

        page.addView(heading("Format"));
        page.addView(label("Portrait", 12, DIM, false));
        int[] portrait = {0, 3, 2}, landscape = {1, 4, 5};
        String[] pNames = {"9:16  Story / Reels", "4:5  Feed", "1:1  Square"}, lNames = {"16:9  YouTube", "21:9  Cinema", "4:3  Classic"};
        int pSel = -1, lSel = -1;
        for (int i = 0; i < 3; i++) { if (portrait[i] == s.aspect) pSel = i; if (landscape[i] == s.aspect) lSel = i; }
        page.addView(chips(pNames, pSel, 3, i -> { s.aspect = portrait[i]; changed(); tab(3); }));
        page.addView(label("Landscape", 12, DIM, false));
        page.addView(chips(lNames, lSel, 3, i -> { s.aspect = landscape[i]; changed(); tab(3); }));
        page.addView(toggle("Cinematic black bars", s.letterbox, b -> { s.letterbox = b; changed(); }));

        page.addView(heading("Words effect"));
        page.addView(chips(Style.ANIMATIONS, s.animation, 3, i -> { s.animation = i; changed(); }));
        page.addView(label("Play the song to see the effect in the preview above.", 12, DIM, false));

        page.addView(heading("Font"));
        Button font = button(Style.FONT_NAMES[s.font] + "   ▸  choose", this::fontPicker);
        font.setTypeface(Fonts.get(s.font, projectFont));
        font.setTextSize(20);
        page.addView(font, new LinearLayout.LayoutParams(-1, dp(56)));
        page.addView(label("Text size", 12, DIM, false));
        page.addView(slider(.6f, 1.6f, s.textSize, v -> { s.textSize = v; changed(); }));
        page.addView(label("Letter spacing", 12, DIM, false));
        page.addView(slider(0, .3f, s.letterSpacing, v -> { s.letterSpacing = v; changed(); }));
        page.addView(label("Position", 12, DIM, false));
        page.addView(chips(Style.POSITIONS, s.position, 3, i -> { s.position = i; changed(); }));

        page.addView(heading("Colours"));
        page.addView(label("Text", 12, DIM, false));
        page.addView(swatches(s.textColor, c -> { s.textColor = c; changed(); }));
        page.addView(label("Highlight (sung words, glow, visualizer)", 12, DIM, false));
        page.addView(swatches(s.highlight, c -> { s.highlight = c; changed(); }));

        page.addView(heading("Text effects"));
        page.addView(toggle("Soft shadow", s.shadow, b -> { s.shadow = b; changed(); }));
        page.addView(toggle("Outline", s.outline, b -> { s.outline = b; changed(); }));
        page.addView(toggle("Glow", s.glow, b -> { s.glow = b; changed(); }));
        page.addView(toggle("Caption box behind the words", s.box, b -> { s.box = b; changed(); }));
        page.addView(toggle("ALL CAPS", s.uppercase, b -> { s.uppercase = b; changed(); }));

        page.addView(heading("Cover photo"));
        if (!project.hasCover()) page.addView(row(button("Choose a square cover photo…", () -> pick("image/*", REQ_COVER))));
        else {
            page.addView(toggle("Show the cover on top of the background", s.cover, b -> { s.cover = b; changed(); tab(3); }));
            if (s.cover) {
                page.addView(label("Place (lyrics move to the free space)", 12, DIM, false));
                page.addView(chips(Style.COVER_POSITIONS, s.coverPos, 4, i -> { s.coverPos = i; changed(); }));
                page.addView(label("Shape", 12, DIM, false));
                page.addView(chips(Style.COVER_SHAPES, s.coverShape, 2, i -> { s.coverShape = i; changed(); }));
                page.addView(label("Size", 12, DIM, false));
                page.addView(slider(.25f, .7f, s.coverSize, v -> { s.coverSize = v; changed(); }));
                page.addView(toggle("Pulse with the beat", s.coverPulse, b -> { s.coverPulse = b; changed(); }));
                page.addView(toggle("Show title & artist with the cover", s.coverCaption, b -> { s.coverCaption = b; changed(); }));
            }
            page.addView(row(button("Use the cover as a blurred background", () -> { s.background = 3; if (s.photoBlur < .1f) s.photoBlur = .6f; changed(); tab(3); })));
        }

        page.addView(heading("Background"));
        page.addView(chips(Style.BACKGROUNDS, s.background, 2, i -> {
            if (i == 2 && !project.hasPhotos()) { pickPhotos(); return; }
            if (i == 3 && !project.hasCover()) { pick("image/*", REQ_COVER); return; }
            if (i == 3 && s.photoBlur < .1f) s.photoBlur = .6f;
            s.background = i; changed(); tab(3);
        }));
        if (s.background == 0) {
            page.addView(label("Gradient colours", 12, DIM, false));
            page.addView(chips(Style.PALETTE_NAMES, s.palette, 4, i -> { s.palette = i; changed(); }));
        } else if (s.background == 1) {
            page.addView(label("Colour", 12, DIM, false));
            page.addView(swatches(s.solid, c -> { s.solid = c; changed(); }));
        } else if (s.background == 3) {
            page.addView(label("Blur", 12, DIM, false));
            page.addView(slider(0, 1, s.photoBlur, v -> { s.photoBlur = v; changed(); }));
            page.addView(label("Colour filter", 12, DIM, false));
            page.addView(chips(Style.PHOTO_FILTERS, s.photoFilter, 3, i -> { s.photoFilter = i; changed(); }));
            page.addView(label("Darken for readable lyrics", 12, DIM, false));
            page.addView(slider(0, .85f, s.dim, v -> { s.dim = v; changed(); }));
            page.addView(toggle("Slow zoom (Ken Burns)", s.kenBurns, b -> { s.kenBurns = b; changed(); }));
        } else {
            page.addView(label(project.photos().length + " photo(s) · add, reorder or remove them in the Song tab", 12, DIM, false));
            page.addView(row(button("Add photos…", this::pickPhotos)));
            page.addView(label("Layout", 12, DIM, false));
            page.addView(chips(new String[]{"Fill frame", "Whole photo + blur"}, s.photoFit, 2, i -> { s.photoFit = i; changed(); }));
            page.addView(label("Blur (0 = sharp)", 12, DIM, false));
            page.addView(slider(0, 1, s.photoBlur, v -> { s.photoBlur = v; changed(); }));
            page.addView(label("Colour filter", 12, DIM, false));
            page.addView(chips(Style.PHOTO_FILTERS, s.photoFilter, 3, i -> { s.photoFilter = i; changed(); }));
            int secSel = 0;
            for (int i = 0; i < Style.PHOTO_SECONDS.length; i++) if (Style.PHOTO_SECONDS[i] == s.photoSeconds) secSel = i;
            page.addView(label("Slideshow timing", 12, DIM, false));
            page.addView(spinner(Style.PHOTO_SECONDS_NAMES, secSel, i -> { s.photoSeconds = Style.PHOTO_SECONDS[i]; changed(); }));
            page.addView(label("Darken for readable lyrics", 12, DIM, false));
            page.addView(slider(0, .85f, s.dim, v -> { s.dim = v; changed(); }));
            page.addView(toggle("Slow zoom & pan (Ken Burns)", s.kenBurns, b -> { s.kenBurns = b; changed(); }));
        }

        page.addView(heading("Extras"));
        page.addView(toggle("Audio visualizer (moves with the music)", s.visualizer, b -> { s.visualizer = b; changed(); }));
        page.addView(toggle("Song progress bar", s.progressBar, b -> { s.progressBar = b; changed(); }));
        page.addView(toggle("Title card before the first line", s.titleCard, b -> { s.titleCard = b; changed(); }));

        page.addView(heading("Video quality"));
        page.addView(chips(new String[]{"720p", "1080p HD", "4K"}, s.quality, 3, i -> { s.quality = i; changed(); if (i == 2) toast("4K needs a recent phone and takes several times longer to export. If the phone can't encode 4K, the export uses the best size it supports."); }));
        int[] rates = {24, 30, 60};
        page.addView(chips(new String[]{"24 fps film", "30 fps", "60 fps smooth"}, s.fps == 24 ? 0 : s.fps == 60 ? 2 : 1, 3, i -> { s.fps = rates[i]; changed(); }));
        page.addView(label(s.width() + " × " + s.height() + " · " + s.fps + " fps · H.264 + AAC", 12, DIM, false));
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
        if (speed != 1f) setSpeed(speed);
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
        refreshOverlay();
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
                + "About " + frames + " frames to draw. Keep the app open while it renders; expect a few minutes for a full song (faster at 720p / 30 fps"
                + (s.quality == 2 ? "; 4K takes several times longer" : "") + "). The video is saved to Movies/Lyric Videos." + warn;
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
            Throwable fail = null;
            Uri saved = null;
            try {
                PhotoSet set = new PhotoSet(p.style.background == 2 ? p.photos() : null, Math.max(p.style.width(), p.style.height()));
                Bitmap coverFull = p.style.cover || p.style.background == 3 ? PhotoSet.decode(p.cover(), 1600) : null;
                LyricRenderer r = LyricRenderer.snapshot(p, set, LyricRenderer.loadFont(p.font()), coverFull);
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
            finally { out.delete(); }
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
        if (syncOverlay != null) { closeSyncMode(); return; }
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

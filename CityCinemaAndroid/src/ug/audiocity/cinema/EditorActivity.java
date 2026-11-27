package ug.audiocity.cinema;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * City Cinema's editor: a timeline of clips from anywhere on the phone (trim, split, reorder, volume, colour grade with
 * automatic log LUTs, transitions), titles, a music track, live preview with sound, and export to one MP4.
 * The project autosaves.
 */
public class EditorActivity extends Activity {
  static final String TAG = "CityCinemaEditor";
  static final int BG = CinemaActivity.BG, TEXT = CinemaActivity.TEXT, DIM = CinemaActivity.DIM, ACCENT = CinemaActivity.ACCENT;
  static final int PICK_VIDEO = 11, PICK_MUSIC = 12, PICK_LUT = 13;

  private EditModel.Project project = new EditModel.Project();
  private volatile EditModel.Project shown = new EditModel.Project(); // snapshot drawn by the preview
  private volatile long playhead;
  private volatile boolean playing;
  private Object selected; // EditModel.Clip, Title, Music or null
  private String clipTab = "Edit";
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final ExecutorService jobs = Executors.newSingleThreadExecutor();
  private GLSurfaceView preview;
  private EditEngine.FrameRenderer renderer;
  private Timeline timeline;
  private LinearLayout inspector;
  private TextView timeView;
  private Button playButton;
  private Thread audioThread;
  private float density;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    density = getResources().getDisplayMetrics().density;
    loadProject();
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);

    LinearLayout top = new LinearLayout(this);
    preview = new GLSurfaceView(this);
    preview.setEGLContextClientVersion(3);
    preview.setRenderer(new PreviewRenderer());
    preview.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    top.addView(preview, new LinearLayout.LayoutParams(0, -1, 0.6f));
    ScrollView side = new ScrollView(this);
    inspector = new LinearLayout(this);
    inspector.setOrientation(LinearLayout.VERTICAL);
    inspector.setPadding(dp(10), dp(6), dp(10), dp(6));
    side.addView(inspector);
    top.addView(side, new LinearLayout.LayoutParams(0, -1, 0.4f));
    root.addView(top, new LinearLayout.LayoutParams(-1, 0, 1));

    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(6), dp(4), dp(6), dp(4));
    bar.addView(chip("‹", v -> finish()), lp(dp(40)));
    playButton = chip("▶", v -> togglePlay());
    bar.addView(playButton, lp(dp(50)));
    timeView = text("0:00.0 / 0:00.0", 13, TEXT);
    timeView.setTypeface(Typeface.MONOSPACE);
    timeView.setPadding(dp(8), 0, dp(8), 0);
    bar.addView(timeView);
    HorizontalScrollView actionsScroll = new HorizontalScrollView(this);
    LinearLayout actions = new LinearLayout(this);
    actions.addView(chip("+ VIDEO", v -> pick(PICK_VIDEO, "video/*")), lp(-2));
    actions.addView(chip("+ TITLE", v -> addTitle()), lp(-2));
    actions.addView(chip("+ MUSIC", v -> pick(PICK_MUSIC, "audio/*")), lp(-2));
    actions.addView(chip("SPLIT", v -> splitAtPlayhead()), lp(-2));
    actions.addView(chip("PROJECT", v -> select(null)), lp(-2));
    actions.addView(chip("NEW", v -> newProject()), lp(-2));
    Button export = chip("EXPORT", v -> exportDialog());
    export.setTextColor(BG);
    GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(6)); d.setColor(ACCENT); export.setBackground(d);
    actions.addView(export, lp(-2));
    actionsScroll.addView(actions);
    bar.addView(actionsScroll, new LinearLayout.LayoutParams(0, dp(38), 1));
    root.addView(bar);

    timeline = new Timeline(this);
    root.addView(timeline, new LinearLayout.LayoutParams(-1, dp(118)));
    setContentView(root);
    changed(false);
  }

  @Override protected void onResume() {
    super.onResume();
    preview.onResume();
    WindowInsetsController c = getWindow().getInsetsController();
    if (c != null) { c.hide(WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
  }

  @Override protected void onPause() {
    stopPlayback();
    saveProject();
    preview.queueEvent(() -> { if (renderer != null) renderer.release(); });
    preview.onPause();
    super.onPause();
  }

  // ------------------------------------------------------------------------------------------------ project

  private File projectFile() { File d = new File(getFilesDir(), "edit"); d.mkdirs(); return new File(d, "project.json"); }

  private void loadProject() {
    try {
      File f = projectFile();
      if (f.exists()) project = EditModel.Project.from(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
    } catch (Exception e) { Log.w(TAG, "load", e); }
    // clips without a log: look for their sidecar again (exact name first), else check the picture
    final java.util.List<EditModel.Clip> plain = new java.util.ArrayList<>();
    for (EditModel.Clip c : project.clips) if (!CinemaMath.convertsInPost(c.look) && !c.name.contains("_Rec709")) plain.add(c);
    if (!plain.isEmpty()) jobs.execute(() -> {
      for (EditModel.Clip c : plain) {
        String look = lookFromSidecar(c.name);
        if (look == null) { noSidecar.add(c.id); ui.post(() -> checkForLog(c)); }
        else if (CinemaMath.convertsInPost(look)) ui.post(() -> { c.look = look; changed(true); toast(c.name + ": " + look + " found → normal colour."); });
      }
    });
  }

  private void saveProject() {
    try (FileOutputStream o = new FileOutputStream(projectFile())) { o.write(project.json().toString().getBytes(StandardCharsets.UTF_8)); }
    catch (Exception e) { Log.w(TAG, "save", e); }
  }

  /** After any edit: new preview snapshot, timeline and inspector refresh, autosave. */
  private void changed(boolean save) {
    shown = EditModel.snapshot(project);
    long dur = EditModel.duration(project);
    if (playhead > dur) playhead = Math.max(0, dur - 1);
    timeline.invalidate();
    buildInspector();
    updateTime();
    preview.requestRender();
    if (save) saveProject();
  }

  private void newProject() {
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Start a new project?")
        .setMessage("The current timeline is cleared (your clips stay on the phone).")
        .setPositiveButton("New", (dlg, w) -> { stopPlayback(); project = new EditModel.Project(); selected = null; playhead = 0; changed(true); })
        .setNegativeButton("Cancel", null).show();
  }

  // ------------------------------------------------------------------------------------------------ adding media

  private void pick(int code, String type) {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType(type);
    if (code == PICK_VIDEO) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    startActivityForResult(i, code);
  }

  @Override protected void onActivityResult(int code, int result, Intent data) {
    super.onActivityResult(code, result, data);
    if (result != RESULT_OK || data == null) return;
    if (code == PICK_LUT) { importLut(data.getData()); return; }
    java.util.List<Uri> uris = new java.util.ArrayList<>();
    if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
    else if (data.getData() != null) uris.add(data.getData());
    for (Uri u : uris) {
      try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
    }
    jobs.execute(() -> {
      for (Uri u : uris) {
        if (code == PICK_VIDEO) {
          EditModel.Clip c = probeVideo(u);
          if (c != null) ui.post(() -> {
            // add after the selected clip, or at the end
            int at = selected instanceof EditModel.Clip ? project.clips.indexOf(selected) + 1 : project.clips.size();
            project.clips.add(Math.max(0, Math.min(at, project.clips.size())), c);
            selected = c; changed(true);
            checkForLog(c);
          });
          else ui.post(() -> toast("Couldn't read that video."));
        } else {
          EditModel.Music m = probeMusic(u);
          if (m != null) ui.post(() -> { project.music = m; selected = m; changed(true); });
        }
      }
    });
  }

  private EditModel.Clip probeVideo(Uri u) {
    MediaExtractor ex = new MediaExtractor();
    try {
      ex.setDataSource(this, u, null);
      EditModel.Clip c = new EditModel.Clip();
      c.uri = u.toString(); c.name = displayName(u); c.hasAudio = false;
      for (int i = 0; i < ex.getTrackCount(); i++) {
        MediaFormat f = ex.getTrackFormat(i);
        String m = f.getString(MediaFormat.KEY_MIME);
        if (m == null) continue;
        if (m.startsWith("video/") && c.width == 0) {
          c.width = f.getInteger(MediaFormat.KEY_WIDTH); c.height = f.getInteger(MediaFormat.KEY_HEIGHT);
          c.rotation = f.containsKey(MediaFormat.KEY_ROTATION) ? f.getInteger(MediaFormat.KEY_ROTATION) : 0;
          c.fps = f.containsKey(MediaFormat.KEY_FRAME_RATE) ? f.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
          c.sourceDurationUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 0;
        } else if (m.startsWith("audio/")) c.hasAudio = true;
      }
      if (c.width == 0 || c.sourceDurationUs <= 0) return null;
      if (c.name.endsWith(".mov") && c.width > 0 && !canDecode(ex)) return null;
      c.inUs = 0; c.outUs = c.sourceDurationUs;
      String look = lookFromSidecar(c.name);
      c.look = look != null ? look : "None";
      if (look == null) noSidecar.add(c.id);
      return c;
    } catch (Exception e) { Log.w(TAG, "probe", e); return null; } finally { ex.release(); }
  }

  private boolean canDecode(MediaExtractor ex) {
    for (int i = 0; i < ex.getTrackCount(); i++) {
      MediaFormat f = ex.getTrackFormat(i);
      String m = f.getString(MediaFormat.KEY_MIME);
      if (m != null && m.startsWith("video/")) return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null;
    }
    return false;
  }

  private EditModel.Music probeMusic(Uri u) {
    MediaExtractor ex = new MediaExtractor();
    try {
      ex.setDataSource(this, u, null);
      for (int i = 0; i < ex.getTrackCount(); i++) {
        MediaFormat f = ex.getTrackFormat(i);
        String m = f.getString(MediaFormat.KEY_MIME);
        if (m != null && m.startsWith("audio/")) {
          EditModel.Music music = new EditModel.Music();
          music.uri = u.toString(); music.name = displayName(u);
          music.sourceDurationUs = f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) : 600_000_000L;
          return music;
        }
      }
    } catch (Exception e) { Log.w(TAG, "music", e); } finally { ex.release(); }
    return null;
  }

  private String displayName(Uri u) {
    try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) return c.getString(0);
    } catch (Exception ignored) {}
    return u.getLastPathSegment();
  }

  /** City Cinema clips carry their look in a JSON sidecar: log clips get their LUT automatically. */
  private String lookFromSidecar(String name) {
    if (name.contains("_Rec709")) return "None"; // already converted to normal colour
    String tagged = CinemaMath.lookFromName(name); // City Cinema puts the log in the file name ("_SLOG3")
    if (tagged != null) return tagged;
    String exact = name.replaceAll("\\.(mp4|mov)$", "");
    String look = sidecarLook(exact);
    // copies made by file pickers get " (1)" etc. added: then try the original clip name
    if (look == null) look = sidecarLook(exact.replaceAll(" \\(\\d+\\)$", ""));
    return look; // null: no sidecar
  }

  /** The look in clip.json (null if there is no readable sidecar with that name). */
  private String sidecarLook(String clip) {
    Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
    try (Cursor c = getContentResolver().query(files, new String[] {MediaStore.Files.FileColumns._ID},
        MediaStore.Files.FileColumns.DISPLAY_NAME + " = ?", new String[] {clip + ".json"}, null)) {
      if (c != null && c.moveToFirst()) {
        Uri u = android.content.ContentUris.withAppendedId(files, c.getLong(0));
        try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
          if (in != null) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            String look = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8)).optString("look", "None");
            return CinemaMath.convertsInPost(look) ? look : "None";
          }
        }
      }
    } catch (Exception ignored) {}
    return null;
  }

  /**
   * No sidecar found (renamed or shared copies, or files made before a reinstall, which Android hides from the new
   * install): look at a frame, and if it looks like log footage, ask which log it was shot in.
   */
  private final java.util.Set<Long> noSidecar = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
  private final java.util.Set<Long> logChecked = new java.util.HashSet<>();

  private void checkForLog(EditModel.Clip c) {
    if (!noSidecar.contains(c.id) || CinemaMath.convertsInPost(c.look) || c.name.contains("_Rec709") || !logChecked.add(c.id)) return;
    jobs.execute(() -> {
      android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
      try {
        r.setDataSource(this, Uri.parse(c.uri));
        android.graphics.Bitmap bm = r.getScaledFrameAtTime(c.sourceDurationUs / 2, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180);
        if (bm == null) return;
        int[] px = new int[bm.getWidth() * bm.getHeight()];
        bm.getPixels(px, 0, bm.getWidth(), 0, 0, bm.getWidth(), bm.getHeight());
        bm.recycle();
        if (EditModel.looksLikeLog(px)) ui.post(() -> askLog(c));
      } catch (Exception e) { Log.w(TAG, "log check", e); }
      finally { try { r.release(); } catch (Exception ignored) {} }
    });
  }

  private void askLog(EditModel.Clip c) {
    if (isFinishing() || !project.clips.contains(c) || CinemaMath.convertsInPost(c.look)) return;
    String[] logs = {"City Log", "Sony S-Log3", "RED Log3G10", "BMD Film Gen 5", "Flat Log", "Flat (not log)", "It's not log"};
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("This clip looks like log footage")
        .setItems(logs, (d, i) -> {
          if (i < logs.length - 1) { c.look = logs[i].replace(" (not log)", ""); selected = c; clipTab = "Colour"; changed(true); toast(c.name + ": " + logs[i] + " → normal colour."); }
        })
        .setNegativeButton("Later", null).show();
    toast("No true blacks and washed-out colour: pick the log it was shot in to bring the colour back.");
  }

  /** Opens free LUT sites in the browser; downloaded .cube files come in with IMPORT .cube. */
  private void lutSources() {
    String[] names = {"FreeVisuals: free LUTs", "Uppbeat: free LUTs", "PremiumBeat: free LUT collections", "No Film School: 56 free cinematic LUTs"};
    String[] urls = {"https://www.freevisuals.net/free-luts", "https://uppbeat.io/luts", "https://www.premiumbeat.com/blog/29-free-luts-for-video/",
        "https://nofilmschool.com/download-these-free-56-cinematic-luts"};
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Get LUTs online")
        .setItems(names, (d, i) -> {
          try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(urls[i]))); } catch (Exception e) { toast("No browser found."); }
        })
        .setMessage(null).setNegativeButton("Close", null).show();
    toast("Download a .cube LUT in your browser (check the site's licence), unzip it if needed, then use IMPORT .cube here.");
  }

  /** Writes the built-in cinematic looks as .cube files to Documents/City Cinema/LUTs (for desktop editors). */
  private void exportLutPack() {
    jobs.execute(() -> {
      int ok = 0;
      for (String nm : CinematicLuts.NAMES) {
        try {
          ContentValues v = new ContentValues();
          v.put(MediaStore.MediaColumns.DISPLAY_NAME, "CityCinema_" + nm.replaceAll("[^A-Za-z0-9]+", "_") + ".cube");
          v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
          v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/City Cinema/LUTs");
          Uri u = getContentResolver().insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
          if (u == null) continue;
          try (java.io.OutputStream o = getContentResolver().openOutputStream(u)) { if (o != null) { o.write(CinematicLuts.cube(nm).getBytes(StandardCharsets.US_ASCII)); ok++; } }
        } catch (Exception e) { Log.w(TAG, "lut pack", e); }
      }
      final int n = ok;
      ui.post(() -> toast(n + " cinematic LUTs saved to Documents/City Cinema/LUTs (Rec.709 input), for Resolve, Premiere, Final Cut or CapCut desktop."));
    });
  }

  private void pickLut() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*"); // .cube has no standard type
    startActivityForResult(i, PICK_LUT);
  }

  /** Validates a .cube file and keeps a copy in the app's LUT folder, then applies it to the selected clip. */
  private void importLut(Uri u) {
    if (u == null) return;
    jobs.execute(() -> {
      String name = displayName(u);
      if (name == null) name = "LUT.cube";
      name = name.replaceAll("[^A-Za-z0-9 ._()+-]", "_");
      if (!name.toLowerCase(Locale.US).endsWith(".cube")) name += ".cube";
      try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
        if (in == null) throw new java.io.IOException("can't read the file");
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > 64 * 1024 * 1024) throw new java.io.IOException("file too large for a LUT"); }
        byte[] bytes = b.toByteArray();
        CubeLut lut = CubeLut.parse(new java.io.InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
        try (FileOutputStream o = new FileOutputStream(new File(EditEngine.lutDir(this), name))) { o.write(bytes); }
        final String file = name;
        final String info = (lut.title.isEmpty() ? name : lut.title) + " · " + lut.size + "³";
        ui.post(() -> {
          if (selected instanceof EditModel.Clip) { ((EditModel.Clip) selected).lut = file; }
          changed(true);
          toast("LUT imported: " + info + (selected instanceof EditModel.Clip ? " (applied to the selected clip)" : ""));
        });
      } catch (Exception e) {
        final String why = e.getMessage();
        ui.post(() -> toast("That isn't a usable .cube LUT: " + why));
      }
    });
  }

  private void addTitle() {
    EditModel.Title t = new EditModel.Title();
    t.startUs = playhead;
    t.text = "Title";
    project.titles.add(t);
    selected = t; changed(true);
    editTitleText(t);
  }

  private void editTitleText(EditModel.Title t) {
    EditText e = new EditText(this);
    e.setText(t.text); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Title text").setView(e)
        .setPositiveButton("OK", (d, w) -> { t.text = e.getText().toString(); changed(true); }).setNegativeButton("Cancel", null).show();
  }

  private void splitAtPlayhead() {
    long[] s = EditModel.starts(project);
    for (int i = 0; i < project.clips.size(); i++)
      if (playhead > s[i] && playhead < s[i] + project.clips.get(i).durationUs()) {
        if (EditModel.split(project, i, playhead)) { selected = project.clips.get(i + 1); changed(true); }
        return;
      }
    toast("Move the playhead over a clip to split it.");
  }

  // ------------------------------------------------------------------------------------------------ inspector

  private void select(Object o) { selected = o; buildInspector(); timeline.invalidate(); }

  private void buildInspector() {
    inspector.removeAllViews();
    if (selected instanceof EditModel.Clip && project.clips.contains(selected)) clipInspector((EditModel.Clip) selected);
    else if (selected instanceof EditModel.Title && project.titles.contains(selected)) titleInspector((EditModel.Title) selected);
    else if (selected instanceof EditModel.Music && selected == project.music) musicInspector((EditModel.Music) selected);
    else projectInspector();
  }

  private void clipInspector(EditModel.Clip c) {
    heading(c.name, (c.width + "×" + c.height) + " · " + fmt(c.durationUs()) + (CinemaMath.convertsInPost(c.look) ? " · " + c.look : ""));
    LinearLayout tabs = row();
    for (String t : new String[] {"Edit", "Colour", "Transition"}) tabs.addView(chip(t, v -> { clipTab = t; buildInspector(); }, clipTab.equals(t)), lp(-2));
    inspector.addView(tabs);
    int i = project.clips.indexOf(c);
    if (clipTab.equals("Edit")) {
      slider("Start (trim)", 0, c.sourceDurationUs, c.inUs, v -> { c.inUs = Math.min(v, c.outUs - 200_000); }, x -> fmt(x));
      slider("End (trim)", 0, c.sourceDurationUs, c.outUs, v -> { c.outUs = Math.max(v, c.inUs + 200_000); }, x -> fmt(x));
      slider("Volume", 0, 200, (long) Math.round(c.volume * 100), v -> c.volume = v / 100.0, x -> x + "%");
      LinearLayout r = row();
      r.addView(chip(c.muted ? "UNMUTE" : "MUTE", v -> { c.muted = !c.muted; changed(true); }, c.muted), lp(-2));
      r.addView(chip("◀ MOVE", v -> { if (i > 0) { project.clips.remove(i); project.clips.add(i - 1, c); changed(true); } }), lp(-2));
      r.addView(chip("MOVE ▶", v -> { if (i < project.clips.size() - 1) { project.clips.remove(i); project.clips.add(i + 1, c); changed(true); } }), lp(-2));
      inspector.addView(r);
      LinearLayout r2 = row();
      r2.addView(chip("DUPLICATE", v -> { EditModel.Clip k = c.copy(); k.id = EditModel.NEXT_ID.incrementAndGet(); project.clips.add(i + 1, k); selected = k; changed(true); }), lp(-2));
      r2.addView(chip("DELETE", v -> { project.clips.remove(c); selected = null; changed(true); }), lp(-2));
      inspector.addView(r2);
    } else if (clipTab.equals("Colour")) {
      EditModel.Grade g = c.grade;
      LinearLayout looks = row();
      for (String l : new String[] {"None", "City Log", "Flat Log", "Flat", "Sony S-Log3", "RED Log3G10", "BMD Film Gen 5"})
        looks.addView(chip("None".equals(l) ? "No LUT" : l, v -> { c.look = l; changed(true); }, l.equals(c.look)), lp(-2));
      inspector.addView(scroll(looks));
      label(CinemaMath.convertsInPost(c.look) ? (c.look.equals("Flat") ? "Flat profile" : "Log: " + c.look) + " → converted to normal colour."
          : "No log set. If the picture is grey and washed out, the clip was shot in log: pick its log above (City Cinema clips are set automatically).");
      slider("Exposure", -300, 300, Math.round(g.exposure * 100), v -> g.exposure = v / 100.0, x -> String.format(Locale.US, "%+.1f stops", x / 100.0));
      slider("Contrast", -100, 100, Math.round(g.contrast * 100), v -> g.contrast = v / 100.0, x -> x + "");
      slider("Saturation", -100, 100, Math.round(g.saturation * 100), v -> g.saturation = v / 100.0, x -> x + "");
      slider("Temperature", -100, 100, Math.round(g.temperature * 100), v -> g.temperature = v / 100.0, x -> (x > 0 ? "warmer " : x < 0 ? "cooler " : "") + Math.abs(x));
      slider("Tint", -100, 100, Math.round(g.tint * 100), v -> g.tint = v / 100.0, x -> (x > 0 ? "magenta " : x < 0 ? "green " : "") + Math.abs(x));
      slider("Shadows", -100, 100, Math.round(g.shadows * 100), v -> g.shadows = v / 100.0, x -> x + "");
      slider("Highlights", -100, 100, Math.round(g.highlights * 100), v -> g.highlights = v / 100.0, x -> x + "");
      // creative / custom LUT (.cube)
      label("Cinematic LUT" + (c.lut.isEmpty() ? "" : ": " + (LutPack.isPack(c.lut) ? LutPack.label(c.lut) : CinematicLuts.nameOf(c.lut).replaceAll("\\.cube$", ""))));
      LinearLayout lutRow = row();
      lutRow.addView(chip("None", v -> { c.lut = ""; changed(true); }, c.lut.isEmpty()), lp(-2));
      for (String nm : CinematicLuts.NAMES) { String key = CinematicLuts.key(nm); lutRow.addView(chip("★ " + nm, v -> { c.lut = key; changed(true); }, key.equals(c.lut)), lp(-2)); }
      for (String f : LutPack.list(getAssets())) { String key = LutPack.key(f); lutRow.addView(chip("◆ " + LutPack.label(key), v -> { c.lut = key; changed(true); }, key.equals(c.lut)), lp(-2)); }
      String[] files = EditEngine.lutDir(this).list();
      if (files != null) { java.util.Arrays.sort(files); for (String f : files) lutRow.addView(chip(f.replaceAll("\\.cube$", ""), v -> { c.lut = f; changed(true); }, f.equals(c.lut)), lp(-2)); }
      lutRow.addView(chip("IMPORT .cube", v -> pickLut()), lp(-2));
      lutRow.addView(chip("GET LUTS ONLINE", v -> lutSources()), lp(-2));
      lutRow.addView(chip("EXPORT PACK", v -> exportLutPack()), lp(-2));
      inspector.addView(scroll(lutRow));
      if (!c.lut.isEmpty()) {
        slider("LUT strength", 0, 100, Math.round(c.lutStrength * 100), v -> c.lutStrength = v / 100.0, x -> x + "%");
        if (CinemaMath.convertsInPost(c.look)) label("Note: the clip's log is converted first (" + c.look + "). If your LUT expects log input, set the log above to No LUT.");
      }
      LinearLayout films = row();
      for (int k = 0; k < EditModel.FILM_LOOKS.length; k++) { final int f = k; films.addView(chip(EditModel.FILM_LOOKS[k], v -> { g.film = f; changed(true); }, g.film == k), lp(-2)); }
      inspector.addView(scroll(films));
      if (g.film > 0) slider("Look strength", 0, 100, Math.round(g.filmStrength * 100), v -> g.filmStrength = v / 100.0, x -> x + "%");
      LinearLayout r = row();
      r.addView(chip("RESET", v -> { c.grade = new EditModel.Grade(); changed(true); }), lp(-2));
      r.addView(chip("COPY TO ALL CLIPS", v -> { for (EditModel.Clip k : project.clips) { k.grade = g.copy(); k.lut = c.lut; k.lutStrength = c.lutStrength; } changed(true); toast("Grade and LUT copied to every clip."); }), lp(-2));
      inspector.addView(r);
    } else {
      if (i == 0) { label("The first clip fades in from black when the project's fade in is on (PROJECT)."); return; }
      label("Into this clip from the one before:");
      LinearLayout r = row();
      for (int k = 0; k < EditModel.TRANSITIONS.length; k++) { final int t = k; r.addView(chip(EditModel.TRANSITIONS[k], v -> { c.transition = t; changed(true); }, c.transition == k), lp(-2)); }
      inspector.addView(r);
      if (c.transition > 0) slider("Length", 100, 3000, c.transitionUs / 1000, v -> c.transitionUs = v * 1000, x -> String.format(Locale.US, "%.1f s", x / 1000.0));
    }
  }

  private void titleInspector(EditModel.Title t) {
    heading("Title", fmt(t.startUs) + " for " + fmt(t.durationUs));
    LinearLayout r = row();
    r.addView(chip("EDIT TEXT", v -> editTitleText(t)), lp(-2));
    r.addView(chip("MOVE TO PLAYHEAD", v -> { t.startUs = playhead; changed(true); }), lp(-2));
    inspector.addView(r);
    LinearLayout p = row();
    for (int k = 0; k < EditModel.TITLE_PLACES.length; k++) { final int f = k; p.addView(chip(EditModel.TITLE_PLACES[k], v -> { t.place = f; changed(true); }, t.place == k), lp(-2)); }
    inspector.addView(p);
    slider("Duration", 500, 20_000, t.durationUs / 1000, v -> t.durationUs = v * 1000, x -> String.format(Locale.US, "%.1f s", x / 1000.0));
    slider("Size", 50, 250, Math.round(t.size * 100), v -> t.size = v / 100.0, x -> x + "%");
    LinearLayout colours = row();
    int[] cs = {0xffffffff, 0xffffb341, 0xff53e07a, 0xff4fc3f7, 0xffff5252, 0xff000000};
    String[] names = {"White", "Amber", "Green", "Blue", "Red", "Black"};
    for (int k = 0; k < cs.length; k++) { final int col = cs[k]; colours.addView(chip(names[k], v -> { t.color = col; changed(true); }, t.color == col), lp(-2)); }
    inspector.addView(scroll(colours));
    LinearLayout r2 = row();
    r2.addView(chip(t.box ? "BOX ON" : "BOX OFF", v -> { t.box = !t.box; changed(true); }, t.box), lp(-2));
    r2.addView(chip("DELETE", v -> { project.titles.remove(t); selected = null; changed(true); }), lp(-2));
    inspector.addView(r2);
  }

  private void musicInspector(EditModel.Music m) {
    heading("Music: " + m.name, "starts at " + fmt(m.startUs) + " · " + fmt(m.sourceDurationUs) + " long");
    slider("Volume", 0, 150, Math.round(m.volume * 100), v -> m.volume = v / 100.0, x -> x + "%");
    slider("Fade in", 0, 10_000, m.fadeInUs / 1000, v -> m.fadeInUs = v * 1000, x -> String.format(Locale.US, "%.1f s", x / 1000.0));
    slider("Fade out", 0, 10_000, m.fadeOutUs / 1000, v -> m.fadeOutUs = v * 1000, x -> String.format(Locale.US, "%.1f s", x / 1000.0));
    LinearLayout r = row();
    r.addView(chip("START AT PLAYHEAD", v -> { m.startUs = playhead; changed(true); }), lp(-2));
    r.addView(chip("REMOVE", v -> { project.music = null; selected = null; changed(true); }), lp(-2));
    inspector.addView(r);
    label("Tip: lower the clips' volume (select a clip → Edit) to make the music the main sound.");
  }

  private void projectInspector() {
    heading("Project", project.clips.size() + " clips · " + fmt(EditModel.duration(project)) + (project.music != null ? " · music" : "") + (project.titles.isEmpty() ? "" : " · " + project.titles.size() + " titles"));
    LinearLayout r = row();
    r.addView(chip(project.fadeIn ? "FADE IN ✓" : "FADE IN", v -> { project.fadeIn = !project.fadeIn; changed(true); }, project.fadeIn), lp(-2));
    r.addView(chip(project.fadeOut ? "FADE OUT ✓" : "FADE OUT", v -> { project.fadeOut = !project.fadeOut; changed(true); }, project.fadeOut), lp(-2));
    inspector.addView(r);
    slider("Fade length", 100, 3000, project.fadeUs / 1000, v -> project.fadeUs = v * 1000, x -> String.format(Locale.US, "%.1f s", x / 1000.0));
    label("Tap a clip, title or the music on the timeline to edit it. Drag the timeline to move the playhead (the white line).");
    if (project.clips.isEmpty()) label("Start with + VIDEO: pick clips from anywhere on the phone (City Cinema, camera, downloads).");
  }

  // ------------------------------------------------------------------------------------------------ preview & playback

  final class PreviewRenderer implements GLSurfaceView.Renderer {
    private int w = 1, h = 1;
    @Override public void onSurfaceCreated(GL10 gl, EGLConfig c) { renderer = new EditEngine.FrameRenderer(EditorActivity.this); renderer.init(); }
    @Override public void onSurfaceChanged(GL10 gl, int width, int height) { w = width; h = height; }
    @Override public void onDrawFrame(GL10 gl) {
      EditModel.Project p = shown;
      int[] out = outputSize(p);
      double scale = Math.min(w / (double) out[0], h / (double) out[1]);
      int vw = (int) (out[0] * scale), vh = (int) (out[1] * scale);
      if (p.clips.isEmpty()) { android.opengl.GLES30.glClearColor(0, 0, 0, 1); android.opengl.GLES30.glClear(android.opengl.GLES30.GL_COLOR_BUFFER_BIT); return; }
      renderer.render(p, Math.min(playhead, Math.max(0, EditModel.duration(p) - 1)), (w - vw) / 2, (h - vh) / 2, vw, vh);
    }
  }

  /** Output size: the first clip's displayed size (rotation aware), even, at most 4K wide. */
  static int[] outputSize(EditModel.Project p) {
    if (p.clips.isEmpty()) return new int[] {1920, 1080};
    EditModel.Clip c = p.clips.get(0);
    int w = c.rotation % 180 == 0 ? c.width : c.height, h = c.rotation % 180 == 0 ? c.height : c.width;
    if (w > 3840) { h = h * 3840 / w; w = 3840; }
    return new int[] {w & ~1, h & ~1};
  }

  private void togglePlay() { if (playing) stopPlayback(); else startPlayback(); }

  private void startPlayback() {
    if (project.clips.isEmpty()) return;
    if (playhead >= EditModel.duration(project) - 100_000) playhead = 0;
    playing = true;
    playButton.setText("❚❚");
    preview.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    final long start = playhead;
    final EditModel.Project p = shown;
    audioThread = new Thread(() -> {
      EditEngine.Mixer mixer = new EditEngine.Mixer(EditorActivity.this);
      int min = AudioTrack.getMinBufferSize(48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
      AudioTrack track = new AudioTrack.Builder()
          .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
          .setAudioFormat(new AudioFormat.Builder().setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
          .setBufferSizeInBytes(Math.max(min, 4096 * 8)).setTransferMode(AudioTrack.MODE_STREAM).build();
      float[] buf = new float[1024 * 2];
      long written = 0, end = EditModel.duration(p);
      track.play();
      try {
        while (playing) {
          long t = start + written * 1_000_000L / 48_000;
          if (t >= end) break;
          mixer.mix(p, t, 1024, buf);
          track.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
          written += 1024;
          // the picture follows the sound actually heard
          playhead = start + track.getPlaybackHeadPosition() * 1_000_000L / 48_000;
        }
        while (playing && track.getPlaybackHeadPosition() < written - 512) { playhead = start + track.getPlaybackHeadPosition() * 1_000_000L / 48_000; Thread.sleep(10); }
      } catch (Exception e) { Log.w(TAG, "playback", e); }
      finally { track.stop(); track.release(); mixer.release(); ui.post(() -> { if (playing) stopPlayback(); }); }
    }, "editor-audio");
    audioThread.start();
    ui.post(followPlayhead);
  }

  private void stopPlayback() {
    playing = false;
    if (audioThread != null) { try { audioThread.join(500); } catch (InterruptedException ignored) {} audioThread = null; }
    if (playButton != null) playButton.setText("▶");
    preview.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    preview.requestRender();
  }

  private final Runnable followPlayhead = new Runnable() {
    @Override public void run() { updateTime(); timeline.invalidate(); if (playing) ui.postDelayed(this, 40); }
  };

  private void updateTime() { timeView.setText(fmt(playhead) + " / " + fmt(EditModel.duration(project))); }

  // ------------------------------------------------------------------------------------------------ export

  private void exportDialog() {
    if (project.clips.isEmpty()) { toast("Add a video first."); return; }
    stopPlayback();
    int[] src = outputSize(project);
    String[] sizes = {"Source " + src[0] + "×" + src[1], "4K 3840×2160", "1080p 1920×1080", "720p 1280×720"};
    double fps0 = project.clips.get(0).fps;
    double[] rates = {fps0, 24, 25, 30, 60};
    String[] rateNames = {"Source (" + CinemaMath.fpsLabel(fps0) + ")", "24", "25", "30", "60"};
    final int[] sel = {0, 0, 0};
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16), dp(8), dp(16), dp(8));
    box.addView(text("Size", 13, DIM));
    box.addView(scroll(choiceRow(sizes, sel, 0)));
    box.addView(text("Frame rate", 13, DIM));
    box.addView(scroll(choiceRow(rateNames, sel, 1)));
    box.addView(text("Codec", 13, DIM));
    box.addView(scroll(choiceRow(new String[] {"HEVC (smaller)", "H.264 (most compatible)"}, sel, 2)));
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Export").setView(box)
        .setPositiveButton("Export", (d, w) -> {
          EditModel.Project p = EditModel.snapshot(project);
          EditEngine.ExportSettings s = new EditEngine.ExportSettings();
          int[][] dims = {src, {3840, 2160}, {1920, 1080}, {1280, 720}};
          s.width = dims[sel[0]][0]; s.height = dims[sel[0]][1];
          if (sel[0] > 0 && src[1] > src[0]) { int t = s.width; s.width = s.height; s.height = t; } // keep portrait projects portrait
          s.fps = rates[sel[1]]; s.hevc = sel[2] == 0;
          CinemaRecorder.EncoderCaps caps = CinemaRecorder.caps(s.hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC);
          s.bitrate = CinemaMath.bitrate(s.width, s.height, s.fps, 2, s.hevc, false, false);
          if (caps.available) s.bitrate = Math.min(s.bitrate, caps.maxBitrate);
          runExport(p, s);
        }).setNegativeButton("Cancel", null).show();
  }

  /** A row of chips where one is chosen (highlighted); the choice is written to sel[slot]. */
  private LinearLayout choiceRow(String[] names, int[] sel, int slot) {
    LinearLayout r = row();
    Button[] bs = new Button[names.length];
    for (int i = 0; i < names.length; i++) {
      final int k = i;
      bs[i] = chip(names[i], v -> {
        sel[slot] = k;
        for (int j = 0; j < bs.length; j++) styleChip(bs[j], j == k);
      }, sel[slot] == i);
      r.addView(bs[i], lp(-2));
    }
    return r;
  }

  private void styleChip(Button b, boolean active) {
    b.setTextColor(active ? BG : TEXT);
    GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(6)); d.setColor(active ? ACCENT : 0xff22272c); d.setStroke(dp(1), active ? ACCENT : 0xff2a3036);
    b.setBackground(d);
  }

  private void runExport(EditModel.Project p, EditEngine.ExportSettings s) {
    TextView status = text("Preparing…", 14, TEXT);
    status.setPadding(dp(20), dp(16), dp(20), dp(16));
    AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Exporting")
        .setView(status).setCancelable(false).show();
    getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    new Thread(() -> {
      String name = "Edit_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
      ContentValues v = new ContentValues();
      v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
      v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
      v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/City Cinema");
      v.put(MediaStore.MediaColumns.IS_PENDING, 1);
      Uri out = null;
      String msg;
      try {
        out = getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (out == null) throw new java.io.IOException("couldn't create the file");
        try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(out, "rw")) {
          EditEngine.export(this, p, s, fd.getFileDescriptor(), (f, stage) -> ui.post(() -> status.setText(String.format(Locale.US, "%s … %d%%", stage, Math.round(f * 100)))));
        }
        ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContentResolver().update(out, done, null, null);
        msg = "Saved " + name + " in Movies/City Cinema (" + s.width + "×" + s.height + ", " + CinemaMath.fpsLabel(s.fps) + " fps, " + (s.hevc ? "HEVC" : "H.264") + ").";
      } catch (Exception e) {
        Log.w(TAG, "export", e);
        if (out != null) try { getContentResolver().delete(out, null, null); } catch (RuntimeException ignored) {}
        msg = "Export failed: " + e.getMessage();
      }
      final String m = msg;
      ui.post(() -> { dialog.dismiss(); getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); toast(m); preview.requestRender(); });
    }, "editor-export").start();
  }

  // ------------------------------------------------------------------------------------------------ timeline view

  /** Clips, titles and music on a scrolling timeline; the playhead stays in the centre, drag to move through time. */
  final class Timeline extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float pxPerSecond = 60, lastX, downX;
    private boolean dragging;
    private final android.view.ScaleGestureDetector zoom;

    Timeline(Context c) {
      super(c);
      setBackgroundColor(0xff101316);
      zoom = new android.view.ScaleGestureDetector(c, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        @Override public boolean onScale(android.view.ScaleGestureDetector d) { pxPerSecond = Math.max(8, Math.min(600, pxPerSecond * d.getScaleFactor())); invalidate(); return true; }
      });
    }

    private float x(long us) { return getWidth() / 2f + (us - playhead) / 1e6f * pxPerSecond * density / 2; }

    @Override protected void onDraw(Canvas c) {
      float h = getHeight(), rowV = dp(6), rowVh = dp(52), rowT = rowV + rowVh + dp(4), rowTh = dp(22), rowM = rowT + rowTh + dp(4), rowMh = dp(22);
      p.setTextSize(dp(10)); p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
      long[] s = EditModel.starts(project);
      for (int i = 0; i < project.clips.size(); i++) {
        EditModel.Clip k = project.clips.get(i);
        RectF r = new RectF(x(s[i]) + 1, rowV, x(s[i] + k.durationUs()) - 1, rowV + rowVh);
        p.setColor(CinemaMath.convertsInPost(k.look) ? 0xff3a4a5e : 0xff34503f);
        c.drawRoundRect(r, dp(4), dp(4), p);
        if (k.transition > 0 && i > 0) { p.setColor(0x66ffffff); float tx = x(s[i]); c.drawRect(tx, rowV, x(s[i] + EditModel.transitionUs(project, i)), rowV + rowVh, p); }
        if (k == selected) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(ACCENT); c.drawRoundRect(r, dp(4), dp(4), p); p.setStyle(Paint.Style.FILL); }
        p.setColor(TEXT);
        c.save(); c.clipRect(r);
        c.drawText(k.name, r.left + dp(5), rowV + dp(14), p);
        p.setColor(DIM);
        c.drawText(fmt(k.durationUs()) + (k.muted ? " · muted" : "") + (CinemaMath.convertsInPost(k.look) ? " · " + k.look : "") + (k.grade.neutral() ? "" : " · graded"), r.left + dp(5), rowV + dp(28), p);
        c.restore();
      }
      for (EditModel.Title t : project.titles) {
        RectF r = new RectF(x(t.startUs), rowT, x(t.startUs + t.durationUs), rowT + rowTh);
        p.setColor(t == selected ? ACCENT : 0xff6a5a2e); c.drawRoundRect(r, dp(3), dp(3), p);
        p.setColor(t == selected ? BG : TEXT); c.save(); c.clipRect(r); c.drawText("T  " + t.text.replace('\n', ' '), r.left + dp(4), rowT + dp(15), p); c.restore();
      }
      if (project.music != null) {
        EditModel.Music m = project.music;
        RectF r = new RectF(x(m.startUs), rowM, x(Math.min(m.startUs + m.sourceDurationUs, EditModel.duration(project))), rowM + rowMh);
        p.setColor(m == selected ? ACCENT : 0xff5a3a6a); c.drawRoundRect(r, dp(3), dp(3), p);
        p.setColor(m == selected ? BG : TEXT); c.save(); c.clipRect(r); c.drawText("♪  " + m.name, r.left + dp(4), rowM + dp(15), p); c.restore();
      }
      p.setColor(TEXT);
      c.drawRect(getWidth() / 2f - dp(1), 0, getWidth() / 2f + dp(1), h, p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
      zoom.onTouchEvent(e);
      if (e.getPointerCount() > 1) { dragging = true; return true; }
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: lastX = downX = e.getX(); dragging = false; return true;
        case MotionEvent.ACTION_MOVE:
          if (Math.abs(e.getX() - downX) > dp(6)) dragging = true;
          if (dragging) {
            if (playing) stopPlayback();
            long d = (long) ((lastX - e.getX()) / (pxPerSecond * density / 2) * 1e6);
            playhead = Math.max(0, Math.min(Math.max(0, EditModel.duration(project) - 1), playhead + d));
            lastX = e.getX(); updateTime(); invalidate(); preview.requestRender();
          }
          return true;
        case MotionEvent.ACTION_UP:
          if (!dragging) tap(e.getX(), e.getY());
          return true;
        default: return true;
      }
    }

    private void tap(float tx, float ty) {
      long t = (long) (playhead + (tx - getWidth() / 2f) / (pxPerSecond * density / 2) * 1e6);
      float rowT = dp(6) + dp(52) + dp(4), rowM = rowT + dp(22) + dp(4);
      Object hit = null;
      if (ty < rowT) { long[] s = EditModel.starts(project); for (int i = project.clips.size() - 1; i >= 0 && hit == null; i--) if (t >= s[i] && t < s[i] + project.clips.get(i).durationUs()) hit = project.clips.get(i); }
      else if (ty < rowM) { for (EditModel.Title k : project.titles) if (t >= k.startUs && t < k.startUs + k.durationUs) hit = k; }
      else if (project.music != null && t >= project.music.startUs && t < project.music.startUs + project.music.sourceDurationUs) hit = project.music;
      select(hit);
    }
  }

  // ------------------------------------------------------------------------------------------------ small UI helpers

  interface LongSetter { void set(long v); }
  interface Label { String of(long v); }

  private void slider(String name, long min, long max, long value, LongSetter set, Label label) {
    TextView t = text(name + ": " + label.of(value), 12, DIM);
    inspector.addView(t);
    SeekBar s = new SeekBar(this);
    final long range = Math.max(1, max - min);
    final int steps = (int) Math.min(1000, range);
    s.setMax(steps);
    s.setProgress((int) Math.round((value - min) * (double) steps / range));
    s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar b, int pr, boolean user) {
        if (!user) return;
        long v = min + Math.round(pr * (double) range / steps);
        set.set(v);
        t.setText(name + ": " + label.of(v));
        shown = EditModel.snapshot(project);
        preview.requestRender(); timeline.invalidate();
      }
      @Override public void onStartTrackingTouch(SeekBar b) {}
      @Override public void onStopTrackingTouch(SeekBar b) { changed(true); }
    });
    inspector.addView(s, new LinearLayout.LayoutParams(-1, dp(30)));
  }

  private void heading(String title, String sub) {
    TextView t = text(title, 15, TEXT); inspector.addView(t);
    TextView s = text(sub, 11, DIM); s.setPadding(0, 0, 0, dp(6)); inspector.addView(s);
  }

  private void label(String s) { TextView t = text(s, 11, DIM); t.setSingleLine(false); t.setPadding(0, dp(4), 0, dp(4)); inspector.addView(t); }

  private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setPadding(0, dp(3), 0, dp(3)); return r; }

  private HorizontalScrollView scroll(View v) { HorizontalScrollView s = new HorizontalScrollView(this); s.setHorizontalScrollBarEnabled(false); s.addView(v); return s; }

  private LinearLayout.LayoutParams lp(int w) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, dp(36)); l.rightMargin = dp(5); return l; }

  private int dp(float v) { return Math.round(v * density); }

  private TextView text(String s, float sp, int color) {
    TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setSingleLine();
    t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)); return t;
  }

  private Button chip(String s, View.OnClickListener l) { return chip(s, l, false); }

  private Button chip(String s, View.OnClickListener l, boolean active) {
    Button b = new Button(this);
    b.setText(s); b.setAllCaps(false); b.setTextSize(12); b.setTextColor(active ? BG : TEXT);
    b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(10), 0, dp(10), 0);
    GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(6)); d.setColor(active ? ACCENT : 0xff22272c); d.setStroke(dp(1), active ? ACCENT : 0xff2a3036);
    b.setBackground(d); b.setStateListAnimator(null); b.setOnClickListener(l);
    return b;
  }

  private void toast(String msg) {
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setMessage(msg).setPositiveButton("OK", null).show();
  }

  static String fmt(long us) {
    long tenths = Math.max(0, us) / 100_000;
    return String.format(Locale.US, "%d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10);
  }
}

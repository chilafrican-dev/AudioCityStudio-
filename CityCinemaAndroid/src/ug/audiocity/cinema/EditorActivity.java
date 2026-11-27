package ug.audiocity.cinema;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
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
  static final int PICK_VIDEO = 11, PICK_MUSIC = 12, PICK_LUT = 13, PICK_LAYER = 14, PICK_FX = 15;

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

  /** Log clips stay flat (for your own grade) unless this is on. */
  private boolean convertLog() { return getSharedPreferences("editor", MODE_PRIVATE).getBoolean("convertLog", false); }

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
    actions.addView(chip("+ LAYER", v -> pick(PICK_LAYER, "video/*")), lp(-2));
    actions.addView(chip("+ TITLE", v -> addTitle()), lp(-2));
    actions.addView(chip("+ MUSIC", v -> pick(PICK_MUSIC, "audio/*")), lp(-2));
    actions.addView(chip("+ FX", v -> pick(PICK_FX, "audio/*")), lp(-2));
    voiceButton = chip("● VOICE", v -> toggleVoice());
    actions.addView(voiceButton, lp(-2));
    actions.addView(chip("SPLIT", v -> splitAtPlayhead()), lp(-2));
    actions.addView(chip("DELETE", v -> deleteSelected()), lp(-2));
    actions.addView(chip("COPY", v -> copyItems()), lp(-2));
    actions.addView(chip("PASTE", v -> pasteItems()), lp(-2));
    actions.addView(chip("UNDO", v -> undo()), lp(-2));
    actions.addView(chip("REDO", v -> redo()), lp(-2));
    actions.addView(chip("MIX", v -> select(MIX)), lp(-2));
    actions.addView(chip("◆ MARK", v -> { snapshot(); boolean added = EditModel.toggleMarker(project, playhead); changed(true); if (!added) toast("Marker removed."); }), lp(-2));
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
    media = new MediaCache(this, () -> ui.post(timeline::invalidate));
    root.addView(timeline, new LinearLayout.LayoutParams(-1, dp(200)));
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
    // clips without a log: find the log they were shot in (file name tag, then sidecar). With "convert" on, convert
    // them (asking when unsure); otherwise only note it: log clips stay flat for your own grade.
    final boolean convert = convertLog();
    for (EditModel.Clip c : project.clips) if (CinemaMath.convertsInPost(c.look) || !c.shotIn.isEmpty()) verifyLog(c);
    final java.util.List<EditModel.Clip> plain = new java.util.ArrayList<>();
    for (EditModel.Clip c : project.clips) if (!CinemaMath.convertsInPost(c.look) && !c.name.contains("_Rec709") && (convert || c.shotIn.isEmpty())) plain.add(c);
    if (!plain.isEmpty()) jobs.execute(() -> {
      for (EditModel.Clip c : plain) {
        String look = lookFromSidecar(c.name);
        if (look == null) { if (convert) { noSidecar.add(c.id); ui.post(() -> checkForLog(c)); } }
        else if (CinemaMath.convertsInPost(look)) ui.post(() -> { c.shotIn = look; if (convert) { c.look = look; toast(c.name + ": " + look + " → normal colour."); } changed(true); });
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
    if (code == PICK_VIDEO || code == PICK_LAYER) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
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
        if (code == PICK_LAYER) {
          EditModel.Clip c = probeVideo(u);
          if (c != null) ui.post(() -> {
            snapshot();
            c.startUs = playhead;
            c.track = freeTrack(c.startUs, c.durationUs());
            project.overlays.add(c);
            selected = c; changed(true);
            verifyLog(c);
            toast("Added as layer " + c.track + " at the playhead. Drag it on the timeline to move it; in Edit use Zoom below 100% for picture-in-picture and Opacity to blend.");
          });
          else ui.post(() -> toast("Couldn't read that video."));
        } else if (code == PICK_VIDEO) {
          EditModel.Clip c = probeVideo(u);
          if (c != null) ui.post(() -> {
            // add after the selected clip, or at the end
            int at = selected instanceof EditModel.Clip ? project.clips.indexOf(selected) + 1 : project.clips.size();
            project.clips.add(Math.max(0, Math.min(at, project.clips.size())), c);
            selected = c; changed(true);
            if (noSidecar.contains(c.id) && !c.name.contains("_Rec709")) checkForLog(c); // only when "convert log" is on
            verifyLog(c);
          });
          else ui.post(() -> toast("Couldn't read that video."));
        } else {
          EditModel.Music m = probeMusic(u);
          if (m != null) ui.post(() -> {
            snapshot();
            m.track = code == PICK_FX ? 2 : 0;
            m.startUs = code == PICK_FX ? playhead : (project.audio.isEmpty() ? 0 : playhead);
            if (code == PICK_FX) { m.volume = 1; m.fadeInUs = 0; m.fadeOutUs = 50_000; }
            project.audio.add(m); selected = m; changed(true);
          });
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
      c.shotIn = look != null && CinemaMath.convertsInPost(look) ? look : "";
      c.look = look != null && convertLog() ? look : "None"; // log stays flat unless "convert" is on
      if (look == null && convertLog()) noSidecar.add(c.id);
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

  /**
   * Automatic colour correction (exposure, white balance, contrast) from a frame in the middle of the clip, after its
   * log conversion. Looks, LUTs and saturation are left as they are; RESET undoes it.
   */
  private void autoColour(EditModel.Clip c, boolean quiet) {
    jobs.execute(() -> {
      android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
      try {
        r.setDataSource(this, Uri.parse(c.uri));
        android.graphics.Bitmap bm = r.getScaledFrameAtTime((c.inUs + c.outUs) / 2, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180);
        if (bm == null) return;
        int[] px = new int[bm.getWidth() * bm.getHeight()];
        bm.getPixels(px, 0, bm.getWidth(), 0, 0, bm.getWidth(), bm.getHeight());
        bm.recycle();
        String look = c.look;
        if (CinemaMath.convertsInPost(look)) {
          for (int i = 0; i < px.length; i++) {
            int p = px[i];
            double[] o = CinemaMath.logToRec709(look, ((p >> 16) & 255) / 255.0, ((p >> 8) & 255) / 255.0, (p & 255) / 255.0);
            px[i] = 0xff000000 | ((int) Math.round(CinemaMath.clamp01(o[0]) * 255) << 16) | ((int) Math.round(CinemaMath.clamp01(o[1]) * 255) << 8) | (int) Math.round(CinemaMath.clamp01(o[2]) * 255);
          }
        }
        double[] a = EditModel.autoGrade(px);
        ui.post(() -> {
          if (!project.clips.contains(c) || !look.equals(c.look)) return;
          EditModel.applyAuto(c.grade, a);
          changed(true);
          if (!quiet) toast(String.format(Locale.US, "Auto colour: exposure %+.1f, temperature %+.0f, tint %+.0f, contrast %+.0f. RESET undoes it.", a[0], a[2] * 100, a[3] * 100, a[1] * 100));
        });
      } catch (Exception e) { Log.w(TAG, "auto colour", e); if (!quiet) ui.post(() -> toast("Couldn't read a frame for auto colour.")); }
      finally { try { r.release(); } catch (Exception ignored) {} }
    });
  }

  /**
   * A clip named or noted as log whose picture goes brighter than that log can record wasn't really recorded in log
   * (the phone applied its own processing): treat it as normal video so it isn't converted twice.
   */
  private void verifyLog(EditModel.Clip c) {
    final String log = CinemaMath.convertsInPost(c.look) ? c.look : c.shotIn;
    if (!CinemaMath.convertsInPost(log)) return;
    jobs.execute(() -> {
      android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
      try {
        r.setDataSource(this, Uri.parse(c.uri));
        boolean bad = false;
        for (double f : new double[] {0.25, 0.5, 0.75}) {
          android.graphics.Bitmap bm = r.getScaledFrameAtTime(c.inUs + (long) ((c.outUs - c.inUs) * f), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180);
          if (bm == null) continue;
          int[] px = new int[bm.getWidth() * bm.getHeight()];
          bm.getPixels(px, 0, bm.getWidth(), 0, 0, bm.getWidth(), bm.getHeight());
          bm.recycle();
          if (CinemaMath.exceedsLook(px, log)) { bad = true; break; }
        }
        if (bad) ui.post(() -> {
          c.shotIn = ""; if (log.equals(c.look)) c.look = "None";
          changed(true);
          toast(c.name + " is named " + log + " but wasn't recorded in log (the phone used its own processing), so it's treated as normal video.");
        });
      } catch (Exception e) { Log.w(TAG, "verify log", e); }
      finally { try { r.release(); } catch (Exception ignored) {} }
    });
  }

  // ================================================================================================ colour grading

  private String colourTab = "BASIC";
  /** BEFORE: the preview shows clips with only their technical log conversion (no grade, creative LUT or look). */
  private volatile boolean beforeView;

  /** Redraws the preview from the edited project right away (while a control is being moved). */
  private void live() { shown = EditModel.snapshot(project); preview.requestRender(); timeline.invalidate(); }

  private void colourInspector(EditModel.Clip c) {
    EditModel.Grade g = c.grade;
    LinearLayout top = row();
    for (String t : new String[] {"BASIC", "WHEELS", "CURVES", "HSL", "LUT"}) top.addView(chip(t, v -> { colourTab = t; buildInspector(); }, colourTab.equals(t)), lp(-2));
    top.addView(chip(beforeView ? "BEFORE ◐" : "AFTER ◑", v -> { beforeView = !beforeView; preview.requestRender(); buildInspector(); }, beforeView), lp(-2));
    inspector.addView(scroll(top));
    if (beforeView) label("Showing BEFORE: only the log conversion. Tap BEFORE ◐ again to see the grade.");
    switch (colourTab) {
      case "WHEELS": wheelsTab(g); break;
      case "CURVES": curvesTab(g); break;
      case "HSL": hslTab(g); break;
      case "LUT": lutTab(c); break;
      default: basicTab(c, g); break;
    }
    LinearLayout r = row();
    r.addView(chip("AUTO COLOUR", v -> autoColour(c, false)), lp(-2));
    r.addView(chip("AUTO ALL", v -> { for (EditModel.Clip k : project.clips) autoColour(k, true); toast("Auto colour on every clip."); }), lp(-2));
    r.addView(chip("RESET GRADE", v -> { snapshot(); c.grade = new EditModel.Grade(); changed(true); }), lp(-2));
    r.addView(chip("COPY TO ALL CLIPS", v -> { snapshot(); for (EditModel.Clip k : project.clips) { k.grade = g.copy(); k.lut = c.lut; k.lutStrength = c.lutStrength; } changed(true); toast("Grade and LUT copied to every clip."); }), lp(-2));
    inspector.addView(scroll(r));
  }

  private void basicTab(EditModel.Clip c, EditModel.Grade g) {
    LinearLayout looks = row();
    for (String l : new String[] {"None", "City Log", "Flat Log", "Flat", "Sony S-Log3", "RED Log3G10", "BMD Film Gen 5"})
      looks.addView(chip("None".equals(l) ? "No LUT" : l, v -> { c.look = l; changed(true); }, l.equals(c.look)), lp(-2));
    inspector.addView(scroll(looks));
    if (CinemaMath.convertsInPost(c.look)) label((c.look.equals("Flat") ? "Flat profile" : "Log: " + c.look) + " → converted to normal colour. Tap No LUT to keep it flat.");
    else if (!c.shotIn.isEmpty()) label("Shot in " + c.shotIn + ": kept flat for your grade. Grade it by hand, use a LUT made for " + c.shotIn + ", or tap " + c.shotIn + " above to convert it to normal colour.");
    else label("Shot in log somewhere else? Pick its log above to convert it, or leave No LUT to keep it flat.");
    LinearLayout conv = row();
    boolean cv = convertLog();
    conv.addView(chip(cv ? "NEW LOG CLIPS: CONVERT" : "NEW LOG CLIPS: STAY FLAT", v -> {
      getSharedPreferences("editor", MODE_PRIVATE).edit().putBoolean("convertLog", !cv).apply();
      toast(!cv ? "Log clips you add will be converted to normal colour." : "Log clips you add will stay flat for your own grade.");
      buildInspector();
    }, cv), lp(-2));
    inspector.addView(conv);
    slider("Exposure", -300, 300, Math.round(g.exposure * 100), v -> g.exposure = v / 100.0, x -> String.format(Locale.US, "%+.1f stops", x / 100.0));
    slider("Contrast", -100, 100, Math.round(g.contrast * 100), v -> g.contrast = v / 100.0, x -> x + "");
    slider("Contrast pivot", 20, 80, Math.round(g.pivot * 100), v -> g.pivot = v / 100.0, x -> x + "%");
    slider("Saturation", -100, 100, Math.round(g.saturation * 100), v -> g.saturation = v / 100.0, x -> x + "");
    slider("Vibrance", -100, 100, Math.round(g.vibrance * 100), v -> g.vibrance = v / 100.0, x -> x + " (muted colours more)");
    slider("Temperature", -100, 100, Math.round(g.temperature * 100), v -> g.temperature = v / 100.0, x -> (x > 0 ? "warmer " : x < 0 ? "cooler " : "") + Math.abs(x));
    slider("Tint", -100, 100, Math.round(g.tint * 100), v -> g.tint = v / 100.0, x -> (x > 0 ? "magenta " : x < 0 ? "green " : "") + Math.abs(x));
    slider("Blacks", -100, 100, Math.round(g.blacks * 100), v -> g.blacks = v / 100.0, x -> x + "");
    slider("Shadows", -100, 100, Math.round(g.shadows * 100), v -> g.shadows = v / 100.0, x -> x + "");
    slider("Highlights", -100, 100, Math.round(g.highlights * 100), v -> g.highlights = v / 100.0, x -> x + "");
    slider("Whites", -100, 100, Math.round(g.whites * 100), v -> g.whites = v / 100.0, x -> x + "");
    label("Film look");
    LinearLayout films = row();
    for (int k = 0; k < EditModel.FILM_LOOKS.length; k++) { final int f = k; films.addView(chip(EditModel.FILM_LOOKS[k], v -> { g.film = f; changed(true); }, g.film == k), lp(-2)); }
    inspector.addView(scroll(films));
    if (g.film > 0) slider("Look strength", 0, 100, Math.round(g.filmStrength * 100), v -> g.filmStrength = v / 100.0, x -> x + "%");
  }

  interface WheelSet { void set(double hue, double amount); }
  interface MasterSet { void set(double v); }

  /** Lift (shadows), gamma (mid tones), gain (highlights) and offset (everything): colour wheel plus master slider each. */
  private void wheelsTab(EditModel.Grade g) {
    label("Drag a wheel towards a colour to tint that range; the slider under it makes the range darker or brighter. Double-tap a wheel to centre it.");
    LinearLayout r = row();
    r.addView(wheelBox("LIFT", g.liftH, g.liftA, g.liftM, (h, a) -> { g.liftH = h; g.liftA = a; }, m -> g.liftM = m), new LinearLayout.LayoutParams(dp(118), -2));
    r.addView(wheelBox("GAMMA", g.gammaH, g.gammaA, g.gammaM, (h, a) -> { g.gammaH = h; g.gammaA = a; }, m -> g.gammaM = m), new LinearLayout.LayoutParams(dp(118), -2));
    r.addView(wheelBox("GAIN", g.gainH, g.gainA, g.gainM, (h, a) -> { g.gainH = h; g.gainA = a; }, m -> g.gainM = m), new LinearLayout.LayoutParams(dp(118), -2));
    r.addView(wheelBox("OFFSET", g.offH, g.offA, g.offM, (h, a) -> { g.offH = h; g.offA = a; }, m -> g.offM = m), new LinearLayout.LayoutParams(dp(118), -2));
    inspector.addView(scroll(r));
    LinearLayout b = row();
    b.addView(chip("RESET WHEELS", v -> { snapshot(); g.liftA = g.liftM = g.gammaA = g.gammaM = g.gainA = g.gainM = g.offA = g.offM = 0; changed(true); }), lp(-2));
    inspector.addView(b);
  }

  private LinearLayout wheelBox(String name, double hue, double amount, double master, WheelSet set, MasterSet setMaster) {
    LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER_HORIZONTAL);
    TextView t = text(name, 11, DIM); t.setGravity(Gravity.CENTER); box.addView(t, new LinearLayout.LayoutParams(-1, -2));
    ColourWheel w = new ColourWheel(this, hue, amount, set);
    box.addView(w, new LinearLayout.LayoutParams(dp(104), dp(104)));
    SeekBar s = new SeekBar(this);
    s.setMax(200); s.setProgress((int) Math.round(master * 100) + 100);
    s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar b, int p, boolean user) { if (user) { setMaster.set((p - 100) / 100.0); live(); } }
      @Override public void onStartTrackingTouch(SeekBar b) { snapshot(); }
      @Override public void onStopTrackingTouch(SeekBar b) { changed(true); }
    });
    box.addView(s, new LinearLayout.LayoutParams(-1, dp(28)));
    return box;
  }

  /** A colour wheel: the puck's angle is the hue, its distance from the centre the amount. */
  final class ColourWheel extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double hue, amount;
    private final WheelSet set;
    private long lastDown;

    ColourWheel(Context c, double hue, double amount, WheelSet set) { super(c); this.hue = hue; this.amount = amount; this.set = set; }

    @Override protected void onDraw(Canvas c) {
      float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(4);
      int[] cols = new int[13];
      for (int i = 0; i <= 12; i++) { double[] h = EditModel.hueRgb(i * 30); cols[i] = 0xff000000 | ((int) (h[0] * 255) << 16) | ((int) (h[1] * 255) << 8) | (int) (h[2] * 255); }
      p.setShader(new android.graphics.SweepGradient(cx, cy, cols, null)); p.setStyle(Paint.Style.FILL);
      c.drawCircle(cx, cy, r, p);
      p.setShader(new android.graphics.RadialGradient(cx, cy, r, 0xff3a3f44, 0x003a3f44, android.graphics.Shader.TileMode.CLAMP));
      c.drawCircle(cx, cy, r, p);
      p.setShader(null);
      p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(0x66ffffff);
      c.drawCircle(cx, cy, r, p); c.drawLine(cx - dp(5), cy, cx + dp(5), cy, p); c.drawLine(cx, cy - dp(5), cx, cy + dp(5), p);
      double a = Math.toRadians(hue);
      float px = cx + (float) (Math.cos(a) * amount * r), py = cy + (float) (Math.sin(a) * amount * r);
      p.setStyle(Paint.Style.FILL); p.setColor(0xff000000); c.drawCircle(px, py, dp(7), p);
      p.setColor(ACCENT); c.drawCircle(px, py, dp(5), p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
      float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(4);
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          getParent().requestDisallowInterceptTouchEvent(true);
          snapshot();
          if (e.getEventTime() - lastDown < 300) { amount = 0; set.set(hue, 0); live(); invalidate(); lastDown = 0; return true; } // double tap: centre
          lastDown = e.getEventTime();
          return true;
        case MotionEvent.ACTION_MOVE: {
          double dx = e.getX() - cx, dy = e.getY() - cy;
          hue = (Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360;
          amount = Math.min(1, Math.hypot(dx, dy) / r);
          set.set(hue, amount); live(); invalidate();
          return true;
        }
        case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
          changed(true); return true;
        default: return true;
      }
    }
  }

  private int curveChannel; // 0 master, 1 red, 2 green, 3 blue

  private void curvesTab(EditModel.Grade g) {
    LinearLayout ch = row();
    String[] names = {"MASTER", "RED", "GREEN", "BLUE"};
    for (int k = 0; k < 4; k++) { final int n = k; ch.addView(chip(names[k], v -> { curveChannel = n; buildInspector(); }, curveChannel == k), lp(-2)); }
    ch.addView(chip("RESET CURVE", v -> { snapshot(); setCurve(g, curveChannel, new double[0]); changed(true); }), lp(-2));
    inspector.addView(scroll(ch));
    label("Tap to add a point and drag it; drag a point off the side to remove it. MASTER changes brightness, the colours change one channel.");
    inspector.addView(new CurveView(this, g), new LinearLayout.LayoutParams(-1, dp(220)));
  }

  private double[] curveOf(EditModel.Grade g, int ch) { return ch == 1 ? g.curveR : ch == 2 ? g.curveG : ch == 3 ? g.curveB : g.curveM; }

  private void setCurve(EditModel.Grade g, int ch, double[] v) { if (ch == 1) g.curveR = v; else if (ch == 2) g.curveG = v; else if (ch == 3) g.curveB = v; else g.curveM = v; }

  /** Curve editor for the chosen channel; points are x,y in 0..1. */
  final class CurveView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final EditModel.Grade g;
    private int dragging = -1;

    CurveView(Context c, EditModel.Grade g) { super(c); this.g = g; }

    private RectF box() { float s = Math.min(getWidth(), getHeight()) - dp(16); return new RectF(dp(8), dp(8), dp(8) + s, dp(8) + s); }

    @Override protected void onDraw(Canvas c) {
      RectF b = box();
      p.setStyle(Paint.Style.FILL); p.setColor(0xff15191d); c.drawRect(b, p);
      p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1)); p.setColor(0x33ffffff);
      for (int i = 1; i < 4; i++) { float x = b.left + b.width() * i / 4, y = b.top + b.height() * i / 4; c.drawLine(x, b.top, x, b.bottom, p); c.drawLine(b.left, y, b.right, y, p); }
      c.drawLine(b.left, b.bottom, b.right, b.top, p);
      int[] col = {0xffeef1f3, 0xffff5a5a, 0xff62ff7a, 0xff5aa0ff};
      double[] pts = curveOf(g, curveChannel);
      android.graphics.Path path = new android.graphics.Path();
      for (int i = 0; i <= 64; i++) {
        double x = i / 64.0, y = EditModel.curve(pts, x);
        float px = b.left + (float) x * b.width(), py = b.bottom - (float) y * b.height();
        if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
      }
      p.setColor(col[curveChannel]); p.setStrokeWidth(dp(2)); c.drawPath(path, p);
      p.setStyle(Paint.Style.FILL);
      for (int i = 0; i + 1 < pts.length; i += 2) c.drawCircle(b.left + (float) pts[i] * b.width(), b.bottom - (float) pts[i + 1] * b.height(), dp(6), p);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
      RectF b = box();
      double x = (e.getX() - b.left) / b.width(), y = (b.bottom - e.getY()) / b.height();
      double[] pts = curveOf(g, curveChannel);
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: {
          getParent().requestDisallowInterceptTouchEvent(true);
          snapshot();
          dragging = -1;
          for (int i = 0; i + 1 < pts.length; i += 2) if (Math.hypot((pts[i] - x) * b.width(), (pts[i + 1] - y) * b.height()) < dp(20)) dragging = i;
          if (dragging < 0 && x >= 0 && x <= 1) { // new point
            double[] n = java.util.Arrays.copyOf(pts, pts.length + 2);
            n[pts.length] = x; n[pts.length + 1] = Math.max(0, Math.min(1, y));
            setCurve(g, curveChannel, n); dragging = pts.length;
          }
          live(); invalidate();
          return true;
        }
        case MotionEvent.ACTION_MOVE:
          if (dragging < 0) return true;
          if (x < -0.08 || x > 1.08) { // dragged off the side: remove
            double[] n = new double[pts.length - 2];
            for (int i = 0, k = 0; i < pts.length; i += 2) if (i != dragging) { n[k++] = pts[i]; n[k++] = pts[i + 1]; }
            setCurve(g, curveChannel, n); dragging = -1;
          } else { pts[dragging] = Math.max(0.001, Math.min(0.999, x)); pts[dragging + 1] = Math.max(0, Math.min(1, y)); }
          live(); invalidate();
          return true;
        case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
          dragging = -1; changed(true); return true;
        default: return true;
      }
    }
  }

  /** HSL qualifier: pick a range of colours (presets or by hand) and change only those. */
  private void hslTab(EditModel.Grade g) {
    LinearLayout r = row();
    r.addView(chip(g.hslOn ? "QUALIFIER ON" : "QUALIFIER OFF", v -> { snapshot(); g.hslOn = !g.hslOn; changed(true); }, g.hslOn), lp(-2));
    r.addView(chip(g.hslShow ? "SHOWING SELECTION" : "SHOW SELECTION", v -> { g.hslShow = !g.hslShow; changed(true); }, g.hslShow), lp(-2));
    inspector.addView(scroll(r));
    LinearLayout pr = row();
    String[] names = {"SKIN", "SKY", "FOLIAGE", "RED", "YELLOW"};
    double[][] presets = {{25, 40}, {210, 50}, {100, 60}, {0, 30}, {55, 30}};
    for (int k = 0; k < names.length; k++) { final double[] ps = presets[k]; pr.addView(chip(names[k], v -> { snapshot(); g.hslOn = true; g.hslHue = ps[0]; g.hslWidth = ps[1]; changed(true); }), lp(-2)); }
    inspector.addView(scroll(pr));
    label("SHOW SELECTION paints the chosen colours white and the rest black, so you can fine-tune the range.");
    slider("Hue (centre)", 0, 359, Math.round(g.hslHue), v -> g.hslHue = v, x -> x + "°");
    slider("Range width", 10, 180, Math.round(g.hslWidth), v -> g.hslWidth = v, x -> x + "°");
    slider("Softness", 0, 100, Math.round(g.hslSoft * 100), v -> g.hslSoft = v / 100.0, x -> x + "%");
    slider("Hue shift", -60, 60, Math.round(g.hslShift), v -> g.hslShift = v, x -> x + "°");
    slider("Saturation", -100, 100, Math.round(g.hslSat * 100), v -> g.hslSat = v / 100.0, x -> x + "");
    slider("Lightness", -100, 100, Math.round(g.hslLum * 100), v -> g.hslLum = v / 100.0, x -> x + "");
  }

  /** Creative LUTs: choose, strength, and manage imported files (favourite, rename, delete). */
  private void lutTab(EditModel.Clip c) {
    java.util.Set<String> fav = getSharedPreferences("editor", MODE_PRIVATE).getStringSet("lutFav", new java.util.HashSet<>());
    label("Cinematic LUT" + (c.lut.isEmpty() ? "" : ": " + lutName(c.lut)));
    LinearLayout lutRow = row();
    lutRow.addView(chip("None", v -> { c.lut = ""; changed(true); }, c.lut.isEmpty()), lp(-2));
    java.util.List<String> keys = new java.util.ArrayList<>();
    for (String nm : CinematicLuts.NAMES) keys.add(CinematicLuts.key(nm));
    for (String f : LutPack.list(getAssets())) keys.add(LutPack.key(f));
    String[] files = EditEngine.lutDir(this).list();
    if (files != null) { java.util.Arrays.sort(files); keys.addAll(java.util.Arrays.asList(files)); }
    java.util.List<String> ordered = new java.util.ArrayList<>();
    for (String k : keys) if (fav.contains(k)) ordered.add(k);
    for (String k : keys) if (!fav.contains(k)) ordered.add(k);
    for (String k : ordered) {
      String mark = fav.contains(k) ? "♥ " : CinematicLuts.isBuiltin(k) ? "★ " : LutPack.isPack(k) ? "◆ " : "";
      lutRow.addView(chip(mark + lutName(k), v -> { c.lut = k; changed(true); }, k.equals(c.lut)), lp(-2));
    }
    lutRow.addView(chip("IMPORT .cube", v -> pickLut()), lp(-2));
    lutRow.addView(chip("GET LUTS ONLINE", v -> lutSources()), lp(-2));
    lutRow.addView(chip("EXPORT PACK", v -> exportLutPack()), lp(-2));
    inspector.addView(scroll(lutRow));
    if (c.lut.isEmpty()) return;
    slider("LUT strength", 0, 100, Math.round(c.lutStrength * 100), v -> c.lutStrength = v / 100.0, x -> x + "%");
    if (CinemaMath.convertsInPost(c.look)) label("Note: the clip's log is converted first (" + c.look + "). If your LUT expects log input, set the log (BASIC) to No LUT.");
    LinearLayout m = row();
    final String key = c.lut;
    m.addView(chip(fav.contains(key) ? "♥ FAVOURITE" : "♡ FAVOURITE", v -> {
      java.util.Set<String> f = new java.util.HashSet<>(fav);
      if (!f.remove(key)) f.add(key);
      getSharedPreferences("editor", MODE_PRIVATE).edit().putStringSet("lutFav", f).apply();
      buildInspector();
    }, fav.contains(key)), lp(-2));
    boolean imported = !CinematicLuts.isBuiltin(key) && !LutPack.isPack(key);
    if (imported) {
      m.addView(chip("RENAME", v -> renameLut(key)), lp(-2));
      m.addView(chip("DELETE", v -> deleteLut(key)), lp(-2));
    }
    inspector.addView(scroll(m));
  }

  private String lutName(String key) { return LutPack.isPack(key) ? LutPack.label(key) : CinematicLuts.nameOf(key).replaceAll("(?i)\\.cube$", ""); }

  private void renameLut(String file) {
    android.widget.EditText in = new android.widget.EditText(this);
    in.setText(file.replaceAll("(?i)\\.cube$", "")); in.setSingleLine();
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Rename LUT").setView(in)
        .setPositiveButton("Rename", (d, w) -> {
          String name = in.getText().toString().trim().replaceAll("[^A-Za-z0-9 ._()+-]", "_");
          if (name.isEmpty()) return;
          String to = name + ".cube";
          File dir = EditEngine.lutDir(this);
          if (new File(dir, to).exists()) { toast("A LUT with that name already exists."); return; }
          if (!new File(dir, file).renameTo(new File(dir, to))) { toast("Couldn't rename it."); return; }
          snapshot();
          for (EditModel.Clip k : project.clips) if (file.equals(k.lut)) k.lut = to;
          for (EditModel.Clip k : project.overlays) if (file.equals(k.lut)) k.lut = to;
          changed(true);
        }).setNegativeButton("Cancel", null).show();
  }

  private void deleteLut(String file) {
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Delete LUT")
        .setMessage("Delete " + file + " from City Cinema? Clips using it go back to no LUT.")
        .setPositiveButton("Delete", (d, w) -> {
          snapshot();
          new File(EditEngine.lutDir(this), file).delete();
          for (EditModel.Clip k : project.clips) if (file.equals(k.lut)) k.lut = "";
          for (EditModel.Clip k : project.overlays) if (file.equals(k.lut)) k.lut = "";
          changed(true);
        }).setNegativeButton("Cancel", null).show();
  }

  // ================================================================================================ audio mix

  /** The MIX panel (selected like a clip). */
  private static final Object MIX = new Object();
  private int mixBus = 2;
  private volatile double liveLufs = -120, livePeak = -120, liveDuck;
  private TextView meterView;

  private void mixInspector() {
    heading("Mix", "Effects per track, ducking, master limiter and loudness. Play to see the meters.");
    meterView = text("Play the timeline to measure", 12, ACCENT); meterView.setSingleLine(false);
    inspector.addView(meterView);
    LinearLayout bus = row();
    for (int k = 0; k < 4; k++) { final int b = k; bus.addView(chip(EditModel.Project.BUSES[k] + (project.fx[k].active() ? " •" : ""), v -> { mixBus = b; buildInspector(); }, mixBus == k), lp(-2)); }
    inspector.addView(scroll(bus));
    AudioDsp.Fx f = project.fx[mixBus];
    LinearLayout pre = row();
    pre.addView(chip("CLEAN VOICE", v -> { snapshot(); project.fx[mixBus] = AudioDsp.Fx.voicePreset(); changed(true); }), lp(-2));
    pre.addView(chip("RESET", v -> { snapshot(); project.fx[mixBus] = new AudioDsp.Fx(); changed(true); }), lp(-2));
    inspector.addView(pre);
    slider("Level", -24, 12, Math.round(f.gainDb), v -> f.gainDb = v, x -> x + " dB");
    slider("Low cut (high-pass)", 0, 400, Math.round(f.hpfHz), v -> f.hpfHz = v < 20 ? 0 : v, x -> x < 20 ? "off" : x + " Hz");
    slider("High cut (low-pass)", 0, 200, f.lpfHz <= 0 ? 0 : Math.round(f.lpfHz / 100), v -> f.lpfHz = v < 10 ? 0 : v * 100, x -> x < 10 ? "off" : (x * 100) + " Hz");
    slider("Bass (120 Hz)", -12, 12, Math.round(f.lowDb), v -> f.lowDb = v, x -> x + " dB");
    slider("Mid", -12, 12, Math.round(f.midDb), v -> f.midDb = v, x -> x + " dB");
    slider("Mid frequency", 2, 80, Math.round(f.midHz / 100), v -> f.midHz = v * 100, x -> (x * 100) + " Hz");
    slider("Treble (8 kHz)", -12, 12, Math.round(f.highDb), v -> f.highDb = v, x -> x + " dB");
    LinearLayout dyn = row();
    dyn.addView(chip(f.compOn ? "COMPRESSOR ON" : "COMPRESSOR", v -> { f.compOn = !f.compOn; changed(true); }, f.compOn), lp(-2));
    dyn.addView(chip(f.gateOn ? "NOISE GATE ON" : "NOISE GATE", v -> { f.gateOn = !f.gateOn; changed(true); }, f.gateOn), lp(-2));
    inspector.addView(scroll(dyn));
    if (f.compOn) {
      slider("Compressor threshold", -40, 0, Math.round(f.compThreshold), v -> f.compThreshold = v, x -> x + " dB");
      slider("Ratio", 15, 100, Math.round(f.compRatio * 10), v -> f.compRatio = v / 10.0, x -> String.format(Locale.US, "%.1f:1", x / 10.0));
      slider("Make-up gain", 0, 18, Math.round(f.compMakeup), v -> f.compMakeup = v, x -> "+" + x + " dB");
    }
    if (f.gateOn) slider("Gate threshold", -80, -20, Math.round(f.gateThreshold), v -> f.gateThreshold = v, x -> x + " dB (quieter is turned down)");
    slider("De-esser", 0, 100, Math.round(f.deEss * 100), v -> f.deEss = v / 100.0, x -> x == 0 ? "off" : x + "%");
    // master
    label("Master");
    LinearLayout m = row();
    m.addView(chip(project.limiterOn ? "LIMITER -1 dB ✓" : "LIMITER OFF", v -> { project.limiterOn = !project.limiterOn; changed(true); }, project.limiterOn), lp(-2));
    m.addView(chip(project.duckOn ? "DUCKING ON" : "DUCKING", v -> { project.duckOn = !project.duckOn; changed(true); }, project.duckOn), lp(-2));
    m.addView(chip(project.duckFromCamera ? "DUCK FOR VOICE + CAMERA" : "DUCK FOR VOICE TRACK", v -> { project.duckFromCamera = !project.duckFromCamera; changed(true); }, project.duckFromCamera), lp(-2));
    inspector.addView(scroll(m));
    if (project.duckOn) {
      slider("Duck depth", -30, -3, Math.round(project.duckDepth), v -> project.duckDepth = v, x -> x + " dB");
      slider("Sensitivity", -60, -20, Math.round(project.duckThreshold), v -> project.duckThreshold = v, x -> "speech above " + x + " dB");
      slider("Duck attack", 10, 500, Math.round(project.duckAttack), v -> project.duckAttack = v, x -> x + " ms");
      slider("Duck release", 100, 3000, Math.round(project.duckRelease), v -> project.duckRelease = v, x -> x + " ms");
    }
    label("Export loudness (measured over the whole mix, then the limiter keeps peaks under -1 dB)");
    LinearLayout lt = row();
    double[] targets = {0, -14, -16, -9, -23};
    String[] names = {"AS MIXED", "-14 LUFS (YouTube, Spotify)", "-16 LUFS (podcasts, Apple)", "-9 LUFS (loud music video)", "-23 LUFS (broadcast)"};
    for (int k = 0; k < targets.length; k++) { final double tg = targets[k]; lt.addView(chip(names[k], v -> { project.loudnessTarget = tg; changed(true); }, project.loudnessTarget == tg), lp(-2)); }
    inspector.addView(scroll(lt));
    LinearLayout ms = row();
    ms.addView(chip("MEASURE WHOLE MIX", v -> measureMix()), lp(-2));
    inspector.addView(ms);
  }

  /** Mixes the whole programme without playing it and reports its integrated loudness and true peak. */
  private void measureMix() {
    final EditModel.Project p = EditModel.snapshot(project);
    toast("Measuring the whole mix… the result appears in a moment.");
    new Thread(() -> {
      EditEngine.Mixer mx = new EditEngine.Mixer(this);
      mx.meter = new AudioDsp.Loudness();
      float[] buf = new float[4096 * 2];
      long total = EditModel.duration(p) * 48_000 / 1_000_000L;
      for (long f = 0; f < total; f += 4096) mx.mix(p, f * 1_000_000L / 48_000, (int) Math.min(4096, total - f), buf);
      mx.release();
      double lufs = mx.meter.integrated(), peak = mx.meter.truePeakDb();
      ui.post(() -> toast(lufs <= -70 ? "The mix is silent." : String.format(Locale.US, "Whole mix: %.1f LUFS integrated, true peak %.1f dBTP.%s", lufs, peak,
          project.loudnessTarget != 0 ? String.format(Locale.US, " Export will apply %+.1f dB to reach %.0f LUFS.", Math.max(-20, Math.min(20, project.loudnessTarget - lufs)), project.loudnessTarget) : "")));
    }, "measure-mix").start();
  }

  private static String trimNum(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }

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

  // ---- undo: snapshots of the project before each change that removes or cuts something
  private final java.util.ArrayDeque<String> undoStack = new java.util.ArrayDeque<>();

  private final java.util.ArrayDeque<String> redoStack = new java.util.ArrayDeque<>();

  private void snapshot() {
    try { undoStack.push(project.json().toString()); while (undoStack.size() > 40) undoStack.removeLast(); redoStack.clear(); }
    catch (Exception e) { Log.w(TAG, "snapshot", e); }
  }

  private void undo() { swapState(undoStack, redoStack, "Undone.", "Nothing to undo."); }

  private void redo() { swapState(redoStack, undoStack, "Redone.", "Nothing to redo."); }

  private void swapState(java.util.ArrayDeque<String> from, java.util.ArrayDeque<String> to, String done, String empty) {
    if (from.isEmpty()) { toast(empty); return; }
    try {
      stopPlayback();
      to.push(project.json().toString());
      project = EditModel.Project.from(new JSONObject(from.pop()));
      selected = null; changed(true);
      toast(done);
    } catch (Exception e) { Log.w(TAG, "undo", e); toast("Couldn't change the project."); }
  }

  /** The lowest layer track with room for [start, start + length). */
  private int freeTrack(long start, long length) {
    for (int t = 1; t <= EditModel.Project.MAX_TRACKS; t++) {
      boolean free = true;
      for (EditModel.Clip o : project.overlays) if (o.track == t && o.startUs < start + length && start < o.startUs + o.durationUs()) free = false;
      if (free) return t;
    }
    return EditModel.Project.MAX_TRACKS;
  }

  /** Deletes what is selected on the timeline: a clip, a title or the music. */
  private void deleteSelected() {
    if (!multi.isEmpty()) {
      snapshot();
      int n = 0;
      for (Object o : multi) {
        if (o instanceof EditModel.Clip && project.overlays.contains(o) && project.layerLocked(((EditModel.Clip) o).track)) continue;
        if (project.clips.remove(o) || project.overlays.remove(o) || project.titles.remove(o) || project.audio.remove(o)) n++;
      }
      multi.clear(); selected = null; changed(true);
      toast(n + " items deleted. UNDO brings them back.");
      return;
    }
    if (selected instanceof EditModel.Clip && project.overlays.contains(selected) && project.layerLocked(((EditModel.Clip) selected).track)) { toast("Layer " + ((EditModel.Clip) selected).track + " is locked: unlock it in the track header first."); return; }
    if (selected instanceof EditModel.Clip) { snapshot(); if (!project.clips.remove(selected)) project.overlays.remove(selected); toast("Clip deleted. UNDO brings it back."); }
    else if (selected instanceof EditModel.Title) { snapshot(); project.titles.remove(selected); toast("Title deleted. UNDO brings it back."); }
    else if (selected instanceof EditModel.Music) { snapshot(); project.audio.remove(selected); toast("Sound removed. UNDO brings it back."); }
    else { toast("Tap a clip, title or the music on the timeline first, then DELETE."); return; }
    selected = null; changed(true);
  }

  private void splitAtPlayhead() {
    if (selected instanceof EditModel.Music && project.audio.contains(selected)) {
      snapshot();
      EditModel.Music b = EditModel.splitAudio(project, (EditModel.Music) selected, playhead);
      if (b != null) { selected = b; changed(true); } else { undoStack.pop(); toast("Move the playhead inside the selected sound to split it."); }
      return;
    }
    if (selected instanceof EditModel.Clip && project.overlays.contains(selected)) {
      snapshot();
      EditModel.Clip b = EditModel.splitOverlay(project, (EditModel.Clip) selected, playhead);
      if (b != null) { selected = b; changed(true); } else { undoStack.pop(); toast("Move the playhead inside the selected layer clip to split it."); }
      return;
    }
    long[] s = EditModel.starts(project);
    for (int i = 0; i < project.clips.size(); i++)
      if (playhead > s[i] && playhead < s[i] + project.clips.get(i).durationUs()) {
        snapshot();
        if (EditModel.split(project, i, playhead)) { selected = project.clips.get(i + 1); changed(true); }
        return;
      }
    toast("Move the playhead over a clip to split it.");
  }

  // ------------------------------------------------------------------------------------------------ inspector

  private void select(Object o) { selected = o; buildInspector(); timeline.invalidate(); }

  // ---- multi-select, copy and paste
  /** Items picked with a long press (clips, layers, titles, sounds); DELETE and COPY act on all of them. */
  private final java.util.LinkedHashSet<Object> multi = new java.util.LinkedHashSet<>();
  private final java.util.List<Object[]> clipboard = new java.util.ArrayList<>(); // {kind, copy, offset from the first item's start}
  /** How dragging a selected main clip works: TRIM (edges, ripple), ROLL (edges move the cut), SLIP / SLIDE (the body). */
  private String editMode = "TRIM";

  private long startOfItem(Object o) {
    if (o instanceof EditModel.Clip) {
      EditModel.Clip c = (EditModel.Clip) o;
      int i = project.clips.indexOf(c);
      return i >= 0 ? EditModel.starts(project)[i] : c.startUs;
    }
    return o instanceof EditModel.Title ? ((EditModel.Title) o).startUs : ((EditModel.Music) o).startUs;
  }

  private void copyItems() {
    java.util.List<Object> items = new java.util.ArrayList<>(multi.isEmpty() && selected != null ? java.util.Collections.singletonList(selected) : multi);
    if (items.isEmpty()) { toast("Select a clip, layer, title or sound first (long-press to pick several)."); return; }
    long first = Long.MAX_VALUE;
    for (Object o : items) first = Math.min(first, startOfItem(o));
    clipboard.clear();
    for (Object o : items) {
      long off = startOfItem(o) - first;
      if (o instanceof EditModel.Clip) clipboard.add(new Object[] {project.clips.contains(o) ? "main" : "layer", ((EditModel.Clip) o).copy(), off});
      else if (o instanceof EditModel.Title) clipboard.add(new Object[] {"title", ((EditModel.Title) o).copy(), off});
      else clipboard.add(new Object[] {"sound", ((EditModel.Music) o).copy(), off});
    }
    toast(clipboard.size() + (clipboard.size() == 1 ? " item" : " items") + " copied. Move the playhead and tap PASTE.");
  }

  /** Main-track clips go in after the clip under the playhead; everything else keeps its spacing from the playhead. */
  private void pasteItems() {
    if (clipboard.isEmpty()) { toast("Nothing copied yet."); return; }
    snapshot();
    long[] s = EditModel.starts(project);
    int at = project.clips.size();
    for (int i = 0; i < s.length; i++) if (playhead >= s[i] && playhead < s[i] + project.clips.get(i).durationUs()) at = i + 1;
    Object last = null;
    for (Object[] e : clipboard) {
      long off = (Long) e[2];
      if ("main".equals(e[0]) || "layer".equals(e[0])) {
        EditModel.Clip c = ((EditModel.Clip) e[1]).copy(); c.id = EditModel.NEXT_ID.incrementAndGet();
        if ("main".equals(e[0])) project.clips.add(at++, c);
        else { c.startUs = playhead + off; c.track = freeTrack(c.startUs, c.durationUs()); project.overlays.add(c); }
        last = c;
      } else if ("title".equals(e[0])) { EditModel.Title t = ((EditModel.Title) e[1]).copy(); t.startUs = playhead + off; project.titles.add(t); last = t; }
      else { EditModel.Music m = ((EditModel.Music) e[1]).copy(); m.startUs = playhead + off; project.audio.add(m); last = m; }
    }
    multi.clear(); selected = last; changed(true);
  }

  private void buildInspector() {
    inspector.removeAllViews();
    if (selected instanceof EditModel.Clip && (project.clips.contains(selected) || project.overlays.contains(selected))) clipInspector((EditModel.Clip) selected);
    else if (selected instanceof EditModel.Title && project.titles.contains(selected)) titleInspector((EditModel.Title) selected);
    else if (selected instanceof EditModel.Music && project.audio.contains(selected)) musicInspector((EditModel.Music) selected);
    else if (selected == MIX) mixInspector();
    else projectInspector();
  }

  private void clipInspector(EditModel.Clip c) {
    heading(c.name, (c.width + "×" + c.height) + " · " + fmt(c.durationUs()) + (c.speed != 1 ? " · " + trimNum(c.speed) + "x" : "") + (CinemaMath.convertsInPost(c.look) ? " · " + c.look : !c.shotIn.isEmpty() ? " · " + c.shotIn + " (flat)" : ""));
    LinearLayout tabs = row();
    final boolean layer = project.overlays.contains(c);
    if (layer && clipTab.equals("Transition")) clipTab = "Edit";
    for (String t : layer ? new String[] {"Edit", "Colour"} : new String[] {"Edit", "Colour", "Transition"}) tabs.addView(chip(t, v -> { clipTab = t; buildInspector(); }, clipTab.equals(t)), lp(-2));
    inspector.addView(tabs);
    if (layer) label("Layer " + c.track + " · starts at " + fmt(c.startUs) + " · drawn above the main track");
    int i = project.clips.indexOf(c);
    if (clipTab.equals("Edit")) {
      slider("Start (trim)", 0, c.sourceDurationUs, c.inUs, v -> { c.inUs = Math.min(v, c.outUs - 200_000); }, x -> fmt(x));
      slider("End (trim)", 0, c.sourceDurationUs, c.outUs, v -> { c.outUs = Math.max(v, c.inUs + 200_000); }, x -> fmt(x));
      slider("Volume", 0, 200, (long) Math.round(c.volume * 100), v -> c.volume = v / 100.0, x -> x + "%");
      LinearLayout r = row();
      r.addView(chip(c.muted ? "UNMUTE" : "MUTE", v -> { c.muted = !c.muted; changed(true); }, c.muted), lp(-2));
      if (layer) {
        r.addView(chip("LAYER ▲", v -> { if (c.track < EditModel.Project.MAX_TRACKS) { snapshot(); c.track++; changed(true); } }), lp(-2));
        r.addView(chip("LAYER ▼", v -> { if (c.track > 1) { snapshot(); c.track--; changed(true); } }), lp(-2));
        r.addView(chip("START AT PLAYHEAD", v -> { snapshot(); c.startUs = playhead; changed(true); }), lp(-2));
      } else {
        r.addView(chip("◀ MOVE", v -> { if (i > 0) { snapshot(); project.clips.remove(i); project.clips.add(i - 1, c); changed(true); } }), lp(-2));
        r.addView(chip("MOVE ▶", v -> { if (i < project.clips.size() - 1) { snapshot(); project.clips.remove(i); project.clips.add(i + 1, c); changed(true); } }), lp(-2));
      }
      inspector.addView(r);
      LinearLayout r2 = row();
      r2.addView(chip("DUPLICATE", v -> {
        snapshot(); EditModel.Clip k = c.copy(); k.id = EditModel.NEXT_ID.incrementAndGet();
        if (layer) { k.startUs = c.startUs + c.durationUs(); project.overlays.add(k); } else project.clips.add(i + 1, k);
        selected = k; changed(true);
      }), lp(-2));
      r2.addView(chip("DELETE", v -> deleteSelected()), lp(-2));
      inspector.addView(r2);
      if (!layer) {
        label("Dragging this clip on the timeline: " + (editMode.equals("TRIM") ? "edges trim it (the clips after it move up or back: ripple)"
            : editMode.equals("ROLL") ? "edges move the cut with the neighbouring clip (total length stays)"
            : editMode.equals("SLIP") ? "drag the clip to show an earlier or later part of its video (place and length stay)"
            : "drag the clip along, taking time from one neighbour and giving it to the other"));
        LinearLayout em = row();
        for (String m : new String[] {"TRIM", "ROLL", "SLIP", "SLIDE"}) em.addView(chip(m, v -> { editMode = m; buildInspector(); }, editMode.equals(m)), lp(-2));
        inspector.addView(em);
      } else label("Drag the layer's edges on the timeline to trim it, its middle to move it.");
      // speed (sound follows, with its pitch)
      label(String.format(Locale.US, "Speed: %sx%s", trimNum(c.speed), c.speed != 1 ? " · sound pitch follows; MUTE for silent slow motion" : ""));
      LinearLayout sp = row();
      for (double v : new double[] {0.25, 0.5, 0.75, 1, 1.5, 2, 3, 4}) sp.addView(chip(trimNum(v) + "x", x -> { c.speed = v; changed(true); }, Math.abs(c.speed - v) < 1e-6), lp(-2));
      inspector.addView(scroll(sp));
      // framing
      label("Framing" + (c.turn != 0 ? " · turned " + c.turn + "°" : "") + (c.flip ? " · mirrored" : ""));
      LinearLayout fr = row();
      fr.addView(chip("FIT", v -> { c.fill = false; changed(true); }, !c.fill), lp(-2));
      fr.addView(chip("FILL", v -> { c.fill = true; changed(true); }, c.fill), lp(-2));
      fr.addView(chip("ROTATE ↻", v -> { c.turn = (c.turn + 90) % 360; changed(true); }), lp(-2));
      fr.addView(chip("FLIP ↔", v -> { c.flip = !c.flip; changed(true); }, c.flip), lp(-2));
      fr.addView(chip("FLIP ↕", v -> { c.flipV = !c.flipV; changed(true); }, c.flipV), lp(-2));
      fr.addView(chip("RESET", v -> {
        c.fill = false; c.zoom = 1; c.panX = c.panY = 0; c.turn = 0; c.flip = c.flipV = false;
        c.scaleX = c.scaleY = 1; c.rotationDeg = 0; c.cropL = c.cropR = c.cropT = c.cropB = 0; changed(true);
      }), lp(-2));
      inspector.addView(scroll(fr));
      slider("Opacity", 0, 100, Math.round(c.opacity * 100), v -> c.opacity = v / 100.0, x -> x + "%");
      slider("Zoom / crop", 25, 400, Math.round(c.zoom * 100), v -> c.zoom = v / 100.0, x -> x + "%" + (x < 100 ? " (picture-in-picture)" : ""));
      slider("Move left / right", -100, 100, Math.round(c.panX * 100), v -> c.panX = v / 100.0, x -> x + "");
      slider("Move up / down", -100, 100, Math.round(c.panY * 100), v -> c.panY = v / 100.0, x -> x + "");
      slider("Rotation", -180, 180, Math.round(c.rotationDeg), v -> c.rotationDeg = v, x -> x + "°");
      slider("Width", 25, 400, Math.round(c.scaleX * 100), v -> c.scaleX = v / 100.0, x -> x + "%");
      slider("Height", 25, 400, Math.round(c.scaleY * 100), v -> c.scaleY = v / 100.0, x -> x + "%");
      label("Crop (edges of the picture)");
      slider("Crop left", 0, 45, Math.round(c.cropL * 100), v -> c.cropL = v / 100.0, x -> x + "%");
      slider("Crop right", 0, 45, Math.round(c.cropR * 100), v -> c.cropR = v / 100.0, x -> x + "%");
      slider("Crop top", 0, 45, Math.round(c.cropT * 100), v -> c.cropT = v / 100.0, x -> x + "%");
      slider("Crop bottom", 0, 45, Math.round(c.cropB * 100), v -> c.cropB = v / 100.0, x -> x + "%");
      // compositing
      label("Blend mode: how this clip mixes with what is under it" + (layer ? "" : " (the main track sits on black)"));
      LinearLayout bm = row();
      for (int k = 0; k < EditModel.BLEND_MODES.length; k++) { final int m = k; bm.addView(chip(EditModel.BLEND_MODES[k], v -> { c.blend = m; changed(true); }, c.blend == k), lp(-2)); }
      inspector.addView(scroll(bm));
    } else if (clipTab.equals("Colour")) {
      colourInspector(c);
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
    r2.addView(chip("DELETE", v -> deleteSelected()), lp(-2));
    inspector.addView(r2);
  }

  private void musicInspector(EditModel.Music m) {
    heading(EditModel.Music.TRACKS[m.track] + ": " + m.name, "starts at " + fmt(m.startUs) + " · plays " + fmt(m.playUs()) + " of " + fmt(m.sourceDurationUs));
    LinearLayout tr = row();
    for (int k = 0; k < 3; k++) { final int t = k; tr.addView(chip(EditModel.Music.TRACKS[k], v -> { snapshot(); m.track = t; changed(true); }, m.track == k), lp(-2)); }
    inspector.addView(tr);
    slider("Volume", 0, 200, Math.round(m.volume * 100), v -> m.volume = v / 100.0, x -> x + "%");
    slider("Pan", -100, 100, Math.round(m.pan * 100), v -> m.pan = v / 100.0, x -> x == 0 ? "centre" : (x < 0 ? "left " : "right ") + Math.abs(x));
    slider("Start in the file (trim)", 0, Math.max(1, m.sourceDurationUs / 1000 - 200), m.inUs / 1000, v -> m.inUs = v * 1000, x -> fmt(x * 1000));
    slider("Length", 200, Math.max(201, (m.sourceDurationUs - m.inUs) / 1000), m.playUs() / 1000, v -> m.lengthUs = v * 1000, x -> fmt(x * 1000));
    slider("Fade in", 0, 10_000, m.fadeInUs / 1000, v -> m.fadeInUs = v * 1000, x -> String.format(Locale.US, "%.1f s", x / 1000.0));
    slider("Fade out", 0, 10_000, m.fadeOutUs / 1000, v -> m.fadeOutUs = v * 1000, x -> String.format(Locale.US, "%.1f s", x / 1000.0));
    LinearLayout r = row();
    r.addView(chip(m.muted ? "UNMUTE" : "MUTE", v -> { m.muted = !m.muted; changed(true); }, m.muted), lp(-2));
    r.addView(chip("START AT PLAYHEAD", v -> { snapshot(); m.startUs = playhead; changed(true); }), lp(-2));
    r.addView(chip("SPLIT", v -> splitAtPlayhead()), lp(-2));
    r.addView(chip("DUPLICATE", v -> { snapshot(); EditModel.Music k = m.copy(); k.startUs = m.startUs + m.playUs(); project.audio.add(k); selected = k; changed(true); }), lp(-2));
    r.addView(chip("DELETE", v -> deleteSelected()), lp(-2));
    inspector.addView(scroll(r));
    label("Drag it on the timeline to move it (it snaps to edges and the playhead). Sounds stop and fade with the end of the video.");
  }

  private MediaCache media;

  @Override protected void onDestroy() {
    super.onDestroy();
    if (media != null) media.close();
  }

  // ---- voice-over: record the microphone while the programme plays (its sound muted), then place it on the VOICE track
  private Button voiceButton;
  private volatile boolean voiceOn;
  private Thread voiceThread;
  private java.io.File voiceFile;
  private long voiceStartUs;

  private void toggleVoice() {
    if (voiceOn) { stopPlayback(); return; } // stopping playback finishes the take
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, 41);
      toast("Allow the microphone, then tap ● VOICE again.");
      return;
    }
    if (project.clips.isEmpty()) { toast("Add a video first: the voice-over is recorded while it plays."); return; }
    if (playing) stopPlayback();
    java.io.File dir = new java.io.File(getFilesDir(), "voice"); dir.mkdirs();
    voiceFile = new java.io.File(dir, "Voice_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date()) + ".wav");
    final android.media.AudioRecord rec;
    try {
      int min = android.media.AudioRecord.getMinBufferSize(48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      rec = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, 48_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 48_000));
      if (rec.getState() != android.media.AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("microphone busy");
    } catch (Exception e) { toast("Couldn't open the microphone: " + e.getMessage()); return; }
    voiceOn = true;
    voiceStartUs = playhead >= EditModel.duration(project) - 100_000 ? 0 : playhead;
    playhead = voiceStartUs;
    voiceButton.setText("■ STOP VOICE");
    final java.io.File f = voiceFile;
    voiceThread = new Thread(() -> {
      long bytes = 0;
      try (java.io.RandomAccessFile out = new java.io.RandomAccessFile(f, "rw")) {
        out.setLength(0); out.write(new byte[44]);
        short[] buf = new short[2400];
        byte[] b = new byte[buf.length * 2];
        rec.startRecording();
        while (voiceOn) {
          int n = rec.read(buf, 0, buf.length);
          if (n <= 0) continue;
          for (int i = 0; i < n; i++) { b[i * 2] = (byte) buf[i]; b[i * 2 + 1] = (byte) (buf[i] >> 8); }
          out.write(b, 0, n * 2); bytes += n * 2L;
        }
        rec.stop();
        out.seek(0); out.write(EditModel.wavHeader16(bytes, 48_000, 1));
      } catch (Exception e) { Log.w(TAG, "voice", e); }
      finally { rec.release(); }
      final long frames = bytes / 2;
      ui.post(() -> finishVoice(f, frames));
    }, "voice-over");
    voiceThread.start();
    startPlayback(true);
  }

  private void finishVoice(java.io.File f, long frames) {
    voiceButton.setText("● VOICE");
    if (frames < 4800) { f.delete(); toast("Voice-over too short, not added."); return; }
    snapshot();
    EditModel.Music m = new EditModel.Music();
    m.uri = Uri.fromFile(f).toString(); m.name = f.getName(); m.track = 1; m.volume = 1; m.fadeInUs = 30_000; m.fadeOutUs = 100_000;
    m.startUs = voiceStartUs; m.sourceDurationUs = frames * 1_000_000L / 48_000;
    project.audio.add(m); selected = m; changed(true);
    toast("Voice-over added on the VOICE track (" + fmt(m.sourceDurationUs) + "). Lower the music under it with its Volume, or drag it to fine-tune the timing.");
  }

  private void projectInspector() {
    heading("Project", project.clips.size() + " clips · " + fmt(EditModel.duration(project)) + (project.audio.isEmpty() ? "" : " · " + project.audio.size() + " sounds") + (project.titles.isEmpty() ? "" : " · " + project.titles.size() + " titles"));
    LinearLayout r = row();
    r.addView(chip(project.fadeIn ? "FADE IN ✓" : "FADE IN", v -> { project.fadeIn = !project.fadeIn; changed(true); }, project.fadeIn), lp(-2));
    r.addView(chip(project.fadeOut ? "FADE OUT ✓" : "FADE OUT", v -> { project.fadeOut = !project.fadeOut; changed(true); }, project.fadeOut), lp(-2));
    inspector.addView(r);
    slider("Fade length", 100, 3000, project.fadeUs / 1000, v -> project.fadeUs = v * 1000, x -> String.format(Locale.US, "%.1f s", x / 1000.0));
    int[] size = EditModel.outputSize(project);
    label("Frame: " + EditModel.Project.ASPECTS[project.aspect] + " · " + size[0] + "×" + size[1] + " (clips fit or fill it: Edit → FIT / FILL)");
    LinearLayout ar = row();
    for (int k = 0; k < EditModel.Project.ASPECTS.length; k++) { final int a = k; ar.addView(chip(EditModel.Project.ASPECTS[k], v -> { snapshot(); project.aspect = a; changed(true); }, project.aspect == k), lp(-2)); }
    inspector.addView(scroll(ar));
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
      renderer.compositor.bypass = beforeView; // preview only: exports use their own renderer
      int[] out = outputSize(p);
      double scale = Math.min(w / (double) out[0], h / (double) out[1]);
      int vw = (int) (out[0] * scale), vh = (int) (out[1] * scale);
      if (p.clips.isEmpty()) { android.opengl.GLES30.glClearColor(0, 0, 0, 1); android.opengl.GLES30.glClear(android.opengl.GLES30.GL_COLOR_BUFFER_BIT); return; }
      renderer.render(p, Math.min(playhead, Math.max(0, EditModel.duration(p) - 1)), (w - vw) / 2, (h - vh) / 2, vw, vh);
    }
  }

  /** Output size: the first clip's displayed size (rotation aware), even, at most 4K wide. */
  /** Output size: the programme's frame shape (PROJECT → frame), at most 3840 on the long side. */
  static int[] outputSize(EditModel.Project p) { return EditModel.outputSize(p); }

  private void togglePlay() { if (playing) stopPlayback(); else startPlayback(false); }

  private void startPlayback() { startPlayback(false); }

  /** silent: the picture plays and the clock runs, but no programme sound (voice-over recording, no echo). */
  private void startPlayback(final boolean silent) {
    if (project.clips.isEmpty()) return;
    if (playhead >= EditModel.duration(project) - 100_000) playhead = 0;
    playing = true;
    playButton.setText("❚❚");
    preview.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    final long start = playhead;
    final EditModel.Project p = shown;
    audioThread = new Thread(() -> {
      EditEngine.Mixer mixer = new EditEngine.Mixer(EditorActivity.this);
      mixer.meter = new AudioDsp.Loudness();
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
          if (silent) java.util.Arrays.fill(buf, 0f); else mixer.mix(p, t, 1024, buf);
          liveLufs = mixer.meter.momentary; livePeak = mixer.meter.truePeakDb(); liveDuck = mixer.duckingDb();
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
    if (voiceOn) { voiceOn = false; voiceThread = null; } // the recorder thread writes the file and adds it
    if (playButton != null) playButton.setText("▶");
    preview.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    preview.requestRender();
  }

  private final Runnable followPlayhead = new Runnable() {
    @Override public void run() {
      updateTime(); timeline.invalidate();
      if (meterView != null && selected == MIX) meterView.setText(String.format(Locale.US, "Now: %.1f LUFS (momentary) · true peak so far %.1f dBTP%s", liveLufs, livePeak, liveDuck < -0.5 ? String.format(Locale.US, " · music ducked %.0f dB", liveDuck) : ""));
      if (playing) ui.postDelayed(this, 40);
    }
  };

  private void updateTime() { timeView.setText(fmt(playhead) + " / " + fmt(EditModel.duration(project))); }

  // ------------------------------------------------------------------------------------------------ export

  private void exportDialog() {
    if (project.clips.isEmpty()) { toast("Add a video first."); return; }
    stopPlayback();
    int[] src = outputSize(project);
    int[] k4 = EditModel.exportSize(project, 2160), hd = EditModel.exportSize(project, 1080), sd = EditModel.exportSize(project, 720);
    String[] sizes = {"Project " + src[0] + "×" + src[1], "4K " + k4[0] + "×" + k4[1], "1080p " + hd[0] + "×" + hd[1], "720p " + sd[0] + "×" + sd[1]};
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
          int[] dims = sel[0] == 0 ? src : EditModel.exportSize(project, new int[] {0, 2160, 1080, 720}[sel[0]]);
          s.width = dims[0]; s.height = dims[1];
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
        msg = "Saved " + name + " in Movies/City Cinema (" + s.width + "×" + s.height + ", " + CinemaMath.fpsLabel(s.fps) + " fps, " + (s.hevc ? "HEVC" : "H.264") + ")."
            + (Double.isNaN(s.finalLufs) || s.finalLufs <= -70 ? "" : String.format(Locale.US, "\nSound: %.1f LUFS, true peak %.1f dBTP%s.", s.finalLufs, s.finalPeakDb,
                Double.isNaN(s.measuredLufs) ? "" : String.format(Locale.US, " (mixed at %.1f LUFS, normalised to %.0f)", s.measuredLufs, p.loudnessTarget)));
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
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), img = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float pxPerSecond = 60, lastX, downX;
    private boolean dragging;
    private final android.view.ScaleGestureDetector zoom;
    // moving a layer clip, title or sound: what, where it started, and the snap guide shown while moving
    private Object moving; private long moveFrom, guideUs = -1; private boolean moved;
    // edge / body edits on the selected item: L, R (edges), SLIP, SLIDE (main body); applied step by step while dragging
    private String op; private EditModel.Clip opClip; private EditModel.Music opSound; private int opIndex;
    private boolean longPressed;
    private float downY;
    private final Runnable longPress = () -> {
      Object hit = hitAt(downX, downY);
      if (hit == null || dragging) return;
      longPressed = true;
      if (!multi.remove(hit)) multi.add(hit);
      if (selected != null && selected != hit && !multi.contains(selected) && multi.size() == 1) multi.add(selected);
      selected = hit; buildInspector();
      performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
      invalidate();
      if (multi.size() > 1) toast(multi.size() + " items selected: DELETE or COPY act on all of them. Long-press again to drop one.");
    };

    Timeline(Context c) {
      super(c);
      setBackgroundColor(0xff101316);
      zoom = new android.view.ScaleGestureDetector(c, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        @Override public boolean onScale(android.view.ScaleGestureDetector d) { pxPerSecond = Math.max(8, Math.min(600, pxPerSecond * d.getScaleFactor())); invalidate(); return true; }
      });
    }

    private float scale() { return pxPerSecond * density / 2; }
    private float x(long us) { return getWidth() / 2f + (us - playhead) / 1e6f * scale(); }
    private long timeAt(float px) { return (long) (playhead + (px - getWidth() / 2f) / scale() * 1e6); }

    // rows, top to bottom: marker ruler, layers (3 lanes, highest on top), main video, titles, audio (3 lanes)
    private float headW() { return dp(58); }
    private float rowR() { return dp(12); }
    private float layerLane() { return dp(16); }
    private float rowL() { return rowR(); }
    private float rowLh() { return layerLane() * EditModel.Project.MAX_TRACKS; }
    private float rowV() { return rowL() + rowLh() + dp(3); }
    private float rowVh() { return dp(52); }
    private float rowT() { return rowV() + rowVh() + dp(3); }
    private float rowTh() { return dp(20); }
    private float rowM() { return rowT() + rowTh() + dp(3); }
    private float laneH() { return dp(18); }

    private float layerTop(int track) { return rowL() + (EditModel.Project.MAX_TRACKS - track) * layerLane(); }

    private RectF audioRect(EditModel.Music m) {
      float top = rowM() + m.track * laneH();
      return new RectF(x(m.startUs) + 1, top + 1, x(Math.min(m.startUs + m.playUs(), Math.max(m.startUs + 1, EditModel.duration(project)))) - 1, top + laneH() - 1);
    }

    private RectF layerRect(EditModel.Clip o) {
      float top = layerTop(o.track);
      return new RectF(x(o.startUs) + 1, top + 1, x(o.startUs + o.durationUs()) - 1, top + layerLane() - 1);
    }

    /** Thumbnails along a clip (source time follows speed and trim), clipped to its rectangle. */
    private void thumbs(Canvas c, EditModel.Clip k, long start, RectF r) {
      float tileW = r.height() * 16 / 9f;
      c.save(); c.clipRect(Math.max(r.left, headW()), r.top, r.right, r.bottom);
      float first = Math.max(r.left, headW() - tileW);
      for (float x0 = r.left + (float) Math.floor((first - r.left) / tileW) * tileW; x0 < Math.min(r.right, getWidth()); x0 += tileW) {
        long local = Math.max(0, Math.min(k.durationUs() - 1, timeAt(x0 + tileW / 2) - start));
        Bitmap b = media.thumbnail(k.uri, k.sourceAt(local));
        if (b != null) c.drawBitmap(b, null, new RectF(x0, r.top, x0 + tileW, r.bottom), img);
      }
      c.restore();
    }

    /** Sound shape of a file across a rectangle (peaks from the file's start; inUs = where the rectangle's left edge is in it). */
    private void wave(Canvas c, String uri, long fileUs, long startUs, long inUs, double speed, RectF r, int color, boolean centred) {
      float[] pk = media.peaks(uri, fileUs);
      if (pk == null) return;
      p.setColor(color);
      float from = Math.max(r.left, headW()), to = Math.min(r.right, getWidth());
      for (float x0 = from; x0 < to; x0 += dp(2)) {
        long src = inUs + (long) ((timeAt(x0) - startUs) * speed);
        int i = (int) (src * MediaCache.PEAKS_PER_SECOND / 1_000_000L);
        if (i < 0 || i >= pk.length) continue;
        float v = (float) Math.sqrt(pk[i]) * (r.height() - dp(2)); // square root: quiet parts still visible
        if (centred) c.drawRect(x0, r.centerY() - v / 2, x0 + dp(1.2f), r.centerY() + v / 2, p);
        else c.drawRect(x0, r.bottom - v, x0 + dp(1.2f), r.bottom, p);
      }
    }

    @Override protected void onDraw(Canvas c) {
      float h = getHeight(), rowV = rowV(), rowVh = rowVh(), rowT = rowT(), rowTh = rowTh(), rowM = rowM();
      p.setTextSize(dp(10)); p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
      // lane backgrounds
      for (int t = 1; t <= EditModel.Project.MAX_TRACKS; t++) { p.setColor(t % 2 == 0 ? 0xff15191d : 0xff121519); c.drawRect(0, layerTop(t), getWidth(), layerTop(t) + layerLane(), p); }
      for (int k = 0; k < 3; k++) { p.setColor(k % 2 == 0 ? 0xff15191d : 0xff121519); c.drawRect(0, rowM + k * laneH(), getWidth(), rowM + (k + 1) * laneH(), p); }
      // layers
      for (EditModel.Clip o : project.overlays) {
        RectF r = layerRect(o);
        boolean hid = project.layerHidden(o.track);
        p.setColor(0xff4a3f6e); c.drawRoundRect(r, dp(3), dp(3), p);
        thumbs(c, o, o.startUs, r);
        if (hid) { p.setColor(0xb0101316); c.drawRect(r, p); }
        if (o == selected) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(ACCENT); c.drawRoundRect(r, dp(3), dp(3), p); p.setStyle(Paint.Style.FILL); }
        p.setColor(TEXT); c.save(); c.clipRect(r);
        p.setShadowLayer(dp(2), 0, 0, 0xff000000);
        c.drawText(o.name + (o.opacity < 1 ? " · " + Math.round(o.opacity * 100) + "%" : "") + (o.zoom < 1 ? " · PiP" : ""), r.left + dp(4), r.centerY() + dp(4), p);
        p.clearShadowLayer(); c.restore();
      }
      // main track: thumbnails, camera sound at the bottom, labels on a dark band
      long[] s = EditModel.starts(project);
      for (int i = 0; i < project.clips.size(); i++) {
        EditModel.Clip k = project.clips.get(i);
        RectF r = new RectF(x(s[i]) + 1, rowV, x(s[i] + k.durationUs()) - 1, rowV + rowVh);
        if (r.right < headW() || r.left > getWidth()) continue;
        p.setColor(CinemaMath.convertsInPost(k.look) ? 0xff3a4a5e : 0xff34503f);
        c.drawRoundRect(r, dp(4), dp(4), p);
        thumbs(c, k, s[i], new RectF(r.left, r.top, r.right, r.bottom - dp(12)));
        p.setColor(0xff0e1114); c.drawRect(Math.max(r.left, headW()), r.bottom - dp(12), r.right, r.bottom, p);
        if (k.hasAudio && !k.muted && !project.mainMuted) { c.save(); c.clipRect(r); wave(c, k.uri, k.sourceDurationUs, s[i], k.inUs, k.speed, new RectF(r.left, r.bottom - dp(12), r.right, r.bottom), 0xff53e07a, false); c.restore(); }
        if (k.transition > 0 && i > 0) { p.setColor(0x66ffffff); float tx = x(s[i]); c.drawRect(tx, rowV, x(s[i] + EditModel.transitionUs(project, i)), rowV + rowVh, p); }
        c.save(); c.clipRect(r);
        p.setColor(0x99000000); c.drawRect(r.left, r.top, r.right, r.top + dp(28), p);
        p.setColor(TEXT); c.drawText(k.name, r.left + dp(5), rowV + dp(12), p);
        p.setColor(0xffc9d0d6);
        c.drawText(fmt(k.durationUs()) + (k.speed != 1 ? " · " + trimNum(k.speed) + "x" : "") + (k.muted ? " · muted" : "") + (CinemaMath.convertsInPost(k.look) ? " · " + k.look : !k.shotIn.isEmpty() ? " · " + k.shotIn + " (flat)" : "") + (k.grade.neutral() ? "" : " · graded"), r.left + dp(5), rowV + dp(24), p);
        c.restore();
        if (k == selected) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(ACCENT); c.drawRoundRect(r, dp(4), dp(4), p); p.setStyle(Paint.Style.FILL); }
      }
      // titles
      for (EditModel.Title t : project.titles) {
        RectF r = new RectF(x(t.startUs), rowT, x(t.startUs + t.durationUs), rowT + rowTh);
        p.setColor(t == selected ? ACCENT : project.titlesHidden ? 0xff3a3528 : 0xff6a5a2e); c.drawRoundRect(r, dp(3), dp(3), p);
        p.setColor(t == selected ? BG : TEXT); c.save(); c.clipRect(r); c.drawText("T  " + t.text.replace('\n', ' '), r.left + dp(4), rowT + dp(14), p); c.restore();
      }
      // audio lanes with waveforms
      int[] laneColor = {0xff5a3a6a, 0xff2e5a6a, 0xff6a4a2e}, waveColor = {0xffd7a8ff, 0xff8fe3ff, 0xffffcf8f};
      for (EditModel.Music m : project.audio) {
        RectF r = audioRect(m);
        boolean on = !m.muted && project.audioTrackOn(m.track);
        p.setColor(on ? laneColor[m.track] : 0xff2a2d30); c.drawRoundRect(r, dp(3), dp(3), p);
        c.save(); c.clipRect(r);
        wave(c, m.uri, m.sourceDurationUs, m.startUs, m.inUs, 1, r, on ? waveColor[m.track] : 0xff555a60, true);
        p.setColor(TEXT); p.setShadowLayer(dp(2), 0, 0, 0xff000000);
        c.drawText((m.track == 1 ? "🎙 " : "♪ ") + m.name, r.left + dp(4), r.centerY() + dp(4), p);
        p.clearShadowLayer(); c.restore();
        if (m == selected) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(ACCENT); c.drawRoundRect(r, dp(3), dp(3), p); p.setStyle(Paint.Style.FILL); }
      }
      if (voiceOn) { p.setColor(0xffe5322d); c.drawRect(x(voiceStartUs), rowM + laneH() + 1, x(playhead), rowM + 2 * laneH() - 1, p); } // recording
      // markers: diamonds on the ruler and a faint line
      for (long mk : project.markers) {
        float mx = x(mk);
        if (mx < headW() || mx > getWidth()) continue;
        p.setColor(0x55ffb341); c.drawRect(mx - dp(0.5f), rowR(), mx + dp(0.5f), h, p);
        p.setColor(ACCENT);
        android.graphics.Path d = new android.graphics.Path(); d.moveTo(mx, 0); d.lineTo(mx + dp(5), dp(5)); d.lineTo(mx, dp(10)); d.lineTo(mx - dp(5), dp(5)); d.close();
        c.drawPath(d, p);
      }
      // multi-selection outlines and the selected item's edge handles
      p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(0xff8fe3ff);
      for (Object o : multi) { RectF r = rectOf(o); if (r != null) c.drawRoundRect(r, dp(3), dp(3), p); }
      p.setStyle(Paint.Style.FILL);
      RectF sr = selected == null ? null : rectOf(selected);
      if (sr != null) { p.setColor(ACCENT); c.drawRect(sr.left, sr.top, sr.left + dp(4), sr.bottom, p); c.drawRect(sr.right - dp(4), sr.top, sr.right, sr.bottom, p); }
      if (guideUs >= 0) { p.setColor(ACCENT); float gx = x(guideUs); c.drawRect(gx - dp(1), 0, gx + dp(1), h, p); } // snap guide
      p.setColor(TEXT);
      c.drawRect(getWidth() / 2f - dp(1), 0, getWidth() / 2f + dp(1), h, p);
      drawHeaders(c);
    }

    // ---- track headers: name and the track's switches
    private void drawHeaders(Canvas c) {
      float w = headW();
      p.setColor(0xff0c0e10); c.drawRect(0, 0, w, getHeight(), p);
      p.setColor(0xff22272c); c.drawRect(w - dp(1), 0, w, getHeight(), p);
      p.setTextSize(dp(9));
      for (int t = EditModel.Project.MAX_TRACKS; t >= 1; t--) {
        float top = layerTop(t);
        header(c, top, layerLane(), "L" + t, "👁", !project.layerHidden(t), "🔒", project.layerLocked(t));
      }
      header(c, rowV(), rowVh(), "V1", project.mainMuted ? "🔇" : "🔊", !project.mainMuted, null, false);
      header(c, rowT(), rowTh(), "TXT", "👁", !project.titlesHidden, null, false);
      for (int k = 0; k < 3; k++)
        header(c, rowM() + k * laneH(), laneH(), new String[] {"MUS", "VOX", "FX"}[k], "M", (project.audioMute & (1 << k)) != 0, "S", (project.audioSolo & (1 << k)) != 0);
      p.setTextSize(dp(10));
    }

    /** Name at the left, then up to two switches: lit when on. */
    private void header(Canvas c, float top, float hgt, String name, String a, boolean aOn, String b, boolean bOn) {
      float cy = top + hgt / 2 + dp(3.5f);
      p.setColor(DIM); c.drawText(name, dp(3), cy, p);
      for (int i = 0; i < 2; i++) {
        String g = i == 0 ? a : b; boolean on = i == 0 ? aOn : bOn;
        if (g == null) continue;
        float l = dp(22) + i * dp(18);
        RectF box = new RectF(l, top + Math.max(1, (hgt - dp(14)) / 2), l + dp(16), top + Math.max(1, (hgt - dp(14)) / 2) + Math.min(hgt - 2, dp(14)));
        p.setColor(on ? (g.equals("M") ? 0xffe5322d : g.equals("S") ? 0xffffcf3f : 0xff2a3036) : 0xff181c20); c.drawRoundRect(box, dp(3), dp(3), p);
        p.setColor(on ? (g.equals("M") || g.equals("S") ? BG : TEXT) : 0xff5a6066);
        float tw = p.measureText(g); c.drawText(g, box.centerX() - tw / 2, box.centerY() + dp(3.5f), p);
      }
    }

    private RectF rectOf(Object o) {
      if (o instanceof EditModel.Clip) {
        EditModel.Clip k = (EditModel.Clip) o;
        int i = project.clips.indexOf(k);
        if (i >= 0) { long st = EditModel.starts(project)[i]; return new RectF(x(st) + 1, rowV(), x(st + k.durationUs()) - 1, rowV() + rowVh()); }
        return project.overlays.contains(k) ? layerRect(k) : null;
      }
      if (o instanceof EditModel.Title && project.titles.contains(o)) { EditModel.Title t = (EditModel.Title) o; return new RectF(x(t.startUs), rowT(), x(t.startUs + t.durationUs), rowT() + rowTh()); }
      if (o instanceof EditModel.Music && project.audio.contains(o)) return audioRect((EditModel.Music) o);
      return null;
    }

    /** A tap in the header column: which switch, if any. */
    private boolean headerTap(float tx, float ty) {
      if (tx >= headW()) return false;
      int which = tx < dp(22) ? -1 : tx < dp(40) ? 0 : 1;
      if (which < 0) return true;
      for (int t = 1; t <= EditModel.Project.MAX_TRACKS; t++)
        if (ty >= layerTop(t) && ty < layerTop(t) + layerLane()) { if (which == 0) project.hiddenLayers ^= 1 << t; else project.lockedLayers ^= 1 << t; changed(true); return true; }
      if (ty >= rowV() && ty < rowV() + rowVh() && which == 0) { project.mainMuted = !project.mainMuted; changed(true); return true; }
      if (ty >= rowT() && ty < rowT() + rowTh() && which == 0) { project.titlesHidden = !project.titlesHidden; changed(true); return true; }
      for (int k = 0; k < 3; k++)
        if (ty >= rowM() + k * laneH() && ty < rowM() + (k + 1) * laneH()) { if (which == 0) project.audioMute ^= 1 << k; else project.audioSolo ^= 1 << k; changed(true); return true; }
      return true;
    }

    /** The selected item under the finger that can be dragged along the timeline (layer clip, title or sound). */
    private Object movableAt(float tx, float ty) {
      long t = timeAt(tx);
      if (selected instanceof EditModel.Clip && project.overlays.contains(selected) && layerRect((EditModel.Clip) selected).contains(tx, ty))
        return project.layerLocked(((EditModel.Clip) selected).track) ? null : selected;
      if (selected instanceof EditModel.Title && ty >= rowT() && ty < rowM()) { EditModel.Title k = (EditModel.Title) selected; if (t >= k.startUs && t < k.startUs + k.durationUs) return k; }
      if (selected instanceof EditModel.Music && project.audio.contains(selected) && audioRect((EditModel.Music) selected).contains(tx, ty)) return selected;
      return null;
    }

    private long startOf(Object o) { return o instanceof EditModel.Clip ? ((EditModel.Clip) o).startUs : o instanceof EditModel.Title ? ((EditModel.Title) o).startUs : ((EditModel.Music) o).startUs; }

    private void setStart(Object o, long v) {
      if (o instanceof EditModel.Clip) ((EditModel.Clip) o).startUs = v; else if (o instanceof EditModel.Title) ((EditModel.Title) o).startUs = v; else ((EditModel.Music) o).startUs = v;
    }

    private long lengthOf(Object o) { return o instanceof EditModel.Clip ? ((EditModel.Clip) o).durationUs() : o instanceof EditModel.Title ? ((EditModel.Title) o).durationUs : ((EditModel.Music) o).playUs(); }

    @Override public boolean onTouchEvent(MotionEvent e) {
      zoom.onTouchEvent(e);
      if (e.getPointerCount() > 1) { dragging = true; moving = null; guideUs = -1; return true; }
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          lastX = downX = e.getX(); downY = e.getY(); dragging = false; moved = false; longPressed = false;
          op = e.getX() < headW() ? null : opAt(e.getX(), e.getY());
          moving = op != null || e.getX() < headW() ? null : movableAt(e.getX(), e.getY());
          if (moving != null) moveFrom = startOf(moving);
          postDelayed(longPress, 450);
          return true;
        case MotionEvent.ACTION_MOVE:
          if (Math.abs(e.getX() - downX) > dp(6)) { dragging = true; removeCallbacks(longPress); }
          if (!dragging) return true;
          if (playing) stopPlayback();
          if (op != null) { // trim / roll / slip / slide, step by step (each step is clamped to what the media allows)
            if (!moved) { snapshot(); moved = true; }
            long dt = (long) ((e.getX() - lastX) / scale() * 1e6);
            lastX = e.getX();
            applyOp(dt);
            shown = EditModel.snapshot(project);
            updateTime(); invalidate(); preview.requestRender();
            return true;
          }
          if (moving != null) { // drag the selected item, snapping its start or end to nearby edges, markers and the playhead
            if (!moved) { snapshot(); moved = true; }
            long dt = (long) ((e.getX() - downX) / scale() * 1e6);
            long start = Math.max(0, moveFrom + dt), len = lengthOf(moving);
            long tol = (long) (dp(10) / scale() * 1e6);
            long[] pts = EditModel.snapPoints(project, moving, playhead);
            long a = EditModel.snap(start, pts, tol), b = len > 0 ? EditModel.snap(start + len, pts, tol) - len : start;
            if (a != start) { start = a; guideUs = a; } else if (b != start) { start = b; guideUs = b + len; } else guideUs = -1;
            setStart(moving, Math.max(0, start));
            shown = EditModel.snapshot(project);
            updateTime(); invalidate(); preview.requestRender();
            return true;
          }
          long d = (long) ((lastX - e.getX()) / scale() * 1e6);
          playhead = Math.max(0, Math.min(Math.max(0, EditModel.duration(project) - 1), playhead + d));
          lastX = e.getX(); updateTime(); invalidate(); preview.requestRender();
          return true;
        case MotionEvent.ACTION_UP:
          removeCallbacks(longPress);
          if ((moving != null || op != null) && moved) { guideUs = -1; moving = null; op = null; changed(true); return true; }
          moving = null; op = null; guideUs = -1;
          if (!dragging && !longPressed) tap(e.getX(), e.getY());
          invalidate();
          return true;
        default: return true;
      }
    }

    private void tap(float tx, float ty) {
      if (headerTap(tx, ty)) return;
      long t = timeAt(tx);
      if (ty < rowR()) { // ruler: jump to a marker
        long tol = (long) (dp(12) / scale() * 1e6);
        for (long mk : project.markers) if (Math.abs(mk - t) <= tol) { if (playing) stopPlayback(); playhead = mk; updateTime(); invalidate(); preview.requestRender(); return; }
        return;
      }
      Object hit = hitAt(tx, ty);
      if (!multi.isEmpty() && (hit == null || !multi.contains(hit))) multi.clear(); // a plain tap ends a multi-selection
      select(hit);
    }

    private Object hitAt(float tx, float ty) {
      if (tx < headW() || ty < rowR()) return null;
      long t = timeAt(tx);
      Object hit = null;
      if (ty < rowV()) { for (EditModel.Clip o : project.overlays) if (layerRect(o).contains(tx, ty)) hit = o; }
      else if (ty < rowT()) { long[] s = EditModel.starts(project); for (int i = project.clips.size() - 1; i >= 0 && hit == null; i--) if (t >= s[i] && t < s[i] + project.clips.get(i).durationUs()) hit = project.clips.get(i); }
      else if (ty < rowM()) { for (EditModel.Title k : project.titles) if (t >= k.startUs && t < k.startUs + k.durationUs) hit = k; }
      else for (EditModel.Music m : project.audio) if (audioRect(m).contains(tx, ty)) hit = m;
      return hit;
    }

    /** Which edit a drag starting at (tx, ty) makes on the selected item: an edge trim / roll, a slip or slide, or none. */
    private String opAt(float tx, float ty) {
      float grab = dp(14);
      if (selected instanceof EditModel.Clip) {
        EditModel.Clip c = (EditModel.Clip) selected;
        int i = project.clips.indexOf(c);
        if (i >= 0) {
          long st = EditModel.starts(project)[i];
          RectF r = new RectF(x(st), rowV(), x(st + c.durationUs()), rowV() + rowVh());
          if (ty < r.top || ty > r.bottom || tx < r.left - grab || tx > r.right + grab) return null;
          opClip = c; opIndex = i;
          if (Math.abs(tx - r.left) < grab) return "L";
          if (Math.abs(tx - r.right) < grab) return "R";
          return editMode.equals("SLIP") ? "SLIP" : editMode.equals("SLIDE") ? "SLIDE" : null;
        }
        if (project.overlays.contains(c) && !project.layerLocked(c.track)) {
          RectF r = layerRect(c);
          if (ty < r.top - dp(4) || ty > r.bottom + dp(4)) return null;
          opClip = c;
          if (Math.abs(tx - r.left) < grab) return "LL";
          if (Math.abs(tx - r.right) < grab) return "LR";
        }
      } else if (selected instanceof EditModel.Music && project.audio.contains(selected)) {
        RectF r = audioRect((EditModel.Music) selected);
        if (ty < r.top - dp(4) || ty > r.bottom + dp(4)) return null;
        opSound = (EditModel.Music) selected;
        if (Math.abs(tx - r.left) < grab) return "AL";
        if (Math.abs(tx - r.right) < grab) return "AR";
      }
      return null;
    }

    private void applyOp(long dt) {
      switch (op) {
        case "L": if (editMode.equals("ROLL")) EditModel.roll(project, opIndex, dt); else EditModel.trimStart(opClip, dt); break;
        case "R": if (editMode.equals("ROLL")) EditModel.roll(project, opIndex + 1, dt); else EditModel.trimEnd(opClip, dt); break;
        case "SLIP": EditModel.slip(opClip, -dt); break; // drag right: the picture moves right, showing an earlier part
        case "SLIDE": EditModel.slide(project, opIndex, dt); break;
        case "LL": EditModel.trimLayerStart(opClip, dt); break;
        case "LR": EditModel.trimEnd(opClip, dt); break;
        case "AL": EditModel.trimAudioStart(opSound, dt); break;
        case "AR": EditModel.trimAudioEnd(opSound, dt); break;
        default: break;
      }
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

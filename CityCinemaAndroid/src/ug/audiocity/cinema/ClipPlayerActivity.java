package ug.audiocity.cinema;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * City Cinema's clip browser and player: lists the clips in Movies/City Cinema with thumbnails and plays MP4s with
 * an optional live LUT (log to Rec.709, applied in a GLES 3 shader with a 3D texture), chosen automatically from the
 * clip's JSON sidecar. ProRes .mov clips are listed but Android has no ProRes decoder, so they can't play here.
 */
public class ClipPlayerActivity extends Activity {
  static final String TAG = "CityCinemaPlayer";
  static final int BG = CinemaActivity.BG, TEXT = CinemaActivity.TEXT, DIM = CinemaActivity.DIM, ACCENT = CinemaActivity.ACCENT;
  static final String[] LUT_LOOKS = {"None", "City Log", "Flat Log", "Sony S-Log3", "RED Log3G10", "BMD Film Gen 5"};

  static final class Clip { long id; Uri uri; String name, mime; long durationMs, size; Bitmap thumb; String look; }

  private final List<Clip> clips = new ArrayList<>();
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final ExecutorService background = Executors.newSingleThreadExecutor();
  private ListView list;
  private ClipAdapter adapter;
  private GLSurfaceView video;
  private PlayerRenderer renderer;
  private MediaPlayer player;
  private Clip current;
  private Button playButton, lutButton;
  private SeekBar seek;
  private TextView timeView, titleView, emptyView;
  private String lut = "None";
  private float density;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    density = getResources().getDisplayMetrics().density;
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.HORIZONTAL);
    root.setBackgroundColor(BG);

    // ---- left: clip list
    LinearLayout left = new LinearLayout(this);
    left.setOrientation(LinearLayout.VERTICAL);
    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.CENTER_VERTICAL);
    head.setPadding(dp(10), dp(6), dp(6), dp(6));
    Button back = chip("‹ CAMERA", v -> finish());
    head.addView(back, new LinearLayout.LayoutParams(-2, dp(34)));
    TextView t = text("CLIPS", 14, TEXT); t.setPadding(dp(12), 0, 0, 0);
    head.addView(t);
    left.addView(head);
    list = new ListView(this);
    list.setDivider(null);
    adapter = new ClipAdapter();
    list.setAdapter(adapter);
    list.setOnItemClickListener((p, v, pos, id) -> play(clips.get(pos)));
    emptyView = text("No clips yet. Record something with City Cinema.", 13, DIM);
    emptyView.setPadding(dp(14), dp(14), dp(14), dp(14));
    left.addView(emptyView);
    left.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    root.addView(left, new LinearLayout.LayoutParams(0, -1, 0.36f));

    // ---- right: player
    LinearLayout right = new LinearLayout(this);
    right.setOrientation(LinearLayout.VERTICAL);
    titleView = text("Choose a clip", 13, DIM);
    titleView.setPadding(dp(12), dp(10), dp(12), dp(4));
    right.addView(titleView);
    FrameLayout stage = new FrameLayout(this);
    stage.setBackgroundColor(0xff000000);
    video = new GLSurfaceView(this);
    video.setEGLContextClientVersion(3);
    renderer = new PlayerRenderer();
    video.setRenderer(renderer);
    video.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    stage.addView(video, new FrameLayout.LayoutParams(-1, -1));
    right.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout controls = new LinearLayout(this);
    controls.setGravity(Gravity.CENTER_VERTICAL);
    controls.setPadding(dp(8), dp(6), dp(8), dp(6));
    playButton = chip("▶", v -> togglePlay());
    controls.addView(playButton, new LinearLayout.LayoutParams(dp(54), dp(38)));
    seek = new SeekBar(this);
    seek.setMax(1000);
    seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && player != null) player.seekTo((int) ((long) player.getDuration() * p / 1000), MediaPlayer.SEEK_CLOSEST); }
      @Override public void onStartTrackingTouch(SeekBar s) {}
      @Override public void onStopTrackingTouch(SeekBar s) {}
    });
    controls.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));
    timeView = text("0:00 / 0:00", 12, TEXT);
    timeView.setTypeface(Typeface.MONOSPACE);
    controls.addView(timeView);
    lutButton = chip("LUT: None", v -> chooseLut());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38)); lp.leftMargin = dp(8);
    controls.addView(lutButton, lp);
    Button share = chip("SHARE", v -> share());
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(38)); sp.leftMargin = dp(6);
    controls.addView(share, sp);
    right.addView(controls);
    root.addView(right, new LinearLayout.LayoutParams(0, -1, 0.64f));
    setContentView(root);
    String perm = android.os.Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_VIDEO" : "android.permission.READ_EXTERNAL_STORAGE";
    if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[] {perm}, 7);
    loadClips();
  }

  @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) { loadClips(); }

  @Override protected void onResume() {
    super.onResume();
    video.onResume();
    WindowInsetsController c = getWindow().getInsetsController();
    if (c != null) { c.hide(WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
    ui.post(progress);
  }

  @Override protected void onPause() {
    ui.removeCallbacks(progress);
    releasePlayer();
    video.onPause();
    super.onPause();
  }

  @Override protected void onDestroy() { background.shutdownNow(); super.onDestroy(); }

  // ------------------------------------------------------------------------------------------------ clips

  private void loadClips() {
    background.execute(() -> {
      List<Clip> found = new ArrayList<>();
      Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
      String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.MIME_TYPE};
      try (Cursor c = getContentResolver().query(collection, cols, MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
          new String[] {Environment.DIRECTORY_MOVIES + "/City Cinema%"}, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
        while (c != null && c.moveToNext()) {
          Clip k = new Clip();
          k.id = c.getLong(0); k.name = c.getString(1); k.durationMs = c.getLong(2); k.size = c.getLong(3); k.mime = c.getString(4);
          k.uri = ContentUris.withAppendedId(collection, k.id);
          found.add(k);
        }
      } catch (RuntimeException e) { Log.w(TAG, "query", e); }
      Map<String, String> looks = sidecarLooks();
      for (Clip k : found) k.look = looks.get(k.name.replaceAll("\\.(mp4|mov)$", ""));
      ui.post(() -> { clips.clear(); clips.addAll(found); adapter.notifyDataSetChanged(); emptyView.setVisibility(clips.isEmpty() ? View.VISIBLE : View.GONE); });
      for (Clip k : found) {
        try { k.thumb = getContentResolver().loadThumbnail(k.uri, new Size(320, 180), null); } catch (Exception e) { k.thumb = null; }
        ui.post(adapter::notifyDataSetChanged);
      }
    });
  }

  /** Reads the look of each clip from its JSON sidecar in Documents/City Cinema. */
  private Map<String, String> sidecarLooks() {
    Map<String, String> out = new HashMap<>();
    Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
    try (Cursor c = getContentResolver().query(files, new String[] {MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME},
        MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?",
        new String[] {Environment.DIRECTORY_DOCUMENTS + "/City Cinema%", "%.json"}, null)) {
      while (c != null && c.moveToNext()) {
        Uri u = ContentUris.withAppendedId(files, c.getLong(0));
        try (InputStream in = getContentResolver().openInputStream(u)) {
          if (in == null) continue;
          ByteArrayOutputStream b = new ByteArrayOutputStream();
          byte[] buf = new byte[4096]; int n;
          while ((n = in.read(buf)) > 0 && b.size() < 64 * 1024) b.write(buf, 0, n);
          JSONObject j = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
          out.put(j.optString("clip"), j.optString("look"));
        } catch (Exception ignored) {}
      }
    } catch (RuntimeException e) { Log.w(TAG, "sidecars", e); }
    return out;
  }

  // ------------------------------------------------------------------------------------------------ playback

  private void play(Clip k) {
    current = k;
    titleView.setText(k.name + "  ·  " + formatTime(k.durationMs) + "  ·  " + (k.size / 1_000_000) + " MB" + (k.look != null && !k.look.isEmpty() ? "  ·  " + k.look : ""));
    adapter.notifyDataSetChanged();
    releasePlayer();
    if ("video/quicktime".equals(k.mime) || k.name.endsWith(".mov")) {
      new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("ProRes clip")
          .setMessage("Android has no ProRes decoder, so ProRes .mov clips can't play on the phone (the Gallery can't either). "
              + "They play in DaVinci Resolve, Final Cut Pro, Premiere and VLC on a computer. Share sends the file.")
          .setPositiveButton("OK", null).show();
      return;
    }
    setLut(k.look != null && java.util.Arrays.asList(LUT_LOOKS).contains(k.look) && CinemaMath.isLogLook(k.look) ? k.look : "None");
    Surface surface = renderer.surface();
    if (surface == null) { ui.postDelayed(() -> play(k), 100); return; }
    try {
      player = new MediaPlayer();
      player.setDataSource(this, k.uri);
      player.setSurface(surface);
      player.setOnVideoSizeChangedListener((mp, w, h) -> renderer.setVideoSize(w, h));
      player.setOnCompletionListener(mp -> playButton.setText("▶"));
      player.setOnErrorListener((mp, what, extra) -> { titleView.setText(k.name + " · can't play this clip (" + what + "/" + extra + ")"); return true; });
      player.setOnPreparedListener(mp -> { mp.start(); playButton.setText("❚❚"); });
      player.prepareAsync();
    } catch (Exception e) {
      titleView.setText(k.name + " · can't open: " + e.getMessage());
      releasePlayer();
    }
  }

  private void togglePlay() {
    if (player == null) { if (current != null) play(current); return; }
    if (player.isPlaying()) { player.pause(); playButton.setText("▶"); } else { player.start(); playButton.setText("❚❚"); }
  }

  private void releasePlayer() {
    if (player != null) { try { player.release(); } catch (RuntimeException ignored) {} player = null; }
    playButton.setText("▶");
  }

  private final Runnable progress = new Runnable() {
    @Override public void run() {
      MediaPlayer p = player;
      if (p != null) {
        try {
          int pos = p.getCurrentPosition(), dur = Math.max(1, p.getDuration());
          seek.setProgress((int) ((long) pos * 1000 / dur));
          timeView.setText(formatTime(pos) + " / " + formatTime(dur));
        } catch (IllegalStateException ignored) {}
      }
      ui.postDelayed(this, 200);
    }
  };

  private void chooseLut() {
    int sel = java.util.Arrays.asList(LUT_LOOKS).indexOf(lut);
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Viewing LUT (playback only, the file is unchanged)")
        .setSingleChoiceItems(LUT_LOOKS, sel, (d, i) -> { d.dismiss(); setLut(LUT_LOOKS[i]); }).show();
  }

  private void setLut(String look) {
    lut = look;
    lutButton.setText("LUT: " + look);
    if ("None".equals(look)) { renderer.setLut(null); return; }
    background.execute(() -> {
      final int n = 33;
      ByteBuffer data = ByteBuffer.allocateDirect(n * n * n * 3).order(ByteOrder.nativeOrder());
      double d = n - 1;
      for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
        double[] o = CinemaMath.logToRec709(look, r / d, g / d, b / d);
        for (int i = 0; i < 3; i++) data.put((byte) Math.round(CinemaMath.clamp01(o[i]) * 255));
      }
      data.flip();
      renderer.setLut(data);
    });
  }

  private void share() {
    if (current == null) return;
    Intent i = new Intent(Intent.ACTION_SEND);
    i.setType(current.mime != null ? current.mime : "video/*");
    i.putExtra(Intent.EXTRA_STREAM, current.uri);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    startActivity(Intent.createChooser(i, "Share " + current.name));
  }

  static String formatTime(long ms) { long s = ms / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60); }

  // ------------------------------------------------------------------------------------------------ list UI

  final class ClipAdapter extends BaseAdapter {
    @Override public int getCount() { return clips.size(); }
    @Override public Object getItem(int i) { return clips.get(i); }
    @Override public long getItemId(int i) { return clips.get(i).id; }
    @Override public View getView(int i, View convert, ViewGroup parent) {
      LinearLayout row = convert instanceof LinearLayout ? (LinearLayout) convert : newRow();
      Clip k = clips.get(i);
      ((ImageView) row.getChildAt(0)).setImageBitmap(k.thumb);
      LinearLayout texts = (LinearLayout) row.getChildAt(1);
      ((TextView) texts.getChildAt(0)).setText(k.name);
      boolean prores = k.name.endsWith(".mov");
      ((TextView) texts.getChildAt(1)).setText(formatTime(k.durationMs) + " · " + (k.size / 1_000_000) + " MB · " + (prores ? "ProRes" : "MP4") + (k.look != null && !k.look.isEmpty() ? " · " + k.look : ""));
      row.setBackgroundColor(k == current ? 0xff22272c : 0);
      return row;
    }
    private LinearLayout newRow() {
      LinearLayout row = new LinearLayout(ClipPlayerActivity.this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(8), dp(5), dp(8), dp(5));
      ImageView img = new ImageView(ClipPlayerActivity.this);
      img.setScaleType(ImageView.ScaleType.CENTER_CROP);
      img.setBackgroundColor(0xff111315);
      row.addView(img, new LinearLayout.LayoutParams(dp(96), dp(54)));
      LinearLayout texts = new LinearLayout(ClipPlayerActivity.this);
      texts.setOrientation(LinearLayout.VERTICAL);
      texts.setPadding(dp(10), 0, 0, 0);
      texts.addView(text("", 13, TEXT));
      texts.addView(text("", 11, DIM));
      row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
      return row;
    }
  }

  private int dp(float v) { return Math.round(v * density); }

  private TextView text(String s, float sp, int color) {
    TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setSingleLine();
    t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)); return t;
  }

  private Button chip(String s, View.OnClickListener l) {
    Button b = new Button(this);
    b.setText(s); b.setAllCaps(false); b.setTextSize(12); b.setTextColor(TEXT); b.setMinHeight(0); b.setMinimumHeight(0);
    b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(12), 0, dp(12), 0);
    GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(6)); d.setColor(0xff22272c); d.setStroke(dp(1), 0xff2a3036);
    b.setBackground(d); b.setStateListAnimator(null); b.setOnClickListener(l);
    return b;
  }

  // ------------------------------------------------------------------------------------------------ GL renderer

  /** Draws video frames from a SurfaceTexture, optionally through a 3D LUT, letterboxed to the video's aspect. */
  final class PlayerRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private SurfaceTexture texture;
    private volatile Surface surface;
    private int program, oesTex, lutTex, viewW = 1, viewH = 1;
    private volatile int videoW = 16, videoH = 9;
    private volatile ByteBuffer pendingLut;
    private volatile boolean lutChanged, lutOn;
    private final float[] stMatrix = new float[16];
    private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    Surface surface() { return surface; }

    void setVideoSize(int w, int h) { if (w > 0 && h > 0) { videoW = w; videoH = h; video.requestRender(); } }

    void setLut(ByteBuffer data) { pendingLut = data; lutChanged = true; video.requestRender(); }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
      int[] t = new int[2];
      GLES30.glGenTextures(2, t, 0);
      oesTex = t[0]; lutTex = t[1];
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
      GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
      GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
      GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex);
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
      for (int p : new int[] {GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R}) GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES30.GL_CLAMP_TO_EDGE);
      texture = new SurfaceTexture(oesTex);
      texture.setOnFrameAvailableListener(this);
      surface = new Surface(texture);
      program = buildProgram(
          "#version 300 es\nin vec4 aPos; in vec4 aTex; uniform mat4 uSt; out vec2 vTex;\nvoid main() { gl_Position = aPos; vTex = (uSt * aTex).xy; }\n",
          "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\nprecision mediump float;\n"
              + "uniform samplerExternalOES uTex; uniform mediump sampler3D uLut; uniform float uLutOn;\nin vec2 vTex; out vec4 o;\n"
              + "void main() { vec3 c = texture(uTex, vTex).rgb; if (uLutOn > 0.5) c = texture(uLut, c * (32.0 / 33.0) + 0.5 / 33.0).rgb; o = vec4(c, 1.0); }\n");
      if (pendingLut != null) lutChanged = true;
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) { viewW = w; viewH = h; }

    @Override public void onFrameAvailable(SurfaceTexture st) { video.requestRender(); }

    @Override public void onDrawFrame(GL10 gl) {
      if (lutChanged) {
        lutChanged = false;
        ByteBuffer d = pendingLut;
        lutOn = d != null;
        if (d != null) {
          GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex);
          GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
          GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, 33, 33, 33, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, d);
        }
      }
      try { texture.updateTexImage(); texture.getTransformMatrix(stMatrix); } catch (RuntimeException e) { return; }
      GLES30.glViewport(0, 0, viewW, viewH);
      GLES30.glClearColor(0, 0, 0, 1);
      GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
      if (program == 0) return;
      // letterbox: scale the quad to the video's aspect inside the view
      float va = videoW / (float) videoH, sa = viewW / (float) viewH, sx = 1, sy = 1;
      if (va > sa) sy = sa / va; else sx = va / sa;
      quad.clear();
      quad.put(new float[] {-sx, -sy, 0, 0, sx, -sy, 1, 0, -sx, sy, 0, 1, sx, sy, 1, 1});
      GLES30.glUseProgram(program);
      int aPos = GLES30.glGetAttribLocation(program, "aPos"), aTex = GLES30.glGetAttribLocation(program, "aTex");
      quad.position(0);
      GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, quad);
      GLES30.glEnableVertexAttribArray(aPos);
      quad.position(2);
      GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 16, quad);
      GLES30.glEnableVertexAttribArray(aTex);
      GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uSt"), 1, false, stMatrix, 0);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
      GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0);
      GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
      GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex);
      GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1);
      GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uLutOn"), lutOn ? 1f : 0f);
      GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private int buildProgram(String vs, String fs) {
      int v = shader(GLES30.GL_VERTEX_SHADER, vs), f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
      if (v == 0 || f == 0) return 0;
      int p = GLES30.glCreateProgram();
      GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p);
      int[] ok = new int[1];
      GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
      if (ok[0] == 0) { Log.w(TAG, "link: " + GLES30.glGetProgramInfoLog(p)); return 0; }
      return p;
    }

    private int shader(int type, String src) {
      int s = GLES30.glCreateShader(type);
      GLES30.glShaderSource(s, src); GLES30.glCompileShader(s);
      int[] ok = new int[1];
      GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
      if (ok[0] == 0) { Log.w(TAG, "shader: " + GLES30.glGetShaderInfoLog(s)); return 0; }
      return s;
    }
  }
}

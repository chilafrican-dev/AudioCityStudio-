package ug.audiocity.cinema;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.TonemapCurve;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * City Cinema: a manual cinema camera for Android phones. Camera2 with full manual exposure (ISO, shutter
 * angle/speed, exact NTSC frame durations), sensor-calibrated Kelvin white balance with tint, manual focus with
 * A/B focus pulls, eased zoom ramps, log and film looks, 10-bit HLG HEVC, constant-frame-rate MP4 with
 * selectable bitrate/GOP, off-speed (slow-motion) capture, double-system 32-bit float WAV, pro monitoring and
 * camera-style clip naming with a metadata sidecar per clip.
 */
public class CinemaActivity extends Activity {
  static final String TAG = "CityCinema";
  static final int BG = 0xff0b0d0f, BAR = 0xb30b0d0f, PANEL = 0xe0121518, LINE = 0xff2a3036, TEXT = 0xffeef1f3, DIM = 0xff8a939b,
      ACCENT = 0xffffb341, REC = 0xffff3b30, OK = 0xff53e07a;

  // ------------------------------------------------------------------ camera state
  private CameraManager manager;
  private final List<Lens> lenses = new ArrayList<>();
  private Lens lens;
  private CameraDevice device;
  private CameraCaptureSession session;
  private boolean highSpeedSession;
  private HandlerThread cameraThread, analysisThread;
  private Handler cameraHandler, analysisHandler;
  private Executor cameraExecutor;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private Surface previewSurface, encoderSurface;
  private CinemaRecorder recorder;
  private RawRecorder raw;
  private volatile float[] liveBlack;
  private volatile float[][] liveShading;
  private volatile int shadingRows, shadingCols;
  private int shadingCounter;
  private final CinemaRecorder.AudioEngine audio = new CinemaRecorder.AudioEngine();
  private volatile boolean recording, opening;
  private boolean triedFallback;

  // live values reported by the camera (for auto readouts and auto→manual handover)
  private volatile int liveIso = 400;
  private volatile long liveExposure = 20_833_333L;
  private volatile float liveFocus = 0;
  private volatile float[] liveGains;
  private volatile ColorSpaceTransform liveTransform;
  private volatile Integer liveAfState;
  /** Whether the camera really applies our tone curves (some report support but ignore them). */
  private volatile boolean curveIgnored, curveHonoured;
  private int curveChecks;

  // ------------------------------------------------------------------ settings
  private SharedPreferences prefs;
  private Size videoSize = new Size(1920, 1080);
  /** RAW mode records CinemaDNG frame sequences instead of MP4. */
  private boolean rawMode = false, rawPack12 = true, rawCompress = true;
  /** The chosen RAW option (full frame, 8K crop, maximum-resolution mode), by key. */
  private String rawKey = "";
  private RawOption rawOpt;
  private boolean maxResSession;
  private android.widget.ImageView monitorView;
  private final Bitmap[] monitorBitmaps = new Bitmap[2];
  private int monitorIndex;
  /** ProRes mode records ProRes 422 (software encoder) into a QuickTime .mov. Exclusive with rawMode. */
  private boolean proresMode = false;
  private int proresProfile = 3;
  private Size proresSize = new Size(1920, 1080);
  private ProResRecorder prores;
  private Size proresDims;
  private Size rawSize;
  private double fps = 24, conformFps = 0;
  private boolean hevc = true, tenBit = false, cbr = false, wavOn = true, shutterAsAngle = true, todTimecode = false, audioOn = true;
  private int quality = 2, gop = 0, lookIndex = 0, detail = 0, stabilization = 0, antibanding = 0, reel = 1, clip = 1;
  private char cameraLetter = 'A';
  private String audioSource = "Camcorder";
  private int audioDeviceId = 0;
  // exposure
  private boolean aeAuto = true, aeLock = false;
  private int iso = 400, evIndex = 0;
  private double shutterAngle = 180;
  private long shutterNs = 0; // used when shutterAsAngle == false
  // white balance
  private boolean awbAuto = true, awbLock = false;
  private int kelvin = 5600, tint = 0;
  // focus & zoom
  private boolean manualFocus = false;
  private float focus = 0, markA = -1, markB = -1;
  private int pullSeconds = 2, zoomRampSeconds = 0;
  private float zoom = 1f;
  private float[] meteringPoint;
  private long recordStartNs, recordRunBase;
  private long takeStartWall;

  // ------------------------------------------------------------------ UI
  private FrameLayout root;
  private TextureView texture;
  private CinemaScopes scopes;
  private LinearLayout topBar, bottomBar, side, panel;
  private TextView tcView, formatView, storageView, batteryView, lensView, messageView, recLabel;
  private Cell fpsCell, shutterCell, isoCell, wbCell, focusCell, zoomCell, lookCell;
  private Meter meter;
  private RecButton recButton;
  private String openPanel;
  private final List<Toggle> toggles = new ArrayList<>();
  private Bitmap grab;
  private final AtomicBoolean analysing = new AtomicBoolean();
  private float density;
  private SensorManager sensors;
  private float rollFiltered;
  private Runnable ramp;

  // ================================================================== lifecycle

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    density = getResources().getDisplayMetrics().density;
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    prefs = getSharedPreferences("cinema", MODE_PRIVATE);
    loadPrefs();
    manager = (CameraManager) getSystemService(CAMERA_SERVICE);
    sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
    buildUi();
    try { findLenses(); } catch (CameraAccessException | RuntimeException e) { message("Camera unavailable: " + e.getMessage()); }
  }

  @Override protected void onResume() {
    super.onResume();
    immersive();
    cameraThread = new HandlerThread("cinema-camera"); cameraThread.start(); cameraHandler = new Handler(cameraThread.getLooper());
    cameraExecutor = cameraHandler::post;
    analysisThread = new HandlerThread("cinema-scopes"); analysisThread.start(); analysisHandler = new Handler(analysisThread.getLooper());
    Sensor accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    if (accel != null) sensors.registerListener(levelListener, accel, SensorManager.SENSOR_DELAY_UI);
    List<String> need = new ArrayList<>();
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
    if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    else startEverything();
    ui.post(tick);
  }

  @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startEverything();
    else message("City Cinema needs camera permission.");
  }

  private void startEverything() {
    startAudio();
    if (texture.isAvailable()) openCamera(); // otherwise the surface listener opens it
  }

  @Override protected void onPause() {
    ui.removeCallbacks(tick);
    sensors.unregisterListener(levelListener);
    if (ramp != null) { ui.removeCallbacks(ramp); ramp = null; }
    final Object done = new Object();
    final boolean[] finished = {false};
    if (cameraHandler != null) cameraHandler.post(() -> {
      if (recording) { if (rawMode) stopRawOnCamera(); else if (proresMode) stopProResOnCamera(); else stopRecordingOnCamera(); }
      closeCamera();
      if (raw != null) { raw.release(); raw = null; }
      if (prores != null) { prores.release(); prores = null; }
      synchronized (done) { finished[0] = true; done.notifyAll(); }
    });
    synchronized (done) { long until = SystemClock.elapsedRealtime() + 6000; while (!finished[0] && SystemClock.elapsedRealtime() < until) { try { done.wait(200); } catch (InterruptedException e) { break; } } }
    audio.stop();
    cameraThread.quitSafely(); analysisThread.quitSafely();
    cameraThread = analysisThread = null; cameraHandler = analysisHandler = null;
    savePrefs();
    super.onPause();
  }

  @Override protected void onDestroy() {
    if (encoderSurface != null) { encoderSurface.release(); encoderSurface = null; }
    super.onDestroy();
  }

  @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive(); }

  /** Volume keys start/stop recording, like a camera's REC button. */
  @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
    if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) && event.getRepeatCount() == 0) { toggleRecord(); return true; }
    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true;
    return super.onKeyDown(keyCode, event);
  }

  private void immersive() {
    WindowInsetsController c = getWindow().getInsetsController();
    if (c != null) { c.hide(WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
  }

  // ================================================================== lenses & capabilities

  /** One selectable camera with everything we need to know about it. */
  static final class Lens {
    String id, label; CameraCharacteristics chars; boolean front, manualSensor, manualPost, highSpeed, hlg;
    Range<Integer> isoRange = new Range<>(100, 3200); Range<Long> expRange = new Range<>(100_000L, 100_000_000L);
    float minFocus, maxZoom = 1, minZoom = 1; int sensorOrientation, curvePoints; Rect active; StreamConfigurationMap map;
    Range<Integer>[] fpsRanges; Range<Integer> evRange = new Range<>(0, 0); Rational evStep = new Rational(1, 3);
    int[] stab, ois, tonemapModes, edgeModes, nrModes, antibanding; boolean realtimeClock;
    float[] cm1, cm2; int k1, k2;
    // RAW (CinemaDNG) support
    boolean raw; Size[] rawSizes = new Size[0]; int cfa, whiteLevel = 1023, ill1 = 21, ill2 = 17;
    float[] black = {64, 64, 64, 64}, fm1, fm2, cal1, cal2;
    // maximum-resolution ("ultra high resolution") sensor mode, Android 12+
    boolean maxRes; Size[] maxResRawSizes = new Size[0]; StreamConfigurationMap maxResMap; int binning = 1;
    CinemaMath.WbModel wb = CinemaMath.GENERIC_WB;
  }

  @SuppressWarnings("unchecked")
  private void findLenses() throws CameraAccessException {
    lenses.clear();
    for (String id : manager.getCameraIdList()) {
      CameraCharacteristics c = manager.getCameraCharacteristics(id);
      int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
      if (caps == null || !contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue;
      Integer facing = c.get(CameraCharacteristics.LENS_FACING);
      if (facing != null && facing == CameraMetadata.LENS_FACING_EXTERNAL) { /* USB cameras are welcome too */ }
      Lens l = new Lens(); l.id = id; l.chars = c; l.front = facing != null && facing == CameraMetadata.LENS_FACING_FRONT;
      l.manualSensor = contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
      l.manualPost = contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING);
      l.highSpeed = contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO);
      if (Build.VERSION.SDK_INT >= 33 && contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)) {
        DynamicRangeProfiles p = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
        l.hlg = p != null && p.getSupportedProfiles().contains(DynamicRangeProfiles.HLG10);
      }
      Range<Integer> isoR = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE); if (isoR != null) l.isoRange = isoR;
      Range<Long> expR = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE); if (expR != null) l.expRange = expR;
      Float mf = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE); l.minFocus = mf == null ? 0 : mf;
      Range<Float> zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
      if (zr != null) { l.minZoom = zr.getLower(); l.maxZoom = zr.getUpper(); }
      else { Float dz = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM); l.maxZoom = dz == null ? 1 : dz; }
      Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION); l.sensorOrientation = so == null ? 90 : so;
      l.active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
      l.map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      l.fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
      Range<Integer> ev = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE); if (ev != null) l.evRange = ev;
      Rational step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP); if (step != null) l.evStep = step;
      l.stab = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
      l.ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
      l.tonemapModes = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
      Integer cp = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS); l.curvePoints = cp == null ? 0 : cp;
      l.edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
      l.nrModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
      l.antibanding = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES);
      Integer tsSource = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
      l.realtimeClock = tsSource != null && tsSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
      setUpWbModel(l);
      float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
      SizeF phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
      String mm = "";
      if (focal != null && focal.length > 0 && phys != null) {
        double diag = Math.hypot(phys.getWidth(), phys.getHeight());
        mm = " " + Math.round(focal[0] * 43.27 / diag) + "mm";
      }
      setUpRaw(l, caps);
      l.label = (l.front ? "Front" : facing != null && facing == CameraMetadata.LENS_FACING_EXTERNAL ? "USB" : "Back") + mm;
      if (l.map != null) lenses.add(l);
    }
    // Back cameras first, then the front camera.
    Collections.sort(lenses, (a, b) -> Boolean.compare(a.front, b.front));
    if (lenses.isEmpty()) { message("No usable camera found."); return; }
    int saved = prefs.getInt("lens", 0);
    lens = lenses.get(Math.max(0, Math.min(saved, lenses.size() - 1)));
    validateFormat();
  }

  /** Uses the sensor's own XYZ→camera matrices for accurate Kelvin white balance when the HAL provides them. */
  private static void setUpWbModel(Lens l) {
    ColorSpaceTransform t1 = l.chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1), t2 = l.chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2);
    Integer i1 = l.chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
    Byte i2 = l.chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
    if (t1 == null || i1 == null) return;
    l.cm1 = matrix(t1); l.k1 = CinemaMath.illuminantKelvin(i1);
    l.cm2 = t2 != null && i2 != null ? matrix(t2) : l.cm1; l.k2 = t2 != null && i2 != null ? CinemaMath.illuminantKelvin(i2) : l.k1;
    final float[] a = l.cm1, b = l.cm2; final int ka = l.k1, kb = l.k2;
    l.wb = (k, tint) -> CinemaMath.calibratedGains(a, ka, b, kb, k, tint);
  }

  /** RAW needs the RAW capability, a Bayer sensor and RAW_SENSOR output sizes. */
  private static void setUpRaw(Lens l, int[] caps) {
    if (!contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) || l.map == null) return;
    Integer cfa = l.chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
    Size[] sizes = l.map.getOutputSizes(ImageFormat.RAW_SENSOR);
    if (cfa == null || cfa > 3 || sizes == null || sizes.length == 0) return;
    l.raw = true; l.cfa = cfa; l.rawSizes = sizes;
    Arrays.sort(l.rawSizes, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
    Integer white = l.chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL); if (white != null) l.whiteLevel = white;
    BlackLevelPattern bl = l.chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
    if (bl != null) for (int i = 0; i < 4; i++) l.black[i] = bl.getOffsetForIndex(i % 2, i / 2);
    Integer i1 = l.chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1); if (i1 != null) l.ill1 = i1;
    Byte i2 = l.chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2); if (i2 != null) l.ill2 = i2;
    ColorSpaceTransform t;
    if ((t = l.chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)) != null) l.fm1 = matrix(t);
    if ((t = l.chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)) != null) l.fm2 = matrix(t);
    if ((t = l.chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)) != null) l.cal1 = matrix(t);
    if ((t = l.chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)) != null) l.cal2 = matrix(t);
    // 50-200 MP sensors bin pixels by default; their maximum-resolution mode streams the full sensor (8K+).
    if (Build.VERSION.SDK_INT >= 31 && contains(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)) {
      StreamConfigurationMap mm = l.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
      Size bf = l.chars.get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR);
      Size[] ms = mm == null ? null : mm.getOutputSizes(ImageFormat.RAW_SENSOR);
      int bin = bf == null ? 1 : bf.getWidth();
      // Supported: native Bayer (no binning) or quad Bayer (2x2), which a pixel shuffle turns into Bayer.
      if (ms != null && ms.length > 0 && (bin == 1 || bin == 2) && (bf == null || bf.getHeight() == bin)) {
        Arrays.sort(ms, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
        l.maxRes = true; l.maxResMap = mm; l.maxResRawSizes = ms; l.binning = bin;
      }
    }
  }

  /** One way of recording RAW: a sensor output size, a crop of it, and the sensor mode. */
  static final class RawOption {
    String key, label; Size sensor; int cropX, cropY, width, height; boolean maxRes, quad; long minFrameNs;
    boolean is8k() { return width >= 7680; }
    RawRecorder.Geometry geometry() {
      RawRecorder.Geometry g = new RawRecorder.Geometry();
      g.sensorW = sensor.getWidth(); g.sensorH = sensor.getHeight(); g.cropX = cropX; g.cropY = cropY; g.width = width; g.height = height; g.quad = quad;
      return g;
    }
  }

  /** All RAW options of the lens: each full-frame size, 8K UHD/DCI crops, and maximum-resolution mode. */
  private List<RawOption> rawOptions() {
    List<RawOption> out = new ArrayList<>();
    if (lens == null || !lens.raw) return out;
    for (Size s : lens.rawSizes) out.add(rawOption(s.getWidth() + "×" + s.getHeight(), s, s.getWidth(), s.getHeight(), false, false, lens.map));
    addCrops(out, lens.rawSizes[0], false, false, lens.map);
    if (lens.maxRes) {
      Size m = lens.maxResRawSizes[0];
      boolean quad = lens.binning == 2;
      addCrops(out, m, true, quad, lens.maxResMap);
      out.add(rawOption("Full sensor " + m.getWidth() + "×" + m.getHeight() + " (max-res" + (quad ? ", remosaic" : "") + ")", m, m.getWidth(), m.getHeight(), true, quad, lens.maxResMap));
    }
    return out;
  }

  private void addCrops(List<RawOption> out, Size sensor, boolean maxRes, boolean quad, StreamConfigurationMap map) {
    int[][] crops = {{7680, 4320}, {8192, 4320}};
    String[] names = {"8K UHD", "8K DCI"};
    for (int i = 0; i < crops.length; i++) {
      if (sensor.getWidth() < crops[i][0] || sensor.getHeight() < crops[i][1]) continue;
      String label = names[i] + " " + crops[i][0] + "×" + crops[i][1] + (maxRes ? " (max-res" + (quad ? ", remosaic" : "") + ")" : " (crop)");
      out.add(rawOption(label, sensor, crops[i][0], crops[i][1], maxRes, quad, map));
    }
  }

  private static RawOption rawOption(String label, Size sensor, int w, int h, boolean maxRes, boolean quad, StreamConfigurationMap map) {
    RawOption o = new RawOption();
    o.label = label; o.key = label; o.sensor = sensor; o.width = w; o.height = h; o.maxRes = maxRes; o.quad = quad;
    int align = quad ? 4 : 2; // crops keep the CFA phase (and the 4x4 quad tiles)
    o.cropX = ((sensor.getWidth() - w) / 2) / align * align;
    o.cropY = ((sensor.getHeight() - h) / 2) / align * align;
    long d = 0;
    try { d = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, sensor); } catch (RuntimeException ignored) {}
    o.minFrameNs = d > 0 ? d : CinemaMath.NS / 30;
    return o;
  }

  /** The highest frame rate a RAW option streams at. */
  static double maxFps(RawOption o) { return CinemaMath.NS / (double) o.minFrameNs; }

  /** The recorded frame: the RAW size in RAW mode, otherwise the video size. */
  private Size frameSize() { return rawMode && rawSize != null ? rawSize : proresMode ? proresSize : videoSize; }

  /** RAW options the sensor can deliver at the current frame rate. */
  private List<RawOption> rawOptionsForRate() {
    List<RawOption> out = new ArrayList<>();
    long frameNs = CinemaMath.frameDurationNs(fps);
    for (RawOption o : rawOptions()) if (o.minFrameNs <= frameNs + 1000) out.add(o);
    return out;
  }

  /** A preview buffer with the RAW frame's aspect ratio, at most 1920 wide (RAW sizes are too big to preview). */
  private Size previewSizeFor(Size frame) {
    Size[] sizes = lens.map.getOutputSizes(SurfaceTexture.class);
    double aspect = frame.getWidth() / (double) frame.getHeight();
    Size best = null;
    if (sizes != null) for (Size s : sizes) {
      if (s.getWidth() > 1920 || Math.abs(s.getWidth() / (double) s.getHeight() - aspect) > 0.02) continue;
      if (best == null || s.getWidth() > best.getWidth()) best = s;
    }
    return best != null ? best : new Size(1440, (int) Math.round(1440 / aspect));
  }

  /** Whether RAW frames are lossless-JPEG compressed (the sensor's white level allows it and the native code is in). */
  private boolean rawCompressed() { return rawCompress && lens != null && RawNative.compressible(lens.whiteLevel); }

  /** On-disk data rate of RAW recording in bytes per second (lossless: estimated at 2:1 of the packed size). */
  private long rawBytesPerSecond() {
    Size s = frameSize();
    if (rawCompressed()) return (long) (s.getWidth() * (long) s.getHeight() * RawNative.precisionFor(lens.whiteLevel) / 8.0 / 2 * CinemaMath.exactFps(fps));
    boolean packed = rawPack12 && lens != null && lens.whiteLevel <= 4095;
    return (long) (s.getWidth() * (long) s.getHeight() * (packed ? 1.5 : 2) * CinemaMath.exactFps(fps));
  }

  private String rawFormatName() {
    if (rawCompressed()) return "lossless " + RawNative.precisionFor(lens.whiteLevel) + "-bit";
    return (rawPack12 && lens != null && lens.whiteLevel <= 4095 ? 12 : 16) + "-bit uncompressed";
  }

  private static float[] matrix(ColorSpaceTransform t) {
    float[] m = new float[9];
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) m[r * 3 + c] = t.getElement(c, r).floatValue();
    return m;
  }

  static boolean contains(int[] a, int v) { if (a != null) for (int x : a) if (x == v) return true; return false; }

  /** Video sizes the camera and the encoder can both do at the current frame rate, largest first. */
  private List<Size> videoSizes() {
    List<Size> out = new ArrayList<>();
    if (lens == null || lens.map == null) return out;
    CinemaRecorder.EncoderCaps caps = CinemaRecorder.caps(hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC);
    Size[] sizes;
    if (fps > 60) sizes = lens.highSpeed ? lens.map.getHighSpeedVideoSizes() : new Size[0];
    else sizes = lens.map.getOutputSizes(MediaCodec.class);
    if (sizes == null) return out;
    long frameNs = CinemaMath.frameDurationNs(fps);
    for (Size s : sizes) {
      if (s.getHeight() < 720 || s.getWidth() > 8192) continue;
      double ar = s.getWidth() / (double) s.getHeight();
      if (ar < 1.30 || ar > 2.0) continue;
      if (fps > 60) {
        boolean ok = false;
        for (Range<Integer> r : lens.map.getHighSpeedVideoFpsRangesFor(s)) if (r.getUpper() == CinemaMath.nominalFps(fps)) ok = true;
        if (!ok) continue;
      } else if (lens.map.getOutputMinFrameDuration(MediaCodec.class, s) > frameNs + 1000) continue;
      if (caps.available && !caps.supports(s.getWidth(), s.getHeight(), Math.min(fps, 240))) continue;
      out.add(s);
    }
    Collections.sort(out, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
    return out;
  }

  /** Frame rates available on this lens at any size. */
  private List<Double> availableRates() {
    List<Double> out = new ArrayList<>();
    if (lens == null) return out;
    int maxAe = 30;
    if (lens.fpsRanges != null) for (Range<Integer> r : lens.fpsRanges) maxAe = Math.max(maxAe, r.getUpper());
    List<Integer> hs = new ArrayList<>();
    if (lens.highSpeed) for (Range<Integer> r : lens.map.getHighSpeedVideoFpsRanges()) hs.add(r.getUpper());
    if (proresMode) {
      List<Size> sizes = proresSizesAll();
      long min = sizes.isEmpty() ? Long.MAX_VALUE : Long.MAX_VALUE;
      for (Size sz : sizes) min = Math.min(min, lens.map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, sz));
      for (double f : CinemaMath.FRAME_RATES) if (f <= 60 && CinemaMath.frameDurationNs(f) + 1000 >= min && (CinemaMath.nominalFps(f) <= maxAe || lens.manualSensor)) out.add(f);
      return out;
    }
    if (rawMode && lens.raw) {
      // rates the chosen option streams; 8K maximum-resolution modes are often 5-15 fps, so offer those too
      long min = rawOpt != null ? rawOpt.minFrameNs : lens.map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, lens.rawSizes[lens.rawSizes.length - 1]);
      if (min > CinemaMath.frameDurationNs(23.976) + 1000) for (double f : CinemaMath.RAW_SLOW_RATES) if (CinemaMath.frameDurationNs(f) + 1000 >= min) out.add(f);
      for (double f : CinemaMath.FRAME_RATES) if (f <= 60 && CinemaMath.frameDurationNs(f) + 1000 >= min && (CinemaMath.nominalFps(f) <= maxAe || lens.manualSensor)) out.add(f);
      return out;
    }
    for (double f : CinemaMath.FRAME_RATES) {
      if (f <= 60) { if (CinemaMath.nominalFps(f) <= maxAe || (lens.manualSensor && f <= 30)) out.add(f); }
      else if (hs.contains(CinemaMath.nominalFps(f))) out.add(f);
    }
    return out;
  }

  /** Keeps the saved format inside what the selected lens and encoder can do. */
  private void validateFormat() {
    if (proresMode && !ProRes.AVAILABLE) { proresMode = false; message("This build has no ProRes encoder. Switched to MP4."); }
    if (proresMode) {
      rawMode = false; tenBit = false;
      List<Double> rates = availableRates();
      if (!rates.isEmpty() && !rates.contains(fps)) fps = rates.contains(24.0) ? 24 : rates.get(0);
      List<Size> ps = proresSizes();
      if (!ps.isEmpty() && !ps.contains(proresSize)) {
        Size pick = ps.get(ps.size() - 1);
        for (Size sz : ps) if (sz.getWidth() == 1920 && sz.getHeight() == 1080) pick = sz;
        proresSize = pick;
      }
    }
    if (rawMode && !lens.raw) { rawMode = false; message(lens.label + " can't record RAW. Switched to MP4."); }
    if (rawMode) {
      tenBit = false;
      List<Double> rates = availableRates();
      if (!rates.isEmpty() && !rates.contains(fps)) {
        double pick = rates.get(0);
        for (double r : rates) if (r <= 30) pick = r; // the fastest rate up to 30, unless 24 is available
        fps = rates.contains(24.0) ? 24 : pick;
      }
      rawOpt = null;
      for (RawOption o : rawOptions()) if (o.key.equals(rawKey)) rawOpt = o;
      if (rawOpt == null) rawOpt = rawOptions().get(0);
      rawKey = rawOpt.key;
      rawSize = new Size(rawOpt.width, rawOpt.height);
      List<Double> optionRates = availableRates();
      if (!optionRates.isEmpty() && !optionRates.contains(fps)) {
        double pick = optionRates.get(optionRates.size() - 1);
        for (double r : optionRates) if (r <= 30) pick = r;
        fps = optionRates.contains(24.0) ? 24 : pick;
      }
    }
    List<Double> rates = availableRates();
    if (!rates.isEmpty() && !rates.contains(fps)) fps = rates.contains(24.0) ? 24 : rates.get(0);
    if (fps > 60) tenBit = false;
    if (tenBit && (!lens.hlg || !CinemaRecorder.caps(MediaFormat.MIMETYPE_VIDEO_HEVC).main10)) tenBit = false;
    if (tenBit) hevc = true;
    List<Size> sizes = videoSizes();
    if (sizes.isEmpty() && fps > 60) { fps = 30; sizes = videoSizes(); }
    if (sizes.isEmpty() && hevc) { hevc = false; sizes = videoSizes(); }
    if (!sizes.isEmpty() && !sizes.contains(videoSize)) {
      Size pick = sizes.get(0);
      for (Size s : sizes) if (s.getWidth() == 3840 && s.getHeight() == 2160) pick = s;
      if (!sizes.contains(pick)) pick = sizes.get(0);
      videoSize = pick;
    }
    iso = Math.max(lens.isoRange.getLower(), Math.min(lens.isoRange.getUpper() << CinemaMath.LOG_HEADROOM, iso)); // EI may exceed sensor ISO in the log modes
    zoom = Math.max(lens.minZoom, Math.min(lens.maxZoom, zoom));
    if (!lens.manualSensor) aeAuto = true;
    if (!lens.manualPost && !lens.manualSensor) awbAuto = true;
    if (lens.minFocus <= 0) manualFocus = false;
  }

  // ================================================================== camera open / session

  private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { if (cameraHandler != null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { layoutPreview(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
  };

  private void openCamera() {
    if (lens == null || opening || cameraHandler == null) return;
    opening = true;
    layoutPreview();
    cameraHandler.post(() -> {
      try {
        closeCamera();
        if (encoderSurface == null) encoderSurface = MediaCodec.createPersistentInputSurface();
        if (recorder == null) recorder = new CinemaRecorder(encoderSurface);
        if (!rawMode && raw != null) { raw.release(); raw = null; }
        if (!proresMode && prores != null) { prores.release(); prores = null; }
        if (rawMode) {
          if (raw != null && (raw.isRecording() || !rawOpt.key.equals(rawActiveKey))) { raw.release(); raw = null; }
          if (raw == null) { raw = new RawRecorder(rawOpt.geometry()); rawActiveKey = rawOpt.key; }
          raw.setMonitor(rawOpt.maxRes ? this::onRawMonitor : null, lens.cfa, lens.black, lens.whiteLevel);
        } else if (proresMode) {
          if (prores != null && (prores.isRecording() || !proresSize.equals(proresDims))) { prores.release(); prores = null; }
          if (prores == null) { prores = new ProResRecorder(proresSize.getWidth(), proresSize.getHeight()); proresDims = proresSize; }
        } else {
          recorder.prepare(recorderConfig());
        }
        manager.openCamera(lens.id, cameraExecutor, deviceCallback);
      } catch (CameraAccessException | IOException | RuntimeException e) {
        opening = false; Log.w(TAG, "open", e); message("Couldn't open camera: " + e.getMessage());
        if (tenBit) { tenBit = false; ui.post(this::reopen); }
      }
    });
  }

  private String rawActiveKey;

  /** RAW monitor image (maximum-resolution mode has no preview stream): shown in place of the preview. */
  private void onRawMonitor(int[] argb, int w, int h) {
    Bitmap b = monitorBitmaps[monitorIndex];
    if (b == null || b.getWidth() != w || b.getHeight() != h) { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); monitorBitmaps[monitorIndex] = b; }
    b.setPixels(argb, 0, w, 0, 0, w, h);
    final Bitmap shown = b;
    monitorIndex ^= 1;
    ui.post(() -> {
      monitorView.setImageBitmap(shown);
      if (scopes.needsFrames() && analysisHandler != null && !analysing.get()) {
        analysing.set(true);
        final Bitmap copy = shown.copy(Bitmap.Config.ARGB_8888, false);
        analysisHandler.post(() -> { try { scopes.analyze(copy); } finally { analysing.set(false); } });
      }
    });
  }

  private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
    @Override public void onOpened(CameraDevice d) { device = d; createSession(); }
    @Override public void onDisconnected(CameraDevice d) { d.close(); device = null; opening = false; }
    @Override public void onError(CameraDevice d, int error) { d.close(); device = null; opening = false; message("Camera error " + error + ". Another app may be using the camera."); }
  };

  private void createSession() {
    try {
      SurfaceTexture st = texture.getSurfaceTexture();
      if (st == null || device == null) { opening = false; return; }
      // A preview stream of at most 1920 wide (same aspect): drawing a 4K buffer into a ~1600-pixel view aliases and
      // looks harshly over-sharpened. High-speed sessions need preview and video at the same size.
      Size buffer = rawMode ? previewSizeFor(rawSize) : proresMode ? previewSizeFor(proresSize) : fps > 60 ? videoSize : previewSizeFor(videoSize);
      st.setDefaultBufferSize(buffer.getWidth(), buffer.getHeight());
      if (previewSurface != null) previewSurface.release();
      previewSurface = new Surface(st);
      OutputConfiguration preview = new OutputConfiguration(previewSurface), video = new OutputConfiguration(rawMode ? raw.surface() : proresMode ? prores.surface() : encoderSurface);
      maxResSession = rawMode && rawOpt != null && rawOpt.maxRes && Build.VERSION.SDK_INT >= 31;
      if (maxResSession) video.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
      ui.post(() -> { monitorView.setVisibility(maxResSession ? View.VISIBLE : View.GONE); texture.setAlpha(maxResSession ? 0f : 1f); });
      if (tenBit && Build.VERSION.SDK_INT >= 33) { preview.setDynamicRangeProfile(DynamicRangeProfiles.HLG10); video.setDynamicRangeProfile(DynamicRangeProfiles.HLG10); }
      highSpeedSession = fps > 60 && !rawMode && !proresMode;
      SessionConfiguration config = new SessionConfiguration(highSpeedSession ? SessionConfiguration.SESSION_HIGH_SPEED : SessionConfiguration.SESSION_REGULAR,
          maxResSession ? Collections.singletonList(video) : Arrays.asList(preview, video), cameraExecutor, new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession s) {
              session = s; opening = false; triedFallback = false; applyRepeating(); ui.post(() -> refreshUi());
              if (heldGains == null) ui.postDelayed(() -> { if (gamutActive()) oneShotWhiteBalance(); }, 800); // first white balance in log
            }
            @Override public void onConfigureFailed(CameraCaptureSession s) { opening = false; sessionFailed(); }
          });
      device.createCaptureSession(config);
    } catch (CameraAccessException | RuntimeException e) { Log.w(TAG, "session", e); opening = false; sessionFailed(); }
  }

  /** Falls back to a safe format (8-bit, 30 fps or below, H.264) when the device refuses a session. */
  private void sessionFailed() {
    if (triedFallback) { message("This camera refused the video format. Try a lower resolution or frame rate."); return; }
    triedFallback = true;
    String what = tenBit ? "10-bit HLG" : fps > 60 ? CinemaMath.fpsLabel(fps) + " fps" : "this format";
    message("Camera couldn't run " + what + ". Switched to a safer format.");
    if (proresMode) { List<Size> ps = proresSizes(); int i = ps.indexOf(proresSize); if (i >= 0 && i + 1 < ps.size()) proresSize = ps.get(i + 1); else { proresMode = false; message("This camera couldn't stream YUV for ProRes here. Switched to MP4."); } }
    else if (rawMode) {
      // fall back to the largest ordinary full-frame RAW size
      List<RawOption> all = rawOptions();
      if (rawOpt != null && (rawOpt.maxRes || rawOpt.is8k()) && !all.isEmpty()) { rawKey = all.get(0).key; message("This camera couldn't stream " + rawOpt.label + ". Switched to " + all.get(0).label + " RAW."); }
      else { rawMode = false; message("This camera couldn't stream RAW here. Switched to MP4."); }
    }
    else if (tenBit) tenBit = false; else if (fps > 60) fps = 30; else { hevc = false; List<Size> s = videoSizes(); if (s.size() > 1) videoSize = s.get(1); }
    ui.post(this::reopen);
  }

  private void closeCamera() {
    if (session != null) { try { session.close(); } catch (RuntimeException ignored) {} session = null; }
    if (device != null) { device.close(); device = null; }
    if (recorder != null && !recorder.isRecording()) recorder.release();
  }


  /** Re-opens the camera after a lens or format change. */
  private void reopen() {
    if (recording) { message("Stop recording to change the format."); return; }
    validateFormat(); savePrefs(); refreshUi();
    opening = false;
    openCamera();
  }

  private CinemaRecorder.Config recorderConfig() {
    CinemaRecorder.Config c = new CinemaRecorder.Config();
    c.width = videoSize.getWidth(); c.height = videoSize.getHeight(); c.fps = fps;
    c.conformFps = fps > 60 ? conformFps : 0; c.hevc = hevc || tenBit; c.tenBitHlg = tenBit; c.cbr = cbr;
    c.gopSeconds = gop == 0 ? 1f : gop == 1 ? 0.5f : 0f;
    c.bitrate = targetBitrate();
    c.audio = audioOn; c.realtimeClock = lens != null && lens.realtimeClock;
    c.orientationHint = lens == null ? 0 : lens.front ? (lens.sensorOrientation + displayDegrees()) % 360 : (lens.sensorOrientation - displayDegrees() + 360) % 360;
    return c;
  }

  private long targetBitrate() {
    long want = CinemaMath.bitrate(videoSize.getWidth(), videoSize.getHeight(), fps, quality, hevc || tenBit, tenBit, gop == 2);
    CinemaRecorder.EncoderCaps caps = CinemaRecorder.caps(hevc || tenBit ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC);
    return caps.available ? Math.min(want, caps.maxBitrate) : want;
  }

  private int displayDegrees() {
    int r = getDisplay() == null ? Surface.ROTATION_90 : getDisplay().getRotation();
    return r == Surface.ROTATION_0 ? 0 : r == Surface.ROTATION_90 ? 90 : r == Surface.ROTATION_180 ? 180 : 270;
  }

  private int relativeRotation() {
    if (lens == null) return 0;
    return lens.front ? (lens.sensorOrientation + displayDegrees()) % 360 : (lens.sensorOrientation - displayDegrees() + 360) % 360;
  }

  // ================================================================== capture requests

  /** Rebuilds and submits the repeating request from the current settings. Safe to call from any thread. */
  private void applyRepeating() {
    if (cameraHandler == null) return;
    cameraHandler.post(this::applyRepeatingOnCamera);
  }

  private void applyRepeatingOnCamera() {
    CameraCaptureSession s = session;
    if (s == null || device == null) return;
    try {
      CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
      if (maxResSession) {
        // maximum-resolution mode: the RAW stream is the only output (and feeds the monitor image)
        b.addTarget(raw.surface());
        if (Build.VERSION.SDK_INT >= 31) b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        applyControls(b);
        s.setRepeatingRequest(b.build(), captureCallback, cameraHandler);
        return;
      }
      b.addTarget(previewSurface);
      if (recording) b.addTarget(rawMode ? raw.surface() : proresMode ? prores.surface() : encoderSurface);
      if (rawMode) b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
      if (highSpeedSession) {
        int f = CinemaMath.nominalFps(fps);
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, highSpeedRange(f, recording));
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
        b.set(CaptureRequest.CONTROL_AE_LOCK, aeLock);
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex);
        b.set(CaptureRequest.CONTROL_AWB_LOCK, awbLock || (recording && lockWbOnRecord));
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        List<CaptureRequest> burst = ((CameraConstrainedHighSpeedCaptureSession) s).createHighSpeedRequestList(b.build());
        s.setRepeatingBurst(burst, captureCallback, cameraHandler);
        return;
      }
      applyControls(b);
      s.setRepeatingRequest(b.build(), captureCallback, cameraHandler);
    } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) { Log.w(TAG, "repeating", e); }
  }

  private void applyControls(CaptureRequest.Builder b) {
    b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
    int nominal = CinemaMath.nominalFps(fps);
    // ---- exposure
    if (ownAe()) {
      // log modes: City Cinema's own auto exposure (meters the decoded log picture), sent as manual exposure
      if (!logAeReady) {
        logAeIso = Math.max(lens.isoRange.getLower(), Math.min(lens.isoRange.getUpper(), liveIso));
        logAeExposure = clampExposure(logAePreferredShutter(), CinemaMath.frameDurationNs(fps));
        logAeReady = true;
      }
      b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
      long frame = CinemaMath.frameDurationNs(fps);
      b.set(CaptureRequest.SENSOR_FRAME_DURATION, frame);
      b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExposure(logAeExposure, frame));
      b.set(CaptureRequest.SENSOR_SENSITIVITY, logAeIso);
    } else if (aeAuto || !lens.manualSensor) {
      logAeReady = false;
      b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
      Range<Integer> range = fpsRange(nominal);
      if (range != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
      int ev = evIndex - (int) Math.round(logHeadroom() / lens.evStep.doubleValue()); // log modes: expose under the EI
      b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, Math.max(lens.evRange.getLower(), Math.min(lens.evRange.getUpper(), ev)));
      b.set(CaptureRequest.CONTROL_AE_LOCK, aeLock);
    } else {
      b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
      long frame = CinemaMath.frameDurationNs(fps);
      b.set(CaptureRequest.SENSOR_FRAME_DURATION, frame);
      b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExposure(exposureNs(), frame));
      b.set(CaptureRequest.SENSOR_SENSITIVITY, Math.max(lens.isoRange.getLower(), iso >> logHeadroom())); // iso is the EI
    }
    int[] bands = {CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF};
    if (contains(lens.antibanding, bands[antibanding])) b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, bands[antibanding]);
    // ---- white balance and colour. The log looks are recorded in their wide gamut (S-Gamut3.Cine, REDWideGamutRGB,
    // BMD Wide Gamut Gen 5, Rec.2020): that needs a custom colour matrix, which needs auto white balance off, so
    // in the logs auto white balance is one-push (measured, then held) like on a cinema camera.
    boolean gamut = gamutActive(), measuring = SystemClock.uptimeMillis() < awbMeasureUntil;
    if (!lens.manualPost || (awbAuto && (!gamut || measuring))) {
      b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
      b.set(CaptureRequest.CONTROL_AWB_LOCK, !measuring && (awbLock || (recording && lockWbOnRecord))); // white balance holds during a take
    } else {
      b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
      b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
      float[] g = awbAuto ? (heldGains != null ? heldGains : liveGains != null ? liveGains : lens.wb.gains(5600, 0)) : lens.wb.gains(kelvin, tint);
      b.set(CaptureRequest.COLOR_CORRECTION_GAINS, new RggbChannelVector(g[0], g[1], g[2], g[3]));
      ColorSpaceTransform t = awbAuto && heldTransform != null ? heldTransform : liveTransform;
      double[] base = t != null ? transformMatrix(t) : new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
      double[] m = gamut ? CinemaMath.mul3(CinemaMath.rec709ToGamut(CinemaMath.LOOKS[lookIndex]), base) : base;
      b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, toTransform(m));
    }
    // ---- focus
    if (manualFocus && lens.minFocus > 0) {
      b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
      b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus);
    } else {
      b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
    }
    if (meteringPoint != null && lens.active != null) {
      Rect r = CinemaScopes.toSensor(meteringPoint[0], meteringPoint[1], lens.active, relativeRotation(), lens.front, 0.12f);
      MeteringRectangle[] m = {new MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)};
      if (!manualFocus) b.set(CaptureRequest.CONTROL_AF_REGIONS, m);
      if (aeAuto) b.set(CaptureRequest.CONTROL_AE_REGIONS, m);
    }
    // ---- zoom
    b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
    // ---- stabilisation: 0 off, 1 optical, 2 digital, 3 optical+digital, 4 preview-matched (Android 13+)
    boolean wantOis = stabilization == 1 || stabilization == 3, wantEis = stabilization >= 2;
    if (contains(lens.ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) || contains(lens.ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF))
      b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, wantOis && contains(lens.ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
    int eis = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
    if (stabilization == 4 && Build.VERSION.SDK_INT >= 33 && contains(lens.stab, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)) eis = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION;
    else if (wantEis && contains(lens.stab, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) eis = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON;
    b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eis);
    // ---- look (tone curve). HLG has its own transfer function, so the ISP's curve is left alone.
    String look = CinemaMath.LOOKS[lookIndex];
    if (!tenBit && !"Rec.709".equals(look) && lens.curvePoints >= 16 && contains(lens.tonemapModes, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)) {
      float[] curve = CinemaMath.toneCurve(look, Math.min(lens.curvePoints, 64), logHeadroom());
      b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
      b.set(CaptureRequest.TONEMAP_CURVE, new TonemapCurve(curve, curve, curve));
    } else if (contains(lens.tonemapModes, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)) b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
    // ---- detail: 0 natural (no sharpening, light NR), 1 standard, 2 clean (strong NR)
    int edge = detail == 0 ? CaptureRequest.EDGE_MODE_OFF : CaptureRequest.EDGE_MODE_HIGH_QUALITY;
    if (detail == 0 && !contains(lens.edgeModes, edge)) edge = CaptureRequest.EDGE_MODE_FAST; // the least sharpening offered
    if (contains(lens.edgeModes, edge)) b.set(CaptureRequest.EDGE_MODE, edge);
    int nr = detail == 0 ? CaptureRequest.NOISE_REDUCTION_MODE_FAST : CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY;
    if (contains(lens.nrModes, nr)) b.set(CaptureRequest.NOISE_REDUCTION_MODE, nr);
  }

  /** High-speed sessions record at [f, f]; preview alone runs at [30, f] where offered, for a smoother, cooler preview. */
  private Range<Integer> highSpeedRange(int f, boolean forRecording) {
    Range<Integer> fixed = new Range<>(f, f);
    if (forRecording) return fixed;
    for (Range<Integer> r : lens.map.getHighSpeedVideoFpsRangesFor(videoSize)) if (r.getUpper() == f && r.getLower() == 30) return r;
    return fixed;
  }

  /** True when a headroom log (City Log, RED Log3G10, Sony S-Log3, BMD Film Gen 5) is in effect: a tone curve can be
   * applied (not RAW, not HLG, not high-speed) and the camera allows custom curves. */
  private boolean cityLogActive() {
    return lens != null && !curveIgnored && CinemaMath.isHeadroomLog(CinemaMath.LOOKS[lookIndex]) && !rawMode && !tenBit && !highSpeedSession
        && lens.curvePoints >= 16 && contains(lens.tonemapModes, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
  }

  /**
   * Stops the sensor is exposed under the exposure index in the log modes (0 otherwise). Limited to what the
   * camera allows: exposure compensation range in auto, minimum ISO in manual. The curve uses the same value, so
   * 18% grey always lands on the log's grey code.
   */
  private int logHeadroom() {
    if (!cityLogActive()) return 0;
    int want = CinemaMath.LOG_HEADROOM;
    if (ownAe()) return want; // our own log auto exposure sets the sensor ISO directly
    if (aeAuto || !lens.manualSensor) {
      double available = -lens.evRange.getLower() * lens.evStep.doubleValue();
      return (int) Math.max(0, Math.min(want, Math.floor(available + 1e-6)));
    }
    int stops = 0;
    while (stops < want && (iso >> (stops + 1)) >= lens.isoRange.getLower()) stops++;
    return stops;
  }

  // ---- City Cinema's own auto exposure for the log modes
  private volatile boolean logAeReady;
  private volatile int logAeIso = 100;
  private volatile long logAeExposure = 20_833_333L, lastLogMeter;

  /**
   * In the log modes with auto exposure, City Cinema meters the picture itself (decoding the log curve) and drives
   * ISO and shutter manually. Phone auto exposure can't be trusted there: some phones (e.g. Galaxy S20 Ultra) meter
   * after the log curve, see the lifted shadows as brightness and run away to ~6 stops under.
   */
  // ---- wide-gamut log colour and one-push white balance
  private volatile float[] heldGains;
  private volatile ColorSpaceTransform heldTransform;
  private volatile long awbMeasureUntil;

  /** True when the current look is a log recorded in its own wide gamut (needs manual colour correction support). */
  private boolean gamutActive() {
    return lens != null && lens.manualPost && CinemaMath.isLogLook(CinemaMath.LOOKS[lookIndex]) && !rawMode && !tenBit && !curveIgnored && !highSpeedSession
        && lens.curvePoints >= 16 && contains(lens.tonemapModes, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
  }

  /** One-push auto white balance: run the phone's auto white balance for ~1.5 s, then hold what it found. */
  private void oneShotWhiteBalance() {
    if (!awbAuto || recording) return;
    awbMeasureUntil = SystemClock.uptimeMillis() + 1500;
    applyRepeating();
    ui.postDelayed(() -> { applyRepeating(); refreshUi(); }, 1600);
  }

  private static double[] transformMatrix(ColorSpaceTransform t) {
    double[] m = new double[9];
    for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) m[r * 3 + c] = t.getElement(c, r).doubleValue();
    return m;
  }

  private static ColorSpaceTransform toTransform(double[] m) {
    Rational[] r = new Rational[9];
    for (int i = 0; i < 9; i++) r[i] = new Rational((int) Math.round(m[i] * 10000), 10000);
    return new ColorSpaceTransform(r);
  }

  /** Tells the scopes where the picture clips in the current log (0 when not in log). */
  private void updateClipLuma() {
    scopes.clipLuma = cityLogActive() ? (int) Math.floor(255 * CinemaMath.toneValue(CinemaMath.LOOKS[lookIndex], 1, logHeadroom())) : 0;
  }

  /**
   * Auto exposure run by City Cinema (when the camera allows manual exposure): it meters the picture through the
   * look's own curve and moves ISO at a fixed cinema shutter angle, like a cinema camera, instead of letting the
   * phone change the shutter constantly. In the logs this also avoids phones that meter after the curve and run away.
   */
  private boolean ownAe() {
    return aeAuto && lens != null && lens.manualSensor && !highSpeedSession && !maxResSession;
  }

  private boolean aeKeepShutter = true, lockWbOnRecord = true;
  private long lastExposureHint;
  private String decodeKey;
  private double[] decode;

  /** Cinema motion blur: the chosen shutter angle (180 by default), nudged to be flicker-free under 50/60 Hz light. */
  private long logAePreferredShutter() {
    int mains = antibanding == 1 ? 50 : antibanding == 2 ? 60 : 0;
    return CinemaMath.exposureForAngle(CinemaMath.flickerSafeAngle(shutterAsAngle ? shutterAngle : 180, fps, mains), fps);
  }

  /** One metering step on a preview frame (analysis thread). */
  private void logAeStep(Bitmap frame) {
    long now = SystemClock.uptimeMillis();
    if (now - lastLogMeter < 250 || !ownAe() || !logAeReady || aeLock) return; // AE LOCK freezes the exposure
    lastLogMeter = now;
    int w = frame.getWidth(), h = frame.getHeight();
    int[] px = new int[w * h];
    frame.getPixels(px, 0, w, 0, 0, w, h);
    // decode through the look actually applied (Rec.709 when the phone ignores custom curves)
    String look = curveIgnored || tenBit ? "Rec.709" : CinemaMath.LOOKS[lookIndex];
    int hr = logHeadroom();
    String key = look + "/" + hr;
    if (!key.equals(decodeKey)) { decode = CinemaMath.decodeTable(look, hr); decodeKey = key; }
    double metered = CinemaMath.meterTable(px, w, h, decode);
    double target = CinemaMath.LOG_AE_TARGET * Math.pow(2, evIndex * lens.evStep.doubleValue()); // EV compensation still works
    int clipLuma = hr > 0 ? (int) Math.floor(255 * CinemaMath.toneValue(look, 1, hr)) - 3 : 250;
    double stops = CinemaMath.protectHighlights(CinemaMath.logAeCorrection(metered, target), CinemaMath.clippedFraction(px, w, h, clipLuma));
    if (recording) { if (Math.abs(stops) < 0.2) return; stops *= 0.5; } // smoother, steadier changes during a take
    if (stops == 0) return;
    double isoSeconds = logAeIso * (logAeExposure / 1e9) * Math.pow(2, stops);
    int isoMax = lens.isoRange.getUpper();
    long[] e;
    if (aeKeepShutter) {
      double[] k = CinemaMath.keepShutter(isoSeconds, clampExposure(logAePreferredShutter(), CinemaMath.frameDurationNs(fps)), lens.isoRange.getLower(), isoMax);
      e = new long[] {(long) k[0], (long) k[1]};
      if (Math.abs(k[2]) > 0.7 && now - lastExposureHint > 15_000) {
        lastExposureHint = now;
        message(k[2] < 0 ? String.format(Locale.US, "Too bright for %s at the lowest ISO: about %.0f stops over. Add an ND filter, or allow auto shutter in the menu.", CinemaMath.angleLabel(CinemaMath.angleForExposure(e[1], fps)), -k[2])
            : String.format(Locale.US, "Too dark even at ISO %d: about %.0f stops under. Add light, open the shutter, or allow auto shutter in the menu.", e[0], k[2]));
      }
    } else {
      e = CinemaMath.allocateExposure(isoSeconds, logAePreferredShutter(), CinemaMath.frameDurationNs(fps), lens.isoRange.getLower(),
          Math.min(isoMax, 3200), isoMax, lens.expRange.getLower(), lens.expRange.getUpper());
    }
    if (e[0] == logAeIso && e[1] == logAeExposure) return;
    logAeIso = (int) e[0]; logAeExposure = e[1];
    applyRepeating();
  }

  private Range<Integer> fpsRange(int nominal) {
    if (lens.fpsRanges == null) return null;
    Range<Integer> best = null;
    for (Range<Integer> r : lens.fpsRanges) {
      if (r.getUpper() != nominal) continue;
      if (best == null || r.getLower() > best.getLower()) best = r; // prefer fixed [f, f]: no ramping frame rate
    }
    return best;
  }

  private long exposureNs() { return shutterAsAngle ? CinemaMath.exposureForAngle(shutterAngle, fps) : shutterNs > 0 ? shutterNs : CinemaMath.exposureForAngle(180, fps); }

  private long clampExposure(long e, long frame) { return Math.max(lens.expRange.getLower(), Math.min(Math.min(lens.expRange.getUpper(), frame), e)); }

  private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
    @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
      Integer i = res.get(CaptureResult.SENSOR_SENSITIVITY); if (i != null) liveIso = i;
      Long e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME); if (e != null) liveExposure = e;
      Float f = res.get(CaptureResult.LENS_FOCUS_DISTANCE); if (f != null) liveFocus = f;
      liveAfState = res.get(CaptureResult.CONTROL_AF_STATE);
      // Some phones list custom tone curves but quietly ignore them: check what the camera says it actually applied.
      Integer tm = res.get(CaptureResult.TONEMAP_MODE);
      Integer asked = r.get(CaptureRequest.TONEMAP_MODE);
      if (asked != null && asked == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) {
        boolean honoured = tm != null && tm == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE;
        curveChecks = honoured ? 0 : curveChecks + 1;
        if (honoured) curveHonoured = true;
        if (!curveIgnored && curveChecks > 30 && !curveHonoured) { curveIgnored = true; applyRepeating(); ui.post(() -> { message("This phone ignores custom tone curves, so log looks can't be applied. The phone's own processing is used and the log exposure offset is turned off."); refreshUi(); }); }
      }
      if (rawMode) {
        float[] bl = res.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL); if (bl != null && bl.length == 4) liveBlack = bl;
        LensShadingMap map;
        if (shadingCounter++ % 15 == 0 && (map = res.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)) != null) {
          int rows = map.getRowCount(), cols = map.getColumnCount();
          float[][] g = new float[4][rows * cols];
          for (int c = 0; c < 4; c++) for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) g[c][y * cols + x] = map.getGainFactor(c, x, y);
          shadingRows = rows; shadingCols = cols; liveShading = g;
        }
      }
      RawRecorder rr = raw;
      if (rr != null && maxResSession) {
        float[] g = awbAuto ? liveGains : lens.wb.gains(kelvin, tint);
        if (g != null) { float green = (g[1] + g[2]) / 2; rr.setMonitorGains(new float[] {g[0] / green, 1, g[3] / green}); }
      }
      // Only real auto white balance results count (never read back the matrix City Cinema sent itself).
      Integer awbMode = res.get(CaptureResult.CONTROL_AWB_MODE);
      if (awbAuto && awbMode != null && awbMode == CaptureResult.CONTROL_AWB_MODE_AUTO) {
        RggbChannelVector g = res.get(CaptureResult.COLOR_CORRECTION_GAINS);
        if (g != null) liveGains = new float[] {g.getRed(), g.getGreenEven(), g.getGreenOdd(), g.getBlue()};
        ColorSpaceTransform t = res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        if (t != null) liveTransform = t;
        if (SystemClock.uptimeMillis() < awbMeasureUntil) { heldGains = liveGains; heldTransform = liveTransform; }
      }
    }
  };

  // ================================================================== recording

  private void toggleRecord() {
    if (cameraHandler == null || session == null) { message("Camera not ready."); return; }
    if (recordBusy) return;
    recordBusy = true;
    cameraHandler.post(() -> {
      try {
        if (recording) { if (rawMode) stopRawOnCamera(); else if (proresMode) stopProResOnCamera(); else stopRecordingOnCamera(); }
        else { if (rawMode) startRawOnCamera(); else if (proresMode) startProResOnCamera(); else startRecordingOnCamera(); }
      } finally { recordBusy = false; ui.post(this::refreshUi); }
    });
  }

  private volatile boolean recordBusy;
  private Uri mp4Uri, wavUri;
  private ParcelFileDescriptor mp4Fd, wavFd;
  private FileChannel wavChannel;
  private String clipName;

  private void startRecordingOnCamera() {
    long free = new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
    if (free < 300L * 1024 * 1024) { message("Storage is almost full."); return; }
    String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    clipName = CinemaMath.clipName(cameraLetter, reel, clip, date);
    ContentResolver cr = getContentResolver();
    try {
      ContentValues v = new ContentValues();
      v.put(MediaStore.MediaColumns.DISPLAY_NAME, clipName + ".mp4");
      v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
      v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/City Cinema");
      v.put(MediaStore.MediaColumns.IS_PENDING, 1);
      mp4Uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
      if (mp4Uri == null) throw new IOException("Couldn't create the video file.");
      mp4Fd = cr.openFileDescriptor(mp4Uri, "rw");
      wavChannel = null; wavUri = null; wavFd = null;
      boolean wantWav = wavOn && audioOn && audio.isRunning() && !(fps > 60 && conformFps > 0);
      if (wantWav) {
        ContentValues a = new ContentValues();
        a.put(MediaStore.MediaColumns.DISPLAY_NAME, clipName + ".wav");
        a.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav");
        a.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/City Cinema");
        a.put(MediaStore.MediaColumns.IS_PENDING, 1);
        wavUri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), a);
        if (wavUri != null) { wavFd = cr.openFileDescriptor(wavUri, "rw"); wavChannel = new FileOutputStream(wavFd.getFileDescriptor()).getChannel(); }
      }
      CinemaRecorder.Config c = recorderConfig();
      if (recorder.config() == null || recorder.config().width != c.width || recorder.config().fps != c.fps) recorder.prepare(c);
      else { recorder.config().orientationHint = c.orientationHint; recorder.config().audio = c.audio; }
      recorder.start(mp4Fd.getFileDescriptor(), audioOn ? audio : null, wavChannel);
      recording = true;
      recordStartNs = SystemClock.elapsedRealtimeNanos();
      takeStartWall = System.currentTimeMillis();
      applyRepeatingOnCamera();
      ui.post(() -> { scopes.invalidate(); refreshUi(); });
    } catch (IOException | RuntimeException e) {
      Log.w(TAG, "start", e);
      recording = false;
      cleanupFailedTake();
      message("Couldn't start recording: " + e.getMessage());
      try { recorder.prepare(recorderConfig()); } catch (IOException | RuntimeException ignored) {}
    }
  }

  private void stopRecordingOnCamera() {
    if (!recording) return;
    recording = false;
    applyRepeatingOnCamera(); // stop feeding the encoder first
    long frames = recorder.framesWritten, dropped = recorder.droppedFrames, bytes = recorder.bytesWritten;
    boolean ok = recorder.stop();
    long durationNs = SystemClock.elapsedRealtimeNanos() - recordStartNs;
    wavChannel = null; closeQuietly(wavFd); closeQuietly(mp4Fd); wavFd = mp4Fd = null; // the channel shares wavFd's descriptor: close it once
    ContentResolver cr = getContentResolver();
    if (ok) {
      ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0);
      cr.update(mp4Uri, done, null, null);
      if (wavUri != null) cr.update(wavUri, done, null, null);
      writeSidecar(frames, dropped, bytes, durationNs);
      recordRunBase += CinemaMath.framesFor(durationNs, fps);
      clip++;
      savePrefs();
      String note = dropped > 0 ? " · " + dropped + " dropped frames" : "";
      message("Saved " + clipName + " (" + CinemaMath.formatBitrate(bytes * 8 * CinemaMath.NS / Math.max(1, durationNs)) + note + ")");
    } else {
      cleanupFailedTake();
      message("The take was too short or the encoder failed; nothing was saved.");
    }
    mp4Uri = wavUri = null;
    try { recorder.prepare(recorderConfig()); } catch (IOException | RuntimeException e) { Log.w(TAG, "re-prepare", e); }
    ui.post(() -> { scopes.invalidate(); refreshUi(); });
  }

  private void cleanupFailedTake() {
    wavChannel = null; closeQuietly(wavFd); closeQuietly(mp4Fd); wavFd = mp4Fd = null; // the channel shares wavFd's descriptor: close it once
    ContentResolver cr = getContentResolver();
    if (mp4Uri != null) try { cr.delete(mp4Uri, null, null); } catch (RuntimeException ignored) {}
    if (wavUri != null) try { cr.delete(wavUri, null, null); } catch (RuntimeException ignored) {}
    mp4Uri = wavUri = null;
  }

  /** Per-clip metadata (like camera XML/ALE sidecars) so editors and colourists know exactly how it was shot. */
  private void writeSidecar(long frames, long dropped, long bytes, long durationNs) {
    try {
      JSONObject j = new JSONObject();
      j.put("clip", clipName).put("camera", String.valueOf(cameraLetter)).put("reel", reel).put("clipNumber", clip);
      j.put("startedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date(takeStartWall)));
      j.put("startTimecode", startTimecodeFor(takeStartWall)).put("timecodeMode", todTimecode ? "time of day" : "record run");
      j.put("device", Build.MANUFACTURER + " " + Build.MODEL).put("lens", lens.label).put("cameraId", lens.id);
      j.put("resolution", videoSize.getWidth() + "x" + videoSize.getHeight()).put("sensorFps", CinemaMath.fpsLabel(fps));
      j.put("projectFps", CinemaMath.fpsLabel(fps > 60 && conformFps > 0 ? conformFps : fps));
      j.put("codec", tenBit ? "HEVC Main10 HLG BT.2020" : hevc ? "HEVC Main BT.709" : "H.264 High BT.709");
      j.put("bitrateTarget", targetBitrate()).put("bitrateMode", cbr ? "CBR" : "VBR").put("gop", gop == 0 ? "1s" : gop == 1 ? "0.5s" : "all-intra");
      j.put("look", CinemaMath.LOOKS[lookIndex]).put("detail", new String[] {"natural", "standard", "clean"}[detail]);
      if (cityLogActive()) j.put("logHeadroomStops", logHeadroom()).put("exposureIndex", (aeAuto ? liveIso : iso >> logHeadroom()) << logHeadroom()).put("lut", lutFor(CinemaMath.LOOKS[lookIndex]))
          .put("gradeAs", (gamutActive() ? CinemaMath.gamutName(CinemaMath.LOOKS[lookIndex]) : "Rec.709") + " / " + CinemaMath.LOOKS[lookIndex]);
      j.put("exposure", aeAuto ? "auto" : "manual").put("iso", aeAuto ? liveIso : iso).put("shutter", CinemaMath.speedLabel(aeAuto ? liveExposure : exposureNs()));
      j.put("shutterAngle", Math.round(CinemaMath.angleForExposure(aeAuto ? liveExposure : exposureNs(), fps) * 10) / 10.0);
      j.put("whiteBalance", awbAuto ? "auto" : kelvin + "K tint " + tint).put("focus", manualFocus ? "manual " + focusLabel(focus) : "continuous AF");
      j.put("zoom", zoom).put("stabilization", new String[] {"off", "optical", "digital", "optical+digital", "preview"}[stabilization]);
      j.put("frames", frames).put("droppedFrames", dropped).put("bytes", bytes).put("durationSeconds", durationNs / 1e9);
      j.put("audio", audioOn ? audio.inputName + ", " + audioSource + ", 48 kHz, gain " + audio.gainDb + " dB" : "off");
      j.put("wav", wavUri != null ? clipName + ".wav (32-bit float)" : "none");
      ContentValues v = new ContentValues();
      v.put(MediaStore.MediaColumns.DISPLAY_NAME, clipName + ".json");
      v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
      v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/City Cinema");
      Uri u = getContentResolver().insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
      if (u != null) try (OutputStream o = getContentResolver().openOutputStream(u)) { if (o != null) o.write(j.toString(2).getBytes(StandardCharsets.UTF_8)); }
    } catch (Exception e) { Log.w(TAG, "sidecar", e); }
  }

  private static void closeQuietly(java.io.Closeable c) { if (c != null) try { c.close(); } catch (IOException ignored) {} }

  // ================================================================== ProRes recording

  /** YUV sizes the camera streams that suit ProRes (16:9-ish, 720p to 4K DCI), largest first. */
  private List<Size> proresSizesAll() {
    List<Size> out = new ArrayList<>();
    Size[] sizes = lens == null ? null : lens.map.getOutputSizes(ImageFormat.YUV_420_888);
    if (sizes == null) return out;
    for (Size s : sizes) {
      double ar = s.getWidth() / (double) s.getHeight();
      if (s.getHeight() >= 720 && s.getWidth() <= 4096 && ar >= 1.3 && ar <= 2.0 && s.getWidth() % 2 == 0 && s.getHeight() % 2 == 0) out.add(s);
    }
    Collections.sort(out, (a, b) -> Long.compare((long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
    return out;
  }

  /** ProRes sizes the camera can deliver at the current frame rate. */
  private List<Size> proresSizes() {
    List<Size> out = new ArrayList<>();
    long frameNs = CinemaMath.frameDurationNs(fps);
    for (Size s : proresSizesAll()) if (lens.map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, s) <= frameNs + 1000) out.add(s);
    return out;
  }

  private void chooseProResOptions() {
    if (recording) return;
    List<Size> sizes = proresSizes();
    String[] options = new String[sizes.size() * 4];
    int sel = 0, k = 0;
    for (Size sz : sizes) for (int pr = 3; pr >= 0; pr--) {
      options[k] = ProRes.NAMES[pr] + " · " + sz.getWidth() + "×" + sz.getHeight() + " · " + CinemaMath.formatBitrate(ProRes.bitrate(sz.getWidth(), sz.getHeight(), fps, pr));
      if (sz.equals(proresSize) && pr == proresProfile) sel = k;
      k++;
    }
    choose("ProRes profile and size", options, sel, i -> { proresSize = sizes.get(i / 4); proresProfile = 3 - i % 4; reopen(); });
  }

  private Uri movUri;
  private ParcelFileDescriptor movFd;

  private void startProResOnCamera() {
    if (prores == null) { message("ProRes isn't ready yet."); return; }
    long free = new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
    if (free < 500L * 1024 * 1024) { message("Storage is almost full."); return; }
    String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    clipName = CinemaMath.clipName(cameraLetter, reel, clip, date);
    ContentResolver cr = getContentResolver();
    try {
      ContentValues v = new ContentValues();
      v.put(MediaStore.MediaColumns.DISPLAY_NAME, clipName + ".mov");
      v.put(MediaStore.MediaColumns.MIME_TYPE, "video/quicktime");
      v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/City Cinema");
      v.put(MediaStore.MediaColumns.IS_PENDING, 1);
      movUri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
      if (movUri == null) throw new IOException("Couldn't create the .mov file.");
      movFd = cr.openFileDescriptor(movUri, "rw");
      FileChannel channel = new FileOutputStream(movFd.getFileDescriptor()).getChannel();
      wavUri = null; wavFd = null; wavChannel = null;
      boolean withAudio = audioOn && audio.isRunning();
      if (withAudio && wavOn) {
        ContentValues a = new ContentValues();
        a.put(MediaStore.MediaColumns.DISPLAY_NAME, clipName + ".wav");
        a.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav");
        a.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/City Cinema");
        a.put(MediaStore.MediaColumns.IS_PENDING, 1);
        wavUri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), a);
        if (wavUri != null) { wavFd = cr.openFileDescriptor(wavUri, "rw"); wavChannel = new FileOutputStream(wavFd.getFileDescriptor()).getChannel(); }
      }
      takeStartWall = System.currentTimeMillis();
      MovWriter.Config c = new MovWriter.Config();
      c.width = proresSize.getWidth(); c.height = proresSize.getHeight();
      c.fourcc = ProRes.FOURCC[proresProfile]; c.compressorName = "Apple " + ProRes.NAMES[proresProfile];
      c.fps = fps; c.primaries = 1; c.transfer = 1; c.matrix = 6; // camera YUV: BT.709 primaries, BT.601 matrix
      c.audioChannels = withAudio ? audio.channels : 0; c.sampleRate = CinemaRecorder.AudioEngine.RATE;
      c.startTimecodeFrames = todTimecode ? todFrames(takeStartWall) : recordRunBase;
      c.dropFrame = CinemaMath.isDropFrameRate(fps);
      c.rotationDegrees = relativeRotation() == 180 ? 180 : 0;
      c.reelName = String.format(Locale.US, "%c%03d", cameraLetter, reel);
      prores.start(channel, c, proresProfile);
      if (withAudio) { audio.setSink(prores); audio.beginTake(null, wavChannel); }
      recording = true;
      recordStartNs = SystemClock.elapsedRealtimeNanos();
      applyRepeatingOnCamera();
      ui.post(() -> { scopes.invalidate(); refreshUi(); });
    } catch (IOException | RuntimeException e) {
      Log.w(TAG, "prores start", e);
      recording = false;
      audio.setSink(null);
      if (prores.isRecording()) prores.stop(1000);
      closeQuietly(movFd); movFd = null;
      if (movUri != null) try { cr.delete(movUri, null, null); } catch (RuntimeException ignored) {}
      cleanupFailedTake();
      message("Couldn't start ProRes: " + e.getMessage());
    }
  }

  private void stopProResOnCamera() {
    if (!recording) return;
    recording = false;
    applyRepeatingOnCamera();
    long durationNs = SystemClock.elapsedRealtimeNanos() - recordStartNs;
    boolean hadAudio = audio.isRunning() && audioOn;
    if (hadAudio) audio.endTake();
    audio.setSink(null);
    ui.post(() -> message("Finishing ProRes encode…"));
    long slots = prores.stop(60_000);
    long encodedFrames = prores.encoded.get(), drops = prores.dropped, errors = prores.errors;
    closeQuietly(movFd); movFd = null;
    wavChannel = null; closeQuietly(wavFd); wavFd = null;
    ContentResolver cr = getContentResolver();
    if (slots > 0) {
      ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0);
      cr.update(movUri, done, null, null);
      if (wavUri != null) cr.update(wavUri, done, null, null);
      writeProResSidecar(slots, encodedFrames, drops, errors, durationNs);
      recordRunBase += slots;
      clip++;
      savePrefs();
      message("Saved " + clipName + ".mov · " + ProRes.NAMES[proresProfile] + (drops > 0 ? " · " + drops + " dropped (filled)" : "") + (errors > 0 ? " · " + errors + " errors" : ""));
    } else {
      if (movUri != null) try { cr.delete(movUri, null, null); } catch (RuntimeException ignored) {}
      if (wavUri != null) try { cr.delete(wavUri, null, null); } catch (RuntimeException ignored) {}
      message("No ProRes frames were encoded; nothing was saved.");
    }
    movUri = null; wavUri = null;
    ui.post(() -> { scopes.invalidate(); refreshUi(); });
  }

  static String lutFor(String look) { return "City Log".equals(look) ? "CityLog_to_Rec709.cube" : CinemaMath.lutName(look); }

  /** Writes the grading LUTs (each log to Rec.709, City Log to linear) to Documents/City Cinema/LUTs. */
  private void exportLuts() {
    new Thread(() -> {
      try {
        List<String[]> luts = new ArrayList<>();
        luts.add(new String[] {"CityLog_to_Rec709.cube", CinemaMath.cityLogRec709Cube()});
        luts.add(new String[] {"CityLog_to_Linear.cube", CinemaMath.cityLogLinearCube()});
        for (String look : new String[] {"Flat Log", "RED Log3G10", "Sony S-Log3", "BMD Film Gen 5"}) luts.add(new String[] {CinemaMath.lutName(look), CinemaMath.logRec709Cube(look)});
        for (String[] l : luts) {
          ContentValues v = new ContentValues();
          v.put(MediaStore.MediaColumns.DISPLAY_NAME, l[0]);
          v.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
          v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/City Cinema/LUTs");
          Uri u = getContentResolver().insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
          if (u == null) throw new IOException("couldn't create " + l[0]);
          try (OutputStream o = getContentResolver().openOutputStream(u)) { if (o != null) o.write(l[1].getBytes(StandardCharsets.US_ASCII)); }
        }
        message("LUTs saved to Documents/City Cinema/LUTs: City Log, Flat Log, RED Log3G10, Sony S-Log3 and BMD Film Gen 5 to Rec.709, plus City Log to linear");
      } catch (IOException | RuntimeException e) { message("Couldn't save LUTs: " + e.getMessage()); }
    }, "lut-export").start();
  }

  /** Encodes test frames on all encoder threads and reports which ProRes formats this phone sustains in real time. */
  private void proresSpeedTest() {
    if (recording) { message("Stop recording first."); return; }
    if (!ProRes.AVAILABLE) { message("This build has no ProRes encoder."); return; }
    final AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("ProRes speed test")
        .setMessage("Encoding test frames on " + ProResRecorder.threadsFor(3840, 2160) + " threads (" + (ProRes.nativeUsesNeon() ? "NEON" : "scalar") + ")…\nThis takes about 15 seconds; the phone will get warm.")
        .setCancelable(false).show();
    new Thread(() -> {
      StringBuilder out = new StringBuilder();
      int[][] sizes = {{3840, 2160}, {2560, 1440}, {1920, 1080}};
      try {
        for (int[] sz : sizes) for (int profile : new int[] {3, 1}) {
          double f = ProResRecorder.benchmark(sz[0], sz[1], profile, 2500);
          String verdict = f >= 31 ? "real time up to 30 fps" : f >= 25.5 ? "real time up to 25 fps" : f >= 24.5 ? "real time at 24 fps" : "too slow for real time (frames will be filled)";
          out.append(String.format(Locale.US, "%s %s: %.0f fps · %s%n", sz[0] >= 3840 ? "4K" : sz[1] + "p", ProRes.NAMES[profile], f, verdict));
        }
        out.append("\nThreads: ").append(ProResRecorder.threadsFor(3840, 2160)).append(" at 4K, ").append(ProResRecorder.threadsFor(1920, 1080)).append(" at 1080p · ")
            .append(ProRes.nativeUsesNeon() ? "NEON" : "scalar").append(" encoder.\nSustained recording runs warmer than this short test: leave ~20% headroom. Watch ENC while recording.");
      } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      ui.post(() -> {
        dialog.dismiss();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("ProRes speed test").setMessage(out.toString()).setPositiveButton("OK", null).show();
      });
    }, "prores-speed-test").start();
  }

  private void writeProResSidecar(long slots, long encodedFrames, long drops, long errors, long durationNs) {
    try {
      JSONObject j = new JSONObject();
      j.put("clip", clipName).put("format", ProRes.NAMES[proresProfile] + " (" + ProRes.FOURCC[proresProfile] + "), 10-bit 4:2:2, QuickTime")
          .put("camera", String.valueOf(cameraLetter)).put("reel", reel).put("clipNumber", clip)
          .put("startedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date(takeStartWall)))
          .put("startTimecode", startTimecodeFor(takeStartWall)).put("device", Build.MANUFACTURER + " " + Build.MODEL).put("lens", lens.label)
          .put("resolution", proresSize.getWidth() + "x" + proresSize.getHeight()).put("fps", CinemaMath.fpsLabel(fps))
          .put("colour", "BT.709 primaries and transfer, BT.601 matrix (camera YCbCr), limited range")
          .put("look", CinemaMath.LOOKS[lookIndex]).put("logHeadroomStops", logHeadroom()).put("lut", cityLogActive() ? lutFor(CinemaMath.LOOKS[lookIndex]) : "none").put("iso", aeAuto ? liveIso : iso).put("shutter", CinemaMath.speedLabel(aeAuto ? liveExposure : exposureNs()))
          .put("whiteBalance", awbAuto ? "auto" : kelvin + "K tint " + tint)
          .put("frameSlots", slots).put("framesEncoded", encodedFrames).put("droppedFramesFilled", drops).put("errors", errors)
          .put("durationSeconds", durationNs / 1e9).put("encoderThreads", prores.encoderThreads())
          .put("audio", audioOn ? "24-bit PCM 48 kHz in the .mov" + (wavUri != null ? " + " + clipName + ".wav (32-bit float)" : "") : "none");
      ContentValues v = new ContentValues();
      v.put(MediaStore.MediaColumns.DISPLAY_NAME, clipName + ".json");
      v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
      v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/City Cinema");
      Uri u = getContentResolver().insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
      if (u != null) try (OutputStream o = getContentResolver().openOutputStream(u)) { if (o != null) o.write(j.toString(2).getBytes(StandardCharsets.UTF_8)); }
    } catch (Exception e) { Log.w(TAG, "prores sidecar", e); }
  }

  // ================================================================== RAW (CinemaDNG) recording

  private File rawClipDir;
  private RandomAccessFile rawWav;
  private int rawFrameBase;

  /** Where RAW clips go: app storage, so thousands of frames don't flood the gallery. Visible over USB. */
  File rawRoot() {
    File base = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
    return new File(base != null ? base : getFilesDir(), "RAW");
  }

  private long rawFreeBytes() {
    File root = rawRoot(); root.mkdirs();
    return new StatFs(root.getPath()).getAvailableBytes();
  }

  /** The parts of the DNG header that stay the same for the whole clip. */
  private CinemaDng.Meta rawTemplate() {
    CinemaDng.Meta m = new CinemaDng.Meta();
    m.cfa = lens.cfa; m.whiteLevel = lens.whiteLevel; m.black = lens.black.clone();
    m.colorMatrix1 = lens.cm1; m.colorMatrix2 = lens.cm1 != null && lens.cm2 != lens.cm1 ? lens.cm2 : null;
    m.illuminant1 = lens.ill1; m.illuminant2 = lens.ill2;
    m.forward1 = lens.fm1; m.forward2 = m.colorMatrix2 != null ? lens.fm2 : null;
    m.calibration1 = lens.cal1; m.calibration2 = m.colorMatrix2 != null ? lens.cal2 : null;
    m.orientation = relativeRotation() == 180 ? 3 : 1;
    m.make = Build.MANUFACTURER; m.model = Build.MODEL; m.uniqueModel = Build.MANUFACTURER + " " + Build.MODEL + " " + lens.label;
    m.software = "City Cinema"; m.reel = String.format(Locale.US, "%c%03d", cameraLetter, reel);
    m.dateTime = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date());
    return m;
  }

  /** As-shot neutral (camera RGB, green = 1) from the white balance gains in use. */
  private float[] neutral() {
    float[] g = awbAuto ? liveGains : lens.wb.gains(kelvin, tint);
    if (g == null) return new float[] {0.5f, 1, 0.5f};
    float green = (g[1] + g[2]) / 2;
    return new float[] {green / g[0], 1, green / g[3]};
  }

  private void startRawOnCamera() {
    if (raw == null) { message("RAW isn't ready yet."); return; }
    long rate = rawBytesPerSecond();
    if (rawFreeBytes() < rate * 10 + 500L * 1024 * 1024) { message("Not enough free space for RAW."); return; }
    String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    clipName = CinemaMath.clipName(cameraLetter, reel, clip, date);
    rawClipDir = new File(rawRoot(), clipName);
    if (!rawClipDir.mkdirs() && !rawClipDir.isDirectory()) { message("Couldn't create " + rawClipDir); return; }
    try {
      rawWav = null;
      if (audioOn && wavOn && audio.isRunning()) {
        rawWav = new RandomAccessFile(new File(rawClipDir, clipName + ".wav"), "rw");
        rawWav.setLength(0);
        audio.beginTake(null, rawWav.getChannel());
      }
      final CinemaDng.Meta template = rawTemplate();
      takeStartWall = System.currentTimeMillis();
      long startFrames = todTimecode ? todFrames(takeStartWall) : recordRunBase;
      final RawOption opt = rawOpt;
      raw.start(rawClipDir, clipName, fps, rawPack12, rawCompressed(), startFrames, () -> {
        CinemaDng.Meta m = template.copy();
        float[] bl = liveBlack; if (bl != null) m.black = bl.clone();
        m.neutral = neutral();
        m.iso = aeAuto ? liveIso : iso; m.exposureNs = aeAuto ? liveExposure : exposureNs();
        // The shading map describes the default-mode sensor; a crop sees only the middle of it. In maximum-resolution
        // mode the camera doesn't report a matching map, so none is written.
        if (!opt.maxRes) {
          m.shading = liveShading; m.shadingRows = shadingRows; m.shadingCols = shadingCols;
          m.mapOriginH = -opt.cropX / (double) opt.width; m.mapOriginV = -opt.cropY / (double) opt.height;
          m.mapExtentH = opt.sensor.getWidth() / (double) opt.width; m.mapExtentV = opt.sensor.getHeight() / (double) opt.height;
        }
        return m;
      });
      recording = true;
      recordStartNs = SystemClock.elapsedRealtimeNanos();
      applyRepeatingOnCamera();
      ui.post(() -> { scopes.invalidate(); refreshUi(); });
    } catch (IOException | RuntimeException e) {
      Log.w(TAG, "raw start", e);
      recording = false;
      if (raw.isRecording()) raw.stop(1000);
      if (rawWav != null) { audio.endTake(); closeQuietly(rawWav); rawWav = null; }
      message("Couldn't start RAW: " + e.getMessage());
    }
  }

  private void stopRawOnCamera() {
    if (!recording) return;
    recording = false;
    applyRepeatingOnCamera();
    long durationNs = SystemClock.elapsedRealtimeNanos() - recordStartNs;
    if (rawWav != null) audio.endTake();
    ui.post(() -> message("Writing RAW buffer to storage…"));
    long frames = raw.stop(60_000);
    closeQuietly(rawWav);
    boolean hadWav = rawWav != null; rawWav = null;
    if (frames == 0) { message("No RAW frames were captured."); return; }
    writeRawSidecar(frames, durationNs, hadWav);
    recordRunBase += frames;
    clip++;
    savePrefs();
    long drops = raw.dropped, errors = raw.writeErrors;
    message("Saved " + clipName + ": " + frames + " DNG frames" + (drops > 0 ? " · " + drops + " dropped (filled)" : "") + (errors > 0 ? " · " + errors + " write errors" : ""));
    ui.post(() -> { scopes.invalidate(); refreshUi(); });
  }

  private long todFrames(long wallMs) {
    Calendar c = Calendar.getInstance(); c.setTimeInMillis(wallMs);
    long since = ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60 + c.get(Calendar.SECOND)) * CinemaMath.NS + c.get(Calendar.MILLISECOND) * 1_000_000L;
    return CinemaMath.framesFor(since, fps);
  }

  private void writeRawSidecar(long frames, long durationNs, boolean hadWav) {
    try {
      JSONObject j = new JSONObject();
      j.put("clip", clipName).put("format", "CinemaDNG, " + rawFormatName())
          .put("rawSource", rawOpt.label).put("sensorReadout", rawOpt.sensor.getWidth() + "x" + rawOpt.sensor.getHeight())
          .put("crop", rawOpt.cropX + "," + rawOpt.cropY + " " + rawOpt.width + "x" + rawOpt.height)
          .put("maximumResolutionMode", rawOpt.maxRes).put("quadBayerRemosaic", rawOpt.quad)
          .put("uncompressedFallbackFrames", raw.uncompressedFallbacks)
          .put("camera", String.valueOf(cameraLetter)).put("reel", reel).put("clipNumber", clip)
          .put("startedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date(takeStartWall)))
          .put("device", Build.MANUFACTURER + " " + Build.MODEL).put("lens", lens.label).put("cameraId", lens.id)
          .put("resolution", rawSize.getWidth() + "x" + rawSize.getHeight()).put("fps", CinemaMath.fpsLabel(fps))
          .put("cfa", new String[] {"RGGB", "GRBG", "GBRG", "BGGR"}[lens.cfa]).put("whiteLevel", lens.whiteLevel)
          .put("exposure", aeAuto ? "auto" : "manual").put("iso", aeAuto ? liveIso : iso).put("shutter", CinemaMath.speedLabel(aeAuto ? liveExposure : exposureNs()))
          .put("whiteBalance", awbAuto ? "auto (as-shot neutral per frame)" : kelvin + "K tint " + tint)
          .put("lensShadingGainMap", liveShading != null).put("sensorCalibrated", lens.cm1 != null)
          .put("framesOnDisk", frames).put("framesCaptured", raw.written.get()).put("framesFilledForSync", raw.filled.get())
          .put("droppedFrames", raw.dropped).put("writeErrors", raw.writeErrors).put("bytes", raw.bytes.get()).put("durationSeconds", durationNs / 1e9);
      if (hadWav) {
        long offsetNs = audio.wavStartNs >= 0 && raw.firstFrameTimestampNs >= 0 ? audio.wavStartNs - raw.firstFrameTimestampNs : 0;
        j.put("wav", clipName + ".wav (32-bit float, 48 kHz)")
            .put("audioOffsetSeconds", offsetNs / 1e9)
            .put("audioSync", offsetNs >= 0 ? "audio starts " + Math.round(offsetNs / 1e6) + " ms after frame 0" : "audio starts " + Math.round(-offsetNs / 1e6) + " ms before frame 0");
      }
      try (FileOutputStream o = new FileOutputStream(new File(rawClipDir, clipName + ".json"))) { o.write(j.toString(2).getBytes(StandardCharsets.UTF_8)); }
    } catch (Exception e) { Log.w(TAG, "raw sidecar", e); }
  }

  private void chooseRawOptions() {
    if (recording) return;
    if (!lens.raw) { message(lens.label + " doesn't offer RAW output."); return; }
    final List<RawOption> list = rawOptions();
    final List<int[]> modes = new ArrayList<>(); // {compress, pack12}
    if (RawNative.compressible(lens.whiteLevel)) modes.add(new int[] {1, 0});
    if (lens.whiteLevel <= 4095) modes.add(new int[] {0, 1});
    modes.add(new int[] {0, 0});
    String[] options = new String[list.size() * modes.size()];
    int sel = 0, k = 0;
    int bits = RawNative.precisionFor(lens.whiteLevel);
    for (RawOption o : list) for (int[] md : modes) {
      double bytesPerFrame = md[0] == 1 ? o.width * (double) o.height * bits / 8 / 2 : o.width * (double) o.height * (md[1] == 1 ? 1.5 : 2);
      double rate = Math.min(fps, maxFps(o));
      options[k] = o.label + " · " + (md[0] == 1 ? "lossless " + bits + "-bit" : md[1] == 1 ? "12-bit" : "16-bit") + " · up to "
          + CinemaMath.fpsLabel(Math.floor(maxFps(o) * 10) / 10) + " fps · " + (md[0] == 1 ? "~" : "") + Math.round(bytesPerFrame * rate / 1e6) + " MB/s";
      if (o.key.equals(rawKey) && (md[0] == 1) == rawCompressed() && (md[1] == 1) == (rawPack12 && md[0] == 0)) sel = k;
      k++;
    }
    choose("RAW format", options, sel, i -> {
      RawOption o = list.get(i / modes.size());
      int[] md = modes.get(i % modes.size());
      rawKey = o.key; rawCompress = md[0] == 1; if (md[0] == 0) rawPack12 = md[1] == 1;
      if (o.is8k()) message(o.label + " streams up to " + CinemaMath.fpsLabel(Math.floor(maxFps(o) * 10) / 10) + " fps on this sensor. Manual exposure gives the steadiest frame rate.");
      reopen();
    });
  }

  // ================================================================== audio

  private void startAudio() {
    if (!audioOn || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { audio.stop(); return; }
    AudioDeviceInfo dev = null;
    if (audioDeviceId != 0) for (AudioDeviceInfo d : ((AudioManager) getSystemService(AUDIO_SERVICE)).getDevices(AudioManager.GET_DEVICES_INPUTS)) if (d.getId() == audioDeviceId) dev = d;
    try { audio.start(CinemaRecorder.sourceFor(audioSource), dev, lens != null && lens.realtimeClock); }
    catch (SecurityException e) { message("Microphone permission is needed for sound."); }
    audio.gainDb = prefs.getFloat("gain", 0);
  }

  // ================================================================== periodic UI tick

  private final Runnable tick = new Runnable() {
    @Override public void run() {
      updateStatus();
      meter.invalidate();
      if ((scopes.needsFrames() || ownAe()) && !maxResSession && texture.isAvailable() && analysisHandler != null && !analysing.get() && session != null) {
        int w = 480, h = Math.max(1, Math.round(480f * texture.getHeight() / Math.max(1, texture.getWidth())));
        if (grab == null || grab.getWidth() != w || grab.getHeight() != h) grab = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        texture.getBitmap(grab);
        analysing.set(true);
        final Bitmap frame = grab;
        analysisHandler.post(() -> {
          try { if (scopes.needsFrames()) scopes.analyze(frame); logAeStep(frame); }
          catch (RuntimeException e) { Log.w(TAG, "scopes", e); }
          finally { analysing.set(false); }
        });
      }
      ui.postDelayed(this, 80);
    }
  };

  private String startTimecodeFor(long wallMs) {
    if (todTimecode) {
      Calendar c = Calendar.getInstance(); c.setTimeInMillis(wallMs);
      long since = ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60 + c.get(Calendar.SECOND)) * CinemaMath.NS + c.get(Calendar.MILLISECOND) * 1_000_000L;
      return CinemaMath.timeOfDay(since, fps);
    }
    return CinemaMath.timecode(recordRunBase, fps);
  }

  private long lastSlowStatus;

  private void updateStatus() {
    String tc;
    if (todTimecode) tc = startTimecodeFor(System.currentTimeMillis());
    else tc = CinemaMath.timecode(recordRunBase + (recording ? CinemaMath.framesFor(SystemClock.elapsedRealtimeNanos() - recordStartNs, fps) : 0), fps);
    tcView.setText(tc);
    tcView.setTextColor(recording ? REC : TEXT);
    recLabel.setVisibility(recording ? View.VISIBLE : View.INVISIBLE);
    if (recording) {
      long ms = (SystemClock.elapsedRealtimeNanos() - recordStartNs) / 1_000_000;
      String buf = "";
      RawRecorder r = raw;
      if (rawMode && r != null) buf = String.format(Locale.US, "  RAW BUF %d/%d%s", r.backlog(), r.poolSize(), r.dropped > 0 ? "  DROP " + r.dropped : "");
      ProResRecorder pr = prores;
      if (proresMode && pr != null) buf = String.format(Locale.US, "  ENC %.0f fps · BUF %d/%d%s", pr.throughputFps(), pr.backlog(), pr.poolSize(), pr.dropped > 0 ? "  DROP " + pr.dropped : "");
      recLabel.setText(String.format(Locale.US, "● REC %s  %d:%02d%s", clipName == null ? "" : clipName.substring(0, 9), ms / 60000, (ms / 1000) % 60, buf));
      boolean strained = (rawMode && r != null && (r.dropped > 0 || r.backlog() >= r.poolSize() - 1))
          || (proresMode && pr != null && (pr.dropped > 0 || pr.backlog() >= pr.poolSize() - 1));
      recLabel.setTextColor(strained ? ACCENT : REC);
    }
    // exposure readouts: live values in auto, set values in manual
    long exp = aeAuto ? liveExposure : exposureNs();
    shutterCell.set(shutterAsAngle ? CinemaMath.angleLabel(Math.round(CinemaMath.angleForExposure(exp, fps) * 10) / 10.0) : CinemaMath.speedLabel(exp), aeAuto);
    int hr = logHeadroom();
    isoCell.set((hr > 0 ? "EI " : "") + ((aeAuto ? liveIso : iso >> hr) << hr), aeAuto);
    float[] g = liveGains;
    int k = awbAuto && g != null ? CinemaMath.estimateKelvin(lens.wb, g[0], g[3]) : kelvin;
    wbCell.set((awbAuto ? Math.round(k / 50f) * 50 : k) + "K" + (!awbAuto && tint != 0 ? (tint > 0 ? " +" : " ") + tint : ""), awbAuto);
    focusCell.set(manualFocus ? focusLabel(focus) : afLabel(), !manualFocus);
    zoomCell.set(String.format(Locale.US, "%.1fx", zoom), false);
    // storage, battery, heat (polled every ~2 s)
    long nowMs = SystemClock.uptimeMillis();
    if (nowMs - lastSlowStatus < 2000) return;
    lastSlowStatus = nowMs;
    long free = rawMode ? rawFreeBytes() : new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
    long bitsPerSecond = rawMode ? rawBytesPerSecond() * 8 + (audioOn && wavOn ? 48_000 * 64 : 0)
        : proresMode ? ProRes.bitrate(proresSize.getWidth(), proresSize.getHeight(), fps, proresProfile) + (audioOn ? 48_000 * 48 + (wavOn ? 48_000 * 64 : 0) : 0)
        : targetBitrate() + (audioOn ? 320_000 + (wavOn ? 48_000 * 64 : 0) : 0);
    double minutes = CinemaMath.minutesRemaining(free, bitsPerSecond);
    // Stop cleanly before the card is full, so the clip (and its last frames) are kept intact.
    if (recording && free < Math.max(300L * 1024 * 1024, bitsPerSecond / 8 * 4) && !recordBusy) { message("Storage full: recording stopped."); toggleRecord(); }
    storageView.setText(CinemaMath.formatMinutes(minutes));
    storageView.setTextColor(minutes < 10 ? REC : TEXT);
    Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    int level = bat == null ? -1 : bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
    int thermal = ((PowerManager) getSystemService(POWER_SERVICE)).getCurrentThermalStatus();
    String heat = thermal >= PowerManager.THERMAL_STATUS_SEVERE ? "  HOT" : thermal >= PowerManager.THERMAL_STATUS_MODERATE ? "  WARM" : "";
    batteryView.setText((level >= 0 ? level + "%" : "") + heat);
    batteryView.setTextColor(level >= 0 && level < 15 || thermal >= PowerManager.THERMAL_STATUS_SEVERE ? REC : thermal >= PowerManager.THERMAL_STATUS_MODERATE ? ACCENT : TEXT);
  }

  private String afLabel() {
    Integer s = liveAfState;
    if (s == null) return "AF";
    if (s == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || s == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) return "AF ●";
    if (s == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN || s == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN) return "AF …";
    return "AF";
  }

  static String focusLabel(float diopters) {
    if (diopters <= 0.02f) return "∞";
    float m = 1 / diopters;
    return m >= 10 ? Math.round(m) + "m" : m >= 1 ? String.format(Locale.US, "%.1fm", m) : Math.round(m * 100) + "cm";
  }

  // ================================================================== UI construction

  private int dp(float v) { return Math.round(v * density); }

  private void buildUi() {
    root = new FrameLayout(this);
    root.setBackgroundColor(BG);
    texture = new TextureView(this);
    texture.setSurfaceTextureListener(surfaceListener);
    root.addView(texture, new FrameLayout.LayoutParams(1, 1));
    monitorView = new android.widget.ImageView(this);
    monitorView.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
    monitorView.setVisibility(View.GONE);
    root.addView(monitorView, new FrameLayout.LayoutParams(1, 1));
    scopes = new CinemaScopes(this) {
      @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (recording) { // red tally frame, like a cinema camera's REC border
          Paint p = new Paint(); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(3)); p.setColor(REC);
          c.drawRect(frame.left + dp(1.5f), frame.top + dp(1.5f), frame.right - dp(1.5f), frame.bottom - dp(1.5f), p);
        }
      }
    };
    root.addView(scopes, new FrameLayout.LayoutParams(-1, -1));
    applyMonitorPrefs();
    scopes.setOnTouchListener(this::onPreviewTouch);

    // ---- top status bar
    topBar = row(); topBar.setBackgroundColor(BAR); topBar.setPadding(dp(10), 0, dp(10), 0); topBar.setGravity(Gravity.CENTER_VERTICAL);
    lensView = status("", v -> chooseLens());
    formatView = status("", v -> showSettings());
    tcView = mono("00:00:00:00", 18, TEXT); tcView.setGravity(Gravity.CENTER); tcView.setOnClickListener(v -> resetTimecode());
    storageView = status("", null); batteryView = status("", null);
    topBar.addView(lensView, wrap(dp(10)));
    topBar.addView(formatView, new LinearLayout.LayoutParams(0, -1, 1));
    topBar.addView(tcView, new LinearLayout.LayoutParams(-2, -1));
    LinearLayout right = row(); right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    right.addView(storageView, wrap(dp(14))); right.addView(batteryView, wrap(0));
    topBar.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
    root.addView(topBar, new FrameLayout.LayoutParams(-1, dp(34), Gravity.TOP));

    recLabel = mono("", 12, REC); recLabel.setVisibility(View.INVISIBLE);
    FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL); rl.topMargin = dp(38);
    root.addView(recLabel, rl);

    // ---- bottom parameter bar
    bottomBar = row(); bottomBar.setBackgroundColor(BAR); bottomBar.setPadding(dp(6), 0, dp(6), 0);
    fpsCell = cell("FPS", () -> togglePanel("format"));
    shutterCell = cell("SHUTTER", () -> togglePanel("shutter"));
    isoCell = cell("ISO", () -> togglePanel("iso"));
    wbCell = cell("WB", () -> togglePanel("wb"));
    focusCell = cell("FOCUS", () -> togglePanel("focus"));
    zoomCell = cell("ZOOM", () -> togglePanel("zoom"));
    lookCell = cell("LOOK", () -> togglePanel("look"));
    meter = new Meter(this);
    meter.setOnClickListener(v -> togglePanel("audio"));
    for (Cell c : new Cell[] {fpsCell, shutterCell, isoCell, wbCell, focusCell, zoomCell, lookCell}) bottomBar.addView(c, new LinearLayout.LayoutParams(0, -1, 1));
    bottomBar.addView(meter, new LinearLayout.LayoutParams(0, -1, 1.4f));
    root.addView(bottomBar, new FrameLayout.LayoutParams(-1, dp(50), Gravity.BOTTOM));

    // ---- right column: REC + monitoring toggles
    side = new LinearLayout(this); side.setOrientation(LinearLayout.VERTICAL); side.setGravity(Gravity.CENTER_HORIZONTAL);
    side.setBackgroundColor(BG); side.setPadding(dp(4), dp(8), dp(4), dp(8));
    recButton = new RecButton(this); recButton.setOnClickListener(v -> toggleRecord());
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(64), dp(64)); rp.bottomMargin = dp(8);
    LinearLayout togglesGrid = new LinearLayout(this); togglesGrid.setOrientation(LinearLayout.VERTICAL);
    addToggle(togglesGrid, "ZEBRA", () -> scopes.zebra, v -> scopes.zebra = v);
    addToggle(togglesGrid, "PEAK", () -> scopes.peaking, v -> scopes.peaking = v);
    addToggle(togglesGrid, "FALSE", () -> scopes.falseColor, v -> scopes.falseColor = v);
    addCycle(togglesGrid, () -> new String[] {"SCOPE", "HIST", "WAVE", "PARADE"}[scopes.scopeMode], () -> scopes.scopeMode = (scopes.scopeMode + 1) % 4);
    addCycle(togglesGrid, () -> scopes.guide == 0 ? "GUIDE" : CinemaMath.GUIDE_NAMES[scopes.guide], () -> scopes.guide = (scopes.guide + 1) % CinemaMath.GUIDE_NAMES.length);
    addToggle(togglesGrid, "GRID", () -> scopes.grid, v -> scopes.grid = v);
    addToggle(togglesGrid, "LEVEL", () -> scopes.level, v -> scopes.level = v);
    Button settings = chip("MENU", false, this::showSettings);
    togglesGrid.addView(settings, new LinearLayout.LayoutParams(-1, dp(30)));
    ScrollView sc = new ScrollView(this); sc.setVerticalScrollBarEnabled(false); sc.addView(togglesGrid);
    side.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
    side.addView(recButton, 0, rp);
    root.addView(side, new FrameLayout.LayoutParams(dp(80), -1, Gravity.END));

    // ---- control panel (shown above the bottom bar)
    panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setBackgroundColor(PANEL); panel.setPadding(dp(12), dp(8), dp(12), dp(8));
    panel.setVisibility(View.GONE);
    FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); pp.bottomMargin = dp(50);
    root.addView(panel, pp);

    messageView = new TextView(this); messageView.setTextColor(TEXT); messageView.setTextSize(13); messageView.setPadding(dp(14), dp(8), dp(14), dp(8));
    GradientDrawable mb = new GradientDrawable(); mb.setColor(0xe6202428); mb.setCornerRadius(dp(8)); messageView.setBackground(mb);
    messageView.setVisibility(View.GONE);
    FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER); root.addView(messageView, ml);

    root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol || b - t != ob - ot) layoutPreview(); });
    setContentView(root);
    refreshUi();
  }

  /** Sizes the preview to the recorded aspect ratio (times the anamorphic desqueeze), centred left of the side column. */
  private void layoutPreview() {
    int sideW = dp(80);
    int aw = root.getWidth() - sideW, ah = root.getHeight();
    if (aw <= 0 || ah <= 0) return;
    // the top and bottom bars float over the picture; keep the full height available
    double d = CinemaMath.DESQUEEZE[prefs.getInt("desqueeze", 0)];
    Size frame = frameSize();
    double aspect = frame.getWidth() / (double) frame.getHeight();
    double shown = aspect * d;
    int w, h;
    if (aw / (double) ah > shown) { h = ah; w = (int) Math.round(ah * shown); } else { w = aw; h = (int) Math.round(aw / shown); }
    int baseW = (int) Math.round(w / d);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(baseW, h);
    lp.leftMargin = (aw - baseW) / 2; lp.topMargin = (ah - h) / 2;
    texture.setLayoutParams(lp);
    monitorView.setLayoutParams(new FrameLayout.LayoutParams(lp));
    monitorView.setPivotX(baseW / 2f); monitorView.setPivotY(h / 2f); monitorView.setScaleX((float) d);
    texture.setPivotX(baseW / 2f); texture.setPivotY(h / 2f); texture.setScaleX((float) d);
    // Camera buffers arrive in the sensor's orientation; rotate them for the landscape display.
    Matrix m = new Matrix();
    int rotation = getDisplay() == null ? Surface.ROTATION_90 : getDisplay().getRotation();
    RectF view = new RectF(0, 0, baseW, h), buffer = new RectF(0, 0, frame.getHeight(), frame.getWidth());
    float cx = view.centerX(), cy = view.centerY();
    if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
      buffer.offset(cx - buffer.centerX(), cy - buffer.centerY());
      m.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL);
      float scale = Math.max(h / (float) frame.getHeight(), baseW / (float) frame.getWidth());
      m.postScale(scale, scale, cx, cy);
      m.postRotate(90 * (rotation - 2), cx, cy);
    } else if (rotation == Surface.ROTATION_180) m.postRotate(180, cx, cy);
    texture.setTransform(m);
    scopes.frame.set(lp.leftMargin + baseW / 2f - w / 2f, lp.topMargin, lp.leftMargin + baseW / 2f + w / 2f, lp.topMargin + h);
    scopes.insetTop = Math.max(0, dp(34) - lp.topMargin);
    scopes.insetBottom = Math.max(0, dp(50) - (ah - lp.topMargin - h));
    FrameLayout.LayoutParams tb = (FrameLayout.LayoutParams) topBar.getLayoutParams(); tb.rightMargin = sideW; topBar.setLayoutParams(tb);
    FrameLayout.LayoutParams bb = (FrameLayout.LayoutParams) bottomBar.getLayoutParams(); bb.rightMargin = sideW; bottomBar.setLayoutParams(bb);
    FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) panel.getLayoutParams(); pl.rightMargin = sideW; panel.setLayoutParams(pl);
    scopes.invalidate();
  }

  private void refreshUi() {
    if (lens == null) return;
    lensView.setText(lens.label);
    String codec = tenBit ? "HEVC 10-bit HLG" : hevc ? "HEVC" : "H.264";
    String speed = fps > 60 && conformFps > 0 ? " → " + CinemaMath.fpsLabel(conformFps) : "";
    if (proresMode) formatView.setText(ProRes.NAMES[proresProfile] + "  " + proresSize.getWidth() + "×" + proresSize.getHeight() + "  " + CinemaMath.fpsLabel(fps) + "p  " + CinemaMath.formatBitrate(ProRes.bitrate(proresSize.getWidth(), proresSize.getHeight(), fps, proresProfile)));
    else if (rawMode && rawSize != null) formatView.setText((rawOpt != null && rawOpt.is8k() ? "8K " : "") + "RAW " + rawSize.getWidth() + "×" + rawSize.getHeight() + "  " + CinemaMath.fpsLabel(fps) + "p  CinemaDNG " + rawFormatName() + "  " + (rawCompressed() ? "~" : "") + rawBytesPerSecond() / 1_000_000 + " MB/s");
    else formatView.setText(resolutionName(videoSize) + "  " + CinemaMath.fpsLabel(fps) + speed + "p  " + codec + "  " + CinemaMath.formatBitrate(targetBitrate()));
    fpsCell.set(CinemaMath.fpsLabel(fps) + speed, false);
    lookCell.set(rawMode ? "RAW" : curveIgnored && !tenBit && !"Rec.709".equals(CinemaMath.LOOKS[lookIndex]) ? "Phone look" : proresMode ? CinemaMath.LOOKS[lookIndex] : tenBit ? "HLG" : CinemaMath.LOOKS[lookIndex], false);
    recButton.recording = recording; recButton.invalidate();
    updateClipLuma();
    for (Toggle t : toggles) t.sync();
    scopes.invalidate();
    if (openPanel != null) buildPanel(openPanel);
  }

  static String resolutionName(Size s) {
    int w = s.getWidth(), h = s.getHeight();
    if (w >= 4096) return "4K DCI"; if (w >= 3840) return "4K UHD"; if (w >= 2560) return w + "×" + h;
    if (h >= 1080) return w == 1920 ? "1080p" : w + "×" + h; if (h >= 720) return "720p"; return w + "×" + h;
  }

  private void resetTimecode() {
    if (recording || todTimecode) return;
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Reset record-run timecode to 00:00:00:00?")
        .setPositiveButton("Reset", (d, w) -> { recordRunBase = 0; savePrefs(); }).setNegativeButton("Cancel", null).show();
  }

  // ================================================================== touch: tap to focus/expose, double tap to clear

  private long lastTap;

  private boolean onPreviewTouch(View v, MotionEvent e) {
    if (e.getAction() != MotionEvent.ACTION_UP) return true;
    if (openPanel != null) { togglePanel(openPanel); return true; }
    float[] n = scopes.normalised(e.getX(), e.getY());
    if (n == null) return true;
    long now = SystemClock.uptimeMillis();
    if (now - lastTap < 300) { meteringPoint = null; message("Metering: whole frame"); }
    else { meteringPoint = n; if (manualFocus) message("Exposure point set (focus is manual)"); }
    lastTap = now;
    applyRepeating();
    return true;
  }

  // ================================================================== panels

  private void togglePanel(String id) {
    if (id.equals(openPanel)) { openPanel = null; panel.setVisibility(View.GONE); return; }
    openPanel = id;
    buildPanel(id);
    panel.setVisibility(View.VISIBLE);
  }

  private void buildPanel(String id) {
    panel.removeAllViews();
    switch (id) {
      case "shutter": shutterPanel(); break;
      case "iso": isoPanel(); break;
      case "wb": wbPanel(); break;
      case "focus": focusPanel(); break;
      case "zoom": zoomPanel(); break;
      case "look": lookPanel(); break;
      case "audio": audioPanel(); break;
      default: formatPanel(); break;
    }
  }

  private LinearLayout panelHeader(String title, String value) {
    LinearLayout r = row(); r.setGravity(Gravity.CENTER_VERTICAL);
    TextView t = label(title, 11, DIM); r.addView(t, wrap(dp(10)));
    TextView v = mono(value, 15, TEXT); r.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
    panel.addView(r, new LinearLayout.LayoutParams(-1, dp(26)));
    return r;
  }

  private LinearLayout chips() {
    LinearLayout r = row();
    HorizontalScrollView s = new HorizontalScrollView(this); s.setHorizontalScrollBarEnabled(false); s.addView(r);
    panel.addView(s, new LinearLayout.LayoutParams(-1, dp(36)));
    return r;
  }

  private void addChip(LinearLayout r, String text, boolean active, Runnable a) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(30)); lp.rightMargin = dp(6); lp.topMargin = dp(3);
    r.addView(chip(text, active, a), lp);
  }

  private void addSlider(int max, int progress, IntConsumer change, boolean enabled) {
    SeekBar s = new SeekBar(this);
    s.setMax(max); s.setProgress(progress); s.setEnabled(enabled);
    s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar b, int p, boolean user) { if (user) change.accept(p); }
      @Override public void onStartTrackingTouch(SeekBar b) {}
      @Override public void onStopTrackingTouch(SeekBar b) { savePrefs(); }
    });
    panel.addView(s, new LinearLayout.LayoutParams(-1, dp(34)));
  }

  /** Switching from auto to manual starts from what auto exposure was doing, so the picture doesn't jump. */
  private void setAeAuto(boolean auto) {
    if (!auto && !lens.manualSensor) { message("This camera doesn't allow manual exposure."); return; }
    if (!auto && aeAuto) {
      int hr = logHeadroom(); // still the auto-exposure headroom: the live ISO is the sensor ISO, the EI is 2^hr higher
      iso = Math.max(lens.isoRange.getLower(), Math.min(lens.isoRange.getUpper(), liveIso)) << hr;
      shutterAngle = CinemaMath.SHUTTER_ANGLES[CinemaMath.nearest(CinemaMath.SHUTTER_ANGLES, CinemaMath.angleForExposure(liveExposure, fps))];
      shutterNs = liveExposure;
    }
    aeAuto = auto; applyRepeating(); refreshUi();
  }

  private void shutterPanel() {
    long exp = aeAuto ? liveExposure : exposureNs();
    panelHeader(shutterAsAngle ? "SHUTTER ANGLE" : "SHUTTER SPEED", (shutterAsAngle ? CinemaMath.angleLabel(CinemaMath.angleForExposure(exp, fps)) : CinemaMath.speedLabel(exp)) + (aeAuto ? "  (auto)" : ""));
    LinearLayout c = chips();
    addChip(c, "AUTO", aeAuto, () -> setAeAuto(!aeAuto));
    addChip(c, shutterAsAngle ? "° → 1/s" : "1/s → °", false, () -> { shutterAsAngle = !shutterAsAngle; if (!shutterAsAngle) shutterNs = exposureNs(); else shutterAngle = CinemaMath.SHUTTER_ANGLES[CinemaMath.nearest(CinemaMath.SHUTTER_ANGLES, CinemaMath.angleForExposure(shutterNs, fps))]; refreshUi(); });
    if (shutterAsAngle) {
      for (double a : new double[] {90, 144, 172.8, 180, 270, 360}) addChip(c, CinemaMath.angleLabel(a) + (a == 172.8 ? " 50Hz" : a == 144 ? " 60Hz" : ""), !aeAuto && Math.abs(shutterAngle - a) < 0.05, () -> { setManualShutterAngle(a); });
      addSlider(CinemaMath.SHUTTER_ANGLES.length - 1, CinemaMath.nearest(CinemaMath.SHUTTER_ANGLES, aeAuto ? CinemaMath.angleForExposure(liveExposure, fps) : shutterAngle), p -> setManualShutterAngle(CinemaMath.SHUTTER_ANGLES[p]), true);
    } else {
      int twice = (int) Math.round(fps * 2);
      for (int d : new int[] {twice, 50, 60, 100, 120}) addChip(c, "1/" + d, false, () -> setManualShutterSpeed(d));
      double[] speeds = new double[CinemaMath.SHUTTER_SPEEDS.length]; for (int i = 0; i < speeds.length; i++) speeds[i] = CinemaMath.SHUTTER_SPEEDS[i];
      addSlider(speeds.length - 1, CinemaMath.nearest(speeds, CinemaMath.NS / (double) exp), p -> setManualShutterSpeed(CinemaMath.SHUTTER_SPEEDS[p]), true);
    }
  }

  private void setManualShutterAngle(double a) {
    if (aeAuto) setAeAuto(false);
    if (aeAuto) return;
    shutterAngle = a; applyRepeating(); refreshUi();
  }

  private void setManualShutterSpeed(int denominator) {
    if (aeAuto) setAeAuto(false);
    if (aeAuto) return;
    shutterNs = CinemaMath.NS / denominator; applyRepeating(); refreshUi();
  }

  private void isoPanel() {
    int eiShift = cityLogActive() ? CinemaMath.LOG_HEADROOM : 0; // in the log modes the dial sets the exposure index
    List<Integer> stops = CinemaMath.isoStops(lens.isoRange.getLower(), lens.isoRange.getUpper() << eiShift);
    if (aeAuto) {
      double step = lens.evStep.doubleValue();
      panelHeader("EXPOSURE (auto) · ISO " + liveIso, String.format(Locale.US, "EV %+.1f", evIndex * step));
      LinearLayout c = chips();
      addChip(c, "AUTO", true, () -> setAeAuto(false));
      addChip(c, aeLock ? "AE LOCKED" : "AE LOCK", aeLock, () -> { aeLock = !aeLock; applyRepeating(); refreshUi(); });
      addChip(c, "EV 0", evIndex == 0, () -> { evIndex = 0; applyRepeating(); refreshUi(); });
      int lo = lens.evRange.getLower(), hi = lens.evRange.getUpper();
      addSlider(hi - lo, evIndex - lo, p -> { evIndex = lo + p; applyRepeating(); updatePanelHeader(String.format(Locale.US, "EV %+.1f", evIndex * step)); }, hi > lo);
      return;
    }
    panelHeader(eiShift > 0 ? "EXPOSURE INDEX (" + CinemaMath.LOOKS[lookIndex] + " · sensor ISO " + Math.max(lens.isoRange.getLower(), iso >> logHeadroom()) + ")" : "ISO", String.valueOf(iso));
    LinearLayout c = chips();
    addChip(c, "AUTO", false, () -> setAeAuto(true));
    for (int v : new int[] {100, 200, 400, 800, 1600, 3200}) if (v >= lens.isoRange.getLower() && v <= lens.isoRange.getUpper() << eiShift) addChip(c, String.valueOf(v), iso == v, () -> { iso = v; applyRepeating(); refreshUi(); });
    addSlider(stops.size() - 1, CinemaMath.nearest(stops, iso), p -> { iso = stops.get(p); applyRepeating(); updatePanelHeader(String.valueOf(iso)); isoCell.set(String.valueOf(iso), false); }, true);
  }

  private void updatePanelHeader(String value) {
    if (panel.getChildCount() == 0) return;
    LinearLayout header = (LinearLayout) panel.getChildAt(0);
    ((TextView) header.getChildAt(1)).setText(value);
  }

  private void wbPanel() {
    panelHeader("WHITE BALANCE" + (lens.cm1 != null ? " · sensor calibrated" : ""), awbAuto ? "Auto" : kelvin + "K  tint " + (tint > 0 ? "+" : "") + tint);
    LinearLayout c = chips();
    addChip(c, "AUTO", awbAuto, () -> {
      if (awbAuto) {
        if (!lens.manualPost) { message("This camera doesn't allow manual white balance."); return; }
        float[] g = liveGains; if (g != null) kelvin = Math.round(CinemaMath.estimateKelvin(lens.wb, g[0], g[3]) / 50f) * 50;
      }
      awbAuto = !awbAuto; applyRepeating(); refreshUi();
    });
    if (awbAuto && gamutActive()) addChip(c, "ONE-PUSH AWB", false, () -> { oneShotWhiteBalance(); message("Measuring white balance… point at something white or grey."); });
    else if (awbAuto) addChip(c, awbLock ? "AWB LOCKED" : "AWB LOCK", awbLock, () -> { awbLock = !awbLock; applyRepeating(); refreshUi(); });
    for (int k : CinemaMath.WB_PRESETS) addChip(c, k + "K", !awbAuto && kelvin == k, () -> { if (!lens.manualPost) return; awbAuto = false; kelvin = k; applyRepeating(); refreshUi(); });
    if (!awbAuto) {
      addSlider((10000 - 2000) / 50, (kelvin - 2000) / 50, p -> { kelvin = 2000 + p * 50; applyRepeating(); updatePanelHeader(kelvin + "K  tint " + tint); }, true);
      addSlider(100, tint + 50, p -> { tint = p - 50; applyRepeating(); updatePanelHeader(kelvin + "K  tint " + (tint > 0 ? "+" : "") + tint); }, true);
    }
  }

  private void focusPanel() {
    if (lens.minFocus <= 0) { panelHeader("FOCUS", "Fixed focus lens"); return; }
    panelHeader("FOCUS", manualFocus ? focusLabel(focus) + "   A " + (markA < 0 ? "–" : focusLabel(markA)) + "   B " + (markB < 0 ? "–" : focusLabel(markB)) : "Continuous AF · tap the picture to choose a subject");
    LinearLayout c = chips();
    addChip(c, manualFocus ? "MF" : "AF", !manualFocus, () -> { if (!manualFocus) focus = liveFocus; manualFocus = !manualFocus; applyRepeating(); refreshUi(); });
    if (manualFocus) {
      addChip(c, "SET A", false, () -> { markA = focus; refreshUi(); });
      addChip(c, "SET B", false, () -> { markB = focus; refreshUi(); });
      addChip(c, "PULL → A", false, () -> pullFocus(markA));
      addChip(c, "PULL → B", false, () -> pullFocus(markB));
      addChip(c, pullSeconds + "s pull", false, () -> { pullSeconds = pullSeconds == 1 ? 2 : pullSeconds == 2 ? 3 : pullSeconds == 3 ? 5 : pullSeconds == 5 ? 8 : 1; refreshUi(); });
      addChip(c, "∞", focus == 0, () -> { focus = 0; applyRepeating(); refreshUi(); });
      // Slider is linear in diopters, reversed so that left = near and right = far like a lens barrel.
      addSlider(1000, Math.round(1000 * (1 - focus / lens.minFocus)), p -> { cancelRamp(); focus = lens.minFocus * (1 - p / 1000f); applyRepeating(); updatePanelHeader(focusLabel(focus)); }, true);
    }
  }

  private void pullFocus(float target) {
    if (target < 0) { message("Set the mark first."); return; }
    final float from = focus;
    runRamp(pullSeconds * 1000L, t -> { focus = (float) CinemaMath.lerp(from, target, CinemaMath.ease(t)); applyRepeating(); }, this::refreshUi);
  }

  private void zoomPanel() {
    panelHeader("ZOOM", String.format(Locale.US, "%.2fx   (%.1f–%.0fx)", zoom, lens.minZoom, lens.maxZoom));
    LinearLayout c = chips();
    addChip(c, zoomRampSeconds == 0 ? "Instant" : zoomRampSeconds + "s ramp", zoomRampSeconds > 0, () -> { zoomRampSeconds = zoomRampSeconds == 0 ? 2 : zoomRampSeconds == 2 ? 4 : zoomRampSeconds == 4 ? 8 : 0; refreshUi(); });
    for (float z : new float[] {0.5f, 0.6f, 1, 1.5f, 2, 3, 5, 10}) if (z >= lens.minZoom - 0.01f && z <= lens.maxZoom + 0.01f) addChip(c, (z == Math.rint(z) ? String.valueOf((int) z) : String.valueOf(z)) + "x", Math.abs(zoom - z) < 0.02f, () -> zoomTo(Math.max(lens.minZoom, z)));
    final double lo = Math.log(lens.minZoom), hi = Math.log(Math.max(lens.maxZoom, lens.minZoom + 0.01f));
    addSlider(1000, (int) Math.round(1000 * (Math.log(zoom) - lo) / (hi - lo)), p -> { cancelRamp(); zoom = (float) Math.exp(lo + (hi - lo) * p / 1000.0); applyRepeating(); updatePanelHeader(String.format(Locale.US, "%.2fx", zoom)); zoomCell.set(String.format(Locale.US, "%.1fx", zoom), false); }, lens.maxZoom > lens.minZoom);
  }

  private void zoomTo(float target) {
    if (zoomRampSeconds == 0) { cancelRamp(); zoom = target; applyRepeating(); refreshUi(); return; }
    final float from = zoom;
    runRamp(zoomRampSeconds * 1000L, t -> { zoom = (float) CinemaMath.zoomAt(from, target, t); applyRepeating(); }, this::refreshUi);
  }

  interface RampStep { void at(double t); }

  /** Runs a timed move (focus pull, zoom) at ~60 Hz on the UI thread. */
  private void runRamp(long durationMs, RampStep step, Runnable done) {
    cancelRamp();
    final long start = SystemClock.uptimeMillis();
    ramp = new Runnable() {
      @Override public void run() {
        double t = (SystemClock.uptimeMillis() - start) / (double) durationMs;
        step.at(Math.min(1, t));
        if (t < 1 && ramp == this) ui.postDelayed(this, 16); else { if (ramp == this) ramp = null; done.run(); }
      }
    };
    ui.post(ramp);
  }

  private void cancelRamp() { if (ramp != null) { ui.removeCallbacks(ramp); ramp = null; } }

  private void lookPanel() {
    boolean curves = lens.curvePoints >= 16 && contains(lens.tonemapModes, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
    panelHeader("LOOK", rawMode ? CinemaMath.LOOKS[lookIndex] + " · monitoring only: RAW frames are recorded ungraded"
        : cityLogActive() || gamutActive() ? CinemaMath.LOOKS[lookIndex] + (logHeadroom() > 0 ? " · exposed " + logHeadroom() + " stops under the EI" : "") + " · grade as " + (gamutActive() ? CinemaMath.gamutName(CinemaMath.LOOKS[lookIndex]) : "Rec.709") + " / " + CinemaMath.LOOKS[lookIndex] + " or use " + lutFor(CinemaMath.LOOKS[lookIndex]) : tenBit ? "HLG (10-bit HDR uses its own curve)" : CinemaMath.LOOKS[lookIndex] + (curveIgnored ? "  · this phone ignores custom tone curves: its own processing is recorded" : curves ? "" : "  · this camera doesn't allow custom tone curves"));
    LinearLayout c = chips();
    for (int i = 0; i < CinemaMath.LOOKS.length; i++) {
      final int k = i;
      addChip(c, CinemaMath.LOOKS[i], lookIndex == i, () -> {
        lookIndex = k; applyRepeating(); refreshUi();
        if (gamutActive() && awbAuto) oneShotWhiteBalance(); // measure white once, then hold it in the log gamut
      });
    }
    LinearLayout d = chips();
    String[] details = {"Natural detail", "Standard detail", "Clean (strong NR)"};
    for (int i = 0; i < details.length; i++) { final int k = i; addChip(d, details[i], detail == i, () -> { detail = k; applyRepeating(); refreshUi(); }); }
  }

  private void audioPanel() {
    panelHeader("AUDIO · " + (audio.isRunning() ? audio.inputName + " · " + audio.channels + "ch 48 kHz" + (audio.floatInput ? " float" : "") : "off"), String.format(Locale.US, "Gain %+.0f dB", audio.gainDb));
    LinearLayout c = chips();
    addChip(c, audioOn ? "AUDIO ON" : "AUDIO OFF", audioOn, () -> { if (recording) return; audioOn = !audioOn; startAudio(); savePrefs(); refreshUi(); });
    addChip(c, "Input: " + audioSource, false, () -> {
      if (recording) return;
      String[] s = {"Camcorder", "Unprocessed", "Mic", "Voice"};
      int i = Arrays.asList(s).indexOf(audioSource); audioSource = s[(i + 1) % s.length]; startAudio(); savePrefs(); refreshUi();
    });
    addChip(c, "Device…", false, this::chooseAudioDevice);
    addChip(c, wavOn ? "WAV 32f ON" : "WAV OFF", wavOn, () -> { wavOn = !wavOn; savePrefs(); refreshUi(); });
    addChip(c, "Reset clip", audio.clipped, () -> { audio.clipped = false; refreshUi(); });
    addSlider(36, Math.round(audio.gainDb) + 12, p -> { audio.gainDb = p - 12; prefs.edit().putFloat("gain", audio.gainDb).apply(); updatePanelHeader(String.format(Locale.US, "Gain %+.0f dB", audio.gainDb)); }, true);
  }

  private void chooseAudioDevice() {
    if (recording) return;
    AudioDeviceInfo[] devs = ((AudioManager) getSystemService(AUDIO_SERVICE)).getDevices(AudioManager.GET_DEVICES_INPUTS);
    String[] names = new String[devs.length + 1]; names[0] = "Default (built-in)";
    int sel = 0;
    for (int i = 0; i < devs.length; i++) { names[i + 1] = devs[i].getProductName() + " · " + deviceType(devs[i].getType()); if (devs[i].getId() == audioDeviceId) sel = i + 1; }
    choose("Audio input", names, sel, i -> { audioDeviceId = i == 0 ? 0 : devs[i - 1].getId(); startAudio(); savePrefs(); refreshUi(); });
  }

  static String deviceType(int t) {
    switch (t) {
      case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "built-in";
      case AudioDeviceInfo.TYPE_USB_DEVICE: case AudioDeviceInfo.TYPE_USB_HEADSET: case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "USB";
      case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "wired";
      case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth";
      default: return "input " + t;
    }
  }

  private void formatPanel() {
    Size fs = frameSize();
    panelHeader("FORMAT", (rawMode ? "RAW " : proresMode ? ProRes.NAMES[proresProfile] + " " : resolutionName(fs) + " ") + fs.getWidth() + "×" + fs.getHeight() + " · " + CinemaMath.fpsLabel(fps) + " fps");
    LinearLayout c = chips();
    for (double f : availableRates()) addChip(c, CinemaMath.fpsLabel(f), Math.abs(f - fps) < 0.001, () -> { if (recording) return; fps = f; if (f <= 60) conformFps = 0; reopen(); });
    if (fps > 60) {
      LinearLayout o = chips();
      addChip(o, "Real time", conformFps == 0, () -> { conformFps = 0; reopen(); });
      for (double f : new double[] {23.976, 24, 25, 29.97, 30}) addChip(o, "Slow-mo → " + CinemaMath.fpsLabel(f), Math.abs(conformFps - f) < 0.001, () -> { conformFps = f; reopen(); });
    }
    LinearLayout r = chips();
    if (proresMode) {
      for (Size s : proresSizes()) addChip(r, s.getWidth() + "×" + s.getHeight(), s.equals(proresSize), () -> { if (recording) return; proresSize = s; reopen(); });
      LinearLayout pp = chips();
      for (int i = 0; i < 4; i++) { final int k = i; addChip(pp, ProRes.SHORT[i], proresProfile == i, () -> { if (recording) return; proresProfile = k; savePrefs(); refreshUi(); }); }
      return;
    }
    if (rawMode) {
      for (RawOption o : rawOptions()) addChip(r, o.label + " ≤" + CinemaMath.fpsLabel(Math.floor(maxFps(o))) + "fps", o.key.equals(rawKey), () -> { if (recording) return; rawKey = o.key; reopen(); });
      addChip(r, rawFormatName(), false, this::chooseRawOptions);
      return;
    }
    for (Size s : videoSizes()) addChip(r, resolutionName(s) + (resolutionName(s).contains("×") ? "" : " " + s.getWidth() + "×" + s.getHeight()), s.equals(videoSize), () -> { if (recording) return; videoSize = s; reopen(); });
  }

  // ================================================================== settings menu

  private void showSettings() {
    if (lens == null) return;
    CinemaRecorder.EncoderCaps hevcCaps = CinemaRecorder.caps(MediaFormat.MIMETYPE_VIDEO_HEVC);
    boolean hlgPossible = lens.hlg && hevcCaps.main10 && fps <= 60;
    String[] items = {
      "Recording: " + (rawMode ? "CinemaDNG RAW" : proresMode ? ProRes.NAMES[proresProfile] : "MP4 (H.264 / HEVC)"),
      proresMode ? "ProRes: " + ProRes.NAMES[proresProfile] + " · " + proresSize.getWidth() + "×" + proresSize.getHeight() + " · " + CinemaMath.formatBitrate(ProRes.bitrate(proresSize.getWidth(), proresSize.getHeight(), fps, proresProfile)) :
      "RAW: " + (rawOpt != null ? rawOpt.label : rawSize != null ? rawSize.getWidth() + "×" + rawSize.getHeight() : "–") + " · " + rawFormatName() + " · " + rawBytesPerSecond() / 1_000_000 + " MB/s",
      "Codec: " + (tenBit ? "HEVC 10-bit" : hevc ? "HEVC (H.265)" : "H.264"),
      "Dynamic range: " + (tenBit ? "HDR HLG 10-bit BT.2020" : "SDR 8-bit BT.709") + (hlgPossible ? "" : " (10-bit not supported here)"),
      "Quality: " + CinemaMath.QUALITY[quality] + " · " + CinemaMath.formatBitrate(targetBitrate()),
      "GOP: " + new String[] {"Long GOP (1 s)", "Short GOP (0.5 s)", "All-Intra"}[gop],
      "Bitrate mode: " + (cbr ? "CBR" : "VBR"),
      "Stabilization: " + new String[] {"Off", "Optical", "Digital", "Optical + digital", "Preview-matched"}[stabilization],
      "Anti-flicker: " + new String[] {"Auto", "50 Hz", "60 Hz", "Off"}[antibanding],
      "Timecode: " + (todTimecode ? "Time of day" : "Record run"),
      "Clip naming: " + CinemaMath.clipName(cameraLetter, reel, clip, "YYYYMMDD"),
      "Zebra level: " + scopes.zebraIre + " IRE",
      "Focus peaking: " + peakName() + " · sensitivity " + Math.round(scopes.peakSensitivity * 100) + "%",
      "Anamorphic desqueeze: " + CinemaMath.DESQUEEZE_NAMES[prefs.getInt("desqueeze", 0)],
      "Center marker: " + (scopes.center ? "On" : "Off"),
      "Lens: " + lens.label,
      "Export log LUTs (.cube): City Log, RED, Sony, Blackmagic",
      "ProRes speed test (can this phone record 4K ProRes?)",
      "Auto exposure: " + (aeKeepShutter ? "keep shutter angle, ISO only (cinema)" : "ISO and shutter"),
      "White balance while recording: " + (lockWbOnRecord ? "locked" : "follows the light"),
      "About this camera",
    };
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("City Cinema · Menu").setItems(items, (d, which) -> onSetting(which, hlgPossible)).show();
  }

  private void onSetting(int item, boolean hlgPossible) {
    boolean busy = recording;
    if (item == 0) {
      if (busy) return;
      String[] formats = {"MP4 · H.264 / HEVC, sound inside",
          "ProRes 422 (.mov) · Proxy / LT / 422 / HQ, 24-bit sound, timecode" + (ProRes.AVAILABLE ? "" : " (not in this build)"),
          "CinemaDNG RAW · DNG frame sequence + WAV" + (lens.raw ? "" : " (not offered by this lens)")};
      choose("Recording format", formats, rawMode ? 2 : proresMode ? 1 : 0, i -> {
        if (i == 1 && !ProRes.AVAILABLE) { message("This build has no ProRes encoder (built without the NDK)."); return; }
        if (i == 2 && !lens.raw) { message(lens.label + " doesn't offer RAW output (needs the Camera2 RAW capability and a Bayer sensor)."); return; }
        proresMode = i == 1; rawMode = i == 2; reopen();
      });
      return;
    }
    if (item == 1) {
      if (proresMode) { chooseProResOptions(); return; }
      if (!rawMode) { message("Switch Recording to ProRes or CinemaDNG RAW first."); return; }
      chooseRawOptions(); return;
    }
    if ((rawMode || proresMode) && item >= 2 && item <= 6) { message("Codec settings apply to MP4. " + (rawMode ? "RAW frames are uncompressed CinemaDNG." : "ProRes has its own profiles (Menu → ProRes).")); return; }
    int which = item - 2;
    switch (which) {
      case 0: if (busy) break; choose("Codec", new String[] {"HEVC (H.265) · smaller files", "H.264 · most compatible"}, hevc ? 0 : 1, i -> { hevc = i == 0; if (!hevc) tenBit = false; reopen(); }); break;
      case 1: if (busy) break;
        if (!hlgPossible) { message("10-bit HLG needs Android 13+, a 10-bit camera, a Main10 HEVC encoder and 60 fps or lower."); break; }
        choose("Dynamic range", new String[] {"SDR 8-bit BT.709", "HDR HLG 10-bit BT.2020"}, tenBit ? 1 : 0, i -> { tenBit = i == 1; if (tenBit) hevc = true; reopen(); }); break;
      case 2: if (busy) break; choose("Quality", labelsWithBitrate(), quality, i -> { quality = i; reopen(); }); break;
      case 3: if (busy) break; choose("GOP", new String[] {"Long GOP (1 s) · efficient", "Short GOP (0.5 s) · easier to edit", "All-Intra · every frame a keyframe, best for grading"}, gop, i -> { gop = i; reopen(); }); break;
      case 4: if (busy) break; choose("Bitrate mode", new String[] {"VBR · variable", "CBR · constant"}, cbr ? 1 : 0, i -> { cbr = i == 1; reopen(); }); break;
      case 5: choose("Stabilization", new String[] {"Off (tripod, gimbal)", "Optical (OIS)", "Digital (EIS, crops)", "Optical + digital", "Preview-matched (Android 13+)"}, stabilization, i -> { stabilization = i; applyRepeating(); savePrefs(); }); break;
      case 6: choose("Anti-flicker", new String[] {"Auto", "50 Hz mains", "60 Hz mains", "Off"}, antibanding, i -> { antibanding = i; applyRepeating(); savePrefs(); }); break;
      case 7: choose("Timecode", new String[] {"Record run (advances only while recording)", "Time of day (sync multiple cameras)"}, todTimecode ? 1 : 0, i -> { todTimecode = i == 1; savePrefs(); }); break;
      case 8: clipNaming(); break;
      case 9: choose("Zebra level", new String[] {"70 IRE (skin)", "80 IRE", "90 IRE", "95 IRE", "100 IRE (clipping)"}, Arrays.asList(70, 80, 90, 95, 100).indexOf(scopes.zebraIre), i -> { scopes.zebraIre = new int[] {70, 80, 90, 95, 100}[i]; savePrefs(); }); break;
      case 10: choose("Focus peaking", new String[] {"Red · normal", "Green · normal", "White · normal", "Blue · normal", "Red · high sensitivity", "Red · low sensitivity"}, 0, i -> {
          int[] colors = {0xffff3b30, 0xff4cff6a, 0xffffffff, 0xff3aa0ff, 0xffff3b30, 0xffff3b30};
          scopes.peakColor = colors[i]; scopes.peakSensitivity = i == 4 ? 0.85f : i == 5 ? 0.2f : 0.5f; savePrefs(); }); break;
      case 11: choose("Anamorphic desqueeze (preview only)", CinemaMath.DESQUEEZE_NAMES, prefs.getInt("desqueeze", 0), i -> { prefs.edit().putInt("desqueeze", i).apply(); layoutPreview(); }); break;
      case 12: scopes.center = !scopes.center; savePrefs(); scopes.invalidate(); break;
      case 13: chooseLens(); break;
      case 14: exportLuts(); break;
      case 15: proresSpeedTest(); break;
      case 16: choose("Auto exposure", new String[] {"Keep shutter angle, change ISO only (cinema: steady motion blur; use ND in bright light)", "Change ISO and shutter (never overexposes, motion blur varies)"}, aeKeepShutter ? 0 : 1, i -> { aeKeepShutter = i == 0; savePrefs(); }); break;
      case 17: choose("Auto white balance while recording", new String[] {"Lock when recording starts (steady colour, like a cinema camera)", "Keep adjusting during the take"}, lockWbOnRecord ? 0 : 1, i -> { lockWbOnRecord = i == 0; savePrefs(); }); break;
      default: about(); break;
    }
  }

  private String[] labelsWithBitrate() {
    String[] out = new String[CinemaMath.QUALITY.length];
    CinemaRecorder.EncoderCaps caps = CinemaRecorder.caps(hevc || tenBit ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC);
    for (int i = 0; i < out.length; i++) {
      long b = CinemaMath.bitrate(videoSize.getWidth(), videoSize.getHeight(), fps, i, hevc || tenBit, tenBit, gop == 2);
      out[i] = CinemaMath.QUALITY[i] + " · " + CinemaMath.formatBitrate(caps.available ? Math.min(b, caps.maxBitrate) : b) + (caps.available && b > caps.maxBitrate ? " (encoder max)" : "");
    }
    return out;
  }

  private String peakName() {
    int c = scopes.peakColor;
    return c == 0xff4cff6a ? "Green" : c == 0xffffffff ? "White" : c == 0xff3aa0ff ? "Blue" : "Red";
  }

  private void clipNaming() {
    String[] cams = {"Camera A", "Camera B", "Camera C", "Camera D", "Next reel (" + String.format(Locale.US, "%03d", reel + 1) + ", clip 1)", "Reset to reel 001, clip 1"};
    choose("Clip naming · " + CinemaMath.clipName(cameraLetter, reel, clip, "YYYYMMDD"), cams, cameraLetter - 'A', i -> {
      if (i < 4) cameraLetter = (char) ('A' + i); else if (i == 4) { reel++; clip = 1; } else { reel = 1; clip = 1; }
      savePrefs();
    });
  }

  private void about() {
    CinemaRecorder.EncoderCaps h = CinemaRecorder.caps(MediaFormat.MIMETYPE_VIDEO_HEVC), a = CinemaRecorder.caps(MediaFormat.MIMETYPE_VIDEO_AVC);
    String s = "Camera " + lens.id + " (" + lens.label + ")\n"
        + "Manual exposure: " + yes(lens.manualSensor) + "\nManual white balance: " + yes(lens.manualPost) + (lens.cm1 != null ? " (sensor calibrated)" : "")
        + "\nCustom tone curves: " + yes(lens.curvePoints >= 16) + " (" + lens.curvePoints + " points)"
        + "\nHigh frame rate: " + yes(lens.highSpeed) + "\n10-bit HLG camera: " + yes(lens.hlg)
        + "\nISO " + lens.isoRange.getLower() + "–" + lens.isoRange.getUpper() + " · shutter " + CinemaMath.speedLabel(lens.expRange.getUpper()) + "–" + CinemaMath.speedLabel(lens.expRange.getLower())
        + "\nZoom " + lens.minZoom + "–" + lens.maxZoom + "x · min focus " + (lens.minFocus > 0 ? focusLabel(lens.minFocus) : "fixed")
        + "\nHEVC encoder: " + h.name + " · max " + CinemaMath.formatBitrate(h.maxBitrate) + " · Main10 " + yes(h.main10)
        + "\nH.264 encoder: " + a.name + " · max " + CinemaMath.formatBitrate(a.maxBitrate)
        + "\nProRes 422: " + (ProRes.AVAILABLE ? "yes (software encoder, " + Runtime.getRuntime().availableProcessors() + " cores)" : "not in this build") + "\n8K RAW: " + (lens.maxRes ? "maximum-resolution mode " + lens.maxResRawSizes[0].getWidth() + "×" + lens.maxResRawSizes[0].getHeight() + (lens.binning == 2 ? " (quad Bayer, remosaiced)" : "") : lens.raw && lens.rawSizes[0].getWidth() >= 7680 ? "8K crop of the full-size RAW" : "not on this lens")
        + "\nRAW (CinemaDNG): " + (lens.raw ? "yes, " + lens.rawSizes[0].getWidth() + "×" + lens.rawSizes[0].getHeight() + ", white level " + lens.whiteLevel + ", " + new String[] {"RGGB", "GRBG", "GBRG", "BGGR"}[lens.cfa] : "no")
        + "\n\nClips: Movies/City Cinema · WAV: Music/City Cinema · metadata: Documents/City Cinema"
        + "\nRAW clips: " + rawRoot().getPath() + " (one folder of DNG frames + WAV + JSON per clip; copy them off over USB. They are removed if the app is uninstalled.)"
        + "\nVolume keys start and stop recording. Tap the picture to set the focus/exposure point; double-tap to clear it. Tap the timecode to reset it.";
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("About City Cinema").setMessage(s).setPositiveButton("OK", null).show();
  }

  static String yes(boolean b) { return b ? "yes" : "no"; }

  private void chooseLens() {
    if (recording) { message("Stop recording to change lens."); return; }
    String[] names = new String[lenses.size()];
    for (int i = 0; i < names.length; i++) names[i] = lenses.get(i).label + " · id " + lenses.get(i).id + (lenses.get(i).manualSensor ? " · manual" : "");
    choose("Lens", names, lenses.indexOf(lens), i -> { lens = lenses.get(i); prefs.edit().putInt("lens", i).apply(); meteringPoint = null; zoom = 1; reopen(); });
  }

  private void choose(String title, String[] options, int selected, IntConsumer pick) {
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
        .setSingleChoiceItems(options, selected, (d, i) -> { d.dismiss(); pick.accept(i); refreshUi(); }).setNegativeButton("Cancel", null).show();
  }

  // ================================================================== prefs

  private void loadPrefs() {
    SharedPreferences p = prefs;
    videoSize = new Size(p.getInt("w", 3840), p.getInt("h", 2160));
    fps = Double.longBitsToDouble(p.getLong("fps", Double.doubleToLongBits(24)));
    conformFps = Double.longBitsToDouble(p.getLong("conform", Double.doubleToLongBits(0)));
    hevc = p.getBoolean("hevc", true); tenBit = p.getBoolean("tenBit", false); cbr = p.getBoolean("cbr", true);
    quality = p.getInt("quality", 2); gop = p.getInt("gop", 0);
    aeKeepShutter = p.getBoolean("aeKeepShutter", true); lockWbOnRecord = p.getBoolean("lockWbOnRecord", true);
    if (!p.getBoolean("bitrateFix16", false)) { // 1.6: earlier versions defaulted to VBR, which many encoders undershoot
      cbr = true; quality = Math.max(quality, 2);
      p.edit().putBoolean("bitrateFix16", true).apply();
    } lookIndex = p.getInt("look", 0); detail = p.getInt("detail", 0);
    stabilization = p.getInt("stab", 1); antibanding = p.getInt("antibanding", 0); reel = p.getInt("reel", 1); clip = p.getInt("clip", 1);
    cameraLetter = (char) p.getInt("camLetter", 'A'); wavOn = p.getBoolean("wav", true); audioOn = p.getBoolean("audio", true);
    audioSource = p.getString("audioSource", "Camcorder"); audioDeviceId = p.getInt("audioDevice", 0);
    rawMode = p.getBoolean("rawMode", false); rawPack12 = p.getBoolean("raw12", true); rawCompress = p.getBoolean("rawLossless", true); rawKey = p.getString("rawKey", "");
    proresMode = p.getBoolean("prores", false) && !rawMode; proresProfile = p.getInt("proresProfile", 3);
    proresSize = new Size(p.getInt("proresW", 1920), p.getInt("proresH", 1080));
    if (p.contains("rawW")) rawSize = new Size(p.getInt("rawW", 0), p.getInt("rawH", 0));
    shutterAsAngle = p.getBoolean("angle", true); todTimecode = p.getBoolean("tod", false); recordRunBase = p.getLong("rr", 0);
    aeAuto = p.getBoolean("aeAuto", true); iso = p.getInt("iso", 400); shutterAngle = p.getFloat("shutterAngle", 180); shutterNs = p.getLong("shutterNs", 0);
    awbAuto = p.getBoolean("awbAuto", true); kelvin = p.getInt("kelvin", 5600); tint = p.getInt("tint", 0);
    manualFocus = p.getBoolean("mf", false); focus = p.getFloat("focus", 0); zoom = p.getFloat("zoom", 1);
  }

  private void applyMonitorPrefs() {
    SharedPreferences p = prefs;
    scopes.zebra = p.getBoolean("zebra", false); scopes.peaking = p.getBoolean("peak", false); scopes.falseColor = false;
    scopes.scopeMode = p.getInt("scope", 1); scopes.guide = p.getInt("guide", 0); scopes.grid = p.getBoolean("grid", true);
    scopes.level = p.getBoolean("level", true); scopes.center = p.getBoolean("center", false); scopes.zebraIre = p.getInt("zebraIre", 95);
    scopes.peakColor = p.getInt("peakColor", 0xffff3b30); scopes.peakSensitivity = p.getFloat("peakSens", 0.5f);
  }

  private void savePrefs() {
    prefs.edit().putInt("w", videoSize.getWidth()).putInt("h", videoSize.getHeight()).putLong("fps", Double.doubleToLongBits(fps))
        .putLong("conform", Double.doubleToLongBits(conformFps)).putBoolean("hevc", hevc).putBoolean("tenBit", tenBit).putBoolean("cbr", cbr)
        .putInt("quality", quality).putInt("gop", gop).putInt("look", lookIndex).putInt("detail", detail).putInt("stab", stabilization)
        .putInt("antibanding", antibanding).putInt("reel", reel).putInt("clip", clip).putInt("camLetter", cameraLetter).putBoolean("wav", wavOn)
        .putBoolean("audio", audioOn).putString("audioSource", audioSource).putInt("audioDevice", audioDeviceId).putBoolean("angle", shutterAsAngle)
        .putBoolean("tod", todTimecode).putLong("rr", recordRunBase).putBoolean("aeAuto", aeAuto).putInt("iso", iso).putFloat("shutterAngle", (float) shutterAngle)
        .putLong("shutterNs", shutterNs).putBoolean("awbAuto", awbAuto).putInt("kelvin", kelvin).putInt("tint", tint).putBoolean("mf", manualFocus)
        .putFloat("focus", focus).putFloat("zoom", zoom).putBoolean("aeKeepShutter", aeKeepShutter).putBoolean("lockWbOnRecord", lockWbOnRecord).putBoolean("rawMode", rawMode).putBoolean("raw12", rawPack12).putBoolean("rawLossless", rawCompress).putString("rawKey", rawKey)
        .putBoolean("prores", proresMode).putInt("proresProfile", proresProfile).putInt("proresW", proresSize.getWidth()).putInt("proresH", proresSize.getHeight())
        .putInt("rawW", rawSize == null ? 0 : rawSize.getWidth()).putInt("rawH", rawSize == null ? 0 : rawSize.getHeight())
        .putBoolean("zebra", scopes.zebra).putBoolean("peak", scopes.peaking).putInt("scope", scopes.scopeMode).putInt("guide", scopes.guide)
        .putBoolean("grid", scopes.grid).putBoolean("level", scopes.level).putBoolean("center", scopes.center).putInt("zebraIre", scopes.zebraIre)
        .putInt("peakColor", scopes.peakColor).putFloat("peakSens", scopes.peakSensitivity).apply();
  }

  // ================================================================== level sensor

  private final SensorEventListener levelListener = new SensorEventListener() {
    @Override public void onSensorChanged(SensorEvent e) {
      // In landscape the device's x axis points up; roll is the angle of gravity away from it.
      float x = e.values[0], y = e.values[1];
      if (displayDegrees() == 270) { x = -x; y = -y; }
      float roll = (float) Math.toDegrees(Math.atan2(y, x));
      rollFiltered += (roll - rollFiltered) * 0.15f;
      scopes.roll = rollFiltered;
      if (scopes.level) scopes.invalidate();
    }
    @Override public void onAccuracyChanged(Sensor s, int a) {}
  };

  // ================================================================== small UI parts

  void message(String text) {
    ui.post(() -> {
      messageView.setText(text); messageView.setVisibility(View.VISIBLE);
      messageView.removeCallbacks(hideMessage); messageView.postDelayed(hideMessage, 3200);
    });
  }

  private final Runnable hideMessage = () -> messageView.setVisibility(View.GONE);

  private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }

  private LinearLayout.LayoutParams wrap(int rightMargin) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1); lp.rightMargin = rightMargin; return lp; }

  private TextView label(String s, float sp, int color) {
    TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL);
    t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)); t.setLetterSpacing(0.06f); t.setSingleLine(); return t;
  }

  private TextView mono(String s, float sp, int color) { TextView t = label(s, sp, color); t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)); t.setLetterSpacing(0); return t; }

  private TextView status(String s, View.OnClickListener click) { TextView t = label(s, 12, TEXT); if (click != null) t.setOnClickListener(click); return t; }

  private Button chip(String text, boolean active, Runnable action) {
    Button b = new Button(this);
    b.setText(text); b.setAllCaps(false); b.setTextSize(12); b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
    b.setPadding(dp(12), 0, dp(12), 0); b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    b.setTextColor(active ? BG : TEXT);
    GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(6)); d.setColor(active ? ACCENT : 0xff22272c); d.setStroke(dp(1), active ? ACCENT : LINE);
    b.setBackground(d); b.setStateListAnimator(null);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  interface BoolGet { boolean get(); }
  interface BoolSet { void set(boolean v); }
  interface StrGet { String get(); }

  /** A monitoring toggle in the side column; re-styles itself from its getter. */
  final class Toggle {
    final Button button; final BoolGet get; final StrGet name;
    Toggle(Button b, BoolGet g, StrGet n) { button = b; get = g; name = n; }
    void sync() {
      boolean on = get.get(); button.setText(name.get()); button.setTextColor(on ? BG : TEXT);
      GradientDrawable d = new GradientDrawable(); d.setCornerRadius(dp(6)); d.setColor(on ? ACCENT : 0xff22272c); d.setStroke(dp(1), on ? ACCENT : LINE);
      button.setBackground(d);
    }
  }

  private void addToggle(LinearLayout parent, String name, BoolGet get, BoolSet set) {
    Button b = chip(name, false, () -> {});
    Toggle t = new Toggle(b, get, () -> name);
    b.setOnClickListener(v -> { set.set(!get.get()); t.sync(); savePrefs(); scopes.invalidate(); });
    toggles.add(t); t.sync();
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(30)); lp.bottomMargin = dp(5);
    parent.addView(b, lp);
  }

  private void addCycle(LinearLayout parent, StrGet name, Runnable next) {
    Button b = chip(name.get(), false, () -> {});
    Toggle t = new Toggle(b, () -> !name.get().equals("SCOPE") && !name.get().equals("GUIDE"), name);
    b.setOnClickListener(v -> { next.run(); t.sync(); savePrefs(); scopes.invalidate(); });
    toggles.add(t); t.sync();
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(30)); lp.bottomMargin = dp(5);
    parent.addView(b, lp);
  }

  /** A parameter readout in the bottom bar: caption over value; auto values shown dimmer with an "A". */
  final class Cell extends LinearLayout {
    final TextView caption, value;
    Cell(String cap, Runnable click) {
      super(CinemaActivity.this);
      setOrientation(VERTICAL); setGravity(Gravity.CENTER);
      caption = label(cap, 9, DIM); caption.setGravity(Gravity.CENTER);
      value = mono("", 15, TEXT); value.setGravity(Gravity.CENTER);
      addView(caption, new LayoutParams(-1, -2)); addView(value, new LayoutParams(-1, -2));
      setOnClickListener(v -> click.run());
      setBackground(null);
    }
    void set(String v, boolean auto) {
      value.setText(v); value.setTextColor(auto ? 0xffc9d0d6 : ACCENT);
      caption.setText(caption.getText().toString().replace(" · A", "") + (auto ? " · A" : ""));
    }
  }

  private Cell cell(String caption, Runnable click) { return new Cell(caption, click); }

  /** Stereo peak meter with hold and clip indicator, -60..0 dBFS. */
  final class Meter extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    Meter(Context c) { super(c); }
    @Override protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight(), pad = dp(8), barH = dp(6);
      p.setColor(DIM); p.setTextSize(dp(9)); p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
      c.drawText(audio.isRunning() ? "AUDIO " + String.format(Locale.US, "%+.0fdB", audio.gainDb) : "AUDIO OFF", pad, dp(14), p);
      if (audio.clipped) { p.setColor(REC); c.drawText("CLIP", w - pad - dp(22), dp(14), p); }
      float[] levels = {audio.peakL, audio.peakR}, holds = {audio.holdL, audio.holdR};
      for (int ch = 0; ch < 2; ch++) {
        float top = dp(21) + ch * (barH + dp(4)), left = pad, right = w - pad, span = right - left;
        p.setColor(0xff1c2024); c.drawRect(left, top, right, top + barH, p);
        double pos = audio.isRunning() ? CinemaMath.meterPosition(CinemaMath.dbfs(levels[ch])) : 0;
        float x = left + (float) (span * pos);
        float yellow = left + (float) (span * CinemaMath.meterPosition(-12)), red = left + (float) (span * CinemaMath.meterPosition(-3));
        p.setColor(OK); c.drawRect(left, top, Math.min(x, yellow), top + barH, p);
        if (x > yellow) { p.setColor(ACCENT); c.drawRect(yellow, top, Math.min(x, red), top + barH, p); }
        if (x > red) { p.setColor(REC); c.drawRect(red, top, x, top + barH, p); }
        float hx = left + (float) (span * CinemaMath.meterPosition(CinemaMath.dbfs(holds[ch])));
        if (audio.isRunning() && holds[ch] > 0.001f) { p.setColor(TEXT); c.drawRect(hx - dp(1), top, hx + dp(1), top + barH, p); }
      }
      p.setColor(0x66ffffff);
      for (int db : new int[] {-40, -20, -12, -6, 0}) { float tx = pad + (float) ((w - 2 * pad) * CinemaMath.meterPosition(db)); c.drawRect(tx - 0.5f, h - dp(9), tx + 0.5f, h - dp(6), p); }
    }
  }

  /** The record button: a ring with a red dot that becomes a rounded square while recording. */
  final class RecButton extends View {
    boolean recording; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    RecButton(Context c) { super(c); setContentDescription("Record"); }
    @Override protected void onDraw(Canvas c) {
      float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - dp(3);
      p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(3)); p.setColor(recording ? REC : TEXT); c.drawCircle(cx, cy, r, p);
      p.setStyle(Paint.Style.FILL); p.setColor(REC);
      if (recording) { float s = r * 0.45f; c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, dp(4), dp(4), p); }
      else c.drawCircle(cx, cy, r * 0.72f, p);
    }
  }
}

package ug.audiocity.cinema;

import java.util.List;

/** Host tests for the editor's timeline, transitions, gains, grade and resampler (no JSON: CI's android.jar stubs it). */
public class EditTests {
  static int passed;
  static void check(boolean ok, String what) { if (!ok) throw new AssertionError("FAILED: " + what); passed++; }
  static void near(double a, double b, double tol, String what) { check(Math.abs(a - b) <= tol, what + " (expected " + b + ", got " + a + ")"); }

  static EditModel.Clip clip(long seconds) {
    EditModel.Clip c = new EditModel.Clip();
    c.sourceDurationUs = seconds * 1_000_000; c.inUs = 0; c.outUs = c.sourceDurationUs; c.name = seconds + "s";
    return c;
  }

  public static void main(String[] args) {
    EditModel.Project p = new EditModel.Project();
    p.fadeIn = p.fadeOut = false;
    p.clips.add(clip(4)); p.clips.add(clip(6)); p.clips.add(clip(5));
    check(EditModel.duration(p) == 15_000_000, "cuts: durations add up");
    // dissolve into clip 1 overlaps it by 1 s
    p.clips.get(1).transition = 1; p.clips.get(1).transitionUs = 1_000_000;
    long[] s = EditModel.starts(p);
    check(s[0] == 0 && s[1] == 3_000_000 && s[2] == 9_000_000, "dissolve overlaps the previous clip");
    check(EditModel.duration(p) == 14_000_000, "a dissolve shortens the programme by its length");
    List<EditModel.Layer> mid = EditModel.layersAt(p, 3_500_000);
    check(mid.size() == 2 && mid.get(0).clip == 0 && mid.get(1).clip == 1, "two layers during a dissolve, outgoing underneath");
    near(mid.get(1).alpha, 0.5, 1e-9, "dissolve is half way at its middle");
    check(mid.get(1).sourceUs == 500_000 && mid.get(0).sourceUs == 3_500_000, "source times inside each clip");
    near(EditModel.clipGain(p, 0, 3_500_000) * EditModel.clipGain(p, 0, 3_500_000) + EditModel.clipGain(p, 1, 3_500_000) * EditModel.clipGain(p, 1, 3_500_000), 1, 1e-9, "equal-power audio crossfade");
    // dip to black into clip 2: fades down then up, no overlap
    p.clips.get(2).transition = 2; p.clips.get(2).transitionUs = 1_000_000;
    s = EditModel.starts(p);
    check(s[2] == 9_000_000, "a dip doesn't overlap");
    near(EditModel.layersAt(p, 8_750_000).get(0).brightness, 0.5, 1e-9, "dip: half way down");
    near(EditModel.layersAt(p, 9_000_000).get(0).brightness, 0, 1e-9, "dip: black at the cut");
    near(EditModel.layersAt(p, 9_250_000).get(0).brightness, 0.5, 1e-9, "dip: half way up");
    // transition length is limited by the clips
    p.clips.get(1).transitionUs = 30_000_000;
    check(EditModel.transitionUs(p, 1) == 2_000_000, "a transition is at most half the shorter clip");
    p.clips.get(1).transitionUs = 1_000_000;
    // project fades
    p.fadeIn = p.fadeOut = true; p.fadeUs = 1_000_000;
    near(EditModel.layersAt(p, 500_000).get(0).brightness, 0.5, 1e-9, "fade in from black");
    near(EditModel.layersAt(p, EditModel.duration(p) - 250_000).get(0).brightness, 0.25, 1e-9, "fade out to black");
    // split
    int before = p.clips.size();
    check(EditModel.split(p, 0, 2_000_000), "split inside a clip");
    check(p.clips.size() == before + 1 && p.clips.get(0).outUs == 2_000_000 && p.clips.get(1).inUs == 2_000_000 && p.clips.get(1).transition == 0, "split halves");
    check(!EditModel.split(p, 0, 50_000), "no split at the very edge");
    p.clips.get(1).volume = 0.5;
    check(p.clips.get(0).volume == 1, "split halves are independent copies");
    // music
    EditModel.Music m = new EditModel.Music();
    m.startUs = 0; m.sourceDurationUs = 60_000_000; m.volume = 0.8; m.fadeInUs = 1_000_000; m.fadeOutUs = 2_000_000; p.audio.add(m);
    near(EditModel.audioGain(p, m, 500_000), 0.4, 1e-9, "music fades in");
    near(EditModel.audioGain(p, m, 5_000_000), 0.8, 1e-9, "music at its volume");
    near(EditModel.audioGain(p, m, EditModel.duration(p) - 1_000_000), 0.4, 1e-9, "music fades out with the programme");
    check(EditModel.audioGain(p, m, EditModel.duration(p) + 1) == 0, "music stops with the programme");
    // titles
    EditModel.Title t = new EditModel.Title(); t.startUs = 1_000_000; t.durationUs = 3_000_000;
    near(EditModel.titleAlpha(t, 1_150_000), 0.5, 1e-9, "title fades in");
    near(EditModel.titleAlpha(t, 2_500_000), 1, 1e-9, "title fully on");
    check(EditModel.titleAlpha(t, 4_000_000) == 0 && EditModel.titleAlpha(t, 900_000) == 0, "title off outside its time");
    // grade
    EditModel.Grade g = new EditModel.Grade();
    double[] grey = {0.4, 0.4, 0.4};
    double[] o = EditModel.grade(grey, g);
    for (int i = 0; i < 3; i++) near(o[i], 0.4, 1e-9, "neutral grade changes nothing");
    g.exposure = 1; o = EditModel.grade(grey, g);
    near(Math.pow(o[0], 2.2) / Math.pow(0.4, 2.2), 2, 0.01, "+1 stop doubles linear light");
    g = new EditModel.Grade(); g.saturation = -1; o = EditModel.grade(new double[] {0.8, 0.3, 0.2}, g);
    check(Math.abs(o[0] - o[1]) < 1e-9 && Math.abs(o[1] - o[2]) < 1e-9, "saturation -1 is black and white");
    g = new EditModel.Grade(); g.temperature = 1; o = EditModel.grade(grey, g);
    check(o[0] > o[1] && o[1] > o[2], "warmer temperature: more red, less blue");
    g = new EditModel.Grade(); g.contrast = 0.5; o = EditModel.grade(new double[] {0.2, 0.5, 0.8}, g);
    check(o[0] < 0.2 && Math.abs(o[1] - 0.5) < 1e-9 && o[2] > 0.8, "contrast pivots around mid grey");
    for (int look = 0; look < EditModel.FILM_LOOKS.length; look++) {
      g = new EditModel.Grade(); g.film = look;
      for (double v = 0; v <= 1; v += 0.1) for (double x : EditModel.grade(new double[] {v, v * 0.7, 1 - v}, g)) check(x >= 0 && x <= 1, EditModel.FILM_LOOKS[look] + " stays in range");
    }
    g = new EditModel.Grade(); g.film = 5; o = EditModel.grade(new double[] {0.9, 0.2, 0.1}, g);
    check(o[0] == o[1] && o[1] == o[2], "Mono is grey");
    // resampler: 44.1 kHz mono sine to 48 kHz stereo keeps frequency and level, across buffer boundaries
    EditModel.Resampler r = new EditModel.Resampler(44_100, 1);
    float[] out = new float[48_000 * 2], tmp = new float[4096 * 2];
    int total = 0;
    for (int blk = 0; blk < 44; blk++) {
      float[] in = new float[1002];
      for (int i = 0; i < 1002; i++) in[i] = (float) Math.sin(2 * Math.PI * 1000 * (blk * 1002 + i) / 44_100.0);
      int n = r.process(in, 1002, tmp, 4096);
      System.arraycopy(tmp, 0, out, total * 2, Math.min(n, out.length / 2 - total) * 2);
      total += n;
    }
    near(total, 44 * 1002 * 48_000 / 44_100.0, 3, "resampled length");
    double err = 0;
    for (int i = 10; i < total - 10; i++) err = Math.max(err, Math.abs(out[i * 2] - Math.sin(2 * Math.PI * 1000 * i / 48_000.0)));
    check(err < 0.02, "resampled sine matches (max error " + err + ")");
    for (int i = 0; i < total; i++) check(out[i * 2] == out[i * 2 + 1], "mono goes to both channels");
    cubeTests();
    lutPackTests();
    logDetectTests();
    autoGradeTests();
    toolTests();
    layerTests();
    audioTrackTests();
    trackStateTests();
    editOperationTests();
    compositingTests();
    gradingTests();
    audioDspTests();
    System.out.println("EditTests: " + passed + " checks passed");
  }


  static CubeLut cube(String text) throws java.io.IOException { return CubeLut.parse(new java.io.StringReader(text)); }

  /** Every .cube shipped in assets/luts must load, be in range and produce a usable picture. */
  /** Log footage shown without conversion is recognised; normal and dark/flat frames are not. */
  /** Auto colour: a dark, warm-cast frame comes out neutral and at normal brightness; a good frame is left alone. */
  /** Speed changes the clip's length and maps timeline time to source time; split keeps both halves in sync. */
  /** Layer tracks: drawn above the main track in track order, extend the timeline, snap, split, keep their sound. */
  /** Audio tracks: trimmed and muted sound clips, splitting, snapping to sound edges, the voice-over WAV header. */
  /** Track headers: hidden layers aren't drawn, muted/soloed audio tracks, muted camera sound; markers. */
  /** Ripple trims, roll, slip, slide and in-place trims keep the programme consistent and stay inside the media. */
  /** Blend modes, the clip quad (fit, PiP, rotation, flips) and programme / export sizes for each aspect ratio. */
  /** Wheels, levels, vibrance, pivot, curves and the HSL qualifier. */
  /** Filters, dynamics, ducking, and loudness / true peak against known reference signals. */
  static void audioDspTests() {
    AudioDsp.Biquad lp = new AudioDsp.Biquad().set(AudioDsp.lowpass(1000, 0.707));
    near(lp.responseDb(1000), -3.01, 0.05, "low-pass -3 dB at its frequency");
    check(Math.abs(lp.responseDb(100)) < 0.1 && lp.responseDb(10_000) < -35, "low-pass passes lows, cuts highs");
    AudioDsp.Biquad hp = new AudioDsp.Biquad().set(AudioDsp.highpass(100, 0.707));
    check(hp.responseDb(20) < -25 && Math.abs(hp.responseDb(2000)) < 0.1, "high-pass cuts rumble");
    near(new AudioDsp.Biquad().set(AudioDsp.peaking(3000, 1, 6)).responseDb(3000), 6, 0.01, "peaking EQ +6 dB at its centre");
    AudioDsp.Biquad ls = new AudioDsp.Biquad().set(AudioDsp.lowShelf(120, 6));
    check(Math.abs(ls.responseDb(20) - 6) < 0.3 && Math.abs(ls.responseDb(5000)) < 0.1, "low shelf");
    AudioDsp.Biquad hs = new AudioDsp.Biquad().set(AudioDsp.highShelf(8000, -6));
    check(Math.abs(hs.responseDb(20000) + 6) < 0.6 && Math.abs(hs.responseDb(200)) < 0.1, "high shelf");
    // compressor: a -6 dBFS tone, threshold -18, 4:1 -> about -15 dBFS once settled
    AudioDsp.Compressor comp = new AudioDsp.Compressor(); comp.threshold = -18; comp.ratio = 4;
    float[] tone = sine(1000, 0.5, 48_000);
    comp.process(tone, 48_000);
    double outPeak = 0; for (int i = 24_000 * 2; i < tone.length; i++) outPeak = Math.max(outPeak, Math.abs(tone[i]));
    near(AudioDsp.gainToDb(outPeak), -15, 0.6, "compressor 4:1 above -18 dB");
    // gate: quiet hiss turned down, speech-level sound kept
    AudioDsp.Gate gate = new AudioDsp.Gate(); gate.threshold = -45;
    float[] hiss = sine(3000, 0.001, 48_000), loud = sine(300, 0.3, 24_000);
    gate.process(hiss, 48_000); // the gate closes over its release time (120 ms after a 60 ms hold)
    double hp2 = 0; for (int i = 43_200 * 2; i < hiss.length; i++) hp2 = Math.max(hp2, Math.abs(hiss[i]));
    check(AudioDsp.gainToDb(hp2) < -88, "gate turns hiss down by its 30 dB range: " + AudioDsp.gainToDb(hp2));
    AudioDsp.Gate gate2 = new AudioDsp.Gate(); gate2.threshold = -45;
    gate2.process(loud, 24_000);
    double lp2 = 0; for (int i = 12_000 * 2; i < loud.length; i++) lp2 = Math.max(lp2, Math.abs(loud[i]));
    near(lp2, 0.3, 0.01, "gate keeps speech");
    // ducking: music goes down by the depth while the voice talks, then back up
    AudioDsp.Ducker d = new AudioDsp.Ducker(); d.depthDb = -12;
    float[] voice = sine(200, 0.2, 48_000), music = sine(500, 0.5, 48_000);
    d.process(voice, music, 48_000);
    near(d.currentDb(), -12, 0.2, "music ducked under the voice");
    float[] silence = new float[192_000 * 2], music2 = sine(500, 0.5, 192_000);
    d.process(silence, music2, 192_000); // 4 s: the voice's tail fades (~0.4 s), then the music returns over its 600 ms release
    check(d.currentDb() > -0.5, "music comes back after the voice stops");
    // loudness: 1 kHz at -20 dBFS (peak 0.1) in both channels reads -20.0 LUFS (BS.1770 reference)
    AudioDsp.Loudness lu = new AudioDsp.Loudness();
    float[] ref = sine(1000, 0.1, 48_000 * 10);
    lu.process(ref, 48_000 * 10);
    near(lu.integrated(), -20.0, 0.1, "BS.1770 1 kHz reference");
    near(lu.momentary, -20.0, 0.1, "momentary loudness");
    near(lu.truePeakDb(), -20, 0.1, "true peak of a plain tone");
    // true peak between samples: a 12 kHz tone sampled at 45 degrees has sample peaks of 0.707 but a true peak of 1
    float[] between = new float[48_000 * 2];
    for (int i = 0; i < 48_000; i++) { float v = (float) Math.sin(2 * Math.PI * 12_000 * i / 48_000 + Math.PI / 4); between[i * 2] = v; between[i * 2 + 1] = v; }
    AudioDsp.Loudness lu2 = new AudioDsp.Loudness();
    lu2.process(between, 48_000);
    check(lu2.truePeakDb() > -0.5 && lu2.truePeakDb() < 0.5, "true peak finds the peak between samples: " + lu2.truePeakDb());
    check(new AudioDsp.Loudness().integrated() == -120, "silence has no loudness");
    // the voice preset chain runs and stays finite
    AudioDsp.Chain ch = new AudioDsp.Chain();
    float[] v2 = sine(250, 0.3, 48_000);
    ch.process(AudioDsp.Fx.voicePreset(), v2, 48_000);
    boolean finite = true; for (float x : v2) if (Float.isNaN(x) || Float.isInfinite(x)) finite = false;
    check(finite, "voice chain output is finite");
    float[] untouched = sine(250, 0.3, 4800), copy = untouched.clone();
    new AudioDsp.Chain().process(new AudioDsp.Fx(), untouched, 4800);
    check(java.util.Arrays.equals(untouched, copy), "a flat chain leaves the sound alone");
  }

  static float[] sine(double hz, double amp, int frames) {
    float[] b = new float[frames * 2];
    for (int i = 0; i < frames; i++) { float v = (float) (amp * Math.sin(2 * Math.PI * hz * i / 48_000)); b[i * 2] = v; b[i * 2 + 1] = v; }
    return b;
  }

  static void gradingTests() {
    double[] grey = {0.5, 0.5, 0.5}, dark = {0.1, 0.1, 0.1}, light = {0.9, 0.9, 0.9};
    EditModel.Grade g = new EditModel.Grade();
    for (double[] c : new double[][] {grey, dark, light, {0.8, 0.3, 0.2}}) {
      double[] o = EditModel.grade(c, g);
      for (int i = 0; i < 3; i++) near(o[i], c[i], 0.003, "neutral grade leaves the picture");
    }
    check(g.neutral(), "new grade is neutral");
    // lift raises shadows more than highlights; gain the opposite; gamma the mid tones
    g.liftM = 0.5;
    double dl = EditModel.grade(dark, g)[1] - 0.1, ll = EditModel.grade(light, g)[1] - 0.9;
    check(dl > 0.05 && dl > ll * 3, "lift works on the shadows");
    g.liftM = 0; g.gainM = -0.5;
    double dg = 0.1 - EditModel.grade(dark, g)[1], lg = 0.9 - EditModel.grade(light, g)[1];
    check(lg > dg * 3, "gain works on the highlights");
    g.gainM = 0; g.gammaM = 0.5;
    double mid = EditModel.grade(grey, g)[1];
    check(mid > 0.55 && Math.abs(EditModel.grade(new double[] {1, 1, 1}, g)[1] - 1) < 1e-9 && EditModel.grade(new double[] {0, 0, 0}, g)[1] < 1e-9, "gamma lifts mids, keeps black and white");
    g.gammaM = 0;
    // a coloured wheel tints without changing brightness
    g.liftH = 30; g.liftA = 1; // warm shadows
    double[] w = EditModel.grade(dark, g);
    check(w[0] > w[2] + 0.03, "warm lift tints shadows orange");
    double[] wv = EditModel.wheel(200, 1, 0, 1);
    near(0.2126 * wv[0] + 0.7152 * wv[1] + 0.0722 * wv[2], 0, 1e-12, "wheel colour pushes keep brightness");
    g = new EditModel.Grade();
    // blacks / whites
    g.blacks = -1;
    check(EditModel.grade(new double[] {0.05, 0.05, 0.05}, g)[0] < 0.01, "crushing blacks");
    g.blacks = 0; g.whites = 1;
    check(EditModel.grade(new double[] {0.92, 0.92, 0.92}, g)[0] > 0.99, "pushing whites");
    g = new EditModel.Grade();
    // vibrance boosts muted colours more than vivid ones
    g.vibrance = 1;
    double[] muted = {0.55, 0.5, 0.45}, vivid = {0.9, 0.2, 0.1};
    double mg = spread(EditModel.grade(muted, g)) / spread(muted), vg = spread(EditModel.grade(vivid, g)) / spread(vivid);
    check(mg > vg + 0.3, "vibrance favours muted colours: " + mg + " vs " + vg);
    g = new EditModel.Grade();
    // contrast pivot: the pivot value doesn't move
    g.contrast = 0.5; g.pivot = 0.3;
    near(EditModel.grade(new double[] {0.3, 0.3, 0.3}, g)[0], 0.3, 0.003, "contrast pivots around its pivot");
    g = new EditModel.Grade();
    // curves: smooth, monotone, through the points, ends fixed
    double[] sCurve = {0.25, 0.18, 0.75, 0.82};
    near(EditModel.curve(sCurve, 0.25), 0.18, 1e-9, "curve through its points");
    check(EditModel.curve(sCurve, 0) == 0 && EditModel.curve(sCurve, 1) == 1 && EditModel.curve(new double[0], 0.37) == 0.37, "curve ends and straight default");
    double prev = -1;
    for (double x = 0; x <= 1.0001; x += 0.01) { double y = EditModel.curve(sCurve, x); check(y >= prev - 1e-12, "curve never goes down"); prev = y; }
    g.curveB = new double[] {0.5, 0.6};
    double[] cb = EditModel.grade(grey, g);
    check(cb[2] > 0.58 && Math.abs(cb[0] - 0.5) < 0.004, "blue curve only changes blue");
    byte[] table = EditModel.curveTable(g);
    check((table[128 * 4 + 2] & 255) > 150 && Math.abs((table[128 * 4] & 255) - 128) <= 1, "baked curve table");
    g = new EditModel.Grade();
    // HSL: change orange (skin) only
    g.hslOn = true; g.hslHue = 25; g.hslWidth = 40; g.hslSat = -1;
    double[] skin = EditModel.grade(new double[] {0.8, 0.55, 0.4}, g), sky = EditModel.grade(new double[] {0.3, 0.5, 0.9}, g), gry = EditModel.grade(grey, g);
    check(spread(skin) < 0.05 && Math.abs(sky[2] - 0.9) < 0.01 && spread(gry) < 1e-6, "HSL desaturates only the chosen hue");
    check(EditModel.hslWeight(g, 25, 0.5) > 0.99 && EditModel.hslWeight(g, 200, 0.5) == 0 && EditModel.hslWeight(g, 25, 0.0) == 0, "qualifier weight");
    check(EditModel.hslWeight(g, 365 % 360 + 20, 0.5) > 0.99, "hue distance wraps around 360");
    g.hslShow = true;
    check(EditModel.grade(new double[] {0.8, 0.55, 0.4}, g)[0] > 0.9 && EditModel.grade(new double[] {0.3, 0.5, 0.9}, g)[0] < 0.01, "show selection: white inside, black outside");
    check(!new EditModel.Grade().copy().hslOn && g.copy().hslShow, "copies keep HSL settings");
  }

  static double spread(double[] c) { return Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2])); }

  static void compositingTests() {
    near(EditModel.blend(0, 0.3, 0.8), 0.3, 1e-12, "normal");
    near(EditModel.blend(1, 0.5, 0.5), 0.25, 1e-12, "multiply");
    near(EditModel.blend(2, 0.5, 0.5), 0.75, 1e-12, "screen");
    check(EditModel.blend(1, 1, 0.4) == 0.4 && EditModel.blend(2, 0, 0.4) == 0.4, "white multiply and black screen leave the picture");
    near(EditModel.blend(3, 0.5, 0.3), 0.3, 1e-12, "overlay with grey leaves it");
    near(EditModel.blend(4, 0.5, 0.3), 0.3, 1e-12, "soft light with grey leaves it");
    check(EditModel.blend(6, 0.7, 0.6) == 1 && Math.abs(EditModel.blend(7, 0.2, 0.7) - 0.5) < 1e-12 && EditModel.blend(8, 0.2, 0.7) == 0.2 && EditModel.blend(9, 0.2, 0.7) == 0.7, "add, difference, darken, lighten");
    for (int m = 0; m < EditModel.BLEND_MODES.length; m++) for (double sv = 0; sv <= 1; sv += 0.25) for (double dv = 0; dv <= 1; dv += 0.25) {
      double v = EditModel.blend(m, sv, dv);
      check(v >= -1e-12 && v <= 1 + 1e-12, EditModel.BLEND_MODES[m] + " stays in range");
    }
    EditModel.Clip c = new EditModel.Clip(); c.width = 1920; c.height = 1080;
    float[] q = EditModel.clipQuad(c, 1920, 1080);
    check(near(q[0], -1) && near(q[1], -1) && near(q[12], 1) && near(q[13], 1), "same shape fills the frame");
    c.zoom = 0.5; c.panX = 1; c.panY = 1; // picture-in-picture in the bottom-right corner
    q = EditModel.clipQuad(c, 1920, 1080);
    check(near(q[0], 0) && near(q[4], 1) && near(q[1], -1) && near(q[9], 0), "half-size picture-in-picture, bottom right");
    c.zoom = 1; c.panX = c.panY = 0; c.rotationDeg = 90;
    q = EditModel.clipQuad(c, 1080, 1080); // square frame: a 90-degree turn of a fitted 16:9 picture
    check(Math.abs(q[0]) < 1.01 && near(Math.abs(q[0]), 0.5625f) && near(Math.abs(q[1]), 1), "rotation keeps the picture's shape");
    c.rotationDeg = 0; c.flipV = true;
    q = EditModel.clipQuad(c, 1920, 1080);
    check(q[3] == 1 && q[15] == 0, "vertical flip swaps top and bottom");
    // programme sizes
    EditModel.Project p = new EditModel.Project();
    EditModel.Clip k = new EditModel.Clip(); k.width = 3840; k.height = 2160; p.clips.add(k);
    int[][] want = {{3840, 2160}, {3840, 2160}, {2160, 3840}, {2160, 2160}, {2160, 2700}, {3840, 1606}};
    for (int a = 0; a < want.length; a++) { p.aspect = a; int[] o = EditModel.outputSize(p); check(o[0] == want[a][0] && o[1] == want[a][1], EditModel.Project.ASPECTS[a] + " size " + o[0] + "x" + o[1]); }
    p.aspect = 2;
    int[] e = EditModel.exportSize(p, 1080);
    check(e[0] == 1080 && e[1] == 1920, "9:16 export at 1080 is 1080x1920");
    p.aspect = 5;
    e = EditModel.exportSize(p, 1080);
    check(e[0] == 2580 && e[1] == 1080, "2.39:1 at 1080 is 2580x1080");
  }

  static boolean near(float a, float b) { return Math.abs(a - b) < 1e-4; }

  static void editOperationTests() {
    EditModel.Project p = new EditModel.Project();
    EditModel.Clip a = clip(0, 4_000_000, 10_000_000), b = clip(2_000_000, 6_000_000, 10_000_000), c = clip(1_000_000, 5_000_000, 10_000_000);
    p.clips.add(a); p.clips.add(b); p.clips.add(c);
    long total = EditModel.duration(p);
    check(total == 12_000_000, "three 4 s clips");
    // ripple trim
    check(EditModel.trimStart(b, 1_000_000) == 1_000_000 && b.inUs == 3_000_000 && EditModel.duration(p) == 11_000_000, "ripple trim start shortens the programme");
    check(EditModel.trimStart(b, -9_000_000) == -3_000_000 && b.inUs == 0, "trim start stops at the start of the file");
    check(EditModel.trimEnd(a, 2_000_000) == 2_000_000 && a.outUs == 6_000_000, "trim end lengthens");
    check(EditModel.trimEnd(a, 20_000_000) == 4_000_000 && a.outUs == 10_000_000, "trim end stops at the end of the file");
    check(EditModel.trimEnd(a, -20_000_000) == -(10_000_000 - EditModel.MIN_US) && a.durationUs() == EditModel.MIN_US, "never shorter than the minimum");
    a.outUs = 4_000_000; b.inUs = 2_000_000;
    total = EditModel.duration(p);
    // roll keeps the length
    long r = EditModel.roll(p, 1, 1_000_000);
    check(r == 1_000_000 && a.outUs == 5_000_000 && b.inUs == 3_000_000 && EditModel.duration(p) == total, "roll moves the cut, same length");
    check(EditModel.roll(p, 1, -9_000_000) == -3_000_000 && b.inUs == 0 && a.outUs == 2_000_000 && EditModel.duration(p) == total, "roll stops where the next clip's file starts");
    a.outUs = 4_000_000; b.inUs = 2_000_000;
    // slip keeps place and length
    long[] before = EditModel.starts(p);
    check(EditModel.slip(b, 1_000_000) == 1_000_000 && b.inUs == 3_000_000 && b.outUs == 7_000_000 && b.durationUs() == 4_000_000, "slip moves the content");
    check(java.util.Arrays.equals(before, EditModel.starts(p)) && EditModel.duration(p) == total, "slip keeps the timeline");
    check(EditModel.slip(b, 9_000_000) == 3_000_000 && b.outUs == 10_000_000, "slip stops at the end of the file");
    b.inUs = 2_000_000; b.outUs = 6_000_000;
    // slide moves the clip between neighbours
    long s1 = EditModel.starts(p)[1];
    check(EditModel.slide(p, 1, 1_000_000) == 1_000_000 && EditModel.starts(p)[1] == s1 + 1_000_000 && b.durationUs() == 4_000_000 && EditModel.duration(p) == total, "slide");
    check(EditModel.slide(p, 0, 1_000_000) == 0 && EditModel.slide(p, 2, 1_000_000) == 0, "first and last clips can't slide");
    // speed: a 2x clip trims twice as much source per timeline second
    EditModel.Clip f = clip(0, 8_000_000, 8_000_000); f.speed = 2;
    check(EditModel.trimStart(f, 1_000_000) == 1_000_000 && f.inUs == 2_000_000, "trim at 2x");
    // layers and sounds trim in place
    EditModel.Clip l = clip(1_000_000, 5_000_000, 10_000_000); l.startUs = 3_000_000;
    long end = l.startUs + l.durationUs();
    check(EditModel.trimLayerStart(l, 500_000) == 500_000 && l.startUs == 3_500_000 && l.startUs + l.durationUs() == end, "layer start trim keeps its end");
    EditModel.Music m = new EditModel.Music(); m.sourceDurationUs = 10_000_000; m.startUs = 2_000_000; m.inUs = 1_000_000;
    long mEnd = m.startUs + m.playUs();
    check(EditModel.trimAudioStart(m, 1_000_000) == 1_000_000 && m.startUs == 3_000_000 && m.inUs == 2_000_000 && m.startUs + m.playUs() == mEnd, "sound start trim keeps its end");
    check(EditModel.trimAudioEnd(m, -2_000_000) == -2_000_000 && m.playUs() == 6_000_000, "sound end trim");
    check(EditModel.trimAudioStart(m, -9_000_000) == -2_000_000 && m.inUs == 0, "sound start trim stops at the file start");
  }

  static EditModel.Clip clip(long in, long out, long src) {
    EditModel.Clip c = new EditModel.Clip(); c.inUs = in; c.outUs = out; c.sourceDurationUs = src; c.transition = 0; return c;
  }

  static void trackStateTests() {
    EditModel.Project p = new EditModel.Project();
    p.fadeIn = p.fadeOut = false;
    EditModel.Clip v = new EditModel.Clip();
    v.outUs = 10_000_000; v.sourceDurationUs = 10_000_000;
    p.clips.add(v);
    EditModel.Clip l1 = new EditModel.Clip(); l1.outUs = 5_000_000; l1.sourceDurationUs = 5_000_000; l1.track = 1;
    EditModel.Clip l2 = l1.copy(); l2.id = EditModel.NEXT_ID.incrementAndGet(); l2.track = 2;
    p.overlays.add(l1); p.overlays.add(l2);
    check(EditModel.layersAt(p, 1_000_000).size() == 3, "all layers shown");
    p.hiddenLayers |= 1 << 2;
    java.util.List<EditModel.Layer> ls = EditModel.layersAt(p, 1_000_000);
    check(ls.size() == 2 && ls.get(1).ref == l1, "hidden layer 2 is not drawn");
    EditModel.Music[] ms = new EditModel.Music[3];
    for (int k = 0; k < 3; k++) { ms[k] = new EditModel.Music(); ms[k].track = k; ms[k].sourceDurationUs = 10_000_000; ms[k].volume = 1; ms[k].fadeInUs = ms[k].fadeOutUs = 0; p.audio.add(ms[k]); }
    check(EditModel.audioGain(p, ms[0], 1_000_000) == 1 && EditModel.audioGain(p, ms[1], 1_000_000) == 1, "all tracks heard");
    p.audioMute = 1 << 1;
    check(EditModel.audioGain(p, ms[1], 1_000_000) == 0 && EditModel.audioGain(p, ms[0], 1_000_000) == 1, "muted VOICE track");
    p.audioMute = 0; p.audioSolo = 1 << 2;
    check(EditModel.audioGain(p, ms[2], 1_000_000) == 1 && EditModel.audioGain(p, ms[0], 1_000_000) == 0 && EditModel.audioGain(p, ms[1], 1_000_000) == 0, "solo FX: only FX heard");
    p.audioSolo = 0;
    v.hasAudio = true;
    check(EditModel.clipGain(p, 0, 1_000_000) > 0, "camera sound on");
    p.mainMuted = true;
    check(EditModel.clipGain(p, 0, 1_000_000) == 0, "camera sound muted from the header");
    // markers
    check(EditModel.toggleMarker(p, 3_000_000) && EditModel.toggleMarker(p, 1_000_000) && p.markers.get(0) == 1_000_000, "markers added, sorted");
    check(!EditModel.toggleMarker(p, 3_050_000) && p.markers.size() == 1, "tapping near a marker removes it");
    EditModel.toggleMarker(p, 6_000_000);
    check(EditModel.nextMarker(p, 2_000_000, 1) == 6_000_000 && EditModel.nextMarker(p, 2_000_000, -1) == 1_000_000 && EditModel.nextMarker(p, 7_000_000, 1) == -1, "next and previous marker");
    boolean snapsToMarker = false; for (long q : EditModel.snapPoints(p, null)) if (q == 6_000_000) snapsToMarker = true;
    check(snapsToMarker, "markers are snap points");
    EditModel.Project q = EditModel.snapshot(p);
    check(q.markers.size() == 2 && q.mainMuted && q.hiddenLayers == (1 << 2), "states in the preview copy");
    // the envelope used for waveforms
    check(MediaCache.envelopeOf(new float[] {0.1f, -0.7f, 0.3f, 0.2f}, 2) == 0.7f && MediaCache.gridTime(740_000) == 500_000 && MediaCache.gridTime(760_000) == 1_000_000, "waveform peak and thumbnail grid");
  }

  static void audioTrackTests() {
    EditModel.Project p = new EditModel.Project();
    p.fadeIn = p.fadeOut = false;
    EditModel.Clip v = new EditModel.Clip();
    v.outUs = 30_000_000; v.sourceDurationUs = 30_000_000;
    p.clips.add(v);
    EditModel.Music m = new EditModel.Music();
    m.sourceDurationUs = 20_000_000; m.inUs = 5_000_000; m.startUs = 2_000_000; m.volume = 1; m.fadeInUs = 0; m.fadeOutUs = 0;
    p.audio.add(m);
    check(m.playUs() == 15_000_000, "plays from the trim to the end of the file");
    check(EditModel.audioGain(p, m, 1_000_000) == 0 && EditModel.audioGain(p, m, 10_000_000) == 1 && EditModel.audioGain(p, m, 17_500_000) == 0, "sound only while it plays");
    m.lengthUs = 4_000_000;
    check(m.playUs() == 4_000_000 && EditModel.audioGain(p, m, 7_000_000) == 0, "length trims the end");
    m.muted = true;
    check(EditModel.audioGain(p, m, 3_000_000) == 0, "muted");
    m.muted = false; m.lengthUs = 0;
    EditModel.Music b = EditModel.splitAudio(p, m, 6_000_000);
    check(b != null && m.playUs() == 4_000_000 && b.startUs == 6_000_000 && b.inUs == 9_000_000 && b.playUs() == 11_000_000, "split sound");
    check(p.audio.size() == 2 && p.audio.get(1) == b, "second half follows");
    check(EditModel.splitAudio(p, m, 20_000_000) == null, "split outside refused");
    long[] pts = EditModel.snapPoints(p, null);
    boolean edge = false; for (long q : pts) if (q == 17_000_000) edge = true;
    check(edge, "sound edges are snap points");
    EditModel.Music c = b.copy();
    check(c.inUs == b.inUs && c.track == b.track && c.id != b.id, "sound copies keep settings, new identity");
    byte[] h = EditModel.wavHeader16(96_000, 48_000, 1);
    java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    check(h.length == 44 && bb.getInt(4) == 36 + 96_000 && bb.getInt(24) == 48_000 && bb.getShort(34) == 16 && bb.getInt(40) == 96_000, "voice-over WAV header");
  }

  static void layerTests() {
    EditModel.Project p = new EditModel.Project();
    p.fadeIn = p.fadeOut = false;
    EditModel.Clip main = new EditModel.Clip();
    main.inUs = 0; main.outUs = 10_000_000; main.sourceDurationUs = 10_000_000;
    p.clips.add(main);
    EditModel.Clip b2 = new EditModel.Clip(), b1 = new EditModel.Clip();
    for (EditModel.Clip k : new EditModel.Clip[] {b2, b1}) { k.inUs = 2_000_000; k.outUs = 6_000_000; k.sourceDurationUs = 8_000_000; }
    b2.track = 2; b2.startUs = 3_000_000; b2.opacity = 0.5;
    b1.track = 1; b1.startUs = 4_000_000;
    p.overlays.add(b2); p.overlays.add(b1);
    java.util.List<EditModel.Layer> ls = EditModel.layersAt(p, 5_000_000);
    check(ls.size() == 3 && ls.get(0).ref == main && ls.get(1).ref == b1 && ls.get(2).ref == b2, "main, then layer 1, then layer 2");
    check(ls.get(1).sourceUs == 3_000_000 && ls.get(2).sourceUs == 4_000_000, "layer source times");
    check(Math.abs(ls.get(2).alpha - 0.5) < 1e-9 && ls.get(1).clip == -1, "layer opacity and marker");
    check(EditModel.layersAt(p, 2_000_000).size() == 1, "layers only while they run");
    // a layer past the end extends the timeline
    b1.startUs = 9_000_000;
    check(EditModel.duration(p) == 13_000_000, "layer extends the timeline");
    // snapping: within tolerance to the nearest edge, otherwise unchanged
    long[] pts = EditModel.snapPoints(p, b1, 5_500_000);
    check(EditModel.snap(9_950_000, pts, 100_000) == 10_000_000, "snaps to the main clip's end");
    check(EditModel.snap(5_430_000, pts, 100_000) == 5_500_000, "snaps to the playhead");
    check(EditModel.snap(8_000_000, pts, 100_000) == 8_000_000, "no snap when nothing is near");
    boolean ownEdge = false; for (long q : pts) if (q == 9_000_000) ownEdge = true;
    check(!ownEdge, "an item never snaps to its own edge");
    // split a layer clip
    EditModel.Clip second = EditModel.splitOverlay(p, b2, 4_000_000);
    check(second != null && b2.outUs == 3_000_000 && second.inUs == 3_000_000 && second.startUs == 4_000_000 && second.track == 2, "layer split");
    check(b2.durationUs() + second.durationUs() == 4_000_000, "layer split keeps its length");
    check(EditModel.splitOverlay(p, b2, 9_000_000) == null, "split outside the clip refused");
    // copies carry layer settings; the project copy for the preview thread includes layers
    EditModel.Project q = EditModel.snapshot(p);
    check(q.overlays.size() == 3 && q.overlays.get(0).track == 2 && q.overlays.get(0).opacity == 0.5, "layers in the preview copy");
    // project fades
    p.fadeIn = true; p.fadeUs = 1_000_000;
    check(Math.abs(EditModel.projectFade(p, 500_000) - 0.5) < 1e-9 && EditModel.projectFade(p, 2_000_000) == 1, "project fade");
  }

  static void toolTests() {
    EditModel.Project p = new EditModel.Project();
    EditModel.Clip c = new EditModel.Clip();
    c.inUs = 1_000_000; c.outUs = 9_000_000; c.sourceDurationUs = 10_000_000;
    p.clips.add(c);
    check(c.durationUs() == 8_000_000, "1x length");
    c.speed = 2;
    check(c.durationUs() == 4_000_000 && EditModel.duration(p) == 4_000_000, "2x halves the length");
    check(c.sourceAt(1_000_000) == 3_000_000, "2x: 1 s in shows source 3 s");
    check(EditModel.layersAt(p, 2_000_000).get(0).sourceUs == 5_000_000, "layer at 2x");
    c.speed = 0.5;
    check(c.durationUs() == 16_000_000, "0.5x doubles the length");
    check(EditModel.split(p, 0, 4_000_000), "split slow clip");
    EditModel.Clip a = p.clips.get(0), b = p.clips.get(1);
    check(a.outUs == 3_000_000 && b.inUs == 3_000_000 && b.speed == 0.5, "split at 0.5x lands on source 3 s");
    check(a.durationUs() + b.durationUs() == 16_000_000, "split keeps the total length");
    try {
      EditModel.Clip k = EditModel.Clip.from(b.json());
      check(k.speed == 0.5, "speed saved");
    } catch (Exception e) { check(true, "json is stubbed on the CI android.jar"); }
    EditModel.Clip d = b.copy();
    d.zoom = 2; d.turn = 90; d.flip = true; d.fill = true;
    EditModel.Clip e = d.copy();
    check(e.zoom == 2 && e.turn == 90 && e.flip && e.fill, "framing copied");
  }

  static void autoGradeTests() {
    int w = 80, h = 45;
    java.util.Random rnd = new java.util.Random(3);
    double[][] scene = new double[w * h][];
    for (int i = 0; i < scene.length; i++) {
      double x = (i % w) / (double) w, yy = (i / w) / (double) h;
      if (x < 0.35) scene[i] = new double[] {0.05 + 0.6 * yy, 0.05 + 0.6 * yy, 0.05 + 0.6 * yy}; // grey wall
      else if (x < 0.7) scene[i] = new double[] {0.04, 0.10 + 0.1 * yy, 0.03};                 // foliage (saturated)
      else scene[i] = new double[] {0.25 + 0.2 * rnd.nextDouble(), 0.18, 0.12};                 // soil
    }
    int[] good = new int[scene.length], bad = new int[scene.length];
    double[] cast = {1.18, 1.0, 0.82}; // warm cast (white balance ~1000K off)
    for (int i = 0; i < scene.length; i++) {
      good[i] = px(scene[i], new double[] {1, 1, 1}, 1);
      bad[i] = px(scene[i], cast, 0.3); // and ~1.7 stops dark
    }
    double[] a = EditModel.autoGrade(bad);
    check(a[0] > 1.0 && a[0] <= 2, "auto brightens a dark picture: " + a[0]);
    check(a[2] < -0.3, "auto cools a warm cast: " + a[2]);
    EditModel.Grade g = new EditModel.Grade();
    EditModel.applyAuto(g, a);
    // the grey wall comes out neutral
    double[] grey = EditModel.grade(rgbOf(px(new double[] {0.3, 0.3, 0.3}, cast, 0.3)), g);
    check(Math.abs(grey[0] - grey[2]) < 0.06 && Math.abs(grey[1] - (grey[0] + grey[2]) / 2) < 0.05, "grey comes out neutral " + java.util.Arrays.toString(grey));
    double[] ok = EditModel.autoGrade(good);
    check(Math.abs(ok[2]) < 0.1 && Math.abs(ok[3]) < 0.1, "a neutral picture keeps its white balance");
    check(Math.abs(ok[0]) <= 1.0, "a normal picture needs little exposure change: " + ok[0]);
    // a bright picture is never pushed brighter into clipping
    int[] bright = new int[scene.length];
    for (int i = 0; i < scene.length; i++) bright[i] = px(scene[i], new double[] {1, 1, 1}, 3);
    check(EditModel.autoGrade(bright)[0] <= 0, "bright picture is not brightened");
    check(EditModel.autoGrade(null)[0] == 0, "no frame, no change");
  }

  static int px(double[] lin, double[] cast, double gain) {
    int[] c = new int[3];
    for (int k = 0; k < 3; k++) c[k] = (int) Math.round(Math.min(1, Math.pow(Math.max(0, lin[k] * cast[k] * gain), 1 / 2.2)) * 255);
    return 0xff000000 | (c[0] << 16) | (c[1] << 8) | c[2];
  }

  static double[] rgbOf(int p) { return new double[] {((p >> 16) & 255) / 255.0, ((p >> 8) & 255) / 255.0, (p & 255) / 255.0}; }

  static void logDetectTests() {
    int w = 64, h = 36;
    int[] normal = new int[w * h], log = new int[w * h], black = new int[w * h], grey = new int[w * h];
    java.util.Random rnd = new java.util.Random(7);
    for (int i = 0; i < normal.length; i++) {
      double t = (i % w) / (double) (w - 1), u = (i / w) / (double) (h - 1);
      double r = t, g = 0.3 + 0.5 * u * t, b = 1 - t; // full range, saturated
      double n = rnd.nextDouble() * 0.02;
      normal[i] = argb(r + n, g, b);
      // the same scene through a log curve: lifted blacks, lowered whites, low saturation
      double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
      double[] c = {r, g, b};
      for (int k = 0; k < 3; k++) c[k] = 0.12 + 0.62 * (y + (c[k] - y) * 0.25);
      log[i] = argb(c[0], c[1], c[2]);
      black[i] = argb(0.02, 0.02, 0.02);
      grey[i] = argb(0.5, 0.5, 0.5);
    }
    check(!EditModel.looksLikeLog(normal), "normal footage is not log");
    check(EditModel.looksLikeLog(log), "log footage detected");
    check(!EditModel.looksLikeLog(black), "black frame: can't tell");
    check(!EditModel.looksLikeLog(grey), "grey card: can't tell");
    check(!EditModel.looksLikeLog(null), "no frame");
    // real City Cinema log: grey ramp through the S-Log3 curve is detected
    int[] slog = new int[w * h];
    for (int i = 0; i < slog.length; i++) {
      double x = (i % w) / (double) (w - 1) * 0.9 + 0.005;
      double v = x >= 0.01125 ? (420 + Math.log10((x + 0.01) / 0.19) * 261.5) / 1023 : (x * (171.2102946929 - 95) / 0.01125 + 95) / 1023;
      slog[i] = argb(v * 1.02, v, v * 0.97);
    }
    check(EditModel.looksLikeLog(slog), "S-Log3 ramp detected");
    // overexposed log: the sky clips to white but there are still no true blacks
    int[] hot = slog.clone();
    for (int i = 0; i < hot.length / 4; i++) hot[i] = argb(1, 1, 1);
    check(EditModel.looksLikeLog(hot), "overexposed log detected");
    // normal footage with a blown sky is still not log
    int[] hotNormal = normal.clone();
    for (int i = 0; i < hotNormal.length / 4; i++) hotNormal[i] = argb(1, 1, 1);
    check(!EditModel.looksLikeLog(hotNormal), "normal footage with a blown sky is not log");
  }

  static int argb(double r, double g, double b) {
    int R = (int) Math.round(Math.max(0, Math.min(1, r)) * 255), G = (int) Math.round(Math.max(0, Math.min(1, g)) * 255), B = (int) Math.round(Math.max(0, Math.min(1, b)) * 255);
    return 0xff000000 | (R << 16) | (G << 8) | B;
  }

  static void lutPackTests() {
    java.io.File dir = new java.io.File("assets/luts");
    String[] files = dir.list();
    check(files != null && files.length >= 17, "LUT pack present");
    if (files == null) return;
    for (String f : files) {
      check(f.toLowerCase(java.util.Locale.US).endsWith(".cube"), "pack file is .cube: " + f);
      try (java.io.Reader r = new java.io.FileReader(new java.io.File(dir, f))) {
        CubeLut l = CubeLut.parse(r);
        check(l.size >= 2 && l.size <= CubeLut.MAX_3D, "pack size " + f);
        float[] grey = l.apply(0.5f, 0.5f, 0.5f), white = l.apply(1, 1, 1), black = l.apply(0, 0, 0);
        check(white[1] > black[1] + 0.4f, "pack keeps a tonal range " + f);
        check(grey[1] > 0.25f && grey[1] < 0.8f, "pack mid grey sensible " + f);
      } catch (java.io.IOException e) { check(false, "pack parses " + f + ": " + e.getMessage()); }
    }
    check(LutPack.isPack(LutPack.key("A.cube")) && LutPack.assetPath(LutPack.key("A.cube")).equals("luts/A.cube"), "pack keys");
    check(LutPack.label("pack:NicoPan Indoor.cube").equals("NicoPan Indoor"), "pack label");
  }

  static void cubeTests() {
    try {
      // identity 3D LUT with title, comments and blank lines
      StringBuilder b = new StringBuilder("# made by hand\nTITLE \"Identity\"\n\nLUT_3D_SIZE 17\n");
      for (int z = 0; z < 17; z++) for (int y = 0; y < 17; y++) for (int x = 0; x < 17; x++) b.append(x / 16.0).append(' ').append(y / 16.0).append(' ').append(z / 16.0).append('\n');
      CubeLut id = cube(b.toString());
      check(id.size == 17 && "Identity".equals(id.title), "3D size and title");
      float[] o = id.apply(0.3f, 0.6f, 0.9f);
      near(o[0], 0.3, 1e-5, "identity r"); near(o[1], 0.6, 1e-5, "identity g"); near(o[2], 0.9, 1e-5, "identity b");
      // red varies fastest: a LUT that swaps red and blue
      b = new StringBuilder("LUT_3D_SIZE 2\n");
      for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) b.append(z).append(' ').append(y).append(' ').append(x).append('\n');
      o = cube(b.toString()).apply(1, 0, 0);
      check(o[0] == 0 && o[2] == 1, "red is the fastest axis");
      // domain
      b = new StringBuilder("LUT_3D_SIZE 2\nDOMAIN_MIN 0 0 0\nDOMAIN_MAX 2 2 2\n");
      for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) b.append(x).append(' ').append(y).append(' ').append(z).append('\n');
      near(cube(b.toString()).apply(1, 1, 1)[0], 0.5, 1e-6, "DOMAIN_MAX 2 maps input 1 to the middle");
      // 1D LUT: squares each channel
      b = new StringBuilder("LUT_1D_SIZE 256\n");
      for (int i = 0; i < 256; i++) { double v = i / 255.0; b.append(v * v).append(' ').append(v * v).append(' ').append(v * v).append('\n'); }
      CubeLut one = cube(b.toString());
      near(one.apply(0.5f, 0.5f, 0.5f)[0], 0.25, 0.003, "1D LUT expanded to 3D");
      // our own exported log LUT reads back exactly at its grid points
      CubeLut slog = cube(CinemaMath.logRec709Cube("Sony S-Log3"));
      double[] want = CinemaMath.logToRec709("Sony S-Log3", 0.5, 0.25, 0.75);
      float[] got = slog.apply(0.5f, 0.25f, 0.75f);
      for (int i = 0; i < 3; i++) near(got[i], want[i], 1e-5, "City Cinema cube reads back");
      // errors
      String[] bad = {"LUT_3D_SIZE 2\n0 0 0\n", "0 0 0\n", "LUT_3D_SIZE 99\n", "hello", "LUT_3D_SIZE 2\n0 0 x\n"};
      for (String s : bad) { boolean threw = false; try { cube(s); } catch (java.io.IOException e) { threw = true; } check(threw, "rejects a broken .cube: " + s.replace('\n', '|')); }
      // built-in cinematic pack
      for (String nm : CinematicLuts.NAMES) {
        CubeLut l = CinematicLuts.build(nm);
        check(l.size == 33 && l.data.length == 33 * 33 * 33 * 3, nm + " size");
        double diff = 0;
        for (int i = 0; i < l.data.length; i++) check(l.data[i] >= 0 && l.data[i] <= 1, nm + " in range");
        for (float[] p : new float[][] {{0.2f, 0.4f, 0.6f}, {0.8f, 0.5f, 0.3f}, {0.5f, 0.5f, 0.5f}}) { float[] o2 = l.apply(p[0], p[1], p[2]); for (int i = 0; i < 3; i++) diff += Math.abs(o2[i] - p[i]); }
        check(diff > 0.05, nm + " actually changes the picture");
        float[] black = l.apply(0, 0, 0), white = l.apply(1, 1, 1);
        check(CinematicLuts.luma(new double[] {white[0], white[1], white[2]}) > CinematicLuts.luma(new double[] {black[0], black[1], black[2]}) + 0.4, nm + " keeps tonal range");
        CubeLut back = cube(CinematicLuts.cube(nm));
        float[] p1 = back.apply(0.3f, 0.6f, 0.2f), p2 = l.apply(0.3f, 0.6f, 0.2f);
        for (int i = 0; i < 3; i++) near(p1[i], p2[i], 2e-5, nm + " .cube export reads back");
      }
      float[] noir = CinematicLuts.build("Noir").apply(0.9f, 0.2f, 0.1f);
      check(noir[0] == noir[1] && noir[1] == noir[2], "Noir is black and white");
      float[] to = CinematicLuts.build("Teal & Orange").apply(0.15f, 0.15f, 0.15f);
      check(to[2] > to[0], "Teal & Orange: teal shadows");
      // half floats for the GPU texture
      for (float f : new float[] {0, 0.001f, 0.18f, 0.5f, 1, 1.5f, -0.25f}) near(CubeLut.fromHalf(CubeLut.toHalf(f)), f, Math.max(1e-6, Math.abs(f) * 1e-3), "half float " + f);
    } catch (java.io.IOException e) { throw new AssertionError(e); }
  }
}

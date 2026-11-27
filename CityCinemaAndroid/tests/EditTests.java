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
    m.startUs = 0; m.sourceDurationUs = 60_000_000; m.volume = 0.8; m.fadeInUs = 1_000_000; m.fadeOutUs = 2_000_000; p.music = m;
    near(EditModel.musicGain(p, 500_000), 0.4, 1e-9, "music fades in");
    near(EditModel.musicGain(p, 5_000_000), 0.8, 1e-9, "music at its volume");
    near(EditModel.musicGain(p, EditModel.duration(p) - 1_000_000), 0.4, 1e-9, "music fades out with the programme");
    check(EditModel.musicGain(p, EditModel.duration(p) + 1) == 0, "music stops with the programme");
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
    System.out.println("EditTests: " + passed + " checks passed");
  }


  static CubeLut cube(String text) throws java.io.IOException { return CubeLut.parse(new java.io.StringReader(text)); }

  /** Every .cube shipped in assets/luts must load, be in range and produce a usable picture. */
  /** Log footage shown without conversion is recognised; normal and dark/flat frames are not. */
  /** Auto colour: a dark, warm-cast frame comes out neutral and at normal brightness; a good frame is left alone. */
  /** Speed changes the clip's length and maps timeline time to source time; split keeps both halves in sync. */
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

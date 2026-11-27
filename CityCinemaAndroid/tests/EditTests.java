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
    System.out.println("EditTests: " + passed + " checks passed");
  }


  static CubeLut cube(String text) throws java.io.IOException { return CubeLut.parse(new java.io.StringReader(text)); }

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
      // half floats for the GPU texture
      for (float f : new float[] {0, 0.001f, 0.18f, 0.5f, 1, 1.5f, -0.25f}) near(CubeLut.fromHalf(CubeLut.toHalf(f)), f, Math.max(1e-6, Math.abs(f) * 1e-3), "half float " + f);
    } catch (java.io.IOException e) { throw new AssertionError(e); }
  }
}

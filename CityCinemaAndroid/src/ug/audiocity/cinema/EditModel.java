package ug.audiocity.cinema;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's project model and timeline maths (pure Java, host-testable): clips with trims, volume, colour grade
 * and a transition into each clip; titles; a music track. Times are microseconds. A dissolve overlaps two clips;
 * a dip to black fades out and in without overlap; the project can fade in from and out to black.
 */
final class EditModel {
  private EditModel() {}

  static final String[] TRANSITIONS = {"Cut", "Dissolve", "Dip to black"};
  static final String[] FILM_LOOKS = {"None", "Teal & Orange", "Warm Film", "Cool Night", "Bleach Bypass", "Mono"};
  static final String[] TITLE_PLACES = {"Centre", "Lower third", "Top"};

  /** Colour grade, applied after the log LUT, in display (Rec.709) space. All neutral at 0. */
  /** Temperature and tint at +-1 scale red/blue (and green) by this much: enough to correct a wrong white balance. */
  static final double TEMP_RANGE = 0.25, TINT_RANGE = 0.15;

  static final class Grade {
    double exposure, contrast, saturation, temperature, tint, shadows, highlights; // stops; -1..1 for the rest
    int film; // index into FILM_LOOKS
    double filmStrength = 1;

    boolean neutral() { return exposure == 0 && contrast == 0 && saturation == 0 && temperature == 0 && tint == 0 && shadows == 0 && highlights == 0 && film == 0; }

    JSONObject json() throws JSONException {
      return new JSONObject().put("exposure", exposure).put("contrast", contrast).put("saturation", saturation).put("temperature", temperature)
          .put("tint", tint).put("shadows", shadows).put("highlights", highlights).put("film", film).put("filmStrength", filmStrength);
    }

    static Grade from(JSONObject j) {
      Grade g = new Grade();
      if (j == null) return g;
      g.exposure = j.optDouble("exposure", 0); g.contrast = j.optDouble("contrast", 0); g.saturation = j.optDouble("saturation", 0);
      g.temperature = j.optDouble("temperature", 0); g.tint = j.optDouble("tint", 0); g.shadows = j.optDouble("shadows", 0);
      g.highlights = j.optDouble("highlights", 0); g.film = j.optInt("film", 0); g.filmStrength = j.optDouble("filmStrength", 1);
      return g;
    }

    Grade copy() {
      Grade g = new Grade();
      g.exposure = exposure; g.contrast = contrast; g.saturation = saturation; g.temperature = temperature; g.tint = tint;
      g.shadows = shadows; g.highlights = highlights; g.film = film; g.filmStrength = filmStrength;
      return g;
    }
  }

  static final class Clip {
    /** Stable identity across copies (decoders and audio streams are kept per id while editing). */
    long id = NEXT_ID.incrementAndGet();
    String uri, name, look = "None"; // look: the log the clip was shot in ("None" for normal video)
    long sourceDurationUs, inUs, outUs;
    int width, height, rotation;
    double fps = 30;
    double volume = 1;
    boolean muted, hasAudio = true;
    int transition; // into this clip: 0 cut, 1 dissolve, 2 dip to black
    long transitionUs = 500_000;
    Grade grade = new Grade();
    /** A user-imported .cube LUT (file name in the app's LUT folder; "" = none), applied after the log LUT. */
    String lut = "";
    double lutStrength = 1;

    long durationUs() { return Math.max(0, outUs - inUs); }

    JSONObject json() throws JSONException {
      return new JSONObject().put("uri", uri).put("name", name).put("look", look).put("sourceDurationUs", sourceDurationUs).put("inUs", inUs)
          .put("outUs", outUs).put("width", width).put("height", height).put("rotation", rotation).put("fps", fps).put("volume", volume)
          .put("muted", muted).put("hasAudio", hasAudio).put("transition", transition).put("transitionUs", transitionUs).put("grade", grade.json())
          .put("lut", lut).put("lutStrength", lutStrength);
    }

    static Clip from(JSONObject j) {
      Clip c = new Clip();
      c.uri = j.optString("uri"); c.name = j.optString("name"); c.look = j.optString("look", "None");
      c.sourceDurationUs = j.optLong("sourceDurationUs"); c.inUs = j.optLong("inUs"); c.outUs = j.optLong("outUs");
      c.width = j.optInt("width"); c.height = j.optInt("height"); c.rotation = j.optInt("rotation"); c.fps = j.optDouble("fps", 30);
      c.volume = j.optDouble("volume", 1); c.muted = j.optBoolean("muted"); c.hasAudio = j.optBoolean("hasAudio", true);
      c.transition = j.optInt("transition"); c.transitionUs = j.optLong("transitionUs", 500_000); c.grade = Grade.from(j.optJSONObject("grade"));
      c.lut = j.optString("lut", ""); c.lutStrength = j.optDouble("lutStrength", 1);
      return c;
    }

    Clip copy() {
      Clip c = new Clip();
      c.id = id; c.uri = uri; c.name = name; c.look = look; c.sourceDurationUs = sourceDurationUs; c.inUs = inUs; c.outUs = outUs;
      c.width = width; c.height = height; c.rotation = rotation; c.fps = fps; c.volume = volume; c.muted = muted; c.hasAudio = hasAudio;
      c.transition = transition; c.transitionUs = transitionUs; c.grade = grade.copy(); c.lut = lut; c.lutStrength = lutStrength;
      return c;
    }
  }

  static final class Title {
    String text = "Title";
    long startUs, durationUs = 3_000_000;
    int place = 1; // TITLE_PLACES
    int color = 0xffffffff;
    boolean box = true;
    double size = 1;

    JSONObject json() throws JSONException {
      return new JSONObject().put("text", text).put("startUs", startUs).put("durationUs", durationUs).put("place", place)
          .put("color", color).put("box", box).put("size", size);
    }

    static Title from(JSONObject j) {
      Title t = new Title();
      t.text = j.optString("text", "Title"); t.startUs = j.optLong("startUs"); t.durationUs = j.optLong("durationUs", 3_000_000);
      t.place = j.optInt("place", 1); t.color = j.optInt("color", 0xffffffff); t.box = j.optBoolean("box", true); t.size = j.optDouble("size", 1);
      return t;
    }
  }

  static final class Music {
    String uri, name;
    long startUs, sourceDurationUs;
    double volume = 0.6;
    long fadeInUs = 1_000_000, fadeOutUs = 2_000_000;

    JSONObject json() throws JSONException {
      return new JSONObject().put("uri", uri).put("name", name).put("startUs", startUs).put("sourceDurationUs", sourceDurationUs)
          .put("volume", volume).put("fadeInUs", fadeInUs).put("fadeOutUs", fadeOutUs);
    }

    static Music from(JSONObject j) {
      if (j == null) return null;
      Music m = new Music();
      m.uri = j.optString("uri"); m.name = j.optString("name"); m.startUs = j.optLong("startUs"); m.sourceDurationUs = j.optLong("sourceDurationUs");
      m.volume = j.optDouble("volume", 0.6); m.fadeInUs = j.optLong("fadeInUs", 1_000_000); m.fadeOutUs = j.optLong("fadeOutUs", 2_000_000);
      return m;
    }
  }

  static final class Project {
    final List<Clip> clips = new ArrayList<>();
    final List<Title> titles = new ArrayList<>();
    Music music;
    boolean fadeIn = true, fadeOut = true;
    long fadeUs = 700_000;

    JSONObject json() throws JSONException {
      JSONArray c = new JSONArray(), t = new JSONArray();
      for (Clip k : clips) c.put(k.json());
      for (Title k : titles) t.put(k.json());
      JSONObject j = new JSONObject().put("version", 1).put("clips", c).put("titles", t).put("fadeIn", fadeIn).put("fadeOut", fadeOut).put("fadeUs", fadeUs);
      if (music != null) j.put("music", music.json());
      return j;
    }

    static Project from(JSONObject j) {
      Project p = new Project();
      JSONArray c = j.optJSONArray("clips"), t = j.optJSONArray("titles");
      if (c != null) for (int i = 0; i < c.length(); i++) p.clips.add(Clip.from(c.optJSONObject(i)));
      if (t != null) for (int i = 0; i < t.length(); i++) p.titles.add(Title.from(t.optJSONObject(i)));
      p.music = Music.from(j.optJSONObject("music"));
      p.fadeIn = j.optBoolean("fadeIn", true); p.fadeOut = j.optBoolean("fadeOut", true); p.fadeUs = j.optLong("fadeUs", 700_000);
      return p;
    }
  }

  static final java.util.concurrent.atomic.AtomicLong NEXT_ID = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

  /** A deep copy (for the preview thread to draw while the UI edits). */
  static Project snapshot(Project p) {
    Project q = new Project();
    for (Clip c : p.clips) q.clips.add(c.copy());
    for (Title t : p.titles) { Title u = new Title(); u.text = t.text; u.startUs = t.startUs; u.durationUs = t.durationUs; u.place = t.place; u.color = t.color; u.box = t.box; u.size = t.size; q.titles.add(u); }
    if (p.music != null) { Music m = new Music(); Music o = p.music; m.uri = o.uri; m.name = o.name; m.startUs = o.startUs; m.sourceDurationUs = o.sourceDurationUs; m.volume = o.volume; m.fadeInUs = o.fadeInUs; m.fadeOutUs = o.fadeOutUs; q.music = m; }
    q.fadeIn = p.fadeIn; q.fadeOut = p.fadeOut; q.fadeUs = p.fadeUs;
    return q;
  }

  static JSONObject safe(Grade g) { try { return g.json(); } catch (JSONException e) { return new JSONObject(); } }

  // ------------------------------------------------------------------------------------------------ timeline

  /** A transition can't be longer than half of either clip it joins. */
  static long transitionUs(Project p, int i) {
    if (i <= 0 || i >= p.clips.size()) return 0;
    Clip c = p.clips.get(i), prev = p.clips.get(i - 1);
    if (c.transition == 0) return 0;
    return Math.max(0, Math.min(c.transitionUs, Math.min(c.durationUs(), prev.durationUs()) / 2));
  }

  /** Start time of each clip on the timeline (dissolves overlap the previous clip). */
  static long[] starts(Project p) {
    long[] s = new long[p.clips.size()];
    long t = 0;
    for (int i = 0; i < s.length; i++) {
      if (i > 0 && p.clips.get(i).transition == 1) t -= transitionUs(p, i);
      s[i] = t;
      t += p.clips.get(i).durationUs();
    }
    return s;
  }

  static long duration(Project p) {
    if (p.clips.isEmpty()) return 0;
    long[] s = starts(p);
    int last = s.length - 1;
    return s[last] + p.clips.get(last).durationUs();
  }

  /** What to draw at a timeline time: the clip, the time inside its source, its opacity, and the black-dip factor. */
  static final class Layer {
    final int clip; final long sourceUs; final double alpha, brightness;
    Layer(int clip, long sourceUs, double alpha, double brightness) { this.clip = clip; this.sourceUs = sourceUs; this.alpha = alpha; this.brightness = brightness; }
  }

  /**
   * Layers at timeline time t, bottom first: normally one clip at full opacity; during a dissolve the outgoing clip
   * under the incoming one with rising opacity. Brightness carries dips to black and the project fades.
   */
  static List<Layer> layersAt(Project p, long t) {
    List<Layer> out = new ArrayList<>();
    long[] s = starts(p);
    long total = duration(p);
    double fade = 1;
    if (p.fadeIn && t < p.fadeUs) fade = Math.min(fade, t / (double) p.fadeUs);
    if (p.fadeOut && t > total - p.fadeUs) fade = Math.min(fade, Math.max(0, (total - t) / (double) p.fadeUs));
    for (int i = 0; i < s.length; i++) {
      Clip c = p.clips.get(i);
      long local = t - s[i];
      if (local < 0 || local >= c.durationUs()) continue;
      double alpha = 1, bright = fade;
      long tin = transitionUs(p, i);
      if (c.transition == 1 && tin > 0 && local < tin) alpha = ease(local / (double) tin);          // dissolve in
      if (c.transition == 2 && tin > 0 && local < tin / 2) bright *= local / (tin / 2.0);           // dip: fade up
      if (i + 1 < s.length) {
        Clip next = p.clips.get(i + 1);
        long tout = transitionUs(p, i + 1);
        if (next.transition == 2 && tout > 0 && local > c.durationUs() - tout / 2) bright *= Math.max(0, (c.durationUs() - local) / (tout / 2.0)); // dip: fade down
      }
      out.add(new Layer(i, c.inUs + local, alpha, bright));
    }
    return out;
  }

  static double ease(double x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

  /** Volume of a clip's sound at timeline time t (clip volume, mute, crossfades, dips, project fades). */
  static double clipGain(Project p, int i, long t) {
    Clip c = p.clips.get(i);
    if (c.muted || !c.hasAudio) return 0;
    for (Layer l : layersAt(p, t)) if (l.clip == i) {
      double g = c.volume * l.brightness;
      long local = t - starts(p)[i];
      long tin = transitionUs(p, i);
      if (c.transition == 1 && tin > 0 && local < tin) g *= Math.sqrt(ease(local / (double) tin)); // equal-power crossfade
      if (i + 1 < p.clips.size() && p.clips.get(i + 1).transition == 1) {
        long tout = transitionUs(p, i + 1);
        long fromEnd = c.durationUs() - local;
        if (tout > 0 && fromEnd < tout) g *= Math.sqrt(ease(fromEnd / (double) tout));
      }
      return g;
    }
    return 0;
  }

  /** Music volume at timeline time t (its own fades, plus the project fade out). */
  static double musicGain(Project p, long t) {
    Music m = p.music;
    if (m == null) return 0;
    long local = t - m.startUs, total = duration(p);
    if (local < 0 || local >= m.sourceDurationUs || t >= total) return 0;
    double g = m.volume;
    if (m.fadeInUs > 0 && local < m.fadeInUs) g *= local / (double) m.fadeInUs;
    long end = Math.min(m.sourceDurationUs - local, total - t);
    if (m.fadeOutUs > 0 && end < m.fadeOutUs) g *= end / (double) m.fadeOutUs;
    return g;
  }

  /** Title opacity at time t (0.3 s fades in and out). */
  static double titleAlpha(Title title, long t) {
    long local = t - title.startUs;
    if (local < 0 || local >= title.durationUs) return 0;
    double f = 300_000;
    return Math.min(1, Math.min(local / f, (title.durationUs - local) / f));
  }

  /** Splits clip i at timeline time t. Returns false if t isn't inside the clip (with 0.1 s to spare). */
  static boolean split(Project p, int i, long t) {
    Clip c = p.clips.get(i);
    long local = t - starts(p)[i];
    if (local < 100_000 || local > c.durationUs() - 100_000) return false;
    Clip b = c.copy();
    b.id = NEXT_ID.incrementAndGet();
    b.inUs = c.inUs + local; b.transition = 0;
    c.outUs = c.inUs + local;
    p.clips.add(i + 1, b);
    return true;
  }

  // ------------------------------------------------------------------------------------------------ colour grade

  /**
   * The grade on one display-referred RGB value (0..1). Mirrors the editor's GLSL so previews, exports and tests
   * agree: exposure and white balance in linear light, then shadows/highlights, contrast, film look, saturation.
   */
  static double[] grade(double[] rgb, Grade g) {
    double[] c = new double[3];
    double[] wb = {1 + TEMP_RANGE * g.temperature, 1 - TINT_RANGE * g.tint, 1 - TEMP_RANGE * g.temperature};
    double gain = Math.pow(2, g.exposure);
    for (int i = 0; i < 3; i++) {
      double lin = Math.pow(Math.max(0, rgb[i]), 2.2) * gain * wb[i];
      double v = Math.pow(Math.min(1, lin), 1 / 2.2);
      v += g.shadows * 0.35 * (1 - v) * (1 - v) * v * 2;      // lift or crush the low end
      v -= g.highlights * 0.35 * v * v * (1 - v) * 2;          // pull down or push the top end
      v = (v - 0.5) * (1 + g.contrast) + 0.5;
      c[i] = Math.max(0, Math.min(1, v));
    }
    c = film(c, g.film, g.filmStrength);
    double y = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    double sat = 1 + g.saturation;
    for (int i = 0; i < 3; i++) c[i] = Math.max(0, Math.min(1, y + (c[i] - y) * sat));
    return c;
  }

  static double[] film(double[] c, int look, double strength) {
    if (look == 0) return c;
    double y = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    double[] f = c.clone();
    switch (look) {
      case 1: { double w = y; f[0] = c[0] + 0.10 * w - 0.03; f[1] = c[1] + 0.02 * w; f[2] = c[2] - 0.10 * w + 0.07; break; } // teal shadows, orange highlights
      case 2: f[0] = c[0] * 1.06 + 0.02; f[1] = c[1] * 1.0 + 0.01; f[2] = c[2] * 0.88; break;
      case 3: f[0] = c[0] * 0.90; f[1] = c[1] * 0.98; f[2] = c[2] * 1.08 + 0.02; break;
      case 4: for (int i = 0; i < 3; i++) { double m = c[i] * 0.6 + y * 0.4; f[i] = (m - 0.5) * 1.25 + 0.5; } break;
      case 5: f[0] = f[1] = f[2] = (y - 0.5) * 1.1 + 0.5; break;
      default: break;
    }
    for (int i = 0; i < 3; i++) f[i] = Math.max(0, Math.min(1, c[i] + (f[i] - c[i]) * strength));
    return f;
  }

  // ------------------------------------------------------------------------------------------------ audio

  /** Linear-interpolation resampler for interleaved float audio (any rate and channel count to 48 kHz stereo). */
  static final class Resampler {
    private final double step;
    private final int inChannels;
    private double pos;
    private final float[] last = new float[2];
    private boolean primed;

    Resampler(int inRate, int inChannels) { this.step = inRate / 48_000.0; this.inChannels = Math.max(1, inChannels); }

    /** Converts `frames` input frames; returns the number of 48 kHz stereo frames written to out. */
    int process(float[] in, int frames, float[] out, int outCapacityFrames) {
      int written = 0;
      for (; written < outCapacityFrames; written++) {
        int i0 = (int) Math.floor(pos);
        if (i0 + 1 >= frames) break;
        double f = pos - i0;
        for (int ch = 0; ch < 2; ch++) {
          int src = Math.min(ch, inChannels - 1);
          float a = i0 < 0 ? last[ch] : in[i0 * inChannels + src], b = in[(i0 + 1) * inChannels + src];
          out[written * 2 + ch] = (float) (a + (b - a) * f);
        }
        pos += step;
      }
      // carry the remainder into the next call
      if (frames > 0) { for (int ch = 0; ch < 2; ch++) last[ch] = in[(frames - 1) * inChannels + Math.min(ch, inChannels - 1)]; }
      pos -= frames;
      if (!primed) primed = true;
      return written;
    }
  }

  /**
   * True when a frame (ARGB pixels) looks like log footage shown without its conversion: no true blacks, no bright
   * whites and washed-out colour. Used to ask about the log when a clip has no sidecar.
   */
  static boolean looksLikeLog(int[] argb) {
    if (argb == null || argb.length < 100) return false;
    int[] hist = new int[256];
    double sat = 0;
    for (int p : argb) {
      int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
      int y = (int) Math.round(0.2126 * r + 0.7152 * g + 0.0722 * b);
      hist[Math.max(0, Math.min(255, y))]++;
      int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
      sat += mx == 0 ? 0 : (mx - mn) / (double) mx;
    }
    sat /= argb.length;
    int lo = percentile(hist, argb.length, 0.01), hi = percentile(hist, argb.length, 0.99);
    if (hi - lo < 25) return false; // flat card or black frame: can't tell
    // log has no true blacks and muted colour; overexposed log can still clip to white, so the top is not required
    return lo > 14 && sat < 0.28;
  }

  private static int percentile(int[] hist, int n, double q) {
    long want = Math.round(n * q), sum = 0;
    for (int i = 0; i < 256; i++) { sum += hist[i]; if (sum > want) return i; }
    return 255;
  }

  /**
   * Automatic colour correction from one frame (ARGB, normal colour, i.e. after any log conversion), like the auto
   * button in phone and desktop editors: white balance from the near-neutral mid tones (saturated colours such as
   * foliage and sky are ignored), exposure so the middle of the picture sits at normal brightness without pushing
   * highlights into clipping, and a little contrast for flat, hazy pictures. Returns {exposure, contrast, temperature,
   * tint} in the Grade's units; the creative settings (looks, saturation) are left to the user.
   */
  static double[] autoGrade(int[] argb) {
    double[] out = new double[4];
    if (argb == null || argb.length < 100) return out;
    // 1. white balance from near-neutral mid tones (linear light)
    double sr = 0, sg = 0, sb = 0; int n = 0;
    for (int p : argb) {
      double r = ((p >> 16) & 255) / 255.0, g = ((p >> 8) & 255) / 255.0, b = (p & 255) / 255.0;
      double mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
      double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
      if (y < 0.12 || mx > 0.94 || (mx - mn) / mx > 0.28) continue;
      sr += Math.pow(r, 2.2); sg += Math.pow(g, 2.2); sb += Math.pow(b, 2.2); n++;
    }
    double t = 0, u = 0;
    if (n > argb.length * 0.03 && sr > 0 && sg > 0 && sb > 0) {
      double q = sb / sr; // (1 + 0.12t) r = (1 - 0.12t) b
      t = (q - 1) / (TEMP_RANGE * (1 + q));
      t = Math.max(-0.8, Math.min(0.8, t * 0.85)); // a touch short of fully neutral keeps the light's mood
      double rr = sr * (1 + TEMP_RANGE * t), bb = sb * (1 - TEMP_RANGE * t);
      u = (1 - Math.sqrt(rr * bb) / sg) / TINT_RANGE;
      u = Math.max(-0.5, Math.min(0.5, u * 0.85));
    }
    out[2] = Math.abs(t) < 0.03 ? 0 : Math.round(t * 100) / 100.0;
    out[3] = Math.abs(u) < 0.03 ? 0 : Math.round(u * 100) / 100.0;
    // 2. exposure: median light to normal brightness, never pushing the bright 1% past white
    double[] wb = {1 + TEMP_RANGE * out[2], 1 - TINT_RANGE * out[3], 1 - TEMP_RANGE * out[2]};
    double[] lum = new double[argb.length];
    for (int i = 0; i < argb.length; i++) {
      int p = argb[i];
      double r = Math.pow(((p >> 16) & 255) / 255.0, 2.2) * wb[0], g = Math.pow(((p >> 8) & 255) / 255.0, 2.2) * wb[1], b = Math.pow((p & 255) / 255.0, 2.2) * wb[2];
      lum[i] = 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }
    double[] sorted = lum.clone();
    java.util.Arrays.sort(sorted);
    double median = Math.max(1e-4, sorted[sorted.length / 2]), p99 = Math.max(1e-4, sorted[(int) (sorted.length * 0.99)]);
    double stops = Math.log(0.16 / median) / Math.log(2);
    if (stops > 0) stops = Math.min(stops, Math.max(0, Math.log(0.95 / p99) / Math.log(2))); // don't blow the highlights
    stops = Math.max(-2, Math.min(2, stops));
    out[0] = Math.abs(stops) < 0.15 ? 0 : Math.round(stops * 10) / 10.0;
    // 3. contrast for flat pictures: widen a narrow range a little
    double gain = Math.pow(2, out[0]);
    double lo = Math.pow(Math.min(1, sorted[(int) (sorted.length * 0.02)] * gain), 1 / 2.2), hi = Math.pow(Math.min(1, sorted[(int) (sorted.length * 0.98)] * gain), 1 / 2.2);
    double range = hi - lo;
    out[1] = range < 0.75 ? Math.round(Math.min(0.25, (0.8 - range) * 0.5) * 100) / 100.0 : 0;
    return out;
  }

  static void applyAuto(Grade g, double[] a) { g.exposure = a[0]; g.contrast = a[1]; g.temperature = a[2]; g.tint = a[3]; }
}

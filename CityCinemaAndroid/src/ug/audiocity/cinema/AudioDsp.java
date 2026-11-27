package ug.audiocity.cinema;

/**
 * The editor's audio processing, plain Java (host-tested): filters, EQ, compressor, noise gate, de-esser, ducking,
 * the master limiter and BS.1770 loudness / true-peak measurement. Everything works on interleaved 48 kHz stereo
 * float blocks and keeps its state between blocks, so it runs the same in preview playback and in export.
 */
final class AudioDsp {
  private AudioDsp() {}

  static final int RATE = 48_000;

  static double dbToGain(double db) { return Math.pow(10, db / 20); }

  static double gainToDb(double g) { return 20 * Math.log10(Math.max(1e-12, g)); }

  // ---------------------------------------------------------------- biquad filters (RBJ audio EQ cookbook)

  /** A stereo biquad (direct form I, one state per channel). */
  static final class Biquad {
    double b0 = 1, b1, b2, a1, a2;
    private final double[] x1 = new double[2], x2 = new double[2], y1 = new double[2], y2 = new double[2];

    Biquad set(double[] c) { b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4]; return this; }

    double run(int ch, double x) {
      double y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch];
      x2[ch] = x1[ch]; x1[ch] = x; y2[ch] = y1[ch]; y1[ch] = y;
      return y;
    }

    void process(float[] buf, int frames) {
      for (int i = 0; i < frames; i++) { buf[i * 2] = (float) run(0, buf[i * 2]); buf[i * 2 + 1] = (float) run(1, buf[i * 2 + 1]); }
    }

    /** Gain in dB of this filter at frequency f. */
    double responseDb(double f) {
      double w = 2 * Math.PI * f / RATE;
      double cr = Math.cos(w), ci = -Math.sin(w), c2r = Math.cos(2 * w), c2i = -Math.sin(2 * w);
      double nr = b0 + b1 * cr + b2 * c2r, ni = b1 * ci + b2 * c2i, dr = 1 + a1 * cr + a2 * c2r, di = a1 * ci + a2 * c2i;
      return 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di));
    }
  }

  private static double[] norm(double b0, double b1, double b2, double a0, double a1, double a2) { return new double[] {b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0}; }

  static double[] lowpass(double f, double q) {
    double w = 2 * Math.PI * f / RATE, al = Math.sin(w) / (2 * q), c = Math.cos(w);
    return norm((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
  }

  static double[] highpass(double f, double q) {
    double w = 2 * Math.PI * f / RATE, al = Math.sin(w) / (2 * q), c = Math.cos(w);
    return norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
  }

  static double[] peaking(double f, double q, double db) {
    double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / RATE, al = Math.sin(w) / (2 * q), c = Math.cos(w);
    return norm(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
  }

  static double[] lowShelf(double f, double db) {
    double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / RATE, c = Math.cos(w), al = Math.sin(w) / 2 * Math.sqrt(2), sa = 2 * Math.sqrt(A) * al;
    return norm(A * ((A + 1) - (A - 1) * c + sa), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sa),
        (A + 1) + (A - 1) * c + sa, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sa);
  }

  static double[] highShelf(double f, double db) {
    double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / RATE, c = Math.cos(w), al = Math.sin(w) / 2 * Math.sqrt(2), sa = 2 * Math.sqrt(A) * al;
    return norm(A * ((A + 1) + (A - 1) * c + sa), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sa),
        (A + 1) - (A - 1) * c + sa, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sa);
  }

  // ---------------------------------------------------------------- dynamics

  /** Envelope smoothing coefficient for a time constant in milliseconds. */
  static double coef(double ms) { return Math.exp(-1.0 / (Math.max(0.01, ms) / 1000.0 * RATE)); }

  /** Feed-forward stereo-linked compressor with a 6 dB soft knee (gains in dB). */
  static final class Compressor {
    double threshold = -18, ratio = 3, makeup = 0, attackMs = 10, releaseMs = 150;
    private double env, grSmooth; // detector: instant-attack peak with a slow release; then the gain change is smoothed
    double reductionDb;

    /** Gain (linear) for the next sample's level (linear peak of the sample). */
    double gainFor(double level) {
      env = Math.max(level, env * coef(releaseMs));
      double over = gainToDb(env) - threshold, knee = 6, gr;
      if (over <= -knee / 2) gr = 0;
      else if (over >= knee / 2) gr = over * (1 / ratio - 1);
      else { double x = over + knee / 2; gr = (1 / ratio - 1) * x * x / (2 * knee); }
      double a = gr < grSmooth ? coef(attackMs) : coef(releaseMs); // more reduction: attack; less: release
      grSmooth = a * grSmooth + (1 - a) * gr;
      reductionDb = grSmooth;
      return dbToGain(grSmooth + makeup);
    }

    void process(float[] buf, int frames) {
      for (int i = 0; i < frames; i++) {
        double peak = Math.max(Math.abs(buf[i * 2]), Math.abs(buf[i * 2 + 1]));
        float g = (float) gainFor(peak);
        buf[i * 2] *= g; buf[i * 2 + 1] *= g;
      }
    }
  }

  /** Noise gate: below the threshold the sound is turned down by `rangeDb`, smoothly (hold keeps word ends). */
  static final class Gate {
    double threshold = -50, rangeDb = -30, attackMs = 2, releaseMs = 120, holdMs = 60;
    private double env, gain = 1;
    private int hold;

    void process(float[] buf, int frames) {
      double low = dbToGain(rangeDb), thr = dbToGain(threshold);
      double ea = coef(1), er = coef(30), ga = coef(attackMs), gr = coef(releaseMs);
      int holdN = (int) (holdMs / 1000 * RATE);
      for (int i = 0; i < frames; i++) {
        double peak = Math.max(Math.abs(buf[i * 2]), Math.abs(buf[i * 2 + 1]));
        env = peak > env ? ea * env + (1 - ea) * peak : er * env + (1 - er) * peak;
        double target;
        if (env >= thr) { target = 1; hold = holdN; } else if (hold > 0) { target = 1; hold--; } else target = low;
        gain = target > gain ? ga * gain + (1 - ga) * target : gr * gain + (1 - gr) * target;
        buf[i * 2] *= gain; buf[i * 2 + 1] *= gain;
      }
    }
  }

  /** De-esser: a compressor that listens only to 5-9 kHz (sibilance) and turns the whole sound down briefly. */
  static final class DeEsser {
    private final Biquad detect = new Biquad().set(highpass(5000, 0.7));
    private final Compressor comp = new Compressor();
    DeEsser(double amount) { comp.threshold = -30 + 18 * (1 - Math.max(0, Math.min(1, amount))); comp.ratio = 6; comp.attackMs = 1; comp.releaseMs = 60; }

    void process(float[] buf, int frames) {
      for (int i = 0; i < frames; i++) {
        double s = Math.max(Math.abs(detect.run(0, buf[i * 2])), Math.abs(detect.run(1, buf[i * 2 + 1])));
        float g = (float) comp.gainFor(s);
        buf[i * 2] *= g; buf[i * 2 + 1] *= g;
      }
    }
  }

  // ---------------------------------------------------------------- a bus's effects

  /** Settings of one bus (CAMERA, MUSIC, VOICE, FX). 0 Hz = filter off. */
  static final class Fx {
    double gainDb, hpfHz, lpfHz, lowDb, midDb, midHz = 1000, highDb;
    boolean compOn, gateOn;
    double compThreshold = -18, compRatio = 3, compMakeup, gateThreshold = -50, deEss;

    boolean active() { return gainDb != 0 || hpfHz > 0 || lpfHz > 0 || lowDb != 0 || midDb != 0 || highDb != 0 || compOn || gateOn || deEss > 0; }

    String signature() {
      return gainDb + "|" + hpfHz + "|" + lpfHz + "|" + lowDb + "|" + midDb + "|" + midHz + "|" + highDb + "|" + compOn + "|" + gateOn + "|"
          + compThreshold + "|" + compRatio + "|" + compMakeup + "|" + gateThreshold + "|" + deEss;
    }

    Fx copy() {
      Fx f = new Fx();
      f.gainDb = gainDb; f.hpfHz = hpfHz; f.lpfHz = lpfHz; f.lowDb = lowDb; f.midDb = midDb; f.midHz = midHz; f.highDb = highDb;
      f.compOn = compOn; f.gateOn = gateOn; f.compThreshold = compThreshold; f.compRatio = compRatio; f.compMakeup = compMakeup;
      f.gateThreshold = gateThreshold; f.deEss = deEss;
      return f;
    }

    org.json.JSONObject json() throws org.json.JSONException {
      return new org.json.JSONObject().put("gainDb", gainDb).put("hpfHz", hpfHz).put("lpfHz", lpfHz).put("lowDb", lowDb).put("midDb", midDb)
          .put("midHz", midHz).put("highDb", highDb).put("compOn", compOn).put("gateOn", gateOn).put("compThreshold", compThreshold)
          .put("compRatio", compRatio).put("compMakeup", compMakeup).put("gateThreshold", gateThreshold).put("deEss", deEss);
    }

    static Fx from(org.json.JSONObject j) {
      Fx f = new Fx();
      if (j == null) return f;
      f.gainDb = j.optDouble("gainDb", 0); f.hpfHz = j.optDouble("hpfHz", 0); f.lpfHz = j.optDouble("lpfHz", 0); f.lowDb = j.optDouble("lowDb", 0);
      f.midDb = j.optDouble("midDb", 0); f.midHz = j.optDouble("midHz", 1000); f.highDb = j.optDouble("highDb", 0);
      f.compOn = j.optBoolean("compOn"); f.gateOn = j.optBoolean("gateOn"); f.compThreshold = j.optDouble("compThreshold", -18);
      f.compRatio = j.optDouble("compRatio", 3); f.compMakeup = j.optDouble("compMakeup", 0); f.gateThreshold = j.optDouble("gateThreshold", -50);
      f.deEss = j.optDouble("deEss", 0);
      return f;
    }

    /** Clean dialogue: rumble cut, a little presence, gentle compression, de-essing, a soft gate for room noise. */
    static Fx voicePreset() {
      Fx f = new Fx();
      f.hpfHz = 90; f.midDb = 2; f.midHz = 3000; f.compOn = true; f.compThreshold = -22; f.compRatio = 3; f.compMakeup = 4; f.deEss = 0.5;
      f.gateOn = true; f.gateThreshold = -55;
      return f;
    }
  }

  /** The running effects chain of one bus: gate, HPF, LPF, EQ, de-esser, compressor, gain (rebuilt when settings change). */
  static final class Chain {
    private String sig = "";
    private Gate gate; private Biquad hp, lp, low, mid, high; private DeEsser deEss; private Compressor comp;
    private double gain = 1;

    void process(Fx fx, float[] buf, int frames) {
      if (fx == null || !fx.active()) return;
      if (!fx.signature().equals(sig)) build(fx);
      if (gate != null) gate.process(buf, frames);
      for (Biquad b : new Biquad[] {hp, lp, low, mid, high}) if (b != null) b.process(buf, frames);
      if (deEss != null) deEss.process(buf, frames);
      if (comp != null) comp.process(buf, frames);
      if (gain != 1) for (int i = 0; i < frames * 2; i++) buf[i] *= gain;
    }

    double reductionDb() { return comp == null ? 0 : comp.reductionDb; }

    private void build(Fx f) {
      sig = f.signature();
      gate = f.gateOn ? new Gate() : null; if (gate != null) gate.threshold = f.gateThreshold;
      hp = f.hpfHz > 0 ? new Biquad().set(highpass(f.hpfHz, 0.707)) : null;
      lp = f.lpfHz > 0 ? new Biquad().set(lowpass(f.lpfHz, 0.707)) : null;
      low = f.lowDb != 0 ? new Biquad().set(lowShelf(120, f.lowDb)) : null;
      mid = f.midDb != 0 ? new Biquad().set(peaking(f.midHz, 1.0, f.midDb)) : null;
      high = f.highDb != 0 ? new Biquad().set(highShelf(8000, f.highDb)) : null;
      deEss = f.deEss > 0 ? new DeEsser(f.deEss) : null;
      if (f.compOn) { comp = new Compressor(); comp.threshold = f.compThreshold; comp.ratio = f.compRatio; comp.makeup = f.compMakeup; } else comp = null;
      gain = dbToGain(f.gainDb);
    }
  }

  // ---------------------------------------------------------------- ducking

  /** Turns the music down smoothly while there is speech (attack / release in ms, depth in dB). */
  static final class Ducker {
    double depthDb = -12, thresholdDb = -40, attackMs = 80, releaseMs = 600;
    private double env, gainDb;

    /** key: the speech (interleaved stereo); music: turned down in place. */
    void process(float[] key, float[] music, int frames) {
      double ea = coef(5), er = coef(120), ga = coef(attackMs), gr = coef(releaseMs), thr = dbToGain(thresholdDb);
      for (int i = 0; i < frames; i++) {
        double k = Math.max(Math.abs(key[i * 2]), Math.abs(key[i * 2 + 1]));
        env = k > env ? ea * env + (1 - ea) * k : er * env + (1 - er) * k;
        double target = env > thr ? depthDb : 0;
        gainDb = target < gainDb ? ga * gainDb + (1 - ga) * target : gr * gainDb + (1 - gr) * target;
        float g = (float) dbToGain(gainDb);
        music[i * 2] *= g; music[i * 2 + 1] *= g;
      }
    }

    double currentDb() { return gainDb; }
  }

  // ---------------------------------------------------------------- loudness (ITU-R BS.1770-4 / EBU R128)

  /** Integrated and momentary loudness (LUFS) and true peak (dBTP) of a stereo 48 kHz signal. */
  static final class Loudness {
    // K-weighting at 48 kHz (BS.1770): high shelf, then the RLB high-pass
    private final Biquad pre = new Biquad().set(new double[] {1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585});
    private final Biquad rlb = new Biquad().set(new double[] {1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621});
    private static final int HOP = RATE / 10; // 100 ms
    private final double[] hops = new double[4]; // the last four 100 ms mean squares (one 400 ms block)
    private int hopFill, hopCount;
    private double hopSum;
    private final java.util.ArrayList<Double> blocks = new java.util.ArrayList<>();
    private final TruePeak tp = new TruePeak();
    double momentary = -120;

    void process(float[] buf, int frames) {
      tp.process(buf, frames);
      for (int i = 0; i < frames; i++) {
        double l = rlb.run(0, pre.run(0, buf[i * 2])), r = rlb.run(1, pre.run(1, buf[i * 2 + 1]));
        hopSum += l * l + r * r;
        if (++hopFill == HOP) {
          hops[hopCount % 4] = hopSum / HOP;
          hopCount++; hopFill = 0; hopSum = 0;
          if (hopCount >= 4) {
            double z = (hops[0] + hops[1] + hops[2] + hops[3]) / 4;
            blocks.add(z);
            momentary = -0.691 + 10 * Math.log10(Math.max(1e-12, z));
          }
        }
      }
    }

    /** Integrated loudness with the absolute (-70 LUFS) and relative (-10 LU) gates; -120 if there is no sound. */
    double integrated() {
      double sum = 0; int n = 0;
      for (double z : blocks) if (-0.691 + 10 * Math.log10(Math.max(1e-12, z)) > -70) { sum += z; n++; }
      if (n == 0) return -120;
      double rel = -0.691 + 10 * Math.log10(sum / n) - 10;
      sum = 0; n = 0;
      for (double z : blocks) if (-0.691 + 10 * Math.log10(Math.max(1e-12, z)) > rel) { sum += z; n++; }
      return n == 0 ? -120 : -0.691 + 10 * Math.log10(sum / n);
    }

    double truePeakDb() { return gainToDb(tp.peak); }
  }

  /** True peak: the signal 4x oversampled (48-tap windowed-sinc interpolation), as BS.1770 Annex 2 suggests. */
  static final class TruePeak {
    private static final int TAPS = 12; // per phase
    private static final double[][] PHASES = new double[4][TAPS];
    static {
      for (int ph = 0; ph < 4; ph++) {
        for (int k = 0; k < TAPS; k++) {
          double t = (k - TAPS / 2 + 1) - ph / 4.0; // distance from the interpolated point, in input samples
          double sinc = Math.abs(t) < 1e-9 ? 1 : Math.sin(Math.PI * t) / (Math.PI * t);
          double win = 0.5 + 0.5 * Math.cos(Math.PI * t / (TAPS / 2.0));
          PHASES[ph][k] = sinc * win;
        }
      }
    }
    private final double[][] hist = new double[2][TAPS];
    private int pos;
    double peak;

    void process(float[] buf, int frames) {
      for (int i = 0; i < frames; i++) {
        for (int ch = 0; ch < 2; ch++) {
          hist[ch][pos] = buf[i * 2 + ch];
          for (int ph = 0; ph < 4; ph++) {
            double v = 0;
            for (int k = 0; k < TAPS; k++) v += PHASES[ph][k] * hist[ch][(pos - k + TAPS) % TAPS];
            if (Math.abs(v) > peak) peak = Math.abs(v);
          }
        }
        pos = (pos + 1) % TAPS;
      }
    }
  }
}

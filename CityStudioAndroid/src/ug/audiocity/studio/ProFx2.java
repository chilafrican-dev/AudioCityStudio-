package ug.audiocity.studio;

import java.util.*;

/**
 * Creative and corrective inserts: phaser, flanger, tremolo / auto-pan, auto-filter, drive, bitcrusher, noise gate,
 * de-esser, vocal doubler, exciter and pitch shifter. All buffers are allocated up front so processing never
 * allocates. Platform independent.
 */
final class ProFx2 {
    private ProFx2() {}
    static final int RATE = Core.RATE;
    static final String PHASER = "phaser", FLANGER = "flanger", TREMOLO = "tremolo", AUTOFILTER = "autofilter", DRIVE = "drive", CRUSHER = "crusher",
            GATE = "gate", DEESSER = "deesser", DOUBLER = "doubler", EXCITER = "exciter", PITCH = "pitch";

    static void kind(Map<String, Pro.Kind> k, String id, String title, String[] names, String[] units, double[] min, double[] max, double[] def) { k.put(id, new Pro.Kind(id, title, names, units, min, max, def)); }
    static void register(Map<String, Pro.Kind> k) {
        kind(k, PHASER, "Phaser", new String[]{"RATE", "DEPTH", "CENTRE", "FEEDBACK", "STAGES", "MIX"}, new String[]{"Hz", "%", "Hz", "%", "", "%"},
                new double[]{.02, 0, 150, 0, 2, 0}, new double[]{8, 100, 5000, 90, 12, 100}, new double[]{.4, 70, 900, 45, 6, 100});
        kind(k, FLANGER, "Flanger", new String[]{"RATE", "DEPTH", "DELAY", "FEEDBACK", "MIX"}, new String[]{"Hz", "ms", "ms", "%", "%"},
                new double[]{.02, 0, .3, -90, 0}, new double[]{5, 8, 10, 90, 100}, new double[]{.25, 2, 1.5, 50, 100});
        kind(k, TREMOLO, "Tremolo · auto-pan", new String[]{"RATE", "DEPTH", "SQUARE WAVE", "AUTO-PAN"}, new String[]{"beats", "%", "", ""},
                new double[]{.0625, 0, 0, 0}, new double[]{4, 100, 1, 1}, new double[]{.5, 60, 0, 0});
        kind(k, AUTOFILTER, "Auto-filter", new String[]{"TYPE", "CUTOFF", "RESONANCE", "LFO RATE", "LFO DEPTH", "ENVELOPE", "ENV RELEASE", "MIX"},
                new String[]{"", "Hz", "", "beats", "oct", "oct", "ms", "%"},
                new double[]{0, 40, .5, .0625, 0, -4, 10, 0}, new double[]{2, 18000, 12, 16, 5, 4, 2000, 100}, new double[]{0, 1200, 2, 2, 1.5, 0, 200, 100});
        kind(k, DRIVE, "Overdrive · distortion", new String[]{"DRIVE", "TYPE", "TONE", "OUTPUT", "MIX"}, new String[]{"dB", "", "Hz", "dB", "%"},
                new double[]{0, 0, 500, -24, 0}, new double[]{36, 3, 20000, 12, 100}, new double[]{12, 0, 8000, -6, 100});
        kind(k, CRUSHER, "Bitcrusher · lo-fi", new String[]{"BITS", "DOWNSAMPLE", "OUTPUT", "MIX"}, new String[]{"bit", "×", "dB", "%"},
                new double[]{2, 1, -24, 0}, new double[]{16, 40, 12, 100}, new double[]{8, 4, 0, 100});
        kind(k, GATE, "Noise gate", new String[]{"THRESHOLD", "RANGE", "ATTACK", "HOLD", "RELEASE", "HYSTERESIS"}, new String[]{"dB", "dB", "ms", "ms", "ms", "dB"},
                new double[]{-90, -90, .05, 0, 5, 0}, new double[]{0, 0, 50, 500, 2000, 12}, new double[]{-45, -60, 1, 40, 120, 4});
        kind(k, DEESSER, "De-esser", new String[]{"FREQUENCY", "THRESHOLD", "MAX CUT", "SPLIT BAND"}, new String[]{"Hz", "dB", "dB", ""},
                new double[]{2000, -60, 0, 0}, new double[]{12000, 0, 24, 1}, new double[]{6000, -28, 10, 1});
        kind(k, DOUBLER, "Vocal doubler", new String[]{"DETUNE", "DELAY", "WIDTH", "MIX"}, new String[]{"cents", "ms", "%", "%"},
                new double[]{0, 5, 0, 0}, new double[]{30, 60, 100, 100}, new double[]{9, 18, 100, 45});
        kind(k, EXCITER, "Exciter", new String[]{"FREQUENCY", "DRIVE", "AMOUNT"}, new String[]{"Hz", "%", "%"},
                new double[]{1000, 0, 0}, new double[]{12000, 100, 100}, new double[]{4000, 50, 25});
        kind(k, PITCH, "Pitch shifter", new String[]{"SEMITONES", "FINE", "WINDOW", "MIX"}, new String[]{"st", "cents", "ms", "%"},
                new double[]{-12, -100, 20, 0}, new double[]{12, 100, 100, 100}, new double[]{0, 0, 50, 100});
    }

    /** One line of help shown under each effect's knobs. */
    static String help(String kind) {
        switch (kind) {
            case PHASER: return "Sweeping notches for guitars, keys and pads. More stages give a deeper sweep; feedback makes it ring.";
            case FLANGER: return "Short modulated delay with feedback: the jet sweep. Negative feedback gives a hollower tone.";
            case TREMOLO: return "Rate follows the song tempo in beats (0.25 = sixteenths, 1 = quarter notes). Auto-pan moves the sound left and right instead of up and down in level.";
            case AUTOFILTER: return "TYPE: 0 low-pass, 1 band-pass, 2 high-pass. LFO rate follows the tempo in beats. ENVELOPE opens the filter as you play harder (negative closes it).";
            case DRIVE: return "TYPE: 0 soft overdrive, 1 hard clip, 2 fuzz, 3 tube. TONE rolls off fizz. Lower OUTPUT as you raise DRIVE.";
            case CRUSHER: return "Fewer bits add grit and noise; DOWNSAMPLE adds metallic aliasing for lo-fi sounds.";
            case GATE: return "Silences the track below the threshold: mic hiss, headphone bleed, drum ring. RANGE sets how far it turns down; HOLD keeps it open between words.";
            case DEESSER: return "Turns down harsh S and T sounds above FREQUENCY. Split band only turns down the highs, so the voice keeps its body.";
            case DOUBLER: return "Two slightly detuned, delayed copies spread left and right, like a double-tracked vocal. Best on lead vocals and guitars.";
            case EXCITER: return "Adds new harmonics above FREQUENCY for air and presence. A little goes a long way.";
            case PITCH: return "Shifts pitch without changing timing: harmonies, octave effects, deep or chipmunk voices. A longer window is smoother; a shorter one reacts faster. For tuning a vocal, use Vocal Tune or the vocal editor.";
            default: return null;
        }
    }

    static Pro.Dsp create(Pro.Insert i) {
        switch (i.kind) {
            case PHASER: return new Phaser(i); case FLANGER: return new Flanger(i); case TREMOLO: return new Tremolo(i);
            case AUTOFILTER: return new AutoFilter(i); case DRIVE: return new Drive(i); case CRUSHER: return new Crusher(i);
            case GATE: return new Gate(i); case DEESSER: return new DeEsser(i); case DOUBLER: return new Doubler(i);
            case EXCITER: return new Exciter(i); case PITCH: return new Pitch(i);
            default: return null;
        }
    }

    static double flush(double v) { return Math.abs(v) < 1e-20 ? 0 : v; }
    static double ms(double v) { return v * .001 * RATE; }
    static double coef(double msTime) { return Math.exp(-1 / Math.max(1, ms(msTime))); }
    static double beatHz(double beats, int bpm) { return bpm / 60.0 / Math.max(1e-3, beats); }

    /** Topology-preserving state-variable filter (stable under fast cutoff modulation). */
    static final class Svf {
        double ic1, ic2, a1, a2, a3, k; double low, band, high;
        void set(double hz, double q) { double g = Math.tan(Math.PI * Math.max(10, Math.min(RATE * .45, hz)) / RATE); k = 1 / Math.max(.3, q); a1 = 1 / (1 + g * (g + k)); a2 = g * a1; a3 = g * a2; }
        void tick(double x) { double v3 = x - ic2, v1 = a1 * ic1 + a2 * v3, v2 = ic2 + a2 * ic1 + a3 * v3; ic1 = flush(2 * v1 - ic1); ic2 = flush(2 * v2 - ic2); low = v2; band = v1; high = x - k * v1 - v2; }
    }
    /** Fractional delay line read with linear interpolation. */
    static final class Line {
        final float[] d; int w; Line(int size) { d = new float[size]; }
        void put(double x) { d[w] = (float) x; w = (w + 1) % d.length; }
        double get(double delay) { double p = w - 1 - Math.max(0, Math.min(d.length - 2, delay)); while (p < 0) p += d.length; int i = (int) p; double f = p - i; return d[i] * (1 - f) + d[(i + 1) % d.length] * f; }
    }

    static final class Phaser extends Pro.Dsp {
        static final int MAX = 12; final double[][] z = new double[2][MAX]; final double[] fb = new double[2]; double phase, rate, depth, centre, feedback, mix; int stages; final double[] a = new double[2];
        Phaser(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; rate = v[0]; depth = v[1] / 100; centre = v[2]; feedback = v[3] / 100; stages = Math.max(2, Math.min(MAX, (int) Math.round(v[4] / 2) * 2)); mix = v[5] / 100; }
        void run(float[] s, int n, int bpm) {
            for (int i = 0; i < n; i++) {
                if ((i & 15) == 0) for (int c = 0; c < 2; c++) { double lfo = Math.sin(phase + c * Math.PI / 2), hz = centre * Math.pow(2, lfo * depth * 2.5), t = Math.tan(Math.PI * Math.min(RATE * .45, Math.max(20, hz)) / RATE); a[c] = (t - 1) / (t + 1); }
                phase += 2 * Math.PI * rate / RATE; if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                for (int c = 0; c < 2; c++) {
                    double x = s[2 * i + c], y = x + fb[c] * feedback;
                    for (int k = 0; k < stages; k++) { double out = a[c] * y + z[c][k]; z[c][k] = flush(y - a[c] * out); y = out; }
                    fb[c] = flush(y); s[2 * i + c] = (float) (x * (1 - mix) + (x + y) * .5 * mix); // dry + all-passed sum makes the moving notches
                }
            }
        }
    }

    static final class Flanger extends Pro.Dsp {
        final Line[] line = { new Line(RATE / 40), new Line(RATE / 40) }; final double[] fb = new double[2]; double phase, rate, depth, delay, feedback, mix;
        Flanger(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; rate = v[0]; depth = ms(v[1]); delay = ms(v[2]); feedback = v[3] / 100; mix = v[4] / 100; }
        void run(float[] s, int n, int bpm) {
            for (int i = 0; i < n; i++) {
                phase += 2 * Math.PI * rate / RATE; if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                for (int c = 0; c < 2; c++) {
                    double x = s[2 * i + c], d = delay + depth * .5 * (1 + Math.sin(phase + c * Math.PI / 2)), wet = line[c].get(d);
                    line[c].put(x + wet * feedback); s[2 * i + c] = (float) (x * (1 - mix) + (x + wet) * .5 * mix);
                }
            }
        }
    }

    static final class Tremolo extends Pro.Dsp {
        double phase, depth, shape; boolean pan;
        Tremolo(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; depth = v[1] / 100; shape = v[2]; pan = v[3] >= .5; }
        void run(float[] s, int n, int bpm) {
            double inc = 2 * Math.PI * beatHz(insert.values[0], bpm) / RATE;
            for (int i = 0; i < n; i++) {
                phase += inc; if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                double sine = Math.sin(phase), square = Math.tanh(sine * 6) / Math.tanh(6), lfo = sine * (1 - shape) + square * shape; // -1..1
                if (pan) { double p = lfo * depth, l = Math.cos((p + 1) * Math.PI / 4) * Math.sqrt(2), r = Math.sin((p + 1) * Math.PI / 4) * Math.sqrt(2); s[2 * i] *= (float) l; s[2 * i + 1] *= (float) r; }
                else { float g = (float) (1 - depth * .5 * (1 - lfo)); s[2 * i] *= g; s[2 * i + 1] *= g; }
            }
        }
    }

    static final class AutoFilter extends Pro.Dsp {
        final Svf[] f = { new Svf(), new Svf() }; double phase, env, type, cutoff, q, depth, amount, rel, mix;
        AutoFilter(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; type = Math.round(v[0]); cutoff = v[1]; q = v[2]; depth = v[4]; amount = v[5]; rel = coef(v[6]); mix = v[7] / 100; }
        void run(float[] s, int n, int bpm) {
            double inc = 2 * Math.PI * beatHz(insert.values[3], bpm) / RATE;
            for (int i = 0; i < n; i++) {
                double l = s[2 * i], r = s[2 * i + 1], level = Math.max(Math.abs(l), Math.abs(r)); env = level > env ? env + (level - env) * .02 : env * rel;
                phase += inc; if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                if ((i & 7) == 0) { double hz = cutoff * Math.pow(2, Math.sin(phase) * depth + Math.min(1, env * 2) * amount); f[0].set(hz, q); f[1].set(hz, q); }
                for (int c = 0; c < 2; c++) { double x = c == 0 ? l : r; f[c].tick(x); double y = type == 0 ? f[c].low : type == 1 ? f[c].band : f[c].high; s[2 * i + c] = (float) (x * (1 - mix) + y * mix); }
            }
        }
    }

    static final class Drive extends Pro.Dsp {
        final Pro.Filter[] tone = { new Pro.Filter(), new Pro.Filter() }; final double[] dcIn = new double[2], dcOut = new double[2]; double pre, post, mix; int type;
        Drive(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; pre = Pro.gain(v[0]); type = (int) Math.round(v[1]); post = Pro.gain(v[3]); mix = v[4] / 100; for (Pro.Filter t : tone) t.set(4, v[2], 0, .707); }
        static double shape(double x, int type) {
            switch (type) {
                case 1: return Math.max(-1, Math.min(1, x)) * .9;                                   // hard clip
                case 2: { double y = Math.tanh(x * 1.5 + .25) - Math.tanh(.25); return y * .8; }   // fuzz (asymmetric, strong)
                case 3: return x >= 0 ? 1 - Math.exp(-x) : -(1 - Math.exp(x * 1.4)) / 1.4 * 1.2;    // tube-style asymmetric
                default: return Math.tanh(x);                                                      // soft
            }
        }
        void run(float[] s, int n, int bpm) {
            for (int i = 0; i < n; i++) for (int c = 0; c < 2; c++) {
                double x = s[2 * i + c], y = shape(x * pre, type); y = tone[c].tick(y);
                double dc = y - dcIn[c] + .995 * dcOut[c]; dcIn[c] = y; dcOut[c] = flush(dc); // DC blocker for the asymmetric curves
                s[2 * i + c] = (float) (x * (1 - mix) + dc * post * mix);
            }
        }
    }

    static final class Crusher extends Pro.Dsp {
        final double[] held = new double[2]; double count, step, factor, post, mix;
        Crusher(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; step = 2 / Math.pow(2, v[0]); factor = v[1]; post = Pro.gain(v[2]); mix = v[3] / 100; }
        void run(float[] s, int n, int bpm) {
            for (int i = 0; i < n; i++) {
                count += 1; boolean take = count >= factor; if (take) count -= factor;
                for (int c = 0; c < 2; c++) { double x = s[2 * i + c]; if (take || factor <= 1) held[c] = Math.round(x / step) * step; s[2 * i + c] = (float) ((x * (1 - mix) + held[c] * mix) * post); }
            }
        }
    }

    static final class Gate extends Pro.Dsp {
        double env, gain = 0, open, close, floor, att, rel; int hold, held; boolean on;
        Gate(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; open = Pro.gain(v[0]); close = Pro.gain(v[0] - v[5]); floor = Pro.gain(v[1]); att = coef(v[2]); hold = (int) ms(v[3]); rel = coef(v[4]); }
        void run(float[] s, int n, int bpm) {
            for (int i = 0; i < n; i++) {
                double level = Math.max(Math.abs(s[2 * i]), Math.abs(s[2 * i + 1])); env = Math.max(level, env * .9995);
                if (env >= open) { on = true; held = hold; } else if (env < close) { if (held > 0) held--; else on = false; }
                double target = on ? 1 : floor; gain = target > gain ? target + (gain - target) * att : target + (gain - target) * rel;
                s[2 * i] *= (float) gain; s[2 * i + 1] *= (float) gain;
            }
        }
        public double reduction() { return -Pro.db(gain); }
    }

    static final class DeEsser extends Pro.Dsp {
        final Svf[] f = { new Svf(), new Svf() }; double env, gr, th, range; boolean split;
        DeEsser(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; f[0].set(v[0], .7); f[1].set(v[0], .7); th = v[1]; range = v[2]; split = v[3] >= .5; }
        void run(float[] s, int n, int bpm) {
            double att = coef(.5), rel = coef(60);
            for (int i = 0; i < n; i++) {
                f[0].tick(s[2 * i]); f[1].tick(s[2 * i + 1]); double hl = s[2 * i] - f[0].low, hr = s[2 * i + 1] - f[1].low, level = Math.max(Math.abs(hl), Math.abs(hr));
                env = level > env ? level + (env - level) * att : level + (env - level) * rel;
                double want = Math.min(range, Math.max(0, (Pro.db(env + 1e-9) - th) * .75)); gr = want > gr ? want + (gr - want) * att : want + (gr - want) * rel; double g = Pro.gain(-gr);
                if (split) { s[2 * i] = (float) (s[2 * i] - hl * (1 - g)); s[2 * i + 1] = (float) (s[2 * i + 1] - hr * (1 - g)); } else { s[2 * i] *= (float) g; s[2 * i + 1] *= (float) g; }
            }
        }
        public double reduction() { return gr; }
    }

    /** Two delayed, slowly pitch-wandering copies panned apart: an artificial double-track. */
    static final class Doubler extends Pro.Dsp {
        final Line line = new Line(RATE / 8); double pa, pb, delay, swing, width, mix;
        Doubler(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; delay = ms(v[1]); double w = 2 * Math.PI * .37; swing = (Math.pow(2, v[0] / 1200) - 1) / w * RATE; swing = Math.min(swing, delay * .8); width = v[2] / 100; mix = v[3] / 100; }
        void run(float[] s, int n, int bpm) {
            for (int i = 0; i < n; i++) {
                double l = s[2 * i], r = s[2 * i + 1], m = (l + r) * .5; line.put(m);
                pa += 2 * Math.PI * .37 / RATE; pb += 2 * Math.PI * .29 / RATE; if (pa > 2 * Math.PI) pa -= 2 * Math.PI; if (pb > 2 * Math.PI) pb -= 2 * Math.PI;
                double a = line.get(delay + swing * Math.sin(pa)), b = line.get(delay * 1.37 + swing * Math.sin(pb + 1.3));
                double left = a * (.5 + .5 * width) + b * (.5 - .5 * width), right = b * (.5 + .5 * width) + a * (.5 - .5 * width);
                s[2 * i] = (float) (l + left * mix * .7); s[2 * i + 1] = (float) (r + right * mix * .7);
            }
        }
    }

    static final class Exciter extends Pro.Dsp {
        final Svf[] in = { new Svf(), new Svf() }, out = { new Svf(), new Svf() }; double drive, amount;
        Exciter(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; for (int c = 0; c < 2; c++) { in[c].set(v[0], .7); out[c].set(v[0], .7); } drive = 1 + v[1] / 100 * 15; amount = v[2] / 100; }
        void run(float[] s, int n, int bpm) {
            for (int i = 0; i < n; i++) for (int c = 0; c < 2; c++) {
                double x = s[2 * i + c]; in[c].tick(x); double h = Math.tanh(in[c].high * drive) / Math.sqrt(drive); out[c].tick(h); s[2 * i + c] = (float) (x + out[c].high * amount);
            }
        }
    }

    /** Two-tap rotating-delay pitch shifter with Hann crossfades; good for ±12 semitones and harmonies. */
    static final class Pitch extends Pro.Dsp {
        final Line[] line = { new Line(RATE / 8), new Line(RATE / 8) }; double pos, window, ratio, mix;
        Pitch(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; ratio = Math.pow(2, (v[0] + v[1] / 100) / 12); window = ms(v[2]); mix = v[3] / 100; if (pos >= window) pos = 0; }
        void run(float[] s, int n, int bpm) {
            if (Math.abs(ratio - 1) < 1e-9 && mix >= 1) return;
            for (int i = 0; i < n; i++) {
                pos += 1 - ratio; while (pos < 0) pos += window; while (pos >= window) pos -= window;
                double p2 = pos + window / 2; if (p2 >= window) p2 -= window; double g1 = .5 - .5 * Math.cos(2 * Math.PI * pos / window), g2 = 1 - g1;
                for (int c = 0; c < 2; c++) { double x = s[2 * i + c]; line[c].put(x); double y = line[c].get(pos + 1) * g1 + line[c].get(p2 + 1) * g2; s[2 * i + c] = (float) (x * (1 - mix) + y * mix); }
            }
        }
    }
}

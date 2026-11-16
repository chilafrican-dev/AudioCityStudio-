package ug.audiocity.studio;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Professional-workstation engine features. Platform independent (no Android imports) so it can be
 * tested on a host JVM: loudness and true-peak metering, a look-ahead limiter, an 8-band parametric
 * EQ, a 3-band multiband compressor, insert slots, automation lanes, buses and sends, take comping,
 * crossfades, slip/ripple edits, a tempo map with markers, latency measurement, an SPSC ring buffer,
 * MIDI note editing and a FLAC encoder.
 */
public final class Pro {
    private Pro() {}
    static final int RATE = Core.RATE;

    static double db(double gain) { return 20 * Math.log10(Math.max(1e-12, gain)); }
    static double gain(double db) { return Math.pow(10, db / 20); }
    static double clamp(double v, double lo, double hi) { return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo; }

    // ------------------------------------------------------------------ filters

    /** RBJ biquad with arbitrary Q. Types: 0 bell, 1 low shelf, 2 high shelf, 3 low cut, 4 high cut, 5 notch, 6 band pass. */
    public static final class Filter {
        double b0 = 1, b1, b2, a1, a2, z1, z2;
        public Filter set(int type, double hz, double gainDb, double q) {
            hz = clamp(hz, 10, RATE * 0.49);
            q = clamp(q, 0.1, 24);
            double A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * hz / RATE, cs = Math.cos(w), sn = Math.sin(w), alpha = sn / (2 * q), a0;
            switch (type) {
                case 1: { double s = 2 * Math.sqrt(A) * alpha;
                    b0 = A * ((A + 1) - (A - 1) * cs + s); b1 = 2 * A * ((A - 1) - (A + 1) * cs); b2 = A * ((A + 1) - (A - 1) * cs - s);
                    a0 = (A + 1) + (A - 1) * cs + s; a1 = -2 * ((A - 1) + (A + 1) * cs); a2 = (A + 1) + (A - 1) * cs - s; break; }
                case 2: { double s = 2 * Math.sqrt(A) * alpha;
                    b0 = A * ((A + 1) + (A - 1) * cs + s); b1 = -2 * A * ((A - 1) + (A + 1) * cs); b2 = A * ((A + 1) + (A - 1) * cs - s);
                    a0 = (A + 1) - (A - 1) * cs + s; a1 = 2 * ((A - 1) - (A + 1) * cs); a2 = (A + 1) - (A - 1) * cs - s; break; }
                case 3: b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = b0; a0 = 1 + alpha; a1 = -2 * cs; a2 = 1 - alpha; break;
                case 4: b0 = (1 - cs) / 2; b1 = 1 - cs; b2 = b0; a0 = 1 + alpha; a1 = -2 * cs; a2 = 1 - alpha; break;
                case 5: b0 = 1; b1 = -2 * cs; b2 = 1; a0 = 1 + alpha; a1 = -2 * cs; a2 = 1 - alpha; break;
                case 6: b0 = alpha; b1 = 0; b2 = -alpha; a0 = 1 + alpha; a1 = -2 * cs; a2 = 1 - alpha; break;
                default: b0 = 1 + alpha * A; b1 = -2 * cs; b2 = 1 - alpha * A; a0 = 1 + alpha / A; a1 = -2 * cs; a2 = 1 - alpha / A;
            }
            b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0;
            return this;
        }
        public Filter coefficients(double b0, double b1, double b2, double a1, double a2) { this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2; return this; }
        public double tick(double x) { double y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; if (Math.abs(z1) < 1e-25) z1 = 0; if (Math.abs(z2) < 1e-25) z2 = 0; return y; }
        public void reset() { z1 = z2 = 0; }
        /** Magnitude response in dB. */
        public double db(double hz) {
            double w = 2 * Math.PI * hz / RATE, c1 = Math.cos(w), c2 = Math.cos(2 * w), s1 = Math.sin(w), s2 = Math.sin(2 * w);
            double nr = b0 + b1 * c1 + b2 * c2, ni = -b1 * s1 - b2 * s2, dr = 1 + a1 * c1 + a2 * c2, di = -a1 * s1 - a2 * s2;
            return 10 * Math.log10(Math.max(1e-20, (nr * nr + ni * ni) / (dr * dr + di * di)));
        }
    }

    /** 4th-order Linkwitz–Riley section (two cascaded Butterworth biquads). */
    static final class LR4 {
        final Filter a = new Filter(), b = new Filter();
        LR4(boolean high, double hz) { a.set(high ? 3 : 4, hz, 0, Math.sqrt(0.5)); b.set(high ? 3 : 4, hz, 0, Math.sqrt(0.5)); }
        double tick(double x) { return b.tick(a.tick(x)); }
    }

    // ------------------------------------------------------------------ loudness (ITU-R BS.1770-4 / EBU R128)

    /** Integrated, short-term and momentary loudness in LUFS, loudness range and true peak in dBTP. */
    public static final class Loudness {
        final Filter[] shelf = new Filter[2], highPass = new Filter[2];
        final TruePeak[] peaks = { new TruePeak(), new TruePeak() };
        final double[] blocks100 = new double[30]; // ring of 100 ms mean-square sums (last 3 s)
        int written, inBlock; double acc;
        final ArrayList<Double> gated = new ArrayList<>(); // 400 ms block energies, for integrated loudness
        final ArrayList<Double> shortTerm = new ArrayList<>(); // 3 s energies every 100 ms, for LRA
        public volatile double momentary = Double.NEGATIVE_INFINITY, shortTermLufs = Double.NEGATIVE_INFINITY, truePeakDb = Double.NEGATIVE_INFINITY;
        public Loudness() {
            for (int c = 0; c < 2; c++) {
                shelf[c] = new Filter().coefficients(1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585);
                highPass[c] = new Filter().coefficients(1.0, -2.0, 1.0, -1.99004745483398, 0.99007225036621);
            }
        }
        public synchronized void reset() { Loudness n = new Loudness(); for (int c = 0; c < 2; c++) { shelf[c] = n.shelf[c]; highPass[c] = n.highPass[c]; peaks[c] = new TruePeak(); } Arrays.fill(blocks100, 0); written = inBlock = 0; acc = 0; gated.clear(); shortTerm.clear(); momentary = shortTermLufs = truePeakDb = Double.NEGATIVE_INFINITY; }
        /** Feeds interleaved stereo frames. */
        public synchronized void feed(float[] stereo, int frames) {
            double tp = 0;
            for (int i = 0; i < frames; i++) {
                double l = stereo[2 * i], r = stereo[2 * i + 1];
                tp = Math.max(tp, Math.max(peaks[0].tick(l), peaks[1].tick(r)));
                double kl = highPass[0].tick(shelf[0].tick(l)), kr = highPass[1].tick(shelf[1].tick(r));
                acc += kl * kl + kr * kr;
                if (++inBlock == RATE / 10) {
                    blocks100[written % 30] = acc / inBlock; written++; acc = 0; inBlock = 0;
                    if (written >= 4) { double e = 0; for (int k = 1; k <= 4; k++) e += blocks100[(written - k) % 30]; e /= 4; momentary = lufs(e); gated.add(e); }
                    if (written >= 30) { double e = 0; for (int k = 0; k < 30; k++) e += blocks100[k]; e /= 30; shortTermLufs = lufs(e); shortTerm.add(e); }
                    else if (written >= 4) { double e = 0; for (int k = 0; k < written; k++) e += blocks100[k]; shortTermLufs = lufs(e / written); }
                }
            }
            double d = db(tp);
            if (d > truePeakDb) truePeakDb = d;
        }
        static double lufs(double meanSquare) { return meanSquare <= 0 ? Double.NEGATIVE_INFINITY : -0.691 + 10 * Math.log10(meanSquare); }
        /** Gated integrated loudness; −∞ until at least 400 ms of non-silent programme. */
        public synchronized double integrated() { return gatedLoudness(gated, -10); }
        /** EBU Tech 3342 loudness range in LU. */
        public synchronized double range() {
            ArrayList<Double> abs = new ArrayList<>();
            for (double e : shortTerm) if (lufs(e) > -70) abs.add(e);
            if (abs.size() < 2) return 0;
            double mean = 0; for (double e : abs) mean += e; mean /= abs.size();
            double gate = lufs(mean) - 20; ArrayList<Double> rel = new ArrayList<>();
            for (double e : abs) if (lufs(e) > gate) rel.add(lufs(e));
            if (rel.size() < 2) return 0;
            Collections.sort(rel);
            return rel.get((int) Math.round(0.95 * (rel.size() - 1))) - rel.get((int) Math.round(0.10 * (rel.size() - 1)));
        }
        static double gatedLoudness(List<Double> blocks, double relative) {
            double sum = 0; int n = 0;
            for (double e : blocks) if (lufs(e) > -70) { sum += e; n++; }
            if (n == 0) return Double.NEGATIVE_INFINITY;
            double gate = lufs(sum / n) + relative; sum = 0; n = 0;
            for (double e : blocks) if (lufs(e) > -70 && lufs(e) > gate) { sum += e; n++; }
            return n == 0 ? Double.NEGATIVE_INFINITY : lufs(sum / n);
        }
    }

    /** 4× oversampling peak estimator (48-tap windowed-sinc polyphase interpolator). */
    public static final class TruePeak {
        static final int TAPS = 12, PHASES = 4; static final double[][] H = new double[PHASES][TAPS];
        static {
            for (int p = 0; p < PHASES; p++) for (int k = 0; k < TAPS; k++) {
                double t = k - TAPS / 2.0 + 1 - p / (double) PHASES; // fractional offset
                double sinc = Math.abs(t) < 1e-9 ? 1 : Math.sin(Math.PI * t) / (Math.PI * t);
                double win = 0.5 + 0.5 * Math.cos(Math.PI * t / (TAPS / 2.0));
                H[p][k] = sinc * Math.max(0, win);
            }
            for (int p = 0; p < PHASES; p++) { double s = 0; for (double h : H[p]) s += h; for (int k = 0; k < TAPS; k++) H[p][k] /= s; }
        }
        final double[] hist = new double[TAPS]; int pos;
        /** Returns the largest absolute value among the four interpolated points ending at this sample. */
        public double tick(double x) {
            hist[pos] = x; pos = (pos + 1) % TAPS;
            double peak = Math.abs(x);
            for (int p = 1; p < PHASES; p++) { double y = 0; for (int k = 0; k < TAPS; k++) y += H[p][k] * hist[(pos + k) % TAPS]; peak = Math.max(peak, Math.abs(y)); }
            return peak;
        }
    }

    // ------------------------------------------------------------------ insert slots

    /** Insert kinds and their parameter metadata. */
    public static final class Kind {
        public final String id, title; public final String[] names, units; public final double[] min, max, defaults;
        Kind(String id, String title, String[] names, String[] units, double[] min, double[] max, double[] defaults) { this.id = id; this.title = title; this.names = names; this.units = units; this.min = min; this.max = max; this.defaults = defaults; }
    }
    public static final String EQ8 = "eq8", MULTIBAND = "multiband", LIMITER = "limiter", UTILITY = "utility", CHORUS = "chorus";
    public static final int EQ_BANDS = 8;
    public static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static {
        String[] n = new String[EQ_BANDS * 5], u = new String[n.length]; double[] mn = new double[n.length], mx = new double[n.length], df = new double[n.length];
        double[] freqs = { 40, 100, 250, 600, 1500, 3500, 8000, 14000 };
        for (int b = 0; b < EQ_BANDS; b++) {
            int o = b * 5;
            n[o] = "B" + (b + 1) + " ON"; u[o] = ""; mn[o] = 0; mx[o] = 1; df[o] = b == 0 || b == 7 ? 0 : 1;
            n[o + 1] = "B" + (b + 1) + " TYPE"; u[o + 1] = ""; mn[o + 1] = 0; mx[o + 1] = 5; df[o + 1] = b == 0 ? 3 : b == 7 ? 4 : b == 1 ? 1 : b == 6 ? 2 : 0;
            n[o + 2] = "B" + (b + 1) + " FREQ"; u[o + 2] = "Hz"; mn[o + 2] = 20; mx[o + 2] = 20000; df[o + 2] = freqs[b];
            n[o + 3] = "B" + (b + 1) + " GAIN"; u[o + 3] = "dB"; mn[o + 3] = -18; mx[o + 3] = 18; df[o + 3] = 0;
            n[o + 4] = "B" + (b + 1) + " Q"; u[o + 4] = ""; mn[o + 4] = 0.2; mx[o + 4] = 12; df[o + 4] = 0.9;
        }
        KINDS.put(EQ8, new Kind(EQ8, "Parametric EQ · 8 band", n, u, mn, mx, df));
        KINDS.put(MULTIBAND, new Kind(MULTIBAND, "Multiband compressor · 3 band",
                new String[]{"LOW/MID", "MID/HIGH", "LOW THR", "LOW RATIO", "LOW GAIN", "MID THR", "MID RATIO", "MID GAIN", "HIGH THR", "HIGH RATIO", "HIGH GAIN", "ATTACK", "RELEASE", "MIX"},
                new String[]{"Hz", "Hz", "dB", ":1", "dB", "dB", ":1", "dB", "dB", ":1", "dB", "ms", "ms", "%"},
                new double[]{40, 800, -48, 1, -12, -48, 1, -12, -48, 1, -12, 0.1, 10, 0},
                new double[]{800, 12000, 0, 10, 12, 0, 10, 12, 0, 10, 12, 100, 1000, 100},
                new double[]{180, 3200, -18, 2, 0, -18, 2, 0, -18, 2, 0, 10, 150, 100}));
        KINDS.put(LIMITER, new Kind(LIMITER, "True-peak limiter",
                new String[]{"INPUT", "CEILING", "RELEASE", "LOOKAHEAD"}, new String[]{"dB", "dBTP", "ms", "ms"},
                new double[]{0, -6, 5, 0.5}, new double[]{18, 0, 1000, 5}, new double[]{0, -1, 80, 1.5}));
        KINDS.put(UTILITY, new Kind(UTILITY, "Utility · gain, width, phase",
                new String[]{"GAIN", "WIDTH", "INVERT L", "INVERT R", "MONO BASS"}, new String[]{"dB", "%", "", "", "Hz"},
                new double[]{-24, 0, 0, 0, 0}, new double[]{24, 200, 1, 1, 300}, new double[]{0, 100, 0, 0, 0}));
        KINDS.put(CHORUS, new Kind(CHORUS, "Stereo chorus",
                new String[]{"RATE", "DEPTH", "DELAY", "MIX"}, new String[]{"Hz", "ms", "ms", "%"},
                new double[]{0.05, 0, 3, 0}, new double[]{5, 10, 30, 100}, new double[]{0.6, 3, 12, 35}));
        ProFx.register(KINDS);
    }

    /** One insert slot. Parameters are a flat array so the UI and project format can treat every kind alike. */
    public static final class Insert {
        public final String id; public final String kind; public volatile boolean enabled = true; public final double[] values;
        public Insert(String kind) { this(UUID.randomUUID().toString(), kind, KINDS.get(kind).defaults.clone()); }
        public Insert(String id, String kind, double[] values) {
            Kind k = KINDS.get(kind); if (k == null) throw new IllegalArgumentException("Unknown insert " + kind);
            this.id = id; this.kind = kind; this.values = new double[k.defaults.length];
            for (int i = 0; i < this.values.length; i++) this.values[i] = i < values.length ? clamp(values[i], k.min[i], k.max[i]) : k.defaults[i];
        }
        public Insert copy() { Insert c = new Insert(id, kind, values); c.enabled = enabled; return c; }
        public Kind info() { return KINDS.get(kind); }
        public void set(int index, double value) { Kind k = info(); values[index] = clamp(value, k.min[index], k.max[index]); }
        /** Parameter change counter so DSP can update coefficients lazily. */
        public volatile int revision;
        public void touch() { revision++; }
        public Dsp create() {
            Dsp extra = ProFx.create(this); if (extra != null) return extra;
            switch (kind) {
                case EQ8: return new EqDsp(this);
                case MULTIBAND: return new MultibandDsp(this);
                case LIMITER: return new LimiterDsp(this);
                case CHORUS: return new ChorusDsp(this);
                default: return new UtilityDsp(this);
            }
        }
        /** Combined magnitude response in dB (EQ only). */
        public double response(double hz) {
            if (!kind.equals(EQ8)) return 0;
            double sum = 0; Filter f = new Filter();
            for (int b = 0; b < EQ_BANDS; b++) if (values[b * 5] >= .5) { eqBand(f, values, b); sum += f.db(hz) * (((int) values[b * 5 + 1] == 3 || (int) values[b * 5 + 1] == 4) ? 2 : 1); }
            return sum;
        }
    }

    static void eqBand(Filter f, double[] v, int band) {
        int o = band * 5, type = (int) Math.round(v[o + 1]);
        // UI types: 0 bell, 1 low shelf, 2 high shelf, 3 low cut (24 dB/oct), 4 high cut (24 dB/oct), 5 notch
        int filterType = type == 0 ? 0 : type == 1 ? 1 : type == 2 ? 2 : type == 3 ? 3 : type == 4 ? 4 : 5;
        double q = type == 3 || type == 4 ? Math.sqrt(0.5) * Math.max(.5, Math.min(2, v[o + 4] / .9)) : v[o + 4];
        f.set(filterType, v[o + 2], v[o + 3], q);
    }

    /** Running DSP state for an insert; one per renderer. */
    public abstract static class Dsp {
        final Insert insert; int revision = -1;
        Dsp(Insert i) { insert = i; }
        /** Processes interleaved stereo in place. */
        public final void process(float[] audio, int frames, int bpm) { if (revision != insert.revision) { revision = insert.revision; update(); } run(audio, frames, bpm); }
        abstract void update();
        abstract void run(float[] audio, int frames, int bpm);
        /** Current gain reduction in dB, for meters. */
        public double reduction() { return 0; }
        /** Delay added by this processor, in frames. */
        public int latency() { return 0; }
    }

    static final class EqDsp extends Dsp {
        final Filter[][] f = new Filter[2][EQ_BANDS * 2]; final boolean[] on = new boolean[EQ_BANDS]; final boolean[] steep = new boolean[EQ_BANDS];
        EqDsp(Insert i) { super(i); for (int c = 0; c < 2; c++) for (int k = 0; k < f[c].length; k++) f[c][k] = new Filter(); }
        void update() {
            double[] v = insert.values.clone();
            for (int b = 0; b < EQ_BANDS; b++) {
                on[b] = v[b * 5] >= .5; int type = (int) Math.round(v[b * 5 + 1]); steep[b] = type == 3 || type == 4;
                for (int c = 0; c < 2; c++) {
                    Filter a = f[c][2 * b], s = f[c][2 * b + 1]; double z1 = a.z1, z2 = a.z2, y1 = s.z1, y2 = s.z2;
                    eqBand(a, v, b); eqBand(s, v, b); a.z1 = z1; a.z2 = z2; s.z1 = y1; s.z2 = y2;
                }
            }
        }
        void run(float[] audio, int frames, int bpm) {
            for (int i = 0; i < frames; i++) for (int c = 0; c < 2; c++) {
                double x = audio[2 * i + c];
                for (int b = 0; b < EQ_BANDS; b++) if (on[b]) { x = f[c][2 * b].tick(x); if (steep[b]) x = f[c][2 * b + 1].tick(x); }
                audio[2 * i + c] = (float) (Double.isFinite(x) ? x : 0);
            }
        }
    }

    static final class MultibandDsp extends Dsp {
        LR4[] lowLp = new LR4[2], lowHp = new LR4[2], midLp = new LR4[2], midHp = new LR4[2], apLp = new LR4[2], apHp = new LR4[2];
        final double[] env = new double[3], gr = { 1, 1, 1 }; final double[][] bands = new double[3][2]; double[] v; double attack, release; double lastLow = -1, lastHigh = -1;
        MultibandDsp(Insert i) { super(i); }
        void update() {
            v = insert.values.clone();
            double low = v[0], high = Math.max(v[1], v[0] * 1.5);
            if (low != lastLow || high != lastHigh) {
                for (int c = 0; c < 2; c++) { lowLp[c] = new LR4(false, low); lowHp[c] = new LR4(true, low); midLp[c] = new LR4(false, high); midHp[c] = new LR4(true, high); apLp[c] = new LR4(false, high); apHp[c] = new LR4(true, high); }
                lastLow = low; lastHigh = high;
            }
            attack = Math.exp(-1.0 / (Math.max(.05, v[11]) * .001 * RATE)); release = Math.exp(-1.0 / (v[12] * .001 * RATE));
        }
        void run(float[] audio, int frames, int bpm) {
            double mix = v[13] / 100;
            for (int i = 0; i < frames; i++) {
                for (int c = 0; c < 2; c++) {
                    double x = audio[2 * i + c], lo = lowLp[c].tick(x), rest = lowHp[c].tick(x);
                    lo = apLp[c].tick(lo) + apHp[c].tick(lo); // phase-align the low band with the upper split
                    bands[0][c] = lo; bands[1][c] = midLp[c].tick(rest); bands[2][c] = midHp[c].tick(rest);
                }
                for (int b = 0; b < 3; b++) {
                    double level = Math.max(Math.abs(bands[b][0]), Math.abs(bands[b][1])), k = level > env[b] ? attack : release;
                    env[b] = k * env[b] + (1 - k) * level;
                    double th = gain(v[2 + b * 3]), ratio = v[3 + b * 3], target = env[b] > th ? Math.pow(env[b] / th, 1 / ratio - 1) : 1;
                    gr[b] = target;
                }
                for (int c = 0; c < 2; c++) {
                    double wet = 0; for (int b = 0; b < 3; b++) wet += bands[b][c] * gr[b] * gain(v[4 + b * 3]);
                    double dry = audio[2 * i + c], y = dry * (1 - mix) + wet * mix;
                    audio[2 * i + c] = (float) (Double.isFinite(y) ? y : 0);
                }
            }
        }
        public double reduction() { double m = 1; for (double g : gr) m = Math.min(m, g); return -db(m); }
    }

    /**
     * Look-ahead brick-wall limiter with 4× true-peak detection. A sliding minimum of the required gain is smoothed by a
     * box filter as long as the look-ahead, so the gain is already down when a peak leaves the delay line.
     */
    public static final class LimiterDsp extends Dsp {
        static final int DETECT = TruePeak.TAPS / 2; // the interpolator reports peaks this many samples late
        float[] delay = new float[2]; int delayLength, write, box = 1, window;
        double[] queueValue = new double[1]; long[] queueIndex = new long[1]; int qHead, qSize; long counter;
        double[] boxRing = new double[1]; int boxPos; double boxSum;
        final TruePeak[] detect = { new TruePeak(), new TruePeak() }; double gainNow = 1, smooth = 1, ceiling = 1, input = 1, releaseCoef;
        public LimiterDsp(Insert i) { super(i); }
        void update() {
            double[] v = insert.values;
            input = gain(v[0]); ceiling = gain(v[1]); releaseCoef = Math.exp(-1.0 / (v[2] * .001 * RATE));
            int wanted = Math.max(1, (int) Math.round(v[3] * .001 * RATE));
            if (wanted != box) { box = wanted; delayLength = box + DETECT; window = delayLength + 1;
                delay = new float[delayLength * 2]; write = 0; queueValue = new double[window + 1]; queueIndex = new long[window + 1]; qHead = qSize = 0;
                boxRing = new double[box]; Arrays.fill(boxRing, 1); boxSum = box; boxPos = 0; smooth = 1; }
            else if (delayLength == 0) { box = -1; update(); }
        }
        void run(float[] audio, int frames, int bpm) {
            int cap = queueValue.length;
            for (int i = 0; i < frames; i++) {
                double l = audio[2 * i] * input, r = audio[2 * i + 1] * input;
                double peak = Math.max(detect[0].tick(l), detect[1].tick(r)), need = peak > ceiling ? ceiling / peak : 1;
                while (qSize > 0 && queueValue[(qHead + qSize - 1) % cap] >= need) qSize--;
                queueValue[(qHead + qSize) % cap] = need; queueIndex[(qHead + qSize) % cap] = counter; qSize++;
                while (queueIndex[qHead] <= counter - window) { qHead = (qHead + 1) % cap; qSize--; }
                double minimum = queueValue[qHead];
                smooth = minimum < smooth ? minimum : minimum + (smooth - minimum) * releaseCoef;
                boxSum += smooth - boxRing[boxPos]; boxRing[boxPos] = smooth; boxPos = (boxPos + 1) % box;
                double g = Math.min(smooth, boxSum / box); gainNow = g;
                double dl = delay[2 * write], dr = delay[2 * write + 1];
                delay[2 * write] = (float) l; delay[2 * write + 1] = (float) r; write = (write + 1) % delayLength;
                audio[2 * i] = (float) Math.max(-ceiling, Math.min(ceiling, dl * g));
                audio[2 * i + 1] = (float) Math.max(-ceiling, Math.min(ceiling, dr * g));
                counter++;
            }
        }
        public double reduction() { return -db(gainNow); }
        public int latency() { return delayLength; }
    }

    static final class UtilityDsp extends Dsp {
        double g, width, invL, invR; Filter[] lowL = new Filter[2]; double monoHz;
        UtilityDsp(Insert i) { super(i); }
        void update() {
            double[] v = insert.values; g = gain(v[0]); width = v[1] / 100; invL = v[2] >= .5 ? -1 : 1; invR = v[3] >= .5 ? -1 : 1;
            if (v[4] != monoHz) { monoHz = v[4]; lowL[0] = new Filter().set(4, Math.max(20, monoHz), 0, .707); lowL[1] = new Filter().set(4, Math.max(20, monoHz), 0, .707); }
        }
        void run(float[] audio, int frames, int bpm) {
            for (int i = 0; i < frames; i++) {
                double l = audio[2 * i] * g * invL, r = audio[2 * i + 1] * g * invR, mid = (l + r) / 2, side = (l - r) / 2 * width;
                if (monoHz > 0) { double sl = lowL[0].tick(side); side -= sl; lowL[1].tick(0); }
                audio[2 * i] = (float) (mid + side); audio[2 * i + 1] = (float) (mid - side);
            }
        }
    }

    static final class ChorusDsp extends Dsp {
        final float[][] line = new float[2][RATE / 10]; int w; double phase, rate, depth, base, mix;
        ChorusDsp(Insert i) { super(i); }
        void update() { double[] v = insert.values; rate = v[0]; depth = v[1] * .001 * RATE; base = v[2] * .001 * RATE; mix = v[3] / 100; }
        void run(float[] audio, int frames, int bpm) {
            int n = line[0].length;
            for (int i = 0; i < frames; i++) {
                phase += 2 * Math.PI * rate / RATE; if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                for (int c = 0; c < 2; c++) {
                    double x = audio[2 * i + c]; line[c][w] = (float) x;
                    double d = base + depth * (0.5 + 0.5 * Math.sin(phase + (c == 0 ? 0 : Math.PI / 2))), read = w - d; while (read < 0) read += n;
                    int a = (int) read; double frac = read - a, y = line[c][a % n] * (1 - frac) + line[c][(a + 1) % n] * frac;
                    audio[2 * i + c] = (float) (x * (1 - mix * .5) + y * mix);
                }
                w = (w + 1) % n;
            }
        }
    }

    /** Stereo delay line used for plugin delay compensation; the delay can change between blocks. */
    public static final class DelayLine {
        float[] buf = new float[2 * 4096]; int w;
        /** Adds src delayed by delay frames into dst. */
        public void process(float[] src, float[] dst, int n, int delay) {
            if (delay * 2 + 2 > buf.length) { int size = buf.length; while (size < delay * 2 + 2) size *= 2; float[] nb = new float[size]; int frames = buf.length / 2; for (int i = 0; i < frames; i++) { int from = (w - 1 - i + frames) % frames; int to = (frames * 0 + (size / 2) - 1 - i); nb[2 * to] = buf[2 * from]; nb[2 * to + 1] = buf[2 * from + 1]; } buf = nb; w = 0; }
            int frames = buf.length / 2;
            for (int i = 0; i < n; i++) { buf[2 * w] = src[2 * i]; buf[2 * w + 1] = src[2 * i + 1]; int r = (w - delay + frames) % frames; dst[2 * i] += buf[2 * r]; dst[2 * i + 1] += buf[2 * r + 1]; w = (w + 1) % frames; }
        }
    }

    /** Runs an ordered insert chain; keeps DSP state keyed by insert id so reordering keeps each processor's state. */
    public static final class Chain {
        final Map<String, Dsp> dsp = new HashMap<>();
        public double reduction;
        public void process(List<Insert> inserts, float[] audio, int frames, int bpm) {
            reduction = 0;
            if (inserts.isEmpty()) { if (!dsp.isEmpty()) dsp.clear(); return; }
            for (Insert i : inserts) {
                Dsp d = dsp.get(i.id);
                if (d == null || d.insert != i) { d = i.create(); dsp.put(i.id, d); }
                if (i.enabled) { d.process(audio, frames, bpm); reduction = Math.max(reduction, d.reduction()); }
            }
            if (dsp.size() > inserts.size()) { HashSet<String> keep = new HashSet<>(); for (Insert i : inserts) keep.add(i.id); dsp.keySet().retainAll(keep); }
        }
        public int latency(List<Insert> inserts) { int sum = 0; for (Insert i : inserts) { if (!i.enabled) continue; Dsp d = dsp.get(i.id); if (d == null) { d = i.create(); d.process(new float[2], 0, 120); } sum += d.latency(); } return sum; }
    }

    // ------------------------------------------------------------------ buses and sends

    public static final class Send {
        public final String bus; public volatile double level; public volatile boolean pre;
        public Send(String bus, double level, boolean pre) { this.bus = bus; this.level = level; this.pre = pre; }
        public Send copy() { return new Send(bus, level, pre); }
    }

    // ------------------------------------------------------------------ automation

    public static final String[] AUTOMATABLE = { "volume", "pan", "fx.inputDb", "fx.outputDb", "fx.low", "fx.mid", "fx.high", "fx.threshold", "fx.ratio", "fx.deessAmount", "fx.drive", "fx.delayMix", "fx.roomMix", "fx.delayFeedback" };
    public static final String[] AUTOMATABLE_NAMES = { "Volume", "Pan", "Input trim", "Output trim", "EQ low", "EQ mid", "EQ high", "Comp threshold", "Comp ratio", "De-ess amount", "Saturation drive", "Delay mix", "Room mix", "Delay feedback" };

    public static double[] range(String param) {
        switch (param) {
            case "volume": return new double[]{0, 2};
            case "pan": return new double[]{-1, 1};
            case "fx.inputDb": case "fx.outputDb": case "fx.low": case "fx.mid": case "fx.high": return new double[]{-12, 12};
            case "fx.threshold": return new double[]{-48, 0};
            case "fx.ratio": return new double[]{1, 10};
            case "fx.drive": return new double[]{1, 6};
            case "fx.delayMix": case "fx.roomMix": return new double[]{0, .6};
            case "fx.delayFeedback": return new double[]{0, .85};
            default: return param.startsWith("send:") ? new double[]{0, 2} : new double[]{0, 1};
        }
    }

    /** Breakpoint envelope with linear interpolation. Reads are lock-free against an immutable snapshot. */
    public static final class Lane {
        public final String param; public volatile boolean enabled = true;
        private volatile long[] frames = new long[0]; private volatile double[] values = new double[0];
        public Lane(String param) { this.param = param; }
        public Lane copy() { Lane l = new Lane(param); l.enabled = enabled; l.frames = frames; l.values = values; return l; }
        public int size() { return frames.length; }
        public long frame(int i) { return frames[i]; }
        public double value(int i) { return values[i]; }
        public synchronized void set(long[] f, double[] v) {
            Integer[] order = new Integer[f.length]; for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, Comparator.comparingLong(i -> f[i]));
            double[] r = range(param); long[] nf = new long[f.length]; double[] nv = new double[f.length]; int n = 0;
            for (Integer i : order) { if (n > 0 && nf[n - 1] == f[i]) n--; nf[n] = Math.max(0, f[i]); nv[n] = clamp(v[i], r[0], r[1]); n++; }
            values = Arrays.copyOf(nv, n); frames = Arrays.copyOf(nf, n);
        }
        public synchronized void add(long frame, double value) { long[] f = Arrays.copyOf(frames, frames.length + 1); double[] v = Arrays.copyOf(values, values.length + 1); f[f.length - 1] = frame; v[v.length - 1] = value; set(f, v); }
        public synchronized void remove(int index) { if (index < 0 || index >= frames.length) return; long[] f = new long[frames.length - 1]; double[] v = new double[f.length]; for (int i = 0, k = 0; i < frames.length; i++) if (i != index) { f[k] = frames[i]; v[k++] = values[i]; } frames = f; values = v; }
        public synchronized void clear() { frames = new long[0]; values = new double[0]; }
        /** Index of the nearest point within tolerance frames, or −1. */
        public int nearest(long frame, long tolerance) { long[] f = frames; int best = -1; long d = tolerance + 1; for (int i = 0; i < f.length; i++) { long e = Math.abs(f[i] - frame); if (e < d) { d = e; best = i; } } return best; }
        /** Value at a frame, or NaN when the lane has no points or is disabled. */
        public double at(long frame) {
            long[] f = frames; double[] v = values; if (!enabled || f.length == 0) return Double.NaN;
            if (frame <= f[0]) return v[0]; if (frame >= f[f.length - 1]) return v[f.length - 1];
            int lo = 0, hi = f.length - 1; while (hi - lo > 1) { int m = (lo + hi) >>> 1; if (f[m] <= frame) lo = m; else hi = m; }
            double t = (frame - f[lo]) / (double) (f[hi] - f[lo]); return v[lo] + (v[hi] - v[lo]) * t;
        }
        /** Touch-style write: replaces everything between from and to with a ramp ending at value, then thins redundant points. */
        public synchronized void write(long from, long to, double value) {
            if (to < from) { long t = from; from = to; to = t; }
            double start = Double.isNaN(at(from)) ? value : at(from); ArrayList<long[]> keepF = new ArrayList<>(); ArrayList<Double> keepV = new ArrayList<>();
            for (int i = 0; i < frames.length; i++) if (frames[i] < from || frames[i] > to) { keepF.add(new long[]{frames[i]}); keepV.add(values[i]); }
            long[] f = new long[keepF.size() + 2]; double[] v = new double[f.length];
            for (int i = 0; i < keepF.size(); i++) { f[i] = keepF.get(i)[0]; v[i] = keepV.get(i); }
            f[f.length - 2] = from; v[f.length - 2] = start; f[f.length - 1] = to; v[f.length - 1] = value; set(f, v); thin(0.002);
        }
        /** Removes points that lie on the line between their neighbours. */
        public synchronized void thin(double tolerance) {
            if (frames.length < 3) return; double span = range(param)[1] - range(param)[0];
            ArrayList<Integer> keep = new ArrayList<>(); keep.add(0);
            for (int i = 1; i < frames.length - 1; i++) {
                int p = keep.get(keep.size() - 1); double t = (frames[i] - frames[p]) / (double) Math.max(1, frames[i + 1] - frames[p]);
                double predicted = values[p] + (values[i + 1] - values[p]) * t;
                if (Math.abs(predicted - values[i]) > tolerance * span) keep.add(i);
            }
            keep.add(frames.length - 1);
            long[] f = new long[keep.size()]; double[] v = new double[f.length]; for (int i = 0; i < f.length; i++) { f[i] = frames[keep.get(i)]; v[i] = values[keep.get(i)]; }
            frames = f; values = v;
        }
    }

    /** Applies FX-parameter automation onto a scratch copy of the track's FX; returns the FX object to render with. */
    public static Core.Fx automatedFx(Core.Track t, long frame, Core.Fx scratch) {
        boolean any = false;
        for (Lane l : t.automation.values()) if (l.param.startsWith("fx.") && l.enabled && l.size() > 0) { any = true; break; }
        if (!any) return t.fx;
        scratch.copyFrom(t.fx);
        for (Lane l : t.automation.values()) {
            if (!l.param.startsWith("fx.")) continue; double v = l.at(frame); if (Double.isNaN(v)) continue;
            switch (l.param) {
                case "fx.inputDb": scratch.inputDb = v; break; case "fx.outputDb": scratch.outputDb = v; break;
                case "fx.low": scratch.low = v; break; case "fx.mid": scratch.mid = v; break; case "fx.high": scratch.high = v; break;
                case "fx.threshold": scratch.threshold = v; break; case "fx.ratio": scratch.ratio = v; break;
                case "fx.deessAmount": scratch.deessAmount = v; break; case "fx.drive": scratch.drive = v; break;
                case "fx.delayMix": scratch.delayMix = v; break; case "fx.roomMix": scratch.roomMix = v; break;
                case "fx.delayFeedback": scratch.delayFeedback = v; break;
            }
        }
        return scratch;
    }
    public static double automated(Core.Track t, String param, long frame, double fallback) {
        if (t.automation.isEmpty()) return fallback; Lane l = t.automation.get(param); if (l == null) return fallback; double v = l.at(frame); return Double.isNaN(v) ? fallback : v;
    }

    // ------------------------------------------------------------------ crossfades, comping and edit operations

    public static final int DEFAULT_CROSSFADE = RATE / 100; // 10 ms

    /**
     * Gain for a clip at a timeline frame when the track uses overlap crossfades. The clip that starts later sits on top;
     * at each edge of an overlap the two clips cross over with an equal-power curve.
     */
    public static double overlapGain(Core.Track t, Core.Clip c, long at, int crossfade) { return overlapGain(t, c, t.clips.indexOf(c), at, crossfade); }
    /** As above with the clip's index in the track list already known (the renderer's per-sample path). */
    public static double overlapGain(Core.Track t, Core.Clip c, int ci, long at, int crossfade) {
        double g = 1;
        for (int k = 0; k < t.clips.size(); k++) {
            Core.Clip o = t.clips.get(k); if (o == c) continue;
            long oEnd = o.start + o.length; if (at < o.start || at >= oEnd) continue; // other clip silent here
            boolean cOnTop = c.start > o.start || (c.start == o.start && ci > k);
            Core.Clip top = cOnTop ? c : o, under = cOnTop ? o : c; long topEnd = top.start + top.length, underEnd = under.start + under.length;
            long len = Math.max(1, Math.min(crossfade, Math.min(top.length, Math.max(1, Math.min(underEnd, topEnd) - top.start))));
            double topGain;
            if (at < top.start + len && top.start > under.start) topGain = Math.sin(Math.PI / 2 * (at - top.start + .5) / len);
            else if (topEnd < underEnd && at >= topEnd - len) topGain = Math.sin(Math.PI / 2 * (topEnd - at - .5) / len);
            else topGain = 1;
            topGain = clamp(topGain, 0, 1);
            g *= cOnTop ? topGain : Math.sqrt(Math.max(0, 1 - topGain * topGain));
        }
        return g;
    }

    /** Slip edit: moves the audio inside a clip without moving the clip. */
    public static void slip(Core.Clip c, long deltaFrames) {
        // fades stay attached to the clip edges; only the source window moves (the dry-vocal mapping follows offset)
        c.offset = (int) Math.max(0, Math.min(c.source.frames() - c.length, c.offset - deltaFrames));
    }

    /** Ripple delete: removes a clip and closes the gap by moving later material on the same track (or all tracks). */
    public static void rippleDelete(Core.Session s, Core.Track t, Core.Clip c, boolean allTracks) {
        long gapStart = c.start, gap = c.length; t.clips.remove(c);
        for (Core.Track tr : s.tracks) { if (!allTracks && tr != t) continue; for (Core.Clip o : tr.clips) if (o.start >= gapStart + gap || (tr != t && o.start >= gapStart)) o.start = Math.max(0, o.start - gap); }
        for (Marker m : s.markers) if (allTracks && m.frame >= gapStart + gap) m.frame -= gap;
    }

    /** Trims a clip to [from, to) on the timeline, keeping the envelope aligned. Returns false when nothing remains. */
    static boolean trimTo(Core.Clip c, long from, long to) {
        long start = Math.max(c.start, from), end = Math.min(c.start + c.length, to); if (end <= start) return false;
        int cut = (int) (start - c.start); c.offset += cut; c.envelopeOffset += cut; c.start = start; c.length = (int) (end - start); return true;
    }

    /**
     * Comp: replaces the timeline range [from, to) on the track with the matching part of a take, using short crossfades
     * at the edges. Clips outside the range are trimmed, not removed.
     */
    public static Core.Clip comp(Core.Track t, Core.Clip take, long from, long to, int crossfade) {
        from = Math.max(from, take.start); to = Math.min(to, take.start + take.length);
        if (to <= from) throw new IllegalArgumentException("The take does not cover that range.");
        ArrayList<Core.Clip> result = new ArrayList<>();
        for (Core.Clip c : t.clips) {
            long end = c.start + c.length;
            if (end <= from || c.start >= to) { result.add(c); continue; }
            if (c.start < from) { Core.Clip left = c.copy(); if (trimTo(left, c.start, from + crossfade)) result.add(left); }
            if (end > to) { Core.Clip right = c.copy(); if (trimTo(right, to - crossfade, end)) result.add(right); }
        }
        Core.Clip piece = take.copy(); trimTo(piece, from, to); piece.name = take.name;
        result.add(piece);
        result.sort(Comparator.comparingLong(c -> c.start));
        t.clips.clear(); t.clips.addAll(result); t.autoCrossfade = true;
        return piece;
    }

    /** Adds a clip to the track's take list (once per id), keeping takes ordered by recording time. */
    public static void rememberTake(Core.Track t, Core.Clip take) { for (Core.Clip c : t.takes) if (Objects.equals(c.file, take.file) && c.start == take.start && c.offset == take.offset && c.length == take.length) return; t.takes.add(take.copy()); }

    // ------------------------------------------------------------------ tempo map, time signature and markers

    public static final class Marker {
        public long frame; public String name;
        public Marker(long frame, String name) { this.frame = frame; this.name = name; }
        public Marker copy() { return new Marker(frame, name); }
    }
    public static final class TempoChange {
        public final int bar; public final int bpm;
        public TempoChange(int bar, int bpm) { this.bar = Math.max(1, bar); this.bpm = (int) clamp(bpm, 40, 240); }
    }

    /** Converts between frames, beats and bars using the session tempo, tempo changes and time signature. */
    public static final class TempoMap {
        final int beatsPerBar; final long[] startFrame; final double[] startBeat, framesPerBeat;
        public TempoMap(Core.Session s) {
            beatsPerBar = s.timeSignature;
            ArrayList<TempoChange> list = new ArrayList<>(s.tempoChanges); list.sort(Comparator.comparingInt(c -> c.bar));
            ArrayList<double[]> segs = new ArrayList<>(); segs.add(new double[]{0, 0, RATE * 60.0 / s.bpm});
            for (TempoChange c : list) {
                double beat = c.bar * (double) beatsPerBar; double[] last = segs.get(segs.size() - 1); if (beat <= last[1]) { last[2] = RATE * 60.0 / c.bpm; continue; }
                double frame = last[0] + (beat - last[1]) * last[2]; segs.add(new double[]{frame, beat, RATE * 60.0 / c.bpm});
            }
            startFrame = new long[segs.size()]; startBeat = new double[segs.size()]; framesPerBeat = new double[segs.size()];
            for (int i = 0; i < segs.size(); i++) { startFrame[i] = Math.round(segs.get(i)[0]); startBeat[i] = segs.get(i)[1]; framesPerBeat[i] = segs.get(i)[2]; }
        }
        public double beatAt(long frame) { int i = startFrame.length - 1; while (i > 0 && startFrame[i] > frame) i--; return startBeat[i] + (frame - startFrame[i]) / framesPerBeat[i]; }
        public long frameAt(double beat) { int i = startBeat.length - 1; while (i > 0 && startBeat[i] > beat) i--; return Math.round(startFrame[i] + (beat - startBeat[i]) * framesPerBeat[i]); }
        public double bpmAt(long frame) { int i = startFrame.length - 1; while (i > 0 && startFrame[i] > frame) i--; return RATE * 60.0 / framesPerBeat[i]; }
        public int beatsPerBar() { return beatsPerBar; }
        /** Bar.beat.tick label (1-based bars and beats, 960 ticks per beat). */
        public String label(long frame) { double b = beatAt(frame); int bar = (int) (b / beatsPerBar) + 1, beat = (int) (b % beatsPerBar) + 1, tick = (int) ((b - Math.floor(b)) * 960); return bar + "." + beat + "." + String.format(Locale.ROOT, "%03d", tick); }
        /** Snaps to the nearest grid line; division is subdivisions per beat (0 = off). */
        public long snap(long frame, int division) { if (division <= 0) return Math.max(0, frame); double b = beatAt(frame); return Math.max(0, frameAt(Math.round(b * division) / (double) division)); }
        public boolean constant() { return startFrame.length == 1; }
    }

    // ------------------------------------------------------------------ latency measurement and real-time helpers

    /** In-place iterative radix-2 FFT. */
    static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) { int bit = n >> 1; for (; (j & bit) != 0; bit >>= 1) j ^= bit; j ^= bit; if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; } }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1), wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) { double cr = 1, ci = 0; for (int k = 0; k < len / 2; k++) {
                int a = i + k, b = a + len / 2; double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi; double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t; } }
        }
        if (inverse) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }

    /** Measured round-trip latency. confidence is the correlation peak over the next-best peak (≥ 2 is a clear result). */
    public static final class LatencyResult { public final int frames; public final double confidence; LatencyResult(int f, double c) { frames = f; confidence = c; } public double ms() { return frames * 1000.0 / RATE; } }

    /** Estimates how many frames the recorded signal lags the played reference, by FFT cross-correlation. */
    public static LatencyResult estimateLatency(float[] reference, float[] recorded, int maxLag) {
        int len = Math.min(reference.length, recorded.length), n = 1; while (n < len + maxLag) n <<= 1;
        double[] ar = new double[n], ai = new double[n], br = new double[n], bi = new double[n];
        for (int i = 0; i < len; i++) { ar[i] = reference[i]; }
        for (int i = 0; i < recorded.length && i < n; i++) br[i] = recorded[i];
        fft(ar, ai, false); fft(br, bi, false);
        for (int i = 0; i < n; i++) { double r = br[i] * ar[i] + bi[i] * ai[i], im = bi[i] * ar[i] - br[i] * ai[i]; double mag = Math.hypot(r, im) + 1e-12; ar[i] = r / mag; ai[i] = im / mag; } // PHAT weighting
        fft(ar, ai, true);
        int best = 0; double top = -1;
        for (int lag = 0; lag <= maxLag && lag < n; lag++) if (ar[lag] > top) { top = ar[lag]; best = lag; }
        double second = 1e-9; for (int lag = 0; lag <= maxLag && lag < n; lag++) if (Math.abs(lag - best) > RATE / 500 && ar[lag] > second) second = ar[lag];
        return new LatencyResult(best, top / second);
    }

    /** Calibration signal: a train of short broadband clicks, mono. */
    public static float[] calibrationClicks(int count, int spacingFrames) {
        float[] out = new float[count * spacingFrames + RATE / 2]; Random r = new Random(77);
        for (int k = 0; k < count; k++) { int at = RATE / 10 + k * spacingFrames; for (int j = 0; j < 96; j++) out[at + j] = (float) ((r.nextDouble() * 2 - 1) * Math.exp(-j / 24.0) * .7); }
        return out;
    }

    /** Moves a just-recorded clip earlier by the measured latency, trimming the head if it would start before zero. */
    public static void compensate(Core.Clip c, int latencyFrames) {
        if (latencyFrames <= 0) return; long start = c.start - latencyFrames;
        if (start < 0) { int cut = (int) Math.min(c.length - 1, -start); c.offset += cut; c.envelopeOffset += cut; c.length -= cut; start = 0; }
        c.start = start;
    }

    /** Lock-free single-producer/single-consumer ring of interleaved stereo floats, for input monitoring. */
    public static final class Ring {
        final float[] data; final int mask; final AtomicLong write = new AtomicLong(), read = new AtomicLong();
        public Ring(int framesPowerOfTwo) { int n = 1; while (n < framesPowerOfTwo * 2) n <<= 1; data = new float[n]; mask = n - 1; }
        public int available() { return (int) ((write.get() - read.get()) / 2); }
        public void push(float l, float r) { long w = write.get(); if (w - read.get() >= data.length - 2) return; data[(int) (w & mask)] = l; data[(int) ((w + 1) & mask)] = r; write.lazySet(w + 2); }
        /** Adds up to frames of audio into out (scaled); returns frames read. Drops backlog so latency stays bounded. */
        public int mixInto(float[] out, int frames, double gain, int maxBacklog) {
            long r = read.get(), w = write.get(); if ((w - r) / 2 > maxBacklog) r = w - 2L * Math.min(frames, maxBacklog);
            int n = (int) Math.min(frames, (w - r) / 2);
            for (int i = 0; i < n; i++) { out[2 * i] += (float) (data[(int) ((r + 2 * i) & mask)] * gain); out[2 * i + 1] += (float) (data[(int) ((r + 2 * i + 1) & mask)] * gain); }
            read.lazySet(r + 2L * n); return n;
        }
        public void clear() { read.set(write.get()); }
    }

    // ------------------------------------------------------------------ MIDI note editing (piano roll back end)

    public static final class Note {
        public int pitch, velocity, channel; public long start, length;
        public Note(int pitch, int velocity, long start, long length, int channel) { this.pitch = pitch; this.velocity = velocity; this.start = start; this.length = length; this.channel = channel; }
        public Note copy() { return new Note(pitch, velocity, start, length, channel); }
        public long end() { return start + length; }
    }

    /** Pairs note-on/off events into notes; controllers and other events are returned separately. */
    public static List<Note> notes(List<MidiTake.Event> events, List<MidiTake.Event> other) {
        ArrayList<Note> out = new ArrayList<>(); Map<Integer, ArrayDeque<Note>> open = new HashMap<>();
        boolean[] sustain=new boolean[16];ArrayList<Note> deferred=new ArrayList<>();long last=0;
        for (MidiTake.Event e : events) {
            last=Math.max(last,e.frame);
            int type = e.status & 0xF0, ch = e.status & 15, key = ch * 128 + e.a;
            if (type == 0x90 && e.b > 0) { Note n = new Note(e.a, e.b, e.frame, 0, ch); open.computeIfAbsent(key, k -> new ArrayDeque<>()).add(n); out.add(n); }
            else if (type == 0x80 || (type == 0x90 && e.b == 0)) {
                ArrayDeque<Note> q=open.get(key);if(q!=null&&!q.isEmpty()){Note n=q.poll();if(sustain[ch])deferred.add(n);else n.length=Math.max(1,e.frame-n.start);}
            } else {
                if(type==0xB0&&e.a==64){sustain[ch]=e.b>=64;if(!sustain[ch])for(Iterator<Note> it=deferred.iterator();it.hasNext();){Note n=it.next();if(n.channel==ch){n.length=Math.max(1,e.frame-n.start);it.remove();}}}
                if(type==0xB0&&(e.a==120||e.a==123)){
                    for(Map.Entry<Integer,ArrayDeque<Note>> entry:open.entrySet())if(entry.getKey()/128==ch){for(Note n:entry.getValue())n.length=Math.max(1,e.frame-n.start);entry.getValue().clear();}
                    for(Iterator<Note> it=deferred.iterator();it.hasNext();){Note n=it.next();if(n.channel==ch){n.length=Math.max(1,e.frame-n.start);it.remove();}}
                }
                if(other!=null&&!(type==0xB0&&(e.a==123||e.a==120)))other.add(e);
            }
        }
        for(ArrayDeque<Note> q:open.values())for(Note n:q)n.length=Math.max(1,last-n.start);
        for(Note n:deferred)n.length=Math.max(1,last-n.start);
        out.sort(Comparator.comparingLong((Note n) -> n.start).thenComparingInt(n -> n.pitch));
        return out;
    }

    /** Rebuilds a printable MIDI take from notes plus the other (controller) events. */
    public static MidiTake take(Synth.Config config, int bpm, List<Note> notes, List<MidiTake.Event> other) {
        MidiTake t = new MidiTake(config, bpm); t.recording = false; ArrayList<MidiTake.Event> ev = new ArrayList<>();
        long end = 0;
        for (Note n : notes) { if (n.length <= 0 || n.velocity <= 0) continue; ev.add(new MidiTake.Event((int) n.start, 0x90 | n.channel, n.pitch, Math.min(127, n.velocity))); ev.add(new MidiTake.Event((int) n.end(), 0x80 | n.channel, n.pitch, 0)); end = Math.max(end, n.end()); }
        if (other != null) for (MidiTake.Event e : other) { ev.add(e); end = Math.max(end, e.frame); }
        ev.sort((a, b) -> a.frame != b.frame ? Integer.compare(a.frame, b.frame) : Integer.compare(order(a), order(b)));
        t.events.addAll(ev); t.frames = (int) Math.min(Core.MAX_FRAMES, end);
        for (int ch = 0; ch < 16; ch++) { t.events.add(new MidiTake.Event(t.frames, 176 | ch, 64, 0)); t.events.add(new MidiTake.Event(t.frames, 176 | ch, 123, 0)); }
        return t;
    }
    static int order(MidiTake.Event e) { int type = e.status & 0xF0; return type == 0x80 ? 0 : type == 0x90 ? 2 : 1; }

    /** Moves note starts toward the grid. strength 0..1; swing 0..1 delays every second grid step by up to two thirds of a step. */
    public static void quantize(List<Note> notes, double gridFrames, double strength, double swing, boolean lengths) {
        for (Note n : notes) {
            long step = Math.round(n.start / gridFrames); double target = step * gridFrames; if ((step & 1) == 1) target += swing * gridFrames / 3;
            n.start = Math.max(0, Math.round(n.start + (target - n.start) * strength));
            if (lengths) { double l = Math.max(1, Math.round(n.length / gridFrames)) * gridFrames; n.length = Math.max(1, Math.round(n.length + (l - n.length) * strength)); }
        }
    }
    public static void velocity(List<Note> notes, double scale, int offset) { for (Note n : notes) n.velocity = (int) clamp(Math.round(n.velocity * scale) + offset, 1, 127); }
    public static void transpose(List<Note> notes, int semitones) { for (Note n : notes) n.pitch = (int) clamp(n.pitch + semitones, 0, 127); }

    /** Reads a Standard MIDI File (format 0 or 1) into frame-timed events, honouring tempo changes. */
    public static ArrayList<MidiTake.Event> readMidi(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(input)); byte[] id = new byte[4]; in.readFully(id);
        if (!new String(id, "US-ASCII").equals("MThd")) throw new IOException("Not a MIDI file.");
        int headerLength = in.readInt(); int format = in.readUnsignedShort(), tracks = in.readUnsignedShort(), division = in.readUnsignedShort(); in.skipBytes(headerLength - 6);
        if ((division & 0x8000) != 0) throw new IOException("SMPTE-timed MIDI files are not supported.");
        if (format > 1 || tracks > 64) throw new IOException("Unsupported MIDI file layout.");
        ArrayList<long[]> raw = new ArrayList<>(); TreeMap<Long, Integer> tempos = new TreeMap<>(); tempos.put(0L, 500000);
        for (int t = 0; t < tracks; t++) {
            in.readFully(id); int length = in.readInt(); if (!new String(id, "US-ASCII").equals("MTrk")) { in.skipBytes(length); t--; continue; }
            if (length < 0 || length > 16 * 1024 * 1024) throw new IOException("MIDI track is too large.");
            byte[] data = new byte[length]; in.readFully(data); int p = 0, running = 0; long tick = 0;
            while (p < data.length) {
                long delta = 0; int b; do { b = data[p++] & 255; delta = (delta << 7) | (b & 127); } while ((b & 128) != 0 && p < data.length); tick += delta;
                if (p >= data.length) break; int status = data[p] & 255;
                if (status == 0xFF) { int type = data[p + 1] & 255; p += 2; int len = 0; do { b = data[p++] & 255; len = (len << 7) | (b & 127); } while ((b & 128) != 0); if (type == 0x51 && len == 3) tempos.put(tick, ((data[p] & 255) << 16) | ((data[p + 1] & 255) << 8) | (data[p + 2] & 255)); p += len; continue; }
                if (status == 0xF0 || status == 0xF7) { p++; int len = 0; do { b = data[p++] & 255; len = (len << 7) | (b & 127); } while ((b & 128) != 0); p += len; continue; }
                if ((status & 128) != 0) { running = status; p++; } else if (running == 0) throw new IOException("Corrupt MIDI running status.");
                int type = running & 0xF0, a = data[p++] & 255, c = (type == 0xC0 || type == 0xD0) ? 0 : data[p++] & 255;
                raw.add(new long[]{tick, running, a, c});
            }
        }
        raw.sort(Comparator.comparingLong(r -> r[0]));
        ArrayList<MidiTake.Event> out = new ArrayList<>();
        for (long[] r : raw) out.add(new MidiTake.Event((int) Math.min(Core.MAX_FRAMES, ticksToFrames(r[0], tempos, division)), (int) r[1], (int) r[2], (int) r[3]));
        return out;
    }
    static long ticksToFrames(long tick, TreeMap<Long, Integer> tempos, int division) {
        double seconds = 0; long last = 0; int tempo = 500000;
        for (Map.Entry<Long, Integer> e : tempos.entrySet()) { if (e.getKey() >= tick) break; seconds += (e.getKey() - last) * tempo / 1e6 / division; last = e.getKey(); tempo = e.getValue(); }
        seconds += (tick - last) * tempo / 1e6 / division; return Math.round(seconds * RATE);
    }

    // ------------------------------------------------------------------ FLAC encoder (lossless export)

    /** Streams 16- or 24-bit stereo FLAC. Uses fixed predictors (orders 0–4) with Rice-coded residuals and mid/side selection. */
    public static final class FlacWriter implements Closeable {
        static final int BLOCK = 4096;
        final OutputStream out; final int bits; final long totalFrames; final int[][] buffer = new int[2][BLOCK]; int filled; long frameNumber; int minFrame = Integer.MAX_VALUE, maxFrame;
        final int rate;
        public FlacWriter(OutputStream out, int bits, long totalFrames) throws IOException { this(out, bits, totalFrames, RATE); }
        public FlacWriter(OutputStream out, int bits, long totalFrames, int rate) throws IOException {
            if (bits != 16 && bits != 24) throw new IOException("Use 16-bit or 24-bit FLAC.");
            if (rate != 48000 && rate != 44100) throw new IOException("Use 44.1 or 48 kHz FLAC.");
            this.out = out; this.bits = bits; this.totalFrames = totalFrames; this.rate = rate;
            out.write(new byte[]{'f', 'L', 'a', 'C'});
            out.write(new byte[]{(byte) 0x80, 0, 0, 34}); // last metadata block, STREAMINFO, 34 bytes
            BitOut s = new BitOut(); s.put(BLOCK, 16); s.put(BLOCK, 16); s.put(0, 24); s.put(0, 24); // frame sizes unknown (allowed)
            s.put(rate, 20); s.put(1, 3); s.put(bits - 1, 5); s.put(totalFrames >>> 32, 4); s.put(totalFrames & 0xffffffffL, 32);
            for (int i = 0; i < 16; i++) s.put(0, 8); // MD5 not computed (zero = unknown)
            out.write(s.bytes());
        }
        /** Writes one already-quantised stereo sample pair. */
        public void sample(int l, int r) throws IOException { buffer[0][filled] = l; buffer[1][filled] = r; if (++filled == BLOCK) flush(); }
        void flush() throws IOException {
            if (filled == 0) return; int n = filled; filled = 0;
            int[] left = Arrays.copyOf(buffer[0], n), right = Arrays.copyOf(buffer[1], n), mid = new int[n], side = new int[n];
            for (int i = 0; i < n; i++) { side[i] = left[i] - right[i]; mid[i] = (left[i] + right[i]) >> 1; }
            BitOut[] options = { sub(left, bits), sub(right, bits), sub(mid, bits), sub(side, bits + 1) };
            long indep = options[0].length() + options[1].length(), ls = options[0].length() + options[3].length(), rs = options[1].length() + options[3].length(), ms = options[2].length() + options[3].length();
            int assignment; BitOut a, b;
            long best = Math.min(Math.min(indep, ls), Math.min(rs, ms));
            if (best == ms) { assignment = 10; a = options[2]; b = options[3]; } else if (best == ls) { assignment = 8; a = options[0]; b = options[3]; } else if (best == rs) { assignment = 9; a = options[3]; b = options[1]; } else { assignment = 1; a = options[0]; b = options[1]; }
            BitOut f = new BitOut();
            f.put(0x3FFE, 14); f.put(0, 1); f.put(0, 1); // sync, reserved, fixed blocksize
            int sizeCode = n == BLOCK ? 12 : 7; f.put(sizeCode, 4); f.put(rate == 44100 ? 9 : 10, 4); // block size code, sample rate code
            f.put(assignment, 4); f.put(bits == 16 ? 4 : 6, 3); f.put(0, 1);
            utf8(f, frameNumber++);
            if (sizeCode == 7) f.put(n - 1, 16);
            f.put(crc8(f.bytes()), 8);
            f.append(a); f.append(b); f.align();
            byte[] body = f.bytes(); out.write(body); int crc = crc16(body); out.write(crc >> 8); out.write(crc & 255);
        }
        static void utf8(BitOut f, long v) {
            if (v < 0x80) { f.put(v, 8); return; }
            int bytes = v < 0x800 ? 2 : v < 0x10000 ? 3 : v < 0x200000 ? 4 : v < 0x4000000 ? 5 : 6;
            f.put(((0xFF00 >> bytes) & 0xFF) | (v >> (6 * (bytes - 1))), 8);
            for (int i = bytes - 2; i >= 0; i--) f.put(0x80 | ((v >> (6 * i)) & 0x3F), 8);
        }
        /** Subframe: chooses verbatim/constant/fixed order 0–4 by coded size. */
        static BitOut sub(int[] x, int bits) {
            int n = x.length; boolean constant = true; for (int i = 1; i < n; i++) if (x[i] != x[0]) { constant = false; break; }
            if (constant) { BitOut b = new BitOut(); b.put(0, 1); b.put(0, 6); b.put(0, 1); b.put(x[0] & ((1L << bits) - 1), bits); return b; }
            BitOut best = null;
            for (int order = 0; order <= 4 && order < n; order++) {
                int[] res = new int[n - order];
                for (int i = order; i < n; i++) {
                    long p; switch (order) { case 0: p = 0; break; case 1: p = x[i - 1]; break; case 2: p = 2L * x[i - 1] - x[i - 2]; break; case 3: p = 3L * x[i - 1] - 3L * x[i - 2] + x[i - 3]; break; default: p = 4L * x[i - 1] - 6L * x[i - 2] + 4L * x[i - 3] - x[i - 4]; }
                    res[i - order] = (int) (x[i] - p);
                }
                BitOut b = new BitOut(); b.put(0, 1); b.put(8 | order, 6); b.put(0, 1);
                for (int i = 0; i < order; i++) b.put(x[i] & ((1L << bits) - 1), bits);
                rice(b, res, n, order);
                if (best == null || b.length() < best.length()) best = b;
            }
            BitOut verbatim = new BitOut(); verbatim.put(0, 1); verbatim.put(1, 6); verbatim.put(0, 1); for (int v : x) verbatim.put(v & ((1L << bits) - 1), bits);
            return verbatim.length() < best.length() ? verbatim : best;
        }
        /** Residual with partitioned Rice coding (partition order chosen up to 4). */
        static void rice(BitOut b, int[] res, int blockSize, int predictorOrder) {
            int bestOrder = 0; long bestBits = Long.MAX_VALUE; int[] bestParams = null;
            for (int po = 0; po <= 4; po++) {
                int parts = 1 << po; if (blockSize % parts != 0 || blockSize / parts <= predictorOrder) break;
                int[] params = new int[parts]; long total = 0; int idx = 0;
                for (int p = 0; p < parts; p++) {
                    int count = blockSize / parts - (p == 0 ? predictorOrder : 0); long sum = 0;
                    for (int i = 0; i < count; i++) { int v = res[idx + i]; sum += v >= 0 ? 2L * v : -2L * v - 1; }
                    int k = 0; while (k < 30 && ((long) count << (k + 1)) < sum) k++; k = Math.min(k, 30);
                    long bitsUsed = 0; for (int i = 0; i < count; i++) { int v = res[idx + i]; long u = v >= 0 ? 2L * v : -2L * v - 1; bitsUsed += (u >>> k) + 1 + k; }
                    params[p] = k; total += bitsUsed + 5; idx += count;
                }
                if (total < bestBits) { bestBits = total; bestOrder = po; bestParams = params; }
            }
            boolean wide = false; for (int k : bestParams) if (k > 14) wide = true;
            b.put(wide ? 1 : 0, 2); b.put(bestOrder, 4); int parts = 1 << bestOrder, idx = 0;
            for (int p = 0; p < parts; p++) {
                int count = blockSize / parts - (p == 0 ? predictorOrder : 0), k = bestParams[p]; b.put(k, wide ? 5 : 4);
                for (int i = 0; i < count; i++) { int v = res[idx + i]; long u = v >= 0 ? 2L * v : -2L * v - 1; b.unary(u >>> k); if (k > 0) b.put(u & ((1L << k) - 1), k); }
                idx += count;
            }
        }
        public void close() throws IOException { flush(); out.flush(); }
        static int crc8(byte[] data) { int crc = 0; for (byte d : data) { crc ^= d & 255; for (int i = 0; i < 8; i++) crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 255 : (crc << 1) & 255; } return crc; }
        static int crc16(byte[] data) { int crc = 0; for (byte d : data) { crc ^= (d & 255) << 8; for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x8005) & 0xFFFF : (crc << 1) & 0xFFFF; } return crc; }
    }

    /** Big-endian bit writer. */
    static final class BitOut {
        byte[] buf = new byte[1024]; long bits;
        void put(long value, int count) { for (int i = count - 1; i >= 0; i--) bit((int) ((value >>> i) & 1)); }
        void unary(long zeros) { for (long i = 0; i < zeros; i++) bit(0); bit(1); }
        void bit(int b) { int byteIndex = (int) (bits >>> 3); if (byteIndex >= buf.length) buf = Arrays.copyOf(buf, buf.length * 2); if (b != 0) buf[byteIndex] |= (byte) (0x80 >>> (bits & 7)); bits++; }
        void append(BitOut o) { for (long i = 0; i < o.bits; i++) bit((o.buf[(int) (i >>> 3)] >>> (7 - (i & 7))) & 1); }
        void align() { while ((bits & 7) != 0) bit(0); }
        long length() { return bits; }
        byte[] bytes() { return Arrays.copyOf(buf, (int) ((bits + 7) >>> 3)); }
    }

    // ------------------------------------------------------------------ offline rendering helpers

    /** Receives rendered audio in blocks. */
    public interface BlockSink { void block(float[] stereo, int frames) throws IOException; }

    /** Renders a session offline, removing master-chain look-ahead delay so the export lines up with the timeline. */
    public static void renderOffline(Core.Session s, long frames, BlockSink sink) throws IOException {
        Core.Session renderSession=s;for(Core.Track t:s.tracks)if(t.ephemeral){renderSession=s.copy();break;}
        Core.Renderer renderer = new Core.Renderer(renderSession); float[] block = new float[1024];
        int skip = renderer.masterLatency(); long pos = 0, emitted = 0;
        if (skip == 0) { // same block sequence as the original exporter, so legacy exports stay sample-identical
            for (; pos < frames; pos += 512) { int n = (int) Math.min(512, frames - pos); renderer.render(pos, n, block, false); sink.block(block, n); }
            return;
        }
        while (emitted < frames) {
            renderer.render(pos, 512, block, false); pos += 512;
            int from = Math.min(512, skip); skip -= from; int n = (int) Math.min(512 - from, frames - emitted); if (n <= 0) continue;
            if (from > 0) System.arraycopy(block, from * 2, block, 0, n * 2);
            sink.block(block, n); emitted += n;
        }
    }

    /** A copy of the session that plays only one track (with its buses), master processing bypassed, for stem export. */
    public static Core.Session stem(Core.Session s, int trackIndex) {
        Core.Session c = s.copy();
        for (int i = 0; i < c.tracks.size(); i++) { Core.Track t = c.tracks.get(i); t.solo = false; t.mute = i != trackIndex || s.tracks.get(i).mute; }
        c.masterInserts.clear(); c.masterAutomation = null; c.master = 1;
        return c;
    }

    /** Measures the integrated loudness and true peak of a whole session render. */
    public static Loudness analyse(Core.Session s, long frames) throws IOException { Loudness m = new Loudness(); renderOffline(s, frames, m::feed); return m; }

    /** Gain in dB that would bring the session to the target integrated loudness without the true peak passing ceiling. */
    public static double normaliseGain(Loudness m, double targetLufs, double ceilingDb) {
        double i = m.integrated(); if (!Double.isFinite(i)) return 0; double g = targetLufs - i; return Math.min(g, ceilingDb - m.truePeakDb);
    }
}

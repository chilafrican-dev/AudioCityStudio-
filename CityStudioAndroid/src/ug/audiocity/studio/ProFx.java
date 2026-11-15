package ug.audiocity.studio;

import java.util.*;

/**
 * Additional insert processors: a full compressor, plate-style reverb, tempo-synced delay, tape saturation and a
 * transient shaper. Registered as insert kinds by {@link Pro}. Platform independent.
 */
final class ProFx {
    private ProFx() {}
    static final int RATE = Core.RATE;
    static final String COMP = "comp", REVERB = "reverb", DELAY = "delay", TAPE = "tape", TRANSIENT = "transient";

    static void register(Map<String, Pro.Kind> kinds) {
        kinds.put(COMP, new Pro.Kind(COMP, "Compressor · soft knee",
                new String[]{"THRESHOLD", "RATIO", "ATTACK", "RELEASE", "KNEE", "MAKEUP", "MIX", "SC LOW CUT"},
                new String[]{"dB", ":1", "ms", "ms", "dB", "dB", "%", "Hz"},
                new double[]{-60, 1, 0.1, 10, 0, 0, 0, 20}, new double[]{0, 20, 200, 2000, 18, 24, 100, 500},
                new double[]{-20, 4, 10, 120, 6, 0, 100, 20}));
        kinds.put(REVERB, new Pro.Kind(REVERB, "Plate reverb",
                new String[]{"SIZE", "DECAY", "DAMPING", "PRE-DELAY", "WIDTH", "LOW CUT", "MIX"},
                new String[]{"%", "s", "Hz", "ms", "%", "Hz", "%"},
                new double[]{20, 0.2, 1000, 0, 0, 20, 0}, new double[]{100, 10, 16000, 200, 100, 600, 100},
                new double[]{70, 1.8, 7000, 20, 100, 150, 25}));
        kinds.put(DELAY, new Pro.Kind(DELAY, "Tempo delay",
                new String[]{"TIME", "FEEDBACK", "LOW CUT", "HIGH CUT", "PING-PONG", "MIX"},
                new String[]{"beats", "%", "Hz", "Hz", "", "%"},
                new double[]{0.0625, 0, 20, 1000, 0, 0}, new double[]{2, 95, 2000, 20000, 1, 100},
                new double[]{0.75, 35, 200, 6000, 1, 20}));
        kinds.put(TAPE, new Pro.Kind(TAPE, "Tape saturation",
                new String[]{"DRIVE", "BIAS", "TONE", "OUTPUT", "MIX"}, new String[]{"dB", "%", "Hz", "dB", "%"},
                new double[]{0, 0, 2000, -24, 0}, new double[]{24, 100, 20000, 12, 100}, new double[]{6, 20, 14000, 0, 100}));
        kinds.put(TRANSIENT, new Pro.Kind(TRANSIENT, "Transient shaper",
                new String[]{"ATTACK", "SUSTAIN", "OUTPUT"}, new String[]{"%", "%", "dB"},
                new double[]{-100, -100, -12}, new double[]{100, 100, 12}, new double[]{30, 0, 0}));
        ProFx2.register(kinds);
    }

    static Pro.Dsp create(Pro.Insert i) {
        switch (i.kind) {
            case COMP: return new Comp(i);
            case REVERB: return new Plate(i);
            case DELAY: return new TempoDelay(i);
            case TAPE: return new Tape(i);
            case TRANSIENT: return new Transient(i);
            default: return ProFx2.create(i);
        }
    }

    /** Feed-forward stereo-linked compressor with a soft knee, log-domain smoothing and a detector high-pass. */
    static final class Comp extends Pro.Dsp {
        double th, ratio, knee, makeup, mix, att, rel, gr; final Pro.Filter[] hp = { new Pro.Filter(), new Pro.Filter() }; double lastHp = -1; boolean useHp;
        Comp(Pro.Insert i) { super(i); }
        void update() {
            double[] v = insert.values; th = v[0]; ratio = v[1]; att = Math.exp(-1 / (v[2] * .001 * RATE)); rel = Math.exp(-1 / (v[3] * .001 * RATE)); knee = v[4]; makeup = v[5]; mix = v[6] / 100;
            useHp = v[7] > 25; if (v[7] != lastHp) { lastHp = v[7]; hp[0].set(3, Math.max(20, v[7]), 0, .707); hp[1].set(3, Math.max(20, v[7]), 0, .707); }
        }
        /** Static curve: gain reduction in dB (≥ 0) for an input level in dB. */
        static double curve(double in, double th, double ratio, double knee) {
            double over = in - th;
            if (knee > 0 && Math.abs(over) <= knee / 2) { double x = over + knee / 2; return (1 - 1 / ratio) * x * x / (2 * knee); }
            return over > 0 ? over * (1 - 1 / ratio) : 0;
        }
        void run(float[] a, int n, int bpm) {
            double out = Pro.gain(makeup);
            for (int i = 0; i < n; i++) {
                double l = a[2 * i], r = a[2 * i + 1], dl = useHp ? hp[0].tick(l) : l, dr = useHp ? hp[1].tick(r) : r;
                double level = Pro.db(Math.max(Math.abs(dl), Math.abs(dr))), want = curve(level, th, ratio, knee);
                gr = want > gr ? att * gr + (1 - att) * want : rel * gr + (1 - rel) * want;
                double g = Pro.gain(-gr) * out;
                a[2 * i] = (float) (l * (1 - mix) + l * g * mix); a[2 * i + 1] = (float) (r * (1 - mix) + r * g * mix);
            }
        }
        public double reduction() { return gr; }
    }

    /** Freeverb-style plate: parallel damped combs into series all-passes, decorrelated per channel, with pre-delay. */
    static final class Plate extends Pro.Dsp {
        static final int[] COMBS = { 1557, 1617, 1491, 1422, 1277, 1356, 1188, 1116 }, ALLPASS = { 556, 441, 341, 225 };
        static final int SPREAD = 23;
        final float[][][] comb = new float[2][8][], ap = new float[2][4][]; final int[][] cp = new int[2][8], apos = new int[2][4]; final double[][] damp = new double[2][8];
        final float[][] pre = new float[2][RATE / 4]; int pw; int preFrames; final double[] wet = new double[2]; double[] fb = new double[8]; double dampCoef, width, mix; final Pro.Filter[] lowCut = { new Pro.Filter(), new Pro.Filter() }; double size = -1;
        Plate(Pro.Insert i) { super(i); }
        void update() {
            double[] v = insert.values; double s = v[0] / 100 * 1.6 + .2;
            if (s != size) { size = s; for (int c = 0; c < 2; c++) { for (int k = 0; k < 8; k++) comb[c][k] = new float[Math.max(16, (int) ((COMBS[k] + c * SPREAD) * s * RATE / 44100.0))]; for (int k = 0; k < 4; k++) ap[c][k] = new float[Math.max(8, (int) ((ALLPASS[k] + c * SPREAD) * RATE / 44100.0))]; } for (int[] x : cp) Arrays.fill(x, 0); }
            for (int k = 0; k < 8; k++) fb[k] = Math.pow(10, -3.0 * comb[0][k].length / (Math.max(.1, v[1]) * RATE)); // RT60 = decay
            dampCoef = Math.exp(-2 * Math.PI * v[2] / RATE); preFrames = Math.min(pre[0].length - 1, (int) (v[3] * .001 * RATE)); width = v[4] / 100; mix = v[6] / 100;
            lowCut[0].set(3, v[5], 0, .707); lowCut[1].set(3, v[5], 0, .707);
        }
        void run(float[] a, int n, int bpm) {
            int pl = pre[0].length;
            for (int i = 0; i < n; i++) {
                double in = (a[2 * i] + a[2 * i + 1]) * .5; pre[0][pw] = (float) in; double x = pre[0][(pw - preFrames + pl) % pl]; pw = (pw + 1) % pl;
                for (int c = 0; c < 2; c++) {
                    double sum = 0;
                    for (int k = 0; k < 8; k++) { float[] buf = comb[c][k]; int p = cp[c][k]; double y = buf[p]; damp[c][k] = y * (1 - dampCoef) + damp[c][k] * dampCoef; buf[p] = (float) (x * .015 + damp[c][k] * fb[k]); cp[c][k] = (p + 1) % buf.length; sum += y; }
                    for (int k = 0; k < 4; k++) { float[] buf = ap[c][k]; int p = apos[c][k]; double b = buf[p]; double y = -sum + b; buf[p] = (float) (sum + b * .5); apos[c][k] = (p + 1) % buf.length; sum = y; }
                    wet[c] = lowCut[c].tick(sum);
                }
                double mid = (wet[0] + wet[1]) * .5, side = (wet[0] - wet[1]) * .5 * width;
                double l = a[2 * i], r = a[2 * i + 1];
                a[2 * i] = (float) (l * (1 - mix) + (mid + side) * mix); a[2 * i + 1] = (float) (r * (1 - mix) + (mid - side) * mix);
            }
        }
    }

    /** Tempo-synced stereo delay with band-limited feedback and optional ping-pong. */
    static final class TempoDelay extends Pro.Dsp {
        final float[][] line = new float[2][RATE * 4]; int w, delay; double feedback, mix; boolean ping; final Pro.Filter[] lo = { new Pro.Filter(), new Pro.Filter() }, hi = { new Pro.Filter(), new Pro.Filter() }; int lastBpm = -1; double lastBeats = -1;
        TempoDelay(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; feedback = v[1] / 100; ping = v[4] >= .5; mix = v[5] / 100; for (int c = 0; c < 2; c++) { lo[c].set(3, v[2], 0, .707); hi[c].set(4, v[3], 0, .707); } lastBpm = -1; }
        void run(float[] a, int n, int bpm) {
            double beats = insert.values[0]; if (bpm != lastBpm || beats != lastBeats) { lastBpm = bpm; lastBeats = beats; delay = (int) Math.max(1, Math.min(line[0].length - 1, Math.round(RATE * 60.0 / bpm * beats))); }
            int len = line[0].length;
            for (int i = 0; i < n; i++) {
                int rp = (w - delay + len) % len; double el = line[0][rp], er = line[1][rp], l = a[2 * i], r = a[2 * i + 1];
                double fl = hi[0].tick(lo[0].tick(el)) * feedback, fr = hi[1].tick(lo[1].tick(er)) * feedback;
                if (ping) { line[0][w] = (float) ((l + r) * .5 + fr); line[1][w] = (float) fl; } else { line[0][w] = (float) (l + fl); line[1][w] = (float) (r + fr); }
                w = (w + 1) % len;
                a[2 * i] = (float) (l + el * mix); a[2 * i + 1] = (float) (r + er * mix);
            }
        }
    }

    /** Asymmetric soft saturation with a tone low-pass; output compensated so moderate drive stays level-matched. */
    static final class Tape extends Pro.Dsp {
        double drive, bias, out, mix, comp; final Pro.Filter[] tone = { new Pro.Filter(), new Pro.Filter() }; final Pro.Filter[] dcBlock = { new Pro.Filter(), new Pro.Filter() };
        Tape(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; drive = Pro.gain(v[0]); bias = v[1] / 100 * .3; out = Pro.gain(v[3]); mix = v[4] / 100; comp = 1 / drive; for (int c = 0; c < 2; c++) { tone[c].set(4, v[2], 0, .707); dcBlock[c].set(3, 10, 0, .707); } }
        void run(float[] a, int n, int bpm) {
            double offset = Math.tanh(bias);
            for (int i = 0; i < n; i++) for (int c = 0; c < 2; c++) {
                double x = a[2 * i + c], y = (Math.tanh(x * drive + bias) - offset) * comp;
                y = dcBlock[c].tick(tone[c].tick(y)) * out;
                a[2 * i + c] = (float) (x * (1 - mix) + y * mix);
            }
        }
    }

    /** Transient shaper: compares fast and slow envelopes to boost or soften attacks and sustain independently. */
    static final class Transient extends Pro.Dsp {
        double fast, slow, attack, sustain, out; final double fa = Math.exp(-1 / (.001 * RATE)), fr = Math.exp(-1 / (.02 * RATE)), sa = Math.exp(-1 / (.02 * RATE)), sr = Math.exp(-1 / (.25 * RATE));
        Transient(Pro.Insert i) { super(i); }
        void update() { double[] v = insert.values; attack = v[0] / 100; sustain = v[1] / 100; out = Pro.gain(v[2]); }
        void run(float[] a, int n, int bpm) {
            for (int i = 0; i < n; i++) {
                double x = Math.max(Math.abs(a[2 * i]), Math.abs(a[2 * i + 1]));
                fast = x > fast ? fa * fast + (1 - fa) * x : fr * fast + (1 - fr) * x; slow = x > slow ? sa * slow + (1 - sa) * x : sr * slow + (1 - sr) * x;
                double diff = Pro.db(fast + 1e-9) - Pro.db(slow + 1e-9); // positive on attacks, negative in tails
                double gdb = diff > 0 ? attack * Math.min(diff, 12) : sustain * Math.min(-diff, 12);
                double g = Pro.gain(Math.max(-18, Math.min(18, gdb))) * out;
                a[2 * i] *= g; a[2 * i + 1] *= g;
            }
        }
    }
}

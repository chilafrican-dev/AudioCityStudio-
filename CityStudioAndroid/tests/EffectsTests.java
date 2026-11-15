package ug.audiocity.studio;

import java.util.*;

public class EffectsTests {
    static int passed = 0;
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); passed++; }
    static float[] tone(double hz, double seconds, double amp) { int n = (int) (Core.RATE * seconds); float[] a = new float[n * 2]; for (int i = 0; i < n; i++) a[2 * i] = a[2 * i + 1] = (float) (amp * Math.sin(2 * Math.PI * hz * i / Core.RATE)); return a; }
    static float[] noise(double seconds, double amp, long seed) { Random r = new Random(seed); int n = (int) (Core.RATE * seconds); float[] a = new float[n * 2]; for (int i = 0; i < n; i++) a[2 * i] = a[2 * i + 1] = (float) (amp * (r.nextDouble() * 2 - 1)); return a; }
    static float[] run(String kind, float[] in, double... set) { Pro.Insert ins = new Pro.Insert(kind); for (int i = 0; i + 1 < set.length; i += 2) ins.set((int) set[i], set[i + 1]); ins.touch(); Pro.Dsp d = ins.create(); float[] a = in.clone(); for (int at = 0; at < a.length / 2; at += 512) { int n = Math.min(512, a.length / 2 - at); float[] b = new float[n * 2]; System.arraycopy(a, at * 2, b, 0, n * 2); d.process(b, n, 120); System.arraycopy(b, 0, a, at * 2, n * 2); } return a; }
    static double rms(float[] a, int ch, int from, int to) { double s = 0; for (int i = from; i < to; i++) s += a[2 * i + ch] * a[2 * i + ch]; return Math.sqrt(s / Math.max(1, to - from)); }
    static boolean finite(float[] a) { for (float v : a) if (!Float.isFinite(v) || Math.abs(v) > 8) return false; return true; }
    static double freq(float[] a, int from, int to) { int z = 0; for (int i = from + 1; i < to; i++) if (a[2 * i - 2] < 0 && a[2 * i] >= 0) z++; return z * (double) Core.RATE / (to - from); }
    static double band(float[] a, double hz, int from, int len) { double re = 0, im = 0; for (int i = 0; i < len; i++) { double w = 2 * Math.PI * hz * i / Core.RATE; re += a[(from + i) * 2] * Math.cos(w); im += a[(from + i) * 2] * Math.sin(w); } return Math.hypot(re, im) / len * 2; }

    public static void main(String[] args) throws Exception {
        String[] all = {ProFx2.PHASER, ProFx2.FLANGER, ProFx2.TREMOLO, ProFx2.AUTOFILTER, ProFx2.DRIVE, ProFx2.CRUSHER, ProFx2.GATE, ProFx2.DEESSER, ProFx2.DOUBLER, ProFx2.EXCITER, ProFx2.PITCH};
        float[] loud = noise(1, .9, 1);
        for (String k : all) {
            check(Pro.KINDS.containsKey(k), "registered: " + k);
            Pro.Kind info = Pro.KINDS.get(k); check(info.names.length == info.defaults.length && info.min.length == info.max.length, "metadata lines up: " + k);
            check(finite(run(k, loud)), "defaults stay finite and bounded on loud noise: " + k);
            double[] extreme = new double[info.max.length * 2]; for (int i = 0; i < info.max.length; i++) { extreme[2 * i] = i; extreme[2 * i + 1] = info.max[i]; }
            check(finite(run(k, loud, extreme)), "every control at maximum stays finite: " + k);
            for (int i = 0; i < info.min.length; i++) { extreme[2 * i + 1] = info.min[i]; }
            check(finite(run(k, loud, extreme)), "every control at minimum stays finite: " + k);
            Pro.Insert copy = new Pro.Insert(k).copy(); check(copy.kind.equals(k) && copy.values.length == info.defaults.length, "copies: " + k);
        }
        float[] t = tone(440, 1, .3); int s = 12000, e = 36000; double base = rms(t, 0, s, e);
        float[] tr = run(ProFx2.TREMOLO, t, 1, 100); double lo = 1, hi = 0; for (int w = s; w < e; w += 480) { double r = rms(tr, 0, w, w + 480); lo = Math.min(lo, r); hi = Math.max(hi, r); }
        check(lo < base * .2 && hi > base * .9, "tremolo at 100 % swings the level");
        float[] pan = run(ProFx2.TREMOLO, t, 1, 100, 3, 1); double maxDiff = 0; for (int w = s; w < e; w += 480) maxDiff = Math.max(maxDiff, Math.abs(rms(pan, 0, w, w + 480) - rms(pan, 1, w, w + 480)));
        check(maxDiff > base * .8, "auto-pan moves the sound between speakers");
        float[] lp = run(ProFx2.AUTOFILTER, tone(5000, 1, .3), 1, 300, 4, 0, 5, 0); check(rms(lp, 0, s, e) < base * .1, "auto-filter low-pass removes highs");
        float[] hp = run(ProFx2.AUTOFILTER, tone(100, 1, .3), 0, 2, 1, 3000, 4, 0, 5, 0); check(rms(hp, 0, s, e) < base * .1, "auto-filter high-pass removes lows");
        float[] dr = run(ProFx2.DRIVE, t, 0, 24, 3, 0); check(band(dr, 1320, s, 24000) > band(t, 1320, s, 24000) * 50 + .01, "drive adds harmonics");
        float[] cr = run(ProFx2.CRUSHER, t, 0, 3, 1, 1); TreeSet<Float> levels = new TreeSet<>(); for (int i = s; i < e; i++) levels.add(cr[2 * i]); check(levels.size() <= 9, "3-bit crusher leaves at most 9 levels");
        float[] quiet = noise(1, .003, 2), gated = run(ProFx2.GATE, quiet); check(rms(gated, 0, s, e) < rms(quiet, 0, s, e) * .01, "gate closes on noise below threshold");
        float[] opened = run(ProFx2.GATE, t); check(Math.abs(rms(opened, 0, s, e) - base) < base * .01, "gate passes a signal above threshold");
        float[] ess = tone(7000, 1, .5), de = run(ProFx2.DEESSER, ess), low = run(ProFx2.DEESSER, tone(300, 1, .5)); check(rms(de, 0, s, e) < rms(ess, 0, s, e) * .5, "de-esser cuts loud sibilance");
        check(Math.abs(rms(low, 0, s, e) - rms(tone(300, 1, .5), 0, s, e)) < .01, "de-esser leaves the body of the voice");
        float[] dbl = run(ProFx2.DOUBLER, t); double side = 0; for (int i = s; i < e; i++) side += Math.abs(dbl[2 * i] - dbl[2 * i + 1]); check(side / (e - s) > .01, "doubler widens a mono voice");
        float[] ex = run(ProFx2.EXCITER, tone(2500, 1, .4), 0, 2000, 2, 100); check(band(ex, 7500, s, 24000) > .005, "exciter adds upper harmonics");
        float[] up = run(ProFx2.PITCH, t, 0, 12), down = run(ProFx2.PITCH, t, 0, -7); double fu = freq(up, s, e), fd = freq(down, s, e);
        System.out.println("pitch shifter: +12 -> " + fu + " Hz, -7 -> " + fd + " Hz");
        check(Math.abs(fu - 880) < 20, "pitch shifter +12 doubles the frequency"); check(Math.abs(fd - 440 * Math.pow(2, -7 / 12.0)) < 12, "pitch shifter -7 drops a fifth");
        float[] ph = run(ProFx2.PHASER, noise(2, .3, 3)), fl = run(ProFx2.FLANGER, noise(2, .3, 3)); check(rms(ph, 0, s, 90000) > .05 && rms(fl, 0, s, 90000) > .05, "phaser and flanger pass sound");
        float[] flat = run(ProFx2.FLANGER, t, 4, 0); check(Math.abs(rms(flat, 0, s, e) - base) < base * .02, "flanger at 0 % mix is dry");
        System.out.println("EffectsTests passed " + passed + " checks");
    }
}

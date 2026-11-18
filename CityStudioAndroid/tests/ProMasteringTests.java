package ug.audiocity.studio;

import java.util.*;

/** Every mastering preset must land on its loudness target with true peaks at or below -1 dBTP. */
public final class ProMasteringTests {
    static int checks;
    static void check(boolean ok, String m) { checks++; if (!ok) throw new AssertionError(m); }

    static Core.Session song(double gain) {
        Core.Session s = new Core.Session(); s.bpm = 104; int frames = Core.RATE * 16; float[] mix = new float[frames * 2]; Random r = new Random(3); double beat = Core.RATE * 60.0 / 104;
        for (int i = 0; i < frames; i++) {
            double t = i / (double) Core.RATE, b = i / beat, ph = b - Math.floor(b), sixteenth = (b * 4) % 1;
            double kick = Math.exp(-ph * 9) * Math.sin(2 * Math.PI * 52 * ph * beat / Core.RATE * (1 + 3 * Math.exp(-ph * 30)));
            double hat = (r.nextDouble() * 2 - 1) * Math.exp(-sixteenth * 25) * .25, bass = .35 * Math.sin(2 * Math.PI * (((int) (b / 4)) % 2 == 0 ? 55 : 49) * t);
            double chord = .12 * (Math.sin(2 * Math.PI * 262 * t) + Math.sin(2 * Math.PI * 330 * t) + Math.sin(2 * Math.PI * 392 * t)) * (.6 + .4 * Math.sin(2 * Math.PI * t / 4));
            double l = (kick * .8 + hat + bass + chord) * gain, rr = (kick * .8 + hat * .7 + bass + chord * .9) * gain; mix[i * 2] = (float) l; mix[i * 2 + 1] = (float) rr;
        }
        Core.Track t = new Core.Track("Mix", 0); t.fx.eq = false; t.volume = 1; t.clips.add(new Core.Clip("Mix", new Core.Source() { public int frames() { return frames; } public float sample(int f, int c) { return mix[f * 2 + c]; } }, "", 0)); s.tracks.add(t);
        return s;
    }

    public static void main(String[] a) throws Exception {
        check(ProMastering.NAMES.length == ProMastering.TARGETS.length && ProMastering.NAMES.length == 5, "five presets including Afrobeats / Amapiano");
        for (double level : new double[]{.15, 1.0}) for (int i = 0; i < ProMastering.NAMES.length; i++) {
            Core.Session s = song(level); ProMastering.Result r = ProMastering.apply(s, i);
            String id = ProMastering.NAMES[i] + " (mix " + String.format(Locale.US, "%.1f", r.beforeLufs) + " LUFS): ";
            check(r.measured, id + "measured");
            check(Math.abs(r.lufs - ProMastering.TARGETS[i]) <= .6, id + "loudness " + r.lufs + " vs target " + ProMastering.TARGETS[i]);
            check(r.truePeak <= ProMastering.CEILING + .15, id + "true peak " + r.truePeak + " dBTP");
            // The chain left on the output reproduces the measured result exactly.
            Pro.Loudness again = ProMastering.measure(s, s.end()); check(Math.abs(again.integrated() - r.lufs) < .05, id + "chain on the output matches the report");
            System.out.printf("  %-38s mix %6.1f LUFS → %6.1f LUFS, TP %5.1f dBTP, limiter %+5.1f dB, trim %+5.1f dB, %d passes%n", ProMastering.NAMES[i], r.beforeLufs, r.lufs, r.truePeak, r.limiterDb, r.trimDb, r.passes);
        }
        ProMastering.Result silent = ProMastering.apply(new Core.Session(), 0); check(!silent.measured, "silent project: chain added, not measured");
        System.out.println("ProMasteringTests OK: " + checks + " checks");
    }
}

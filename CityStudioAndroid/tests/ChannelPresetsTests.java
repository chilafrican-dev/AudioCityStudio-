package ug.audiocity.studio;

import java.util.*;

/** Every factory preset is measured on test material and must land on its gain-staging targets. */
public final class ChannelPresetsTests {
    static int checks;
    static void check(boolean ok, String m) { checks++; if (!ok) throw new AssertionError(m); }

    static Core.Source buffer(float[] mono) { return new Core.Source() { public int frames() { return mono.length; } public float sample(int f, int c) { return mono[f]; } }; }

    /** Sung phrases with pauses (vibrato, harmonics, breath noise, a loud word), at a given average level. */
    static float[] voice(double levelDb, long seed) {
        Random r = new Random(seed); int n = Core.RATE * 8; float[] out = new float[n]; double ph = 0;
        for (int i = 0; i < n; i++) { double t = i / (double) Core.RATE; boolean sing = (t % 2) < 1.5; double loud = (t > 5 && t < 5.3) ? 2.2 : 1; double hz = 220 + 30 * Math.sin(t * 1.7) + 4 * Math.sin(2 * Math.PI * 5.5 * t); ph += 2 * Math.PI * hz / Core.RATE;
            double v = sing ? loud * (Math.sin(ph) + .5 * Math.sin(2 * ph) + .3 * Math.sin(3 * ph) + .15 * Math.sin(7 * ph)) + .05 * (r.nextDouble() - .5) : .003 * (r.nextDouble() - .5); out[i] = (float) v; }
        return scale(out, levelDb, false);
    }
    static float[] sustained(double levelDb, double hz) { int n = Core.RATE * 6; float[] out = new float[n]; for (int i = 0; i < n; i++) { double t = i / (double) Core.RATE; double env = .6 + .4 * Math.abs(Math.sin(Math.PI * t * 1.5)); out[i] = (float) (env * (Math.sin(2 * Math.PI * hz * t) + .4 * Math.sin(4 * Math.PI * hz * t) + .2 * Math.sin(6 * Math.PI * hz * t))); } return scale(out, levelDb, false); }
    static float[] hits(double peakDb, double hz, double decay, boolean noise, long seed) {
        Random r = new Random(seed); int n = Core.RATE * 6; float[] out = new float[n]; int every = Core.RATE / 2;
        for (int i = 0; i < n; i++) { int k = i % every; double t = k / (double) Core.RATE; double env = Math.exp(-t / decay); double v = noise ? (r.nextDouble() * 2 - 1) : Math.sin(2 * Math.PI * hz * t * (1 + 2 * Math.exp(-t * 40))); out[i] = (float) (env * v * ((i / every) % 4 == 3 ? 1 : .8)); }
        return scale(out, peakDb, true);
    }
    static float[] scale(float[] x, double db, boolean peak) {
        double m = 0, e = 0; int c = 0; for (float v : x) { m = Math.max(m, Math.abs(v)); if (Math.abs(v) > 1e-3) { e += v * v; c++; } }
        double now = peak ? m : Math.sqrt(e / Math.max(1, c)), g = Math.pow(10, db / 20) / now; for (int i = 0; i < x.length; i++) x[i] *= g; return x;
    }

    static float[] material(ChannelPresets.Preset p, double level, long seed) {
        switch (p.name) {
            case "Kick": return hits(level, 55, .12, false, seed);
            case "808": return hits(level, 45, .45, false, seed);
            case "Snare / clap": case "Percussion": case "Drum bus glue": return hits(level, 200, .08, true, seed);
            case "Hi-hats & shakers": return hits(level, 0, .03, true, seed);
            default: return p.category.equals("Vocals") ? voice(level, seed) : p.category.equals("Bass") ? sustained(level, 55) : sustained(level, 262);
        }
    }

    public static void main(String[] a) {
        check(ChannelPresets.ALL.size() >= 19, "factory presets for every kind of channel");
        check(ChannelPresets.categories().containsAll(Arrays.asList("Vocals", "Drums", "Bass", "Keys", "Guitar", "Synth")), "all categories");
        for (ChannelPresets.Preset p : ChannelPresets.ALL) {
            // Quiet phone recording, a well-recorded take, and a hot one: all must land on target.
            double[] levels = p.percussive ? new double[]{-24, -10, -1} : new double[]{-34, -20, -9};
            for (double level : levels) {
                ChannelPresets.Result r = ChannelPresets.calibrate(p, buffer(material(p, level, 7)), 100);
                String id = p.category + " / " + p.name + " from " + level + " dB: ";
                check(r.measured, id + "measured");
                check(r.outputPeakDb <= ChannelPresets.PEAK_CEILING + .05, id + "peak headroom kept, peak " + r.outputPeakDb);
                if (p.percussive) check(r.outputPeakDb >= ChannelPresets.PERCUSSIVE_PEAK_TARGET - 2.5, id + "drum peaks staged near -8 dBFS, got " + r.outputPeakDb);
                else check(Math.abs(r.outputActiveDb - ChannelPresets.SUSTAINED_TARGET) <= 1.5 || r.outputPeakDb >= ChannelPresets.PEAK_CEILING - .4, id + "average level staged to -18 dBFS, got " + r.outputActiveDb + " (peak " + r.outputPeakDb + ")");
                check(r.reductionDb >= p.minGr - .5 && r.reductionDb <= p.maxGr + .5, id + "gain reduction " + r.reductionDb + " within " + p.minGr + ".." + p.maxGr);
                check(!Double.isNaN(r.fx.inputDb + r.fx.outputDb + r.fx.threshold), id + "valid settings");
                if (level == levels[1]) System.out.printf("  %-26s in %+5.1f dB  GR %4.1f dB  out avg %6.1f  peak %5.1f dBFS%n", p.name, r.gainDb, r.reductionDb, r.outputActiveDb, r.outputPeakDb);
            }
        }
        ChannelPresets.Result empty = ChannelPresets.calibrate(ChannelPresets.ALL.get(0), null, 100); check(!empty.measured && empty.fx != null, "no audio: standard levels, clearly reported");
        System.out.println("ChannelPresetsTests OK: " + checks + " checks");
    }
}

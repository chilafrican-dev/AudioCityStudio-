package ug.audiocity.studio;

import java.io.IOException;
import java.util.*;

/**
 * Measured mastering presets for the output channel. Each preset builds a chain (gain trim, EQ, compressor, optional tape,
 * multiband, true-peak limiter), then renders the whole song through it and adjusts the trim and the limiter input until
 * the integrated loudness (ITU-R BS.1770 / EBU R128) lands on the preset's target with true peaks at or below -1 dBTP.
 */
public final class ProMastering {
    private ProMastering() {}

    public static final String[] NAMES = {"Streaming · clean · −14 LUFS", "Loud · club & rap · −9 LUFS", "Warm & gentle · acoustic · −16 LUFS", "Podcast voice · −16 LUFS", "Afrobeats / Amapiano · −9.5 LUFS"};
    public static final double[] TARGETS = {-14, -9, -16, -16, -9.5};
    public static final double CEILING = -1;

    /** Builds preset i's chain on the output channel (replacing what was there). The first insert is the gain trim, the last the limiter. */
    public static void build(Core.Session s, int i) {
        s.masterInserts.clear();
        Pro.Insert trim = new Pro.Insert(Pro.UTILITY); trim.touch(); s.masterInserts.add(trim);
        Pro.Insert eq = new Pro.Insert(Pro.EQ8); eq.set(0, 1); eq.set(2, i == 3 ? 70 : 28);
        if (i == 2) { eq.set(30, 1); eq.set(31, 2); eq.set(32, 9000); eq.set(33, -1.5); }
        if (i == 1 || i == 4) { eq.set(5, 1); eq.set(6, 1); eq.set(7, i == 4 ? 60 : 90); eq.set(8, i == 4 ? 1.2 : 1.5); }
        eq.touch(); s.masterInserts.add(eq);
        boolean loud = i == 1 || i == 4;
        Pro.Insert comp = new Pro.Insert(ProFx.COMP); comp.set(0, loud ? -14 : -18); comp.set(1, loud ? 3 : 2); comp.set(2, 30); comp.set(3, 200); comp.set(4, 8); comp.set(5, loud ? 2 : 1); comp.touch(); s.masterInserts.add(comp);
        if (loud) { Pro.Insert tape = new Pro.Insert(ProFx.TAPE); tape.set(0, i == 4 ? 3 : 4); tape.set(4, 40); tape.touch(); s.masterInserts.add(tape); }
        if (i != 3) s.masterInserts.add(new Pro.Insert(Pro.MULTIBAND));
        Pro.Insert lim = new Pro.Insert(Pro.LIMITER); lim.set(0, 0); lim.set(1, CEILING); lim.touch(); s.masterInserts.add(lim);
    }

    public static final class Result {
        public double beforeLufs, beforePeak, lufs, truePeak, limiterDb, trimDb, target; public boolean measured, reached; public int passes;
        public String report(String name) {
            if (!measured) return "The song is silent, so the chain was added with neutral levels. Apply the preset again once there is music.";
            String s = String.format(Locale.US, "Your mix before mastering: %.1f LUFS integrated, true peak %.1f dBTP.%n%nAfter %s:%n  Integrated loudness %.1f LUFS (target %.1f)%n  True peak %.1f dBTP (ceiling %.1f)%n  Limiter input %+.1f dB, gain trim %+.1f dB%n%nMeasured over the whole song in %d passes, with the same meter as the Master page.",
                beforeLufs, beforePeak, name, lufs, target, truePeak, CEILING, limiterDb, trimDb, passes);
            if (!reached) s += "\n\nThe target needs more than 18 dB of limiting, which would distort. Raise the loudest tracks or use a less loud preset.";
            return s;
        }
    }

    static Pro.Insert first(Core.Session s, String kind) { for (Pro.Insert x : s.masterInserts) if (x.kind.equals(kind)) return x; return null; }

    /** Renders and measures the song with the current master chain. */
    static Pro.Loudness measure(Core.Session s, long frames) throws IOException { Core.Session c = s.copy(); c.referenceOn = false; return Pro.analyse(c, frames); }

    /** Builds preset i and sets its gain trim and limiter so the song lands on the target loudness. */
    public static Result apply(Core.Session s, int i) throws IOException {
        Result r = new Result(); r.target = TARGETS[i];
        List<Pro.Insert> before = new ArrayList<>(s.masterInserts);
        long frames = Math.min(Core.MAX_FRAMES, s.end()); 
        if (frames > 0) { s.masterInserts.clear(); Pro.Loudness dry = measure(s, frames); r.beforeLufs = dry.integrated(); r.beforePeak = dry.truePeakDb; }
        build(s, i);
        if (frames <= 0 || !Double.isFinite(r.beforeLufs)) { s.masterInserts.clear(); s.masterInserts.addAll(before); build(s, i); return r; }
        r.measured = true; Pro.Insert trim = first(s, Pro.UTILITY), lim = first(s, Pro.LIMITER);
        double drive = 0, gain = 0, usedDrive = 0, usedGain = 0; Pro.Loudness m = null;
        for (int pass = 0; pass < 7; pass++) {
            trim.set(0, gain); trim.touch(); lim.set(0, drive); lim.touch(); m = measure(s, frames); r.passes = pass + 1; usedDrive = drive; usedGain = gain;
            double miss = r.target - m.integrated(); if (!Double.isFinite(miss)) break;
            if (Math.abs(miss) <= .3) break;
            // Louder: push the limiter. Quieter: first back the limiter off, then trim the input down.
            if (pass == 0) { gain = Math.max(-24, Math.min(24, (r.target - 4) - m.integrated())); continue; } // clean gain to about 4 dB under the target
            double next = drive + miss * (drive > 6 ? 1.25 : 1); // heavy limiting returns less loudness per dB
            if (next < 0) { gain = Math.max(-24, gain + next); drive = 0; } else if (next > 12 && gain < 24) { gain = Math.min(24, gain + (next - 12)); drive = 12; } else drive = Math.min(18, next);
            if (drive >= 18 && miss > 0 && pass > 2) break;
        }
        drive = usedDrive; gain = usedGain; trim.set(0, gain); trim.touch(); lim.set(0, drive); lim.touch(); // keep the settings that were actually measured
        r.lufs = m.integrated(); r.truePeak = m.truePeakDb; r.limiterDb = drive; r.trimDb = gain; r.reached = Math.abs(r.target - r.lufs) <= .6;
        return r;
    }
}

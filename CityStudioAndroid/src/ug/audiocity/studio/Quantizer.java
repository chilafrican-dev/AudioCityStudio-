package ug.audiocity.studio;

import java.util.*;

/**
 * Full quantize settings, modelled on Logic Pro's region quantize parameters, applied on the song's tempo map:
 *  - Grid: straight values 1/1 to 1/64, triplets 1/2T to 1/32T, mixed grids (8 & 8T, 16 & 16T, 16 & 8T) and swing grids
 *    (8 Swing and 16 Swing, A light to F heavy);
 *  - Strength (Q-Strength): how far notes move toward the grid;
 *  - Swing (Q-Swing): delays every second grid position of a straight grid (50% = straight);
 *  - Range (Q-Range, ms): 0 = every note; positive = only notes farther from the grid than this move (keeps tight notes
 *    and their feel); negative = only notes closer than this move (fixes small errors, leaves deliberate pushes alone);
 *  - Flam (Q-Flam, ms): notes that land together are spread out (positive: lowest first, negative: highest first);
 *  - Length (Q-Length): how far note ends move to the grid;
 *  - Velocity (Q-Velocity): how far velocities move toward the take's average (evens out the dynamics).
 */
public final class Quantizer {
    private Quantizer() {}

    public static final String[] GRIDS = {"Off", "1/1 note", "1/2 note", "1/4 note", "1/8 note", "1/16 note", "1/32 note", "1/64 note",
        "1/2 triplet", "1/4 triplet", "1/8 triplet", "1/16 triplet", "1/32 triplet", "8 & 8 triplet", "16 & 16 triplet", "16 & 8 triplet",
        "8 Swing A", "8 Swing B", "8 Swing C", "8 Swing D", "8 Swing E", "8 Swing F", "16 Swing A", "16 Swing B", "16 Swing C", "16 Swing D", "16 Swing E", "16 Swing F"};
    static final double[] SWING_PRESETS = {54, 58, 62, 66, 70, 75};

    public static final class Settings {
        public int grid; public double strength = 100, swing = 50, rangeMs, flamMs, length, velocity;
        public Settings() {}
        public Settings(int grid) { this.grid = grid; }
        public boolean on() { return grid > 0 || velocity > 0; }
        public Settings copy() { return parse(encode()); }
        public String encode() { return grid + "," + strength + "," + swing + "," + rangeMs + "," + flamMs + "," + length + "," + velocity; }
        public static Settings parse(String s) {
            Settings q = new Settings(); if (s == null || s.isEmpty()) return q;
            try { String[] p = s.split(","); q.grid = Math.max(0, Math.min(GRIDS.length - 1, Integer.parseInt(p[0]))); q.strength = Double.parseDouble(p[1]); q.swing = Double.parseDouble(p[2]); q.rangeMs = Double.parseDouble(p[3]); q.flamMs = Double.parseDouble(p[4]); q.length = Double.parseDouble(p[5]); q.velocity = Double.parseDouble(p[6]); } catch (Exception ignored) {}
            return q;
        }
        public String summary() {
            if (!on()) return "Off · exactly as played";
            StringBuilder b = new StringBuilder(GRIDS[grid]); if (strength < 100) b.append(" · ").append(Math.round(strength)).append("%");
            if (swing != 50 && straight(grid)) b.append(" · swing ").append(Math.round(swing)).append("%"); if (rangeMs != 0) b.append(" · range ").append(Math.round(rangeMs)).append(" ms");
            if (flamMs != 0) b.append(" · flam ").append(Math.round(flamMs)).append(" ms"); if (length > 0) b.append(" · length ").append(Math.round(length)).append("%"); if (velocity > 0) b.append(" · velocity ").append(Math.round(velocity)).append("%");
            return b.toString();
        }
        /** The old simple setting: notes per beat (1 = 1/4 … 8 = 1/32). */
        public static Settings division(int perBeat) { int g = perBeat <= 0 ? 0 : perBeat == 1 ? 3 : perBeat == 2 ? 4 : perBeat == 4 ? 5 : perBeat == 8 ? 6 : 7; return new Settings(g); }
    }

    static boolean straight(int g) { return g >= 1 && g <= 7; }
    /** Step in beats of a straight or triplet grid. */
    static double step(int g) {
        switch (g) { case 1: return 4; case 2: return 2; case 3: return 1; case 4: return .5; case 5: return .25; case 6: return .125; case 7: return .0625;
            case 8: return 4 / 3.0; case 9: return 2 / 3.0; case 10: return 1 / 3.0; case 11: return 1 / 6.0; case 12: return 1 / 12.0; default: return 0; }
    }

    static double roundTo(double beat, double step) { return Math.round(beat / step) * step; }
    /** Pairs of steps where the second sits at swing% of the pair (50 = straight). */
    static double swung(double beat, double step, double swingPct) {
        double pair = step * 2, base = Math.floor(beat / pair) * pair, second = base + pair * swingPct / 100; double[] c = {base, second, base + pair}; double best = c[0];
        for (double x : c) if (Math.abs(x - beat) < Math.abs(best - beat)) best = x; return best;
    }

    /** The grid position (in beats) a note at this beat moves to. */
    public static double target(Settings q, double beat) {
        int g = q.grid;
        if (g >= 1 && g <= 7) return q.swing != 50 ? swung(beat, step(g), q.swing) : roundTo(beat, step(g));
        if (g >= 8 && g <= 12) return roundTo(beat, step(g));
        if (g >= 13 && g <= 15) { double a = roundTo(beat, g == 13 ? .5 : .25), b = roundTo(beat, g == 14 ? 1 / 6.0 : 1 / 3.0); return Math.abs(a - beat) <= Math.abs(b - beat) ? a : b; }
        if (g >= 16 && g <= 27) { boolean eighth = g <= 21; return swung(beat, eighth ? .5 : .25, SWING_PRESETS[(g - 16) % 6]); }
        return beat;
    }

    /** Quantizes notes whose positions are relative to `origin` (a song frame), on the song's tempo map. */
    public static void apply(Settings q, List<Pro.Note> notes, long origin, Pro.TempoMap map) {
        if (q == null || !q.on() || notes.isEmpty()) return;
        double strength = Math.max(0, Math.min(100, q.strength)) / 100, len = Math.max(0, Math.min(100, q.length)) / 100;
        if (q.grid > 0) for (Pro.Note n : notes) {
            long abs = origin + n.start, end = abs + n.length; double beat = map.beatAt(abs), tb = target(q, beat); long to = map.frameAt(tb);
            double deltaMs = (to - abs) * 1000.0 / Core.RATE; boolean move = q.rangeMs == 0 || (q.rangeMs > 0 ? Math.abs(deltaMs) > q.rangeMs : Math.abs(deltaMs) <= -q.rangeMs);
            long newStart = move ? Math.round(abs + (to - abs) * strength) : abs;
            long newEnd = end + (newStart - abs);
            if (len > 0) { long endGrid = map.frameAt(target(q, map.beatAt(end))); newEnd = Math.round(newEnd + (endGrid - newEnd) * len); }
            long minLen = Math.max(1, (map.frameAt(tb + Math.max(step(q.grid) > 0 ? step(q.grid) : .25, .0625) / 4) - to));
            n.start = Math.max(0, newStart - origin); n.length = Math.max(Math.min(n.length, minLen), newEnd - newStart);
        }
        if (q.flamMs != 0) {
            Map<Long, List<Pro.Note>> together = new TreeMap<>(); for (Pro.Note n : notes) together.computeIfAbsent(n.start, k -> new ArrayList<>()).add(n);
            long flam = Math.round(Math.abs(q.flamMs) * Core.RATE / 1000.0);
            for (List<Pro.Note> chord : together.values()) { if (chord.size() < 2) continue; chord.sort((a, b) -> q.flamMs > 0 ? a.pitch - b.pitch : b.pitch - a.pitch); for (int i = 1; i < chord.size(); i++) { Pro.Note n = chord.get(i); n.start += i * flam; n.length = Math.max(1, n.length - i * flam); } }
        }
        if (q.velocity > 0) {
            double avg = 0; for (Pro.Note n : notes) avg += n.velocity; avg /= notes.size(); double v = Math.max(0, Math.min(100, q.velocity)) / 100;
            for (Pro.Note n : notes) n.velocity = (int) Math.max(1, Math.min(127, Math.round(n.velocity + (avg - n.velocity) * v)));
        }
        notes.sort((a, b) -> Long.compare(a.start, b.start));
    }
}

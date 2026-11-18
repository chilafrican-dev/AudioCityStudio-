package ug.audiocity.studio;

import java.util.*;

/** Every quantize setting does what its description says, on the song's tempo map. */
public final class QuantizerTests {
    static int checks;
    static void check(boolean ok, String m) { checks++; if (!ok) throw new AssertionError(m); }
    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-9; }

    public static void main(String[] a) {
        Quantizer.Settings q = new Quantizer.Settings();
        // Grids
        double[][] cases = { // grid, input beat, expected beat
            {1, 1.9, 0}, {1, 2.1, 4}, {2, 1.2, 2}, {3, 1.4, 1}, {4, 1.3, 1.5}, {5, 1.13, 1.25}, {6, 1.07, 1.125}, {7, 1.05, 1.0625},
            {8, 1.2, 4 / 3.0}, {9, .7, 2 / 3.0}, {10, .3, 1 / 3.0}, {11, .2, 1 / 6.0}, {12, .09, 1 / 12.0},
            {13, .34, 1 / 3.0}, {13, .48, .5}, {14, .17, 1 / 6.0}, {14, .26, .25}, {15, .3, 1 / 3.0}, {15, .24, .25}};
        for (double[] c : cases) { q.grid = (int) c[0]; check(near(Quantizer.target(q, c[1]), c[2]), Quantizer.GRIDS[q.grid] + ": " + c[1] + " -> " + Quantizer.target(q, c[1]) + " want " + c[2]); }
        // Swing grids: the off-eighth moves later, A light to F heavy; downbeats stay
        double prev = .5; for (int g = 16; g <= 21; g++) { q.grid = g; double t = Quantizer.target(q, .6); check(t > prev, Quantizer.GRIDS[g] + " swings later than the previous: " + t); prev = t; check(near(Quantizer.target(q, .05), 0), "downbeat stays"); }
        q.grid = 22; check(near(Quantizer.target(q, .25), .5 * .54), "16 Swing A puts the off-sixteenth at 54%");
        // Q-Swing on a straight grid
        q = new Quantizer.Settings(4); q.swing = 66; check(near(Quantizer.target(q, .62), 1.0 * .66), "1/8 with 66% swing: " + Quantizer.target(q, .62));
        // Apply on a tempo map with notes relative to a clip starting mid-bar
        Core.Session s = new Core.Session(); s.bpm = 90; Pro.TempoMap map = new Pro.TempoMap(s); long beat = Core.beat(90), origin = beat * 3 + 777;
        List<Pro.Note> notes = notes(map, origin, new double[]{4.04, 4.49, 5.02, 5.3}, new int[]{100, 60, 120, 40});
        Quantizer.Settings full = new Quantizer.Settings(5); Quantizer.apply(full, notes, origin, map);
        for (Pro.Note n : notes) { double b = map.beatAt(origin + n.start); check(Math.abs(b * 4 - Math.rint(b * 4)) < 1e-4, "on the song's 1/16 grid: " + b); }
        // Strength 50%
        List<Pro.Note> half = notes(map, origin, new double[]{4.1}, new int[]{100}); Quantizer.Settings h = new Quantizer.Settings(3); h.strength = 50; Quantizer.apply(h, half, origin, map);
        check(Math.abs(map.beatAt(origin + half.get(0).start) - 4.05) < 1e-4, "50% strength moves halfway");
        // Range: + only far notes move, − only close notes move
        double tick = 1000.0 * beat / Core.RATE; // ms per beat
        List<Pro.Note> r1 = notes(map, origin, new double[]{4 + 15 / tick, 4.5 + 90 / tick}, new int[]{100, 100}); Quantizer.Settings pr = new Quantizer.Settings(4); pr.rangeMs = 40; Quantizer.apply(pr, r1, origin, map);
        check(Math.abs(map.beatAt(origin + r1.get(0).start) - (4 + 15 / tick)) < 1e-3, "positive range keeps a note 15 ms off"); check(Math.abs(map.beatAt(origin + r1.get(1).start) - 4.5) < 1e-4, "positive range moves a note 90 ms off");
        List<Pro.Note> r2 = notes(map, origin, new double[]{4 + 15 / tick, 4.5 + 90 / tick}, new int[]{100, 100}); Quantizer.Settings nr = new Quantizer.Settings(4); nr.rangeMs = -40; Quantizer.apply(nr, r2, origin, map);
        check(Math.abs(map.beatAt(origin + r2.get(0).start) - 4) < 1e-4, "negative range fixes the small error"); check(Math.abs(map.beatAt(origin + r2.get(1).start) - (4.5 + 90 / tick)) < 1e-3, "negative range leaves the deliberate push");
        // Flam: a chord spreads lowest first by 20 ms per note
        List<Pro.Note> chord = new ArrayList<>(); for (int p : new int[]{67, 60, 64}) chord.add(new Pro.Note(p, 100, map.frameAt(4.02) - origin, beat, 0));
        Quantizer.Settings fl = new Quantizer.Settings(3); fl.flamMs = 20; Quantizer.apply(fl, chord, origin, map); long step = Math.round(.02 * Core.RATE);
        check(chord.get(0).pitch == 60 && chord.get(1).pitch == 64 && chord.get(2).pitch == 67 && chord.get(1).start - chord.get(0).start == step && chord.get(2).start - chord.get(1).start == step, "flam spreads the chord upward");
        // Length and velocity
        List<Pro.Note> lv = notes(map, origin, new double[]{4, 5}, new int[]{120, 40}); lv.get(0).length = map.frameAt(4.42) - map.frameAt(4);
        Quantizer.Settings ln = new Quantizer.Settings(4); ln.length = 100; ln.velocity = 50; Quantizer.apply(ln, lv, origin, map);
        check(Math.abs(map.beatAt(origin + lv.get(0).end()) - 4.5) < 1e-4, "length 100% puts the note end on the grid"); check(lv.get(0).velocity == 100 && lv.get(1).velocity == 60, "velocity 50% evens toward the average: " + lv.get(0).velocity + " " + lv.get(1).velocity);
        // Off keeps everything; settings survive saving
        List<Pro.Note> off = notes(map, origin, new double[]{4.013}, new int[]{77}); long was = off.get(0).start; Quantizer.apply(new Quantizer.Settings(), off, origin, map); check(off.get(0).start == was, "Off keeps the performance");
        Quantizer.Settings sv = new Quantizer.Settings(19); sv.strength = 80; sv.rangeMs = -25; sv.flamMs = 12; sv.length = 30; sv.velocity = 20; check(Quantizer.Settings.parse(sv.encode()).encode().equals(sv.encode()), "settings saved and restored");
        System.out.println("QuantizerTests OK: " + checks + " checks");
    }

    static List<Pro.Note> notes(Pro.TempoMap map, long origin, double[] beats, int[] vel) {
        List<Pro.Note> l = new ArrayList<>(); for (int i = 0; i < beats.length; i++) l.add(new Pro.Note(60 + i, vel[i], map.frameAt(beats[i]) - origin, Core.beat(90) / 4, 0)); return l;
    }
}

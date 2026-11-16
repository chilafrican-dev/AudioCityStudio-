package ug.audiocity.studio;

import java.util.*;

/**
 * AI MIDI generator: a small variable-order Markov melody model plus rule-based chords, bass and drums.
 * Platform independent (no Android classes), deterministic for a given seed, and host-testable.
 * Positions are in beats; {@link #toNotes} converts them to the frame-based {@link Pro.Note}s that live MIDI clips use.
 * Pitches are always inside the chosen scale; melody notes on strong beats are snapped to the current chord.
 */
public final class AiMidiGenerator {
    private AiMidiGenerator() {}

    public static final int MIN_BARS = 1, MAX_BARS = 64;
    static final int[] MAJOR = {0, 2, 4, 5, 7, 9, 11}, MINOR = {0, 2, 3, 5, 7, 8, 10};
    static final int[][] PROG_MAJOR = {{0, 4, 5, 3}, {0, 5, 3, 4}, {0, 3, 4, 0}, {0, 5, 1, 4}, {0, 2, 3, 4}};
    static final int[][] PROG_MINOR = {{0, 5, 2, 6}, {0, 3, 6, 4}, {0, 5, 3, 4}, {0, 6, 5, 4}, {0, 3, 0, 4}};
    static final int[] DURS = {1, 2, 3, 4, 6, 8, 12, 16};          // sixteenth notes
    static final int REST = -100, MAX_ORDER = 3, LO = 60, HI = 84;

    /** One generated note. Positions and lengths are in beats from the start of the clip. */
    public static final class Note {
        public final int pitch, velocity; public final double startBeat, lengthBeat;
        Note(int pitch, int velocity, double startBeat, double lengthBeat) { this.pitch = pitch; this.velocity = velocity; this.startBeat = startBeat; this.lengthBeat = lengthBeat; }
    }

    public static final class Options {
        public int key = 9;                 // pitch class 0..11 (0 = C)
        public boolean minor = true;
        public int bars = 8, beatsPerBar = 4;
        public double creativity = .9;      // 0 = predictable, 1 = adventurous
        public long seed = 1;
        public boolean melody = true, chords = true, bass = true, drums = true;
    }

    public static final class Result {
        public final List<Note> melody = new ArrayList<>(), chords = new ArrayList<>(), bass = new ArrayList<>(), drums = new ArrayList<>();
        public int key; public boolean minor; public int bars, beatsPerBar;
    }

    // ------------------------------------------------------------------ Markov model

    private static int tok(int off, int dur) { return (off + 200) * 32 + dur; }
    private static int offOf(int t) { return t / 32 - 200; }
    private static int durOf(int t) { return t % 32; }

    private static final class Model {
        final List<Map<String, Map<Integer, Integer>>> tables = new ArrayList<>();
        Model() { for (int i = 0; i <= MAX_ORDER; i++) tables.add(new HashMap<String, Map<Integer, Integer>>()); }
        static String ctx(List<Integer> h, int n) { StringBuilder s = new StringBuilder(); for (int i = h.size() - n; i < h.size(); i++) s.append(h.get(i)).append(','); return s.toString(); }
        void train(List<Integer> seq) {
            List<Integer> h = new ArrayList<>(); for (int i = 0; i < MAX_ORDER; i++) h.add(-1);
            for (int t : seq) {
                for (int n = 0; n <= MAX_ORDER; n++) {
                    Map<Integer, Integer> m = tables.get(n).get(ctx(h, n)); if (m == null) { m = new HashMap<Integer, Integer>(); tables.get(n).put(ctx(h, n), m); }
                    Integer c = m.get(t); m.put(t, c == null ? 1 : c + 1);
                }
                h.add(t);
            }
        }
        interface Allowed { boolean ok(int t); }
        /** Samples the next token with back-off from the longest context; null if nothing is allowed. */
        Integer sample(List<Integer> hist, Random rng, Allowed allowed, double exponent) {
            List<Integer> h = new ArrayList<>(); for (int i = 0; i < MAX_ORDER; i++) h.add(-1); h.addAll(hist);
            for (int n = MAX_ORDER; n >= 0; n--) {
                Map<Integer, Integer> m = tables.get(n).get(ctx(h, n)); if (m == null) continue;
                List<Integer> keys = new ArrayList<>(m.keySet()); Collections.sort(keys);          // sorted: deterministic order
                double total = 0; double[] w = new double[keys.size()];
                for (int i = 0; i < w.length; i++) { int t = keys.get(i); if (!allowed.ok(t)) continue; w[i] = Math.pow(m.get(t), exponent); total += w[i]; }
                if (total <= 0) continue;
                double r = rng.nextDouble() * total;
                for (int i = 0; i < w.length; i++) { r -= w[i]; if (r <= 0 && w[i] > 0) return keys.get(i); }
                for (int i = w.length - 1; i >= 0; i--) if (w[i] > 0) return keys.get(i);
            }
            return null;
        }
    }

    /** Bootstrap corpus: motif-based, scale-aware phrases (the model learns their shapes and rhythms). */
    private static Model bootstrap(int[] scale, Random rng) {
        Model model = new Model();
        int[] firstDeg = {0, 2, 4}, steps = {-2, -1, -1, 0, 1, 1, 2, 3}, motifDur = {2, 2, 4, 4, 4, 6, 8}, motifLen = {3, 4, 5}, reps = {2, 3, 4}, restDur = {2, 4};
        for (int p = 0; p < 300; p++) {
            int deg = firstDeg[rng.nextInt(3)]; List<int[]> motif = new ArrayList<>();
            for (int k = 0, n = motifLen[rng.nextInt(3)]; k < n; k++) { deg += steps[rng.nextInt(steps.length)]; deg = Math.max(-4, Math.min(11, deg)); motif.add(new int[]{deg, motifDur[rng.nextInt(motifDur.length)]}); }
            List<Integer> seq = new ArrayList<>();
            for (int rep = 0, nr = reps[rng.nextInt(3)]; rep < nr; rep++) {
                for (int[] m : motif) {
                    int d = m[0]; if (rep > 0 && rng.nextDouble() < .25) d += rng.nextBoolean() ? 1 : -1;
                    int oct = Math.floorDiv(d, 7), idx = Math.floorMod(d, 7);
                    seq.add(tok(12 * oct + scale[idx], m[1]));
                }
                if (rng.nextBoolean()) seq.add(tok(REST, restDur[rng.nextInt(2)]));
            }
            model.train(seq);
        }
        return model;
    }

    // ------------------------------------------------------------------ generation

    public static Result generate(Options o) {
        if (o == null) throw new IllegalArgumentException("Options are required");
        if (o.key < 0 || o.key > 11) throw new IllegalArgumentException("Key must be 0..11");
        if (o.bars < MIN_BARS || o.bars > MAX_BARS) throw new IllegalArgumentException("Bars must be " + MIN_BARS + ".." + MAX_BARS);
        if (o.beatsPerBar < 1 || o.beatsPerBar > 16) throw new IllegalArgumentException("Beats per bar must be 1..16");
        if (Double.isNaN(o.creativity) || o.creativity < 0 || o.creativity > 1) throw new IllegalArgumentException("Creativity must be 0..1");
        int[] scale = o.minor ? MINOR : MAJOR; Random rng = new Random(o.seed);
        Result r = new Result(); r.key = o.key; r.minor = o.minor; r.bars = o.bars; r.beatsPerBar = o.beatsPerBar;
        int[][] progs = o.minor ? PROG_MINOR : PROG_MAJOR; int[] prog = progs[rng.nextInt(progs.length)];
        int[][] chordTones = new int[o.bars][];                         // MIDI pitches per bar
        for (int bar = 0; bar < o.bars; bar++) {
            int deg = prog[bar % prog.length]; int[] ch = new int[3];
            for (int k = 0; k < 3; k++) { int d = deg + 2 * k; ch[k] = o.key + 48 + scale[d % 7] + 12 * (d / 7); }
            chordTones[bar] = ch;
            double t0 = bar * (double) o.beatsPerBar;
            if (o.chords) for (int p : ch) r.chords.add(new Note(p, 70, t0, o.beatsPerBar - .05));
            if (o.bass) addBass(r.bass, o, o.key + 36 + scale[deg % 7], t0, rng);
        }
        if (o.drums) addDrums(r.drums, o, rng);
        if (o.melody) addMelody(r.melody, o, scale, chordTones, rng);
        return r;
    }

    private static void addBass(List<Note> out, Options o, int root, double t0, Random rng) {
        if (o.beatsPerBar == 4) {
            int[][] pat = {{0, 6, 0}, {6, 2, 0}, {8, 4, 0}, {12, 2, 12}, {14, 2, 0}};
            for (int[] p : pat) { int pitch = root + p[2]; if (p[0] == 8 && rng.nextDouble() < .3) pitch = root + 7 - 12; out.add(new Note(pitch, 92, t0 + p[0] / 4.0, p[1] / 4.0 - .04)); }
        } else for (int b = 0; b < o.beatsPerBar; b++) out.add(new Note(root, 92, t0 + b, .9));
    }

    private static void addDrums(List<Note> out, Options o, Random rng) {
        int spb = o.beatsPerBar * 4;
        for (int bar = 0; bar < o.bars; bar++) {
            double t0 = bar * (double) o.beatsPerBar;
            for (int s = 0; s < spb; s++) {
                double t = t0 + s / 4.0;
                boolean kick = o.beatsPerBar == 4 ? (s == 0 || s == 10 || (s == 7 && rng.nextDouble() < .3)) : s == 0;
                boolean snare = o.beatsPerBar == 4 ? (s == 4 || s == 12) : (s % 8 == 4);
                if (kick) out.add(new Note(36, 112, t, .25));
                if (snare) out.add(new Note(38, 104, t, .25));
                if (s % 2 == 0) out.add(new Note(42, s % 4 == 0 ? 96 : 80, t, .12));
                else if (rng.nextDouble() < .25) out.add(new Note(42, 60, t, .12));
            }
            if (bar % 4 == 3 && o.beatsPerBar == 4) for (int s = 13; s <= 15; s++) out.add(new Note(38, 90 + 8 * (s - 13), t0 + s / 4.0, .25));
        }
    }

    private static void addMelody(List<Note> out, Options o, int[] scale, int[][] chordTones, Random rng) {
        final Model model = bootstrap(scale, rng);
        final int total = o.bars * o.beatsPerBar * 4, spb = o.beatsPerBar * 4, key = o.key;
        final double exponent = 1.6 - 1.1 * o.creativity;               // creative = flatter odds, predictable = peaky
        final boolean[] inScale = new boolean[12]; for (int s : scale) inScale[(s + key) % 12] = true;
        List<Integer> hist = new ArrayList<>(); int pos = 0, lastPitch = -1;
        while (pos < total) {
            final int at = pos, last = lastPitch;
            Integer t = model.sample(hist, rng, new Model.Allowed() { public boolean ok(int x) {
                int du = durOf(x), off = offOf(x); if (at + du > total) return false; if (off == REST) return true;
                int p = key + 60 + off; if (p < LO || p > HI || !inScale[((p % 12) + 12) % 12]) return false;
                return last < 0 || Math.abs(p - last) <= 9; } }, exponent);
            if (t == null) { int du = Math.min(total - pos, 16); int d = 1; for (int x : DURS) if (x <= du) d = x; t = tok(REST, d); }
            int off = offOf(t), du = durOf(t);
            if (off != REST) {
                int p = key + 60 + off;
                if (pos % 8 == 0) {                                      // strong beat: land on a chord tone
                    int[] ct = chordTones[pos / spb]; int best = -1, bestDist = 1 << 20;
                    for (int c = LO; c <= HI; c++) for (int tone : ct) if (((c - tone) % 12 + 12) % 12 == 0 && Math.abs(c - p) < bestDist && (last < 0 || Math.abs(c - last) <= 9)) { best = c; bestDist = Math.abs(c - p); }
                    if (best >= 0) { p = best; off = p - key - 60; t = tok(off, du); }
                }
                int vel = (pos % 4 == 0 ? 96 : 84) + rng.nextInt(11) - 5;
                out.add(new Note(p, Math.max(1, Math.min(127, vel)), pos / 4.0, Math.max(.05, du / 4.0 - .04)));
                lastPitch = p;
            }
            hist.add(t); pos += du;
        }
    }

    // ------------------------------------------------------------------ conversion to live MIDI clip notes

    /**
     * Converts beat-based notes to frame-based {@link Pro.Note}s relative to the clip start, following the project's tempo map.
     * @param clipStartBeat the beat where the clip begins on the timeline
     */
    /** Maps generator drum notes (GM 36 kick, 38 snare, 42 hat) onto drum-rack pads: 36 kick, 37 snare, 39 closed hat. */
    public static List<Note> toRackPitches(List<Note> drums) {
        List<Note> out = new ArrayList<>();
        for (Note n : drums) out.add(new Note(n.pitch == 38 ? 37 : n.pitch == 42 ? 39 : n.pitch, n.velocity, n.startBeat, n.lengthBeat));
        return out;
    }

    public static List<Pro.Note> toNotes(List<Note> notes, Pro.TempoMap map, double clipStartBeat, int channel) {
        List<Pro.Note> out = new ArrayList<>(); long base = map.frameAt(clipStartBeat);
        for (Note n : notes) {
            long s = map.frameAt(clipStartBeat + n.startBeat) - base, e = map.frameAt(clipStartBeat + n.startBeat + n.lengthBeat) - base;
            out.add(new Pro.Note(n.pitch, n.velocity, Math.max(0, s), Math.max(1, e - s), channel));
        }
        return out;
    }
}

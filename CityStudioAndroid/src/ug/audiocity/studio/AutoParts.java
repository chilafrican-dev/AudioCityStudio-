package ug.audiocity.studio;

import java.util.*;

/**
 * Beginner-friendly automatic parts: melody, bass, rhythm guitar and solo guitar.
 * The generator reads the chords already in the project (or invents a matching progression), then writes a part that
 * always fits them: strong beats land on chord tones, everything stays in the key, phrases repeat and answer each other.
 * Each part has several playing styles; options() returns five different takes to audition. Pure Java, host-testable.
 */
public final class AutoParts {
    private AutoParts() {}

    public static final int MELODY = 0, BASS = 1, RHYTHM = 2, SOLO = 3;
    public static final String[] PART_NAMES = {"Melody", "Bass", "Rhythm guitar", "Solo guitar"};
    public static final String[] MOODS = {"Calm", "Happy", "Sad", "Energetic"};
    public static final String[][] STYLES = {
        {"Singable", "Catchy hook", "Call & answer", "Flowing", "Afro groove"},
        {"Steady", "Pumping", "Walking", "Afro bounce", "Octave funk", "Slide groove", "Sub 808", "Fingerstyle", "Lock to kick"},
        {"Open strum", "Arpeggio", "Palm mute", "Offbeat chop", "Afro pluck", "Funk scratch"},
        {"Pentatonic lick", "Melodic lead", "Call & answer", "Fast run", "Afro riff"}};
    public static final int OPTIONS = 5;

    static final String[] KEYS = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    static final int[] MAJOR = {0, 2, 4, 5, 7, 9, 11}, MINOR = {0, 2, 3, 5, 7, 8, 10};
    static final int[] PENT_MAJOR = {0, 2, 4, 7, 9}, PENT_MINOR = {0, 3, 5, 7, 10};

    // ------------------------------------------------------------------ chords

    /** A triad. quality 0 = major, 1 = minor, 2 = diminished. */
    public static final class Chord {
        public final int root, quality; public final int[] pcs;
        Chord(int root, int quality) {
            this.root = ((root % 12) + 12) % 12; this.quality = quality;
            int third = quality == 0 ? 4 : 3, fifth = quality == 2 ? 6 : 7;
            pcs = new int[]{this.root, (this.root + third) % 12, (this.root + fifth) % 12};
        }
        public int third() { return quality == 0 ? 4 : 3; }
        public int fifth() { return quality == 2 ? 6 : 7; }
        public boolean has(int pc) { pc = ((pc % 12) + 12) % 12; for (int p : pcs) if (p == pc) return true; return false; }
        public String name() { return KEYS[root] + (quality == 1 ? "m" : quality == 2 ? "dim" : ""); }
    }

    /** A note read from the project (beats from the start of the analysed range). */
    public static final class In {
        public final int pitch; public final double startBeat, lengthBeat;
        public In(int pitch, double startBeat, double lengthBeat) { this.pitch = pitch; this.startBeat = startBeat; this.lengthBeat = lengthBeat; }
    }

    public static final class Analysis {
        public Chord[] chords; public int key; public boolean minor; public boolean found;
        public String describe() { StringBuilder b = new StringBuilder(); Chord last = null; for (Chord c : chords) { if (last != null && last.root == c.root && last.quality == c.quality) continue; if (b.length() > 0) b.append(" · "); b.append(c.name()); last = c; } return b.toString(); }
    }

    static final int[][] TEMPLATES = {{0, 4, 7}, {0, 3, 7}, {0, 3, 6}};

    /** One chord per bar from the notes of the project, or an invented progression when nothing is playing. */
    public static Analysis analyse(List<In> notes, int bars, int bpb, int fallbackKey, boolean fallbackMinor, long seed) {
        Analysis a = new Analysis(); a.chords = new Chord[bars]; boolean any = false;
        for (int bar = 0; bar < bars; bar++) {
            double lo = bar * (double) bpb, hi = lo + bpb; double[] w = new double[12]; double total = 0; int lowest = 999;
            if (notes != null) for (In n : notes) {
                double s = Math.max(lo, n.startBeat), e = Math.min(hi, n.startBeat + n.lengthBeat); if (e <= s) continue;
                int pc = ((n.pitch % 12) + 12) % 12; double dur = e - s; w[pc] += dur; total += dur; lowest = Math.min(lowest, n.pitch);
            }
            if (total < .5) continue;
            int distinct = 0; for (double v : w) if (v > .05 * total) distinct++;
            int bassPc = lowest == 999 ? -1 : ((lowest % 12) + 12) % 12; double bestScore = -1e9; Chord best = null; double bestIn = 0;
            for (int r = 0; r < 12; r++) for (int q = 0; q < 3; q++) {
                double in = 0, out = 0; boolean[] inT = new boolean[12]; for (int t : TEMPLATES[q]) inT[(r + t) % 12] = true;
                for (int pc = 0; pc < 12; pc++) { if (inT[pc]) in += w[pc]; else out += w[pc]; }
                double score = in - .6 * out + (r == bassPc ? .25 * total : 0) - (q == 2 ? .15 * total : 0);
                if (score > bestScore) { bestScore = score; best = new Chord(r, q); bestIn = in; }
            }
            if (best != null && distinct >= 2 && bestIn / total >= .55) { a.chords[bar] = best; any = true; }
        }
        if (!any) {
            a.found = false; a.key = fallbackKey; a.minor = fallbackMinor;
            int[][] progs = fallbackMinor ? AiMidiGenerator.PROG_MINOR : AiMidiGenerator.PROG_MAJOR; int[] prog = progs[(int) Math.floorMod(seed, (long) progs.length)];
            int[] scale = fallbackMinor ? MINOR : MAJOR;
            for (int bar = 0; bar < bars; bar++) a.chords[bar] = diatonic(fallbackKey, scale, prog[bar % prog.length]);
            return a;
        }
        a.found = true;
        Chord prev = null; for (int bar = 0; bar < bars; bar++) { if (a.chords[bar] != null) prev = a.chords[bar]; else if (prev != null) a.chords[bar] = prev; }
        Chord next = null; for (int bar = bars - 1; bar >= 0; bar--) { if (a.chords[bar] != null) next = a.chords[bar]; else a.chords[bar] = next; }
        // Key: the scale that contains the most chord tones, preferring the one the progression starts and ends on.
        double bestK = -1e9;
        for (int k = 0; k < 12; k++) for (int m = 0; m < 2; m++) {
            int[] scale = m == 1 ? MINOR : MAJOR; boolean[] in = new boolean[12]; for (int s : scale) in[(k + s) % 12] = true; double score = 0;
            for (Chord c : a.chords) { for (int pc : c.pcs) score += in[pc] ? 1 : -1.5; if (c.root == k) score += .5; }
            if (a.chords[0].root == k) score += 1.5; if (a.chords[bars - 1].root == k) score += 1.5;
            if (score > bestK) { bestK = score; a.key = k; a.minor = m == 1; }
        }
        return a;
    }

    static Chord diatonic(int key, int[] scale, int degree) {
        int root = key + scale[degree % 7], third = (scale[(degree + 2) % 7] - scale[degree % 7] + 12) % 12, fifth = (scale[(degree + 4) % 7] - scale[degree % 7] + 12) % 12;
        return new Chord(root, fifth == 6 ? 2 : third == 3 ? 1 : 0);
    }

    /** Soft block chords (one per bar) for hearing a part in context or adding a chord track. */
    public static List<AiMidiGenerator.Note> chordBlocks(Chord[] chords, int bpb, int velocity) {
        List<AiMidiGenerator.Note> out = new ArrayList<>();
        for (int bar = 0; bar < chords.length; bar++) {
            Chord c = chords[bar]; int root = 48 + ((c.root - 48) % 12 + 12) % 12;
            int[] tones = {root, root + c.third(), root + c.fifth(), root + 12};
            for (int p : tones) out.add(new AiMidiGenerator.Note(p, velocity, bar * (double) bpb, bpb - .05));
        }
        return out;
    }

    // ------------------------------------------------------------------ options

    public static final class Option {
        public final String title; public final int style; public final long seed; public final List<AiMidiGenerator.Note> notes;
        public Option(String title, int style, long seed, List<AiMidiGenerator.Note> notes) { this.title = title; this.style = style; this.seed = seed; this.notes = notes; }
    }

    /** Five takes. style < 0 means "surprise me": a different style for each option. */
    public static List<Option> options(int part, int style, int mood, Analysis an, int bpb, double[] kickBeats, long seed) {
        String[] names = STYLES[part]; List<Option> out = new ArrayList<>();
        List<Integer> order = new ArrayList<>(); for (int i = 0; i < names.length; i++) order.add(i); Collections.shuffle(order, new Random(seed * 31 + part));
        for (int i = 0; i < OPTIONS; i++) {
            int s = style >= 0 ? style : order.get(i % order.size());
            long sd = seed * 1000003L + i * 7919L + s;
            out.add(new Option("Option " + (i + 1) + " · " + names[s], s, sd, generate(part, s, mood, an, bpb, kickBeats, sd)));
        }
        return out;
    }

    public static List<AiMidiGenerator.Note> generate(int part, int style, int mood, Analysis an, int bpb, double[] kickBeats, long seed) {
        Random rng = new Random(seed); int bars = an.chords.length; Mood m = mood(mood); List<AiMidiGenerator.Note> out = new ArrayList<>();
        switch (part) {
            case MELODY: line(out, rng, an, bpb, m, style, false); break;
            case SOLO: line(out, rng, an, bpb, m, style, true); break;
            case BASS: bass(out, rng, an, bpb, m, style, kickBeats); break;
            default: rhythm(out, rng, an, bpb, m, style); break;
        }
        // Keep everything inside the clip and inside MIDI range.
        List<AiMidiGenerator.Note> clean = new ArrayList<>(); double end = bars * (double) bpb;
        for (AiMidiGenerator.Note n : out) {
            if (n.startBeat < 0 || n.startBeat >= end || n.pitch < 0 || n.pitch > 127) continue;
            double len = Math.min(n.lengthBeat, end - n.startBeat); if (len < .03) continue;
            clean.add(new AiMidiGenerator.Note(n.pitch, Math.max(1, Math.min(127, n.velocity)), n.startBeat, len));
        }
        return clean;
    }

    static final class Mood { double density; int vel, low, high; double gate; }
    static Mood mood(int i) {
        Mood m = new Mood();
        switch (i) {
            case 0: m.density = .3; m.vel = 68; m.low = 62; m.high = 79; m.gate = 1.0; break;      // calm
            case 1: m.density = .55; m.vel = 88; m.low = 64; m.high = 83; m.gate = .88; break;     // happy
            case 2: m.density = .4; m.vel = 74; m.low = 57; m.high = 76; m.gate = 1.0; break;      // sad
            default: m.density = .9; m.vel = 100; m.low = 64; m.high = 84; m.gate = .72; break;     // energetic
        }
        return m;
    }

    static AiMidiGenerator.Note note(int pitch, int vel, double start, double len) { return new AiMidiGenerator.Note(pitch, vel, start, len); }
    static int jitter(Random r, int v, int amount) { return v + r.nextInt(amount * 2 + 1) - amount; }

    // ------------------------------------------------------------------ melody and solo

    // Rhythm templates: {start, length} in sixteenth notes within a 4/4 bar.
    static final int[][][][] LINE_RHYTHMS = {
        // melody
        {   // Singable
            {{0, 8}, {8, 8}}, {{0, 6}, {6, 2}, {8, 8}}, {{0, 4}, {4, 4}, {8, 8}}, {{0, 8}, {8, 4}, {12, 4}}},
        {   // Catchy hook
            {{0, 2}, {2, 2}, {4, 4}, {8, 2}, {10, 2}, {12, 4}}, {{0, 4}, {4, 2}, {6, 2}, {8, 4}, {12, 4}}, {{0, 3}, {3, 3}, {6, 2}, {8, 4}, {12, 2}, {14, 2}}},
        {   // Call & answer
            {{0, 4}, {4, 2}, {6, 2}, {8, 8}}, {{0, 2}, {2, 2}, {4, 4}, {8, 4}, {12, 4}}},
        {   // Flowing
            {{0, 2}, {2, 2}, {4, 2}, {6, 2}, {8, 2}, {10, 2}, {12, 4}}, {{0, 2}, {2, 2}, {4, 2}, {6, 2}, {8, 4}, {12, 2}, {14, 2}}},
        {   // Afro groove
            {{0, 3}, {3, 3}, {6, 2}, {10, 2}, {12, 4}}, {{0, 2}, {3, 3}, {6, 3}, {10, 3}, {14, 2}}, {{2, 2}, {4, 2}, {7, 3}, {10, 2}, {12, 4}}}};
    static final int[][][][] SOLO_RHYTHMS = {
        {   // Pentatonic lick
            {{0, 2}, {2, 2}, {4, 2}, {6, 2}, {8, 8}}, {{0, 1}, {1, 1}, {2, 2}, {4, 4}, {10, 2}, {12, 4}}, {{2, 2}, {4, 1}, {5, 1}, {6, 2}, {8, 2}, {12, 4}}},
        {   // Melodic lead
            {{0, 6}, {6, 2}, {8, 8}}, {{0, 8}, {8, 4}, {12, 4}}, {{0, 4}, {4, 4}, {8, 6}, {14, 2}}},
        {   // Call & answer
            {{0, 2}, {2, 2}, {4, 4}, {8, 8}}, {{0, 2}, {2, 2}, {4, 2}, {6, 2}, {8, 4}, {12, 4}}},
        {   // Fast run
            {{0, 1}, {1, 1}, {2, 1}, {3, 1}, {4, 1}, {5, 1}, {6, 1}, {7, 1}, {8, 8}}, {{0, 1}, {1, 1}, {2, 1}, {3, 1}, {4, 2}, {6, 2}, {8, 1}, {9, 1}, {10, 1}, {11, 1}, {12, 4}}},
        {   // Afro riff
            {{0, 2}, {3, 2}, {6, 2}, {8, 2}, {11, 2}, {14, 2}}, {{0, 3}, {3, 3}, {6, 2}, {10, 2}, {13, 2}}}};

    static int[][] pickRhythm(int[][][] pool, double density, Random rng, int bpb) {
        // Choose the template whose note count is closest to the wanted density (with a little randomness).
        int target = (int) Math.round(3 + density * 6); int[][] best = null; double bestD = 1e9;
        for (int[][] t : pool) { double d = Math.abs(t.length - target) + rng.nextDouble() * 2.2; if (d < bestD) { bestD = d; best = t; } }
        int limit = bpb * 4; List<int[]> kept = new ArrayList<>(); for (int[] s : best) if (s[0] < limit) kept.add(new int[]{s[0], Math.min(s[1], limit - s[0])});
        return kept.toArray(new int[0][]);
    }

    /** Scale index helpers: an index counts allowed notes (scale or pentatonic) upward from the key's octave below middle C. */
    static final class Ladder {
        final int[] pitches;
        Ladder(int key, boolean minor, boolean pent, int low, int high) {
            int[] pcs = pent ? (minor ? PENT_MINOR : PENT_MAJOR) : (minor ? MINOR : MAJOR); List<Integer> l = new ArrayList<>();
            for (int p = low; p <= high; p++) { int rel = ((p - key) % 12 + 12) % 12; for (int s : pcs) if (s == rel) { l.add(p); break; } }
            pitches = new int[l.size()]; for (int i = 0; i < pitches.length; i++) pitches[i] = l.get(i);
        }
        int nearestChordTone(int idx, Chord c, int reach) {
            int best = -1, bestD = 99;
            for (int i = Math.max(0, idx - reach); i <= Math.min(pitches.length - 1, idx + reach); i++) if (c.has(pitches[i]) && Math.abs(i - idx) < bestD) { bestD = Math.abs(i - idx); best = i; }
            return best >= 0 ? best : Math.max(0, Math.min(pitches.length - 1, idx));
        }
        int rootNear(int idx, Chord c) { int best = -1, bestD = 99; for (int i = 0; i < pitches.length; i++) if (((pitches[i] % 12) + 12) % 12 == c.root && Math.abs(i - idx) < bestD) { bestD = Math.abs(i - idx); best = i; } return best >= 0 ? best : nearestChordTone(idx, c, 6); }
        int clamp(int i) { return Math.max(0, Math.min(pitches.length - 1, i)); }
    }

    static int[] contour(Random rng, int n, double busy, int direction) {
        int[] c = new int[n];
        for (int i = 0; i < n; i++) {
            int[] choices = busy > .6 ? new int[]{-2, -1, -1, 0, 1, 1, 2} : new int[]{-1, -1, 0, 1, 1, 2, -2};
            int s = choices[rng.nextInt(choices.length)];
            if (direction > 0 && s < 0 && rng.nextDouble() < .7) s = -s; if (direction < 0 && s > 0 && rng.nextDouble() < .7) s = -s;
            c[i] = s;
        }
        return c;
    }

    static void line(List<AiMidiGenerator.Note> out, Random rng, Analysis an, int bpb, Mood m, int style, boolean solo) {
        int bars = an.chords.length; int[][][][] pools = solo ? SOLO_RHYTHMS : LINE_RHYTHMS; int[][][] pool = pools[Math.min(style, pools.length - 1)];
        int low = solo ? m.low - 2 : m.low, high = solo ? m.high + 5 : m.high; Ladder ladder = new Ladder(an.key, an.minor, solo, low, high);
        boolean callAnswer = style == 2, riff = solo && style == 4, fast = solo && style == 3;
        int idx = ladder.pitches.length / 2; int[][] rhythmA = pickRhythm(pool, m.density, rng, bpb), rhythmB = pickRhythm(pool, m.density, rng, bpb);
        int[] contourA = contour(rng, rhythmA.length, m.density, callAnswer ? 1 : 0), contourB = contour(rng, rhythmB.length, m.density, callAnswer ? -1 : 0);
        List<AiMidiGenerator.Note> riffBar = null;
        for (int bar = 0; bar < bars; bar++) {
            Chord c = an.chords[bar]; int role = bar % 4; boolean last = bar == bars - 1; int[][] rh; int[] ct;
            if (callAnswer) { boolean answer = bar % 2 == 1; rh = answer ? rhythmB : rhythmA; ct = answer ? contourB : contourA; }
            else if (role == 2) { rh = rhythmB; ct = contourB; }
            else { rh = rhythmA; ct = contourA; if (role == 1 && rng.nextDouble() < .5) { ct = ct.clone(); ct[ct.length - 1] = -ct[ct.length - 1]; } }
            if (fast) { ct = ct.clone(); for (int i = 0; i < ct.length && rh[i][1] == 1; i++) ct[i] = (bar % 2 == 0) ? 1 : -1; }
            boolean cadence = last || role == 3;
            if (riff && bar >= 2 && riffBar != null) { // repeat the 2-bar riff note for note
                List<AiMidiGenerator.Note> copy = new ArrayList<>(); for (AiMidiGenerator.Note n : out) if (n.startBeat >= (bar - 2) * (double) bpb && n.startBeat < (bar - 1) * (double) bpb) copy.add(n);
                for (AiMidiGenerator.Note n : copy) out.add(note(n.pitch, jitter(rng, n.velocity, 3), n.startBeat + 2.0 * bpb, n.lengthBeat));
                continue;
            }
            for (int k = 0; k < rh.length; k++) {
                int startS = rh[k][0], lenS = rh[k][1]; boolean strong = startS % 8 == 0, beat = startS % 4 == 0;
                boolean lastNote = k == rh.length - 1;
                idx = ladder.clamp(idx + ct[k]);
                if (k == 0 || strong || lenS >= 4) idx = ladder.nearestChordTone(idx, c, 3);
                if (cadence && lastNote) { idx = ladder.rootNear(idx, c); lenS = Math.max(lenS, bpb * 4 - startS); }
                if (idx <= 1 || idx >= ladder.pitches.length - 2) idx = ladder.nearestChordTone(ladder.pitches.length / 2, c, 4);
                int pitch = ladder.pitches[idx]; double start = bar * (double) bpb + startS / 4.0, len = Math.max(.2, lenS / 4.0 * (lenS >= 8 || (cadence && lastNote) ? 1 : m.gate));
                int vel = jitter(rng, m.vel + (strong ? 8 : beat ? 3 : -4), 4);
                if (solo && lenS >= 2 && (beat || rng.nextDouble() < .3) && (style == 0 || style == 1 || style == 2) && rng.nextDouble() < .55 && start >= .2)
                    out.add(note(ladder.pitches[Math.max(0, idx - 1)], (int) (vel * .6), start - .125, .11)); // quick slide-in from the note below, kept in key
                out.add(note(pitch, vel, start, len));
            }
            if (riff && bar == 1) riffBar = new ArrayList<>(out);
        }
    }

    // ------------------------------------------------------------------ bass

    static int bassRoot(Chord c) { return 31 + ((c.root - 31) % 12 + 12) % 12; }

    static void bass(List<AiMidiGenerator.Note> out, Random rng, Analysis an, int bpb, Mood m, int style, double[] kicks) {
        int bars = an.chords.length; int[] scale = an.minor ? MINOR : MAJOR; int pickPattern = rng.nextInt(3); int vel = m.vel - 6;
        for (int bar = 0; bar < bars; bar++) {
            Chord c = an.chords[bar], next = an.chords[Math.min(bars - 1, bar + 1)]; int r = bassRoot(c), fifth = r + c.fifth(), third = r + c.third(), oct = r + 12; if (fifth > 45) fifth -= 12;
            double t0 = bar * (double) bpb; int nextRoot = bassRoot(next); double g = Math.max(.5, m.gate);
            switch (style) {
                case 0: // Steady: root on every beat
                    for (int b = 0; b < bpb; b++) out.add(note(r, jitter(rng, vel + (b == 0 ? 12 : 0), 3), t0 + b, .9 * g)); break;
                case 1: // Pumping eighths
                    for (int i = 0; i < bpb * 2; i++) { int p = (i == bpb * 2 - 1 && rng.nextDouble() < .4) ? fifth : r; out.add(note(p, jitter(rng, vel + (i % 2 == 0 ? 10 : -6), 3), t0 + i * .5, .42 * g + .05)); } break;
                case 2: // Walking
                    out.add(note(r, vel + 12, t0, .95));
                    if (bpb >= 2) out.add(note(rng.nextBoolean() ? third : fifth, vel, t0 + 1, .95));
                    if (bpb >= 3) out.add(note(fifth, vel - 2, t0 + 2, .95));
                    if (bpb >= 4) { int approach = nextRoot + (nextRoot > r ? -1 : 1); if (rng.nextDouble() < .35) approach = nextRoot + (nextRoot > r ? -2 : 2); out.add(note(bar == bars - 1 ? oct : approach, vel - 4, t0 + 3, .95)); }
                    break;
                case 3: { // Afro bounce
                    int[][][] pats = {{{0, 3, r, 100}, {3, 1, r, 60}, {6, 2, fifth, 88}, {8, 2, r, 92}, {10, 1, oct, 80}, {12, 2, r, 84}, {14, 2, fifth, 76}},
                        {{0, 4, r, 100}, {4, 1, r, 55}, {6, 2, oct, 84}, {9, 2, fifth, 86}, {12, 2, r, 90}, {14, 1, third, 70}},
                        {{0, 2, r, 100}, {3, 2, r, 78}, {6, 1, fifth, 60}, {8, 3, r, 92}, {11, 2, oct, 82}, {14, 2, fifth, 80}}};
                    for (int[] n : pats[pickPattern]) if (n[0] < bpb * 4) out.add(note(n[2], jitter(rng, Math.min(120, n[3] * vel / 90), 3), t0 + n[0] / 4.0, Math.max(.12, n[1] / 4.0 * g))); break; }
                case 4: // Octave funk
                    for (int i = 0; i < bpb * 2; i++) { if (i == bpb * 2 - 1 && rng.nextBoolean()) continue; out.add(note(i % 2 == 0 ? r : oct, jitter(rng, vel + (i % 2 == 0 ? 10 : 0), 3), t0 + i * .5, .3)); } break;
                case 5: // Slide groove: ghost notes and slide-ins
                    out.add(note(r, vel + 12, t0, 1.4)); out.add(note(r, 42, t0 + 1.5, .12)); out.add(note(r, vel + 4, t0 + 2, .7));
                    out.add(note(fifth - 1, (int) (vel * .6), t0 + 2.75, .1)); out.add(note(fifth, vel, t0 + 3, .7));
                    if (bar < bars - 1) out.add(note(nextRoot - 1, (int) (vel * .6), t0 + 3.75, .1)); break;
                case 6: // Sub 808: one long note, re-hit before the chord changes
                    out.add(note(r - (r > 36 ? 12 : 0) + (r < 31 ? 12 : 0), Math.min(120, vel + 18), t0, (bpb - (c.root != next.root ? 1 : 0)) - .05));
                    if (c.root != next.root && bar < bars - 1) out.add(note(r, vel, t0 + bpb - 1, .45)); break;
                case 7: { // Fingerstyle: root, fifth, third with a soft touch
                    out.add(note(r, vel + 8, t0, 1.9)); if (bpb >= 2) out.add(note(fifth, vel - 8, t0 + 2, 1)); if (bpb >= 4) out.add(note(rng.nextBoolean() ? oct : third, vel - 12, t0 + 3, .9)); break; }
                default: { // Lock to kick: a root note wherever the kick drum hits
                    double[] ks = kicks != null && kicks.length > 0 ? kicks : new double[]{0, 1.5, 2, 3.5};
                    List<Double> inBar = new ArrayList<>(); for (double k : ks) { double pos = k - t0; if (k >= t0 && pos < bpb) inBar.add(pos); } if (inBar.isEmpty() || inBar.get(0) > .01) inBar.add(0, 0.0);
                    for (int i = 0; i < inBar.size(); i++) { double s = inBar.get(i), e = i + 1 < inBar.size() ? inBar.get(i + 1) : bpb; out.add(note(i % 4 == 3 ? oct : r, jitter(rng, vel + (s == 0 ? 12 : 4), 3), t0 + s, Math.max(.15, Math.min(1.5, (e - s) * .85)))); } break; }
            }
        }
    }

    // ------------------------------------------------------------------ rhythm guitar

    /** Guitar-like voicing, low to high: root, fifth, octave, third, fifth, root. */
    static int[] voicing(Chord c) {
        int base = 40 + ((c.root - 40) % 12 + 12) % 12; int[] v = {base, base + c.fifth(), base + 12, base + 12 + c.third(), base + 12 + c.fifth() + 0, base + 24};
        v[4] = base + 12 + c.fifth(); if (v[4] > 79) for (int i = 4; i < 6; i++) v[i] -= 12;
        return v;
    }

    static void strum(List<AiMidiGenerator.Note> out, int[] v, boolean down, int first, double start, double len, int vel) {
        int n = v.length - first; for (int i = 0; i < n; i++) { int s = down ? first + i : v.length - 1 - i; out.add(note(v[s], vel - (down ? 0 : 6) - (i > 3 ? 4 : 0), start + (down ? .012 : .008) * i, len)); }
    }

    static void rhythm(List<AiMidiGenerator.Note> out, Random rng, Analysis an, int bpb, Mood m, int style) {
        int bars = an.chords.length, pickPattern = rng.nextInt(3), vel = m.vel;
        for (int bar = 0; bar < bars; bar++) {
            Chord c = an.chords[bar]; int[] v = voicing(c); double t0 = bar * (double) bpb, g = Math.max(.6, m.gate);
            switch (style) {
                case 0: { // Open strum
                    int[][][] pats = {{{0, 1, 95}, {4, 1, 80}, {6, 0, 66}, {10, 0, 66}, {12, 1, 84}, {14, 0, 66}}, {{0, 1, 98}, {4, 1, 70}, {8, 1, 88}, {10, 0, 62}, {12, 1, 80}, {14, 0, 64}}, {{0, 1, 96}, {6, 0, 64}, {8, 1, 86}, {12, 1, 76}, {14, 0, 66}}};
                    int[][] p = pats[pickPattern]; for (int i = 0; i < p.length; i++) { if (p[i][0] >= bpb * 4) continue; double s = t0 + p[i][0] / 4.0, e = i + 1 < p.length && p[i + 1][0] < bpb * 4 ? t0 + p[i + 1][0] / 4.0 : t0 + bpb; strum(out, v, p[i][1] == 1, p[i][1] == 1 ? 0 : 2, s, Math.max(.2, (e - s) * .92 * Math.min(1, g + .2)), jitter(rng, p[i][2] * vel / 90, 3)); } break; }
                case 1: { // Arpeggio
                    int[] order = pickPattern == 0 ? new int[]{0, 2, 3, 4, 5, 4, 3, 2} : pickPattern == 1 ? new int[]{0, 3, 2, 4, 3, 5, 4, 2} : new int[]{0, 2, 4, 3, 5, 3, 4, 2};
                    for (int i = 0; i < bpb * 2; i++) out.add(note(v[order[i % 8]], jitter(rng, (i % 4 == 0 ? vel : vel - 14), 4), t0 + i * .5, 1.4 * g)); break; }
                case 2: // Palm mute: tight low chug
                    for (int i = 0; i < bpb * 2; i++) { boolean accent = i % 4 == 0; out.add(note(v[accent || i % 4 == 2 ? 0 : 1], jitter(rng, accent ? vel : vel - 18, 4), t0 + i * .5, .17)); if (i % 4 == 3 && rng.nextBoolean()) out.add(note(v[2], vel - 10, t0 + i * .5 + .0, .17)); } break;
                case 3: // Offbeat chop
                    out.add(note(v[0], vel, t0, .3)); for (int b = 0; b < bpb; b++) strum(out, v, true, 2, t0 + b + .5, .17, jitter(rng, vel - 4, 4)); break;
                case 4: { // Afro pluck: interlocking high plucks
                    int[] pos = pickPattern == 0 ? new int[]{0, 3, 6, 8, 11, 14} : pickPattern == 1 ? new int[]{0, 2, 5, 8, 10, 13} : new int[]{1, 3, 6, 9, 11, 14}; int[] idx = {3, 4, 5, 4, 3, 2};
                    for (int i = 0; i < pos.length; i++) if (pos[i] < bpb * 4) out.add(note(v[idx[i]], jitter(rng, vel - 10 + (i % 2) * 8, 4), t0 + pos[i] / 4.0, .33)); out.add(note(v[0], vel - 8, t0, .6)); break; }
                default: { // Funk scratch: muted ghosts with a few open hits
                    for (int i = 0; i < bpb * 4; i++) {
                        boolean open = i == 0 || i == 6 || i == 10 || (pickPattern == 1 && i == 13);
                        if (open) strum(out, v, true, 2, t0 + i / 4.0, .26, jitter(rng, vel + 4, 3)); else if (i % 2 == 1 || i % 4 == 2) strum(out, v, i % 2 == 0, 3, t0 + i / 4.0, .06, 34 + rng.nextInt(10));
                    } }
            }
        }
    }

    // ------------------------------------------------------------------ easy ways to make your own melody

    static void sortByStart(List<AiMidiGenerator.Note> l) { Collections.sort(l, new Comparator<AiMidiGenerator.Note>() { public int compare(AiMidiGenerator.Note x, AiMidiGenerator.Note y) { return Double.compare(x.startBeat, y.startBeat); } }); }

    /** "Tap the rhythm": the user only taps when notes should happen; the app chooses notes that fit the chords. */
    public static List<AiMidiGenerator.Note> fromTaps(double[] tapBeats, Analysis an, int bpb, int mood, long seed) {
        List<AiMidiGenerator.Note> out = new ArrayList<>(); if (tapBeats == null || tapBeats.length == 0) return out;
        Mood m = mood(mood); Random rng = new Random(seed); int bars = an.chords.length; double end = bars * (double) bpb;
        double[] t = tapBeats.clone(); Arrays.sort(t); List<Double> gaps = new ArrayList<>(); for (int i = 1; i < t.length; i++) gaps.add(t[i] - t[i - 1]); Collections.sort(gaps);
        double grid = !gaps.isEmpty() && gaps.get(gaps.size() / 2) < .4 ? .25 : .5; // fast tappers get sixteenths, everyone else eighths
        TreeSet<Long> steps = new TreeSet<>(); for (double v : t) { long q = Math.round(v / grid); if (q * grid >= end) q = 0; if (q >= 0) steps.add(q); }
        Ladder ladder = new Ladder(an.key, an.minor, false, m.low, m.high); int idx = ladder.pitches.length / 2; Long[] st = steps.toArray(new Long[0]);
        int direction = 1;
        for (int i = 0; i < st.length; i++) {
            double start = st[i] * grid, next = i + 1 < st.length ? st[i + 1] * grid : end, len = Math.max(.2, Math.min(next - start, 2) * (next - start >= 1 ? 1 : m.gate));
            int bar = Math.min(bars - 1, (int) (start / bpb)); Chord c = an.chords[bar]; double inBar = start - bar * (double) bpb; boolean strong = Math.abs(inBar % 2) < 1e-6, last = i == st.length - 1;
            if (rng.nextDouble() < .25) direction = -direction; int move = rng.nextDouble() < .2 ? 0 : (rng.nextDouble() < .8 ? 1 : 2) * direction;
            idx = ladder.clamp(idx + move);
            if (i == 0 || strong || next - start >= 1) idx = ladder.nearestChordTone(idx, c, 3);
            if (idx <= 1) { idx = ladder.nearestChordTone(3, c, 3); direction = 1; } else if (idx >= ladder.pitches.length - 2) { idx = ladder.nearestChordTone(ladder.pitches.length - 4, c, 3); direction = -1; }
            if (last) idx = ladder.rootNear(idx, c);
            out.add(note(ladder.pitches[idx], jitter(rng, m.vel + (strong ? 8 : -2), 4), start, last ? Math.max(len, Math.min(2, end - start)) : len));
        }
        return out;
    }

    /**
     * "Draw the shape": heights holds one value per eighth note (0 = low, 1 = high, NaN = nothing drawn).
     * The rhythm is written automatically; the notes follow the drawn line and are pulled onto the chords.
     */
    public static List<AiMidiGenerator.Note> fromShape(double[] heights, Analysis an, int bpb, int mood, long seed) {
        List<AiMidiGenerator.Note> out = new ArrayList<>(); if (heights == null) return out;
        Mood m = mood(mood); Random rng = new Random(seed); int bars = an.chords.length, perBar = bpb * 2;
        int[][][] pool = LINE_RHYTHMS[mood == 3 ? 3 : mood == 1 ? 1 : 0]; int[][] rhythmA = pickRhythm(pool, m.density, rng, bpb), rhythmB = pickRhythm(pool, m.density, rng, bpb);
        Ladder ladder = new Ladder(an.key, an.minor, false, m.low - 3, m.high + 2); int top = ladder.pitches.length - 1;
        for (int bar = 0; bar < bars; bar++) {
            int[][] rh = bar % 4 == 2 ? rhythmB : rhythmA; Chord c = an.chords[bar];
            for (int[] r : rh) {
                int from = bar * perBar + r[0] / 2, to = Math.min(heights.length - 1, bar * perBar + (r[0] + r[1] - 1) / 2); double h = Double.NaN;
                for (int k = from; k <= to && k < heights.length; k++) if (!Double.isNaN(heights[k])) { h = heights[k]; break; }
                if (Double.isNaN(h)) continue;
                int idx = ladder.clamp((int) Math.round(Math.max(0, Math.min(1, h)) * top)); boolean strong = r[0] % 8 == 0;
                if (strong || r[1] >= 4) idx = ladder.nearestChordTone(idx, c, 2);
                out.add(note(ladder.pitches[idx], jitter(rng, m.vel + (strong ? 8 : -3), 4), bar * (double) bpb + r[0] / 4.0, Math.max(.2, r[1] / 4.0 * (r[1] >= 8 ? 1 : m.gate))));
            }
        }
        if (!out.isEmpty()) { // settle the ending on a chord note and let it ring
            AiMidiGenerator.Note l = out.remove(out.size() - 1); int bar = Math.min(bars - 1, (int) (l.startBeat / bpb)); int idx = 0, bestD = 999; for (int i = 0; i < ladder.pitches.length; i++) if (an.chords[bar].has(ladder.pitches[i]) && Math.abs(ladder.pitches[i] - l.pitch) < bestD) { bestD = Math.abs(ladder.pitches[i] - l.pitch); idx = i; }
            out.add(note(ladder.pitches[idx], l.velocity, l.startBeat, Math.max(l.lengthBeat, Math.min(2, (bar + 1) * (double) bpb - l.startBeat))));
        }
        return out;
    }

    /** "Hum it": mono audio at 12 kHz. Finds the hummed notes, moves them into the key and onto the beat grid. */
    public static List<AiMidiGenerator.Note> fromHum(float[] mono12k, int bpm, Analysis an, int bpb) { return fromHum(mono12k, bpm, an, bpb, false); }

    /** aligned = the audio starts exactly at the first beat of the clip (hummed along with the song), so notes keep their place against the beat. */
    public static List<AiMidiGenerator.Note> fromHum(float[] mono12k, int bpm, Analysis an, int bpb, boolean aligned) {
        List<AiMidiGenerator.Note> out = new ArrayList<>(); if (mono12k == null) return out;
        final int hop = 120; int frames = Math.max(0, (mono12k.length - 700) / hop); if (frames < 10) return out;
        double[] midi = new double[frames];
        // Loudness gate: only the clearly hummed parts count, so leftover song or room noise is ignored.
        double[] rms = new double[frames]; for (int f = 0; f < frames; f++) { double e = 0; for (int i = 0; i < 384; i++) { double v = mono12k[f * hop + i]; e += v * v; } rms[f] = Math.sqrt(e / 384); }
        double[] sortedRms = rms.clone(); Arrays.sort(sortedRms); double loud = sortedRms[(int) (frames * .95)], gate = Math.max(.004, loud * .18);
        for (int f = 0; f < frames; f++) { double hz = rms[f] >= gate ? PitchCorrector.detect(mono12k, f * hop, 80, 900) : 0; midi[f] = hz > 0 ? 69 + 12 * Math.log(hz / 440) / Math.log(2) : Double.NaN; }
        double[] sm = new double[frames]; double[] win = new double[5];
        for (int f = 0; f < frames; f++) { int n = 0; for (int k = -2; k <= 2; k++) { int j = f + k; if (j >= 0 && j < frames && !Double.isNaN(midi[j])) win[n++] = midi[j]; } if (n < 2 || Double.isNaN(midi[f])) { sm[f] = n >= 4 ? med(win, n) : Double.NaN; } else sm[f] = med(win, n); }
        List<double[]> raw = new ArrayList<>(); // {startFrame, endFrame, meanPitch}
        int f = 0;
        while (f < frames) {
            if (Double.isNaN(sm[f])) { f++; continue; }
            int start = f, lastVoiced = f, count = 1; double sum = sm[f]; f++;
            while (f < frames) {
                if (Double.isNaN(sm[f])) { if (f - lastVoiced > 4) break; f++; continue; }
                if (Math.abs(sm[f] - sum / count) > .8) break;
                sum += sm[f]; count++; lastVoiced = f; f++;
            }
            if (lastVoiced - start + 1 >= 8) raw.add(new double[]{start, lastVoiced + 1, sum / count});
            if (f <= lastVoiced) f = lastVoiced + 1;
        }
        if (raw.isEmpty()) return out;
        double[] ps = new double[raw.size()]; for (int i = 0; i < ps.length; i++) ps[i] = raw.get(i)[2]; double median = med(ps.clone(), ps.length);
        int shift = 0; while (median + shift < 60) shift += 12; while (median + shift > 78) shift -= 12;
        double bps = bpm / 60.0, t0 = aligned ? 0 : raw.get(0)[0] * hop / 12000.0, end = an.chords.length * (double) bpb; double lastStart = -1;
        for (double[] r : raw) {
            double start = Math.round((r[0] * hop / 12000.0 - t0) * bps * 4) / 4.0, len = Math.max(.25, Math.round((r[1] - r[0]) * hop / 12000.0 * bps * 4) / 4.0);
            if (start >= end) break; if (start <= lastStart) start = lastStart + .25; if (start >= end) break;
            int pitch = PitchCorrector.nearest((int) Math.round(r[2]) + shift, an.key, an.minor ? 2 : 1);
            if (!out.isEmpty()) { AiMidiGenerator.Note prev = out.get(out.size() - 1); if (prev.startBeat + prev.lengthBeat > start) out.set(out.size() - 1, note(prev.pitch, prev.velocity, prev.startBeat, Math.max(.2, start - prev.startBeat))); }
            out.add(note(pitch, 92, start, Math.min(len, end - start))); lastStart = start;
        }
        return out;
    }
    /**
     * Fixes notes that clash with the chords. Gentle (all = false): only long notes and notes on the strong beats move to
     * the nearest chord note, so the tune keeps its shape. all = true pulls every note onto the chords.
     */
    public static List<AiMidiGenerator.Note> fitToChords(List<AiMidiGenerator.Note> notes, Analysis an, int bpb, boolean all) {
        List<AiMidiGenerator.Note> out = new ArrayList<>();
        for (AiMidiGenerator.Note n : notes) {
            int bar = Math.max(0, Math.min(an.chords.length - 1, (int) (n.startBeat / bpb))); Chord c = an.chords[bar]; double inBar = n.startBeat - bar * (double) bpb;
            boolean strong = Math.abs(inBar % 2) < 1e-6 || n.lengthBeat >= .95; int pitch = n.pitch;
            if ((all || strong) && !c.has(pitch)) for (int d = 1; d <= 4; d++) { if (c.has(pitch - d)) { pitch -= d; break; } if (c.has(pitch + d)) { pitch += d; break; } }
            out.add(note(pitch, n.velocity, n.startBeat, n.lengthBeat));
        }
        return out;
    }

    static double med(double[] v, int n) { double[] c = Arrays.copyOf(v, n); Arrays.sort(c); return c[n / 2]; }

    /** Octave up or down, refused when it would leave a comfortable range. */
    public static List<AiMidiGenerator.Note> transpose(List<AiMidiGenerator.Note> notes, int semis, int low, int high) {
        for (AiMidiGenerator.Note n : notes) if (n.pitch + semis < low || n.pitch + semis > high) return null;
        List<AiMidiGenerator.Note> out = new ArrayList<>(); for (AiMidiGenerator.Note n : notes) out.add(note(n.pitch + semis, n.velocity, n.startBeat, n.lengthBeat)); return out;
    }

    /** Fewer, longer notes: keeps the notes on the beats and lets them ring. */
    public static List<AiMidiGenerator.Note> simpler(List<AiMidiGenerator.Note> notes, int bpb) {
        List<AiMidiGenerator.Note> sorted = new ArrayList<>(notes); sortByStart(sorted); List<AiMidiGenerator.Note> kept = new ArrayList<>();
        for (AiMidiGenerator.Note n : sorted) { if (n.velocity < 50) continue; boolean onBeat = Math.abs(n.startBeat - Math.rint(n.startBeat)) < 1e-6; if (onBeat || n.lengthBeat >= 1) kept.add(n); }
        if (kept.size() >= sorted.size() || kept.isEmpty()) { kept.clear(); for (int i = 0; i < sorted.size(); i++) if (i % 2 == 0 || i == sorted.size() - 1) kept.add(sorted.get(i)); }
        List<AiMidiGenerator.Note> out = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) { AiMidiGenerator.Note n = kept.get(i); double next = i + 1 < kept.size() ? kept.get(i + 1).startBeat : n.startBeat + Math.max(n.lengthBeat, 2); out.add(note(n.pitch, n.velocity, n.startBeat, Math.max(n.lengthBeat, Math.min(next - n.startBeat, bpb) * .95))); }
        return out;
    }

    /** More movement: long notes are split and step toward the next note, staying in the key. */
    public static List<AiMidiGenerator.Note> busier(List<AiMidiGenerator.Note> notes, Analysis an) {
        List<AiMidiGenerator.Note> sorted = new ArrayList<>(notes); sortByStart(sorted); Ladder ladder = new Ladder(an.key, an.minor, false, 24, 108); List<AiMidiGenerator.Note> out = new ArrayList<>();
        double threshold = 1; boolean any = false; for (AiMidiGenerator.Note n : sorted) if (n.lengthBeat >= .95) any = true; if (!any) threshold = .5;
        for (int i = 0; i < sorted.size(); i++) {
            AiMidiGenerator.Note n = sorted.get(i);
            if (n.lengthBeat < threshold * .95 || n.velocity < 50) { out.add(n); continue; }
            int idx = 0, bestD = 999; for (int k = 0; k < ladder.pitches.length; k++) if (Math.abs(ladder.pitches[k] - n.pitch) < bestD) { bestD = Math.abs(ladder.pitches[k] - n.pitch); idx = k; }
            int target = i + 1 < sorted.size() ? sorted.get(i + 1).pitch : n.pitch + 2; int nb = ladder.clamp(idx + (target >= n.pitch ? 1 : -1)); double half = n.lengthBeat / 2;
            out.add(note(n.pitch, n.velocity, n.startBeat, half * .95)); out.add(note(ladder.pitches[nb], Math.max(1, n.velocity - 10), n.startBeat + half, half * .95));
        }
        return out;
    }
}

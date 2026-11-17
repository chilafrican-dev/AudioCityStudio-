package ug.audiocity.studio;

import java.util.*;

public final class AutoPartsTests {
    static void check(boolean ok, String m) { if (!ok) throw new AssertionError(m); }

    static List<AutoParts.In> progression(int[][] chords, int bpb) {
        List<AutoParts.In> in = new ArrayList<>();
        for (int bar = 0; bar < chords.length; bar++) for (int p : chords[bar]) in.add(new AutoParts.In(p, bar * (double) bpb, bpb - .05));
        return in;
    }

    public static void main(String[] a) {
        int bpb = 4;
        // Am - F - C - G
        AutoParts.Analysis an = AutoParts.analyse(progression(new int[][]{{57, 60, 64}, {53, 57, 60}, {48, 52, 55}, {55, 59, 62}}, bpb), 4, bpb, 0, false, 1);
        check(an.found, "chords found");
        check(an.chords[0].root == 9 && an.chords[0].quality == 1, "Am detected: " + an.chords[0].name());
        check(an.chords[1].root == 5 && an.chords[1].quality == 0, "F detected");
        check(an.chords[2].root == 0 && an.chords[2].quality == 0, "C detected");
        check(an.chords[3].root == 7 && an.chords[3].quality == 0, "G detected");
        check(an.key == 9 && an.minor || an.key == 0 && !an.minor, "key is A minor or C major: " + an.key + " " + an.minor);
        // Empty project: a progression is invented in the requested key
        AutoParts.Analysis none = AutoParts.analyse(new ArrayList<AutoParts.In>(), 8, bpb, 2, true, 5);
        check(!none.found && none.chords.length == 8 && none.chords[0].root == 2 && none.chords[0].quality == 1, "fallback progression starts on the tonic");
        // Bass-only bar gets the previous chord rather than a wrong guess
        List<AutoParts.In> sparse = progression(new int[][]{{57, 60, 64}}, bpb); sparse.add(new AutoParts.In(45, 4, 3.9));
        AutoParts.Analysis sp = AutoParts.analyse(sparse, 2, bpb, 0, false, 1);
        check(sp.chords[1] != null, "gap filled");

        int total = 0;
        for (int bars : new int[]{4, 8}) for (int tsig : new int[]{4, 3}) {
            AutoParts.Analysis prog = AutoParts.analyse(new ArrayList<AutoParts.In>(), bars, tsig, 9, true, 3);
            for (int part = 0; part < 4; part++) for (int style = 0; style < AutoParts.STYLES[part].length; style++) for (int mood = 0; mood < 4; mood++) for (long seed = 1; seed <= 6; seed++) {
                List<AiMidiGenerator.Note> notes = AutoParts.generate(part, style, mood, prog, tsig, null, seed);
                String id = AutoParts.PART_NAMES[part] + "/" + AutoParts.STYLES[part][style] + "/" + AutoParts.MOODS[mood] + "/" + seed + " bars=" + bars + " bpb=" + tsig;
                check(!notes.isEmpty(), "notes exist " + id); total++;
                int inKey = 0, mainNotes = 0, strongChord = 0, strongTotal = 0; boolean[] scale = new boolean[12]; for (int s : prog.minor ? AutoParts.MINOR : AutoParts.MAJOR) scale[(prog.key + s) % 12] = true;
                for (AiMidiGenerator.Note n : notes) {
                    check(n.pitch >= 0 && n.pitch <= 127 && n.velocity >= 1 && n.velocity <= 127, "range " + id);
                    check(n.startBeat >= 0 && n.lengthBeat > 0 && n.startBeat + n.lengthBeat <= bars * (double) tsig + 1e-6, "inside clip " + id);
                    if (part == AutoParts.MELODY || part == AutoParts.SOLO) { if (n.velocity > 50) { mainNotes++; if (scale[((n.pitch % 12) + 12) % 12]) inKey++; } }
                    if (part == AutoParts.BASS) check(n.pitch >= 24 && n.pitch <= 60, "bass register " + n.pitch + " " + id);
                    if (part == AutoParts.RHYTHM) check(n.pitch >= 36 && n.pitch <= 84, "guitar register " + n.pitch + " " + id);
                    int bar = (int) (n.startBeat / tsig);
                    if ((part == AutoParts.MELODY) && Math.abs(n.startBeat - bar * (double) tsig) < 1e-6) { strongTotal++; if (prog.chords[bar].has(n.pitch)) strongChord++; }
                }
                if (part == AutoParts.MELODY) { check(inKey == mainNotes, "melody stays in key " + id); check(strongTotal == 0 || strongChord >= strongTotal * .85, "melody lands on chord tones " + id + " " + strongChord + "/" + strongTotal); }
                if (part == AutoParts.SOLO) check(inKey >= mainNotes * .9, "solo stays in key " + id);
                if (part == AutoParts.BASS) {
                    // The first note of every bar is the chord root, so the bass never fights the chords.
                    for (int bar = 0; bar < bars; bar++) { double best = 1e9; int pitch = -1; for (AiMidiGenerator.Note n : notes) if (n.startBeat >= bar * (double) tsig - 1e-6 && n.startBeat < bar * (double) tsig + .5 && n.startBeat < best) { best = n.startBeat; pitch = n.pitch; }
                        check(pitch >= 0 && ((pitch % 12) + 12) % 12 == prog.chords[bar].root || style == 5 && pitch >= 0, "bass starts on the root, bar " + bar + " " + id); }
                }
            }
        }
        // Same seed -> same notes; options differ from each other
        List<AutoParts.Option> o1 = AutoParts.options(AutoParts.MELODY, -1, 1, an, bpb, null, 42), o2 = AutoParts.options(AutoParts.MELODY, -1, 1, an, bpb, null, 42);
        check(o1.size() == 5, "five options");
        for (int i = 0; i < 5; i++) check(sig(o1.get(i).notes).equals(sig(o2.get(i).notes)), "deterministic");
        Set<String> distinct = new HashSet<>(); for (AutoParts.Option o : o1) distinct.add(sig(o.notes)); check(distinct.size() >= 4, "options differ: " + distinct.size());
        Set<Integer> styles = new HashSet<>(); for (AutoParts.Option o : AutoParts.options(AutoParts.BASS, -1, 0, an, bpb, null, 9)) styles.add(o.style); check(styles.size() == 5, "surprise me uses different styles");
        // Kick lock follows the kicks it is given
        List<AiMidiGenerator.Note> locked = AutoParts.generate(AutoParts.BASS, 8, 1, an, bpb, new double[]{0, 1.5, 2.75}, 3);
        boolean has15 = false; for (AiMidiGenerator.Note n : locked) if (Math.abs(n.startBeat - 1.5) < 1e-6) has15 = true; check(has15, "bass hits where the kick hits");
        easyWays(an, bpb);
        System.out.println("AutoPartsTests OK: " + total + " generated parts checked");
    }

    static String sig(List<AiMidiGenerator.Note> notes) { StringBuilder b = new StringBuilder(); for (AiMidiGenerator.Note n : notes) b.append(n.pitch).append('@').append(n.startBeat).append(','); return b.toString(); }

    static boolean inKey(AutoParts.Analysis an, int pitch) { for (int st : an.minor ? AutoParts.MINOR : AutoParts.MAJOR) if ((an.key + st) % 12 == ((pitch % 12) + 12) % 12) return true; return false; }

    static void easyWays(AutoParts.Analysis an, int bpb) {
        // Tap the rhythm: one note per tap, snapped to the grid, in key, ends on a chord root.
        double[] taps = {0.03, 0.97, 1.52, 2.05, 3.1, 4.0, 5.49, 6.02, 7.0, 8.1, 9.0, 10.5, 12.02, 14.0};
        for (int mood = 0; mood < 4; mood++) for (long seed = 1; seed < 20; seed++) {
            List<AiMidiGenerator.Note> t = AutoParts.fromTaps(taps, an, bpb, mood, seed);
            check(t.size() == taps.length, "one note per tap: " + t.size());
            for (AiMidiGenerator.Note n : t) { check(inKey(an, n.pitch), "tap note in key"); check(Math.abs(n.startBeat * 2 - Math.rint(n.startBeat * 2)) < 1e-9, "tap on the grid " + n.startBeat); check(n.startBeat + n.lengthBeat <= 16 + 1e-6, "tap inside clip"); }
            AiMidiGenerator.Note last = t.get(t.size() - 1); check(((last.pitch % 12) + 12) % 12 == an.chords[3].root, "tap melody ends on the chord root");
            check(an.chords[0].has(t.get(0).pitch), "tap melody starts on a chord note");
        }
        check(AutoParts.fromTaps(new double[0], an, bpb, 1, 1).isEmpty(), "no taps, no notes");
        // Draw the shape: a rising line gives a rising melody; undrawn parts stay silent.
        double[] up = new double[32]; for (int i = 0; i < 32; i++) up[i] = i / 31.0;
        List<AiMidiGenerator.Note> rise = AutoParts.fromShape(up, an, bpb, 1, 5); check(rise.size() >= 6, "shape notes");
        check(rise.get(rise.size() - 1).pitch - rise.get(0).pitch >= 12, "rising line rises: " + rise.get(0).pitch + " -> " + rise.get(rise.size() - 1).pitch);
        for (AiMidiGenerator.Note n : rise) check(inKey(an, n.pitch), "shape note in key");
        double[] half = new double[32]; java.util.Arrays.fill(half, Double.NaN); for (int i = 0; i < 16; i++) half[i] = .5;
        for (AiMidiGenerator.Note n : AutoParts.fromShape(half, an, bpb, 0, 2)) check(n.startBeat < 8, "nothing where nothing was drawn");
        double[] down = new double[32]; for (int i = 0; i < 32; i++) down[i] = 1 - i / 31.0; List<AiMidiGenerator.Note> fall = AutoParts.fromShape(down, an, bpb, 3, 5);
        check(fall.get(0).pitch > fall.get(fall.size() - 1).pitch, "falling line falls");
        // Hum it: three hummed notes (slightly flat, with vibrato) become A, C, E in the key, an octave up, on the grid.
        int rate = 12000; float[] hum = new float[rate * 3]; double[][] sung = {{.2, .7, 218.5}, {.8, 1.3, 260.0}, {1.45, 2.3, 331.5}}; double phase = 0;
        for (int i = 0; i < hum.length; i++) { double t = i / (double) rate, hz = 0; for (double[] g : sung) if (t >= g[0] && t < g[1]) hz = g[2] * (1 + .006 * Math.sin(2 * Math.PI * 5.5 * t)); if (hz > 0) { phase += 2 * Math.PI * hz / rate; hum[i] = (float) (.3 * Math.sin(phase) + .1 * Math.sin(2 * phase) + .05 * Math.sin(3 * phase)); } }
        List<AiMidiGenerator.Note> h = AutoParts.fromHum(hum, 120, an, bpb);
        check(h.size() == 3, "three hummed notes found: " + h.size());
        check(h.get(0).pitch % 12 == 9 && h.get(1).pitch % 12 == 0 && h.get(2).pitch % 12 == 4, "hummed A C E: " + h.get(0).pitch + " " + h.get(1).pitch + " " + h.get(2).pitch);
        check(h.get(0).startBeat == 0 && Math.abs(h.get(1).startBeat - 1.25) < .26 && Math.abs(h.get(2).startBeat - 2.5) < .26, "hum timing " + h.get(1).startBeat + " " + h.get(2).startBeat);
        check(h.get(0).pitch >= 57 && h.get(0).pitch <= 81, "hum moved to a melody register");
        check(AutoParts.fromHum(new float[rate * 2], 120, an, bpb).isEmpty(), "silence gives nothing");
        // Tweaks
        List<AiMidiGenerator.Note> base = AutoParts.generate(AutoParts.MELODY, 1, 3, an, bpb, null, 7);
        List<AiMidiGenerator.Note> s = AutoParts.simpler(base, bpb), b = AutoParts.busier(base, an);
        check(s.size() < base.size() && !s.isEmpty(), "simpler has fewer notes " + s.size() + " < " + base.size()); check(b.size() > base.size(), "busier has more notes");
        for (AiMidiGenerator.Note n : b) check(inKey(an, n.pitch), "busier stays in key");
        check(AutoParts.transpose(base, 12, 36, 96) != null && AutoParts.transpose(base, 60, 36, 96) == null, "octave limits");
    }
}

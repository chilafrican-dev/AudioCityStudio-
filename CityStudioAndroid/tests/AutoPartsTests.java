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
        System.out.println("AutoPartsTests OK: " + total + " generated parts checked");
    }

    static String sig(List<AiMidiGenerator.Note> notes) { StringBuilder b = new StringBuilder(); for (AiMidiGenerator.Note n : notes) b.append(n.pitch).append('@').append(n.startBeat).append(','); return b.toString(); }
}

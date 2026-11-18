package ug.audiocity.studio;

import java.util.*;

/** Host checks for the AI MIDI generator: scale safety, ranges, determinism, bar limits, chord alignment, tempo-map conversion. */
public class AiMidiGeneratorTests {
    static int passed;
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); passed++; }

    static boolean same(List<AiMidiGenerator.Note> a, List<AiMidiGenerator.Note> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) { AiMidiGenerator.Note x = a.get(i), y = b.get(i); if (x.pitch != y.pitch || x.velocity != y.velocity || x.startBeat != y.startBeat || x.lengthBeat != y.lengthBeat) return false; }
        return true;
    }

    public static void main(String[] args) {
        AiMidiGenerator.Options o = new AiMidiGenerator.Options();
        AiMidiGenerator.Result a = AiMidiGenerator.generate(o), b = AiMidiGenerator.generate(o);
        check(!a.melody.isEmpty() && !a.chords.isEmpty() && !a.bass.isEmpty() && !a.drums.isEmpty(), "all parts are produced");
        check(same(a.melody, b.melody) && same(a.chords, b.chords) && same(a.bass, b.bass) && same(a.drums, b.drums), "same seed gives identical music");
        o.seed = 2; check(!same(a.melody, AiMidiGenerator.generate(o).melody), "a different seed gives a different melody"); o.seed = 1;

        Random rng = new Random(99); int generated = 0;
        for (int trial = 0; trial < 3000; trial++) {
            AiMidiGenerator.Options p = new AiMidiGenerator.Options();
            p.key = rng.nextInt(12); p.minor = rng.nextBoolean(); p.bars = 1 + rng.nextInt(24); p.beatsPerBar = new int[]{2, 3, 4, 5, 6, 7}[rng.nextInt(6)];
            p.creativity = rng.nextDouble(); p.seed = rng.nextLong(); p.melody = rng.nextInt(8) != 0; p.chords = rng.nextInt(8) != 0; p.bass = rng.nextInt(8) != 0; p.drums = rng.nextInt(8) != 0;
            AiMidiGenerator.Result r = AiMidiGenerator.generate(p); generated++;
            int[] scale = p.minor ? AiMidiGenerator.MINOR : AiMidiGenerator.MAJOR; boolean[] in = new boolean[12]; for (int s : scale) in[(s + p.key) % 12] = true;
            double end = p.bars * (double) p.beatsPerBar;
            check(p.melody ? !r.melody.isEmpty() : r.melody.isEmpty(), "melody toggle");
            check(p.chords == !r.chords.isEmpty() && p.bass == !r.bass.isEmpty() && p.drums == !r.drums.isEmpty(), "part toggles");
            double prevEnd = -1; int last = -1;
            for (AiMidiGenerator.Note n : r.melody) {
                check(in[n.pitch % 12], "melody note stays in the scale");
                check(n.pitch >= 60 && n.pitch <= 84, "melody range");
                check(n.startBeat >= 0 && n.startBeat + n.lengthBeat <= end + 1e-9, "melody inside the clip");
                check(n.startBeat >= prevEnd - 0.04 - 1e-9, "melody notes do not overlap"); prevEnd = n.startBeat + n.lengthBeat;
                check(last < 0 || Math.abs(n.pitch - last) <= 9, "no leaps over a major sixth"); last = n.pitch;
                check(n.velocity >= 1 && n.velocity <= 127, "velocity");
            }
            for (AiMidiGenerator.Note n : r.chords) check(in[n.pitch % 12] && n.startBeat < end && n.startBeat + n.lengthBeat <= end, "chord notes in scale and clip");

            for (AiMidiGenerator.Note n : r.bass) check(n.startBeat >= 0 && n.startBeat + n.lengthBeat <= end + 1e-9, "bass inside clip");
            for (AiMidiGenerator.Note n : r.drums) check((n.pitch == 36 || n.pitch == 38 || n.pitch == 42) && n.startBeat >= 0 && n.startBeat < end, "drum notes");
            for (AiMidiGenerator.Note n : AiMidiGenerator.toRackPitches(r.drums)) check(n.pitch == 36 || n.pitch == 37 || n.pitch == 39, "rack pads");
            // strong-beat melody notes are chord tones
            for (AiMidiGenerator.Note n : r.melody) {
                double sixteenth = n.startBeat * 4; if (Math.abs(sixteenth - Math.rint(sixteenth)) > 1e-9) continue; long pos = Math.round(sixteenth);
                if (pos % 8 != 0) continue; int bar = (int) (pos / (p.beatsPerBar * 4)); boolean ok = false;
                for (AiMidiGenerator.Note c : r.chords) if (Math.abs(c.startBeat - bar * p.beatsPerBar) < 1e-9 && (c.pitch - n.pitch) % 12 == 0) ok = true;
                if (p.chords) check(ok, "strong-beat melody note is a chord tone");
            }
        }
        check(generated == 3000, "3000 random configurations generated without error");

        for (int[] bad : new int[][]{{-1, 8, 4}, {12, 8, 4}, {0, 0, 4}, {0, 65, 4}, {0, 8, 0}}) {
            AiMidiGenerator.Options p = new AiMidiGenerator.Options(); p.key = bad[0]; p.bars = bad[1]; p.beatsPerBar = bad[2];
            try { AiMidiGenerator.generate(p); check(false, "invalid options are rejected"); } catch (IllegalArgumentException expected) { passed++; }
        }
        AiMidiGenerator.Options nan = new AiMidiGenerator.Options(); nan.creativity = Double.NaN;
        try { AiMidiGenerator.generate(nan); check(false, "NaN creativity rejected"); } catch (IllegalArgumentException expected) { passed++; }

        // conversion follows the tempo map: 120 BPM = 24000 frames per beat at 48 kHz
        Core.Session s = new Core.Session(); s.bpm = 120; Pro.TempoMap m = new Pro.TempoMap(s);
        AiMidiGenerator.Options q = new AiMidiGenerator.Options(); q.bars = 4; AiMidiGenerator.Result rq = AiMidiGenerator.generate(q);
        List<Pro.Note> notes = AiMidiGenerator.toNotes(rq.melody, m, 8, 3);
        check(notes.size() == rq.melody.size(), "all notes converted");
        long perBeat = Math.round(Core.RATE * 60.0 / 120);
        for (int i = 0; i < notes.size(); i++) { Pro.Note n = notes.get(i); AiMidiGenerator.Note src = rq.melody.get(i);
            check(n.start >= 0 && n.length >= 1 && n.channel == 3 && n.pitch == src.pitch, "converted note fields");
            check(Math.abs(n.start - Math.round(src.startBeat * perBeat)) <= 1, "start follows the tempo map"); }
        s.bpm = 60; Pro.TempoMap slow = new Pro.TempoMap(s); check(AiMidiGenerator.toNotes(rq.melody, slow, 0, 0).get(1).start > notes.get(1).start, "slower tempo means later frames");
        System.out.println("AiMidiGeneratorTests passed: " + passed + " checks");
    }
}

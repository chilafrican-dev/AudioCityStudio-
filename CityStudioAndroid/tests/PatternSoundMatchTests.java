package ug.audiocity.studio;

import java.util.*;

/** A pattern placed in Arrange (MIDI lanes + one-shot drum packs) must sound like the pattern editor's own render. */
public final class PatternSoundMatchTests {
    static void check(boolean ok, String m) { if (!ok) throw new AssertionError(m); }

    public static void main(String[] a) {
        int bpm = 100; BeatPilotDrumPattern p = new BeatPilotDrumPattern(bpm, 1); Random r = new Random(4);
        float[][] samples = new float[8][];
        for (int lane = 0; lane < 8; lane++) {
            int frames = (int) (Core.RATE * (lane == 0 ? .7 : .35)); float[] w = new float[frames * 2]; double f = 50 + lane * 140;
            for (int i = 0; i < frames; i++) { double env = Math.exp(-i / (Core.RATE * (lane == 0 ? .18 : .06))); double v = .25 * env * (lane < 3 ? Math.sin(2 * Math.PI * f * i / Core.RATE) : (r.nextDouble() * 2 - 1)); w[i * 2] = (float) v; w[i * 2 + 1] = (float) (v * .9); }
            samples[lane] = w;
        }
        for (int s = 0; s < 16; s++) { if (s % 4 == 0) p.set(0, 0, s, 120); if (s % 8 == 4) p.set(0, 1, s, 110); if (s % 2 == 0) p.set(0, 3, s, 70 + s); if (s == 14) p.set(0, 6, s, 100); }
        float[] editor = p.render(samples);
        int length = editor.length / 2; double[] arrange = new double[length * 2];
        for (int lane = 0; lane < 8; lane++) {
            BeatPilotDrumPattern part = PatternLibrary.lane(p, lane); if (PatternLibrary.empty(part)) continue;
            List<Pro.Note> notes = new ArrayList<>(); for (Pro.Note n : part.notes()) notes.add(new Pro.Note(n.pitch, n.velocity, n.start, Math.max(1, Math.min(n.length, length - n.start)), 9));
            int pitch = 36 + lane; SampledInstruments.Region region = new SampledInstruments.Region(pitch, pitch, 1, 127, pitch, 0, 0, false, 1f, samples[lane], 240);
            SampledInstruments.Pack pack = new SampledInstruments.Pack(BeatPilotDrums.LABELS[lane], "Pattern drum kit", new SampledInstruments.Region[]{region});
            ProMidi.LiveSource src = new ProMidi.LiveSource(notes, Synth.factory(0), pack, length);
            for (int f = 0; f < length; f++) { arrange[f * 2] += src.sample(f, 0); arrange[f * 2 + 1] += src.sample(f, 1); }
        }
        double num = 0, ea = 0, eb = 0, diff = 0;
        for (int i = 0; i < editor.length; i++) { double x = editor[i], y = arrange[i]; num += x * y; ea += x * x; eb += y * y; diff += (x - y) * (x - y); }
        double corr = num / Math.sqrt(ea * eb), levelDb = 10 * Math.log10(eb / ea), errDb = 10 * Math.log10(diff / ea);
        System.out.printf("pattern vs arrange: correlation %.4f, level %.2f dB, difference %.1f dB%n", corr, levelDb, errDb);
        check(corr > .995, "arrange sounds like the pattern editor, correlation " + corr);
        check(Math.abs(levelDb) < .5, "same loudness, off by " + levelDb + " dB");
        // Moving the playhead into the middle of a hit must continue the drum, not drop it.
        BeatPilotDrumPattern kick = PatternLibrary.lane(p, 0); List<Pro.Note> kn = new ArrayList<>(); for (Pro.Note n : kick.notes()) kn.add(new Pro.Note(n.pitch, n.velocity, n.start, n.length, 9));
        SampledInstruments.Pack kp = new SampledInstruments.Pack("Kick", "Pattern drum kit", new SampledInstruments.Region[]{new SampledInstruments.Region(36, 36, 1, 127, 36, 0, 0, false, 1f, samples[0], 240)});
        ProMidi.LiveSource seeking = new ProMidi.LiveSource(kn, Synth.factory(0), kp, length); int at = (int) (Core.RATE * .2131);
        ProMidi.LiveSource walk = new ProMidi.LiveSource(kn, Synth.factory(0), kp, length); float w = 0; for (int f = 0; f <= at; f++) w = walk.sample(f, 0);
        float jumped = seeking.sample(at, 0);
        check(Math.abs(w) > 1e-4 && Math.abs(jumped - w) < 1e-4, "kick still ringing after a seek: " + w + " vs " + jumped);
        System.out.println("PatternSoundMatchTests OK");
    }
}

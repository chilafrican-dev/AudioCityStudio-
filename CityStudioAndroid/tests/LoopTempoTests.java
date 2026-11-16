package ug.audiocity.studio;

public class LoopTempoTests {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static float[] clicks(double bpm, int bars, int rate) {
        int frames = (int) Math.round(bars * 4 * 60.0 / bpm * rate); float[] s = new float[frames * 2];
        double beat = 60.0 / bpm * rate;
        for (int b = 0; b < bars * 4; b++) { int at = (int) Math.round(b * beat); float amp = b % 4 == 0 ? 1f : .6f;
            for (int k = 0; k < 2400 && at + k < frames; k++) { float v = (float) (amp * Math.exp(-k / 300.0) * Math.sin(k * .3)); s[(at + k) * 2] = v; s[(at + k) * 2 + 1] = v; }
            int half = (int) Math.round(b * beat + beat / 2); for (int k = 0; k < 800 && half + k < frames; k++) { float v = (float) (.25 * Math.exp(-k / 120.0) * Math.sin(k * 1.7)); s[(half + k) * 2] += v; s[(half + k) * 2 + 1] += v; } }
        return s;
    }
    public static void main(String[] a) {
        check(LoopTempo.bpmFromName("Afro_Groove_102bpm_Am.wav") == 102, "name tag");
        check(LoopTempo.bpmFromName("amapiano loop 112 BPM.wav") == 112, "name tag spaced");
        check(LoopTempo.bpmFromName("Loop 98.wav") == 98, "bare number");
        check(LoopTempo.bpmFromName("vocal take 3.wav") == 0, "no tempo");
        int rate = Core.RATE;
        for (double bpm : new double[]{90, 100, 115, 128}) {
            float[] s = clicks(bpm, 4, rate);
            LoopTempo.Result r = LoopTempo.analyse("loop.wav", s, rate, 4, 100);
            check(r != null && Math.abs(r.bpm - bpm) < .6, "detect " + bpm + " got " + (r == null ? "none" : r.bpm));
            check(r.bars == 4, "bars for " + bpm + " got " + r.bars);
        }
        LoopTempo.Result named = LoopTempo.analyse("beat_100bpm.wav", clicks(100, 2, rate), rate, 4, 120);
        check(named.fromName && Math.abs(named.bpm - 100) < .01 && named.bars == 2, "named fit");
        check(LoopTempo.targetFrames(48000, 120, 60) == 96000, "target frames");
        System.out.println("loop tempo checks passed");
    }
}

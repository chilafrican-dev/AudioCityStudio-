package ug.audiocity.studio;

public class BleedRemoverTests {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) throws Exception {
        int rate = Core.RATE, frames = rate * 12; java.util.Random rnd = new java.util.Random(7);
        float[] ref = new float[frames * 2], voice = new float[frames * 2], take = new float[frames * 2];
        // Backing track: kick, hats and a bass line.
        for (int f = 0; f < frames; f++) {
            double t = f / (double) rate, beat = t * 2, ph = beat - Math.floor(beat);
            double kick = Math.exp(-ph * 18) * Math.sin(2 * Math.PI * 55 * ph * (1 + 2 * Math.exp(-ph * 30)) / 2), hat = (rnd.nextDouble() * 2 - 1) * Math.exp(-((beat * 2) % 1) * 40) * .3;
            double bass = .3 * Math.sin(2 * Math.PI * (t % 4 < 2 ? 82.4 : 110) * t);
            ref[f * 2] = ref[f * 2 + 1] = (float) (.5 * kick + hat + bass);
            double vt = t - 1; double v = vt > 0 && (vt % 3) < 2.2 ? .25 * Math.sin(2 * Math.PI * (220 + 30 * Math.sin(vt)) * vt) * (1 + .3 * Math.sin(2 * Math.PI * 5 * vt)) : 0;
            voice[f * 2] = voice[f * 2 + 1] = (float) v;
        }
        // Speaker to mic: 37 ms delay, small room echo, lower level.
        int d = (int) (rate * .037), e1 = (int) (rate * .011), e2 = (int) (rate * .023);
        for (int f = 0; f < frames; f++) {
            double b = 0; if (f >= d) b += .45 * ref[(f - d) * 2]; if (f >= d + e1) b += .18 * ref[(f - d - e1) * 2]; if (f >= d + e2) b -= .09 * ref[(f - d - e2) * 2];
            take[f * 2] = take[f * 2 + 1] = (float) (voice[f * 2] + b + (rnd.nextDouble() - .5) * 1e-4);
        }
        int found = BleedRemover.delay(BleedRemover.of(take), BleedRemover.of(ref), frames);
        check(Math.abs(found - d) <= 8, "delay found " + found + " expected " + d);
        final float[] out = new float[frames * 2]; final int[] pos = {0};
        BleedRemover.Report r = BleedRemover.process(BleedRemover.of(take), BleedRemover.of(ref), frames, .6, (s, n) -> { System.arraycopy(s, 0, out, pos[0] * 2, n * 2); pos[0] += n; });
        check(pos[0] == frames, "output length matches the take");
        double bleedBefore = 0, bleedAfter = 0, voiceE = 0, voiceErr = 0;
        for (int f = rate; f < frames - rate; f++) {
            double v = voice[f * 2], tk = take[f * 2], o = out[f * 2];
            bleedBefore += (tk - v) * (tk - v); bleedAfter += (o - v) * (o - v);
            if (Math.abs(v) > 0) { voiceE += v * v; }
        }
        double reduction = 10 * Math.log10(bleedBefore / bleedAfter);
        System.out.printf("delay %d, bleed reduced by %.1f dB (report %.1f dB)%n", found, reduction, r.removedDb);
        check(reduction > 12, "backing track bleed reduced by at least 12 dB, got " + reduction);
        // Voice is preserved: correlation between output and the clean voice stays high where the voice sings.
        double num = 0, a = 0, b = 0; for (int f = rate; f < frames - rate; f++) { double v = voice[f * 2]; if (v == 0) continue; num += v * out[f * 2]; a += v * v; b += out[f * 2] * out[f * 2]; }
        double corr = num / Math.sqrt(a * b); System.out.printf("voice correlation %.3f%n", corr);
        check(corr > .9, "voice kept intact, correlation " + corr);
        hardRoom();
        System.out.println("bleed remover checks passed");
    }

    /** Harder phone: 20 s take, speaker clock 150 ppm slow (delay creeps by ~140 frames), 130 ms room tail, level wobble, voice loud. */
    static void hardRoom() throws Exception {
        int rate = Core.RATE, frames = rate * 20; java.util.Random rnd = new java.util.Random(11);
        float[] ref = new float[frames * 2], voice = new float[frames * 2], take = new float[frames * 2];
        for (int f = 0; f < frames; f++) {
            double t = f / (double) rate, beat = t * 2.2, ph = beat - Math.floor(beat);
            double kick = Math.exp(-ph * 14) * Math.sin(2 * Math.PI * 50 * ph * (1 + 2 * Math.exp(-ph * 30)) / 2), hat = (rnd.nextDouble() * 2 - 1) * Math.exp(-((beat * 2) % 1) * 30) * .35;
            double chord = .18 * (Math.sin(2 * Math.PI * 261.6 * t) + Math.sin(2 * Math.PI * 329.6 * t) + Math.sin(2 * Math.PI * 392 * t)) * (((int) (t * 2)) % 2 == 0 ? 1 : .6);
            ref[f * 2] = ref[f * 2 + 1] = (float) (.5 * kick + hat + chord + .3 * Math.sin(2 * Math.PI * (t % 4 < 2 ? 73.4 : 98) * t));
            double vt = t - .5; double v = vt > 0 && (vt % 4) < 3 ? .3 * Math.sin(2 * Math.PI * (196 + 40 * Math.sin(vt * 1.3)) * vt) * (1 + .4 * Math.sin(2 * Math.PI * 5.5 * vt)) : 0;
            voice[f * 2] = voice[f * 2 + 1] = (float) v;
        }
        int d = (int) (rate * .052); int[] echo = {0, 190, 530, 1100, 2000, 3300, 5200}; double[] gain = {.40, .22, -.15, .12, -.08, .05, .03};
        for (int f = 0; f < frames; f++) {
            double b = 0; double drift = f * 150e-6; double wobble = 1 + .12 * Math.sin(f / (double) rate * .7);
            for (int k = 0; k < echo.length; k++) { double src = f - d - echo[k] - drift; if (src < 1) continue; int i0 = (int) src; double fr = src - i0; b += gain[k] * (ref[i0 * 2] * (1 - fr) + ref[(i0 + 1) * 2] * fr); }
            take[f * 2] = take[f * 2 + 1] = (float) (voice[f * 2] + b * wobble + (rnd.nextDouble() - .5) * 2e-4);
        }
        final float[] out = new float[frames * 2]; final int[] pos = {0};
        long t0 = System.nanoTime();
        BleedRemover.Report rep = BleedRemover.process(BleedRemover.of(take), BleedRemover.of(ref), frames, .6, (s, n) -> { System.arraycopy(s, 0, out, pos[0] * 2, n * 2); pos[0] += n; });
        System.out.println("segments " + rep.segments + " drift frames " + rep.driftFrames + " delay " + rep.delayFrames);
        double before = 0, after = 0, num = 0, a = 0, b2 = 0;
        for (int f = rate; f < frames - rate; f++) { double v = voice[f * 2]; before += (take[f * 2] - v) * (take[f * 2] - v); after += (out[f * 2] - v) * (out[f * 2] - v); if (v != 0) { num += v * out[f * 2]; a += v * v; b2 += out[f * 2] * (double) out[f * 2]; } }
        double red = 10 * Math.log10(before / after), corr = num / Math.sqrt(a * b2);
        System.out.printf("hard room: bleed reduced %.1f dB, voice correlation %.3f, %.1f s%n", red, corr, (System.nanoTime() - t0) / 1e9);
        check(red > 15, "hard room bleed reduced by at least 15 dB, got " + red);
        check(corr > .95, "hard room voice kept, correlation " + corr);
    }
}

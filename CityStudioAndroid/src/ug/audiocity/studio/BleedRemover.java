package ug.audiocity.studio;

import java.io.IOException;

/**
 * Removes the backing track that leaked from the phone speaker into a microphone take.
 * The app knows exactly what it played, so the take is aligned with that reference,
 * the speaker-to-mic path is estimated per frequency over the whole take, and the
 * predicted bleed is subtracted, with gentle suppression of what remains. Streams in
 * two passes, so long takes do not need to fit in memory. Host-testable.
 */
public final class BleedRemover {
    private BleedRemover() {}

    /** Supplies interleaved stereo frames from a position; returns frames written (zeros past the end are fine). */
    public interface Reader { void read(long frame, float[] stereo, int frames) throws IOException; }
    public interface Sink { void write(float[] stereo, int frames) throws IOException; }

    static final int N = 8192, HOP = N / 4, BINS = N / 2 + 1;
    public static final int MAX_DELAY = Core.RATE / 2;
    /** Extra reach of the per-segment delay search around the global delay (covers speaker/mic clock drift). */
    static final int TRACK_RANGE = Core.RATE / 25;

    public static final class Report { public int delayFrames; public double removedDb; public int segments, driftFrames; }

    static void safeRead(Reader r, long from, float[] dst, int frames) throws IOException {
        java.util.Arrays.fill(dst, 0, frames * 2, 0f);
        if (from >= 0) { r.read(from, dst, frames); return; }
        int skip = (int) Math.min(frames, -from); if (skip >= frames) return;
        float[] tmp = new float[(frames - skip) * 2]; r.read(0, tmp, frames - skip); System.arraycopy(tmp, 0, dst, skip * 2, tmp.length);
    }

    /** Speaker-to-mic delay (frames the take lags the reference), from GCC-PHAT on a decimated excerpt. */
    public static int delay(Reader take, Reader ref, long frames) throws IOException {
        int d = delayAt(take, ref, 0, Math.min(frames, (long) Core.RATE * 8), 0, MAX_DELAY, null);
        return Math.max(0, d);
    }

    /**
     * GCC-PHAT delay of take[from..] against ref, searching delays lo..lo+span. Returns -1 when no clear peak
     * (silent reference, or a voice-only stretch). quality[0] receives peak / rms of the correlation.
     */
    static int delayAt(Reader take, Reader ref, long from, long frames, int lo, int span, double[] quality) throws IOException {
        int dec = 4; int len = (int) Math.min(frames, (long) Core.RATE * 8) / dec;
        if (len < 2048) return quality == null ? 0 : -1;
        int size = 1; while (size < 2 * len) size <<= 1;
        double[] ar = new double[size], ai = new double[size], br = new double[size], bi = new double[size];
        float[] blockA = new float[dec * 2 * 1024], blockB = new float[dec * 2 * 1024];
        for (int done = 0; done < len; done += 1024) {
            int count = Math.min(1024, len - done);
            safeRead(take, from + (long) done * dec, blockA, count * dec); safeRead(ref, from - lo + (long) done * dec, blockB, count * dec);
            for (int i = 0; i < count; i++) { double sa = 0, sb = 0; for (int k = 0; k < dec; k++) { int f = (i * dec + k) * 2; sa += blockA[f] + blockA[f + 1]; sb += blockB[f] + blockB[f + 1]; } ar[done + i] = sa; br[done + i] = sb; }
        }
        Pro.fft(ar, ai, false); Pro.fft(br, bi, false);
        for (int k = 0; k < size; k++) { double re = ar[k] * br[k] + ai[k] * bi[k], im = ai[k] * br[k] - ar[k] * bi[k], mag = Math.hypot(re, im) + 1e-12; ar[k] = re / mag; ai[k] = im / mag; }
        Pro.fft(ar, ai, true);
        int maxLag = Math.min(span / dec, size / 2 - 1), best = 0; double peak = -Double.MAX_VALUE, energy = 0;
        for (int lag = 0; lag <= maxLag; lag++) { energy += ar[lag] * ar[lag]; if (ar[lag] > peak) { peak = ar[lag]; best = lag; } }
        double rms = Math.sqrt(energy / (maxLag + 1)) + 1e-12; if (quality != null) quality[0] = peak / rms;
        if (quality != null && peak / rms < 6) return -1;
        return Math.max(0, best * dec - dec) + lo;
    }

    /** Most trustworthy delay over several excerpts spread across the take (a voice-only stretch can mislead one of them). */
    static int bestDelay(Reader take, Reader ref, long frames) throws IOException {
        int excerpt = Core.RATE * 6, tries = (int) Math.max(1, Math.min(6, frames / excerpt)); int best = -1; double bestQ = -1; double[] q = new double[1];
        for (int i = 0; i < tries; i++) {
            long from = tries == 1 ? 0 : (frames - excerpt) * i / (tries - 1);
            int d = delayAt(take, ref, Math.max(0, from), Math.min(frames - Math.max(0, from), excerpt), 0, MAX_DELAY, q);
            if (d >= 0 && q[0] > bestQ) { bestQ = q[0]; best = d; }
        }
        return best >= 0 ? best : delay(take, ref, frames);
    }

    static double[] window() { double[] w = new double[N]; for (int i = 0; i < N; i++) w[i] = Math.sqrt(.5 - .5 * Math.cos(2 * Math.PI * i / N)); return w; }

    /** Per-frame working buffers: the delayed reference spectrum and the take spectra. */
    private static final class Frame {
        final double[] w = window();
        final double[] xr = new double[N], xi = new double[N];
        final double[][] yr = new double[2][N], yi = new double[2][N];
        final float[] tb = new float[N * 2], rb = new float[N * 2];
        boolean same; long start;
        void load(Reader take, Reader ref, long f, int d, long frames) throws IOException {
            start = f * HOP - (N - HOP);
            readWindow(take, start, tb, frames); readWindow(ref, start - d, rb, frames + d);
            for (int i = 0; i < N; i++) { xr[i] = (rb[i * 2] + rb[i * 2 + 1]) * .5 * w[i]; xi[i] = 0; }
            Pro.fft(xr, xi, false);
            same = mono(tb);
            for (int ch = 0; ch < (same ? 1 : 2); ch++) { for (int i = 0; i < N; i++) { yr[ch][i] = tb[i * 2 + ch] * w[i]; yi[ch][i] = 0; } Pro.fft(yr[ch], yi[ch], false); }
            if (same) { System.arraycopy(yr[0], 0, yr[1], 0, N); System.arraycopy(yi[0], 0, yi[1], 0, N); }
        }
    }

    /** Processes frames of the take; output has the same length. strength 0..1 scales the residual suppression. */
    public static Report process(Reader take, Reader ref, long frames, double strength, Sink out) throws IOException {
        Report report = new Report();
        int global = bestDelay(take, ref, frames);
        // The phone's playback and recording clocks differ slightly, so the delay creeps over a long take. Track it per segment.
        int segFrames = (int) Math.max(Core.RATE * 2L, ((frames / 48 + HOP - 1) / HOP) * (long) HOP);
        int segs = (int) Math.max(1, (frames + segFrames - 1) / segFrames);
        int[] sd = new int[segs]; boolean[] ok = new boolean[segs]; double[] q = new double[1];
        int lo = Math.max(0, global - TRACK_RANGE);
        for (int s = 0; s < segs; s++) {
            long from = (long) s * segFrames; long len = Math.min(segFrames, frames - from);
            int found = delayAt(take, ref, from, len, lo, 2 * TRACK_RANGE, q);
            ok[s] = found >= 0; sd[s] = found >= 0 ? found : global;
        }
        for (int s = 0; s < segs; s++) if (!ok[s]) { int near = -1; for (int k = 1; k < segs && near < 0; k++) { if (s - k >= 0 && ok[s - k]) near = sd[s - k]; else if (s + k < segs && ok[s + k]) near = sd[s + k]; } if (near >= 0) sd[s] = near; }
        int minD = Integer.MAX_VALUE, maxD = 0; for (int v : sd) { minD = Math.min(minD, v); maxD = Math.max(maxD, v); }
        report.delayFrames = sd[segs / 2]; report.segments = segs; report.driftFrames = maxD - minD;
        long frameCount = (frames + HOP - 1) / HOP + 3;
        Frame fr = new Frame();
        // Learn the speaker-to-mic path per segment. The voice is unrelated to the beat, so it averages out, and frames
        // where the voice is loud are down-weighted on each refinement round (iteratively re-weighted least squares).
        float[][][] hRe = new float[segs][2][BINS], hIm = new float[segs][2][BINS];
        double[][] sxx = new double[segs][BINS]; double[][][] sxyRe = new double[segs][2][BINS], sxyIm = new double[segs][2][BINS];
        for (int round = 0; round < 3; round++) {
            for (int s = 0; s < segs; s++) { java.util.Arrays.fill(sxx[s], 0); for (int ch = 0; ch < 2; ch++) { java.util.Arrays.fill(sxyRe[s][ch], 0); java.util.Arrays.fill(sxyIm[s][ch], 0); } }
            for (long f = 0; f < frameCount; f++) {
                int seg = segmentOf(f, segFrames, segs);
                fr.load(take, ref, f, delayOf(f, segFrames, sd), frames);
                double weight = 1;
                if (round > 0) {
                    double err = 0, tot = 0;
                    for (int ch = 0; ch < 2; ch++) for (int k = 0; k < BINS; k++) {
                        double pr = hRe[seg][ch][k] * fr.xr[k] - hIm[seg][ch][k] * fr.xi[k], pi = hRe[seg][ch][k] * fr.xi[k] + hIm[seg][ch][k] * fr.xr[k];
                        double er = fr.yr[ch][k] - pr, ei = fr.yi[ch][k] - pi; err += er * er + ei * ei; tot += fr.yr[ch][k] * fr.yr[ch][k] + fr.yi[ch][k] * fr.yi[ch][k];
                    }
                    weight = 1 / (err + .02 * tot + 1e-6);
                }
                for (int k = 0; k < BINS; k++) sxx[seg][k] += weight * (fr.xr[k] * fr.xr[k] + fr.xi[k] * fr.xi[k]);
                for (int ch = 0; ch < 2; ch++) for (int k = 0; k < BINS; k++) {
                    sxyRe[seg][ch][k] += weight * (fr.yr[ch][k] * fr.xr[k] + fr.yi[ch][k] * fr.xi[k]); sxyIm[seg][ch][k] += weight * (fr.yi[ch][k] * fr.xr[k] - fr.yr[ch][k] * fr.xi[k]);
                }
            }
            for (int s = 0; s < segs; s++) {
                int from = Math.max(0, s - 1), to = Math.min(segs - 1, s + 1); double top = 0;
                double[] den = new double[BINS];
                for (int j = from; j <= to; j++) { double wj = j == s ? 1 : .5; for (int k = 0; k < BINS; k++) den[k] += wj * sxx[j][k]; }
                for (int k = 0; k < BINS; k++) top = Math.max(top, den[k]);
                double reg = top * 1e-6 + 1e-30;
                for (int ch = 0; ch < 2; ch++) for (int k = 0; k < BINS; k++) {
                    double nr = 0, ni = 0; for (int j = from; j <= to; j++) { double wj = j == s ? 1 : .5; nr += wj * sxyRe[j][ch][k]; ni += wj * sxyIm[j][ch][k]; }
                    hRe[s][ch][k] = (float) (nr / (den[k] + reg)); hIm[s][ch][k] = (float) (ni / (den[k] + reg));
                }
            }
        }
        sxx = null; sxyRe = null; sxyIm = null;
        // Subtract the predicted bleed, then Wiener-style suppression of the small remainder where it still dominates.
        double floor = Math.pow(10, -(8 + 24 * Math.max(0, Math.min(1, strength))) / 20), misfit = .12 * Math.max(.2, strength);
        double[][] ola = new double[2][N]; float[] emit = new float[HOP * 2]; long emitted = 0; double inEnergy = 0, outEnergy = 0; double[] gain = new double[BINS];
        for (long f = 0; f < frameCount && emitted < frames; f++) {
            int seg = segmentOf(f, segFrames, segs);
            fr.load(take, ref, f, delayOf(f, segFrames, sd), frames);
            for (int ch = 0; ch < (fr.same ? 1 : 2); ch++) {
                double[] a = fr.yr[ch], b = fr.yi[ch];
                for (int k = 0; k < BINS; k++) {
                    double pr = hRe[seg][ch][k] * fr.xr[k] - hIm[seg][ch][k] * fr.xi[k], pi = hRe[seg][ch][k] * fr.xi[k] + hIm[seg][ch][k] * fr.xr[k];
                    double er = a[k] - pr, ei = b[k] - pi, ee = er * er + ei * ei + 1e-18, pe = pr * pr + pi * pi;
                    a[k] = er; b[k] = ei; gain[k] = Math.max(floor, 1 - misfit * pe / ee * 4);
                }
                for (int k = 0; k < BINS; k++) { double g = gain[k]; if (k > 1 && k < BINS - 2) g = (gain[k - 2] + 2 * gain[k - 1] + 3 * gain[k] + 2 * gain[k + 1] + gain[k + 2]) / 9; a[k] *= g; b[k] *= g; if (k > 0 && k < N / 2) { a[N - k] = a[k]; b[N - k] = -b[k]; } }
                Pro.fft(a, b, true);
                double[] o = ola[ch]; for (int i = 0; i < N; i++) o[i] += a[i] * fr.w[i] * .5;
                if (fr.same) { double[] o1 = ola[1]; for (int i = 0; i < N; i++) o1[i] += a[i] * fr.w[i] * .5; }
            }
            long first = fr.start; int n = HOP;
            for (int i = 0; i < n; i++) { emit[i * 2] = (float) ola[0][i]; emit[i * 2 + 1] = (float) ola[1][i]; }
            if (first >= 0) {
                int count = (int) Math.min(n, frames - emitted);
                if (count > 0) {
                    for (int i = 0; i < count; i++) { float tv = fr.tb[(N - HOP + i) * 2]; inEnergy += tv * (double) tv; outEnergy += emit[i * 2] * (double) emit[i * 2]; }
                    out.write(emit, count); emitted += count;
                }
            }
            for (int ch = 0; ch < 2; ch++) { System.arraycopy(ola[ch], HOP, ola[ch], 0, N - HOP); java.util.Arrays.fill(ola[ch], N - HOP, N, 0); }
        }
        if (emitted < frames) { java.util.Arrays.fill(emit, 0); while (emitted < frames) { int count = (int) Math.min(HOP, frames - emitted); out.write(emit, count); emitted += count; } }
        report.removedDb = inEnergy > 0 && outEnergy > 0 ? 10 * Math.log10(inEnergy / outEnergy) : 0;
        return report;
    }

    static int segmentOf(long f, int segFrames, int segs) { long center = f * HOP - (N - HOP) + N / 2; return (int) Math.max(0, Math.min(segs - 1, center / segFrames)); }

    /** Delay at a frame: linear between segment midpoints, so it glides instead of jumping. */
    static int delayOf(long f, int segFrames, int[] sd) {
        double center = f * (double) HOP - (N - HOP) + N / 2.0, t = center / segFrames - .5; int i0 = (int) Math.floor(t);
        if (i0 < 0) return sd[0]; if (i0 >= sd.length - 1) return sd[sd.length - 1];
        double fr = t - i0; return (int) Math.round(sd[i0] * (1 - fr) + sd[i0 + 1] * fr);
    }

    static boolean mono(float[] tb) { for (int i = 0; i < tb.length; i += 2) if (tb[i] != tb[i + 1]) return false; return true; }

    private static void readWindow(Reader r, long start, float[] dst, long limit) throws IOException {
        java.util.Arrays.fill(dst, 0);
        long from = Math.max(0, start), to = Math.min(limit, start + N);
        if (to <= from) return;
        int offset = (int) (from - start), count = (int) (to - from);
        float[] tmp = count * 2 == dst.length && offset == 0 ? dst : new float[count * 2];
        r.read(from, tmp, count);
        if (tmp != dst) System.arraycopy(tmp, 0, dst, offset * 2, count * 2);
    }

    /** Reader over an interleaved stereo array (zeros outside it). */
    public static Reader of(final float[] stereo) {
        return (frame, out, frames) -> { for (int i = 0; i < frames; i++) { long f = frame + i; boolean in = f >= 0 && f * 2 + 1 < stereo.length; out[i * 2] = in ? stereo[(int) f * 2] : 0; out[i * 2 + 1] = in ? stereo[(int) f * 2 + 1] : 0; } };
    }
}

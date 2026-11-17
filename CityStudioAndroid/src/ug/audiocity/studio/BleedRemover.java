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

    static final int N = 4096, HOP = 1024, BINS = N / 2 + 1;
    public static final int MAX_DELAY = Core.RATE / 2;

    public static final class Report { public int delayFrames; public double removedDb; }

    /** Speaker-to-mic delay (frames the take lags the reference), from GCC-PHAT on a decimated excerpt. */
    public static int delay(Reader take, Reader ref, long frames) throws IOException {
        int dec = 4, seconds = 8; int len = (int) Math.min(frames, (long) Core.RATE * seconds) / dec;
        if (len < 2048) return 0;
        int size = 1; while (size < 2 * len) size <<= 1;
        double[] ar = new double[size], ai = new double[size], br = new double[size], bi = new double[size];
        float[] blockA = new float[dec * 2 * 1024], blockB = new float[dec * 2 * 1024];
        for (int done = 0; done < len; done += 1024) {
            int count = Math.min(1024, len - done);
            take.read((long) done * dec, blockA, count * dec); ref.read((long) done * dec, blockB, count * dec);
            for (int i = 0; i < count; i++) { double sa = 0, sb = 0; for (int k = 0; k < dec; k++) { int f = (i * dec + k) * 2; sa += blockA[f] + blockA[f + 1]; sb += blockB[f] + blockB[f + 1]; } ar[done + i] = sa; br[done + i] = sb; }
        }
        Pro.fft(ar, ai, false); Pro.fft(br, bi, false);
        for (int k = 0; k < size; k++) { double re = ar[k] * br[k] + ai[k] * bi[k], im = ai[k] * br[k] - ar[k] * bi[k], mag = Math.hypot(re, im) + 1e-12; ar[k] = re / mag; ai[k] = im / mag; }
        Pro.fft(ar, ai, true);
        int maxLag = Math.min(MAX_DELAY / dec, size / 2 - 1), best = 0; double peak = -Double.MAX_VALUE;
        for (int lag = 0; lag <= maxLag; lag++) if (ar[lag] > peak) { peak = ar[lag]; best = lag; }
        return Math.max(0, best * dec - dec);
    }

    static double[] window() { double[] w = new double[N]; for (int i = 0; i < N; i++) w[i] = Math.sqrt(.5 - .5 * Math.cos(2 * Math.PI * i / N)); return w; }

    /** Processes frames of the take; output has the same length. strength 0..1 scales the residual suppression. */
    public static Report process(Reader take, Reader ref, long frames, double strength, Sink out) throws IOException {
        Report report = new Report();
        int d = delay(take, ref, frames); report.delayFrames = d;
        double[] w = window();
        double[][] sxyRe = new double[2][BINS], sxyIm = new double[2][BINS]; double[] sxx = new double[BINS];
        double[][] y = new double[2][], yi = new double[2][]; double[] x = new double[N], xi = new double[N];
        float[] tb = new float[N * 2], rb = new float[N * 2]; double[] aa = new double[N], bb = new double[N];
        long frameCount = (frames + HOP - 1) / HOP + 3;
        // Pass 1: average cross- and auto-spectra over the whole take. The voice is unrelated to the beat, so it averages out.
        for (long f = 0; f < frameCount; f++) {
            long start = f * HOP - (N - HOP);
            readWindow(take, start, tb, frames); readWindow(ref, start - d, rb, frames + d);
            for (int i = 0; i < N; i++) { x[i] = (rb[i * 2] + rb[i * 2 + 1]) * .5 * w[i]; xi[i] = 0; }
            Pro.fft(x, xi, false);
            for (int k = 0; k < BINS; k++) sxx[k] += x[k] * x[k] + xi[k] * xi[k];
            boolean same = mono(tb);
            for (int ch = 0; ch < (same ? 1 : 2); ch++) {
                java.util.Arrays.fill(bb, 0); for (int i = 0; i < N; i++) aa[i] = tb[i * 2 + ch] * w[i];
                Pro.fft(aa, bb, false);
                for (int k = 0; k < BINS; k++) { sxyRe[ch][k] += aa[k] * x[k] + bb[k] * xi[k]; sxyIm[ch][k] += bb[k] * x[k] - aa[k] * xi[k]; }
                if (same) for (int k = 0; k < BINS; k++) { sxyRe[1][k] += aa[k] * x[k] + bb[k] * xi[k]; sxyIm[1][k] += bb[k] * x[k] - aa[k] * xi[k]; }
            }
        }
        double floorSxx = 0; for (double v : sxx) floorSxx = Math.max(floorSxx, v); floorSxx *= 1e-7;
        double[][] hRe = new double[2][BINS], hIm = new double[2][BINS];
        for (int ch = 0; ch < 2; ch++) for (int k = 0; k < BINS; k++) { double den = sxx[k] + floorSxx; hRe[ch][k] = sxyRe[ch][k] / den; hIm[ch][k] = sxyIm[ch][k] / den; }
        // Pass 2: subtract the predicted bleed, then softly suppress what is left where the bleed dominates.
        double beta = 1.2 * strength, floor = 1 - .7 * strength;
        double[][] ola = new double[2][N]; float[] emit = new float[HOP * 2]; long emitted = 0; double inEnergy = 0, outEnergy = 0;
        for (long f = 0; f < frameCount && emitted < frames; f++) {
            long start = f * HOP - (N - HOP);
            readWindow(take, start, tb, frames); readWindow(ref, start - d, rb, frames + d);
            for (int i = 0; i < N; i++) { x[i] = (rb[i * 2] + rb[i * 2 + 1]) * .5 * w[i]; xi[i] = 0; }
            Pro.fft(x, xi, false);
            boolean same = mono(tb);
            for (int ch = 0; ch < (same ? 1 : 2); ch++) {
                double[] a = aa, b = bb; java.util.Arrays.fill(b, 0); for (int i = 0; i < N; i++) a[i] = tb[i * 2 + ch] * w[i];
                Pro.fft(a, b, false);
                for (int k = 0; k < BINS; k++) {
                    double pr = hRe[ch][k] * x[k] - hIm[ch][k] * xi[k], pi = hRe[ch][k] * xi[k] + hIm[ch][k] * x[k];
                    double er = a[k] - pr, ei = b[k] - pi, ye = a[k] * a[k] + b[k] * b[k] + 1e-18, pe = pr * pr + pi * pi;
                    double g = Math.max(floor, 1 - beta * pe / ye);
                    er *= g; ei *= g;
                    a[k] = er; b[k] = ei; if (k > 0 && k < N / 2) { a[N - k] = er; b[N - k] = -ei; }
                }
                Pro.fft(a, b, true);
                double[] o = ola[ch]; for (int i = 0; i < N; i++) o[i] += a[i] * w[i] * .5;
                if (same) { double[] o1 = ola[1]; for (int i = 0; i < N; i++) o1[i] += a[i] * w[i] * .5; }
            }
            {
                long first = start; int n = HOP;
                for (int i = 0; i < n; i++) { emit[i * 2] = (float) ola[0][i]; emit[i * 2 + 1] = (float) ola[1][i]; }
                if (first >= 0) {
                    int count = (int) Math.min(n, frames - emitted);
                    if (count > 0) {
                        for (int i = 0; i < count; i++) { inEnergy += tb[i * 2] * (double) tb[i * 2]; outEnergy += emit[i * 2] * (double) emit[i * 2]; }
                        out.write(emit, count); emitted += count;
                    }
                }
                for (int ch = 0; ch < 2; ch++) { System.arraycopy(ola[ch], HOP, ola[ch], 0, N - HOP); java.util.Arrays.fill(ola[ch], N - HOP, N, 0); }
            }
        }
        if (emitted < frames) { java.util.Arrays.fill(emit, 0); while (emitted < frames) { int count = (int) Math.min(HOP, frames - emitted); out.write(emit, count); emitted += count; } }
        report.removedDb = inEnergy > 0 && outEnergy > 0 ? 10 * Math.log10(inEnergy / outEnergy) : 0;
        return report;
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

package ug.audiocity.lyrics;

import java.util.Arrays;

/**
 * Turns decoded song audio (any rate, mono or stereo 16-bit) into what the speech model expects: mono float at
 * 16 kHz. Mixes to mono, removes rumble and kick-drum below about 100 Hz (the voice is not there, the beat is), and
 * resamples with a windowed-sinc low-pass so the beat's high end does not fold back into the voice band. Plain Java.
 */
final class Audio16k {
    static final int RATE = 16000;
    private static final int HALF = 24;           // filter taps on each side
    private static final int PHASES = 512;        // kernel table resolution per input sample

    private final int inRate, channels;
    private final double step;                    // input samples per output sample
    private final float[] table;                  // kernel(d) for d in [-HALF, HALF] at 1/PHASES resolution
    private float[] in = new float[1 << 16];
    private long inBase;                          // source index of in[0]
    private int inCount;
    private float[] out = new float[1 << 16];
    private int outCount;
    private long nextOut;                         // index of the next output sample
    private double hpState, hpPrev;
    private final double hpCoef;

    Audio16k(int inRate, int channels) {
        this.inRate = inRate;
        this.channels = Math.max(1, channels);
        this.step = inRate / (double) RATE;
        // Low-pass at 92 % of the output Nyquist frequency, expressed in cycles per input sample × 2.
        double cutoff = Math.min(1.0, RATE / (double) inRate) * 0.92;
        table = new float[(2 * HALF) * PHASES + 1];
        for (int i = 0; i < table.length; i++) {
            double d = i / (double) PHASES - HALF;
            double x = Math.PI * d * cutoff;
            double sinc = Math.abs(d) < 1e-9 ? 1 : Math.sin(x) / x;
            double win = 0.42 + 0.5 * Math.cos(Math.PI * d / HALF) + 0.08 * Math.cos(2 * Math.PI * d / HALF); // Blackman
            table[i] = (float) (cutoff * sinc * win);
        }
        hpCoef = Math.exp(-2 * Math.PI * 100.0 / inRate);
    }

    /** Adds interleaved 16-bit samples. */
    void add(short[] data, int frames) {
        ensureIn(frames);
        for (int i = 0; i < frames; i++) {
            double m = 0;
            for (int c = 0; c < channels; c++) m += data[i * channels + c];
            m /= channels * 32768.0;
            // One-pole high-pass at ~100 Hz.
            hpState = hpCoef * (hpState + m - hpPrev);
            hpPrev = m;
            in[inCount++] = (float) hpState;
        }
        produce(false);
    }

    /** Adds mono float samples (used by tests). */
    void addMono(float[] data, int n) {
        ensureIn(n);
        for (int i = 0; i < n; i++) {
            double m = data[i];
            hpState = hpCoef * (hpState + m - hpPrev);
            hpPrev = m;
            in[inCount++] = (float) hpState;
        }
        produce(false);
    }

    /** Finishes the stream and returns all 16 kHz samples. */
    float[] finish() {
        produce(true);
        return Arrays.copyOf(out, outCount);
    }

    private void ensureIn(int more) {
        // Drop samples that no future output needs, then grow if necessary.
        long needFrom = (long) Math.floor(nextOut * step) - HALF - 1;
        int drop = (int) Math.max(0, Math.min(inCount, needFrom - inBase));
        if (drop > 0) { System.arraycopy(in, drop, in, 0, inCount - drop); inCount -= drop; inBase += drop; }
        if (inCount + more > in.length) in = Arrays.copyOf(in, Math.max(in.length * 2, inCount + more));
    }

    private void produce(boolean end) {
        long available = inBase + inCount; // one past the last source index
        while (true) {
            double pos = nextOut * step;
            long centre = (long) Math.floor(pos);
            if (!end && centre + HALF >= available) break;
            if (end && centre >= available) break;
            double acc = 0;
            for (long k = centre - HALF + 1; k <= centre + HALF; k++) {
                if (k < inBase || k >= available) continue;
                double d = (k - pos + HALF) * PHASES;
                int i = (int) d;
                if (i < 0 || i >= table.length - 1) continue;
                float f = (float) (d - i);
                acc += in[(int) (k - inBase)] * (table[i] + (table[i + 1] - table[i]) * f);
            }
            if (outCount == out.length) out = Arrays.copyOf(out, out.length * 2);
            out[outCount++] = (float) acc;
            nextOut++;
        }
    }
}

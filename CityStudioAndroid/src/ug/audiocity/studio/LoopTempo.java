package ug.audiocity.studio;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the tempo of an imported loop (file name first, then audio analysis) and how to fit it to the project. Host-testable. */
public final class LoopTempo {
    private LoopTempo() {}
    public static final int MIN_BPM = 70, MAX_BPM = 180;

    public static final class Result {
        public final double bpm; public final int bars; public final boolean fromName; public final double confidence;
        Result(double bpm, int bars, boolean fromName, double confidence) { this.bpm = bpm; this.bars = bars; this.fromName = fromName; this.confidence = confidence; }
    }

    private static final Pattern BPM_TAG = Pattern.compile("(?<!\\d)(\\d{2,3})(?:\\.\\d+)?\\s*-?_?\\s*bpm", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])(\\d{2,3})(?![\\d.])");

    /** "Afro_Groove_102bpm_Am.wav" gives 102; a bare "Loop 98.wav" gives 98 when it is a plausible tempo. 0 when none. */
    public static int bpmFromName(String name) {
        if (name == null) return 0;
        Matcher m = BPM_TAG.matcher(name);
        if (m.find()) { int v = Integer.parseInt(m.group(1)); if (v >= 50 && v <= 220) return v; }
        String base = name.replaceFirst("\\.[A-Za-z0-9]{2,5}$", "").replaceFirst("^\\d+[-_ .]+", "");
        Matcher n = NUMBER.matcher(base); int found = 0, count = 0;
        while (n.find()) { int v = Integer.parseInt(n.group(1)); if (v >= MIN_BPM && v <= MAX_BPM) { found = v; count++; } }
        return count == 1 ? found : 0;
    }

    /** Onset-envelope autocorrelation tempo estimate for interleaved stereo at rate. Returns {bpm, confidence 0..1}. */
    public static double[] detect(float[] stereo, int rate) {
        int frames = stereo.length / 2, hop = 512;
        int count = frames / hop;
        if (count < 32) return new double[]{0, 0};
        double[] env = new double[count]; double prev = 0;
        for (int i = 0; i < count; i++) {
            double e = 0; int base = i * hop;
            for (int k = 0; k < hop; k++) { float l = stereo[(base + k) * 2], r = stereo[(base + k) * 2 + 1]; double v = (l + r) * .5; e += v * v; }
            e = Math.log1p(e * 1000);
            env[i] = Math.max(0, e - prev); prev = e;
        }
        double mean = 0; for (double v : env) mean += v; mean /= count;
        for (int i = 0; i < count; i++) env[i] -= mean;
        double framesPerSec = rate / (double) hop;
        int minLag = (int) Math.floor(framesPerSec * 60 / MAX_BPM), maxLag = (int) Math.ceil(framesPerSec * 60 / MIN_BPM);
        maxLag = Math.min(maxLag, count / 2);
        double best = -1, zero = 1e-12; int bestLag = 0;
        for (int i = 0; i < count; i++) zero += env[i] * env[i];
        double[] ac = new double[maxLag + 2];
        for (int lag = Math.max(1, minLag - 1); lag <= maxLag + 1 && lag < count; lag++) {
            double s = 0; for (int i = 0; i + lag < count; i++) s += env[i] * env[i + lag];
            ac[Math.min(lag, ac.length - 1)] = s;
        }
        for (int lag = minLag; lag <= maxLag; lag++) {
            double s = ac[lag];
            if (2 * lag < ac.length) s += .5 * ac[2 * lag];
            if (s > best) { best = s; bestLag = lag; }
        }
        if (bestLag <= 0) return new double[]{0, 0};
        double l = bestLag;
        if (bestLag > 1 && bestLag + 1 < ac.length) {
            double a = ac[bestLag - 1], b = ac[bestLag], c = ac[bestLag + 1], d = a - 2 * b + c;
            if (d < 0) l = bestLag + .5 * (a - c) / d;
        }
        double bpm = 60 * framesPerSec / l;
        return new double[]{bpm, Math.max(0, Math.min(1, ac[bestLag] / zero))};
    }

    /** If the loop is a whole number of bars (1,2,4,8,16) at roughly this tempo, return the exact tempo implied by its length. */
    public static Result fit(int frames, double roughBpm, int beatsPerBar, int rate, boolean fromName, double confidence) {
        double seconds = frames / (double) rate;
        int[] options = {1, 2, 4, 8, 16, 32};
        int bestBars = 0; double bestBpm = roughBpm, bestErr = Double.MAX_VALUE;
        for (int bars : options) {
            double exact = bars * beatsPerBar * 60 / seconds;
            for (double candidate : new double[]{exact}) {
                double err = Math.abs(candidate - roughBpm) / roughBpm;
                if (candidate == exact && err < bestErr && exact >= 50 && exact <= 220) { bestErr = err; bestBpm = exact; bestBars = bars; }
            }
        }
        if (bestErr <= .04) return new Result(bestBpm, bestBars, fromName, confidence);
        return new Result(roughBpm, 0, fromName, confidence);
    }

    /** Half- and double-time readings are equally plausible; pick the one nearest the project tempo, as DAWs do. */
    public static double nearestOctave(double bpm, int projectBpm) {
        double best = bpm; for (double c : new double[]{bpm / 2, bpm * 2}) if (c >= 50 && c <= 220 && Math.abs(Math.log(c / projectBpm)) < Math.abs(Math.log(best / projectBpm))) best = c;
        return best;
    }

    static double[] onsets(float[] stereo, int hop) {
        int frames = stereo.length / 2, count = frames / hop; double[] env = new double[Math.max(0, count)]; double prev = 0;
        for (int i = 0; i < count; i++) { double e = 0; int base = i * hop;
            for (int k = 0; k < hop; k++) { double v = (stereo[(base + k) * 2] + stereo[(base + k) * 2 + 1]) * .5; e += v * v; }
            e = Math.log1p(e * 1000); env[i] = Math.max(0, e - prev); prev = e; }
        return env;
    }

    /** How strongly onsets fall on a beat grid of this tempo (best phase), normalised by average onset strength. */
    static double comb(double[] env, double hopsPerBeat) {
        double mean = 1e-9; for (double v : env) mean += v; mean /= Math.max(1, env.length);
        int phases = Math.max(1, (int) Math.round(hopsPerBeat)); double best = 0;
        for (int ph = 0; ph < phases; ph++) { double sum = 0; int n = 0;
            for (double t = ph; t < env.length; t += hopsPerBeat) { int i = (int) Math.round(t); double v = env[Math.min(env.length - 1, i)];
                if (i > 0) v = Math.max(v, env[i - 1]); if (i + 1 < env.length) v = Math.max(v, env[i + 1]); sum += v; n++; }
            if (n > 0) best = Math.max(best, sum / n / mean); }
        return best;
    }

    public static Result analyse(String name, float[] stereo, int rate, int beatsPerBar, int projectBpm) {
        int frames = stereo.length / 2;
        int named = bpmFromName(name);
        if (named > 0) return fit(frames, named, beatsPerBar, rate, true, 1);
        int hop = 256; double[] env = onsets(stereo, hop);
        if (env.length < 32) return null;
        double hopsPerSecond = rate / (double) hop, seconds = frames / (double) rate;
        // Baseline: how well a typical tempo lines up with this audio.
        double baseline = 0; int grid = 0;
        for (double t = MIN_BPM; t <= MAX_BPM; t += 2.3) { baseline += comb(env, hopsPerSecond * 60 / t); grid++; }
        baseline /= grid;
        // Loops are whole bars: the length gives one exact tempo per octave. Take the one in 75-150 BPM,
        // then the half/double-time reading nearest the project tempo.
        double exact = 0; int bars = 0;
        for (int b : new int[]{1, 2, 4, 8, 16, 32, 64}) { double t = b * beatsPerBar * 60 / seconds; if (t >= 75 && t < 150) { exact = t; bars = b; break; } }
        if (exact > 0) {
            double score = Math.max(comb(env, hopsPerSecond * 60 / exact), comb(env, hopsPerSecond * 60 / (exact / 2)));
            if (score > baseline * 1.12) {
                double chosen = nearestOctave(exact, projectBpm);
                int chosenBars = chosen > exact * 1.5 ? bars * 2 : chosen < exact / 1.5 ? Math.max(1, bars / 2) : bars;
                return new Result(chosen, chosenBars, false, Math.min(1, Math.max(0, (score / baseline - 1) / .5)));
            }
        }
        double[] d = detect(stereo, rate);
        if (d[0] <= 0) return null;
        return new Result(nearestOctave(d[0], projectBpm), 0, false, 0);
    }

    /** Frames the loop should have at the project tempo. */
    public static int targetFrames(int frames, double sourceBpm, int projectBpm) {
        return (int) Math.round(frames * sourceBpm / projectBpm);
    }

    public static String describe(Result r) {
        return String.format(Locale.US, "%.1f BPM%s%s", r.bpm, r.bars > 0 ? " · " + r.bars + (r.bars == 1 ? " bar" : " bars") : "", r.fromName ? " (from file name)" : "");
    }
}

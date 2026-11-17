package ug.audiocity.studio;

import java.util.Locale;

/** Which speaker/microphone layout leaks least beat into the mic. Pure logic, host-testable. */
public final class SpeakerChoice {
    private SpeakerChoice() {}
    public static final String[] ROUTES = {"Both speakers", "Left channel only", "Right channel only", "Top earpiece speaker"};
    public static final String[] MICS = {"Main microphone", "Camera-side microphone"};
    /** Extra dB an option must win by, because it also sounds quieter to the singer. */
    static final double[] ROUTE_PENALTY = {0, 2, 2, 5};

    public static final class Result { public final int route, mic; public final double bleedDb; public Result(int route, int mic, double bleedDb) { this.route = route; this.mic = mic; this.bleedDb = bleedDb; } }

    /** Index of the recommended result; results with NaN bleed (unsupported) are skipped. -1 when none. */
    public static int recommend(Result[] results) {
        int best = -1; double score = Double.MAX_VALUE;
        for (int i = 0; i < results.length; i++) { Result r = results[i]; if (r == null || Double.isNaN(r.bleedDb)) continue; double s = r.bleedDb + ROUTE_PENALTY[r.route]; if (s < score) { score = s; best = i; } }
        return best;
    }

    public static String describe(Result r, double baselineDb) {
        String name = ROUTES[r.route] + " + " + MICS[r.mic].toLowerCase(Locale.US);
        if (Double.isNaN(r.bleedDb)) return name + " · not supported";
        double less = baselineDb - r.bleedDb;
        return name + String.format(Locale.US, " · %s", Double.isNaN(baselineDb) ? String.format(Locale.US, "%.0f dB", r.bleedDb) : Math.abs(less) < .5 ? "same as normal" : less > 0 ? String.format(Locale.US, "%.0f dB less beat in the mic", less) : String.format(Locale.US, "%.0f dB more beat in the mic", -less));
    }

    /** Puts a stereo block onto one channel only (route 1 left, 2 right); routes 0 and 3 leave it unchanged. */
    public static void route(float[] stereo, int frames, int route) {
        if (route != 1 && route != 2) return;
        for (int i = 0; i < frames; i++) { float m = (stereo[i * 2] + stereo[i * 2 + 1]) * .5f; stereo[i * 2] = route == 1 ? m : 0; stereo[i * 2 + 1] = route == 2 ? m : 0; }
    }

    /** Power in dB of the part of a recording above its noise floor. */
    public static double bleedDb(double playPower, double noisePower) { return 10 * Math.log10(Math.max(1e-12, playPower - noisePower)); }
}

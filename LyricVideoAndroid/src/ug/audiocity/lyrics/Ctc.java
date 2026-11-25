package ug.audiocity.lyrics;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Word timing from a speech model's per-frame character probabilities (CTC). Plain Java, tested on the desktop.
 *
 * <p>{@link #align} finds when each word of the known lyrics is sung (forced alignment). Between lines, at the start
 * and at the end it allows a "something else" state, so ad-libs, intros and repeats that are not in the lyrics are
 * skipped instead of dragging the words out of place. Word gaps ("|") are optional because singers often run words
 * together. {@link #transcribe} reads the most likely words straight from the model (for writing lyrics offline).
 */
final class Ctc {
    /** The model's output symbols. */
    static final class Vocab {
        final String[] tokens;
        final int blank, delimiter;
        final HashMap<String, Integer> index = new HashMap<>();

        Vocab(String[] tokens, int blank, String delimiter) {
            this.tokens = tokens;
            this.blank = blank;
            for (int i = 0; i < tokens.length; i++) if (tokens[i] != null) index.put(tokens[i], i);
            Integer d = delimiter == null ? null : index.get(delimiter);
            this.delimiter = d == null ? -1 : d;
        }

        int size() { return tokens.length; }
    }

    /** Lower-cases, removes accents and maps apostrophes, then keeps only characters the model knows. */
    static int[] wordTokens(String word, Vocab v) {
        String w = Normalizer.normalize(word.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('’', '\'').replace('‘', '\'').replace('`', '\'');
        int[] out = new int[w.length()];
        int n = 0;
        for (int i = 0; i < w.length(); i++) {
            Integer id = v.index.get(String.valueOf(w.charAt(i)));
            if (id != null && id != v.blank && id != v.delimiter) out[n++] = id;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    static final int GARBAGE = -2;

    /** Timing of one word in frames; start &lt; 0 when it could not be placed (no known letters). */
    static final class Word {
        int start = -1, end = -1;
        float score;
    }

    /**
     * Forced alignment of lines of words to log-probabilities lp[t * V + k] for T frames.
     *
     * @param garbagePenalty how unlikely "something else is being sung" is per frame (log units); about 2.5 works well.
     * @return per line, per word timing.
     */
    static Word[][] align(float[] lp, int frames, Vocab v, List<String[]> lines, float garbagePenalty) {
        int V = v.size();
        // Build the symbol sequence with ownership information.
        ArrayList<Integer> seq = new ArrayList<>();
        ArrayList<Boolean> optional = new ArrayList<>();
        ArrayList<int[]> owner = new ArrayList<>(); // {line, word} or null-equivalent {-1,-1}
        seq.add(GARBAGE); optional.add(true); owner.add(new int[]{-1, -1});
        for (int li = 0; li < lines.size(); li++) {
            String[] words = lines.get(li);
            boolean any = false;
            for (int wi = 0; wi < words.length; wi++) {
                int[] tk = wordTokens(words[wi], v);
                if (tk.length == 0) continue;
                if (any && v.delimiter >= 0) { seq.add(v.delimiter); optional.add(true); owner.add(new int[]{-1, -1}); }
                for (int id : tk) { seq.add(id); optional.add(false); owner.add(new int[]{li, wi}); }
                any = true;
            }
            if (any) { seq.add(GARBAGE); optional.add(true); owner.add(new int[]{-1, -1}); }
        }
        Word[][] out = new Word[lines.size()][];
        for (int li = 0; li < lines.size(); li++) { out[li] = new Word[lines.get(li).length]; for (int wi = 0; wi < out[li].length; wi++) out[li][wi] = new Word(); }
        int N = seq.size(), S = 2 * N + 1;
        int firstMandatory = -1, lastMandatory = -1;
        for (int i = 0; i < N; i++) if (!optional.get(i)) { if (firstMandatory < 0) firstMandatory = i; lastMandatory = i; }
        if (firstMandatory < 0 || frames <= 0) return out;
        int[] tok = new int[S];
        boolean[] opt = new boolean[S];
        for (int s = 0; s < S; s++) { tok[s] = s % 2 == 0 ? v.blank : seq.get(s / 2); opt[s] = s % 2 == 1 && optional.get(s / 2); }

        // Emission of the "something else" state: the best non-blank symbol, minus a penalty.
        float[] garbage = new float[frames];
        for (int t = 0; t < frames; t++) {
            float best = -1e30f;
            for (int k = 0; k < V; k++) if (k != v.blank && k != v.delimiter && lp[t * V + k] > best) best = lp[t * V + k];
            garbage[t] = best - garbagePenalty;
        }

        final float NEG = -1e30f;
        float[] prev = new float[S], cur = new float[S];
        // Back-pointers: 4 bits per cell (how many states back the path came from, 0..4).
        byte[] back = new byte[(int) (((long) frames * S + 1) / 2)];
        java.util.Arrays.fill(prev, NEG);
        int startLimit = 2 * firstMandatory + 1, endFrom = 2 * lastMandatory + 1;
        for (int s = 0; s <= startLimit; s++) prev[s] = emit(lp, V, tok[s], 0, garbage);
        for (int t = 1; t < frames; t++) {
            for (int s = 0; s < S; s++) {
                float best = prev[s];
                int from = 0;
                if (s >= 1 && prev[s - 1] > best) { best = prev[s - 1]; from = 1; }
                if (s % 2 == 1) {
                    if (s >= 2 && tok[s] != tok[s - 2] && prev[s - 2] > best) { best = prev[s - 2]; from = 2; }
                    // Skip an optional symbol (word gap or "something else") just before this one.
                    if (s >= 3 && opt[s - 2] && prev[s - 3] > best) { best = prev[s - 3]; from = 3; }
                    if (s >= 4 && opt[s - 2] && tok[s] != tok[s - 4] && prev[s - 4] > best) { best = prev[s - 4]; from = 4; }
                } else if (s >= 2 && opt[s - 1] && prev[s - 2] > best) { best = prev[s - 2]; from = 2; }
                cur[s] = best <= NEG ? NEG : best + emit(lp, V, tok[s], t, garbage);
                long cell = (long) t * S + s;
                int b = back[(int) (cell >> 1)];
                back[(int) (cell >> 1)] = (byte) ((cell & 1) == 0 ? (b & 0xf0) | from : (b & 0x0f) | (from << 4));
            }
            float[] x = prev; prev = cur; cur = x;
        }
        int s = -1;
        float best = NEG;
        for (int k = endFrom; k < S; k++) if (prev[k] > best) { best = prev[k]; s = k; }
        if (s < 0) return out;
        // Trace back and record which frames each word's letters occupy.
        float[][] sum = new float[lines.size()][];
        int[][] count = new int[lines.size()][];
        for (int li = 0; li < lines.size(); li++) { sum[li] = new float[out[li].length]; count[li] = new int[out[li].length]; }
        for (int t = frames - 1; t >= 0; t--) {
            if (s % 2 == 1) {
                int[] o = owner.get(s / 2);
                if (o[0] >= 0) {
                    Word w = out[o[0]][o[1]];
                    w.start = t;
                    if (w.end < 0) w.end = t;
                    sum[o[0]][o[1]] += lp[t * V + tok[s]];
                    count[o[0]][o[1]]++;
                }
            }
            if (t == 0) break;
            long cell = (long) t * S + s;
            int b = back[(int) (cell >> 1)];
            s -= (cell & 1) == 0 ? b & 0x0f : (b >> 4) & 0x0f;
        }
        for (int li = 0; li < out.length; li++) for (int wi = 0; wi < out[li].length; wi++)
            if (count[li][wi] > 0) out[li][wi].score = (float) Math.exp(sum[li][wi] / count[li][wi]);
        return out;
    }

    static float emit(float[] lp, int V, int token, int t, float[] garbage) { return token == GARBAGE ? garbage[t] : lp[t * V + token]; }

    /** A word read from the model, with frame times. */
    static final class Heard {
        final String text;
        final int start, end;
        final float score;
        Heard(String text, int start, int end, float score) { this.text = text; this.start = start; this.end = end; this.score = score; }
    }

    /** Most likely words, frame by frame (greedy CTC decoding). */
    static ArrayList<Heard> transcribe(float[] lp, int frames, Vocab v) {
        int V = v.size();
        ArrayList<Heard> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int start = -1, end = -1, prevTok = -1;
        float sum = 0;
        int n = 0;
        for (int t = 0; t <= frames; t++) {
            int arg = v.blank;
            if (t < frames) {
                float best = -1e30f;
                for (int k = 0; k < V; k++) if (lp[t * V + k] > best) { best = lp[t * V + k]; arg = k; }
            }
            boolean boundary = t == frames || arg == v.delimiter;
            if (boundary) {
                if (word.length() > 0) out.add(new Heard(word.toString(), start, end, (float) Math.exp(sum / Math.max(1, n))));
                word.setLength(0); start = -1; sum = 0; n = 0;
            } else if (arg != v.blank && arg != prevTok) {
                String sym = v.tokens[arg];
                if (sym != null && !sym.startsWith("<") && !sym.startsWith("[")) {
                    if (word.length() == 0) start = t;
                    word.append(sym);
                    end = t;
                    sum += lp[t * V + arg];
                    n++;
                }
            } else if (arg == prevTok && arg != v.blank && word.length() > 0) end = t;
            prevTok = t < frames ? arg : -1;
        }
        return out;
    }
}

package ug.audiocity.lyrics;

import java.util.ArrayList;
import java.util.List;

/** Turns the speech model's results into line and word timings. Plain Java, tested on the desktop. */
final class AutoTiming {
    /** Confidence below which a line is flagged for checking (average letter probability). */
    static final float CHECK_BELOW = .12f;

    static final class Result {
        int lines, words, unplaced;
        /** Indices of lines the model was unsure about. */
        final ArrayList<Integer> check = new ArrayList<>();
    }

    /** Applies forced-alignment results (frame units) to the lyrics; lines the model could not place keep their old time. */
    static Result apply(Lyrics lyrics, Ctc.Word[][] aligned, int frameMs) {
        Result r = new Result();
        for (int li = 0; li < lyrics.lines.size() && li < aligned.length; li++) {
            Lyrics.Line line = lyrics.lines.get(li);
            Ctc.Word[] w = aligned[li];
            int n = w.length, placed = 0;
            float score = 0;
            for (Ctc.Word x : w) if (x.start >= 0) { placed++; score += x.score; }
            if (placed == 0) { r.unplaced++; continue; }
            long[] starts = new long[n], ends = new long[n];
            for (int k = 0; k < n; k++) { starts[k] = w[k].start >= 0 ? (long) w[k].start * frameMs : -1; ends[k] = w[k].end >= 0 ? (long) (w[k].end + 1) * frameMs : -1; }
            fillGaps(starts, ends, line.words());
            line.start = starts[0];
            line.wordTimes = starts;
            r.lines++;
            r.words += n;
            if (score / placed < CHECK_BELOW || placed < n) r.check.add(li);
        }
        lyrics.repairOrder();
        return r;
    }

    /** Words the model could not place (numbers, symbols) get times between their neighbours, by syllables. */
    static void fillGaps(long[] starts, long[] ends, String[] words) {
        int n = starts.length;
        for (int k = 0; k < n; k++) {
            if (starts[k] >= 0) continue;
            int a = k - 1, b = k;
            while (b < n && starts[b] < 0) b++;
            long from = a >= 0 ? (ends[a] >= 0 ? ends[a] : starts[a] + 200) : (b < n ? Math.max(0, starts[b] - 300L * (b - k)) : 0);
            long to = b < n ? starts[b] : from + 300L * (b - k);
            double total = 0;
            for (int j = k; j < b; j++) total += Lyrics.singWeight(words[j]);
            double acc = 0;
            for (int j = k; j < b; j++) { starts[j] = from + Math.round((to - from) * acc / total); acc += Lyrics.singWeight(words[j]); ends[j] = from + Math.round((to - from) * acc / total); }
            k = b - 1;
        }
        for (int k = 1; k < n; k++) if (starts[k] < starts[k - 1]) starts[k] = starts[k - 1];
    }

    /**
     * Builds screen lines ("blocks") from timed words, e.g. a transcript: a new line starts at a clear pause in the
     * singing, or before a line gets too long to read (then at the biggest pause available).
     */
    static Lyrics blocks(List<String> words, long[] starts, long[] ends, int maxChars, long pauseMs) {
        Lyrics out = new Lyrics();
        int i = 0, n = words.size();
        while (i < n) {
            int j = i, chars = words.get(i).length();
            while (j + 1 < n) {
                long gap = starts[j + 1] - ends[j];
                int next = chars + 1 + words.get(j + 1).length();
                if (gap >= pauseMs || next > maxChars) break;
                chars = next;
                j++;
            }
            // Too long without a clear pause: cut at the biggest pause in the second half instead of mid-phrase.
            if (j + 1 < n && starts[j + 1] - ends[j] < pauseMs && j - i >= 3) {
                int best = j;
                long bestGap = -1;
                for (int k = i + (j - i) / 2; k <= j; k++) { long g = starts[k + 1] - ends[k]; if (g > bestGap) { bestGap = g; best = k; } }
                j = best;
            }
            StringBuilder text = new StringBuilder();
            long[] times = new long[j - i + 1];
            for (int k = i; k <= j; k++) { if (k > i) text.append(' '); text.append(words.get(k)); times[k - i] = starts[k]; }
            Lyrics.Line line = new Lyrics.Line(text.toString(), starts[i]);
            line.wordTimes = times;
            out.lines.add(line);
            i = j + 1;
        }
        return out;
    }

    /** All words of a text, in order (for aligning a transcript that has no line breaks yet). */
    static List<String[]> asOneLine(String text) {
        ArrayList<String[]> l = new ArrayList<>();
        String t = Lyrics.clean(text.replace('\n', ' '));
        if (!t.isEmpty()) l.add(t.split(" "));
        return l;
    }
}

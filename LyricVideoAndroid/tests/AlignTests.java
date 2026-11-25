package ug.audiocity.lyrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Desktop checks for the word aligner and the 16 kHz converter (no phone or model needed). */
public final class AlignTests {
    static int checks;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }

    // A small Luganda-style vocabulary like the real model's: blank, word gap, letters, apostrophe.
    static final String[] TOKENS = {"<pad>", "<s>", "</s>", "<unk>", "|", "'", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "v", "w", "y", "z", "ŋ"};
    static final Ctc.Vocab V = new Ctc.Vocab(TOKENS, 0, "|");

    /** Builds log-probabilities for a planted "song": each entry is {symbol or null for blank/noise, frames}. */
    static float[] plant(List<Object[]> parts, int[] totalFrames, Random rnd, boolean noisy) {
        int T = 0;
        for (Object[] p : parts) T += (Integer) p[1];
        int n = V.size();
        float[] lp = new float[T * n];
        int t = 0;
        for (Object[] p : parts) {
            for (int f = 0; f < (Integer) p[1]; f++, t++) {
                float[] logits = new float[n];
                for (int k = 0; k < n; k++) logits[k] = (float) (rnd.nextGaussian() * (noisy ? 1.2 : .3));
                String sym = (String) p[0];
                if (sym == null) logits[0] += 6;
                else if (sym.equals("?")) logits[6 + rnd.nextInt(n - 6)] += 6; // singing that is not in the lyrics
                else logits[V.index.get(sym)] += noisy ? 4 : 7;
                float max = Float.NEGATIVE_INFINITY, sum = 0;
                for (float x : logits) max = Math.max(max, x);
                for (float x : logits) sum += Math.exp(x - max);
                for (int k = 0; k < n; k++) lp[t * n + k] = (float) (logits[k] - max - Math.log(sum));
            }
        }
        totalFrames[0] = T;
        return lp;
    }

    /** Sings a word: each letter for a few frames with blanks between some, returns the start frame. */
    static int sing(List<Object[]> parts, String word, int[] clock, Random rnd) {
        int start = clock[0];
        for (char ch : word.toCharArray()) {
            int d = 2 + rnd.nextInt(5);
            parts.add(new Object[]{String.valueOf(ch), d}); clock[0] += d;
            if (rnd.nextBoolean()) { int b = 1 + rnd.nextInt(3); parts.add(new Object[]{null, b}); clock[0] += b; }
        }
        return start;
    }

    static void gap(List<Object[]> parts, String sym, int frames, int[] clock) { parts.add(new Object[]{sym, frames}); clock[0] += frames; }

    public static void main(String[] args) {
        tokens(); planted(false); planted(true); transcribe(); resample(); applyAndBlocks();
        System.out.println("AlignTests: " + checks + " checks passed");
    }

    static void tokens() {
        check(Arrays.equals(Ctc.wordTokens("Nkwagala", V), Ctc.wordTokens("nkwagala", V)), "case-insensitive");
        check(Ctc.wordTokens("ng’oma", V).length == 6, "curly apostrophe mapped (ng'oma)");
        check(Ctc.wordTokens("Kàmpálá", V).length == 7, "accents removed");
        check(Ctc.wordTokens("2024!", V).length == 0, "digits and punctuation skipped");
    }

    static void planted(boolean noisy) {
        Random rnd = new Random(noisy ? 7 : 3);
        String[][] lyrics = {{"nkwagala", "nnyo"}, {"tuli", "wamu", "bulijjo"}, {"mukwano", "gwange"}};
        List<Object[]> parts = new ArrayList<>();
        int[] clock = {0};
        int[][] truth = new int[3][];
        gap(parts, null, 150, clock);           // intro (instrumental)
        gap(parts, "?", 40, clock);             // an ad-lib that is not in the lyrics
        gap(parts, null, 20, clock);
        for (int li = 0; li < lyrics.length; li++) {
            truth[li] = new int[lyrics[li].length];
            for (int wi = 0; wi < lyrics[li].length; wi++) {
                truth[li][wi] = sing(parts, lyrics[li][wi], clock, rnd);
                if (wi < lyrics[li].length - 1) { if (rnd.nextBoolean()) gap(parts, "|", 2, clock); gap(parts, null, 3 + rnd.nextInt(6), clock); }
            }
            gap(parts, null, 60, clock);
            if (li == 1) { gap(parts, "?", 50, clock); gap(parts, null, 30, clock); } // backing vocals between lines
        }
        gap(parts, null, 120, clock);           // outro
        int[] T = new int[1];
        float[] lp = plant(parts, T, rnd, noisy);
        List<String[]> lines = new ArrayList<>(Arrays.asList(lyrics));
        Ctc.Word[][] got = Ctc.align(lp, T[0], V, lines, 2.5f);
        int worst = 0;
        for (int li = 0; li < lyrics.length; li++) for (int wi = 0; wi < lyrics[li].length; wi++) {
            Ctc.Word w = got[li][wi];
            check(w.start >= 0, "word placed " + lyrics[li][wi]);
            worst = Math.max(worst, Math.abs(w.start - truth[li][wi]));
            check(w.end >= w.start, "end after start");
            check(w.score > 0 && w.score <= 1, "score in range");
        }
        check(worst <= 2, (noisy ? "noisy" : "clean") + " planted song: every word within 2 frames (40 ms), worst " + worst);
    }

    static void transcribe() {
        Random rnd = new Random(11);
        List<Object[]> parts = new ArrayList<>();
        int[] clock = {0};
        gap(parts, null, 30, clock);
        int a = sing(parts, "tuli", clock, rnd);
        gap(parts, "|", 3, clock);
        gap(parts, null, 4, clock);
        int b = sing(parts, "wamu", clock, rnd);
        gap(parts, null, 30, clock);
        int[] T = new int[1];
        float[] lp = plant(parts, T, rnd, false);
        ArrayList<Ctc.Heard> heard = Ctc.transcribe(lp, T[0], V);
        check(heard.size() == 2, "two words heard: " + heard.size());
        check(heard.get(0).text.equals("tuli") && heard.get(1).text.equals("wamu"), "words read correctly: " + heard.get(0).text + " " + heard.get(1).text);
        check(heard.get(0).start == a && heard.get(1).start == b, "word start frames");
    }

    static void resample() {
        for (int rate : new int[]{44100, 48000, 22050, 16000}) {
            float pass = tone(rate, 1000), stop = rate > 20000 ? tone(rate, 10000) : 0, bass = tone(rate, 40);
            check(Math.abs(pass - 1) < .05, rate + " Hz: 1 kHz voice-band tone kept, gain " + pass);
            if (rate > 20000) check(stop < .03, rate + " Hz: 10 kHz above the new Nyquist removed, gain " + stop);
            check(bass < .5, rate + " Hz: 40 Hz kick/bass reduced, gain " + bass);
        }
        Audio16k a = new Audio16k(44100, 2);
        short[] block = new short[2 * 4410];
        for (int i = 0; i < 10; i++) a.add(block, 4410);
        int n = a.finish().length;
        check(Math.abs(n - 16000) <= 2, "1 s of 44.1 kHz stereo becomes 16000 samples: " + n);
    }

    static void applyAndBlocks() {
        Lyrics l = Lyrics.parse("Nkwagala nnyo 2x\nTuli wamu", null);
        Ctc.Word[][] a = new Ctc.Word[2][];
        a[0] = new Ctc.Word[]{w(100, 130, .9f), w(140, 150, .8f), w(-1, -1, 0)};
        a[1] = new Ctc.Word[]{w(300, 310, .05f), w(320, 340, .04f)};
        AutoTiming.Result r = AutoTiming.apply(l, a, 20);
        check(l.lines.get(0).start == 2000 && l.lines.get(0).wordTimed(), "line start = first word (frame 100 = 2.000 s)");
        check(l.lines.get(0).wordTimes[2] > l.lines.get(0).wordTimes[1], "unplaced word (2x) placed after its neighbour");
        check(r.check.contains(0) && r.check.contains(1) && r.lines == 2, "unsure lines flagged for checking");
        List<String> words = Arrays.asList("Nkwagala", "nnyo", "mukwano", "gwange", "tuli", "wamu", "bulijjo", "naawe");
        long[] st = {0, 600, 1000, 1500, 4000, 4400, 4800, 5500};
        long[] en = {500, 900, 1400, 2000, 4300, 4700, 5300, 6000};
        Lyrics b = AutoTiming.blocks(words, st, en, 42, 700);
        check(b.lines.size() == 2, "split into two blocks at the 2-second pause: " + b.lines.size());
        check(b.lines.get(1).text.equals("tuli wamu bulijjo naawe") && b.lines.get(1).start == 4000, "second block starts at its first word");
        check(b.lines.get(1).wordTimed() && b.lines.get(1).wordTimes[2] == 4800, "blocks keep word times");
        Lyrics c = AutoTiming.blocks(words, st, en, 20, 5000);
        for (Lyrics.Line x : c.lines) check(x.text.length() <= 20, "blocks respect the length limit: " + x.text);
    }

    static Ctc.Word w(int s, int e, float score) { Ctc.Word x = new Ctc.Word(); x.start = s; x.end = e; x.score = score; return x; }

    /** Gain of a sine through the converter (RMS out / RMS in), measured away from the edges. */
    static float tone(int rate, double hz) {
        Audio16k a = new Audio16k(rate, 1);
        int n = rate * 2;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (float) (.5 * Math.sin(2 * Math.PI * hz * i / rate));
        for (int i = 0; i < n; i += 1000) a.addMono(Arrays.copyOfRange(x, i, Math.min(n, i + 1000)), Math.min(1000, n - i));
        float[] y = a.finish();
        double s = 0;
        int from = y.length / 4, to = 3 * y.length / 4;
        for (int i = from; i < to; i++) s += y[i] * y[i];
        return (float) (Math.sqrt(s / (to - from)) / (.5 / Math.sqrt(2)));
    }
}

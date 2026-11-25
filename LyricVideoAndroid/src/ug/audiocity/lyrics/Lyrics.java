package ug.audiocity.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyric lines with start times (milliseconds), LRC import/export and the timing maths the renderer uses.
 * Plain Java with no Android dependencies so it can be tested on a desktop JVM.
 */
public final class Lyrics {

    public static final class Line {
        public String text;
        /** Start time in ms, or -1 while the line has not been synced. */
        public long start;
        /** Start of each word in ms (same order as {@link #words()}), from the AI aligner or a word-timed LRC; null when not word-timed. */
        public long[] wordTimes;
        /** When the singing of this line ends (ms), from "hold while sung" or an LRC end mark; -1 when unknown. */
        public long end = -1;
        public Line(String text, long start) { this.text = text; this.start = start; }
        public String[] words() { return text.split(" "); }
        /** True when every word has its own time. */
        public boolean wordTimed() { return wordTimes != null && wordTimes.length == words().length && start >= 0; }
        Line copy() { Line l = new Line(text, start); l.wordTimes = wordTimes == null ? null : wordTimes.clone(); l.end = end; return l; }
    }

    /** What the renderer needs to know about one moment of the song. */
    public static final class Frame {
        /** Line on screen, or -1 for none (before the first line, or during an instrumental gap). */
        public int index = -1;
        /** Line that was on screen before this one (for scrolling transitions), or -1. */
        public int previous = -1;
        /** Next line that will appear, or -1. */
        public int next = -1;
        /** 0..1 progress of the sung part of the line, by characters (karaoke wipe, typewriter). */
        public float wipe;
        /** Position in the line by words: 2.5 means half-way through the third word (word pop, highlighter, neon). */
        public float wordPos;
        /** Number of words in the current line. */
        public int words;
        /** 0..1 fade-in over the first moments of the line. */
        public float in;
        /** 0..1 fade-out before a gap (stays 0 when the next line follows straight on). */
        public float out;
        /** 0..1 progress of the change from the previous line to this one. */
        public float change = 1;
        /** Milliseconds until the first line starts (for the title card), or -1 once lyrics have begun. */
        public long untilFirst = -1;
    }

    public static final long FADE_MS = 320;
    /** A line is held at most this long when the next line is far away (an instrumental break). */
    public static final long MAX_HOLD_MS = 7000;

    public final ArrayList<Line> lines = new ArrayList<>();

    // ------------------------------------------------------------------ text

    /** Plain text, one line per row. Blank rows are skipped. LRC text keeps its timings. */
    public static Lyrics parse(String text, String[] meta) {
        if (looksLikeLrc(text)) return parseLrc(text, meta);
        if (text != null && text.startsWith("﻿")) text = text.substring(1);
        Lyrics l = new Lyrics();
        for (String row : split(text)) { String t = clean(row); if (!t.isEmpty()) l.lines.add(new Line(t, -1)); }
        return l;
    }

    static String[] split(String text) { return text == null ? new String[0] : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1); }
    static String clean(String s) { return s.replace('\t', ' ').replaceAll("\\s+", " ").trim(); }

    /** Text for the lyrics editor, one line per row. */
    public String toText() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) { if (i > 0) b.append('\n'); b.append(lines.get(i).text); }
        return b.toString();
    }

    /**
     * Replaces the words with edited text. Timings stay with lines whose text is unchanged, so fixing a typo or
     * inserting a line does not undo the sync of the rest of the song.
     */
    public void setText(String text) {
        ArrayList<Line> old = new ArrayList<>(lines);
        ArrayList<Line> fresh = new ArrayList<>();
        for (String row : split(text)) { String t = clean(row); if (!t.isEmpty()) fresh.add(new Line(t, -1)); }
        // Longest common subsequence on the text keeps the times of matching lines.
        int n = old.size(), m = fresh.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
            lcs[i][j] = old.get(i).text.equals(fresh.get(j).text) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        boolean[] matched = new boolean[m];
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (old.get(i).text.equals(fresh.get(j).text)) { fresh.set(j, old.get(i).copy()); matched[j] = true; i++; j++; }
            else if (lcs[i + 1][j] >= lcs[i][j + 1]) i++; else j++;
        }
        // An edited line that replaces exactly one old line at the same place keeps its old time.
        if (n == m) for (int k = 0; k < m; k++) if (!matched[k]) fresh.get(k).start = old.get(k).start;
        lines.clear(); lines.addAll(fresh);
        repairOrder();
    }

    // ------------------------------------------------------------------ time stamps in the lyrics text

    /** A time stamp at the start of a row: "[0:15.20] words", "[00:15] words" or "0:15 words". */
    static final Pattern LEAD_STAMP = Pattern.compile("^\\s*(?:\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]|(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?(?=\\s))\\s*");

    /** "[m:ss.cc]" as shown in the lyrics box. */
    public static String boxStamp(long ms) {
        long cs = (Math.max(0, ms) + 5) / 10;
        return String.format(Locale.ROOT, "[%d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100);
    }

    /** Text for the lyrics box: synced lines start with their time stamp, so people can see and edit where each line begins. */
    public String toStampedText() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) b.append('\n');
            Line l = lines.get(i);
            if (l.start >= 0) b.append(boxStamp(l.start)).append(' ');
            b.append(l.text);
        }
        return b.toString();
    }

    /** Start time from a leading stamp of a row, or -1; out[0] receives the rest of the row. */
    static long leadStamp(String row, String[] rest) {
        Matcher m = LEAD_STAMP.matcher(row);
        if (!m.lookingAt()) { rest[0] = row; return -1; }
        boolean br = m.group(1) != null;
        long ms = Long.parseLong(br ? m.group(1) : m.group(4)) * 60000 + Long.parseLong(br ? m.group(2) : m.group(5)) * 1000;
        String frac = br ? m.group(3) : m.group(6);
        if (frac != null) { long f = Long.parseLong(frac); for (int i = frac.length(); i < 3; i++) f *= 10; ms += f; }
        rest[0] = row.substring(m.end());
        return ms;
    }

    /**
     * Reads the lyrics box with time stamps: a stamped row starts at its stamp, a row without one is not placed yet, a
     * row with only a stamp marks where the previous line ends. Word timings stay with lines whose words are unchanged
     * (moved along if their stamp was changed).
     */
    public void setStampedText(String text) {
        ArrayList<Line> old = new ArrayList<>(lines);
        ArrayList<Line> fresh = new ArrayList<>();
        ArrayList<Long> stamps = new ArrayList<>();
        String[] rest = new String[1];
        for (String row : split(text)) {
            long t = leadStamp(row, rest);
            String words = clean(rest[0]);
            if (words.isEmpty()) {
                if (t >= 0 && !fresh.isEmpty()) { Line prev = fresh.get(fresh.size() - 1); prev.end = t; }
                continue;
            }
            fresh.add(new Line(words, -1));
            stamps.add(t);
        }
        int n = old.size(), m = fresh.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
            lcs[i][j] = old.get(i).text.equals(fresh.get(j).text) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (old.get(i).text.equals(fresh.get(j).text)) {
                long endMark = fresh.get(j).end;
                Line kept = old.get(i).copy();
                if (endMark >= 0) kept.end = endMark;
                fresh.set(j, kept);
                i++; j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) i++; else j++;
        }
        for (int k = 0; k < m; k++) {
            Line l = fresh.get(k);
            long t = stamps.get(k);
            if (t < 0) { l.start = -1; l.wordTimes = null; l.end = -1; continue; }
            if (l.start >= 0 && Math.abs(l.start - t) <= 15) continue; // unchanged (the box shows centiseconds)
            if (l.start >= 0) moveLine(l, t); else { l.start = t; l.wordTimes = null; }
        }
        lines.clear();
        lines.addAll(fresh);
        repairOrder();
    }

    /** True when text is a full LRC file (meta tags, word tags or repeated stamps) rather than the lyrics box's own stamps. */
    public static boolean isFullLrc(String text) {
        if (text == null) return false;
        for (String row : split(text)) {
            String r = row.trim();
            if (WORD_TIME.matcher(r).find()) return true;
            if (META.matcher(r).matches() && !TIME.matcher(r).lookingAt()) return true;
            Matcher t = TIME.matcher(r);
            if (t.lookingAt() && t.find(t.end()) && t.start() == r.indexOf(']') + 1) return true;
        }
        return false;
    }

    /** Un-syncs any line whose time is earlier than a synced line before it. */
    public void repairOrder() {
        long last = -1;
        for (Line l : lines) {
            if (l.start < 0) { l.wordTimes = null; l.end = -1; continue; }
            if (l.start < last) { l.start = -1; l.wordTimes = null; l.end = -1; } else last = l.start;
            if (l.end >= 0 && l.end <= l.start) l.end = -1;
            if (l.wordTimes != null && !l.wordTimed()) l.wordTimes = null;
        }
    }

    // ------------------------------------------------------------------ typing helpers

    /** A whole row that is only a section label: "Chorus", "Verse 2:", "(Hook x2)", "x3". Real lyrics that merely start with such a word are kept. */
    static final Pattern SECTION = Pattern.compile("^[\\[(]?\\s*(?:(?:intro|outro|verse|chorus|pre-?chorus|post-?chorus|hook|bridge|refrain|interlude|instrumental|break|breakdown|drop|ad-?libs?|repeat|solo|coda)(?:\\s*\\d+)?(?:\\s*[x\u00d7]\\s*\\d+)?|[x\u00d7]\\s*\\d+|\\d+\\s*[x\u00d7])\\s*[\\])]?\\s*:?\\s*$", Pattern.CASE_INSENSITIVE);
    public static final int LONG_LINE = 42;

    /**
     * Tidies pasted lyrics: removes section labels ([Chorus], Verse 2:), LRC/word timestamps and stray symbols, joins
     * spaces, and splits rows that are too long to read on screen at a natural break (punctuation, then the middle).
     */
    public static String cleanUp(String text, int maxChars) {
        StringBuilder b = new StringBuilder();
        for (String raw0 : split(text)) {
            String[] rest = new String[1];
            long stamp = leadStamp(raw0, rest);
            String raw = stamp >= 0 ? rest[0] : raw0;
            String row = TIME.matcher(raw).replaceAll("");
            row = WORD_TIME.matcher(row).replaceAll("");
            if (META.matcher(raw.trim()).matches() && !TIME.matcher(raw.trim()).lookingAt()) continue;
            row = row.replace(' ', ' ').replaceAll("[\\u200b\\u200e\\u200f*_#~|]", "");
            row = clean(row);
            if (row.isEmpty() || SECTION.matcher(row).matches() || row.matches("^\\[.*\\]$")) continue; // [Verse 1: Artist]
            boolean first = true;
            for (String part : splitLong(row, maxChars)) {
                if (b.length() > 0) b.append('\n');
                if (first && stamp >= 0) b.append(boxStamp(stamp)).append(' '); // a line's time stays with its first part
                b.append(part);
                first = false;
            }
        }
        return b.toString();
    }

    /** Splits a row longer than maxChars at the best break near its middle (recursively). */
    static ArrayList<String> splitLong(String row, int maxChars) {
        ArrayList<String> out = new ArrayList<>();
        if (row.length() <= maxChars || row.indexOf(' ') < 0) { out.add(row); return out; }
        int mid = row.length() / 2, best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 1; i < row.length() - 1; i++) {
            if (row.charAt(i) != ' ') continue;
            char prev = row.charAt(i - 1);
            // Prefer breaking after punctuation, as long as both halves then fit on screen.
            boolean fits = Math.max(i, row.length() - i - 1) <= maxChars;
            double score = Math.abs(i - mid) - (fits && ",;:.!?—–-".indexOf(prev) >= 0 ? row.length() * .3 : 0);
            if (score < bestScore) { bestScore = score; best = i; }
        }
        out.addAll(splitLong(row.substring(0, best).trim(), maxChars));
        out.addAll(splitLong(row.substring(best + 1).trim(), maxChars));
        return out;
    }

    /** True when {@link #cleanUp} would change the text, so the app can suggest it. */
    public static boolean needsCleanUp(String text) {
        String c = cleanUp(text, LONG_LINE);
        StringBuilder plain = new StringBuilder();
        for (String row : split(text)) { String t = clean(row); if (!t.isEmpty()) { if (plain.length() > 0) plain.append('\n'); plain.append(t); } }
        return !c.equals(plain.toString());
    }

    /**
     * Quick sync: gives every unsynced line a time, spreading lines between the synced lines around them (or between
     * start and end) in proportion to their length. Returns how many lines were timed.
     */
    public int spread(long start, long end) {
        int timed = 0, n = lines.size(), i = 0;
        while (i < n) {
            if (lines.get(i).start >= 0) { i++; continue; }
            int a = i;
            while (i < n && lines.get(i).start < 0) i++;
            int b = i - 1; // unsynced run a..b
            long right = i < n ? lines.get(i).start : end;
            int from = a > 0 ? a - 1 : a; // a synced line before the run takes its own share of the time
            long left = a > 0 ? lines.get(a - 1).start : Math.min(start, right);
            if (right <= left) right = left + 1500L * (b - a + 1);
            double total = 0;
            for (int k = from; k <= b; k++) total += weight(lines.get(k).text);
            double acc = 0;
            for (int k = from; k <= b; k++) {
                if (k >= a) { lines.get(k).start = left + Math.round((right - left) * acc / total); timed++; }
                acc += weight(lines.get(k).text);
            }
        }
        repairOrder();
        return timed;
    }

    static double weight(String text) { return 10 + text.length(); }

    // ------------------------------------------------------------------ LRC

    static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    static final Pattern META = Pattern.compile("^\\s*\\[([a-zA-Z#]+):(.*)\\]\\s*$");
    static final Pattern WORD_TIME = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

    public static boolean looksLikeLrc(String text) {
        if (text == null) return false;
        for (String row : split(text)) if (TIME.matcher(row.trim()).lookingAt()) return true;
        return false;
    }

    /** Parses LRC, including repeated tags ([00:12.00][01:40.00]chorus), [offset:] and enhanced word tags. meta[0]=title, meta[1]=artist. */
    public static Lyrics parseLrc(String text, String[] meta) {
        Lyrics l = new Lyrics();
        long offset = 0;
        for (String raw : split(text)) {
            String row = raw.trim();
            Matcher mm = META.matcher(row);
            if (mm.matches() && !TIME.matcher(row).lookingAt()) {
                String key = mm.group(1).toLowerCase(Locale.ROOT), value = mm.group(2).trim();
                if (key.equals("offset")) { try { offset = Long.parseLong(value.replace("+", "")); } catch (NumberFormatException ignored) {} }
                else if (meta != null && key.equals("ti") && meta.length > 0) meta[0] = value;
                else if (meta != null && key.equals("ar") && meta.length > 1) meta[1] = value;
                continue;
            }
            ArrayList<Long> times = new ArrayList<>();
            Matcher m = TIME.matcher(row);
            int end = 0;
            while (m.find() && m.start() == end) { times.add(time(m)); end = m.end(); }
            String body = row.substring(end);
            String words = clean(WORD_TIME.matcher(body).replaceAll(""));
            if (times.isEmpty()) { if (!words.isEmpty()) l.lines.add(new Line(words, -1)); continue; }
            if (words.isEmpty()) { // an empty timed row marks the end of the previous line
                if (!l.lines.isEmpty()) { Line prev = l.lines.get(l.lines.size() - 1); if (prev.start >= 0 && times.get(0) > prev.start) prev.end = times.get(0); }
                continue;
            }
            long[] wt = wordTags(body, words);
            for (long t : times) {
                Line line = new Line(words, t);
                // Word tags belong to the first time of a repeated row; later repeats are shifted by the same amount.
                if (wt != null) { line.wordTimes = new long[wt.length]; for (int k = 0; k < wt.length; k++) line.wordTimes[k] = wt[k] - times.get(0) + t; }
                l.lines.add(line);
            }
        }
        // LRC offset: positive values make lyrics appear sooner.
        for (Line x : l.lines) if (x.start >= 0) {
            x.start = Math.max(0, x.start - offset);
            if (x.end >= 0) x.end = Math.max(x.start + 1, x.end - offset);
            if (x.wordTimes != null) for (int k = 0; k < x.wordTimes.length; k++) x.wordTimes[k] = Math.max(x.start, x.wordTimes[k] - offset);
        }
        l.sortByTime();
        return l;
    }

    static final Pattern WORD_TAG = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");

    /** Times from enhanced-LRC word tags ("<00:12.30>word"), or null unless every word has one. */
    static long[] wordTags(String body, String words) {
        String[] w = words.split(" ");
        long[] out = new long[w.length];
        int k = 0;
        Matcher m = WORD_TAG.matcher(body);
        while (m.find()) {
            String after = body.substring(m.end());
            Matcher next = WORD_TAG.matcher(after);
            String chunk = clean(next.find() ? after.substring(0, next.start()) : after);
            if (chunk.isEmpty()) continue;
            long t = time(m);
            for (String piece : chunk.split(" ")) { if (k >= w.length) return null; out[k++] = t; }
        }
        if (k != w.length) return null;
        for (int i = 1; i < out.length; i++) if (out[i] < out[i - 1]) return null;
        return out;
    }

    static long time(Matcher m) {
        long ms = Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000;
        String frac = m.group(3);
        if (frac != null) { long f = Long.parseLong(frac); for (int i = frac.length(); i < 3; i++) f *= 10; ms += f; }
        return ms;
    }

    public String toLrc(String title, String artist) {
        StringBuilder b = new StringBuilder();
        if (title != null && !title.isEmpty()) b.append("[ti:").append(title).append("]\n");
        if (artist != null && !artist.isEmpty()) b.append("[ar:").append(artist).append("]\n");
        b.append("[re:Pearl Lyrics Video Maker by Nico Pan Beats]\n");
        for (Line l : lines) {
            if (l.start >= 0) b.append('[').append(stamp(l.start)).append(']');
            if (l.wordTimed()) {
                // Enhanced LRC (word-by-word): [mm:ss.xx]<mm:ss.xx>word <mm:ss.xx>word …
                String[] w = l.words();
                for (int k = 0; k < w.length; k++) { if (k > 0) b.append(' '); b.append('<').append(stamp(l.wordTimes[k])).append('>').append(w[k]); }
            } else b.append(l.text);
            b.append('\n');
            // End mark (empty timed row) when the line ends well before the next one: keeps breaks in LRC players.
            if (l.start >= 0 && l.end > l.start) {
                long next = -1;
                for (int k = lines.indexOf(l) + 1; k < lines.size(); k++) if (lines.get(k).start >= 0) { next = lines.get(k).start; break; }
                if (next < 0 || next - l.end > 1000) b.append('[').append(stamp(l.end)).append("]\n");
            }
        }
        return b.toString();
    }

    /** mm:ss.cc as used by LRC. */
    public static String stamp(long ms) {
        ms = Math.max(0, ms);
        long cs = (ms + 5) / 10;
        return String.format(Locale.ROOT, "%02d:%02d.%02d", cs / 6000, (cs / 100) % 60, cs % 100);
    }

    /** Parses "m:ss", "mm:ss.cc" or plain seconds; returns -1 when the text is not a time. */
    public static long parseStamp(String s) {
        if (s == null) return -1;
        s = s.trim();
        Matcher m = Pattern.compile("^(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?$").matcher(s);
        if (m.matches()) return time(m);
        try { double sec = Double.parseDouble(s); return sec < 0 ? -1 : Math.round(sec * 1000); } catch (NumberFormatException e) { return -1; }
    }

    // ------------------------------------------------------------------ editing helpers

    public void sortByTime() {
        // Stable: synced lines in time order, unsynced lines keep their place after the line before them.
        ArrayList<Line> synced = new ArrayList<>();
        for (Line l : lines) if (l.start >= 0) synced.add(l);
        boolean sorted = true;
        for (int i = 1; i < synced.size(); i++) if (synced.get(i).start < synced.get(i - 1).start) sorted = false;
        if (sorted) return;
        Collections.sort(synced, (a, b) -> Long.compare(a.start, b.start));
        int k = 0;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).start >= 0) lines.set(i, synced.get(k++));
    }

    public int syncedCount() { int n = 0; for (Line l : lines) if (l.start >= 0) n++; return n; }
    public boolean fullySynced() { return !lines.isEmpty() && syncedCount() == lines.size(); }
    /** First line without a time, or -1. */
    public int nextUnsynced() { for (int i = 0; i < lines.size(); i++) if (lines.get(i).start < 0) return i; return -1; }
    public void clearTimes() { for (Line l : lines) { l.start = -1; l.wordTimes = null; l.end = -1; } }
    public int wordTimedCount() { int n = 0; for (Line l : lines) if (l.wordTimed()) n++; return n; }
    public void shift(long ms) {
        for (Line l : lines) if (l.start >= 0) {
            long d = Math.max(0, l.start + ms) - l.start;
            l.start += d;
            if (l.end >= 0) l.end += d;
            if (l.wordTimes != null) for (int k = 0; k < l.wordTimes.length; k++) l.wordTimes[k] = Math.max(0, l.wordTimes[k] + d);
        }
    }

    /** Moves a line to a new start time, carrying its word times along. */
    public static void moveLine(Line l, long start) {
        if (l.start >= 0 && start >= 0) {
            if (l.wordTimes != null) for (int k = 0; k < l.wordTimes.length; k++) l.wordTimes[k] += start - l.start;
            if (l.end >= 0) l.end += start - l.start;
        } else if (start < 0) { l.wordTimes = null; l.end = -1; }
        l.start = start;
    }

    // ------------------------------------------------------------------ clip tools (timeline)

    /** Word start times for a placed line: its own word times, or an estimate spread by syllables. */
    long[] wordStarts(int i, long songEnd) {
        Line l = lines.get(i);
        if (l.wordTimed()) return l.wordTimes.clone();
        long end = l.end > l.start ? l.end : l.start + singFor(l.text, Math.max(1, endOf(i, songEnd) - l.start));
        return estimateWords(l.words(), l.start, end);
    }

    /** Splits line i before word k (1 ≤ k < words); both parts keep their timing. Returns false if it cannot split. */
    public boolean splitAtWord(int i, int k, long songEnd) {
        Line l = lines.get(i);
        String[] w = l.words();
        if (k < 1 || k >= w.length) return false;
        Line a = new Line(join(w, 0, k), l.start), b = new Line(join(w, k, w.length), -1);
        if (l.start >= 0) {
            long[] st = wordStarts(i, songEnd);
            b.start = st[k];
            a.end = Math.max(st[k - 1] + 150, st[k] - 40);
            b.end = l.end;
            if (l.wordTimed()) { a.wordTimes = java.util.Arrays.copyOfRange(st, 0, k); b.wordTimes = java.util.Arrays.copyOfRange(st, k, w.length); }
        }
        lines.set(i, a);
        lines.add(i + 1, b);
        return true;
    }

    /** Splits line i at time t (between the words around t), or at its middle word when t is outside it. */
    public boolean splitAt(int i, long t, long songEnd) {
        Line l = lines.get(i);
        String[] w = l.words();
        if (w.length < 2) return false;
        int k = w.length / 2;
        if (l.start >= 0 && t > l.start) {
            long[] st = wordStarts(i, songEnd);
            int best = -1;
            for (int j = 1; j < st.length; j++) if (best < 0 || Math.abs(st[j] - t) < Math.abs(st[best] - t)) best = j;
            if (best > 0 && t < endOf(i, songEnd)) k = best;
        }
        return splitAtWord(i, k, songEnd);
    }

    /** Joins line i with the next line into one line. */
    public boolean joinWithNext(int i, long songEnd) {
        if (i < 0 || i + 1 >= lines.size()) return false;
        Line a = lines.get(i), b = lines.get(i + 1);
        Line j = new Line(a.text + " " + b.text, a.start >= 0 ? a.start : b.start);
        if (a.start >= 0 && b.start >= 0) {
            long[] sa = wordStarts(i, songEnd), sb = wordStarts(i + 1, songEnd);
            if (a.wordTimed() || b.wordTimed()) {
                j.wordTimes = new long[sa.length + sb.length];
                System.arraycopy(sa, 0, j.wordTimes, 0, sa.length);
                System.arraycopy(sb, 0, j.wordTimes, sa.length, sb.length);
            }
            j.end = b.end > b.start ? b.end : -1;
        } else if (b.start >= 0) j.end = b.end;
        lines.set(i, j);
        lines.remove(i + 1);
        repairOrder();
        return true;
    }

    /** Changes where line i ends by delta ms (at least 0.3 s long, not past the next line's start). */
    public void changeEnd(int i, long delta, long songEnd) {
        Line l = lines.get(i);
        if (l.start < 0) return;
        long cur = l.end > l.start ? l.end : endOf(i, songEnd);
        long limit = nextStart(i) >= 0 ? nextStart(i) - 50 : (songEnd > 0 ? songEnd : Long.MAX_VALUE);
        long e = Math.max(l.start + 300, Math.min(limit, cur + delta));
        if (l.wordTimed()) {
            long last = l.wordTimes[l.wordTimes.length - 1];
            if (e <= last + 100) e = last + 100;
        }
        l.end = e;
    }

    /** Moves line i by delta ms, staying between its neighbours. */
    public void nudge(int i, long delta) {
        Line l = lines.get(i);
        if (l.start < 0) return;
        long prev = -1, next = -1;
        for (int k = i - 1; k >= 0; k--) if (lines.get(k).start >= 0) { prev = lines.get(k).start; break; }
        next = nextStart(i);
        long s = l.start + delta;
        if (prev >= 0) s = Math.max(prev + 100, s);
        if (next >= 0) s = Math.min(next - 100 - Math.max(0, (l.end > l.start ? l.end : l.start) - l.start), s);
        s = Math.max(0, s);
        moveLine(l, s);
    }

    /** Copies line i to time t (e.g. a repeated chorus line), inserted where it belongs in time. Returns its index. */
    public int copyTo(int i, long t) {
        Line src = lines.get(i), c = src.copy();
        long len = src.start >= 0 && src.end > src.start ? src.end - src.start : -1;
        if (src.start >= 0) moveLine(c, t); else c.start = t;
        if (len > 0) c.end = t + len;
        int at = lines.size();
        for (int k = 0; k < lines.size(); k++) if (lines.get(k).start > t) { at = k; break; }
        // Keep unplaced lines that come before the insertion point in their place.
        lines.add(at, c);
        repairOrder();
        return at;
    }

    /** Splits every line longer than maxChars into readable parts, keeping their timing. Returns how many lines were split. */
    public int splitLongLines(int maxChars, long songEnd) {
        int count = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.text.length() <= maxChars || l.words().length < 2) continue;
            ArrayList<String> parts = splitLong(l.text, maxChars);
            if (parts.size() < 2) continue;
            int k = parts.get(0).split(" ").length;
            if (splitAtWord(i, k, songEnd)) count++;
            i--; // the second part may still be long
        }
        return count;
    }

    static String join(String[] w, int from, int to) {
        StringBuilder b = new StringBuilder();
        for (int k = from; k < to; k++) { if (k > from) b.append(' '); b.append(w[k]); }
        return b.toString();
    }

    /** Deep copy of all lines (for undo/redo). */
    public ArrayList<Line> snapshot() { ArrayList<Line> c = new ArrayList<>(); for (Line l : lines) c.add(l.copy()); return c; }
    public void restore(ArrayList<Line> s) { lines.clear(); for (Line l : s) lines.add(l.copy()); }

    // ------------------------------------------------------------------ Luganda syllables

    /**
     * Syllables in a word, Luganda-style: every syllable has one vowel nucleus (Luganda syllables are open, CV or V),
     * a doubled vowel ("aa", "ee") is one long syllable, and "y"/"w" glides belong to the next vowel. Works well enough
     * for English words too. Returns at least 1.
     */
    public static int syllables(String word) {
        int n = 0;
        boolean inVowel = false;
        String w = word.toLowerCase(Locale.ROOT);
        for (int i = 0; i < w.length(); i++) {
            boolean v = "aeiouáéíóú".indexOf(w.charAt(i)) >= 0;
            if (v && !inVowel) n++;
            inVowel = v;
        }
        return Math.max(1, n);
    }

    /** Relative singing time of a word: syllables, with long (doubled) vowels and long consonants ("kk", "tt") a little longer. */
    public static double singWeight(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        double weight = syllables(word);
        for (int i = 1; i < w.length(); i++) if (w.charAt(i) == w.charAt(i - 1) && Character.isLetter(w.charAt(i))) weight += .5;
        return weight;
    }

    /** Estimated word starts for a line sung from start to end, shared by syllable weight. */
    static long[] estimateWords(String[] words, long start, long end) {
        double total = 0;
        for (String w : words) total += singWeight(w);
        long[] out = new long[words.length];
        double acc = 0;
        for (int k = 0; k < words.length; k++) { out[k] = start + Math.round((end - start) * acc / total); acc += singWeight(words[k]); }
        return out;
    }

    // ------------------------------------------------------------------ timing

    /** Start of the next synced line after i, or -1. */
    long nextStart(int i) { for (int k = i + 1; k < lines.size(); k++) if (lines.get(k).start >= 0) return lines.get(k).start; return -1; }

    /** When line i leaves the screen. */
    public long endOf(int i, long songEnd) {
        Line l = lines.get(i);
        long next = nextStart(i), hold = holdFor(l.text);
        long end;
        if (l.end > l.start) {
            // Known end of singing: stay a moment longer, but leave when the next line starts or after a clear gap.
            end = l.end + 600;
            if (next >= 0 && (end > next || next - end < 400)) end = next;
        } else end = next < 0 ? l.start + hold : (next - l.start > MAX_HOLD_MS + 1500 ? l.start + hold : next);
        if (songEnd > 0) end = Math.min(end, Math.max(l.start + 500, songEnd));
        return end;
    }

    /** How long a line stays up before an instrumental break: depends on its length. */
    static long holdFor(String text) { return Math.max(3000, Math.min(MAX_HOLD_MS, 1800 + 110L * text.length())); }

    /** Estimated duration of the sung part: used for the karaoke wipe. */
    static long singFor(String text, long span) { return Math.max(500, Math.min((long) (span * .9), Math.max(900, 95L * text.length()))); }

    /** Index of the synced line whose start is the latest one at or before t, or -1. */
    public int lastStarted(long t) {
        int found = -1;
        for (int i = 0; i < lines.size(); i++) { long s = lines.get(i).start; if (s < 0) continue; if (s <= t) found = i; else break; }
        return found;
    }

    public long firstStart() { for (Line l : lines) if (l.start >= 0) return l.start; return -1; }

    public Frame frame(long t, long songEnd) {
        Frame f = new Frame();
        long first = firstStart();
        if (first < 0 || t < first) {
            f.untilFirst = first < 0 ? Long.MAX_VALUE : first - t;
            f.next = first < 0 ? -1 : lastStarted(first);
            return f;
        }
        int i = lastStarted(t);
        Line l = lines.get(i);
        long end = endOf(i, songEnd), span = Math.max(1, end - l.start), since = t - l.start;
        f.next = nextSynced(i);
        if (t >= end) { f.previous = i; return f; } // instrumental gap
        f.index = i;
        int prev = previousSynced(i);
        boolean joined = prev >= 0 && endOf(prev, songEnd) >= l.start;
        f.previous = joined ? prev : -1;
        f.in = clamp(since / (float) FADE_MS);
        f.change = joined ? ease(clamp(since / (float) FADE_MS)) : 1;
        boolean gapAfter = f.next < 0 || lines.get(f.next).start > end;
        f.out = gapAfter ? clamp(1 - (end - t) / (float) FADE_MS) : 0;
        String[] w = l.words();
        long singEnd = l.end > l.start ? l.end : l.start + singFor(l.text, span);
        long[] starts = l.wordTimed() ? l.wordTimes : estimateWords(w, l.start, singEnd);
        int k = -1;
        for (int j = 0; j < starts.length; j++) if (starts[j] <= t) k = j;
        float pos = 0;
        if (k >= 0) {
            long a = starts[k], b = k + 1 < starts.length ? starts[k + 1] : (l.wordTimed() ? Math.min(end, a + 180 + Math.round(170 * singWeight(w[k]))) : singEnd);
            pos = k + clamp((t - a) / (float) Math.max(1, b - a));
        }
        f.words = w.length;
        f.wordPos = Math.min(w.length, pos);
        // Character-based progress for the wipe, following the word timing.
        int total = 0;
        for (String x : w) total += x.length();
        float chars = 0;
        int whole = (int) f.wordPos;
        for (int j = 0; j < whole && j < w.length; j++) chars += w[j].length();
        if (whole < w.length) chars += (f.wordPos - whole) * w[whole].length();
        f.wipe = total == 0 ? 1 : clamp(chars / total);
        return f;
    }

    int nextSynced(int i) { for (int k = i + 1; k < lines.size(); k++) if (lines.get(k).start >= 0) return k; return -1; }
    int previousSynced(int i) { for (int k = i - 1; k >= 0; k--) if (lines.get(k).start >= 0) return k; return -1; }

    static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    /** Smooth ease in/out. */
    public static float ease(float x) { x = clamp(x); return x * x * (3 - 2 * x); }
}

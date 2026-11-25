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
        public Line(String text, long start) { this.text = text; this.start = start; }
    }

    /** What the renderer needs to know about one moment of the song. */
    public static final class Frame {
        /** Line on screen, or -1 for none (before the first line, or during an instrumental gap). */
        public int index = -1;
        /** Line that was on screen before this one (for scrolling transitions), or -1. */
        public int previous = -1;
        /** Next line that will appear, or -1. */
        public int next = -1;
        /** 0..1 progress of the sung part of the line (karaoke wipe, typewriter, word pop). */
        public float wipe;
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
            if (old.get(i).text.equals(fresh.get(j).text)) { fresh.get(j).start = old.get(i).start; matched[j] = true; i++; j++; }
            else if (lcs[i + 1][j] >= lcs[i][j + 1]) i++; else j++;
        }
        // An edited line that replaces exactly one old line at the same place keeps its old time.
        if (n == m) for (int k = 0; k < m; k++) if (!matched[k]) fresh.get(k).start = old.get(k).start;
        lines.clear(); lines.addAll(fresh);
        repairOrder();
    }

    /** Un-syncs any line whose time is earlier than a synced line before it. */
    public void repairOrder() {
        long last = -1;
        for (Line l : lines) { if (l.start < 0) continue; if (l.start < last) l.start = -1; else last = l.start; }
    }

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
            String words = clean(WORD_TIME.matcher(row.substring(end)).replaceAll(""));
            if (times.isEmpty()) { if (!words.isEmpty()) l.lines.add(new Line(words, -1)); continue; }
            if (words.isEmpty()) continue; // an empty timed row only marks the end of the previous line
            for (long t : times) l.lines.add(new Line(words, t));
        }
        // LRC offset: positive values make lyrics appear sooner.
        for (Line x : l.lines) if (x.start >= 0) x.start = Math.max(0, x.start - offset);
        l.sortByTime();
        return l;
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
        b.append("[re:Audio City Lyrics]\n");
        for (Line l : lines) { if (l.start >= 0) b.append('[').append(stamp(l.start)).append(']'); b.append(l.text).append('\n'); }
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
    public void clearTimes() { for (Line l : lines) l.start = -1; }
    public void shift(long ms) { for (Line l : lines) if (l.start >= 0) l.start = Math.max(0, l.start + ms); }

    // ------------------------------------------------------------------ timing

    /** Start of the next synced line after i, or -1. */
    long nextStart(int i) { for (int k = i + 1; k < lines.size(); k++) if (lines.get(k).start >= 0) return lines.get(k).start; return -1; }

    /** When line i leaves the screen. */
    public long endOf(int i, long songEnd) {
        Line l = lines.get(i);
        long next = nextStart(i), hold = holdFor(l.text);
        long end = next < 0 ? l.start + hold : (next - l.start > MAX_HOLD_MS + 1500 ? l.start + hold : next);
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
        f.wipe = clamp(since / (float) singFor(l.text, span));
        return f;
    }

    int nextSynced(int i) { for (int k = i + 1; k < lines.size(); k++) if (lines.get(k).start >= 0) return k; return -1; }
    int previousSynced(int i) { for (int k = i - 1; k >= 0; k--) if (lines.get(k).start >= 0) return k; return -1; }

    static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    /** Smooth ease in/out. */
    public static float ease(float x) { x = clamp(x); return x * x * (3 - 2 * x); }
}

package ug.audiocity.lyrics;

/**
 * Word-by-word tap syncing: each tap drops the next word at the current song time. The first word of a line also
 * sets the line's start; when its last word is tapped the line gets all its word times. Plain Java, tested.
 */
final class WordTapper {
    static final long MIN_GAP = 40; // two taps closer than this are treated as one moment, a little apart

    final Lyrics lyrics;
    private int line, word;
    private long[] partial;

    /** Starts at line `from`; that line and all after it lose their old times (they are tapped again). */
    WordTapper(Lyrics lyrics, int from) {
        this.lyrics = lyrics;
        line = Math.max(0, Math.min(from, lyrics.lines.size()));
        for (int i = line; i < lyrics.lines.size(); i++) { lyrics.lines.get(i).start = -1; lyrics.lines.get(i).wordTimes = null; }
        newLine();
    }

    /** First line that is not yet timed word by word (where "continue" begins). */
    static int firstUntimed(Lyrics l) {
        for (int i = 0; i < l.lines.size(); i++) if (!l.lines.get(i).wordTimed()) return i;
        return l.lines.size();
    }

    private void newLine() {
        word = 0;
        partial = line < lyrics.lines.size() ? filled(lyrics.lines.get(line).words().length) : null;
    }

    private static long[] filled(int n) { long[] a = new long[n]; java.util.Arrays.fill(a, -1); return a; }

    boolean done() { return line >= lyrics.lines.size(); }
    int line() { return line; }
    int word() { return word; }
    String[] words() { return done() ? new String[0] : lyrics.lines.get(line).words(); }

    /** Time of the last word dropped (in this line or the one before), or -1. */
    long lastTime() {
        if (word > 0) return partial[word - 1];
        for (int i = line - 1; i >= 0; i--) {
            Lyrics.Line l = lyrics.lines.get(i);
            if (l.wordTimed()) return l.wordTimes[l.wordTimes.length - 1];
            if (l.start >= 0) return l.start;
        }
        return -1;
    }

    /** Drops the next word at time t. */
    void tap(long t) {
        if (done()) return;
        long last = lastTime();
        if (last >= 0 && t < last + MIN_GAP) t = last + MIN_GAP;
        Lyrics.Line l = lyrics.lines.get(line);
        partial[word] = t;
        if (word == 0) { l.start = t; l.wordTimes = null; }
        word++;
        if (word >= partial.length) {
            l.wordTimes = partial;
            line++;
            newLine();
        }
    }

    /** Takes back the last word dropped (going into the previous line if needed). Returns its time, or -1 if nothing to undo. */
    long undo() {
        if (word > 0) {
            word--;
            long t = partial[word];
            partial[word] = -1;
            if (word == 0) lyrics.lines.get(line).start = -1;
            return t;
        }
        if (line == 0) return -1;
        Lyrics.Line prev = lyrics.lines.get(line - 1);
        line--;
        if (prev.wordTimed()) {
            partial = prev.wordTimes.clone();
            prev.wordTimes = null;
            word = partial.length - 1;
            long t = partial[word];
            partial[word] = -1;
            if (word == 0) prev.start = -1;
            return t;
        }
        // A line that was synced only as a whole: take its time back entirely.
        long t = prev.start;
        prev.start = -1;
        newLine();
        return t;
    }
}

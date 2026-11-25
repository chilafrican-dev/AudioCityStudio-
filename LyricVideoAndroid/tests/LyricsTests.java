package ug.audiocity.lyrics;

/** Desktop checks for lyric parsing, editing and timing (no phone needed). Run with run-tests.sh. */
public final class LyricsTests {
    static int checks;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }
    static void eq(Object a, Object b, String what) { check(a == null ? b == null : a.equals(b), what + ": expected " + b + ", got " + a); }

    public static void main(String[] args) {
        plainText(); cleanUp(); spread(); lrc(); stamps(); editingKeepsTimes(); timing(); gaps(); sorting(); style();
        System.out.println("LyricsTests: " + checks + " checks passed");
    }

    static void plainText() {
        Lyrics l = Lyrics.parse("  First line \r\n\r\nSecond\tline\n\n  ", null);
        eq(l.lines.size(), 2, "blank rows skipped");
        eq(l.lines.get(1).text, "Second line", "whitespace cleaned");
        check(!l.fullySynced() && l.nextUnsynced() == 0, "new lines are unsynced");
        eq(l.toText(), "First line\nSecond line", "toText");
    }

    static void cleanUp() {
        String raw = "[Verse 1: Nico]\nChorus:\n[00:12.00]City lights are calling **out** my name\nHook me up tonight\n(oh yeah)\nx2\nVerse 2\n"
                + "We were dancing through the night until the morning comes, and we never stopped\n";
        String c = Lyrics.cleanUp(raw, Lyrics.LONG_LINE);
        String[] rows = c.split("\n");
        eq(rows[0], "City lights are calling out my name", "labels, timestamps and markdown removed");
        eq(rows[1], "Hook me up tonight", "a real lyric starting with a section word is kept");
        eq(rows[2], "(oh yeah)", "sung ad-libs in parentheses are kept");
        eq(rows[3], "We were dancing through the night until", "long row split near the middle");
        eq(rows[4], "the morning comes, and we never stopped", "second half");
        eq(rows.length, 5, "rows after clean-up");
        for (String r : rows) check(r.length() <= Lyrics.LONG_LINE, "every row fits: " + r);
        check(c.contains("morning comes,"), "punctuation kept with its words");
        check(Lyrics.needsCleanUp(raw), "suggests clean-up");
        check(!Lyrics.needsCleanUp("Short line\nAnother one"), "clean text needs nothing");
        eq(Lyrics.splitLong("one two, three four five six seven eight", 34).get(0), "one two,", "prefers punctuation when close");
    }

    static void spread() {
        Lyrics l = Lyrics.parse("a\nbb\nccc\ndddd", null);
        eq(l.spread(10000, 50000), 4, "all lines timed");
        eq(l.lines.get(0).start, 10000L, "first at start");
        check(l.lines.get(1).start > 10000 && l.lines.get(3).start < 50000, "inside the range");
        check(l.lines.get(2).start - l.lines.get(1).start > l.lines.get(1).start - l.lines.get(0).start, "longer lines get more time");
        Lyrics m = Lyrics.parse("one\ntwo\nthree\nfour\nfive", null);
        m.lines.get(0).start = 5000; m.lines.get(3).start = 20000;
        eq(m.spread(0, 60000), 3, "only the gaps are filled");
        eq(m.lines.get(0).start, 5000L, "synced lines stay");
        eq(m.lines.get(3).start, 20000L, "synced lines stay");
        check(m.lines.get(1).start > 5000 && m.lines.get(2).start > m.lines.get(1).start && m.lines.get(2).start < 20000, "between neighbours");
        check(m.lines.get(4).start > 20000 && m.lines.get(4).start < 60000, "tail spread to the end");
        check(m.fullySynced(), "fully synced afterwards");
    }

    static void lrc() {
        String[] meta = {null, null};
        String text = "﻿[ti:My Song]\n[ar:Nico]\n[offset:+500]\n[00:12.50]Hello <00:12.80>world\n[00:20.00][01:05.5]Chorus line\n[00:30.123]Third\n[00:40.00]\nUntimed tail";
        Lyrics l = Lyrics.parseLrc(text.substring(1), meta);
        eq(meta[0], "My Song", "title");
        eq(meta[1], "Nico", "artist");
        eq(l.lines.size(), 5, "repeated tags expand, empty timed row dropped, untimed kept");
        eq(l.lines.get(0).text, "Hello world", "word tags removed");
        eq(l.lines.get(0).start, 12000L, "offset applied (12.5 s - 0.5 s)");
        eq(l.lines.get(1).text, "Chorus line", "chorus first time");
        eq(l.lines.get(2).start, 29623L, "3-digit fraction");
        eq(l.lines.get(3).start, 65000L, "1-digit fraction is tenths, sorted after 0:30");
        eq(l.lines.get(4).start, -1L, "untimed line");
        check(Lyrics.looksLikeLrc("x\n[01:02.03]hey"), "detect LRC");
        check(!Lyrics.looksLikeLrc("[Chorus]\nhey"), "section label is not LRC");
        Lyrics back = Lyrics.parseLrc(l.toLrc("My Song", "Nico"), meta);
        eq(back.lines.size(), 5, "LRC round trip size");
        eq(back.lines.get(2).start, 29620L, "LRC round trip keeps centiseconds");
    }

    static void stamps() {
        eq(Lyrics.stamp(83456), "01:23.46", "stamp rounding");
        eq(Lyrics.parseStamp("1:23.45"), 83450L, "parse m:ss.cc");
        eq(Lyrics.parseStamp("12.5"), 12500L, "parse seconds");
        eq(Lyrics.parseStamp("abc"), -1L, "bad time");
        eq(Lyrics.parseStamp("-3"), -1L, "negative time");
    }

    static void editingKeepsTimes() {
        Lyrics l = Lyrics.parse("one\ntwo\nthree\nfour", null);
        long[] t = {1000, 2000, 3000, 4000};
        for (int i = 0; i < 4; i++) l.lines.get(i).start = t[i];
        l.setText("one\ntwo\nnew line\nthree\nfour");
        eq(l.lines.size(), 5, "inserted");
        eq(l.lines.get(3).start, 3000L, "time follows its line after an insert");
        eq(l.lines.get(2).start, -1L, "new line unsynced");
        l.setText("one\ntwo\nnew line\nthree!\nfour");
        eq(l.lines.get(3).start, 3000L, "typo fix keeps the time when the line count is unchanged");
        l.setText("one\nthree!\nfour");
        eq(l.lines.get(1).start, 3000L, "deleting lines keeps the rest");
        l.lines.get(1).start = 500; l.repairOrder();
        eq(l.lines.get(1).start, -1L, "out-of-order time is cleared");
        l.shift(-1500);
        eq(l.lines.get(0).start, 0L, "shift clamps at zero");
        eq(l.lines.get(2).start, 2500L, "shift");
    }

    static void timing() {
        Lyrics l = Lyrics.parse("a short one\nsecond line here\nthird", null);
        l.lines.get(0).start = 2000; l.lines.get(1).start = 4000; l.lines.get(2).start = 6000;
        Lyrics.Frame f = l.frame(500, 60000);
        eq(f.index, -1, "before first line");
        eq(f.untilFirst, 1500L, "title card countdown");
        f = l.frame(2000, 60000);
        eq(f.index, 0, "first line");
        eq(f.in, 0f, "fade starts");
        eq(f.next, 1, "next line");
        f = l.frame(3990, 60000);
        check(f.wipe == 1f, "wipe completes before the next line");
        eq(f.out, 0f, "no fade-out when the next line follows straight on");
        f = l.frame(4100, 60000);
        eq(f.index, 1, "second line");
        eq(f.previous, 0, "joined to previous for the transition");
        check(f.change > 0 && f.change < 1, "transition in progress");
        f = l.frame(4000 + Lyrics.FADE_MS + 1, 60000);
        eq(f.change, 1f, "transition done");
        Lyrics empty = new Lyrics();
        eq(empty.frame(1000, 5000).untilFirst, Long.MAX_VALUE, "no lyrics: title card stays");
    }

    static void gaps() {
        Lyrics l = Lyrics.parse("before the break\nafter the break", null);
        l.lines.get(0).start = 1000; l.lines.get(1).start = 40000;
        long end = l.endOf(0, 90000);
        check(end > 1000 + 2000 && end <= 1000 + Lyrics.MAX_HOLD_MS, "long gap: line is held then hidden, end=" + end);
        Lyrics.Frame f = l.frame(end - 100, 90000);
        check(f.out > 0, "fades out before a gap");
        f = l.frame(20000, 90000);
        eq(f.index, -1, "nothing during the instrumental");
        eq(f.next, 1, "knows what is coming");
        eq(f.previous, 0, "knows what was last");
        f = l.frame(40100, 90000);
        eq(f.index, 1, "after the break");
        eq(f.previous, -1, "no slide from a line that already left");
        check(l.endOf(1, 41000) <= 41000, "last line ends with the song");
    }

    static void sorting() {
        Lyrics l = Lyrics.parse("a\nb\nc\nd", null);
        l.lines.get(0).start = 3000; l.lines.get(1).start = -1; l.lines.get(2).start = 1000; l.lines.get(3).start = 2000;
        l.sortByTime();
        eq(l.lines.get(0).text, "c", "sorted 0");
        eq(l.lines.get(1).text, "b", "unsynced keeps its slot");
        eq(l.lines.get(2).text, "d", "sorted 2");
        eq(l.lines.get(3).text, "a", "sorted 3");
        eq(l.lastStarted(2500), 2, "lastStarted skips unsynced");
    }

    static void style() {
        Style s = new Style();
        eq(s.width() + "x" + s.height(), "1080x1920", "vertical 1080p");
        s.aspect = 1; s.quality = 0;
        eq(s.width() + "x" + s.height(), "1280x720", "landscape 720p");
        s.aspect = 2;
        eq(s.width() + "x" + s.height(), "720x720", "square");
        s.animation = 99; s.textSize = 9; s.fps = 25; s.sanitise();
        check(s.animation == Style.ANIMATIONS.length - 1 && s.textSize == 1.6f && s.fps == 30, "sanitise");
        Style c = s.copy(); c.highlight = 1;
        check(s.highlight != 1, "copy is independent");
    }
}

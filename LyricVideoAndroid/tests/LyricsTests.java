package ug.audiocity.lyrics;

/** Desktop checks for lyric parsing, editing and timing (no phone needed). Run with run-tests.sh. */
public final class LyricsTests {
    static int checks;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }
    static void eq(Object a, Object b, String what) { check(a == null ? b == null : a.equals(b), what + ": expected " + b + ", got " + a); }

    public static void main(String[] args) {
        plainText(); words(); wordTapping(); lineEnds(); stamps2(); cleanUp(); spread(); lrc(); stamps(); editingKeepsTimes(); timing(); gaps(); sorting(); style();
        System.out.println("LyricsTests: " + checks + " checks passed");
    }

    static void plainText() {
        Lyrics l = Lyrics.parse("  First line \r\n\r\nSecond\tline\n\n  ", null);
        eq(l.lines.size(), 2, "blank rows skipped");
        eq(l.lines.get(1).text, "Second line", "whitespace cleaned");
        check(!l.fullySynced() && l.nextUnsynced() == 0, "new lines are unsynced");
        eq(l.toText(), "First line\nSecond line", "toText");
    }

    static void words() {
        eq(Lyrics.syllables("tukwagala"), 4, "Luganda syllables tu-kwa-ga-la");
        eq(Lyrics.syllables("nnyabo"), 2, "nnya-bo");
        eq(Lyrics.syllables("Kampala"), 3, "Kam-pa-la");
        check(Lyrics.singWeight("mukwano") < Lyrics.singWeight("mukwaano"), "long vowel sings longer");
        String lrc = "[00:10.00]<00:10.00>Nkwagala <00:11.20>nnyo <00:12.00>mukwano\n[00:15.00]Line without word tags";
        Lyrics l = Lyrics.parseLrc(lrc, null);
        check(l.lines.get(0).wordTimed(), "enhanced LRC word tags parsed");
        eq(l.lines.get(0).text, "Nkwagala nnyo mukwano", "word tags removed from text");
        eq(l.lines.get(0).wordTimes[1], 11200L, "second word time");
        check(!l.lines.get(1).wordTimed(), "plain line has no word times");
        Lyrics back = Lyrics.parseLrc(l.toLrc(null, null), null);
        eq(back.lines.get(0).wordTimes[2], 12000L, "word times survive an LRC round trip");
        Lyrics.Frame f = l.frame(11600, 60000);
        check(f.wordPos > 1 && f.wordPos < 2, "inside the second word: " + f.wordPos);
        eq(f.words, 3, "word count");
        float charsBefore = "Nkwagala".length() / (float) "Nkwagalannyomukwano".length();
        check(f.wipe > charsBefore && f.wipe < charsBefore + 4f / 19, "wipe follows the word times: " + f.wipe);
        f = l.frame(10500, 60000);
        check(f.wordPos < 1, "first word still singing");
        l.shift(1000);
        eq(l.lines.get(0).wordTimes[0], 11000L, "shift moves word times");
        Lyrics.moveLine(l.lines.get(0), 20000);
        eq(l.lines.get(0).wordTimes[2], 22000L, "moving a line carries its words");
        l.lines.get(0).text = "edited line";
        l.repairOrder();
        check(l.lines.get(0).wordTimes == null, "word times dropped when the words change");
        Lyrics est = Lyrics.parse("tu tukwagala", null);
        est.lines.get(0).start = 0;
        Lyrics.Frame e = est.frame(300, 60000);
        check(e.wordPos > 1, "short Luganda word sung quickly, long word takes longer (syllable estimate): " + e.wordPos);
        Lyrics keep = Lyrics.parseLrc(lrc, null);
        keep.setText("Nkwagala nnyo mukwano\nA new line\nLine without word tags");
        check(keep.lines.get(0).wordTimed(), "editing other lines keeps word times");
    }

    static void stamps2() {
        Lyrics l = Lyrics.parse("Nkwagala nnyo\nTuli wamu\nOmukwano", null);
        l.setStampedText("[0:15.20] Nkwagala nnyo\n0:21 Tuli wamu\nOmukwano");
        eq(l.lines.get(0).start, 15200L, "bracket stamp read");
        eq(l.lines.get(1).start, 21000L, "plain m:ss stamp read");
        eq(l.lines.get(2).start, -1L, "row without a stamp is not placed");
        eq(l.toStampedText(), "[0:15.20] Nkwagala nnyo\n[0:21.00] Tuli wamu\nOmukwano", "box shows stamps");
        l.lines.get(0).wordTimes = new long[]{15200, 15900};
        l.setStampedText(l.toStampedText());
        check(l.lines.get(0).wordTimed(), "unchanged box keeps word times");
        l.setStampedText("[0:16.20] Nkwagala nnyo\n[0:21.00] Tuli wamu\n[0:30.00] Omukwano\n[0:33.50]");
        eq(l.lines.get(0).wordTimes[1], 16900L, "changing a stamp moves the line's words with it");
        eq(l.lines.get(2).end, 33500L, "a row with only a stamp marks where the line before ends");
        l.setStampedText("[0:16.20] Nkwagala nnyo\n[0:18.00] A new line\n[0:21.00] Tuli wamu\n[0:30.00] Omukwano");
        eq(l.lines.size(), 4, "new line inserted");
        eq(l.lines.get(1).start, 18000L, "new line takes its typed stamp");
        eq(l.lines.get(2).start, 21000L, "others keep theirs");
        l.setStampedText("[0:16.20] Nkwagala nnyo\n[0:12.00] A new line");
        eq(l.lines.get(1).start, -1L, "a stamp earlier than the line before is not accepted");
        check(Lyrics.isFullLrc("[ti:Song]\n[00:01.00]a") && Lyrics.isFullLrc("[00:01.00]<00:01.00>a <00:01.50>b") && Lyrics.isFullLrc("[00:01.00][00:30.00]chorus"), "full LRC recognised");
        check(!Lyrics.isFullLrc("[0:15.20] Nkwagala nnyo\n[0:21.00] Tuli wamu"), "box stamps are not a full LRC");
        eq(Lyrics.cleanUp("[0:15.20] [Chorus]\n[0:15.20] Nkwagala nnyo mukwano gwange tuli wamu bulijjo naawe", 42).split("\n")[0], "[0:15.20] Nkwagala nnyo mukwano gwange", "clean-up keeps a line's stamp on its first part");
        eq(Lyrics.boxStamp(75460), "[1:15.46]", "box stamp format");
    }

    static void lineEnds() {
        Lyrics l = Lyrics.parse("Nkwagala nnyo\nTuli wamu", null);
        l.lines.get(0).start = 5000; l.lines.get(0).end = 7000;   // held from 5.0 to 7.0 s
        l.lines.get(1).start = 20000; l.lines.get(1).end = 22000; // after a 13-second break
        check(l.frame(6000, 60000).index == 0, "held line on screen while sung");
        check(l.frame(8500, 60000).index == -1, "screen clear during the break after a held line");
        check(l.frame(6900, 60000).wipe > .9f, "words spread over the held time");
        Lyrics.Frame f = l.frame(7400, 60000);
        check(f.index == 0 && f.out > 0, "line fades out just after it ends");
        String lrc = l.toLrc(null, null);
        check(lrc.contains("[00:07.00]\n"), "end mark written as an empty timed row: " + lrc);
        Lyrics back = Lyrics.parseLrc(lrc, null);
        check(back.lines.size() == 2 && back.lines.get(0).end == 7000 && back.lines.get(1).end == 22000, "end marks read back");
        Lyrics.moveLine(l.lines.get(0), 6000);
        eq(l.lines.get(0).end, 8000L, "moving a line moves its end");
        l.shift(-1000);
        eq(l.lines.get(1).end, 21000L, "shift moves ends");
        l.lines.get(1).end = 100; l.repairOrder();
        eq(l.lines.get(1).end, -1L, "an end before the start is dropped");
        Lyrics close = Lyrics.parse("a b\nc d", null);
        close.lines.get(0).start = 1000; close.lines.get(0).end = 2900; close.lines.get(1).start = 3000;
        check(close.frame(2950, 60000).index == 0, "a line ending just before the next one stays until it");
    }

    static void wordTapping() {
        Lyrics l = Lyrics.parse("Nkwagala nnyo\nTuli wamu bulijjo\nOmukwano", null);
        l.lines.get(2).start = 99999; // old time, cleared when tapping starts before it
        WordTapper w = new WordTapper(l, 0);
        check(l.lines.get(2).start == -1, "lines being re-tapped lose old times");
        w.tap(5000);
        eq(l.lines.get(0).start, 5000L, "first word sets the line start (intro skipped by just waiting)");
        check(!l.lines.get(0).wordTimed(), "line not complete yet");
        w.tap(5600);
        check(l.lines.get(0).wordTimed() && l.lines.get(0).wordTimes[1] == 5600, "line complete after its last word");
        eq(w.line(), 1, "cursor moves to the next line");
        w.tap(15000); // after an instrumental break
        w.tap(15010); // a double tap: kept apart, in order
        eq(l.lines.get(1).start, 15000L, "line after the break starts where tapped");
        check(w.lastTime() == 15000 + WordTapper.MIN_GAP, "taps too close are moved apart: " + w.lastTime());
        eq(w.undo(), 15040L, "undo the last word");
        eq(w.word(), 1, "back to the second word");
        w.undo();
        eq(l.lines.get(1).start, -1L, "undoing the first word un-syncs the line");
        eq(w.undo(), 5600L, "undo goes back into the previous line");
        check(!l.lines.get(0).wordTimed() && l.lines.get(0).start == 5000, "previous line reopened at its last word");
        w.tap(5700); w.tap(15000); w.tap(15500); w.tap(16000); w.tap(20000);
        check(w.done() && l.fullySynced() && l.wordTimedCount() == 3, "all lines timed word by word");
        eq(l.lines.get(0).wordTimes[1], 5700L, "re-tapped word kept");
        eq(WordTapper.firstUntimed(l), 3, "nothing left to continue");
        Lyrics.Frame f = l.frame(15600, 60000);
        check(f.index == 1 && f.wordPos >= 1 && f.wordPos < 2, "playback follows tapped words: " + f.wordPos);
    }

    static void cleanUp() {
        String raw = "[Verse 1: Nico]\nChorus:\n[00:12.00]City lights are calling **out** my name\nHook me up tonight\n(oh yeah)\nx2\nVerse 2\n"
                + "We were dancing through the night until the morning comes, and we never stopped\n";
        String c = Lyrics.cleanUp(raw, Lyrics.LONG_LINE);
        String[] rows = c.split("\n");
        eq(rows[0], "[0:12.00] City lights are calling out my name", "labels and markdown removed, the line's time kept as a box stamp");
        eq(rows[1], "Hook me up tonight", "a real lyric starting with a section word is kept");
        eq(rows[2], "(oh yeah)", "sung ad-libs in parentheses are kept");
        eq(rows[3], "We were dancing through the night until", "long row split near the middle");
        eq(rows[4], "the morning comes, and we never stopped", "second half");
        eq(rows.length, 5, "rows after clean-up");
        for (String r : rows) check(r.replaceFirst("^\\[[0-9:.]+\\] ", "").length() <= Lyrics.LONG_LINE, "every row fits: " + r);
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

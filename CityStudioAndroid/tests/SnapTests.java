package ug.audiocity.studio;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Host checks for 0.11 Logic-style snapping, magnetic alignment, edge trims and drag modes. */
public class SnapTests {
    static int passed;
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); passed++; }
    static final int R = Core.RATE;
    static Core.Clip clip(long at, int len) { float[] d = new float[len * 4]; for (int i = 0; i < len * 2; i++) d[2 * i] = d[2 * i + 1] = (float) Math.sin(i * .07 + .3); Core.Clip c = new Core.Clip("c", new Core.Memory(d), "c", at); c.length = len; return c; }

    public static void main(String[] args) throws Exception {
        Core.Session s = new Core.Session(); s.bpm = 120; Pro.TempoMap m = new Pro.TempoMap(s); int beat = R / 2, bar = beat * 4;
        check(ProSnap.smartDivision(200, 14) == 8 && ProSnap.smartDivision(40, 14) == 2 && ProSnap.smartDivision(20, 14) == 1 && ProSnap.smartDivision(10, 14) == ProSnap.BAR, "smart snap follows zoom");
        check(ProSnap.grid(s, m, bar + beat + 10, ProSnap.BAR) == bar && ProSnap.grid(s, m, bar + 3 * beat, ProSnap.BAR) == 2L * bar, "bar grid");
        check(ProSnap.grid(s, m, beat + 1000, 4) == beat + beat / 4 * (int) Math.round(1000.0 / (beat / 4)) || ProSnap.grid(s, m, beat + 1000, 4) == beat, "1/16 grid");
        check(ProSnap.grid(s, m, 12345, ProSnap.OFF) == 12345, "off is free");
        check(ProSnap.relative(s, m, beat + 700, beat + 700 + beat + 300, 1) == 2L * beat + 700, "relative keeps the offset from the grid");
        check(ProSnap.relative(s, m, beat + 700, beat + 700 + 100, 1) == beat + 700, "small relative moves stay put");

        Core.Track a = new Core.Track("a", 0), b = new Core.Track("b", 0); s.tracks.add(a); s.tracks.add(b);
        Core.Clip other = clip(3 * beat + 1111, beat); a.clips.add(other); Core.Clip mover = clip(0, beat); b.clips.add(mover);
        s.markers.add(new Pro.Marker(10 * beat + 333, "Hook"));
        long magnet = 1200;
        ProSnap.Result r = ProSnap.move(s, m, mover, 0, 3 * beat + 1111 - 600, 0, 4, magnet, false); check(r.magnet && r.frame == 3 * beat + 1111 && "clip edge".equals(r.what) && r.guide == 3 * beat + 1111, "start locks to another clip's start");
        r = ProSnap.move(s, m, mover, 0, 3 * beat + 1111 + beat + 500, 0, 4, magnet, false); check(r.magnet && r.frame == 4 * beat + 1111, "start locks to another clip's end (butting)");
        r = ProSnap.move(s, m, mover, 0, 10 * beat + 333 - beat + 400, 0, 4, magnet, false); check(r.magnet && r.frame + mover.length == 10 * beat + 333 && "marker".equals(r.what), "end locks to a marker");
        r = ProSnap.move(s, m, mover, 0, 7 * beat + 5000, 5 * beat * 0 + 7 * beat + 4500, 4, magnet, false); check(r.magnet && "playhead".equals(r.what) && r.frame == 7 * beat + 4500, "locks to the playhead");
        r = ProSnap.move(s, m, mover, 0, 6 * beat + 3000, 0, 4, magnet, false); check(!r.magnet && r.guide == -1 && r.frame == 6 * beat + beat / 4, "otherwise the grid: " + r.frame);
        r = ProSnap.move(s, m, mover, 0, 3 * beat + 1111 - 300, 0, 4, magnet, true); check(!r.magnet && r.frame == 3 * beat + 811, "second finger = free placement");
        s.magnet = false; r = ProSnap.move(s, m, mover, 0, 3 * beat + 1111 - 300, 0, 4, magnet, false); check(!r.magnet, "magnet can be turned off"); s.magnet = true;
        s.loopStart = 20 * beat + 77; s.loopEnd = 24 * beat; r = ProSnap.point(s, m, null, 20 * beat + 500, 0, 4, magnet, false, false); check(r.magnet && r.frame == 20 * beat + 77 && "loop edge".equals(r.what), "seek locks to the loop start");

        Core.Clip t = clip(beat, beat); t.offset = 1000; ProSnap.trimStart(t, beat, 1000, beat, 0, beat - 5000); check(t.start == beat - 1000 && t.offset == 0 && t.length == beat + 1000, "left trim reveals audio up to the source start");
        Core.Clip t2 = clip(beat, beat); ProSnap.trimStart(t2, beat, 0, beat, 0, beat + 4000); check(t2.start == beat + 4000 && t2.offset == 4000 && t2.length == beat - 4000 && t2.envelopeOffset == 4000, "left trim keeps the audio in place");
        Core.Clip t3 = clip(0, beat); ProSnap.trimEnd(t3, 10L * beat); check(t3.length == t3.source.frames(), "right trim stops at the end of the source"); ProSnap.trimEnd(t3, -50); check(t3.length == 1, "never shorter than one frame");
        Core.Clip z = clip(0, beat); long zc = ProSnap.zeroCrossing(z, 1000, 96); int i = (int) zc; double prev = z.source.sample(i - 1, 0) + z.source.sample(i - 1, 1), here = z.source.sample(i, 0) + z.source.sample(i, 1);
        check(Math.abs(zc - 1000) <= 96 && (prev <= 0 && here >= 0 || prev >= 0 && here <= 0), "trim moves to a zero crossing: " + zc);

        Core.Session d = new Core.Session(); Core.Track tr = new Core.Track("t", 0); d.tracks.add(tr);
        Core.Clip under = clip(0, 10000); tr.clips.add(under); Core.Clip top = clip(4000, 2000); tr.clips.add(top);
        d.dragMode = ProSnap.NO_OVERLAP; ProSnap.drop(d, tr, top);
        check(tr.clips.size() == 3 && tr.clips.contains(top) && tr.clips.get(0).length == 4000 && tr.clips.get(2).start == 6000 && tr.clips.get(2).offset == 6000, "No Overlap cuts a hole in the clip underneath");
        Core.Track xt = new Core.Track("x", 0); d.tracks.add(xt); d.dragMode = ProSnap.XFADE; ProSnap.drop(d, xt, clip(0, 100)); check(xt.autoCrossfade, "X-Fade turns on crossfades");
        Core.Track sh = new Core.Track("s", 0); Core.Clip c1 = clip(0, 1000), c2 = clip(1000, 1000), c3 = clip(2000, 1000), moved = clip(500, 1000); sh.clips.add(c1); sh.clips.add(c2); sh.clips.add(c3); sh.clips.add(moved);
        d.dragMode = ProSnap.SHUFFLE; ProSnap.drop(d, sh, moved);
        check(moved.start == 1000 && c2.start == 2000 && c3.start == 3000 && c1.start == 0, "Shuffle butts the clip and pushes the rest along: " + moved.start + " " + c2.start + " " + c3.start);
        Core.Track ov = new Core.Track("o", 0); Core.Clip o1 = clip(0, 1000), o2 = clip(500, 1000); ov.clips.add(o1); ov.clips.add(o2); d.dragMode = ProSnap.OVERLAP; ProSnap.drop(d, ov, o2); check(o1.length == 1000 && o2.start == 500, "Overlap leaves clips alone");

        check(Core.snap(bar + beat + 10, 120, ProSnap.BAR) == bar && Core.snap(beat / 4 + 10, 120, ProSnap.SMART) == beat / 4, "older code paths handle Bar and Smart");
        File dir = Files.createTempDirectory("snap").toFile(); Core.Session p = new Core.Session(); p.directory = dir.getAbsolutePath(); p.snapDivision = ProSnap.SMART; p.snapRelative = true; p.magnet = false; p.dragMode = ProSnap.SHUFFLE; ProjectIO.save(p);
        Core.Session l = ProjectIO.load(dir, f -> null); check(l.snapDivision == ProSnap.SMART && l.snapRelative && !l.magnet && l.dragMode == ProSnap.SHUFFLE, "snap settings saved");
        Core.Session c = p.copy(); check(c.snapRelative && !c.magnet && c.dragMode == ProSnap.SHUFFLE, "snap settings copied");
        Core.Session fresh = new Core.Session(); check(fresh.magnet && !fresh.snapRelative && fresh.dragMode == ProSnap.OVERLAP, "defaults: magnetic on, absolute grid, Overlap");
        System.out.println(passed + " snapping and drag-mode checks passed.");
    }
}

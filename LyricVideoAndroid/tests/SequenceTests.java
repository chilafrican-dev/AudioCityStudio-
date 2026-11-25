package ug.audiocity.lyrics;

/** Desktop checks for the multi-clip edit (locating, split, trim, move, speed). */
public final class SequenceTests {
    static int checks;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }

    public static void main(String[] args) {
        Sequence s = new Sequence();
        s.clips.add(Sequence.Clip.video("a.mp4", 10000));
        s.clips.add(Sequence.Clip.image("p.jpg", 4000));
        s.clips.add(Sequence.Clip.video("b.mp4", 6000));
        check(s.total() == 20000, "total length");
        Sequence.At a = s.at(12000);
        check(a.index == 1 && a.clip.photo && a.local == 2000, "inside the photo");
        a = s.at(15500);
        check(a.index == 2 && a.source == 1500, "inside the second video, file time");
        check(s.at(20000).index == -1, "after the end");
        check(s.split(0, 4000) && s.clips.size() == 4, "split the first video");
        check(s.clips.get(0).out == 4000 && s.clips.get(1).in == 4000 && s.total() == 20000, "split keeps the length");
        check(!s.split(0, 100), "no split right at the edge");
        check(s.trimStartTo(1, 5000) && s.clips.get(1).in == 5000 && s.total() == 19000, "trim the start");
        check(s.trimEndTo(1, 7000) && s.clips.get(1).out == 8000 && s.clips.get(1).length() == 3000, "trim the end (file 5.0 s + 3 s)");
        s.move(3, 0);
        check(s.clips.get(0).file.equals("b.mp4"), "move a clip to the front");
        Sequence.Clip fast = Sequence.Clip.video("c.mp4", 8000);
        fast.speed = 2f;
        check(fast.length() == 4000, "double speed halves the length");
        s.clips.clear(); s.clips.add(fast);
        check(s.at(1000).source == 2000, "file time follows the speed");
        java.util.ArrayList<Sequence.Clip> snap = s.snapshot();
        s.clips.clear(); s.restore(snap);
        check(s.clips.size() == 1 && s.clips.get(0).speed == 2f, "undo snapshot");
        System.out.println("SequenceTests: " + checks + " checks passed");
    }
}

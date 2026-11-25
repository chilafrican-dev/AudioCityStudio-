package ug.audiocity.lyrics;

import java.util.ArrayList;

/**
 * The video edit: clips (videos and photos) played one after another. Each video clip uses part of its file (in..out)
 * at a speed; each photo shows for a set time. Plain Java, tested on the desktop.
 */
final class Sequence {
    static final class Clip {
        /** File name inside the project folder. */
        String file;
        boolean photo;
        /** Video: the part of the file used, in ms. */
        long in, out;
        /** Length of the source file (video), ms. */
        long sourceLength;
        /** Photo: how long it shows, ms. */
        long photoMs = 4000;
        float volume = 1f, speed = 1f;
        /** Transition into the next clip (used by the transitions step) and its length. */
        int transition = 0;
        long transitionMs = 600;

        long length() { return photo ? Math.max(200, photoMs) : Math.max(100, Math.round((out - in) / Math.max(.1f, speed))); }

        Clip copy() {
            Clip c = new Clip();
            c.file = file; c.photo = photo; c.in = in; c.out = out; c.sourceLength = sourceLength; c.photoMs = photoMs;
            c.volume = volume; c.speed = speed; c.transition = transition; c.transitionMs = transitionMs;
            return c;
        }

        static Clip video(String file, long sourceLength) { Clip c = new Clip(); c.file = file; c.in = 0; c.out = Math.max(100, sourceLength); c.sourceLength = sourceLength; return c; }
        static Clip image(String file, long ms) { Clip c = new Clip(); c.file = file; c.photo = true; c.photoMs = ms; return c; }
    }

    final ArrayList<Clip> clips = new ArrayList<>();

    long total() { long t = 0; for (Clip c : clips) t += c.length(); return t; }

    long startOf(int i) { long t = 0; for (int k = 0; k < i && k < clips.size(); k++) t += clips.get(k).length(); return t; }

    /** Where the edit is at time t: which clip, how far into it, and the matching time in its file. */
    static final class At {
        int index = -1;
        Clip clip;
        /** ms into the clip (edit time). */
        long local;
        /** ms into the clip's file (video). */
        long source;
    }

    At at(long t) {
        At a = new At();
        long start = 0;
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            long len = c.length();
            if (t < start + len || i == clips.size() - 1) {
                if (t >= start + len) return a; // after the end of the edit
                a.index = i; a.clip = c; a.local = Math.max(0, t - start);
                a.source = c.photo ? a.local : c.in + Math.round(a.local * c.speed);
                return a;
            }
            start += len;
        }
        return a;
    }

    /** Splits clip i at edit time t (must be inside it, 0.2 s from either end). */
    boolean split(int i, long t) {
        if (i < 0 || i >= clips.size()) return false;
        Clip c = clips.get(i);
        long local = t - startOf(i);
        if (local < 200 || local > c.length() - 200) return false;
        Clip b = c.copy();
        if (c.photo) { b.photoMs = c.photoMs - local; c.photoMs = local; }
        else { long cut = c.in + Math.round(local * c.speed); b.in = cut; c.out = cut; }
        clips.add(i + 1, b);
        return true;
    }

    /** The clip now starts at edit time t (trims away what was before). */
    boolean trimStartTo(int i, long t) {
        if (i < 0 || i >= clips.size()) return false;
        Clip c = clips.get(i);
        long local = t - startOf(i);
        if (local <= 0 || local > c.length() - 200) return false;
        if (c.photo) c.photoMs -= local; else c.in += Math.round(local * c.speed);
        return true;
    }

    /** The clip now ends at edit time t (trims away what was after). */
    boolean trimEndTo(int i, long t) {
        if (i < 0 || i >= clips.size()) return false;
        Clip c = clips.get(i);
        long local = t - startOf(i);
        if (local < 200 || local >= c.length()) return false;
        if (c.photo) c.photoMs = local; else c.out = c.in + Math.round(local * c.speed);
        return true;
    }

    void move(int from, int to) {
        if (from < 0 || from >= clips.size()) return;
        to = Math.max(0, Math.min(clips.size() - 1, to));
        clips.add(to, clips.remove(from));
    }

    ArrayList<Clip> snapshot() { ArrayList<Clip> s = new ArrayList<>(); for (Clip c : clips) s.add(c.copy()); return s; }
    void restore(ArrayList<Clip> s) { clips.clear(); for (Clip c : s) clips.add(c.copy()); }
}

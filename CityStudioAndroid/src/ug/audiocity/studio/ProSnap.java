package ug.audiocity.studio;

import java.util.*;

/**
 * Logic-style snapping for the arrangement: smart/bar/beat/division grids, relative snapping, magnetic alignment to
 * clip edges, markers, the playhead and the loop range (with a guide line), zero-crossing trims, and the drag modes
 * Overlap, No Overlap, X-Fade and Shuffle. Platform independent.
 */
final class ProSnap {
    private ProSnap() {}
    static final int OFF = 0, SMART = -1, BAR = -2;
    static final int OVERLAP = 0, NO_OVERLAP = 1, XFADE = 2, SHUFFLE = 3;
    static final String[] MODES = {"Overlap", "No Overlap", "X-Fade", "Shuffle"};

    /** Subdivisions per beat for Smart snap: the finest grid whose lines are at least minPx apart (or bars when zoomed far out). */
    static int smartDivision(double pxPerBeat, double minPx) {
        if (pxPerBeat < minPx) return BAR;
        int d = 1; while (d < 16 && pxPerBeat / (d * 2) >= minPx) d *= 2; return d;
    }
    static int effectiveDivision(Core.Session s, double pxPerBeat, double minPx) { return s.snapDivision == SMART ? smartDivision(pxPerBeat, minPx) : s.snapDivision; }

    /** Nearest grid line (absolute). */
    static long grid(Core.Session s, Pro.TempoMap m, long frame, int division) {
        if (division == OFF) return Math.max(0, frame);
        if (division == BAR) { int bpb = m.beatsPerBar(); return Math.max(0, m.frameAt(Math.round(m.beatAt(frame) / bpb) * (double) bpb)); }
        return m.snap(frame, division);
    }
    /** Relative grid: moves by whole grid steps from where the clip started, keeping its offset from the grid. */
    static long relative(Core.Session s, Pro.TempoMap m, long original, long proposed, int division) {
        if (division == OFF) return Math.max(0, proposed);
        double steps = division == BAR ? 1.0 / m.beatsPerBar() : division; double b0 = m.beatAt(original), b1 = m.beatAt(proposed);
        double moved = Math.round((b1 - b0) * steps) / steps; return Math.max(0, m.frameAt(Math.max(0, b0 + moved)));
    }

    /** A snap result: the frame to use and the guide line to draw (−1 = none). magnet says it locked to an object, not the grid. */
    static final class Result { final long frame, guide; final boolean magnet; final String what; Result(long f, long g, boolean m, String w) { frame = f; guide = g; magnet = m; what = w; } }

    /** Every edge a moving clip can lock to: other clips' edges, markers, the playhead and the loop range. */
    static List<long[]> targets(Core.Session s, Core.Clip moving, long playhead) {
        ArrayList<long[]> out = new ArrayList<>(); // {frame, kind}: 0 clip edge, 1 marker, 2 playhead, 3 loop edge
        for (Core.Track t : s.tracks) for (Core.Clip c : t.clips) if (c != moving) { out.add(new long[]{c.start, 0}); out.add(new long[]{c.start + c.length, 0}); }
        for (Pro.Marker m : s.markers) out.add(new long[]{m.frame, 1});
        out.add(new long[]{playhead, 2});
        if (s.loopEnd > s.loopStart) { out.add(new long[]{s.loopStart, 3}); out.add(new long[]{s.loopEnd, 3}); }
        return out;
    }
    static final String[] KINDS = {"clip edge", "marker", "playhead", "loop edge"};

    /**
     * Snaps a clip being moved. Either edge may lock to a magnet target within magnetFrames; otherwise the start follows
     * the grid (absolute or relative). bypass = free placement (fine drag).
     */
    static Result move(Core.Session s, Pro.TempoMap m, Core.Clip c, long original, long proposed, long playhead, int division, long magnetFrames, boolean bypass) {
        proposed = Math.max(0, proposed);
        if (bypass) return new Result(proposed, -1, false, null);
        if (s.magnet && magnetFrames > 0) {
            long best = Long.MAX_VALUE, frame = 0, guide = -1; String what = null;
            for (long[] t : targets(s, c, playhead)) {
                long d1 = Math.abs(t[0] - proposed); if (d1 < best) { best = d1; frame = t[0]; guide = t[0]; what = KINDS[(int) t[1]]; }
                long d2 = Math.abs(t[0] - (proposed + c.length)); if (d2 < best && t[0] - c.length >= 0) { best = d2; frame = t[0] - c.length; guide = t[0]; what = KINDS[(int) t[1]]; }
            }
            if (best <= magnetFrames) return new Result(frame, guide, true, what);
        }
        long g = s.snapRelative ? relative(s, m, original, proposed, division) : grid(s, m, proposed, division);
        return new Result(g, -1, false, null);
    }

    /** Snaps a single position (seek, trim edge): magnet targets first, then the grid. */
    static Result point(Core.Session s, Pro.TempoMap m, Core.Clip ignore, long proposed, long playhead, int division, long magnetFrames, boolean bypass, boolean includePlayhead) {
        proposed = Math.max(0, proposed);
        if (bypass) return new Result(proposed, -1, false, null);
        if (s.magnet && magnetFrames > 0) {
            long best = Long.MAX_VALUE, frame = 0; String what = null;
            for (long[] t : targets(s, ignore, playhead)) { if (!includePlayhead && t[1] == 2) continue; long d = Math.abs(t[0] - proposed); if (d < best) { best = d; frame = t[0]; what = KINDS[(int) t[1]]; } }
            if (best <= magnetFrames) return new Result(frame, frame, true, what);
        }
        return new Result(grid(s, m, proposed, division), -1, false, null);
    }

    /** Moves a frame to the nearest zero crossing of the clip's audio within maxFrames (keeps trims click-free). */
    static long zeroCrossing(Core.Clip c, long timelineFrame, int maxFrames) {
        int local = (int) (timelineFrame - c.start) + c.offset; int best = local; double bestAbs = Double.MAX_VALUE;
        for (int d = 0; d <= maxFrames; d++) for (int sgn = -1; sgn <= 1; sgn += 2) {
            int i = local + sgn * d; if (i <= 0 || i >= c.source.frames()) continue;
            double a = c.source.sample(i - 1, 0) + c.source.sample(i - 1, 1), b = c.source.sample(i, 0) + c.source.sample(i, 1);
            if ((a <= 0 && b >= 0) || (a >= 0 && b <= 0)) { double v = Math.abs(b); if (v < bestAbs) { bestAbs = v; best = i; } }
            if (bestAbs < Double.MAX_VALUE && d > 0) return timelineFrame + (best - local);
        }
        return timelineFrame + (best - local);
    }

    /** Drag-trims the left edge to a new timeline frame (the audio stays in place). */
    static void trimStart(Core.Clip c, long origStart, int origOffset, int origLength, int origEnvOffset, long newStart) {
        long minStart = origStart - origOffset, maxStart = origStart + origLength - 1; newStart = Math.max(Math.max(0, minStart), Math.min(maxStart, newStart));
        int delta = (int) (newStart - origStart); c.start = newStart; c.offset = origOffset + delta; c.length = origLength - delta; c.envelopeOffset = Math.max(0, origEnvOffset + delta);
        c.envelopeLength = Math.max(c.envelopeLength, c.envelopeOffset + c.length);
    }
    /** Drag-trims the right edge to a new end frame. */
    static void trimEnd(Core.Clip c, long newEnd) {
        long maxEnd = c.start + (c.source.frames() - c.offset), minEnd = c.start + 1; newEnd = Math.max(minEnd, Math.min(Math.min(maxEnd, Core.MAX_FRAMES), newEnd));
        c.length = (int) (newEnd - c.start); c.envelopeLength = Math.max(c.envelopeLength, c.envelopeOffset + c.length);
    }

    /** Applies the drag mode after a clip was dropped on a track. */
    static void drop(Core.Session s, Core.Track t, Core.Clip c) {
        switch (s.dragMode) {
            case NO_OVERLAP: {
                long a = c.start, b = c.start + c.length; ArrayList<Core.Clip> keep = new ArrayList<>();
                for (Core.Clip o : t.clips) {
                    if (o == c) { keep.add(o); continue; } long oa = o.start, ob = o.start + o.length;
                    if (ob <= a || oa >= b) { keep.add(o); continue; }
                    if (oa < a) { Core.Clip left = o.copy(); if (Pro.trimTo(left, oa, a)) keep.add(left); }
                    if (ob > b) { Core.Clip right = o.copy(); if (Pro.trimTo(right, b, ob)) keep.add(right); }
                }
                keep.sort(Comparator.comparingLong(x -> x.start)); t.clips.clear(); t.clips.addAll(keep); break;
            }
            case XFADE: t.autoCrossfade = true; break;
            case SHUFFLE: {
                ArrayList<Core.Clip> order = new ArrayList<>(t.clips); order.remove(c); order.sort(Comparator.comparingLong(x -> x.start));
                for (Core.Clip o : order) if (o.start < c.start && o.start + o.length > c.start) c.start = o.start + o.length; // butt against the clip it landed on
                order.add(c); order.sort(Comparator.comparingLong((Core.Clip x) -> x.start).thenComparing(x -> x != c));
                long end = -1; for (Core.Clip o : order) { if (end >= 0 && o.start < end) o.start = Math.min(Core.MAX_FRAMES - o.length, end); end = Math.max(end, o.start + o.length); }
                break;
            }
            default: break;
        }
    }
}

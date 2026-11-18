package ug.audiocity.studio.arrange.model;

import static ug.audiocity.studio.arrange.model.MusicalTime.*;

/** Half-open region intervals. Pure integer operations shared by editing and scheduling. */
public final class RegionMath {
    private RegionMath() {}
    public static final class Window {
        public final long startTick, lengthTick, sourceOffsetTick;
        public Window(long start, long length, long offset) {
            startTick = tick(start); lengthTick = tick(length); sourceOffsetTick = tick(offset);
            end(start, length); end(offset, length);
        }
        public long endTick() { return end(startTick, lengthTick); }
        public boolean contains(long at) { return at >= startTick && at < endTick(); }
    }
    /** Restrict to an existing window; never mutates the underlying source. Empty intersections are legal. */
    public static Window trim(Window original, long start, long finish) {
        tick(start); tick(finish);
        if (finish < start || start < original.startTick || finish > original.endTick()) throw new IllegalArgumentException("Trim outside region");
        return new Window(start, finish - start, Math.addExact(original.sourceOffsetTick, start - original.startTick));
    }
    public static Window[] split(Window original, long at) {
        if (at <= original.startTick || at >= original.endTick()) throw new IllegalArgumentException("Split requires an interior tick");
        return new Window[]{trim(original, original.startTick, at), trim(original, at, original.endTick())};
    }
    /** Cycle position for nonnegative transport; modulo is relative to the cycle, not song zero. */
    public static long cycleTick(long at, long start, long finish) {
        tick(at); tick(start); tick(finish);
        if (finish <= start) throw new IllegalArgumentException("Empty cycle");
        return at < finish ? at : start + (at - start) % (finish - start);
    }
    /** Musical source position in a repeated region. */
    public static long loopSourceTick(long regionOffset, long elapsed, long sourceLength) {
        tick(regionOffset); tick(elapsed); tick(sourceLength);
        if (sourceLength == 0 || regionOffset >= sourceLength) throw new IllegalArgumentException("Invalid loop source");
        return (regionOffset + elapsed % sourceLength) % sourceLength;
    }
}

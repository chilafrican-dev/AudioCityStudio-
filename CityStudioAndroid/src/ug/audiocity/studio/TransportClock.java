package ug.audiocity.studio;

/**
 * The single master clock. The audio thread reports, after every block it plays, which frame of the output stream was
 * at the speaker at which System.nanoTime() moment (from the audio hardware's own timestamp when available). From that,
 * any moment in time - a finger touching a pad, a MIDI key, the first microphone sample - maps to the exact song frame
 * the musician was hearing. Nothing here depends on UI timers, animation frames or touch refresh rates.
 */
public final class TransportClock {
    private volatile long frame = -1, nanos, base, loopStart, loopEnd; private volatile boolean loop, hardware;

    /** Playback starts: output-stream frame 0 is song frame `base` (negative offsets cover the count-in). */
    public void start(long base) { this.base = base; frame = -1; }
    /** A fresh reading from the audio thread. */
    public void update(long streamFrame, long atNanos, boolean fromHardware, boolean looping, long cycleStart, long cycleEnd) {
        loop = looping; loopStart = cycleStart; loopEnd = cycleEnd; hardware = fromHardware; nanos = atNanos; frame = streamFrame;
    }
    public boolean running() { return frame >= 0; }
    public boolean exact() { return hardware; }
    /** Output-stream frame at the speaker at that moment (-1 before the first reading). Extrapolates in a straight line: the sample clock does not drift against itself. */
    public long streamAt(long atNanos) { long f = frame; if (f < 0) return -1; return f + Math.round((atNanos - nanos) * (double) Core.RATE / 1e9); }
    /** Song frame heard at that moment, counted straight on (not wrapped at the loop end). */
    public long heardAt(long atNanos, long fallback) { long s = streamAt(atNanos); return s < 0 ? fallback : base + s; }
    /** Song position heard at that moment, wrapped into the cycle when looping, exactly as the audio engine plays it. */
    public long songAt(long atNanos, long fallback) { long h = heardAt(atNanos, fallback); return loop ? ProEdit.loopPosition(h, loopStart, loopEnd) : h; }
    /** System.nanoTime() at which a straight-on song frame reaches the speaker. */
    public long nanosOf(long heardFrame) { long f = frame; if (f < 0) return System.nanoTime(); return nanos + Math.round((heardFrame - base - f) * 1e9 / Core.RATE); }

    /**
     * Where a recording belongs. firstHeard = song frame being heard when the first microphone frame was captured;
     * cursor = where recording was started. Returns {regionStart, sourceOffset}: audio from before the cursor is trimmed
     * (kept in the file), so the region begins exactly at the cursor; a microphone that started late is placed later.
     */
    public static long[] place(long cursor, long firstHeard, long recordedFrames) {
        long early = Math.max(0, Math.min(Math.max(0, recordedFrames - 1), cursor - firstHeard));
        return new long[]{early > 0 ? cursor : Math.max(cursor, firstHeard), early};
    }
}

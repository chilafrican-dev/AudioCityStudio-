package ug.audiocity.studio;

import java.util.*;

/**
 * Editing, analysis and mastering helpers: range cut/copy/paste/delete/insert-time, clip nudge/duplicate/normalise/
 * reverse/strip-silence, track bounce, spectrum and loop-range playhead maths, and a streaming 48 kHz → 44.1 kHz
 * resampler for distribution exports. Platform independent.
 */
final class ProEdit {
    private ProEdit() {}
    static final int RATE = Core.RATE;

    // ---------------------------------------------------------------- range editing

    /** A clip copied from a range; start is relative to the range start. */
    static final class Item { final String trackId; final int trackIndex; final Core.Clip clip; Item(String id, int index, Core.Clip c) { trackId = id; trackIndex = index; clip = c; } }

    /** Splits a clip at a timeline frame; returns the new right-hand clip (already added to the track) or null. */
    static Core.Clip split(Core.Track t, Core.Clip c, long at) {
        if (at <= c.start || at >= c.start + c.length) return null;
        Core.Clip right = c.copy(); long end = c.start + c.length; Pro.trimTo(right, at, end); Pro.trimTo(c, c.start, at);
        t.clips.add(t.clips.indexOf(c) + 1, right); return right;
    }

    static List<Item> copy(Core.Session s, List<Core.Track> tracks, long from, long to) {
        ArrayList<Item> out = new ArrayList<>();
        for (Core.Track t : tracks) for (Core.Clip c : t.clips) {
            if (c.start + c.length <= from || c.start >= to) continue;
            Core.Clip piece = c.copy(); if (!Pro.trimTo(piece, from, to)) continue; piece.start -= from; out.add(new Item(t.id, s.tracks.indexOf(t), piece));
        }
        return out;
    }

    /** Removes everything inside [from, to) on the tracks; with ripple, later material (and automation) moves left. */
    static void delete(Core.Session s, List<Core.Track> tracks, long from, long to, boolean ripple) {
        if (to <= from) return; long len = to - from;
        for (Core.Track t : tracks) {
            ArrayList<Core.Clip> keep = new ArrayList<>();
            for (Core.Clip c : t.clips) {
                long end = c.start + c.length;
                if (end <= from) { keep.add(c); continue; }
                if (c.start >= to) { if (ripple) c.start -= len; keep.add(c); continue; }
                if (c.start < from) { Core.Clip left = c.copy(); if (Pro.trimTo(left, c.start, from)) keep.add(left); }
                if (end > to) { Core.Clip right = c.copy(); if (Pro.trimTo(right, to, end)) { if (ripple) right.start -= len; keep.add(right); } }
            }
            t.clips.clear(); t.clips.addAll(keep);
            for (Pro.Lane l : t.automation.values()) cutLane(l, from, to, ripple);
        }
        if (ripple && tracks.size() == s.tracks.size()) {
            s.markers.removeIf(m -> m.frame >= from && m.frame < to); for (Pro.Marker m : s.markers) if (m.frame >= to) m.frame -= len;
            if (s.masterAutomation != null) cutLane(s.masterAutomation, from, to, true);
        }
    }

    static void cutLane(Pro.Lane l, long from, long to, boolean ripple) {
        if (l.size() == 0) return; double before = l.at(from), after = l.at(to); long len = to - from;
        ArrayList<long[]> f = new ArrayList<>(); ArrayList<Double> v = new ArrayList<>(); boolean removed = false;
        for (int i = 0; i < l.size(); i++) { long at = l.frame(i); if (at > from && at < to) { removed = true; continue; } f.add(new long[]{ripple && at >= to ? at - len : at}); v.add(l.value(i)); }
        if (removed || ripple) { f.add(new long[]{from}); v.add(before); f.add(new long[]{ripple ? from + 1 : to}); v.add(after); }
        long[] ff = new long[f.size()]; double[] vv = new double[ff.length]; for (int i = 0; i < ff.length; i++) { ff[i] = f.get(i)[0]; vv[i] = v.get(i); }
        l.set(ff, vv);
    }

    /** Pastes clips at a timeline frame onto their original tracks (by id, then by position). Returns the clips added. */
    static List<Core.Clip> paste(Core.Session s, List<Item> items, long at) {
        ArrayList<Core.Clip> added = new ArrayList<>();
        for (Item it : items) {
            Core.Track t = s.track(it.trackId); if (t == null && it.trackIndex >= 0 && it.trackIndex < s.tracks.size()) t = s.tracks.get(it.trackIndex); if (t == null) continue;
            Core.Clip c = it.clip.copy(); c.start = at + it.clip.start; if (c.start + c.length > Core.MAX_FRAMES) continue; t.clips.add(c); added.add(c);
        }
        return added;
    }

    /** Opens a gap of length frames at a frame on the tracks, splitting clips that cross it. */
    static void insertTime(Core.Session s, List<Core.Track> tracks, long at, long length) {
        if (length <= 0) return;
        for (Core.Track t : tracks) {
            for (Core.Clip c : new ArrayList<>(t.clips)) split(t, c, at);
            for (Core.Clip c : t.clips) if (c.start >= at) c.start = Math.min(Core.MAX_FRAMES - c.length, c.start + length);
            for (Pro.Lane l : t.automation.values()) shiftLane(l, at, length);
        }
        if (tracks.size() == s.tracks.size()) { for (Pro.Marker m : s.markers) if (m.frame >= at) m.frame += length; if (s.masterAutomation != null) shiftLane(s.masterAutomation, at, length); }
    }
    static void shiftLane(Pro.Lane l, long at, long length) { if (l.size() == 0) return; double v = l.at(at); long[] f = new long[l.size() + 1]; double[] vv = new double[f.length]; for (int i = 0; i < l.size(); i++) { f[i] = l.frame(i) >= at ? l.frame(i) + length : l.frame(i); vv[i] = l.value(i); } f[f.length - 1] = at; vv[f.length - 1] = v; l.set(f, vv); }

    // ---------------------------------------------------------------- clip tools

    static void nudge(Core.Clip c, long frames) { c.start = Math.max(0, Math.min(Core.MAX_FRAMES - c.length, c.start + frames)); }

    static List<Core.Clip> duplicate(Core.Track t, Core.Clip c, int times) {
        ArrayList<Core.Clip> out = new ArrayList<>(); long at = c.start + c.length;
        for (int i = 0; i < times && at + c.length <= Core.MAX_FRAMES; i++) { Core.Clip d = c.copy(); d.start = at; t.clips.add(d); out.add(d); at += c.length; }
        return out;
    }

    /** Peak of the clip as it plays (including gain and fades). */
    static double peak(Core.Clip c) {
        double p = 0; int stride = c.length > RATE * 120 ? 2 : 1;
        for (int i = 0; i < c.length; i += stride) { int src = c.offset + i; if (src >= c.source.frames()) break; double e = Core.clipEnvelope(c, i); p = Math.max(p, Math.max(Math.abs(c.source.sample(src, 0)), Math.abs(c.source.sample(src, 1))) * e); }
        return p;
    }
    /** Sets clip gain so its peak reaches targetDb (clip gain is limited to ±24 dB). Returns the change in dB. */
    static double normalise(Core.Clip c, double targetDb) {
        double p = peak(c); if (p < 1e-6) return 0;
        double g = c.gain * Pro.gain(targetDb) / p, lo = Pro.gain(-24), hi = Pro.gain(24); g = Math.max(lo, Math.min(hi, g));
        double change = Pro.db(g / c.gain); c.gain = g; return change;
    }

    /** The clip's source window, reversed (interleaved stereo). */
    static float[] reversed(Core.Clip c) {
        float[] out = new float[c.length * 2];
        for (int i = 0; i < c.length; i++) { int src = c.offset + c.length - 1 - i; if (src < 0 || src >= c.source.frames()) continue; out[2 * i] = c.source.sample(src, 0); out[2 * i + 1] = c.source.sample(src, 1); }
        return out;
    }

    /**
     * Splits a clip into the parts that are above a threshold, dropping silences longer than minSilence frames; each
     * part keeps pad frames either side. Returns the parts (not yet placed on a track).
     */
    static List<Core.Clip> stripSilence(Core.Clip c, double thresholdDb, int minSilence, int pad) {
        double th = Pro.gain(thresholdDb); int win = RATE / 100; int blocks = (c.length + win - 1) / win; boolean[] loud = new boolean[blocks];
        for (int b = 0; b < blocks; b++) { double peak = 0; for (int i = b * win; i < Math.min(c.length, (b + 1) * win); i++) { int src = c.offset + i; if (src >= c.source.frames()) break; peak = Math.max(peak, Math.max(Math.abs(c.source.sample(src, 0)), Math.abs(c.source.sample(src, 1))) * c.gain); } loud[b] = peak >= th; }
        ArrayList<long[]> regions = new ArrayList<>(); int b = 0;
        while (b < blocks) { if (!loud[b]) { b++; continue; } int s = b; while (b < blocks && loud[b]) b++; regions.add(new long[]{(long) s * win, Math.min(c.length, (long) b * win)}); }
        ArrayList<long[]> merged = new ArrayList<>();
        for (long[] r : regions) { if (!merged.isEmpty() && r[0] - merged.get(merged.size() - 1)[1] < minSilence) merged.get(merged.size() - 1)[1] = r[1]; else merged.add(r); }
        ArrayList<Core.Clip> out = new ArrayList<>();
        for (long[] r : merged) { Core.Clip p = c.copy(); long from = c.start + Math.max(0, r[0] - pad), to = c.start + Math.min(c.length, r[1] + pad); if (Pro.trimTo(p, from, to)) { if (p.fadeIn == 0) p.fadeIn = Math.min(240, p.length / 4); if (p.fadeOut == 0) p.fadeOut = Math.min(480, p.length / 4); p.envelopeOffset = 0; p.envelopeLength = p.length; out.add(p); } }
        return out;
    }

    /** Renders one track (with its FX, inserts and any buses it feeds) over [from, to) at unity master, for bounce-in-place. */
    static float[] bounce(Core.Session s, int trackIndex, long from, long to) {
        Core.Session stem = Pro.stem(s, trackIndex); stem.tracks.get(trackIndex).mute = false; int frames = (int) Math.max(0, Math.min(Core.MAX_FRAMES, to) - from);
        float[] out = new float[frames * 2]; Core.Renderer r = new Core.Renderer(stem); r.meterLoudness = false; float[] block = new float[1024];
        for (int p = 0; p < frames; p += 512) { int n = Math.min(512, frames - p); r.render(from + p, n, block, false); System.arraycopy(block, 0, out, p * 2, n * 2); }
        return out;
    }

    // ---------------------------------------------------------------- analysis

    /** Log-spaced magnitude spectrum (dBFS) of the most recent size samples in a ring. bands values from 20 Hz to 20 kHz. */
    static float[] spectrum(float[] ring, int writePos, int size, int bands) {
        double[] re = new double[size], im = new double[size];
        for (int i = 0; i < size; i++) { double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (size - 1)); re[i] = ring[(writePos - size + i + ring.length * 4) % ring.length] * w; }
        Pro.fft(re, im, false); float[] out = new float[bands]; double norm = 4.0 / size; // Hann coherent gain 0.5, one-sided ×2
        for (int b = 0; b < bands; b++) {
            double lo = 20 * Math.pow(1000, b / (double) bands), hi = 20 * Math.pow(1000, (b + 1) / (double) bands); int k0 = (int) Math.floor(lo * size / RATE), k1 = Math.max(k0 + 1, (int) Math.ceil(hi * size / RATE));
            double m = 0; for (int k = k0; k < k1 && k < size / 2; k++) m = Math.max(m, Math.hypot(re[k], im[k]) * norm);
            out[b] = (float) Pro.db(m);
        }
        return out;
    }

    /** Playhead position for a loop range when heard frames have been played starting from start (looping at end). */
    /**
     * Keeps everything at the same bar and beat when the tempo map or time signature changes. MIDI regions follow the
     * tempo: every note keeps its beat position and musical length. Audio regions are time-based: they move to stay on
     * their bar and beat but their audio keeps its recorded speed. The loop range and markers follow too.
     */
    static void followTempoMap(Core.Session s, Pro.TempoMap before, Pro.TempoMap after) {
        for (Core.Track t : s.tracks) for (Core.Clip c : t.clips) {
            long newStart = Math.max(0, after.frameAt(before.beatAt(c.start)));
            if (c.notes != null) {
                long origin = c.start - c.offset, newOrigin = after.frameAt(before.beatAt(origin));
                for (Pro.Note n : c.notes) {
                    long a = after.frameAt(before.beatAt(origin + n.start)), b = after.frameAt(before.beatAt(origin + n.end()));
                    n.start = Math.max(0, a - newOrigin); n.length = Math.max(1, b - a);
                }
                long end = after.frameAt(before.beatAt(c.start + c.length));
                c.offset = (int) Math.max(0, newStart - newOrigin); c.envelopeOffset = c.offset; c.length = (int) Math.max(1, Math.min(Core.MAX_FRAMES - newStart, end - newStart));
                c.start = newStart; ProMidi.refresh(c);
            } else c.start = Math.min(newStart, Core.MAX_FRAMES - c.length);
        }
        if (s.loopEnd > s.loopStart) { s.loopStart = Math.max(0, after.frameAt(before.beatAt(s.loopStart))); s.loopEnd = Math.max(s.loopStart + 1, after.frameAt(before.beatAt(s.loopEnd))); }
        for (Pro.Marker m : s.markers) m.frame = Math.max(0, after.frameAt(before.beatAt(m.frame)));
    }

    /**
     * Quantizes a MIDI region on the song's tempo map: every note start moves toward the nearest grid line (division notes
     * per beat: 1 = 1/4, 2 = 1/8, 4 = 1/16, 8 = 1/32) by the given strength (1 = fully, 0.5 = halfway, keeping some feel).
     * Lengths are kept. Returns how many notes moved.
     */
    static int quantizeClip(Core.Session s, Core.Clip c, int division, double strength) { Quantizer.Settings q = Quantizer.Settings.division(division); q.strength = strength * 100; return quantizeClip(s, c, q); }
    /** Quantizes a MIDI region with the full settings, on the song's tempo map. Returns how many notes changed. */
    static int quantizeClip(Core.Session s, Core.Clip c, Quantizer.Settings q) {
        if (c.notes == null || q == null || !q.on()) return 0; long origin = c.start - c.offset; List<Long> before = new ArrayList<>(); for (Pro.Note n : c.notes) before.add(n.start * 1000 + n.velocity);
        Quantizer.apply(q, c.notes, origin, new Pro.TempoMap(s)); int moved = 0; List<Long> after = new ArrayList<>(); for (Pro.Note n : c.notes) after.add(n.start * 1000 + n.velocity);
        Collections.sort(before); Collections.sort(after); for (int i = 0; i < before.size(); i++) if (!before.get(i).equals(after.get(i))) moved++;
        ProMidi.refresh(c); return moved;
    }

    static long loopPosition(long heard, long loopStart, long loopEnd) {
        if (loopEnd <= loopStart || heard < loopEnd) return heard; return loopStart + (heard - loopEnd) % (loopEnd - loopStart);
    }

    // ---------------------------------------------------------------- resampling for 44.1 kHz delivery

    /** Streaming polyphase windowed-sinc resampler for 48 kHz → 44.1 kHz (147/160), passband to 20 kHz, ≈ 100 dB stop band. */
    static final class Resampler {
        interface Out { void sample(double l, double r) throws java.io.IOException; }
        static final int TAPS = 96; final int up, down; final double[][] h; final double[] hl = new double[TAPS], hr = new double[TAPS]; long in, out, limit = Long.MAX_VALUE; final Out sink;
        Resampler(int fromRate, int toRate, Out sink) {
            int g = gcd(fromRate, toRate); up = toRate / g; down = fromRate / g; this.sink = sink; h = new double[up][TAPS];
            double fc = Math.min(20000.0, toRate * 0.4535) / fromRate, beta = 10.0;
            for (int p = 0; p < up; p++) { double sum = 0; for (int k = 0; k < TAPS; k++) { double d = (k - TAPS / 2 + 1) - p / (double) up; double x = 2 * fc * d; double sinc = Math.abs(x) < 1e-12 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x); double r = d / (TAPS / 2.0); double win = Math.abs(r) >= 1 ? 0 : bessel(beta * Math.sqrt(1 - r * r)) / bessel(beta); h[p][k] = 2 * fc * sinc * win; sum += h[p][k]; } for (int k = 0; k < TAPS; k++) h[p][k] /= sum; }
        }
        static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }
        static double bessel(double x) { double s = 1, t = 1; for (int k = 1; k < 50; k++) { t *= (x / (2 * k)) * (x / (2 * k)); s += t; } return s; }
        /** Feeds one input frame; emits every output frame whose filter window is complete. */
        void push(double l, double r) throws java.io.IOException {
            hl[(int) (in % TAPS)] = l; hr[(int) (in % TAPS)] = r; in++;
            while (out < limit) { long pos = out * down; long i = pos / up; int phase = (int) (pos % up); if (i + TAPS / 2 > in - 1) break; emit(i, phase); out++; }
        }
        void emit(long i, int phase) throws java.io.IOException {
            double yl = 0, yr = 0; double[] k = h[phase]; long first = i - TAPS / 2 + 1;
            for (int t = 0; t < TAPS; t++) { long idx = first + t; if (idx < 0 || idx >= in || in - idx > TAPS) continue; int slot = (int) (idx % TAPS); yl += hl[slot] * k[t]; yr += hr[slot] * k[t]; }
            sink.sample(yl, yr);
        }
        /** Flushes so exactly round(totalIn · to/from) frames have been produced. */
        void finish(long totalIn) throws java.io.IOException { long want = Math.round(totalIn * (double) up / down); limit = want; while (out < want) { push(0, 0); } }
        static long outputFrames(long inFrames, int from, int to) { int g = gcd(from, to); return Math.round(inFrames * (double) (to / g) / (from / g)); }
    }
}

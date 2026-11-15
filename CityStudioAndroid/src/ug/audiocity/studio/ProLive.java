package ug.audiocity.studio;

import java.util.*;

/**
 * Performance features: audio quantize (onset slicing), MIDI controller mapping and the clip launcher.
 * Platform independent; the launcher's playback lives in Core.Renderer.
 */
final class ProLive {
    private ProLive() {}
    static final int RATE = Core.RATE, SLOTS = 8;

    // ---------------------------------------------------------------- audio quantize

    /** Onsets (clip-local frames) found from rises in a 5 ms envelope. sensitivity 0..1 (higher finds softer hits). */
    static List<Integer> onsets(Core.Clip c, double sensitivity) {
        int hop = RATE / 200, n = c.length / hop; double[] env = new double[n];
        for (int b = 0; b < n; b++) { double s = 0; for (int i = b * hop; i < (b + 1) * hop; i++) { int src = c.offset + i; if (src >= c.source.frames()) break; double l = c.source.sample(src, 0), r = c.source.sample(src, 1); s += l * l + r * r; } env[b] = Math.sqrt(s / (2 * hop)); }
        double ratio = 6 - 4.5 * Math.max(0, Math.min(1, sensitivity)), floor = Pro.gain(-50 + 10 * (1 - sensitivity));
        ArrayList<Integer> out = new ArrayList<>(); int last = -1000; int gap = RATE / 20 / hop;
        for (int b = 1; b < n; b++) {
            double before = 0; int k = 0; for (int j = Math.max(0, b - 10); j < b; j++) { before += env[j]; k++; } before = k == 0 ? 0 : before / k;
            if (env[b] > floor && env[b] > before * ratio + 1e-6 && b - last >= gap) {
                int start = (b - 1) * hop, end = Math.min(c.length, (b + 1) * hop), best = start; double peak = 0; // refine to the first sample that crosses half the block peak
                for (int i = start; i < end; i++) { int src = c.offset + i; if (src >= c.source.frames()) break; peak = Math.max(peak, Math.abs(c.source.sample(src, 0))); }
                for (int i = start; i < end; i++) { int src = c.offset + i; if (src >= c.source.frames()) break; if (Math.abs(c.source.sample(src, 0)) >= peak * .5) { best = i; break; } }
                out.add(Math.max(0, best - RATE / 1000)); last = b;
            }
        }
        return out;
    }

    /**
     * Slices a clip at its onsets and moves each slice toward the grid (division = steps per beat, strength 0..1).
     * Slices overlap by the crossfade and the track switches to overlap crossfades. Returns the new slices.
     */
    static List<Core.Clip> quantizeAudio(Core.Track t, Core.Clip c, Pro.TempoMap map, int division, double strength, double sensitivity, int crossfade) {
        List<Integer> on = onsets(c, sensitivity); if (on.isEmpty()) return Collections.emptyList();
        ArrayList<Integer> cuts = new ArrayList<>(); if (on.get(0) > 0) cuts.add(0); cuts.addAll(on);
        int k = cuts.size(); long[] newStart = new long[k];
        for (int i = 0; i < k; i++) { long abs = c.start + cuts.get(i); boolean isOnset = !(i == 0 && cuts.get(0) == 0 && on.get(0) != 0); long target = isOnset ? map.snap(abs, division) : abs; newStart[i] = Math.max(0, Math.round(abs + (target - abs) * strength)); }
        ArrayList<Core.Clip> slices = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            int from = cuts.get(i); long end = i + 1 < k ? newStart[i + 1] + crossfade : newStart[i] + (c.length - from);
            long want = Math.max(1, end - newStart[i]); long available = (long) c.source.frames() - (c.offset + from); if (i + 1 < k) available = Math.min(available, cuts.get(i + 1) - from); // never play into the next hit
            Core.Clip s = c.copy(); s.start = newStart[i]; s.offset = c.offset + from; s.length = (int) Math.max(1, Math.min(want, available));
            s.envelopeOffset = 0; s.envelopeLength = s.length; s.fadeIn = i == 0 ? Math.min(c.fadeIn, s.length) : 0; s.fadeOut = i == k - 1 ? Math.min(c.fadeOut, s.length) : s.length < want ? Math.min(240, s.length / 4) : 0; // a slice that ends before the next one starts fades out instead of clicking s.name = c.name + " " + (i + 1);
            if (s.start + s.length > Core.MAX_FRAMES) s.length = (int) Math.max(1, Core.MAX_FRAMES - s.start);
            slices.add(s);
        }
        int at = t.clips.indexOf(c); t.clips.remove(c); t.clips.addAll(Math.max(0, Math.min(at, t.clips.size())), slices); t.autoCrossfade = true;
        return slices;
    }

    // ---------------------------------------------------------------- MIDI controller mapping

    /** One hardware control → one app target. note = true for pads/keys (note-on), else a CC number. channel −1 = any. */
    static final class Mapping {
        final int channel, number; final boolean note; final String target;
        Mapping(int channel, int number, boolean note, String target) { this.channel = channel; this.number = number; this.note = note; this.target = target; }
        boolean matches(int status, int a) { int type = status & 0xF0, ch = status & 15; if (channel >= 0 && ch != channel) return false; return note ? type == 0x90 && a == number : type == 0xB0 && a == number; }
        String label() { return (note ? "Note " + number : "CC " + number) + (channel >= 0 ? " ch " + (channel + 1) : ""); }
    }

    /** Transport actions a mapping can ask the UI to perform. */
    static final String PLAY = "transport:play", RECORD = "transport:record", LOOP = "transport:loop", MARKER = "transport:marker", UNDO = "transport:undo";

    /** Applies a controller value (0–127) to its target. Returns a transport action for the UI, or null. */
    static String apply(Core.Session s, Mapping m, int value) {
        double v = Math.max(0, Math.min(127, value)) / 127.0; String[] p = m.target.split(":", 3);
        if (p[0].equals("transport")) return m.note || value >= 64 ? m.target : null;
        if (p[0].equals("master") && p.length > 1 && p[1].equals("volume")) { s.master = Core.faderGain(v); return null; }
        if (p[0].equals("track") && p.length == 3) {
            Core.Track t = s.track(p[1]); if (t == null) t = s.bus(p[1]); if (t == null) return null;
            switch (p[2]) {
                case "volume": t.volume = Core.faderGain(v); break;
                case "pan": t.pan = v * 2 - 1; break;
                case "mute": if (m.note || value >= 64) t.mute = m.note ? !t.mute : value >= 64; break;
                case "solo": if (m.note || value >= 64) t.solo = m.note ? !t.solo : value >= 64; break;
                default:
                    if (p[2].startsWith("send:")) { for (Pro.Send x : t.sends) if (x.bus.equals(p[2].substring(5))) x.level = v * v * 2; }
                    else if (p[2].startsWith("insert:")) { String[] q = p[2].split(":"); if (q.length == 3) for (Pro.Insert i : t.inserts) if (i.id.equals(q[1])) { int idx = Integer.parseInt(q[2]); Pro.Kind k = i.info(); if (idx < 0 || idx >= k.names.length) break; double lo = k.min[idx], hi = k.max[idx]; i.set(idx, k.units[idx].equals("Hz") && lo > 0 ? lo * Math.pow(hi / lo, v) : lo + (hi - lo) * v); i.touch(); } }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- clip launcher

    /** The next bar line at or after a frame (launches are quantised to bars). */
    static long nextBar(Pro.TempoMap map, long frame) { int bpb = map.beatsPerBar(); double b = map.beatAt(frame); double bar = Math.ceil(b / bpb - 1e-9) * bpb; return map.frameAt(bar); }

    /** Queues a slot to start at the next bar (or immediately when stopped). */
    static void launch(Core.Session s, Core.Track t, int slot, long position, boolean playing) {
        if (slot < 0 || slot >= t.slots.size() || t.slots.get(slot) == null) return;
        t.launched = t.slots.get(slot); t.launchSlot = slot; t.launchAt = playing ? nextBar(new Pro.TempoMap(s), position) : position; t.launchStop = Long.MAX_VALUE;
    }
    static void stop(Core.Session s, Core.Track t, long position, boolean playing) { if (t.launched == null) return; t.launchStop = playing ? nextBar(new Pro.TempoMap(s), position) : position; if (!playing) t.launched = null; }
    /** Launches one column across all tracks; tracks with an empty slot in that column stop. */
    static void scene(Core.Session s, int slot, long position, boolean playing) { for (Core.Track t : s.tracks) { if (slot < t.slots.size() && t.slots.get(slot) != null) launch(s, t, slot, position, playing); else stop(s, t, position, playing); } }
    static void stopAll(Core.Session s, long position, boolean playing) { for (Core.Track t : s.tracks) stop(s, t, position, playing); }
    static void put(Core.Track t, int slot, Core.Clip c) { while (t.slots.size() <= slot) t.slots.add(null); Core.Clip copy = c.copy(); copy.start = 0; t.slots.set(slot, copy); }
}

package ug.audiocity.studio;

import java.io.IOException;
import java.util.*;

/**
 * Note-by-note vocal editing (in the spirit of Flex Pitch / Melodyne): the dry vocal is tracked with the Vocal Tune
 * YIN tracker, split into notes, and each note can be moved in pitch, have its slow drift and its vibrato scaled,
 * its level changed and its formants shifted. Rendering uses the same epoch-aligned TD-PSOLA as Vocal Tune, with
 * per-note formant scaling of the grains. Always rendered from the preserved dry recording. Platform independent.
 */
final class ProVocal {
    private ProVocal() {}
    static final int HOP = PitchCorrector.HOP, DOWN = PitchCorrector.DOWN;

    /** Pitch track of a region: Hz per 10 ms hop (0 = unvoiced) plus the low-passed analysis signal the marker finder needs. */
    static final class Analysis {
        final PitchCorrector.Track track; final float[] down; final int frames; final double[] midi;
        Analysis(PitchCorrector.Track t, float[] d, int f) { track = t; down = d; frames = f; midi = new double[t.hz.length]; for (int i = 0; i < midi.length; i++) midi[i] = t.hz[i] > 0 ? 69 + 12 * Math.log(t.hz[i] / 440) / Math.log(2) : Double.NaN; }
        int hops() { return midi.length; }
    }

    /** One sung note and its edits. pitch is the target centre in MIDI semitones (starts at the detected centre). */
    static final class Note {
        int start, end; double centre, pitch, drift = 1, vibrato = 1, gainDb = 0, formant = 0;
        /** Where this note's end lands in time after timing edits (hops). Only differs from end when the next note touches it. */
        double outEnd;
        Note(int s, int e, double c) { start = s; end = e; centre = c; pitch = c; outEnd = e; }
        Note copy() { Note n = new Note(start, end, centre); n.outEnd = outEnd; n.pitch = pitch; n.drift = drift; n.vibrato = vibrato; n.gainDb = gainDb; n.formant = formant; return n; }
        boolean edited() { return Math.abs(pitch - centre) > 1e-6 || drift != 1 || vibrato != 1 || gainDb != 0 || formant != 0 || outEnd != end; }
        long startFrame() { return (long) start * HOP; } long endFrame() { return (long) end * HOP; }
    }

    static Analysis analyse(Core.Source region, double minHz, double maxHz) throws IOException {
        int frames = region.frames(); float[] down = new float[(frames + DOWN - 1) / DOWN + 640]; double low = 0, low2 = 0, alpha = 1 - Math.exp(-2 * Math.PI * 1500 / Core.RATE);
        for (int i = 0; i < frames; i++) { low += alpha * (PitchCorrector.mono(region, i) - low); low2 += alpha * (low - low2); if (i % DOWN == 0) down[320 + i / DOWN] = (float) low2; }
        PitchCorrector.Config c = new PitchCorrector.Config(); c.minHz = minHz; c.maxHz = maxHz; c.strength = 0;
        PitchCorrector.Track t = PitchCorrector.analyse(region, c, down, new PitchCorrector.Report());
        bridge(t.hz, 3);
        return new Analysis(t, down, frames);
    }

    /** Fills tracker dropouts of up to max hops inside a sung phrase (log-interpolated) so legato lines stay one phrase. */
    static void bridge(float[] hz, int max) {
        for (int i = 1; i < hz.length; i++) if (hz[i] == 0 && hz[i - 1] > 0) {
            int j = i; while (j < hz.length && hz[j] == 0) j++;
            if (j < hz.length && j - i <= max) { double a = Math.log(hz[i - 1]), b = Math.log(hz[j]); for (int k = i; k < j; k++) hz[k] = (float) Math.exp(a + (b - a) * (k - i + 1) / (j - i + 1)); }
            i = j;
        }
    }
    /** Splits voiced runs into notes where the pitch settles somewhere new for at least 50 ms; very short notes merge into a neighbour. */
    static List<Note> segment(Analysis a) {
        ArrayList<Note> out = new ArrayList<>(); int n = a.hops(), i = 0;
        while (i < n) {
            if (Double.isNaN(a.midi[i])) { i++; continue; }
            int s = i; while (i < n && !Double.isNaN(a.midi[i])) i++; int e = i; // voiced run [s, e)
            int noteStart = s; ArrayList<Double> win = new ArrayList<>();
            for (int h = s; h < e; h++) {
                win.add(a.midi[h]); double med = median(win);
                int away = 0; for (int k = h; k < Math.min(e, h + 5); k++) if (Math.abs(a.midi[k] - med) > .7) away++; else break;
                if (away >= 5 && h - noteStart >= 4) { out.add(new Note(noteStart, h, median(sub(a.midi, noteStart, h)))); noteStart = h; win.clear(); }
            }
            out.add(new Note(noteStart, e, median(sub(a.midi, noteStart, e))));
        }
        for (int k = 0; k < out.size(); k++) if (out.get(k).end - out.get(k).start < 4 && out.size() > 1) {
            Note sh = out.get(k); Note prev = k > 0 ? out.get(k - 1) : null, next = k + 1 < out.size() ? out.get(k + 1) : null;
            if (prev != null && prev.end == sh.start) { prev.end = sh.end; out.remove(k--); } else if (next != null && next.start == sh.end) { next.start = sh.start; out.remove(k--); }
        }
        for (Note x : out) { x.centre = median(sub(a.midi, x.start, x.end)); x.pitch = x.centre; x.outEnd = x.end; }
        return out;
    }
    static ArrayList<Double> sub(double[] m, int from, int to) { ArrayList<Double> l = new ArrayList<>(); for (int i = from; i < to; i++) if (!Double.isNaN(m[i])) l.add(m[i]); return l; }
    static double median(List<Double> v) { if (v.isEmpty()) return Double.NaN; ArrayList<Double> c = new ArrayList<>(v); Collections.sort(c); int n = c.size(); return n % 2 == 1 ? c.get(n / 2) : (c.get(n / 2 - 1) + c.get(n / 2)) / 2; }

    /** Moves a note's centre to the nearest note of the key/scale (scale 0 chromatic, 1 major, 2 minor). */
    static void snap(Note n, int key, int scale) { n.pitch = PitchCorrector.nearest((int) Math.round(n.centre), key, scale); }
    /** Splits a note at a hop; the second half gets a copy of the edits. Returns the new note or null. */
    static Note split(List<Note> notes, Note n, int hop, Analysis a) {
        if (hop <= n.start + 1 || hop >= n.end - 1) return null; int i = notes.indexOf(n); double os = outStart(notes, i), oe = outEnd(notes, i); Note right = n.copy(); right.start = hop; n.end = hop; n.outEnd = os + (oe - os) * (hop - n.start) / (right.end - n.start);
        n.centre = median(sub(a.midi, n.start, n.end)); right.centre = median(sub(a.midi, right.start, right.end)); n.pitch = n.centre + (n.pitch - n.centre); right.pitch = right.centre + (right.pitch - right.centre);
        notes.add(notes.indexOf(n) + 1, right); return right;
    }
    /** Joins a note with the next one when they touch. */
    static boolean join(List<Note> notes, Note n, Analysis a) {
        int i = notes.indexOf(n); if (i < 0 || i + 1 >= notes.size()) return false; Note next = notes.get(i + 1); if (next.start - n.end > 3) return false;
        double offset = n.pitch - n.centre; n.end = next.end; n.outEnd = next.outEnd; n.centre = median(sub(a.midi, n.start, n.end)); n.pitch = n.centre + offset; notes.remove(next); return true;
    }

    /** Timing inside a phrase: notes that touch share a boundary, and moving it stretches one and shortens the other. */
    static boolean touches(Note a, Note b) { return b.start == a.end; }
    static double outStart(List<Note> l, int i) { return i > 0 && touches(l.get(i - 1), l.get(i)) ? l.get(i - 1).outEnd : l.get(i).start; }
    static double outEnd(List<Note> l, int i) { return i + 1 < l.size() && touches(l.get(i), l.get(i + 1)) ? l.get(i).outEnd : l.get(i).end; }
    static final double MIN_HOPS = 3;
    /** Moves the boundary after note i by hops (only possible when the next note touches it). */
    static boolean moveEnd(List<Note> l, int i, double hops) {
        if (i < 0 || i + 1 >= l.size() || !touches(l.get(i), l.get(i + 1))) return false;
        double lo = outStart(l, i) + MIN_HOPS, hi = outEnd(l, i + 1) - MIN_HOPS; if (lo > hi) return false;
        Note n = l.get(i); n.outEnd = clamp(n.outEnd + hops, lo, hi); if (Math.abs(n.outEnd - n.end) < 1e-9) n.outEnd = n.end; return true;
    }
    /** Moves a whole note earlier or later; touching neighbours stretch to fill. Returns how far it actually moved. */
    static double shift(List<Note> l, int i, double hops) {
        boolean left = i > 0 && touches(l.get(i - 1), l.get(i)), right = i + 1 < l.size() && touches(l.get(i), l.get(i + 1));
        if (!left || !right) return 0; // a phrase's first and last notes stay anchored; move the clip for those
        double lo = outStart(l, i - 1) + MIN_HOPS - outStart(l, i), hi = outEnd(l, i + 1) - MIN_HOPS - outEnd(l, i); double d = clamp(hops, lo, hi);
        l.get(i - 1).outEnd += d; l.get(i).outEnd += d; for (Note n : new Note[]{l.get(i - 1), l.get(i)}) if (Math.abs(n.outEnd - n.end) < 1e-9) n.outEnd = n.end; return d;
    }
    /** Output-to-input time map (frames) built from the timing edits; identity outside edited phrases. */
    static final class TimeMap {
        final double[] in, out;
        TimeMap(List<Note> notes, int frames) {
            TreeMap<Double, Double> pts = new TreeMap<>(); pts.put(0.0, 0.0); pts.put((double) frames, (double) frames);
            for (int i = 0; i < notes.size(); i++) { Note n = notes.get(i); pts.put((double) n.start * HOP, outStart(notes, i) * HOP); pts.put((double) n.end * HOP, outEnd(notes, i) * HOP); }
            in = new double[pts.size()]; out = new double[pts.size()]; int k = 0; for (Map.Entry<Double, Double> e : pts.entrySet()) { in[k] = e.getKey(); out[k] = Math.max(k > 0 ? out[k - 1] : 0, e.getValue()); k++; }
        }
        double input(double o) {
            if (o <= out[0]) return o; int lo = 0, hi = out.length - 1; if (o >= out[hi]) return o;
            while (hi - lo > 1) { int m = (lo + hi) >>> 1; if (out[m] <= o) lo = m; else hi = m; }
            double span = out[hi] - out[lo]; return span <= 0 ? in[lo] : in[lo] + (o - out[lo]) / span * (in[hi] - in[lo]);
        }
        boolean moved(int from, int to) { for (int k = 0; k < in.length; k++) if (in[k] > from && in[k] < to && Math.abs(in[k] - out[k]) > .5) return true; return false; }
    }

    /** Per-hop pitch shift (semitones), formant ratio and gain from the note edits. */
    static final class Curves { final float[] shift, formant, gain; Curves(int n) { shift = new float[n]; formant = new float[n]; gain = new float[n]; Arrays.fill(formant, 1); Arrays.fill(gain, 1); } }
    static Curves curves(Analysis a, List<Note> notes) {
        int n = a.hops(); Curves c = new Curves(n);
        for (Note x : notes) {
            int len = x.end - x.start; if (len <= 0) continue; double[] dev = new double[len], slow = new double[len];
            for (int i = 0; i < len; i++) { double m = a.midi[x.start + i]; dev[i] = Double.isNaN(m) ? 0 : m - x.centre; }
            int w = 7; for (int i = 0; i < len; i++) { double s = 0; int k = 0; for (int j = Math.max(0, i - w); j <= Math.min(len - 1, i + w); j++) { s += dev[j]; k++; } slow[i] = s / k; } // ~150 ms average = drift; the rest is vibrato
            for (int i = 0; i < len; i++) {
                int h = x.start + i; double ramp = Math.min(1, Math.min(i + 1, len - i) / 3.0); // ease edits in and out over 30 ms
                double m = a.midi[h];
                if (!Double.isNaN(m)) { double desired = x.pitch + x.drift * slow[i] + x.vibrato * (dev[i] - slow[i]); c.shift[h] = (float) ((desired - m) * ramp); }
                c.formant[h] = (float) Math.pow(2, x.formant * ramp / 12); c.gain[h] = (float) Math.pow(10, x.gainDb * ramp / 20);
            }
        }
        return c;
    }

    /** Renders the region with the note edits. Unedited regions pass through unchanged. */
    static void render(Core.Source source, Analysis a, List<Note> notes, TimeStretch.Sink sink) throws IOException {
        int frames = source.frames(); Curves cv = curves(a, notes); PitchCorrector.Track track = a.track; TimeMap map = new TimeMap(notes, frames);
        for (int i = 0; i < cv.shift.length; i++) track.shift[i] = cv.shift[i];
        final int RING = PitchCorrector.RING, MAX = PitchCorrector.MAX_PERIOD; int written = 0; double[] left = new double[RING], right = new double[RING], weights = new double[RING];
        for (int f = 0; f < track.hz.length;) {
            if (track.hz[f] == 0) { f++; continue; }
            int begin = f; while (f < track.hz.length && track.hz[f] > 0) f++;
            int from = Math.min(frames, begin * HOP), to = Math.min(frames, f * HOP); if (to - from < HOP * 3) continue;
            boolean active = map.moved(from, to); for (int k = begin; k < f; k++) if (Math.abs(cv.shift[k]) > .01 || Math.abs(cv.formant[k] - 1) > .001) { active = true; break; }
            if (!active) continue;
            double[] epochs = PitchCorrector.marks(source, track, a.down, from, to); if (epochs.length < 4) continue;
            int index = 0; double output = epochs[0];
            while (output < to) {
                double at = map.input(output); // where in the original this output moment comes from
                while (index + 1 < epochs.length && Math.abs(epochs[index + 1] - at) < Math.abs(epochs[index] - at)) index++;
                double input = epochs[index], lr = index > 0 ? input - epochs[index - 1] : epochs[1] - input, rr = index + 1 < epochs.length ? epochs[index + 1] - input : lr;
                lr = Math.max(40, Math.min(MAX, lr)); rr = Math.max(40, Math.min(MAX, rr));
                int safe = Math.max(written, (int) Math.floor(output - MAX * 2 - 2)); while (written < safe) emit(source, cv, map, sink, left, right, weights, written++, from, to);
                double fr = PitchCorrector.interpolate(cv.formant, at); // >1 raises formants (grains read faster), <1 lowers them
                int start = (int) Math.ceil(output - lr), end = (int) Math.floor(output + rr);
                for (int dst = Math.max(written, Math.max(0, start)); dst <= end && dst < frames; dst++) {
                    double delta = dst - output, src = input + delta * fr; if (src < from || src >= to) continue;
                    double radius = delta < 0 ? lr : rr, window = .5 + .5 * Math.cos(Math.PI * delta / radius); int slot = dst % RING;
                    left[slot] += PitchCorrector.sample(source, src, 0) * window; right[slot] += PitchCorrector.sample(source, src, 1) * window; weights[slot] += window;
                }
                double hz = PitchCorrector.interpolate(track.hz, at), shift = PitchCorrector.interpolate(track.shift, at); if (hz <= 0) break;
                output += Core.RATE / (hz * Math.pow(2, shift / 12));
            }
            while (written < to) emit(source, cv, map, sink, left, right, weights, written++, from, to);
        }
        while (written < frames) emit(source, cv, map, sink, left, right, weights, written++, frames, frames);
    }
    static void emit(Core.Source source, Curves cv, TimeMap map, TimeStretch.Sink sink, double[] left, double[] right, double[] weights, int frame, int from, int to) throws IOException {
        int slot = frame % PitchCorrector.RING; double l = source.sample(frame, 0), r = source.sample(frame, 1), wet = 0;
        if (frame >= from && frame < to && weights[slot] > .1) { double edge = Math.min(frame - from, to - 1 - frame); wet = Math.min(1, Math.max(0, edge / (Core.RATE * .012))); }
        if (wet > 0) { l = l * (1 - wet) + left[slot] / weights[slot] * wet; r = r * (1 - wet) + right[slot] / weights[slot] * wet; }
        double g = PitchCorrector.interpolate(cv.gain, map.input(frame)); sink.sample((float) (l * g), (float) (r * g)); left[slot] = right[slot] = weights[slot] = 0;
    }

    /** Note edits as [start, end, centre, pitch, drift, vibrato, gainDb, formant, outEnd] rows. */
    static org.json.JSONArray toJson(List<Note> notes) throws org.json.JSONException {
        org.json.JSONArray a = new org.json.JSONArray();
        for (Note n : notes) a.put(new org.json.JSONArray().put(n.start).put(n.end).put(n.centre).put(n.pitch).put(n.drift).put(n.vibrato).put(n.gainDb).put(n.formant).put(n.outEnd));
        return a;
    }
    static List<Note> fromJson(org.json.JSONArray a) throws org.json.JSONException {
        ArrayList<Note> out = new ArrayList<>(); int last = 0;
        for (int i = 0; i < a.length() && i < 4000; i++) {
            org.json.JSONArray r = a.getJSONArray(i); int s = r.getInt(0), e = r.getInt(1); double c = r.getDouble(2), p = r.getDouble(3);
            if (s < last || e <= s || !finite(c, p, r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getDouble(7))) continue; // drop damaged rows
            Note n = new Note(s, e, c); n.pitch = clamp(p, c - 24, c + 24); n.drift = clamp(r.getDouble(4), 0, 2); n.vibrato = clamp(r.getDouble(5), 0, 2);
            n.gainDb = clamp(r.getDouble(6), -24, 12); n.formant = clamp(r.getDouble(7), -12, 12); double oe = r.length() > 8 ? r.getDouble(8) : e; n.outEnd = Double.isFinite(oe) ? oe : e; out.add(n); last = e;
        }
        for (int i = 0; i < out.size(); i++) { Note n = out.get(i); boolean ok = i + 1 < out.size() && touches(n, out.get(i + 1)) && n.outEnd > outStart(out, i) + 1 && n.outEnd < out.get(i + 1).end - 1; if (!ok) n.outEnd = n.end; }
        return out;
    }
    static boolean finite(double... v) { for (double x : v) if (!Double.isFinite(x)) return false; return true; }
    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}

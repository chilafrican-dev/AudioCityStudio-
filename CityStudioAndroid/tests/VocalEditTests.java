package ug.audiocity.studio;

import java.util.*;

public class VocalEditTests {
    static int passed = 0;
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); passed++; }
    /** Voice-like tone: harmonics with a 1/n roll-off, a 5.5 Hz ±30 cent vibrato on the second note. */
    static float[] voice(double[] hz, double seconds, boolean vibrato) {
        int per = (int) (Core.RATE * seconds), n = per * hz.length; float[] out = new float[n * 2]; double phase = 0;
        for (int i = 0; i < n; i++) {
            int note = i / per; double f = hz[note]; if (vibrato && note == 1) f *= Math.pow(2, .3 / 12 * Math.sin(2 * Math.PI * 5.5 * i / Core.RATE));
            phase += 2 * Math.PI * f / Core.RATE; double s = 0; for (int h = 1; h <= 8; h++) s += Math.sin(phase * h) / h;
            int in = i % per; double env = Math.min(1, Math.min(in, per - in) / (Core.RATE * .01));
            out[i * 2] = out[i * 2 + 1] = (float) (.15 * s * env);
        }
        return out;
    }
    static float[] legato(double[] hz, double seconds) {
        int per = (int) (Core.RATE * seconds), n = per * hz.length; float[] out = new float[n * 2]; double phase = 0, f = hz[0];
        for (int i = 0; i < n; i++) { f += (hz[i / per] - f) * .01; phase += 2 * Math.PI * f / Core.RATE; double s = 0; for (int h = 1; h <= 8; h++) s += Math.sin(phase * h) / h; double env = Math.min(1, Math.min(i, n - i) / (Core.RATE * .01)); out[i * 2] = out[i * 2 + 1] = (float) (.15 * s * env); }
        return out;
    }
    static float[] render(Core.Source s, ProVocal.Analysis a, List<ProVocal.Note> notes) throws Exception {
        ArrayList<Float> v = new ArrayList<>(); ProVocal.render(s, a, notes, (l, r) -> { v.add(l); v.add(r); });
        float[] o = new float[v.size()]; for (int i = 0; i < o.length; i++) o[i] = v.get(i); return o;
    }
    static double centreOf(float[] audio, int fromHop, int toHop) throws Exception {
        ProVocal.Analysis a = ProVocal.analyse(new Core.Memory(audio), 70, 900); return ProVocal.median(ProVocal.sub(a.midi, fromHop, toHop));
    }
    static double rms(float[] a, int from, int to) { double s = 0; for (int i = from; i < to; i++) s += a[i * 2] * a[i * 2]; return Math.sqrt(s / (to - from)); }
    static double spread(double[] m, int from, int to) { double lo = 1e9, hi = -1e9; for (int i = from; i < to; i++) if (!Double.isNaN(m[i])) { lo = Math.min(lo, m[i]); hi = Math.max(hi, m[i]); } return hi - lo; }
    /** Spectral centroid of one channel, a rough stand-in for where the formants sit. */
    static double centroid(float[] a, int from, int len) { double num = 0, den = 0; for (int k = 1; k < 200; k++) { double re = 0, im = 0, f = k * 20.0; for (int i = 0; i < len; i++) { double w = 2 * Math.PI * f * i / Core.RATE; re += a[(from + i) * 2] * Math.cos(w); im += a[(from + i) * 2] * Math.sin(w); } double m = Math.hypot(re, im); num += m * f; den += m; } return num / den; }

    public static void main(String[] args) throws Exception {
        float[] dry = voice(new double[]{220, 246.94, 196}, .6, true); Core.Source src = new Core.Memory(dry);
        ProVocal.Analysis a = ProVocal.analyse(src, 70, 900); List<ProVocal.Note> notes = ProVocal.segment(a);
        System.out.println("Detected " + notes.size() + " notes"); for (ProVocal.Note n : notes) System.out.println("  " + n.start + "-" + n.end + " centre " + n.centre);
        check(notes.size() == 3, "three sung notes are found");
        check(Math.abs(notes.get(0).centre - 57) < .15 && Math.abs(notes.get(1).centre - 59) < .15 && Math.abs(notes.get(2).centre - 55) < .15, "note centres are A3, B3, G3");
        check(notes.get(0).end <= notes.get(1).start && notes.get(1).end <= notes.get(2).start, "notes are in order and do not overlap");

        float[] same = render(src, a, notes); check(same.length == dry.length, "render keeps length");
        boolean identical = true; for (int i = 0; i < dry.length; i++) identical &= same[i] == dry[i]; check(identical, "unedited notes pass through untouched");

        ProVocal.Note mid = notes.get(1); mid.pitch = mid.centre + 2;
        float[] up = render(src, a, notes); double moved = centreOf(up, mid.start + 6, mid.end - 6), first = centreOf(up, notes.get(0).start + 6, notes.get(0).end - 6);
        System.out.println("Moved note centre " + moved + ", neighbour " + first);
        check(Math.abs(moved - 61) < .2, "dragging a note up two semitones lands it on C#4");
        check(Math.abs(first - 57) < .15, "neighbouring notes stay where they were");

        mid.pitch = mid.centre; mid.vibrato = 0; float[] flat = render(src, a, notes);
        ProVocal.Analysis fa = ProVocal.analyse(new Core.Memory(flat), 70, 900);
        double before = spread(a.midi, mid.start + 8, mid.end - 8), after = spread(fa.midi, mid.start + 8, mid.end - 8);
        System.out.println("Vibrato spread before " + before + " after " + after);
        check(after < before * .5, "vibrato 0 flattens the wobble");
        mid.vibrato = 1;

        ProVocal.Note last = notes.get(2); last.gainDb = -6; float[] quiet = render(src, a, notes);
        int s = (int) last.startFrame() + 4800, e = (int) last.endFrame() - 4800; double ratio = rms(quiet, s, e) / rms(dry, s, e);
        check(Math.abs(ratio - .501) < .02, "note gain -6 dB halves the level");
        check(Math.abs(rms(quiet, 4800, 20000) - rms(dry, 4800, 20000)) < 1e-6, "gain is per note");
        last.gainDb = 0;

        ProVocal.Note one = notes.get(0); one.formant = 4; float[] bright = render(src, a, notes);
        int at = (int) one.startFrame() + 9600; double c0 = centroid(dry, at, 4800), c1 = centroid(bright, at, 4800), pitchKept = centreOf(bright, one.start + 6, one.end - 6);
        System.out.println("Formant centroid " + c0 + " -> " + c1 + ", pitch " + pitchKept);
        check(c1 > c0 * 1.08, "formant up moves the tone colour up");
        check(Math.abs(pitchKept - 57) < .2, "formant shift keeps the sung pitch");
        one.formant = 0;

        ProVocal.snap(notes.get(1), 0, 1); check(notes.get(1).pitch == 59, "snap to C major keeps B"); notes.get(1).centre = 58.4; ProVocal.snap(notes.get(1), 0, 1); check(notes.get(1).pitch == 59 || notes.get(1).pitch == 57, "snap picks a scale note");
        notes.get(1).centre = notes.get(1).pitch = 59;

        int count = notes.size(); ProVocal.Note half = ProVocal.split(notes, notes.get(0), (notes.get(0).start + notes.get(0).end) / 2, a);
        check(half != null && notes.size() == count + 1 && notes.get(0).end == half.start, "split makes two touching notes");
        check(ProVocal.join(notes, notes.get(0), a) && notes.size() == count, "join puts them back");
        check(Math.abs(notes.get(0).centre - 57) < .15, "joined note keeps its centre");

        Core.Clip clip = new Core.Clip("v", src, "v.pcm", 0); clip.vocalNotes = new ArrayList<>(); for (ProVocal.Note n : notes) clip.vocalNotes.add(n.copy()); clip.vocalNotes.get(1).pitch = 60; clip.vocalNotes.get(1).formant = -2; clip.vocalNotes.get(2).gainDb = 3;
        org.json.JSONArray json = ProVocal.toJson(clip.vocalNotes); List<ProVocal.Note> back = ProVocal.fromJson(json);
        check(back.size() == 3 && back.get(1).pitch == 60 && back.get(1).formant == -2 && back.get(2).gainDb == 3 && back.get(0).start == notes.get(0).start, "note edits survive save and load");
        clip.vocalOffset = 480; clip.vocalLength = 9000; org.json.JSONObject saved = ProjectIO.clip(clip);
        check(saved.has("vocalNotes") && saved.getInt("vocalOffset") == 480 && saved.getInt("vocalLength") == 9000, "project files store the note edits");
        check(clip.copy().vocalNotes.get(1).pitch == 60 && clip.copy().vocalNotes != clip.vocalNotes, "duplicated clips get their own note edits");
        // timing inside a legato phrase: no gap between notes, so they share boundaries
        float[] legato = legato(new double[]{220, 246.94, 220}, .5); Core.Source ls = new Core.Memory(legato);
        ProVocal.Analysis la = ProVocal.analyse(ls, 70, 900); List<ProVocal.Note> ln = ProVocal.segment(la);
        System.out.println("Legato notes " + ln.size()); for (ProVocal.Note n : ln) System.out.println("  " + n.start + "-" + n.end + " centre " + n.centre);
        check(ln.size() == 3 && ProVocal.touches(ln.get(0), ln.get(1)) && ProVocal.touches(ln.get(1), ln.get(2)), "legato phrase gives three touching notes");
        int b = ln.get(0).end; check(ProVocal.moveEnd(ln, 0, 10), "boundary moves");
        ProVocal.Analysis late = ProVocal.analyse(new Core.Memory(render(ls, la, ln)), 70, 900);
        double early = ProVocal.median(ProVocal.sub(late.midi, b + 3, b + 7)), after2 = ProVocal.median(ProVocal.sub(late.midi, b + 14, b + 20));
        System.out.println("After moving boundary 100 ms later: " + early + " then " + after2);
        check(Math.abs(early - 57) < .3, "first note now lasts 100 ms longer");
        check(Math.abs(after2 - 59) < .3, "second note starts later");
        check(render(ls, la, ln).length == legato.length, "timing edits keep the clip length");
        check(!ProVocal.moveEnd(ln, 2, 5), "the phrase end is anchored");
        double d = ProVocal.shift(ln, 1, -1000); check(d < 0 && ln.get(0).outEnd - ProVocal.outStart(ln, 0) >= ProVocal.MIN_HOPS - 1e-9, "moving a note is limited by its neighbours");
        check(ProVocal.shift(ln, 0, 5) == 0, "the first note of a phrase stays anchored");
        List<ProVocal.Note> lb = ProVocal.fromJson(ProVocal.toJson(ln)); check(lb.get(0).outEnd == ln.get(0).outEnd && lb.get(1).outEnd == ln.get(1).outEnd, "timing edits survive save and load");
        System.out.println("VocalEditTests passed " + passed + " checks");
    }
}

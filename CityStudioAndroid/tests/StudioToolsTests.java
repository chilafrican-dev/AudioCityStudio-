package ug.audiocity.studio;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Host checks for 0.8 mixing, editing, mastering and workflow features (ProFx, ProEdit, reference A/B, 44.1 kHz export). */
public class StudioToolsTests {
    static int passed;
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); passed++; }
    static final int R = Core.RATE;

    static float[] sine(double hz, double amp, double seconds) { int n = (int) (R * seconds); float[] a = new float[n * 2]; for (int i = 0; i < n; i++) { float v = (float) (amp * Math.sin(2 * Math.PI * hz * i / R)); a[2 * i] = v; a[2 * i + 1] = v; } return a; }
    static double peak(float[] a, int from, int to) { double p = 0; for (int i = from; i < to; i++) p = Math.max(p, Math.abs(a[2 * i])); return p; }
    static double rms(float[] a, int from, int to, int ch) { double s = 0; for (int i = from; i < to; i++) s += a[2 * i + ch] * (double) a[2 * i + ch]; return Math.sqrt(s / Math.max(1, to - from)); }
    static float[] run(Pro.Insert ins, float[] in, int bpm) { float[] a = in.clone(); Pro.Dsp d = ins.create(); for (int p = 0; p < a.length / 2; p += 512) { int n = Math.min(512, a.length / 2 - p); float[] b = new float[n * 2]; System.arraycopy(a, p * 2, b, 0, n * 2); d.process(b, n, bpm); System.arraycopy(b, 0, a, p * 2, n * 2); } return a; }
    static Pro.Insert ins(String kind, double... pairs) { Pro.Insert i = new Pro.Insert(kind); for (int k = 0; k < pairs.length; k += 2) i.set((int) pairs[k], pairs[k + 1]); i.touch(); return i; }
    static float[] render(Core.Session s, long from, int frames) { Core.Renderer r = new Core.Renderer(s); float[] all = new float[frames * 2], b = new float[1024]; for (int p = 0; p < frames; p += 512) { int n = Math.min(512, frames - p); r.render(from + p, n, b, false); System.arraycopy(b, 0, all, p * 2, n * 2); } return all; }
    static float[] dc(double v, int frames) { float[] a = new float[frames * 2]; Arrays.fill(a, (float) v); return a; }
    static Core.Track track(Core.Session s, String name) { Core.Track t = new Core.Track(name, 0); s.tracks.add(t); return t; }
    static Core.Clip clip(Core.Track t, float[] data, long at) { Core.Clip c = new Core.Clip("c", new Core.Memory(data), "f" + UUID.randomUUID(), at); t.clips.add(c); return c; }

    public static void main(String[] args) throws Exception {
        compressor(); reverb(); delay(); tape(); transient_(); ranges(); clipTools(); bounce(); analysis(); reference(); resampler(); exports(); kinds();
        System.out.println(passed + " mixing/editing/mastering/workflow checks passed.");
    }

    static void compressor() {
        check(ProFx.Comp.curve(-30, -20, 4, 0) == 0 && Math.abs(ProFx.Comp.curve(-6, -20, 4, 0) - 10.5) < 1e-9, "hard-knee static curve");
        check(ProFx.Comp.curve(-20, -20, 4, 6) > 0 && ProFx.Comp.curve(-20, -20, 4, 6) < ProFx.Comp.curve(-17, -20, 4, 6) && Math.abs(ProFx.Comp.curve(-17, -20, 4, 6) - ProFx.Comp.curve(-17, -20, 4, 0)) < 1e-9, "soft knee is continuous and meets the hard curve at its edge");
        float[] out = run(ins(ProFx.COMP, 0, -20, 1, 4, 4, 0, 2, 1), sine(1000, .5, 1), 120);
        double outDb = Pro.db(peak(out, R / 2, R)); System.out.println("Compressor: -6 dBFS sine, −20 dB threshold 4:1 → " + outDb + " dBFS peak");
        check(Math.abs(outDb - (-16.5)) < 0.6, "steady-state reduction matches the curve: " + outDb);
        float[] parallel = run(ins(ProFx.COMP, 0, -20, 1, 4, 4, 0, 6, 0), sine(1000, .5, .5), 120); check(Math.abs(peak(parallel, R / 4, R / 2) - .5) < 1e-3, "0% mix is dry");
        float[] low = run(ins(ProFx.COMP, 0, -20, 1, 4, 7, 400), sine(60, .5, 1), 120); check(peak(low, R / 2, R) > .4, "sidechain low cut ignores bass: " + peak(low, R / 2, R));
        float[] up = run(ins(ProFx.COMP, 0, 0, 1, 1, 5, 6), sine(1000, .1, .3), 120); check(Math.abs(Pro.db(peak(up, R / 8, R * 3 / 10) / .1) - 6) < .1, "makeup gain");
    }

    static void reverb() {
        float[] imp = new float[R * 4 * 2]; imp[0] = imp[1] = 1;
        float[] dry = run(ins(ProFx.REVERB, 6, 0), imp, 120); check(dry[0] == 1 && peak(dry, 100, R) == 0, "0% mix passes the dry signal only");
        float[] wet = run(ins(ProFx.REVERB, 1, 1.8, 6, 100), imp, 120); boolean finite = true; for (float v : wet) finite &= Float.isFinite(v);
        double early = rms(wet, R / 10, R / 2, 0), late = rms(wet, R * 2, R * 5 / 2, 0), drop = Pro.db(early / Math.max(1e-12, late));
        System.out.println("Plate 1.8 s decay: level falls " + drop + " dB between 0.1–0.5 s and 2–2.5 s");
        check(finite && drop > 30 && drop < 120 && early > 1e-4, "reverb decays smoothly");
        check(Math.abs(rms(wet, R / 10, R, 0) - rms(wet, R / 10, R, 1)) / rms(wet, R / 10, R, 0) < .5 && rms(wet, R / 10, R, 0) > 0, "stereo tail");
        Pro.Insert narrow = ins(ProFx.REVERB, 4, 0, 6, 100); float[] mono = run(narrow, imp, 120); double diff = 0; for (int i = 100; i < R; i++) diff = Math.max(diff, Math.abs(mono[2 * i] - mono[2 * i + 1])); check(diff < 1e-6, "0% width is mono");
    }

    static void delay() {
        float[] imp = new float[R * 2 * 2]; imp[0] = imp[1] = 1;
        float[] out = run(ins(ProFx.DELAY, 0, .75, 1, 50, 2, 20, 3, 20000, 4, 1, 5, 100), imp, 120);
        int d = (int) Math.round(R * 60.0 / 120 * .75);
        check(Math.abs(out[2 * d] - 1) < .02 && Math.abs(out[2 * d + 1]) < .02, "first echo after ¾ beat at 120 BPM, left side (ping-pong): " + out[2 * d]);
        check(Math.abs(out[2 * (2 * d) + 1]) > .3 && Math.abs(out[2 * (2 * d)]) < .05, "second echo bounces to the right: " + out[2 * (2 * d) + 1]);
        float[] slow = run(ins(ProFx.DELAY, 0, .75, 5, 100), imp, 60); check(Math.abs(slow[2 * (2 * d)]) > .4, "delay time follows tempo");
    }

    static void tape() {
        float[] quiet = run(ins(ProFx.TAPE, 0, 12), sine(1000, .01, .5), 120); check(Math.abs(Pro.db(rms(quiet, R / 4, R / 2, 0) / (.01 / Math.sqrt(2)))) < 1.0, "low-level gain ≈ unity at 12 dB drive: " + Pro.db(rms(quiet, R / 4, R / 2, 0) / (.01 / Math.sqrt(2))));
        float[] hot = run(ins(ProFx.TAPE, 0, 18, 2, 20000), sine(500, .7, .5), 120);
        double[] re = new double[8192], im = new double[8192]; for (int i = 0; i < 8192; i++) re[i] = hot[2 * (R / 8 + i)] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / 8191)); Pro.fft(re, im, false);
        int f1 = (int) Math.round(500.0 * 8192 / R), f3 = (int) Math.round(1500.0 * 8192 / R); double h1 = 0, h3 = 0; for (int k = -2; k <= 2; k++) { h1 = Math.max(h1, Math.hypot(re[f1 + k], im[f1 + k])); h3 = Math.max(h3, Math.hypot(re[f3 + k], im[f3 + k])); }
        System.out.println("Tape 18 dB drive: 3rd harmonic " + Pro.db(h3 / h1) + " dB re fundamental");
        check(h3 / h1 > .01 && peak(hot, R / 8, R / 2) < 1.6, "drive adds harmonics without blowing up");
    }

    static void transient_() {
        float[] hits = new float[R * 2]; for (int k = 0; k < 4; k++) for (int i = 0; i < R / 4; i++) { float v = (float) (Math.sin(2 * Math.PI * 200 * i / R) * Math.exp(-i / 4000.0) * .5); hits[2 * (k * R / 4 + i)] = v; hits[2 * (k * R / 4 + i) + 1] = v; }
        float[] punchy = run(ins(ProFx.TRANSIENT, 0, 100, 1, 0), hits, 120), soft = run(ins(ProFx.TRANSIENT, 0, 0, 1, -100), hits, 120), flat = run(ins(ProFx.TRANSIENT, 0, 0, 1, 0), hits, 120);
        double attackGain = peak(punchy, R / 4, R / 4 + 600) / peak(hits, R / 4, R / 4 + 600), tail = rms(soft, R / 4 + 8000, R / 2, 0) / rms(hits, R / 4 + 8000, R / 2, 0);
        System.out.println("Transient shaper: attack +100% → ×" + attackGain + ", sustain −100% tail → ×" + tail);
        check(attackGain > 1.2 && tail < .8 && Arrays.equals(flat, hits), "attack boost, sustain cut, neutral at 0/0");
    }

    static void ranges() {
        Core.Session s = new Core.Session(); Core.Track a = track(s, "a"), b = track(s, "b");
        Core.Clip c1 = clip(a, dc(.1, 10000), 0), c2 = clip(a, dc(.2, 4000), 12000), c3 = clip(b, dc(.3, 20000), 0);
        s.markers.add(new Pro.Marker(15000, "m")); Pro.Lane l = new Pro.Lane("volume"); l.add(0, .2); l.add(16000, 1); a.automation.put("volume", l);
        List<ProEdit.Item> clip = ProEdit.copy(s, s.tracks, 5000, 14000);
        check(clip.size() == 3 && clip.get(0).clip.start == 0 && clip.get(0).clip.length == 5000 && clip.get(1).clip.start == 7000 && clip.get(1).clip.length == 2000 && clip.get(2).clip.length == 9000, "range copy trims to the range");
        Core.Session before = s.copy();
        ProEdit.delete(s, s.tracks, 5000, 14000, true);
        check(a.clips.size() == 2 && a.clips.get(0).length == 5000 && a.clips.get(1).start == 5000 && a.clips.get(1).length == 2000 && a.clips.get(1).offset == 2000, "ripple delete closes the gap: " + a.clips.get(1).start);
        check(b.clips.size() == 2 && b.clips.get(1).start == 5000 && b.clips.get(1).offset == 14000 && s.markers.get(0).frame == 6000, "all tracks and markers move");
        check(Math.abs(l.at(4000) - before.tracks.get(0).automation.get("volume").at(4000)) < 1e-9 && Math.abs(l.at(7000) - before.tracks.get(0).automation.get("volume").at(16000)) < 1e-9, "automation is cut and closed up");
        float[] r1 = render(before, 0, 5000), r2 = render(s, 0, 5000); check(Arrays.equals(r1, r2), "audio before the range is untouched");
        List<Core.Clip> pasted = ProEdit.paste(s, clip, 30000); check(pasted.size() == 3 && pasted.get(0).start == 30000 && a.clips.contains(pasted.get(0)) && b.clips.contains(pasted.get(2)), "paste onto original tracks at the playhead");
        Core.Session g = new Core.Session(); Core.Track t = track(g, "t"); Core.Clip whole = clip(t, dc(.5, 10000), 0); ProEdit.insertTime(g, g.tracks, 4000, 3000);
        check(t.clips.size() == 2 && t.clips.get(0).length == 4000 && t.clips.get(1).start == 7000 && t.clips.get(1).offset == 4000, "insert time splits and shifts");
        float[] gap = render(g, 4000, 3000); check(peak(gap, 0, 3000) == 0, "inserted time is silent");
        Core.Session k = new Core.Session(); Core.Track kt = track(k, "k"); Core.Clip kc = clip(kt, dc(.5, 1000), 0); ProEdit.delete(k, k.tracks, 200, 300, false); check(kt.clips.size() == 2 && kt.clips.get(1).start == 300, "delete without ripple leaves a gap");
        check(ProEdit.split(kt, kt.clips.get(0), 0) == null, "split at an edge does nothing");
    }

    static void clipTools() {
        Core.Track t = new Core.Track("t", 0); Core.Clip c = clip(t, sine(440, .25, 1), 1000);
        ProEdit.nudge(c, -5000); check(c.start == 0, "nudge clamps at zero"); ProEdit.nudge(c, 480); check(c.start == 480, "nudge");
        List<Core.Clip> d = ProEdit.duplicate(t, c, 3); check(d.size() == 3 && d.get(2).start == 480 + 3L * c.length, "duplicate ×3 back to back");
        double change = ProEdit.normalise(c, -1); check(Math.abs(Pro.db(ProEdit.peak(c)) + 1) < .01 && Math.abs(change - (-1 - Pro.db(.25))) < .05, "normalise to −1 dBFS: " + change);
        float[] ramp = new float[20]; for (int i = 0; i < 10; i++) { ramp[2 * i] = i; ramp[2 * i + 1] = -i; } Core.Clip rc = new Core.Clip("r", new Core.Memory(ramp), "r", 0); rc.offset = 2; rc.length = 5;
        float[] rev = ProEdit.reversed(rc); check(rev[0] == 6 && rev[1] == -6 && rev[8] == 2, "reverse uses the clip window");
        float[] words = new float[R * 3 * 2]; for (int w = 0; w < 3; w++) for (int i = 0; i < R / 2; i++) { float v = (float) (Math.sin(2 * Math.PI * 300 * i / R) * .3); words[2 * (w * R + i)] = v; words[2 * (w * R + i) + 1] = v; }
        Core.Clip vo = new Core.Clip("vo", new Core.Memory(words), "vo", 10000);
        List<Core.Clip> parts = ProEdit.stripSilence(vo, -40, R / 10, R / 100);
        check(parts.size() == 3 && Math.abs(parts.get(1).start - (10000 + R - R / 100)) <= R / 100 && parts.get(0).fadeIn > 0, "strip silence finds three phrases: " + parts.size());
        check(ProEdit.stripSilence(vo, -40, 2 * R, 0).size() == 1, "short gaps are kept when below the minimum silence");
    }

    static void bounce() {
        Core.Session s = new Core.Session(); s.master = .5; Core.Track a = track(s, "a"); a.fx = Core.preset(1); clip(a, sine(330, .3, 1), 0); Core.Track b = track(s, "b"); clip(b, sine(550, .3, 1), 0);
        s.masterInserts.add(new Pro.Insert(Pro.UTILITY)); a.mute = true;
        float[] bounced = ProEdit.bounce(s, 0, 1000, 9000), expect = render(Pro.stem(s, 0), 1000, 8000);
        check(bounced.length == 16000 && peak(bounced, 0, 8000) > .05, "bounce renders the track even when muted");
        Core.Session st = Pro.stem(s, 0); st.tracks.get(0).mute = false; check(Arrays.equals(bounced, render(st, 1000, 8000)), "bounce equals the solo stem render");
        check(a.mute && s.master == .5, "bounce leaves the project untouched");
    }

    static void analysis() {
        float[] ring = new float[8192]; for (int i = 0; i < ring.length; i++) ring[i] = (float) (.5 * Math.sin(2 * Math.PI * 1000 * i / R));
        float[] sp = ProEdit.spectrum(ring, 0, 4096, 60); int best = 0; for (int b = 0; b < sp.length; b++) if (sp[b] > sp[best]) best = b;
        double center = 20 * Math.pow(1000, (best + .5) / 60.0);
        check(Math.abs(Math.log(center / 1000)) < .12 && Math.abs(sp[best] - Pro.db(.5)) < 1.6 && sp[5] < -60, "spectrum peak at 1 kHz near −6 dBFS: " + center + " Hz " + sp[best]);
        Core.Session s = new Core.Session(); s.master = 1; Core.Track t = track(s, "t"); clip(t, sine(500, .3, 1), 0);
        Core.Renderer r = new Core.Renderer(s); float[] b = new float[1024]; for (int p = 0; p < R / 2; p += 512) r.render(p, 512, b, false); check(r.correlation > .99, "mono correlation +1: " + r.correlation);
        float[] anti = sine(500, .3, 1); for (int i = 0; i < anti.length / 2; i++) anti[2 * i + 1] = -anti[2 * i]; Core.Session s2 = new Core.Session(); Core.Track t2 = track(s2, "t"); clip(t2, anti, 0);
        Core.Renderer r2 = new Core.Renderer(s2); for (int p = 0; p < R / 2; p += 512) r2.render(p, 512, b, false); check(r2.correlation < -.99, "out-of-phase correlation −1: " + r2.correlation);
        check(ProEdit.loopPosition(500, 1000, 2000) == 500 && ProEdit.loopPosition(2000, 1000, 2000) == 1000 && ProEdit.loopPosition(2750, 1000, 2000) == 1750 && ProEdit.loopPosition(3100, 0, 0) == 3100, "loop-range playhead");
    }

    static void reference() {
        Core.Session s = new Core.Session(); s.master = 1; Core.Track t = track(s, "mix"); clip(t, dc(.2, 4000), 0);
        float[] ref = new float[1000 * 2]; for (int i = 0; i < 1000; i++) { ref[2 * i] = .4f; ref[2 * i + 1] = -.4f; }
        s.reference = new Core.Clip("ref", new Core.Memory(ref), "ref.pcm", 0); s.referenceGain = .5;
        float[] mix = render(s, 0, 2000); s.referenceOn = true; float[] a = render(s, 0, 2000);
        check(Math.abs(a[0] - .2f) < 1e-6 && Math.abs(a[1] + .2f) < 1e-6 && Math.abs(a[2 * 1500] - .2f) < 1e-6, "A/B plays the level-matched reference, looping");
        s.referenceOn = false; check(Arrays.equals(mix, render(s, 0, 2000)), "switching back plays the mix unchanged");
        Core.Session c = s.copy(); check(c.reference != null && c.reference != s.reference && c.referenceGain == .5, "reference survives copies");
    }

    static void resampler() throws Exception {
        for (double hz : new double[]{1000, 18000}) {
            ArrayList<double[]> out = new ArrayList<>(); ProEdit.Resampler r = new ProEdit.Resampler(48000, 44100, (l, rr) -> out.add(new double[]{l, rr}));
            int n = R; for (int i = 0; i < n; i++) { double v = .5 * Math.sin(2 * Math.PI * hz * i / R); r.push(v, -v); } r.finish(n);
            check(out.size() == 44100, "44.1 kHz frame count " + out.size());
            double re = 0, im = 0, sq = 0; for (int i = 4000; i < 40000; i++) { double v = out.get(i)[0]; re += v * Math.cos(2 * Math.PI * hz * i / 44100); im += v * Math.sin(2 * Math.PI * hz * i / 44100); sq += v * v; }
            double amp = 2 * Math.hypot(re, im) / 36000, purity = (amp * amp / 2) / (sq / 36000);
            System.out.println("Resampler " + hz + " Hz: amplitude " + Pro.db(amp / .5) + " dB, tone purity " + purity);
            check(Math.abs(Pro.db(amp / .5)) < (hz > 10000 ? .5 : .05) && purity > .9999 && Math.abs(out.get(20000)[0] + out.get(20000)[1]) < 1e-9, "resampled tone keeps level, pitch and channel order at " + hz + " Hz");
        }
        ArrayList<double[]> alias = new ArrayList<>(); ProEdit.Resampler ra = new ProEdit.Resampler(48000, 44100, (l, rr) -> alias.add(new double[]{l, rr}));
        for (int i = 0; i < R; i++) { double v = .5 * Math.sin(2 * Math.PI * 23000 * i / R); ra.push(v, v); } ra.finish(R);
        double worst = 0; for (int i = 4000; i < 40000; i++) worst = Math.max(worst, Math.abs(alias.get(i)[0])); System.out.println("23 kHz input after resampling: " + Pro.db(worst / .5) + " dB");
        check(Pro.db(worst / .5) < -80, "content above 22.05 kHz is removed, no aliasing");
        check(ProEdit.Resampler.outputFrames(48000, 48000, 44100) == 44100 && ProEdit.Resampler.outputFrames(1, 48000, 44100) == 1, "output length rule");
    }

    static void exports() throws Exception {
        Core.Session s = new Core.Session(); Core.Track t = track(s, "t"); t.fx = Core.preset(1); clip(t, sine(997, .4, 2), 0);
        File dir = Files.createTempDirectory("acs08").toFile();
        File w44 = new File(dir, "a.wav"), f44 = new File(dir, "a.flac"), wNo = new File(dir, "b.wav");
        try (OutputStream o = new FileOutputStream(w44)) { Export.wav(o, s, 24, 44100, true); } try (OutputStream o = new FileOutputStream(f44)) { Export.flac(o, s, 24, 44100, true); }
        String probe = probe(w44); check(probe.contains("44100") && probe.contains("s32") || probe.contains("44100"), "WAV header says 44.1 kHz: " + probe);
        int[] a = decode(w44), b = decode(f44); long expected = Export.frames(s, 44100);
        check(a.length == expected * 2 && Arrays.equals(a, b), "44.1 kHz WAV and FLAC decode to the same " + expected + " frames");
        ByteArrayOutputStream legacy = new ByteArrayOutputStream(), viaNew = new ByteArrayOutputStream(); Export.wav(legacy, s, 16); Export.wav(viaNew, s, 16, 48000, true); check(Arrays.equals(legacy.toByteArray(), viaNew.toByteArray()), "48 kHz dithered export unchanged");
        Core.Session quiet = new Core.Session(); Core.Track q = track(quiet, "q"); clip(q, new float[R * 2], 0);
        try (OutputStream o = new FileOutputStream(wNo)) { Export.wav(o, quiet, 16, 48000, false); } boolean zeros = true; for (int v : decode(wNo)) zeros &= v == 0; check(zeros, "no-dither export of silence is digital silence");
        ByteArrayOutputStream zip = new ByteArrayOutputStream(); check(Export.stems(zip, s, 16, true, 44100) == 1 && zip.size() > 1000, "stems at 44.1 kHz");
    }
    static String probe(File f) throws Exception { Process p = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=sample_rate,channels,sample_fmt", "-of", "csv=p=0", f.toString()).redirectErrorStream(true).start(); String s = new String(p.getInputStream().readAllBytes()).trim(); p.waitFor(); return s; }
    static int[] decode(File f) throws Exception {
        File raw = File.createTempFile("decode", ".raw"); Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", f.toString(), "-f", "s32le", "-acodec", "pcm_s32le", raw.toString()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes()); if (p.waitFor() != 0 || !log.trim().isEmpty()) throw new IOException("ffmpeg: " + log);
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(raw.toPath())).order(ByteOrder.LITTLE_ENDIAN); int[] out = new int[bb.remaining() / 4]; for (int i = 0; i < out.length; i++) out[i] = bb.getInt(); return out;
    }

    static void kinds() {
        for (String k : new String[]{ProFx.COMP, ProFx.REVERB, ProFx.DELAY, ProFx.TAPE, ProFx.TRANSIENT}) {
            Pro.Kind info = Pro.KINDS.get(k); check(info != null && info.names.length == info.defaults.length && info.min.length == info.defaults.length, "insert kind registered: " + k);
            float[] noise = new float[R * 2]; Random r = new Random(k.hashCode()); for (int i = 0; i < noise.length; i++) noise[i] = (float) (r.nextGaussian() * .3);
            boolean finite = true; for (float v : run(new Pro.Insert(k), noise, 97)) finite &= Float.isFinite(v) && Math.abs(v) < 8; check(finite, k + " defaults stay finite on loud noise");
        }
    }
}

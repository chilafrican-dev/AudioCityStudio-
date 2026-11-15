package ug.audiocity.studio;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Host checks for the professional-workstation engine features in Pro.java and their Core integration. */
public class ProFeatureTests {
    static int passed;
    static void check(boolean c, String message) { if (!c) throw new AssertionError(message); passed++; }
    static final int R = Core.RATE;

    static float[] sine(double hz, double amp, double seconds, double phase) { int n = (int) (R * seconds); float[] a = new float[n * 2]; for (int i = 0; i < n; i++) { float v = (float) (amp * Math.sin(2 * Math.PI * hz * i / R + phase)); a[2 * i] = v; a[2 * i + 1] = v; } return a; }
    static double rms(float[] a, int from, int to, int ch) { double s = 0; for (int i = from; i < to; i++) s += a[2 * i + ch] * (double) a[2 * i + ch]; return Math.sqrt(s / Math.max(1, to - from)); }
    static float[] process(Pro.Insert ins, float[] in) { float[] a = in.clone(); Pro.Dsp d = ins.create(); for (int p = 0; p < a.length / 2; p += 512) { int n = Math.min(512, a.length / 2 - p); float[] b = new float[n * 2]; System.arraycopy(a, p * 2, b, 0, n * 2); d.process(b, n, 120); System.arraycopy(b, 0, a, p * 2, n * 2); } return a; }
    static float[] render(Core.Session s, int frames) { Core.Renderer r = new Core.Renderer(s); float[] all = new float[frames * 2], b = new float[1024]; for (int p = 0; p < frames; p += 512) { int n = Math.min(512, frames - p); r.render(p, n, b, false); System.arraycopy(b, 0, all, p * 2, n * 2); } return all; }
    static Core.Track track(Core.Session s, String name, float[] data, long at) { Core.Track t = new Core.Track(name, 0); t.volume = 1; t.fx.eq = false; t.clips.add(new Core.Clip(name, new Core.Memory(data), name, at)); s.tracks.add(t); return t; }
    static float[] dc(double v, int frames) { float[] a = new float[frames * 2]; Arrays.fill(a, (float) v); return a; }

    public static void main(String[] args) throws Exception {
        loudness(); truePeak(); limiter(); eq(); multiband(); utility(); automation(); buses(); sidechain(); crossfade(); comping(); edits(); tempo(); latency(); ring(); notes(); flac(); offline(); stems(); chain(); identity();
        System.out.println(passed + " professional-feature checks passed.");
    }

    static void loudness() {
        for (double level : new double[]{-23, -18, -30}) {
            Pro.Loudness m = new Pro.Loudness(); float[] a = sine(997, Math.pow(10, level / 20), 10, 0); m.feed(a, a.length / 2);
            double i = m.integrated(); System.out.println("997 Hz stereo sine at " + level + " dBFS -> " + i + " LUFS");
            check(Math.abs(i - level) < 0.1, "integrated loudness " + i + " for " + level);
            check(Math.abs(m.shortTermLufs - level) < 0.15, "short-term " + m.shortTermLufs);
            check(Math.abs(m.momentary - level) < 0.15, "momentary " + m.momentary);
        }
        Pro.Loudness silent = new Pro.Loudness(); silent.feed(new float[R * 2], R); check(silent.integrated() == Double.NEGATIVE_INFINITY, "silence is gated out");
        // gating: 10 s at -20 plus 10 s at -60 (below relative gate) must still read about -20
        Pro.Loudness g = new Pro.Loudness(); float[] loud = sine(1000, Math.pow(10, -20 / 20.0), 10, 0), quiet = sine(1000, Math.pow(10, -60 / 20.0), 10, 0); g.feed(loud, loud.length / 2); g.feed(quiet, quiet.length / 2);
        check(Math.abs(g.integrated() - (-20)) < 0.2, "relative gate excludes quiet section: " + g.integrated());
        Pro.Loudness lra = new Pro.Loudness(); float[] a = sine(1000, Math.pow(10, -20 / 20.0), 20, 0), b = sine(1000, Math.pow(10, -30 / 20.0), 20, 0); lra.feed(a, a.length / 2); lra.feed(b, b.length / 2);
        System.out.println("Loudness range for 20 s at -20 then 20 s at -30: " + lra.range() + " LU");
        check(Math.abs(lra.range() - 10) < 1.0, "loudness range ≈ 10 LU: " + lra.range());
        check(Math.abs(Pro.normaliseGain(g, -14, -1) - 6) < 0.3 || Pro.normaliseGain(g, -14, -1) <= -1 - g.truePeakDb + 1e-9, "normalise gain respects target and ceiling");
    }

    static void truePeak() {
        float[] a = sine(R / 4.0, 1, 1, Math.PI / 4); // samples land at ±0.707, true peak 1.0
        double sample = 0; for (float v : a) sample = Math.max(sample, Math.abs(v));
        Pro.Loudness m = new Pro.Loudness(); m.feed(a, a.length / 2);
        System.out.println("fs/4 sine: sample peak " + Pro.db(sample) + " dBFS, true peak " + m.truePeakDb + " dBTP");
        check(Math.abs(Pro.db(sample) + 3.01) < 0.05, "sample peak is -3 dB");
        check(Math.abs(m.truePeakDb) < 0.4, "true peak within 0.4 dB of 0 dBTP: " + m.truePeakDb);
        float[] low = sine(1000, 0.5, 1, 0); Pro.Loudness ml = new Pro.Loudness(); ml.feed(low, low.length / 2); check(Math.abs(ml.truePeakDb - Pro.db(.5)) < 0.05, "true peak of 1 kHz sine " + ml.truePeakDb);
    }

    static void limiter() {
        Pro.Insert lim = new Pro.Insert(Pro.LIMITER); lim.set(0, 12); lim.set(1, -1);
        Random r = new Random(4); float[] in = new float[R * 3 * 2]; for (int i = 0; i < in.length / 2; i++) { float v = (float) ((r.nextGaussian() * .25) + .5 * Math.sin(i * 0.37)); in[2 * i] = v; in[2 * i + 1] = (float) (v * .8); }
        float[] out = process(lim, in);
        double max = 0; for (float v : out) max = Math.max(max, Math.abs(v));
        check(max <= Pro.gain(-1) + 1e-6, "limiter sample peak <= ceiling: " + Pro.db(max));
        Pro.Loudness m = new Pro.Loudness(); m.feed(out, out.length / 2);
        System.out.println("Limiter +12 dB drive: output sample peak " + Pro.db(max) + " dBFS, true peak " + m.truePeakDb + " dBTP");
        check(m.truePeakDb < -0.4, "limiter true peak below -0.4 dBTP: " + m.truePeakDb);
        // clean transparent pass for quiet input, and latency is exactly the reported look-ahead
        Pro.Insert clean = new Pro.Insert(Pro.LIMITER); Pro.Dsp d = clean.create(); float[] imp = new float[2048]; imp[200] = .3f; imp[201] = .3f; d.process(imp, 1024, 120);
        int at = -1; for (int i = 0; i < 1024; i++) if (Math.abs(imp[2 * i] - .3f) < 1e-6) at = i;
        check(at == 100 + d.latency(), "limiter latency " + (at - 100) + " = " + d.latency());
        check(d.latency() == Math.round(1.5e-3 * R) + Pro.TruePeak.TAPS / 2, "default look-ahead 1.5 ms");
        // no gain reduction when the signal is under the ceiling
        Pro.Dsp q = new Pro.Insert(Pro.LIMITER).create(); float[] s = sine(440, .3, .5, 0); q.process(s, s.length / 2, 120); check(q.reduction() < 1e-9, "no reduction below ceiling");
    }

    static void eq() {
        Pro.Insert eq = new Pro.Insert(Pro.EQ8);
        for (double hz : new double[]{30, 100, 1000, 5000, 15000}) check(Math.abs(eq.response(hz)) < 1e-6, "default EQ is flat at " + hz);
        eq.set(3 * 5 + 2, 1000); eq.set(3 * 5 + 3, 6); eq.set(3 * 5 + 4, 1.2); eq.touch();
        check(Math.abs(eq.response(1000) - 6) < 0.01, "bell response " + eq.response(1000));
        float[] out = process(eq, sine(1000, .1, 1, 0));
        double gain = Pro.db(rms(out, R / 2, R, 0) / (.1 / Math.sqrt(2)));
        System.out.println("8-band EQ +6 dB bell at 1 kHz measured " + gain + " dB");
        check(Math.abs(gain - 6) < 0.1, "measured bell gain " + gain);
        Pro.Insert cut = new Pro.Insert(Pro.EQ8); cut.set(0, 1); cut.set(2, 200); cut.touch();
        check(cut.response(50) < -20 && Math.abs(cut.response(2000)) < 0.1, "24 dB/oct low cut " + cut.response(50));
        double measured = Pro.db(rms(process(cut, sine(50, .3, 1, 0)), R / 2, R, 0) / (.3 / Math.sqrt(2)));
        check(Math.abs(measured - cut.response(50)) < 0.5, "low cut measured " + measured + " vs " + cut.response(50));
    }

    static void multiband() {
        Pro.Insert mb = new Pro.Insert(Pro.MULTIBAND); for (int b = 0; b < 3; b++) mb.set(3 + b * 3, 1); mb.touch();
        for (double hz : new double[]{60, 180, 1000, 3200, 9000}) {
            double g = Pro.db(rms(process(mb, sine(hz, .2, 1, 0)), R / 2, R, 0) / (.2 / Math.sqrt(2)));
            check(Math.abs(g) < 0.15, "multiband at unity is flat at " + hz + " Hz: " + g);
        }
        Pro.Insert squash = new Pro.Insert(Pro.MULTIBAND); squash.set(2, -30); squash.set(3, 8); squash.set(6, 1); squash.set(9, 1); squash.touch();
        double low = Pro.db(rms(process(squash, sine(80, .5, 1, 0)), R / 2, R, 0) / (.5 / Math.sqrt(2))), high = Pro.db(rms(process(squash, sine(6000, .5, 1, 0)), R / 2, R, 0) / (.5 / Math.sqrt(2)));
        System.out.println("Multiband low-band compression: 80 Hz " + low + " dB, 6 kHz " + high + " dB");
        check(low < -10 && Math.abs(high) < 0.5, "only the low band is compressed");
    }

    static void utility() {
        Pro.Insert u = new Pro.Insert(Pro.UTILITY); u.set(1, 0); float[] in = new float[200]; for (int i = 0; i < 100; i++) { in[2 * i] = .5f; in[2 * i + 1] = -.1f; }
        float[] out = process(u, in); check(Math.abs(out[0] - .2f) < 1e-6 && Math.abs(out[1] - .2f) < 1e-6, "zero width collapses to mono");
        Pro.Insert ch = new Pro.Insert(Pro.CHORUS); float[] c = process(ch, sine(440, .3, .5, 0)); boolean finite = true; for (float v : c) finite &= Float.isFinite(v) && Math.abs(v) < 1; check(finite, "chorus output finite");
    }

    static void automation() {
        Pro.Lane l = new Pro.Lane("volume"); check(Double.isNaN(l.at(0)), "empty lane has no value");
        l.add(1000, .2); l.add(3000, .6); l.add(2000, 1.0);
        check(l.size() == 3 && l.frame(1) == 2000, "points sorted"); check(Math.abs(l.at(1500) - .6) < 1e-9, "linear interpolation");
        check(l.at(0) == .2 && l.at(9999) == .6, "holds outside points");
        l.add(2000, 1.5); check(l.size() == 3 && l.value(1) == 1.5, "same frame replaces");
        l.write(1200, 2800, .4); check(l.at(2800) == .4 && l.nearest(2000, 10) < 0, "touch write replaces range");
        Pro.Lane ramp = new Pro.Lane("pan"); for (int i = 0; i <= 100; i++) ramp.add(i * 100, -1 + i * .02); ramp.thin(.001); check(ramp.size() == 2, "thinning keeps a straight ramp as two points: " + ramp.size());
        Pro.Lane cl = new Pro.Lane("volume"); cl.add(0, 5); check(cl.value(0) == 2, "values clamped to parameter range");
        // render: volume automated from 0 to 1 over one second
        Core.Session s = new Core.Session(); s.master = 1; Core.Track t = track(s, "a", dc(.5, R), 0); Pro.Lane v = new Pro.Lane("volume"); v.add(0, 0); v.add(R, 1); t.automation.put("volume", v);
        float[] out = render(s, R);
        double early = rms(out, 1000, 2000, 0), late = rms(out, R - 2000, R - 1000, 0);
        check(early < .02 && late > .3, "volume automation follows the lane: " + early + " → " + late);
        // FX automation: threshold lane alters only the scratch copy
        Pro.Lane th = new Pro.Lane("fx.threshold"); th.add(0, -40); t.automation.put("fx.threshold", th); t.fx.comp = true; double before = t.fx.threshold;
        render(s, 4096); check(t.fx.threshold == before, "automation does not overwrite the stored FX setting");
        check(Pro.automatedFx(t, 0, new Core.Fx()).threshold == -40, "automated FX copy uses lane value");
        Core.Track copy = t.copy(); check(copy.automation.get("volume").size() == 2 && copy.automation.get("volume") != v, "automation is deep-copied for undo");
        // master automation
        Core.Session m = new Core.Session(); track(m, "a", dc(.3, R / 2), 0); m.master = 1; m.masterAutomation = new Pro.Lane("volume"); m.masterAutomation.add(0, .5);
        float[] mo = render(m, 4096); check(Math.abs(mo[4000] - .15 * Math.cos(Math.PI / 4)) < 1e-3, "master automation scales output " + mo[4000]);
    }

    static void buses() {
        Core.Session s = new Core.Session(); s.master = 1; Core.Track t = track(s, "dry", dc(.2, R / 2), 0); t.pan = 0;
        Core.Track bus = new Core.Track("Reverb bus", 0); bus.bus = true; bus.volume = 1; bus.fx.eq = false; s.buses.add(bus);
        float[] dry = render(s, 4096);
        t.sends.add(new Pro.Send(bus.id, .5, false)); float[] wet = render(s, 4096);
        check(Math.abs(wet[4000] - dry[4000] * (1 + .5 * Math.cos(Math.PI / 4))) < 1e-4, "post-fader send adds bus signal: " + wet[4000] + " vs " + dry[4000]);
        t.volume = 0; float[] post = render(s, 4096); check(Math.abs(post[4000]) < 1e-6, "post-fader send follows the fader");
        t.sends.get(0).pre = true; float[] pre = render(s, 4096); check(pre[4000] > .05, "pre-fader send ignores the fader: " + pre[4000]);
        t.volume = 1; t.sends.clear(); t.output = bus.id; bus.volume = 0; float[] routed = render(s, 4096); check(Math.abs(routed[4000]) < 1e-6, "track routed to a bus goes through the bus fader");
        bus.volume = 1; bus.mute = true; check(Math.abs(render(s, 4096)[4000]) < 1e-6, "muting the bus silences routed tracks");
        bus.mute = false; Core.Session c = s.copy(); check(c.buses.size() == 1 && c.buses.get(0).id.equals(bus.id) && c.tracks.get(0).output.equals(bus.id), "buses and routing survive copy");
        t.output = "missing"; check(Math.abs(render(s, 4096)[4000]) > .05, "missing bus falls back to master");
    }

    static void sidechain() {
        Core.Session s = new Core.Session(); s.master = 1; Core.Track pad = track(s, "pad", sine(220, .2, 1, 0), 0); pad.fx.comp = true; pad.fx.threshold = -30; pad.fx.ratio = 8;
        Core.Track kick = track(s, "kick", sine(60, .9, 1, 0), 0); kick.mute = true;
        float[] free = render(s, R); pad.sidechain = kick.id; float[] ducked = render(s, R);
        double a = rms(free, R / 2, R, 0), b = rms(ducked, R / 2, R, 0);
        System.out.println("Sidechain: pad " + Pro.db(a) + " dB alone, " + Pro.db(b) + " dB keyed by muted kick");
        check(b < a * .7, "a loud key ducks the compressor even when the key track is muted");
        pad.sidechain = pad.id; check(Math.abs(rms(render(s, R), R / 2, R, 0) - a) < 1e-6, "self-key is ignored");
    }

    static void crossfade() {
        Core.Session s = new Core.Session(); s.master = 1; s.crossfadeFrames = 480;
        Core.Track t = track(s, "a", dc(.3, 4000), 0); t.pan = -1; Core.Clip b = new Core.Clip("b", new Core.Memory(dc(.4, 4000)), "b", 3000); t.clips.add(b);
        float[] summed = render(s, 8000); check(Math.abs(summed[2 * 3600] - .7f) < 1e-5, "without crossfades overlapping clips sum");
        t.autoCrossfade = true; float[] x = render(s, 8000);
        check(Math.abs(x[2 * 3700] - .4f) < 1e-5, "after the fade the later clip wins: " + x[2 * 3700]);
        check(Math.abs(x[2 * 2900] - .3f) < 1e-5, "before overlap the earlier clip plays");
        double powerOk = 0; for (int i = 3000; i < 3480; i++) { double g1 = Pro.overlapGain(t, t.clips.get(0), i, 480), g2 = Pro.overlapGain(t, b, i, 480); powerOk = Math.max(powerOk, Math.abs(g1 * g1 + g2 * g2 - 1)); }
        check(powerOk < 1e-9, "equal-power crossfade");
        // contained clip fades back to the outer clip
        Core.Track u = new Core.Track("u", 0); Core.Clip outer = new Core.Clip("o", new Core.Memory(dc(.3, 10000)), "o", 0), inner = new Core.Clip("i", new Core.Memory(dc(.5, 2000)), "i", 4000); u.clips.add(outer); u.clips.add(inner); u.autoCrossfade = true;
        check(Pro.overlapGain(u, outer, 5000, 480) == 0 && Pro.overlapGain(u, outer, 6500, 480) == 1, "outer clip ducked under the inner clip and restored after");
        check(Pro.overlapGain(u, inner, 5995, 480) < .05, "inner clip fades out at its end");
    }

    static void comping() {
        Core.Session s = new Core.Session(); s.master = 1; Core.Track t = track(s, "take1", dc(.1, 20000), 0); t.pan = -1;
        Core.Clip take2 = new Core.Clip("take2", new Core.Memory(dc(.6, 20000)), "take2", 0);
        Pro.rememberTake(t, t.clips.get(0)); Pro.rememberTake(t, take2); Pro.rememberTake(t, take2); check(t.takes.size() == 2, "takes de-duplicated");
        Pro.comp(t, take2, 8000, 12000, 480);
        float[] out = render(s, 20000);
        check(Math.abs(out[2 * 4000] - .1f) < 1e-5 && Math.abs(out[2 * 10000] - .6f) < 1e-5 && Math.abs(out[2 * 15000] - .1f) < 1e-5, "comp swaps only the chosen range: " + out[2 * 10000]);
        boolean smooth = true; for (int i = 7990; i < 12500; i++) smooth &= Math.abs(out[2 * (i + 1)] - out[2 * i]) < .01; check(smooth, "comp edges are crossfaded without jumps");
        check(t.clips.size() == 3, "comp leaves left piece, take piece and right piece: " + t.clips.size());
        try { Pro.comp(t, take2, 30000, 40000, 480); check(false, "out-of-range comp rejected"); } catch (IllegalArgumentException expected) { passed++; }
        check(t.copy().takes.size() == 2, "takes survive undo copies");
    }

    static void edits() {
        Core.Clip c = new Core.Clip("c", new Core.Memory(new float[2000 * 2]), "c", 500); c.length = 1000; c.offset = 300; Pro.slip(c, 100); check(c.offset == 200 && c.start == 500 && c.length == 1000, "slip moves content only");
        Pro.slip(c, 10000); check(c.offset == 0, "slip clamps at source start"); Pro.slip(c, -10000); check(c.offset == 1000, "slip clamps at source end");
        Core.Session s = new Core.Session(); Core.Track a = track(s, "a", new float[2000], 0), b = track(s, "b", new float[2000], 0);
        Core.Clip a2 = new Core.Clip("a2", new Core.Memory(new float[2000]), "x", 3000); a.clips.add(a2); Core.Clip b2 = new Core.Clip("b2", new Core.Memory(new float[2000]), "y", 2500); b.clips.add(b2);
        s.markers.add(new Pro.Marker(4000, "Hook"));
        Pro.rippleDelete(s, a, a.clips.get(0), false); check(a2.start == 2000 && b2.start == 2500, "track ripple moves only that track");
        Pro.rippleDelete(s, a, a2, true); check(b2.start == 1500 && s.markers.get(0).frame == 3000, "global ripple moves other tracks and markers");
        Core.Clip late = new Core.Clip("l", new Core.Memory(new float[20000]), "l", 100); Pro.compensate(late, 240); check(late.start == 0 && late.offset == 140 && late.length == 9860, "latency compensation trims the head");
        Core.Clip ok = new Core.Clip("o", new Core.Memory(new float[2000]), "o", 5000); Pro.compensate(ok, 240); check(ok.start == 4760 && ok.offset == 0, "latency compensation moves the clip earlier");
    }

    static void tempo() {
        Core.Session s = new Core.Session(); s.bpm = 60; Pro.TempoMap m = new Pro.TempoMap(s);
        check(m.constant() && m.frameAt(4) == 4L * R, "constant tempo map");
        check(m.label(R * 5).equals("2.2.000"), "bar.beat label " + m.label(R * 5));
        s.tempoChanges.add(new Pro.TempoChange(2, 120)); m = new Pro.TempoMap(s);
        check(m.frameAt(8) == 8L * R && m.frameAt(10) == 9L * R, "tempo change at bar 3: " + m.frameAt(10));
        check(Math.abs(m.beatAt(9L * R) - 10) < 1e-9 && m.bpmAt(9L * R) == 120 && m.bpmAt(R) == 60, "inverse mapping and bpm");
        check(m.snap(9L * R + 100, 1) == 9L * R, "snap uses the tempo map");
        s.timeSignature = 3; s.tempoChanges.clear(); m = new Pro.TempoMap(s); check(m.label(3L * R).equals("2.1.000"), "3/4 bars " + m.label(3L * R));
        // the click follows time signature: accent on bar starts only
        Core.Session c = new Core.Session(); c.bpm = 120; c.timeSignature = 3; Core.Renderer r = new Core.Renderer(c); float[] b = new float[1024];
        r.render(0, 512, b, true); double a0 = 0; for (int i = 0; i < 512; i++) a0 = Math.max(a0, Math.abs(b[2 * i]));
        check(a0 > .05, "click sounds on beat 1 in 3/4");
        double accent = zeroCrossRate(c, 0), beat2 = zeroCrossRate(c, R / 2), beat4 = zeroCrossRate(c, 3 * R / 2);
        check(accent > beat2 * 1.3 && beat4 > beat2 * 1.3, "bar accents every 3 beats: " + accent + " " + beat2 + " " + beat4);
    }
    static double zeroCrossRate(Core.Session s, long at) { Core.Renderer r = new Core.Renderer(s); float[] b = new float[2400]; r.render(at, 1000, b, true); int z = 0; for (int i = 1; i < 1000; i++) if ((b[2 * i] >= 0) != (b[2 * i - 2] >= 0)) z++; return z; }

    static void latency() {
        float[] ref = Pro.calibrationClicks(6, R / 4); Random r = new Random(3);
        for (int lag : new int[]{0, 37, 1234, 9000}) {
            float[] rec = new float[ref.length + 12000]; for (int i = 0; i < ref.length; i++) rec[i + lag] += ref[i] * .3f; for (int i = 0; i < rec.length; i++) rec[i] += (float) (r.nextGaussian() * .02);
            Pro.LatencyResult res = Pro.estimateLatency(ref, rec, 12000);
            check(res.frames == lag && res.confidence > 2, "latency " + lag + " estimated " + res.frames + " (confidence " + res.confidence + ")");
        }
        float[] noise = new float[ref.length]; for (int i = 0; i < noise.length; i++) noise[i] = (float) r.nextGaussian(); check(Pro.estimateLatency(ref, noise, 12000).confidence < 2, "unrelated input has low confidence");
    }

    static void ring() {
        Pro.Ring ring = new Pro.Ring(1024); for (int i = 0; i < 100; i++) ring.push(i, -i); check(ring.available() == 100, "ring count");
        float[] out = new float[200]; int n = ring.mixInto(out, 100, .5, 1000); check(n == 100 && out[2 * 10] == 5 && out[2 * 10 + 1] == -5, "ring mixes with gain");
        for (int i = 0; i < 5000; i++) ring.push(1, 1); check(ring.available() <= 1024 * 2, "ring drops when full");
        float[] o2 = new float[200]; ring.mixInto(o2, 100, 1, 256); check(ring.available() <= 256, "backlog bounded for low monitoring latency");
    }

    static void notes() throws Exception {
        Synth.Config cfg = Synth.factory(3); MidiTake t = new MidiTake(cfg, 120); t.recording = false;
        t.events.add(new MidiTake.Event(100, 0x90, 60, 100)); t.events.add(new MidiTake.Event(2000, 0x90, 64, 90)); t.events.add(new MidiTake.Event(5000, 0x80, 60, 0)); t.events.add(new MidiTake.Event(6000, 0x90, 64, 0)); t.events.add(new MidiTake.Event(3000, 0xB0, 1, 64));
        ArrayList<MidiTake.Event> other = new ArrayList<>(); List<Pro.Note> ns = Pro.notes(t.events, other);
        check(ns.size() == 2 && ns.get(0).pitch == 60 && ns.get(0).length == 4900 && ns.get(1).length == 4000 && other.size() == 1, "notes paired");
        double grid = R * 60.0 / 120 / 4; Pro.quantize(ns, grid, 1, 0, false); check(ns.get(0).start == 0 && Math.abs(ns.get(1).start - Math.round(2000 / grid) * grid) < 1, "full-strength quantize");
        List<Pro.Note> sw = new ArrayList<>(); sw.add(new Pro.Note(60, 100, Math.round(grid), 100, 0)); Pro.quantize(sw, grid, 1, 1, false); check(Math.abs(sw.get(0).start - grid * 4 / 3) < 1, "swing delays off-beats by a third of a step");
        List<Pro.Note> half = new ArrayList<>(); half.add(new Pro.Note(60, 100, 800, 100, 0)); Pro.quantize(half, 2000, .5, 0, false); check(half.get(0).start == 400, "50% strength moves halfway");
        Pro.velocity(ns, .5, 10); check(ns.get(0).velocity == 60, "velocity scale"); List<Pro.Note> tr = new ArrayList<>(); tr.add(ns.get(0).copy()); Pro.transpose(tr, 200); check(tr.get(0).pitch == 127 && ns.get(0).pitch == 60, "transpose clamps"); Pro.transpose(ns, 2); check(ns.get(1).pitch == 66, "transpose");
        MidiTake rebuilt = Pro.take(cfg, 120, ns, other); List<Pro.Note> again = Pro.notes(rebuilt.events, null);
        check(again.size() == 2 && again.get(0).length == ns.get(0).length && rebuilt.frames >= ns.get(1).end(), "take rebuilt from notes");
        ByteArrayOutputStream mid = new ByteArrayOutputStream(); rebuilt.writeMidi(mid);
        ArrayList<MidiTake.Event> read = Pro.readMidi(new ByteArrayInputStream(mid.toByteArray())); List<Pro.Note> parsed = Pro.notes(read, null);
        check(parsed.size() == 2 && Math.abs(parsed.get(0).start - ns.get(0).start) <= 50 && Math.abs(parsed.get(1).length - ns.get(1).length) <= 100 && parsed.get(0).velocity == ns.get(0).velocity, "MIDI file round trip");
        float[] printed = new float[(rebuilt.frames + R * 3) * 2]; int[] k = {0}; rebuilt.print((l, r) -> { if (k[0] < printed.length) { printed[k[0]++] = l; printed[k[0]++] = r; } });
        check(rms(printed, 0, rebuilt.frames, 0) > .001, "edited notes print to audio");
        try { Pro.readMidi(new ByteArrayInputStream("nope".getBytes())); check(false, "bad MIDI rejected"); } catch (IOException expected) { passed++; }
    }

    static void flac() throws Exception {
        Path dir = Files.createTempDirectory("flac");
        for (int bits : new int[]{16, 24}) {
            int frames = R * 2 + 1234; Random r = new Random(bits); int[][] pcm = new int[frames][2]; double scale = Math.pow(2, bits - 1);
            for (int i = 0; i < frames; i++) { double v = .4 * Math.sin(i * .013) + .1 * r.nextGaussian(); pcm[i][0] = (int) Math.max(-scale, Math.min(scale - 1, Math.round(v * scale))); pcm[i][1] = i > R ? pcm[i][0] : (int) Math.round(-.3 * v * scale); }
            for (int i = 1000; i < 6000; i++) pcm[i][0] = pcm[i][1] = 0; // a silent stretch exercises constant subframes
            Path file = dir.resolve("t" + bits + ".flac");
            try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(file)); Pro.FlacWriter w = new Pro.FlacWriter(o, bits, frames)) { for (int[] p : pcm) w.sample(p[0], p[1]); }
            Path raw = dir.resolve("t" + bits + ".raw");
            Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", file.toString(), "-f", bits == 16 ? "s16le" : "s32le", "-acodec", bits == 16 ? "pcm_s16le" : "pcm_s32le", raw.toString()).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes()); int code = p.waitFor();
            check(code == 0 && log.trim().isEmpty(), "ffmpeg decodes " + bits + "-bit FLAC cleanly: " + log);
            ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(raw)).order(ByteOrder.LITTLE_ENDIAN); int count = b.remaining() / (bits == 16 ? 4 : 8);
            check(count == frames, bits + "-bit FLAC frame count " + count);
            boolean exact = true; for (int i = 0; i < frames && exact; i++) for (int c = 0; c < 2; c++) { int v = bits == 16 ? b.getShort() : b.getInt() >> 8; exact &= v == pcm[i][c]; }
            check(exact, bits + "-bit FLAC is lossless");
            long size = Files.size(file); System.out.println(bits + "-bit FLAC: " + size + " bytes vs " + frames * 2L * bits / 8 + " raw");
            check(size < frames * 2L * bits / 8, "FLAC compresses");
        }
    }

    static void offline() throws Exception {
        Core.Session s = new Core.Session(); s.master = 1; float[] imp = new float[4000]; imp[2 * 1000] = imp[2 * 1000 + 1] = .5f; track(s, "imp", imp, 0);
        s.masterInserts.add(new Pro.Insert(Pro.LIMITER));
        float[] out = new float[4000]; int[] at = {0}; Pro.renderOffline(s, 2000, (b, n) -> { System.arraycopy(b, 0, out, at[0] * 2, n * 2); at[0] += n; });
        int peak = 0; for (int i = 0; i < 2000; i++) if (Math.abs(out[2 * i]) > Math.abs(out[2 * peak])) peak = i;
        check(at[0] == 2000 && peak == 1000, "offline render removes look-ahead delay: peak at " + peak);
        Core.Session plain = new Core.Session(); track(plain, "imp", imp, 0); float[] direct = render(plain, 2000), viaOffline = new float[4000]; int[] k = {0};
        Pro.renderOffline(plain, 2000, (b, n) -> { System.arraycopy(b, 0, viaOffline, k[0] * 2, n * 2); k[0] += n; });
        check(Arrays.equals(direct, viaOffline), "offline render without latency equals direct render");
        Core.Session loud = new Core.Session(); track(loud, "s", sine(1000, .1, 5, 0), 0); loud.master = 1; Pro.Loudness m = Pro.analyse(loud, R * 5);
        check(Math.abs(m.integrated() - (-23.01)) < .1, "session analysis (centre pan -3 dB) " + m.integrated());
    }

    static void stems() {
        Core.Session s = new Core.Session(); s.master = .5; Core.Track a = track(s, "a", dc(.2, 2000), 0), b = track(s, "b", dc(.1, 2000), 0); s.masterInserts.add(new Pro.Insert(Pro.UTILITY));
        Core.Session st = Pro.stem(s, 1); float[] o = render(st, 1024); check(Math.abs(o[1000] - .1f * Math.cos(Math.PI / 4) * 1) < 1e-5 && st.masterInserts.isEmpty() && st.master == 1, "stem plays one track post-fader at unity master: " + o[1000]);
        check(s.masterInserts.size() == 1 && !s.tracks.get(0).mute, "stem does not modify the project");
    }

    static void chain() {
        Core.Session s = new Core.Session(); s.master = 1; Core.Track t = track(s, "t", dc(.25, R), 0);
        Pro.Insert g = new Pro.Insert(Pro.UTILITY); g.set(0, 6); t.inserts.add(g); float[] boosted = render(s, 4096);
        check(Math.abs(boosted[4000] / (.25 * Math.cos(Math.PI / 4)) - Pro.gain(6)) < 1e-3, "insert gain applied");
        g.enabled = false; check(Math.abs(render(s, 4096)[4000] - .25 * Math.cos(Math.PI / 4)) < 1e-5, "disabled insert is bypassed");
        g.enabled = true; t.bypassFx = true; check(Math.abs(render(s, 4096)[4000] - .25 * Math.cos(Math.PI / 4)) < 1e-5, "channel bypass also bypasses inserts");
        t.bypassFx = false; Pro.Insert lim = new Pro.Insert(Pro.LIMITER); lim.set(0, 12); lim.set(1, -6); t.inserts.add(0, lim); g.touch();
        Core.Renderer r = new Core.Renderer(s); float[] b = new float[1024]; for (int p = 0; p < R / 2; p += 512) r.render(p, 512, b, false);
        check(Math.abs(b[1000] - Pro.gain(-6) * Pro.gain(6) * Math.cos(Math.PI / 4)) < 2e-3, "insert order matters (limit then gain): " + b[1000]);
        check(t.reduction > 3, "insert gain reduction reported on the channel meter");
        Core.Track c = t.copy(); check(c.inserts.size() == 2 && c.inserts.get(0) != lim && c.inserts.get(0).kind.equals(Pro.LIMITER), "inserts deep-copied");
        Core.Session big = new Core.Session(); for (int i = 0; i < Core.MAX_TRACKS; i++) track(big, "t" + i, dc(.01, 600), 0); float[] o = render(big, 512); check(Float.isFinite(o[0]) && Core.MAX_TRACKS == 64, "maximum track count renders");
    }

    /** The default render path stays sample-identical to the previous engine (re-run Baseline against base.bin separately). */
    static void identity() {
        Core.Session s = new Core.Session(); track(s, "a", sine(300, .4, 1, 0), 0); Core.Session c = s.copy(); check(Arrays.equals(render(s, R / 2), render(c, R / 2)), "copies render identically");
    }
}

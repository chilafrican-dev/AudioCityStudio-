package ug.audiocity.studio;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Host checks for 0.10: audio quantize, MIDI controller mapping and the clip launcher. */
public class LiveTests {
    static int passed;
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); passed++; }
    static final int R = Core.RATE;
    static float[] render(Core.Session s, long from, int frames) { Core.Renderer r = new Core.Renderer(s); float[] all = new float[frames * 2], b = new float[1024]; for (int p = 0; p < frames; p += 512) { int n = Math.min(512, frames - p); r.render(from + p, n, b, false); System.arraycopy(b, 0, all, p * 2, n * 2); } return all; }
    static float[] hits(int frames, int[] at) { float[] a = new float[frames * 2]; Random r = new Random(1); for (int h : at) for (int j = 0; j < 2400 && h + j < frames; j++) { float v = (float) (Math.sin(2 * Math.PI * 180 * j / R) * Math.exp(-j / 500.0) * .6 + r.nextGaussian() * .02 * Math.exp(-j / 300.0)); a[2 * (h + j)] += v; a[2 * (h + j) + 1] += v; } return a; }

    public static void main(String[] args) throws Exception { quantize(); mapping(); launcher(); persistence(); System.out.println(passed + " live-performance checks passed."); }

    static void quantize() {
        Core.Session s = new Core.Session(); s.bpm = 120; s.master = 1; int beat = R / 2, sixteenth = beat / 4;
        int[] played = {beat + 300, beat * 2 - 700, beat * 3 + 900, beat * 4 - 400, beat * 5 + 600};
        Core.Clip c = new Core.Clip("drums", new Core.Memory(hits(R * 3, played)), "d", 0);
        List<Integer> on = ProLive.onsets(c, .5); check(on.size() == 5, "five hits found: " + on);
        for (int i = 0; i < 5; i++) check(Math.abs(on.get(i) - played[i]) < 160, "onset " + i + " within 3 ms: " + on.get(i) + " vs " + played[i]);
        Core.Track t = new Core.Track("t", 0); t.volume = 1; t.pan = -1; t.fx.eq = false; t.clips.add(c); s.tracks.add(t);
        List<Core.Clip> slices = ProLive.quantizeAudio(t, c, new Pro.TempoMap(s), 4, 1, .5, 240);
        check(slices.size() == 6 && t.autoCrossfade && !t.clips.contains(c), "clip sliced at hits (plus the lead-in): " + slices.size());
        float[] out = render(s, 0, R * 3); Core.Clip probe = new Core.Clip("o", new Core.Memory(out), "o", 0);
        List<Integer> after = ProLive.onsets(probe, .5); check(after.size() == 5, "five hits after quantize: " + after);
        for (int i = 0; i < 5; i++) check(Math.abs(after.get(i) - (i + 1) * beat) < 200, "hit " + i + " on the grid: " + after.get(i) + " vs " + ((i + 1) * beat));
        Core.Track half = new Core.Track("h", 0); Core.Clip c2 = new Core.Clip("d", new Core.Memory(hits(R * 3, played)), "d", 0); half.clips.add(c2);
        List<Core.Clip> h = ProLive.quantizeAudio(half, c2, new Pro.TempoMap(s), 4, .5, .5, 240); check(Math.abs(h.get(2).start - (beat * 2 - 350)) < 160, "50% strength moves halfway: " + h.get(2).start);
        Core.Track q = new Core.Track("q", 0); Core.Clip silent = new Core.Clip("s", new Core.Memory(new float[R * 2]), "s", 0); q.clips.add(silent); check(ProLive.quantizeAudio(q, silent, new Pro.TempoMap(s), 4, 1, .5, 240).isEmpty() && q.clips.contains(silent), "silence is left alone");
    }

    static void mapping() {
        Core.Session s = new Core.Session(); Core.Track t = new Core.Track("t", 0); s.tracks.add(t); Core.Track bus = new Core.Track("b", 0); bus.bus = true; s.buses.add(bus); t.sends.add(new Pro.Send(bus.id, 0, false));
        Pro.Insert eq = new Pro.Insert(Pro.EQ8); t.inserts.add(eq);
        ProLive.Mapping vol = new ProLive.Mapping(-1, 7, false, "track:" + t.id + ":volume");
        check(vol.matches(0xB3, 7) && !vol.matches(0xB0, 8) && !vol.matches(0x90, 7), "CC 7 on any channel");
        ProLive.apply(s, vol, 127); check(Math.abs(t.volume - Core.faderGain(1)) < 1e-12, "full CC = top of fader"); ProLive.apply(s, vol, 0); check(t.volume == 0, "zero CC = silent");
        ProLive.apply(s, new ProLive.Mapping(-1, 10, false, "track:" + t.id + ":pan"), 0); check(t.pan == -1, "pan left");
        ProLive.Mapping pad = new ProLive.Mapping(9, 36, true, "track:" + t.id + ":mute"); check(pad.matches(0x99, 36) && !pad.matches(0x90, 36), "pad on channel 10");
        ProLive.apply(s, pad, 100); check(t.mute, "pad toggles mute on"); ProLive.apply(s, pad, 100); check(!t.mute, "and off");
        ProLive.apply(s, new ProLive.Mapping(-1, 20, false, "track:" + t.id + ":send:" + bus.id), 127); check(Math.abs(t.sends.get(0).level - 2) < 1e-9, "send level");
        ProLive.apply(s, new ProLive.Mapping(-1, 21, false, "track:" + t.id + ":insert:" + eq.id + ":7"), 127); check(Math.abs(eq.values[7] - 20000) < 1e-6, "insert frequency at maximum");
        ProLive.apply(s, new ProLive.Mapping(-1, 21, false, "track:" + t.id + ":insert:" + eq.id + ":7"), 64); check(eq.values[7] > 600 && eq.values[7] < 700, "frequency knob is logarithmic: " + eq.values[7]);
        check(ProLive.PLAY.equals(ProLive.apply(s, new ProLive.Mapping(-1, 60, true, ProLive.PLAY), 100)) && ProLive.apply(s, new ProLive.Mapping(-1, 64, false, ProLive.PLAY), 10) == null, "transport actions on press only");
        ProLive.apply(s, new ProLive.Mapping(-1, 1, false, "track:missing:volume"), 127); passed++;
    }

    static void launcher() {
        Core.Session s = new Core.Session(); s.bpm = 120; s.master = 1; int bar = R * 2;
        Core.Track t = new Core.Track("t", 0); t.volume = 1; t.pan = -1; t.fx.eq = false; float[] tl = new float[bar * 8 * 2]; Arrays.fill(tl, .1f); t.clips.add(new Core.Clip("timeline", new Core.Memory(tl), "tl", 0)); s.tracks.add(t);
        float[] loop = new float[1000 * 2]; for (int i = 0; i < 1000; i++) loop[2 * i] = loop[2 * i + 1] = i < 500 ? .5f : .25f;
        ProLive.put(t, 2, new Core.Clip("loop", new Core.Memory(loop), "loop", 12345)); check(t.slots.size() == 3 && t.slots.get(0) == null && t.slots.get(2).start == 0, "slot assigned (copy at 0)");
        ProLive.launch(s, t, 2, bar / 2, true); check(t.launchAt == bar, "launch waits for the next bar: " + t.launchAt);
        float[] o = render(s, 0, bar * 2); check(Math.abs(o[2 * (bar - 10)] - .1f) < 1e-6, "arrangement plays before the launch");
        check(Math.abs(o[2 * bar] - .5f) < 1e-6 && Math.abs(o[2 * (bar + 700)] - .25f) < 1e-6 && Math.abs(o[2 * (bar + 1000)] - .5f) < 1e-6, "launched clip replaces the track and loops");
        ProLive.stop(s, t, bar + 100, true); check(t.launchStop == bar * 2, "stop at the next bar");
        float[] o2 = render(s, bar * 2 - 100, 300); check(Math.abs(o2[0] - .5f) < 1e-6 || Math.abs(o2[0] - .25f) < 1e-6, "still looping before the stop"); check(Math.abs(o2[2 * 200] - .1f) < 1e-6, "arrangement resumes at the stop"); render(s, bar * 2 + 500, 100); check(t.launched == null, "launch state cleared after the stop");
        Core.Track u = new Core.Track("u", 0); s.tracks.add(u); ProLive.put(u, 0, new Core.Clip("x", new Core.Memory(loop), "x", 0));
        ProLive.scene(s, 2, 0, false); check(t.launched != null && t.launchAt == 0 && u.launched == null, "scene launches the column now when stopped; empty slots stop");
        ProLive.stopAll(s, 0, false); check(t.launched == null, "stop all");
        check(ProLive.nextBar(new Pro.TempoMap(s), bar) == bar && ProLive.nextBar(new Pro.TempoMap(s), bar + 1) == 2L * bar, "next bar rule");
        Core.Track copy = t.copy(); check(copy.slots.size() == 3 && copy.slots.get(2) != t.slots.get(2) && copy.launched == null, "slots copied for undo; playback state is not");
    }

    static void persistence() throws Exception {
        File dir = Files.createTempDirectory("acs10").toFile(); float[] audio = new float[2000]; Arrays.fill(audio, .2f);
        String name = "loop.pcm"; ByteBuffer bb = ByteBuffer.allocate(audio.length * 4).order(ByteOrder.LITTLE_ENDIAN); for (float v : audio) bb.putFloat(v); Files.write(new File(dir, name).toPath(), bb.array());
        Core.Session s = new Core.Session(); s.directory = dir.getAbsolutePath(); Core.Track t = new Core.Track("t", 0); s.tracks.add(t);
        ProLive.put(t, 1, new Core.Clip("loop", new Core.Memory(audio), name, 0)); ProLive.put(t, 3, ProMidi.clip("keys", Arrays.asList(new Pro.Note(60, 100, 0, 4800, 0)), Synth.factory(0), 0, 9600));
        s.midiMap.add(new ProLive.Mapping(-1, 7, false, "track:" + t.id + ":volume")); s.midiMap.add(new ProLive.Mapping(9, 36, true, ProLive.PLAY));
        ProjectIO.save(s);
        ProjectIO.Opener open = f -> { byte[] b = Files.readAllBytes(f.toPath()); FloatBuffer fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); float[] x = new float[fb.remaining()]; fb.get(x); return new Core.Memory(x); };
        Core.Session l = ProjectIO.load(dir, open); Core.Track lt = l.tracks.get(0);
        check(lt.slots.size() == 4 && lt.slots.get(0) == null && lt.slots.get(1).file.equals(name) && lt.slots.get(3).notes.size() == 1 && lt.slots.get(2) == null, "launcher slots saved, including a live MIDI clip");
        check(l.midiMap.size() == 2 && l.midiMap.get(1).note && l.midiMap.get(1).channel == 9 && l.midiMap.get(0).target.endsWith(":volume"), "controller mappings saved");
        check(ProjectIO.files(s).contains(name), "slot audio included in backups");
        File bad = new File(dir, "bad"); bad.mkdirs(); Files.write(new File(bad, "session.json").toPath(), "{\"schema\":1,\"tracks\":[],\"midiMap\":[{\"channel\":3,\"number\":999,\"target\":\"track:x:volume\"},{\"channel\":0,\"number\":1,\"target\":\"evil;rm\"},{\"channel\":0,\"number\":2,\"target\":\"master:volume\"}]}".getBytes("UTF-8"));
        check(ProjectIO.load(bad, open).midiMap.size() == 1, "invalid mappings rejected");
    }
}

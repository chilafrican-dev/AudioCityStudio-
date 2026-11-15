package ug.audiocity.studio;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Host checks for 0.9: live MIDI clips, per-clip time-stretch, plugin delay compensation and the larger limits. */
public class ModernDawTests {
    static int passed;
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); passed++; }
    static final int R = Core.RATE;
    static float[] render(Core.Session s, int frames) { Core.Renderer r = new Core.Renderer(s); float[] all = new float[frames * 2], b = new float[1024]; for (int p = 0; p < frames; p += 512) { int n = Math.min(512, frames - p); r.render(p, n, b, false); System.arraycopy(b, 0, all, p * 2, n * 2); } return all; }
    static int peakAt(float[] a, int ch) { int best = 0; for (int i = 0; i < a.length / 2; i++) if (Math.abs(a[2 * i + ch]) > Math.abs(a[2 * best + ch])) best = i; return best; }
    static double rms(float[] a, int from, int to) { double s = 0; for (int i = from; i < to; i++) s += a[2 * i] * (double) a[2 * i]; return Math.sqrt(s / Math.max(1, to - from)); }
    static List<Pro.Note> chord() { return new ArrayList<>(Arrays.asList(new Pro.Note(60, 100, 0, R / 2, 0), new Pro.Note(64, 90, 0, R / 2, 0), new Pro.Note(67, 80, R / 2, R / 2, 0))); }

    public static void main(String[] args) throws Exception {
        liveMidi(); midiScheduling(); persistence(); stretch(); pdc(); limits();
        System.out.println(passed + " modern-DAW checks passed.");
    }

    static void liveMidi() {
        Core.Clip c = ProMidi.clip("keys", chord(), Synth.factory(0), 1000, 2 * R);
        check(c.file.isEmpty() && c.notes.size() == 3 && c.length == 2 * R && c.source instanceof ProMidi.LiveSource && c.source.frames() >= 2 * R, "live clip keeps notes and synthesizes on demand");
        Core.Session s = new Core.Session(); s.master = 1; Core.Track t = new Core.Track("t", 0); t.clips.add(c); s.tracks.add(t);
        float[] a = render(s, 2 * R + 2000); check(rms(a, 1000, 1000 + R / 4) > .005, "notes sound");
        Core.Clip copy = c.copy(); copy.notes.get(0).pitch = 72; check(c.notes.get(0).pitch == 60 && copy.instrument != c.instrument, "copies are independent (undo-safe)");
        Core.Clip edited = c.copy(); edited.notes.clear(); edited.notes.add(new Pro.Note(48, 110, 0, R, 0)); ProMidi.refresh(edited);
        Core.Session changed = new Core.Session(); changed.master = 1; Core.Track changedTrack = new Core.Track("t", 0); changedTrack.clips.add(edited); changed.tracks.add(changedTrack);
        check(!Arrays.equals(a, render(changed, 2 * R + 2000)) && edited.length == c.length && edited.source instanceof ProMidi.LiveSource, "edited notes use a fresh live source without changing clip length");
        Core.Clip swapped = c.copy(); swapped.instrument = Synth.factory(7); ProMidi.refresh(swapped);
        Core.Session changedInstrument = new Core.Session(); changedInstrument.master = 1; Core.Track instrumentTrack = new Core.Track("t", 0); instrumentTrack.clips.add(swapped); changedInstrument.tracks.add(instrumentTrack);
        check(!Arrays.equals(a, render(changedInstrument, 2 * R + 2000)), "changing instrument updates live playback");
        Core.Clip empty = ProMidi.clip("e", new ArrayList<>(), Synth.factory(3), 0, R); check(empty.length == R && empty.source.sample(0, 0) == 0 && empty.source.sample(R - 1, 1) == 0, "empty live clip is silent with its length");
        Core.Clip r = c.copy(); ProMidi.retime(r, 2); check(r.notes.get(2).start == R / 4 && r.start == 500 && r.length == R, "tempo change scales notes");
        MidiTake take = new MidiTake(Synth.factory(0), 100); take.recording = false; take.events.add(new MidiTake.Event(10, 0x90, 60, 90)); take.events.add(new MidiTake.Event(4810, 0x80, 60, 0)); take.frames = 4810;
        check(ProMidi.fromTake(take).size() == 1 && ProMidi.fromTake(take).get(0).length == 4800, "recording becomes notes");
    }

    static void midiScheduling() {
        float[] sample = new float[4000];
        for (int i = 0; i < sample.length; i++) sample[i] = .25f;
        SynthPoly voices = new SynthPoly(Synth.factory(0));
        voices.setSamplePack(SampledInstruments.monoPack("test sample", sample, 60, "test"));
        ArrayList<MidiTake.Event> events = new ArrayList<>();
        events.add(new MidiTake.Event(0, 0x90, 60, 110));
        events.add(new MidiTake.Event(1000, 0x80, 60, 0));
        voices.schedule(events, 100, 15);
        float[] block = new float[1024];
        voices.render(block, 512);
        boolean silentBeforeStart = true, audibleAfterStart = false;
        for (int i = 0; i < 512; i++) {
            if (i < 100) silentBeforeStart &= block[2 * i] == 0;
            else audibleAfterStart |= Math.abs(block[2 * i]) > 0;
        }
        check(silentBeforeStart && audibleAfterStart, "sampled MIDI event starts at its scheduled sample");
        voices.render(block, 512);
        voices.render(block, 512);
        voices.render(block, 512);
        voices.render(block, 512);
        check(voices.activeVoices == 0, "scheduled note-off releases the shared sampled voice");
        SampledInstruments.Pack pack = SampledInstruments.monoPack("Audio City sample", sample, 60, "test");
        Core.Clip clip = ProMidi.clip("sampled", Collections.singletonList(new Pro.Note(60, 110, 0, 1000, 0)), Synth.factory(0), pack, "factory-test/sample.sfz", 0, 2000);
        Core.Session sampleSession = new Core.Session(); sampleSession.master = 1; Core.Track sampleTrack = new Core.Track("sample", 0); sampleTrack.clips.add(clip); sampleSession.tracks.add(sampleTrack);
        check(clip.source instanceof ProMidi.LiveSource && clip.samplePack == pack && clip.instrumentPackId.equals("factory-test/sample.sfz") && rms(render(sampleSession, 2000), 0, 1000) > .005, "live MIDI clip uses and retains its assigned sampled instrument");
    }

    static void persistence() throws Exception {
        File dir = Files.createTempDirectory("acs09").toFile();
        Core.Session s = new Core.Session(); s.directory = dir.getAbsolutePath(); s.master = 1; Core.Track t = new Core.Track("t", 0); s.tracks.add(t);
        Synth.Config cfg = Synth.factory(4); cfg.release = .5; Core.Clip c = ProMidi.clip("pad", chord(), cfg, 4800, 2 * R); c.instrumentPackId = "factory-test/sample.sfz"; c.offset = 100; c.length = R; c.gain = .7; t.clips.add(c);
        float[] audio = new float[R * 2]; for (int i = 0; i < R; i++) audio[2 * i] = audio[2 * i + 1] = (float) Math.sin(i * .05) * .3f;
        String name = "orig.pcm"; ByteBuffer bb = ByteBuffer.allocate(audio.length * 4).order(ByteOrder.LITTLE_ENDIAN); for (float v : audio) bb.putFloat(v); Files.write(new File(dir, name).toPath(), bb.array());
        Core.Clip a = new Core.Clip("a", new Core.Memory(audio), name, 0); a.stretchFile = name; a.stretchOffset = 10; a.stretchLength = 1000; a.stretchRatio = 1.25; Core.Track t2 = new Core.Track("t2", 0); t2.clips.add(a); s.tracks.add(t2);
        ProjectIO.save(s);
        ProjectIO.Opener open = f -> { byte[] b = Files.readAllBytes(f.toPath()); FloatBuffer fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); float[] x = new float[fb.remaining()]; fb.get(x); return new Core.Memory(x); };
        Core.Session l = ProjectIO.load(dir, open); Core.Clip lc = l.tracks.get(0).clips.get(0), la = l.tracks.get(1).clips.get(0);
        check(lc.notes.size() == 3 && lc.instrument.patch == 4 && "factory-test/sample.sfz".equals(lc.instrumentPackId) && Math.abs(lc.instrument.release - .5) < 1e-9 && lc.offset == 100 && lc.length == R && lc.start == 4800 && Math.abs(lc.gain - .7) < 1e-9, "live MIDI clip instrument assignment saved and restored");
        check(Arrays.equals(render(s, R + 6000), render(l, R + 6000)), "reloaded live clip renders identically");
        check("orig.pcm".equals(la.stretchFile) && la.stretchOffset == 10 && la.stretchLength == 1000 && Math.abs(la.stretchRatio - 1.25) < 1e-9, "stretch original kept");
        LinkedHashSet<String> files = ProjectIO.files(s); check(!files.contains("") && files.contains("orig.pcm"), "backups skip MIDI clips and include stretch originals");
        String json = new String(Files.readAllBytes(new File(dir, "session.json").toPath()), "UTF-8").replace("\"pitch\"", "\"pitch\"");
        File bad = new File(dir, "bad"); bad.mkdirs(); Files.write(new File(bad, "session.json").toPath(), "{\"schema\":1,\"tracks\":[{\"id\":\"t\",\"name\":\"n\",\"color\":1,\"clips\":[{\"name\":\"m\",\"file\":\"\",\"start\":0,\"offset\":0,\"length\":4800,\"notes\":[[0,100,200,100,0],[0,100,60,0,0],[-5,10,60,100,0],[0,100,60,100,0]],\"instrument\":{\"patch\":99}}]}]}".getBytes("UTF-8"));
        Core.Session b = ProjectIO.load(bad, open); check(b.tracks.get(0).clips.get(0).notes.size() == 1 && b.tracks.get(0).clips.get(0).instrument.patch == Synth.NAMES.length - 1, "invalid notes dropped, instrument clamped");
    }

    static void stretch() throws IOException {
        float[] tone = new float[R * 2 * 2]; for (int i = 0; i < 2 * R; i++) tone[2 * i] = tone[2 * i + 1] = (float) (.4 * Math.sin(2 * Math.PI * 440 * i / R));
        Core.Source win = ProMidi.window(new Core.Memory(tone), R / 2, R); check(win.frames() == R && win.sample(0, 0) == tone[R], "window source");
        ArrayList<float[]> out = new ArrayList<>(); TimeStretch.preservePitch(win, 1 / 1.5, (l, r) -> out.add(new float[]{l, r}));
        check(Math.abs(out.size() - 1.5 * R) <= 2, "×1.5 length: " + out.size());
        int z = 0; for (int i = 1000; i < out.size() - 1000; i++) if ((out.get(i)[0] >= 0) != (out.get(i - 1)[0] >= 0)) z++; double hz = z / 2.0 / ((out.size() - 2000) / (double) R);
        System.out.println("Stretched 440 Hz tone ×1.5 → " + hz + " Hz");
        check(Math.abs(hz - 440) < 4, "pitch preserved");
        check(Math.abs(ProMidi.tempoRatio(90, 120) - .75) < 1e-12 && Math.abs(ProMidi.barsRatio(R * 2, 2, 120, 4) - 2) < 1e-12, "tempo and bar-fit ratios");
    }

    static void pdc() throws IOException {
        // track A (left) has a look-ahead limiter; track B (right) has none. Both hit at frame 3000.
        Core.Session s = new Core.Session(); s.master = 1; float[] imp = new float[10000 * 2]; imp[2 * 3000] = imp[2 * 3000 + 1] = .3f;
        Core.Track a = new Core.Track("a", 0); a.volume = 1; a.pan = -1; a.fx.eq = false; a.clips.add(new Core.Clip("a", new Core.Memory(imp), "a", 0)); a.inserts.add(new Pro.Insert(Pro.LIMITER)); s.tracks.add(a);
        Core.Track b = new Core.Track("b", 0); b.volume = 1; b.pan = 1; b.fx.eq = false; b.clips.add(new Core.Clip("b", new Core.Memory(imp.clone()), "b", 0)); s.tracks.add(b);
        float[] o = render(s, 8000); int pa = peakAt(o, 0), pb = peakAt(o, 1); int lat = new Pro.Insert(Pro.LIMITER).create().latency(); Pro.Dsp probe = new Pro.Insert(Pro.LIMITER).create(); probe.process(new float[2], 0, 120);
        check(pa == pb && pa == 3000 + probe.latency(), "insert latency compensated: left " + pa + " right " + pb);
        // a bus with a limiter: a sent track and a direct track stay aligned
        Core.Session t = new Core.Session(); t.master = 1; Core.Track bus = new Core.Track("bus", 0); bus.bus = true; bus.volume = 1; bus.pan = -1; bus.fx.eq = false; bus.inserts.add(new Pro.Insert(Pro.LIMITER)); t.buses.add(bus);
        Core.Track c = new Core.Track("c", 0); c.volume = 1; c.pan = -1; c.fx.eq = false; c.output = bus.id; c.clips.add(new Core.Clip("c", new Core.Memory(imp.clone()), "c", 0)); t.tracks.add(c);
        Core.Track d = new Core.Track("d", 0); d.volume = 1; d.pan = 1; d.fx.eq = false; d.clips.add(new Core.Clip("d", new Core.Memory(imp.clone()), "d", 0)); t.tracks.add(d);
        float[] o2 = render(t, 8000); check(peakAt(o2, 0) == peakAt(o2, 1), "bus latency compensated: " + peakAt(o2, 0) + " vs " + peakAt(o2, 1));
        float[] off = new float[16000]; int[] k = {0}; Pro.renderOffline(s, 8000, (blk, n) -> { System.arraycopy(blk, 0, off, k[0] * 2, n * 2); k[0] += n; });
        check(peakAt(off, 0) == 3000 && peakAt(off, 1) == 3000, "export removes the compensation delay: " + peakAt(off, 0));
        a.inserts.clear(); float[] o3 = render(s, 8000); check(peakAt(o3, 0) == 3000 && peakAt(o3, 1) == 3000, "no latency, no delay");
    }

    static void limits() { check(Core.MAX_TRACKS == 64 && Core.MAX_BUSES == 16 && Core.MAX_FRAMES == 48000L * 3600, "64 tracks, 16 buses, 60 minutes"); }
}

package ug.audiocity.studio;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Host checks for the project format extensions (ProjectIO) and exporters (Export). Uses the host-only org.json and AtomicFile stubs. */
public class ProjectFeatureTests {
    static int passed;
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); passed++; }
    static final int R = Core.RATE;

    /** Reads the app's raw float PCM clip format into memory. */
    static final ProjectIO.Opener OPEN = f -> { byte[] b = Files.readAllBytes(f.toPath()); FloatBuffer fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); float[] a = new float[fb.remaining()]; fb.get(a); return new Core.Memory(a); };
    static String pcm(File dir, float[] data) throws IOException { String name = UUID.randomUUID() + ".pcm"; ByteBuffer b = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN); for (float v : data) b.putFloat(v); Files.write(new File(dir, name).toPath(), b.array()); return name; }
    static float[] tone(double hz, double amp, int frames) { float[] a = new float[frames * 2]; for (int i = 0; i < frames; i++) { float v = (float) (amp * Math.sin(2 * Math.PI * hz * i / R)); a[2 * i] = v; a[2 * i + 1] = v * .7f; } return a; }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("acs-project").toFile();
        Core.Session s = project(dir);
        roundTrip(s, dir); legacy(dir); validation(dir); templates(s); presets(s); retime(s); files(s); exports(s, dir);
        System.out.println(passed + " project/export checks passed.");
    }

    static Core.Session project(File dir) throws Exception {
        Core.Session s = new Core.Session(); s.directory = dir.getAbsolutePath(); s.title = "Pro test"; s.bpm = 96; s.timeSignature = 3; s.crossfadeFrames = 960;
        Core.Track bus = new Core.Track("Verb", 0xffc9b06b); bus.bus = true; bus.fx.room = true; bus.inserts.add(new Pro.Insert(Pro.EQ8)); s.buses.add(bus);
        for (int i = 0; i < 3; i++) {
            float[] data = tone(220 * (i + 1), .2, R * 2); String file = pcm(dir, data);
            Core.Track t = new Core.Track("Track " + i, 0xff50cfc9); Core.Clip c = new Core.Clip("clip" + i, new Core.Memory(data), file, i * 1000L); c.length = R; t.clips.add(c);
            Core.Clip take = c.copy(); take.name = "take"; take.start = 500; t.takes.add(take); s.tracks.add(t);
        }
        Core.Track a = s.tracks.get(0), b = s.tracks.get(1);
        a.sends.add(new Pro.Send(bus.id, .4, true)); a.folder = "Rhythm"; s.collapsed.add("Rhythm"); a.group = "Drums"; b.group = "Drums"; b.output = bus.id; b.sidechain = a.id; b.autoCrossfade = true;
        Pro.Insert eq = new Pro.Insert(Pro.EQ8); eq.set(18, 4.5); a.inserts.add(eq); Pro.Insert lim = new Pro.Insert(Pro.LIMITER); lim.enabled = false; a.inserts.add(lim);
        Pro.Lane v = new Pro.Lane("volume"); v.add(0, .2); v.add(R, 1.1); a.automation.put("volume", v); Pro.Lane snd = new Pro.Lane("send:" + bus.id); snd.add(100, .3); a.automation.put(snd.param, snd);
        s.markers.add(new Pro.Marker(R * 2, "Verse")); s.tempoChanges.add(new Pro.TempoChange(5, 120));
        s.loopStart = 1000; s.loopEnd = R; s.notes = "Lyrics: \"hello\"\nline 2"; float[] refData = tone(880, .3, R); s.reference = new Core.Clip("ref", new Core.Memory(refData), pcm(dir, refData), 0); s.referenceGain = .7;
        s.masterInserts.add(new Pro.Insert(Pro.MULTIBAND)); s.masterInserts.add(new Pro.Insert(Pro.LIMITER)); s.masterAutomation = new Pro.Lane("volume"); s.masterAutomation.add(0, .9);
        return s;
    }

    static float[] render(Core.Session s, int frames) { Core.Renderer r = new Core.Renderer(s); float[] all = new float[frames * 2], b = new float[1024]; for (int p = 0; p < frames; p += 512) { int n = Math.min(512, frames - p); r.render(p, n, b, false); System.arraycopy(b, 0, all, p * 2, n * 2); } return all; }

    static void roundTrip(Core.Session s, File dir) throws Exception {
        ProjectIO.save(s); Core.Session l = ProjectIO.load(dir, OPEN);
        check(l.timeSignature == 3 && l.crossfadeFrames == 960 && l.bpm == 96, "session settings");
        check(l.buses.size() == 1 && l.buses.get(0).bus && l.buses.get(0).id.equals(s.buses.get(0).id) && l.buses.get(0).fx.room && l.buses.get(0).inserts.size() == 1, "bus restored");
        Core.Track a = l.tracks.get(0), b = l.tracks.get(1);
        check(a.sends.size() == 1 && a.sends.get(0).pre && Math.abs(a.sends.get(0).level - .4) < 1e-12, "send restored");
        check("Rhythm".equals(a.folder) && l.collapsed.contains("Rhythm") && b.folder == null && s.copy().collapsed.contains("Rhythm") && s.tracks.get(0).copy().folder.equals("Rhythm"), "folders and collapsed state restored and copied");
        check("Drums".equals(a.group) && b.output.equals(l.buses.get(0).id) && b.sidechain.equals(a.id) && b.autoCrossfade, "routing restored");
        check(a.inserts.size() == 2 && a.inserts.get(0).kind.equals(Pro.EQ8) && a.inserts.get(0).values[18] == 4.5 && !a.inserts.get(1).enabled && a.inserts.get(0).id.equals(s.tracks.get(0).inserts.get(0).id), "inserts restored in order");
        check(a.automation.get("volume").size() == 2 && a.automation.get("volume").at(R / 2) == s.tracks.get(0).automation.get("volume").at(R / 2) && a.automation.containsKey("send:" + l.buses.get(0).id), "automation restored");
        check(a.takes.size() == 1 && a.takes.get(0).start == 500 && a.takes.get(0).source.frames() == R * 2, "takes restored with their audio");
        check(l.markers.size() == 1 && l.markers.get(0).name.equals("Verse") && l.tempoChanges.get(0).bar == 5 && l.tempoChanges.get(0).bpm == 120, "markers and tempo map");
        check(l.loopStart == 1000 && l.loopEnd == R && l.notes.equals(s.notes) && l.reference != null && l.reference.source.frames() == R && Math.abs(l.referenceGain - .7) < 1e-12 && !l.referenceOn, "loop range, notes and reference restored");
        check(ProjectIO.files(s).contains(s.reference.file), "reference audio included in backups");
        check(l.masterInserts.size() == 2 && l.masterInserts.get(0).kind.equals(Pro.MULTIBAND) && l.masterAutomation.value(0) == .9, "master chain and automation");
        check(Arrays.equals(render(s, R), render(l, R)), "reloaded project renders identically");
        ProjectIO.save(l); Core.Session again = ProjectIO.load(dir, OPEN); check(Arrays.equals(render(l, R), render(again, R)), "second save/load is stable");
    }

    static void legacy(File parent) throws Exception {
        File dir = new File(parent, "legacy"); dir.mkdirs(); String file = pcm(dir, tone(440, .3, 1000));
        String json = "{\"schema\":1,\"title\":\"Old\",\"bpm\":100,\"master\":0.85,\"snapDivision\":4,\"tracks\":[{\"id\":\"t1\",\"name\":\"Vox\",\"color\":-1,\"volume\":0.8,\"pan\":0,\"mute\":false,\"solo\":false,\"bypassFx\":false,\"fx\":{\"eq\":true},\"clips\":[{\"name\":\"c\",\"file\":\"" + file + "\",\"start\":0,\"offset\":0,\"length\":1000,\"gain\":1}]}]}";
        Files.write(new File(dir, "session.json").toPath(), json.getBytes("UTF-8"));
        Core.Session s = ProjectIO.load(dir, OPEN); Core.Track t = s.tracks.get(0);
        check(s.timeSignature == 4 && s.buses.isEmpty() && s.masterInserts.isEmpty() && s.masterAutomation == null && s.markers.isEmpty() && s.tempoChanges.isEmpty(), "old project gets neutral session defaults");
        check(t.output == null && t.sends.isEmpty() && t.inserts.isEmpty() && t.automation.isEmpty() && t.takes.isEmpty() && !t.autoCrossfade && t.sidechain == null, "old project gets neutral track defaults");
        ProjectIO.save(s); String written = new String(Files.readAllBytes(new File(dir, "session.json").toPath()), "UTF-8");
        check(!written.contains("buses") && !written.contains("masterInserts") && !written.contains("timeSignature") && !written.contains("inserts"), "neutral projects do not write new keys");
    }

    static void validation(File parent) throws Exception {
        File dir = new File(parent, "bad"); dir.mkdirs(); String file = pcm(dir, tone(440, .3, 1000));
        String json = "{\"schema\":1,\"title\":\"Bad\",\"bpm\":100,\"timeSignature\":99,\"crossfadeFrames\":-5,\"masterAutomation\":{\"param\":\"pan\",\"frames\":[0],\"values\":[1]},"
            + "\"markers\":[{\"frame\":-1,\"name\":\"x\"},{\"frame\":10,\"name\":\"ok\"}],\"tempoChanges\":[{\"bar\":0,\"bpm\":120},{\"bar\":3,\"bpm\":999},{\"bar\":2,\"bpm\":90}],"
            + "\"buses\":[{\"id\":\"b1\",\"name\":\"Bus\",\"volume\":9}],"
            + "\"tracks\":[{\"id\":\"t1\",\"name\":\"Vox\",\"color\":-1,\"output\":\"nope\",\"sidechain\":\"ghost\",\"sends\":[{\"bus\":\"b1\",\"level\":7},{\"bus\":\"zzz\",\"level\":1}],"
            + "\"inserts\":[{\"kind\":\"virus\"},{\"kind\":\"eq8\",\"values\":[1,99]}],\"automation\":[{\"param\":\"rm -rf\",\"frames\":[0],\"values\":[1]},{\"param\":\"pan\",\"frames\":[5,-3,8],\"values\":[3,0,\"x\"]}],"
            + "\"clips\":[{\"name\":\"c\",\"file\":\"" + file + "\",\"start\":0,\"offset\":0,\"length\":1000,\"gain\":1}]}]}";
        Files.write(new File(dir, "session.json").toPath(), json.getBytes("UTF-8"));
        Core.Session s = ProjectIO.load(dir, OPEN); Core.Track t = s.tracks.get(0);
        check(s.timeSignature == 4 && s.crossfadeFrames == 48 && s.masterAutomation == null, "invalid session values rejected or clamped");
        check(s.markers.size() == 1 && s.tempoChanges.size() == 1 && s.tempoChanges.get(0).bar == 2, "invalid markers and tempo changes dropped");
        check(s.buses.get(0).volume == 2, "bus volume clamped");
        check(t.output == null && t.sidechain == null && t.sends.size() == 1 && t.sends.get(0).level == 2, "dangling routing removed, send level clamped");
        check(t.inserts.size() == 1 && t.inserts.get(0).values[1] == 5, "unknown insert dropped and parameters clamped");
        check(t.automation.size() == 1 && t.automation.get("pan").size() == 1 && t.automation.get("pan").value(0) == 1, "unsafe lanes and points dropped, values clamped");
        File traversal = new File(parent, "trav"); traversal.mkdirs(); Files.write(new File(traversal, "session.json").toPath(), ("{\"schema\":1,\"tracks\":[{\"id\":\"t\",\"name\":\"n\",\"color\":1,\"clips\":[],\"takes\":[{\"name\":\"c\",\"file\":\"../../etc/passwd\",\"start\":0,\"offset\":0,\"length\":1}]}]}").getBytes("UTF-8"));
        try { ProjectIO.load(traversal, OPEN); check(false, "take path traversal rejected"); } catch (IOException expected) { passed++; }
    }

    static void templates(Core.Session s) throws Exception {
        String json = ProjectIO.template(s); check(!json.contains(".pcm"), "templates contain no audio references");
        Core.Session t = ProjectIO.fromTemplate(json, "/tmp/new");
        check(t.tracks.size() == 3 && t.tracks.get(0).clips.isEmpty() && t.tracks.get(0).takes.isEmpty() && t.tracks.get(0).automation.isEmpty(), "template keeps tracks, drops audio and automation");
        check(!t.tracks.get(0).id.equals(s.tracks.get(0).id) && !t.buses.get(0).id.equals(s.buses.get(0).id), "template ids regenerated");
        check(t.tracks.get(0).sends.get(0).bus.equals(t.buses.get(0).id) && t.tracks.get(1).output.equals(t.buses.get(0).id) && t.tracks.get(1).sidechain.equals(t.tracks.get(0).id), "template routing remapped to new ids");
        check(t.timeSignature == 3 && t.tempoChanges.size() == 1 && t.masterInserts.size() == 2 && t.bpm == 96 && t.directory.equals("/tmp/new"), "template keeps tempo, metre and master chain");
        try { ProjectIO.fromTemplate("{\"kind\":\"other\"}", "/tmp"); check(false, "non-template rejected"); } catch (IOException expected) { passed++; }
    }

    static void presets(Core.Session s) throws Exception {
        Core.Track src = s.tracks.get(0); src.fx.comp = true; src.fx.threshold = -22; String p = ProjectIO.trackPreset(src);
        Core.Track dst = new Core.Track("x", 0); ProjectIO.applyTrackPreset(dst, p);
        check(dst.fx.comp && dst.fx.threshold == -22 && dst.inserts.size() == 2 && !dst.inserts.get(0).id.equals(src.inserts.get(0).id) && dst.inserts.get(0).values[18] == 4.5 && !dst.inserts.get(1).enabled, "channel preset copies FX and inserts with new ids");
    }

    static void retime(Core.Session s) {
        Core.Session c = s.copy(); ProjectIO.retimeExtras(c, 2);
        check(c.markers.get(0).frame == R && c.tracks.get(0).automation.get("volume").frame(1) == R / 2 && c.tempoChanges.get(0).bpm == 240, "tempo conversion scales markers, automation and tempo changes");
        check(s.markers.get(0).frame == R * 2, "original untouched");
    }

    static void files(Core.Session s) {
        LinkedHashSet<String> f = ProjectIO.files(s); check(f.size() == 4, "backup file list de-duplicates takes sharing a source (plus the reference): " + f.size());
        Core.Clip extra = new Core.Clip("x", new Core.Memory(new float[4]), "only-take.pcm", 0); s.tracks.get(2).takes.add(extra); check(ProjectIO.files(s).contains("only-take.pcm"), "take-only audio is included in backups"); s.tracks.get(2).takes.remove(extra);
    }

    /** The original exporter loop, kept verbatim for comparison. */
    static byte[] legacyWav(Core.Session s, int bits) throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream(); int sampleBytes = bits / 8, frameBytes = sampleBytes * 2; long frames = Math.min(Core.MAX_FRAMES, s.end() + Export.tailFrames(s));
        BufferedOutputStream out = new BufferedOutputStream(target); out.write(new byte[44]);
        Core.Renderer renderer = new Core.Renderer(s); float[] block = new float[1024]; byte[] pcm = new byte[512 * frameBytes]; Random dither = new Random(902); double scale = Math.pow(2, bits - 1);
        for (long pos = 0; pos < frames; pos += 512) { int n = (int) Math.min(512, frames - pos); renderer.render(pos, n, block, false); for (int i = 0; i < n * 2; i++) { double noise = (dither.nextDouble() - dither.nextDouble()) / scale; int value = (int) Math.round(Math.max(-1, Math.min(1 - 1 / scale, block[i] + noise)) * scale); for (int k = 0; k < sampleBytes; k++) pcm[i * sampleBytes + k] = (byte) (value >> (8 * k)); } out.write(pcm, 0, n * frameBytes); }
        out.flush(); return Arrays.copyOfRange(target.toByteArray(), 44, target.size());
    }

    static void exports(Core.Session pro, File dir) throws Exception {
        Core.Session plain = new Core.Session(); plain.directory = dir.getAbsolutePath(); Core.Track t = new Core.Track("A", 0); t.fx = Core.preset(1); t.clips.add(new Core.Clip("c", new Core.Memory(tone(330, .4, R + 777)), "c", 100)); plain.tracks.add(t);
        Core.Track u = new Core.Track("B", 0); u.fx.delay = true; u.clips.add(new Core.Clip("d", new Core.Memory(tone(550, .2, R)), "d", 4000)); plain.tracks.add(u);
        for (int bits : new int[]{16, 24}) {
            ByteArrayOutputStream w = new ByteArrayOutputStream(); Export.wav(w, plain, bits); byte[] body = Arrays.copyOfRange(w.toByteArray(), 44, w.size());
            check(Arrays.equals(body, legacyWav(plain, bits)), bits + "-bit WAV export is byte-identical to the previous exporter");
        }
        // FLAC master decodes to exactly the 24-bit WAV samples (same dither sequence)
        File wavFile = new File(dir, "m.wav"), flacFile = new File(dir, "m.flac");
        try (OutputStream o = new FileOutputStream(wavFile)) { Export.wav(o, pro, 24); } try (OutputStream o = new FileOutputStream(flacFile)) { Export.flac(o, pro, 24); }
        check(Arrays.equals(decode(wavFile), decode(flacFile)), "FLAC export equals WAV export sample-for-sample");
        // stems
        File zip = new File(dir, "stems.zip"); int count; try (OutputStream o = new FileOutputStream(zip)) { count = Export.stems(o, pro, 16, false); }
        check(count == 3, "one stem per unmuted track");
        int entries = 0; try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new FileInputStream(zip))) { java.util.zip.ZipEntry e; while ((e = in.getNextEntry()) != null) { File f = new File(dir, "stem" + entries + ".wav"); Files.copy(in, f.toPath(), StandardCopyOption.REPLACE_EXISTING); check(e.getName().matches("0\\d Track \\d\\.wav") && decode(f).length > 0, "stem entry " + e.getName()); entries++; } }
        check(entries == 3, "stem ZIP entries");
        Core.Session muted = plain.copy(); for (Core.Track x : muted.tracks) x.mute = true; try { Export.stems(new ByteArrayOutputStream(), muted, 16, false); check(false, "empty stem export rejected"); } catch (IOException expected) { passed++; }
        // loudness normalisation
        double[] report = new double[3]; Core.Session n = Export.normalised(plain, -16, -1, report);
        Pro.Loudness after = Pro.analyse(n, Math.min(Core.MAX_FRAMES, n.end() + Export.tailFrames(n)));
        System.out.println("Normalise: " + report[0] + " LUFS → " + after.integrated() + " LUFS (gain " + report[2] + " dB, TP " + after.truePeakDb + ")");
        check(Math.abs(after.integrated() - (-16)) < 0.5 || after.truePeakDb > -1.6, "normalised to target or ceiling-limited");
        check(plain.master == .85, "normalising leaves the project untouched");
        Core.Session silent = new Core.Session(); silent.tracks.add(new Core.Track("s", 0)); silent.tracks.get(0).clips.add(new Core.Clip("z", new Core.Memory(new float[2000]), "z", 0)); check(Export.normalised(silent, -14, -1, null) == null, "silent mix is not normalised");
    }

    static int[] decode(File f) throws Exception {
        File raw = File.createTempFile("dec", ".raw"); Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-y", "-i", f.toString(), "-f", "s32le", "-acodec", "pcm_s32le", raw.toString()).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes()); if (p.waitFor() != 0 || !log.trim().isEmpty()) throw new IOException("ffmpeg: " + log);
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(raw.toPath())).order(ByteOrder.LITTLE_ENDIAN); int[] out = new int[b.remaining() / 4]; for (int i = 0; i < out.length; i++) out[i] = b.getInt(); return out;
    }
}

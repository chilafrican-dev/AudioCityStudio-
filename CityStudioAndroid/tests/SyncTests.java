package ug.audiocity.studio;

import java.util.*;

/**
 * Bar-locked recording and playback: the master clock, latency-compensated placement, timestamped MIDI with optional
 * quantize, region lengths, tempo-map following and drift-free loop cycles of a four-part 4-bar Afrobeat loop.
 */
public final class SyncTests {
    static int checks;
    static void check(boolean ok, String m) { checks++; if (!ok) throw new AssertionError(m); }
    static long ns(double seconds) { return Math.round(seconds * 1e9); }

    public static void main(String[] a) throws Exception {
        clock(); placement(); midi(); tempoMap(); loops();
        System.out.println("SyncTests OK: " + checks + " checks");
    }

    /** The clock maps any moment to the frame being heard, straight on and inside a loop, without drift. */
    static void clock() {
        TransportClock c = new TransportClock(); long beat = Core.beat(100), bar = beat * 4, countIn = bar;
        c.start(-countIn); // the stream starts with a one-bar count-in before song frame 0
        long t0 = ns(1000); c.update(0, t0, true, false, 0, 0);
        check(c.heardAt(t0, -9) == -countIn, "stream frame 0 is the start of the count-in");
        check(c.heardAt(t0 + ns(countIn / (double) Core.RATE), -9) == 0, "the first beat of the song is heard exactly one bar later");
        // Later readings from the audio thread (the hardware clock keeps counting frames)
        long later = 48000L * 600; c.update(later, t0 + ns(600), true, false, 0, 0);
        check(c.heardAt(t0 + ns(600.5), -9) == -countIn + later + 24000, "half a second after a reading is 24000 frames later");
        // Loop of 4 bars: 200 cycles later the position is still exact (whole frames, no accumulated error)
        TransportClock l = new TransportClock(); l.start(0); long cycle = bar * 4; l.update(cycle * 200 + 1234, ns(50), true, true, 0, cycle);
        check(l.songAt(ns(50), -9) == 1234, "after 200 loop cycles the playhead is exactly 1234 frames into the loop");
        check(l.heardAt(ns(50), -9) == cycle * 200 + 1234, "straight-on position keeps counting");
        check(Math.abs(c.nanosOf(c.heardAt(t0 + ns(3), 0)) - (t0 + ns(3))) < 25_000, "frame and time convert both ways");
    }

    /** A recording lands where the song was being heard when its first microphone sample was captured. */
    static void placement() {
        long[] early = TransportClock.place(96000, 96000 - 7000, 200000);
        check(early[0] == 96000 && early[1] == 7000, "a microphone that started early is trimmed, the region starts at the cursor");
        long[] late = TransportClock.place(96000, 96000 + 2400, 200000);
        check(late[0] == 98400 && late[1] == 0, "a microphone that started late is placed later, by exactly the delay");
        long[] exact = TransportClock.place(0, 0, 1000); check(exact[0] == 0 && exact[1] == 0, "no delay, no change");
    }

    static MidiTake take(Core.Session s, TransportClock c, long startFrame) {
        MidiTake t = new MidiTake(Synth.factory(0), s.bpm, () -> 0L, startFrame); t.recording = true; t.clock = n -> c.heardAt(n, 0); return t;
    }

    /** MIDI and pad notes keep the timing they were played with, unless quantize is switched on. */
    static void midi() {
        Core.Session s = new Core.Session(); s.bpm = 100; long beat = Core.beat(100), bar = beat * 4;
        TransportClock c = new TransportClock(); c.start(-bar); long t0 = ns(10); c.update(0, t0, true, false, 0, 0);
        long songStart = t0 + ns(bar / (double) Core.RATE); // song frame 0 is heard after the one-bar count-in
        MidiTake t = take(s, c, 0);
        double[] beats = {0, 1, 1.5, 2.75, 4, 6.5}; long late = Core.RATE * 7 / 1000; // the player is 7 ms behind on each note
        for (double b : beats) { long at = songStart + ns((b * beat + late) / Core.RATE); t.add(0x90, 60, 100, at); t.add(0x80, 60, 0, at + ns(.1)); }
        t.add(0x90, 62, 90, songStart - ns(beat / 16.0 / Core.RATE)); t.add(0x80, 62, 0, songStart + ns(.05)); // a pick-up 1/64 beat early during the count-in
        t.add(0x90, 64, 90, songStart - ns(beat / (double) Core.RATE)); // a whole beat early: still the count-in, ignored
        t.finish(songStart + ns(7 * beat / (double) Core.RATE)); // Stop pressed during beat 8
        List<Pro.Note> played = ProMidi.fromTake(t); played.sort(Comparator.comparingLong(n -> n.start));
        StringBuilder dbg = new StringBuilder(); for (Pro.Note n : played) dbg.append(n.pitch).append("@").append(n.start).append(" "); check(played.size() == beats.length + 1, "count-in noise ignored, early pick-up kept: " + played.size() + " " + dbg);
        check(played.get(0).pitch == 62 && played.get(0).start == 0, "an early pick-up lands on beat one");
        int k = 0; for (Pro.Note n : played) if (n.pitch == 60) { long want = Math.round(beats[k] * beat) + late; check(Math.abs(n.start - want) <= 1, "note " + k + " at " + n.start + " want " + want); k++; }
        // Quantize off: nothing moves; region ends on the next bar line without moving notes
        MidiTake off = TakeBounds.fit(t, 0, s, 0, true); List<Pro.Note> kept = ProMidi.fromTake(off); kept.sort(Comparator.comparingLong(n -> n.start));
        k = 0; for (Pro.Note n : kept) if (n.pitch == 60) { check(Math.abs(n.start - (Math.round(beats[k] * beat) + late)) <= 1, "quantize off keeps the 7 ms feel"); k++; }
        check(off.frames == bar * 2, "region extended to the next bar line: " + off.frames / (double) bar + " bars");
        // Quantize 1/8: every start lands on an eighth note
        for (int div : new int[]{1, 2, 4, 8}) {
            List<Pro.Note> q = ProMidi.fromTake(TakeBounds.fit(t, 0, s, div, true));
            for (Pro.Note n : q) { double g = n.start / (beat / (double) div); check(Math.abs(g - Math.rint(g)) * beat / div <= 1, "1/" + 4 * div + " quantize: " + n.start); }
        }
        // Without extending, the region ends where recording stopped
        MidiTake exact = TakeBounds.fit(t, 0, s, 0, false); check(exact.frames < bar * 2 && exact.frames >= Math.round(6.5 * beat), "region ends where recording stopped: " + exact.frames / (double) beat + " beats");
    }

    /** Adding a tempo change keeps MIDI on its beats and audio on its bar. */
    static void tempoMap() {
        Core.Session s = new Core.Session(); s.bpm = 100; long beat = Core.beat(100);
        Core.Track keys = new Core.Track("Keys", 0); List<Pro.Note> notes = new ArrayList<>(); for (int b = 0; b < 32; b++) notes.add(new Pro.Note(60, 100, b * beat, beat / 2, 0));
        Core.Clip midi = ProMidi.clip("Keys", notes, Synth.factory(0), 0, (int) (32 * beat)); keys.clips.add(midi); s.tracks.add(keys);
        Core.Track vox = new Core.Track("Vocal", 0); Core.Clip voice = new Core.Clip("Vocal", tone(Core.RATE * 3, 220), "", 20 * beat); vox.clips.add(voice); s.tracks.add(vox);
        Pro.TempoMap before = new Pro.TempoMap(s); s.tempoChanges.add(new Pro.TempoChange(4, 120)); Pro.TempoMap after = new Pro.TempoMap(s);
        ProEdit.followTempoMap(s, before, after);
        for (int b = 0; b < 32; b++) { Pro.Note n = midi.notes.get(b); check(Math.abs(midi.start + n.start - after.frameAt(b)) <= 1, "MIDI note " + b + " stays on beat " + b); check(Math.abs(n.length - (after.frameAt(b + .5) - after.frameAt(b))) <= 1, "note length follows the tempo"); }
        check(Math.abs(voice.start - after.frameAt(20)) <= 1, "audio region stays on its beat"); check(voice.length == Core.RATE * 3, "audio keeps its recorded speed");
        check(midi.length == after.frameAt(32) - after.frameAt(0), "MIDI region keeps its 8 bars");
    }

    static Core.Source tone(int frames, double hz) {
        return new Core.Source() { public int frames() { return frames; } public float sample(int f, int ch) { return (float) (.2 * Math.sin(2 * Math.PI * hz * f / Core.RATE) * Math.min(1, f / 480.0)); } };
    }

    /** A 4-bar Afrobeat: drums, bass, piano and a vocal, played as the engine plays it, cycle after cycle. */
    static void loops() {
        Core.Session s = new Core.Session(); s.bpm = 104; long beat = Core.beat(104); int cycle = (int) new Pro.TempoMap(s).frameAt(16);
        float[] kickWave = new float[Core.RATE / 3 * 2]; for (int i = 0; i < kickWave.length / 2; i++) kickWave[i * 2] = kickWave[i * 2 + 1] = (float) (.6 * Math.exp(-i / 4000.0) * Math.sin(2 * Math.PI * 55 * i / Core.RATE));
        SampledInstruments.Pack kit = new SampledInstruments.Pack("Kick", "Pattern drum kit", new SampledInstruments.Region[]{new SampledInstruments.Region(36, 36, 1, 127, 36, 0, 0, false, 1f, kickWave, 240)});
        List<Pro.Note> drums = new ArrayList<>(); int[] tresillo = {0, 3, 6, 8, 11, 14}; for (int b = 0; b < 4; b++) for (int st : tresillo) drums.add(new Pro.Note(36, 110, Math.round((b * 16 + st) * beat / 4.0), beat / 8, 9));
        List<Pro.Note> bass = new ArrayList<>(), piano = new ArrayList<>(); for (int b = 0; b < 16; b++) { bass.add(new Pro.Note(36 + (b / 4) * 2, 100, b * beat, beat * 3 / 4, 0)); if (b % 2 == 1) for (int p : new int[]{60, 64, 67}) piano.add(new Pro.Note(p, 80, b * beat + beat / 2, beat / 3, 0)); }
        String[] names = {"Drums", "Bass", "Piano"}; List<List<Pro.Note>> parts = Arrays.asList(drums, bass, piano);
        for (int i = 0; i < 3; i++) { Core.Track t = new Core.Track(names[i], 0); t.clips.add(i == 0 ? ProMidi.clip(names[i], parts.get(i), Synth.factory(0), kit, null, 0, cycle) : ProMidi.clip(names[i], parts.get(i), Synth.factory(i == 1 ? 12 : 1), 0, cycle)); s.tracks.add(t); }
        Core.Track vox = new Core.Track("Vocal", 0); vox.clips.add(new Core.Clip("Vocal", tone((int) (beat * 6), 330), "", 8 * beat)); s.tracks.add(vox);
        s.loopStart = 0; s.loopEnd = cycle;
        // Engine-style playback: odd block sizes, the cycle restarts exactly at its end with a fresh renderer.
        int cycles = 6, block = 211; float[][] heard = new float[cycles][cycle * 2]; float[] buf = new float[block * 2];
        Core.Renderer r = new Core.Renderer(s); long cursor = 0; int done = 0, which = 0;
        while (which < cycles) {
            if (cursor >= cycle) { cursor = 0; r = new Core.Renderer(s); which++; done = 0; if (which >= cycles) break; }
            int n = (int) Math.min(block, cycle - cursor); r.render(cursor, n, buf, false); System.arraycopy(buf, 0, heard[which], done * 2, n * 2); done += n; cursor += n;
        }
        for (int c = 1; c < cycles; c++) { double diff = 0, energy = 0; for (int i = 0; i < cycle * 2; i++) { double d = heard[c][i] - heard[0][i]; diff += d * d; energy += heard[0][i] * (double) heard[0][i]; } check(diff <= energy * 1e-9, "cycle " + (c + 1) + " plays exactly like cycle 1 (no drift)"); }
        // Every kick lands on its sixteenth: the first loud sample after each hit time sits within 1 ms of it, in every bar.
        Core.Session drumsOnly = new Core.Session(); drumsOnly.bpm = 104; drumsOnly.tracks.add(s.tracks.get(0));
        float[] d = new float[cycle * 2], b2 = new float[1024]; Core.Renderer dr = new Core.Renderer(drumsOnly); for (int at = 0; at < cycle; at += 512) { int n = Math.min(512, cycle - at); dr.render(at, n, b2, false); System.arraycopy(b2, 0, d, at * 2, n * 2); }
        for (Pro.Note n : drums) { int at = (int) n.start; int onset = -1; for (int i = Math.max(0, at - 480); i < Math.min(cycle, at + 480); i++) if (Math.abs(d[i * 2]) > .02) { onset = i; break; } check(onset >= at && onset - at <= Core.RATE / 1000, "kick at sixteenth " + Math.round(n.start / (beat / 4.0)) + " sounds on time (" + (onset - at) + " frames)"); }
    }
}

package ug.audiocity.studio;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class BeatPilotGrooveTests {
    static int passed;
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    static float[] render(Core.Session session, int frames) {
        Core.Renderer renderer = new Core.Renderer(session);
        float[] output = new float[frames * 2], block = new float[1024];
        for (int position = 0; position < frames; position += 512) {
            int count = Math.min(512, frames - position);
            renderer.render(position, count, block, false);
            System.arraycopy(block, 0, output, position * 2, count * 2);
        }
        return output;
    }

    static double rms(float[] data, int start, int end) {
        double sum = 0;
        for (int i = start; i < end; i++) sum += data[i * 2] * (double)data[i * 2];
        return Math.sqrt(sum / Math.max(1, end - start));
    }

    static void remove(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) remove(child);
        }
        file.delete();
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("beatpilot-groove").toFile();
        try {
            File projectDir = new File(root, "project");
            File sampleDir = new File(root, "library/factory-soundza-afrobeats-drums/samples");
            if (!projectDir.mkdirs() || !sampleDir.mkdirs()) throw new IOException("Could not create BeatPilot test folders.");
            File sourceSamples = new File(args[0], "samples/builtin/soundza");
            String[][] samples = {
                {"060-soundza-heavy-punch-kick.wav", "soundza-heavy-punch-kick.wav"},
                {"005-soundza-crisp-snare-hit.wav", "soundza-crisp-snare-hit.wav"},
                {"015-soundza-tight-closed-hi-hat.wav", "soundza-tight-closed-hi-hat.wav"}
            };
            for (String[] sample : samples)
                Files.copy(new File(sourceSamples, sample[0]).toPath(), new File(sampleDir, sample[1]).toPath());

            Core.Session project = new Core.Session();
            project.directory = projectDir.getAbsolutePath();
            project.bpm = 120;
            project.master = 1;
            long bar = Core.beat(project.bpm) * 4L, start = bar * 2;
            int[] degrees = PerformanceChords.degrees(false, 0);
            List<Core.Track> tracks = BeatPilot.groove(project, 0, 1, 7, false, 0, 4, start, new File(root, "library"));
            project.tracks.addAll(tracks);
            check(tracks.size() == 3 && tracks.get(0).clips.size() == 4, "BeatPilot adds four bars on separate tracks");

            for (int i = 0; i < 4; i++) {
                long at = start + i * bar;
                Core.Clip drums = tracks.get(0).clips.get(i);
                Core.Clip bass = tracks.get(1).clips.get(i);
                Core.Clip chords = tracks.get(2).clips.get(i);
                check(drums.start == at && bass.start == at && chords.start == at, "Drums, bass, and chords share the project bar boundary");
                check(bass.notes != null && chords.notes != null && bass.source instanceof ProMidi.LiveSource && chords.source instanceof ProMidi.LiveSource, "Bass and chord clips remain editable live MIDI");
                check(bass.instrument.patch == 2 && bass.notes.size() == 4 && bass.notes.get(0).pitch == BeatPilot.bassRoot(7, false, degrees[i % degrees.length]), "Smart bass notes use the chord root and bass patch");
                check(chords.instrument.patch == 3 && chords.notes.size() == 12, "Chord track retains its triad notes and assigned patch");
                check(drums.source.sample(300, 0) != 0 && !drums.file.isEmpty(), "Drum track uses the bundled sampled kit");
            }

            int frames = (int)(start + bar);
            float[] mix = render(project, frames);
            check(rms(mix, 0, (int)start) == 0, "BeatPilot tracks stay silent before their requested start");
            check(rms(mix, (int)start, frames) > .005, "BeatPilot tracks render together at their shared start");
            System.out.println(passed + " BeatPilot groove checks passed.");
        } finally {
            remove(root);
        }
    }
}

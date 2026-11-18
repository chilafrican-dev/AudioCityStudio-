package ug.audiocity.studio;

import java.io.*;
import java.util.*;

/** Build independent live drum lanes with project-owned sample data. Run on the project worker. */
final class AiDrumTracks {
    static final int LANES = 8;
    static final String[] NAMES = {"Kick", "Snare", "Clap", "Closed Hat", "Open Hat", "Shaker", "Conga", "Bongo"};
    private static final int[] COLORS = {0xffbd704f,0xff607fb2,0xffbb568c,0xff8073b5,0xffa6519c,0xff879e4c,0xffb48850,0xff59959e};
    private AiDrumTracks() {}

    static int requiredTracks(boolean melody, boolean chords, boolean bass, boolean drums) {
        return (melody ? 1 : 0) + (chords ? 1 : 0) + (bass ? 1 : 0) + (drums ? LANES : 0);
    }

    static List<Core.Track> prepare(File directory, List<Pro.Note> notes, long at, int length,
                                    float[][] samples, String[] titles, String[] sources) throws IOException {
        if (directory == null || !directory.isDirectory() || notes == null || at < 0 || length < 1 || at > Core.MAX_FRAMES - length ||
            samples == null || samples.length != LANES || titles == null || titles.length != LANES || sources == null || sources.length != LANES)
            throw new IllegalArgumentException("Invalid generated drum setup");
        for (int lane = 0; lane < LANES; lane++) {
            if (samples[lane] == null || samples[lane].length < 2 || (samples[lane].length & 1) != 0 || titles[lane] == null || sources[lane] == null)
                throw new IllegalArgumentException("Missing drum sound");
            for (float value : samples[lane]) if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid drum sample data");
        }
        for (Pro.Note note : notes) if (note == null || note.pitch < 36 || note.pitch >= 36 + LANES || note.start < 0 || note.length < 1 || note.start > length || note.length > length - note.start || note.velocity < 1 || note.velocity > 127)
            throw new IllegalArgumentException("Generated drum note is outside its lane or region");
        ArrayList<Core.Track> result = new ArrayList<>();
        for (int lane = 0; lane < LANES; lane++) {
            ArrayList<Pro.Note> laneNotes = new ArrayList<>();
            for (Pro.Note note : notes) if (note.pitch == 36 + lane) laneNotes.add(new Pro.Note(note.pitch, note.velocity, note.start, note.length, 9));
            int pitch = 36 + lane;
            SampledInstruments.Region region = new SampledInstruments.Region(pitch,pitch,1,127,pitch,0,0,false,1f,samples[lane],240);
            SampledInstruments.Pack pack = new SampledInstruments.Pack(titles[lane],sources[lane],new SampledInstruments.Region[]{region});
            String file = InstrumentArchive.write(pack,directory);
            Core.Track track = new Core.Track("AI · " + NAMES[lane],COLORS[lane]); track.volume = .82;
            Core.Clip clip = ProMidi.clip("AI · " + NAMES[lane],laneNotes,Synth.factory(0),pack,"embedded:"+file,at,length);
            clip.instrumentFile = file;
            track.clips.add(clip); result.add(track);
        }
        return result;
    }
    /** Resolve the former factory-only AI kit and persist its recovered data on ordinary open/save. */
    static int embedLegacy(Core.Session project) throws IOException {
        IdentityHashMap<SampledInstruments.Pack,String> files = new IdentityHashMap<>(); int migrated = 0;
        for (Core.Clip clip : InstrumentArchive.clips(project))
            if (clip.notes != null && "embedded:ai-drum-pads".equals(clip.instrumentPackId) && clip.samplePack != null && clip.instrumentFile == null) {
                String file = files.get(clip.samplePack);
                if (file == null) { file = InstrumentArchive.write(clip.samplePack,new File(project.directory)); files.put(clip.samplePack,file); }
                clip.instrumentFile = file; migrated++;
            }
        return migrated;
    }

}

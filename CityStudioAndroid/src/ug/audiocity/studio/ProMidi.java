package ug.audiocity.studio;

import java.util.*;

/**
 * Live MIDI clips: the notes and the instrument stay part of the clip and are rendered to audio on demand, so notes
 * can be edited and the instrument swapped at any time without printing a file. Also time-stretch helpers.
 * Platform independent.
 */
final class ProMidi {
    private ProMidi() {}
    static final int MAX_NOTES = 20000;

    /** Rendered audio of a live MIDI clip, held in memory. */
    static final class Rendered implements Core.Source {
        final float[] data; Rendered(float[] d) { data = d; }
        public int frames() { return data.length / 2; }
        public float sample(int f, int c) { return data[f * 2 + c]; }
    }

    /** Stateful, seekable MIDI source used by Arrange; audio is synthesized only as the transport reads it. */
    static final class LiveSource implements Core.Source {
        private static final int BLOCK = 512;
        private final List<Pro.Note> notes;
        private final Synth.Config instrument;
        private final SampledInstruments.Pack samplePack;
        private final int frames;
        private final float[] cache = new float[BLOCK * 2];
        private SynthPoly synth;
        private int cacheStart, cacheFrames, renderedUntil;

        LiveSource(List<Pro.Note> notes, Synth.Config instrument, SampledInstruments.Pack samplePack, int minFrames) {
            this.notes = new ArrayList<>();
            long end = Math.max(1, minFrames);
            for (Pro.Note note : notes) {
                this.notes.add(note.copy());
                end = Math.max(end, note.end());
            }
            this.instrument = (instrument == null ? Synth.factory(0) : instrument).copy();
            this.samplePack = samplePack;
            end += this.notes.isEmpty() ? 0 : MidiTake.releaseTailFrames(this.instrument, samplePack);
            frames = (int)Math.max(1, Math.min(Core.MAX_FRAMES, end));
        }

        @Override public int frames() { return frames; }

        @Override public synchronized float sample(int frame, int channel) {
            if (frame < 0 || frame >= frames || channel < 0 || channel > 1) return 0;
            if (synth == null || frame < cacheStart || frame > renderedUntil) seek(frame);
            if (frame == renderedUntil) renderNext();
            return cache[(frame - cacheStart) * 2 + channel];
        }

        private void seek(int frame) {
            synth = new SynthPoly(instrument);
            synth.setSamplePack(samplePack);
            ArrayList<MidiTake.Event> events = new ArrayList<>();
            for (Pro.Note note : notes) {
                if (note.end() <= frame) continue;
                int on = (int)Math.max(0, note.start - frame);
                int off = (int)Math.max(0, note.end() - frame);
                events.add(new MidiTake.Event(on, 0x90 | note.channel, note.pitch, note.velocity));
                events.add(new MidiTake.Event(off, 0x80 | note.channel, note.pitch, 0));
            }
            events.sort((a, b) -> a.frame == b.frame ? Integer.compare(Pro.order(a), Pro.order(b)) : Integer.compare(a.frame, b.frame));
            for (MidiTake.Event event : events) synth.scheduleMessage(event.frame, event.status, event.a, event.b);
            cacheStart = renderedUntil = frame;
            cacheFrames = 0;
        }

        private void renderNext() {
            cacheStart = renderedUntil;
            cacheFrames = Math.min(BLOCK, frames - renderedUntil);
            if (cacheFrames <= 0) return;
            synth.render(cache, cacheFrames);
            renderedUntil += cacheFrames;
        }
    }

    /** Renders notes through the instrument. The result covers at least minFrames and every note's release tail. */
    static Rendered render(List<Pro.Note> notes, Synth.Config instrument, int minFrames) {
        return render(notes, instrument, null, minFrames);
    }

    static Rendered render(List<Pro.Note> notes, Synth.Config instrument, SampledInstruments.Pack samplePack, int minFrames) {
        long end = minFrames; for (Pro.Note n : notes) end = Math.max(end, n.end());
        int tail = MidiTake.releaseTailFrames(instrument, samplePack);
        int frames = (int) Math.min(Core.MAX_FRAMES, Math.max(1, end + (notes.isEmpty() ? 0 : tail)));
        float[] out = new float[frames * 2];
        if (!notes.isEmpty()) {
            MidiTake events = Pro.take(instrument, 120, notes, null);
            MidiTake take = new MidiTake(instrument, 120, null, 0, samplePack);
            take.events.addAll(events.events); take.frames = events.frames; take.recording = false;
            int[] k = {0};
            try { take.print((l, r) -> { if (k[0] < out.length) { out[k[0]++] = l; out[k[0]++] = r; } }); }
            catch (java.io.IOException e) { throw new IllegalStateException("Could not render MIDI instrument.", e); }
        }
        return new Rendered(out);
    }

    /** A new live MIDI clip at a timeline frame. lengthFrames is the visible length (at least the notes' extent). */
    static Core.Clip clip(String name, List<Pro.Note> notes, Synth.Config instrument, long at, int lengthFrames) {
        return clip(name, notes, instrument, null, null, at, lengthFrames);
    }

    static Core.Clip clip(String name, List<Pro.Note> notes, Synth.Config instrument, SampledInstruments.Pack samplePack, String samplePackId, long at, int lengthFrames) {
        ArrayList<Pro.Note> own = new ArrayList<>(); for (Pro.Note n : notes) own.add(n.copy());
        LiveSource live = new LiveSource(own, instrument, samplePack, Math.max(lengthFrames, 1));
        Core.Clip c = new Core.Clip(name, new Rendered(new float[0]), "", at); c.source = live; c.notes = own; c.instrument = instrument.copy();
        c.samplePack = samplePack; c.instrumentPackId = samplePackId;
        c.length = (int) Math.min(live.frames(), Math.max(lengthFrames, 1)); c.envelopeLength = live.frames();
        c.peaks = peakProfile(own, live.frames());
        return c;
    }

    /** Rebuilds the live event source after edits, keeping position, trims and fades. */
    static void refresh(Core.Clip c) {
        if (c.notes == null) return;
        LiveSource live = new LiveSource(c.notes, c.instrument, c.samplePack, (int)Math.max(c.offset + (long)c.length, c.envelopeOffset + (long)c.length));
        c.source = live; c.peaks = peakProfile(c.notes, live.frames());
        c.envelopeLength = Math.max(live.frames(), c.envelopeOffset + c.length);
        if (c.offset + (long)c.length > live.frames()) c.length = Math.max(1, live.frames() - c.offset);
    }

    private static float[] peakProfile(List<Pro.Note> notes, int frames) {
        float[] peaks = new float[512];
        if (frames <= 0) return peaks;
        for (Pro.Note note : notes) {
            int first = (int)Math.max(0, Math.min(511, note.start * 512 / frames));
            int last = (int)Math.max(first + 1, Math.min(512, (note.end() * 512 + frames - 1) / frames));
            float level = Math.max(0, Math.min(1, note.velocity / 127f));
            for (int i = first; i < last; i++) peaks[i] = Math.max(peaks[i], level);
        }
        return peaks;
    }

    /** Converts a finished MIDI recording into notes (frames relative to the take start). */
    static List<Pro.Note> fromTake(MidiTake take) { return Pro.notes(take.events, null); }

    /** After a project tempo change: note times scale by 1/speed (speed = new bpm / old bpm). */
    static void retime(Core.Clip c, double speed) {
        for (Pro.Note n : c.notes) { n.start = Math.round(n.start / speed); n.length = Math.max(1, Math.round(n.length / speed)); }
        c.start = Math.round(c.start / speed); c.offset = (int) Math.round(c.offset / speed); c.length = Math.max(1, (int) Math.round(c.length / speed));
        c.fadeIn = (int) Math.round(c.fadeIn / speed); c.fadeOut = (int) Math.round(c.fadeOut / speed); c.envelopeOffset = c.offset; refresh(c);
    }

    // ---------------------------------------------------------------- time-stretch

    /** A window [offset, offset+length) of a source, as its own source. */
    static Core.Source window(Core.Source s, int offset, int length) {
        return new Core.Source() { public int frames() { return length; } public float sample(int f, int c) { int i = offset + f; return i >= 0 && i < s.frames() ? s.sample(i, c) : 0; } };
    }
    /** Stretch ratio (new length / original length) that makes a clip recorded at fromBpm play in time at toBpm. */
    static double tempoRatio(double fromBpm, double toBpm) { return fromBpm / toBpm; }
    /** Ratio that makes a clip exactly fit a number of bars at the project tempo. */
    static double barsRatio(int originalFrames, double bars, int bpm, int beatsPerBar) { return bars * beatsPerBar * Core.RATE * 60.0 / bpm / originalFrames; }
}

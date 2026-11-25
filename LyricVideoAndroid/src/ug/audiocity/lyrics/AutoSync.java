package ug.audiocity.lyrics;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Runs the on-phone Luganda model over a song: decodes it, listens, then times known lyrics or writes new ones. */
final class AutoSync {
    /** The song as 16 kHz mono, prepared for the model. */
    static float[] songAudio(File song, long durationMs, VideoExporter.Listener progress) throws IOException {
        final Audio16k[] conv = {null};
        final long[] frames = {0};
        final int[] rate = {44100};
        PcmDecoder.decode(song, new PcmDecoder.Sink() {
            public void format(int r, int ch) { conv[0] = new Audio16k(r, ch); rate[0] = r; }
            public void pcm(short[] data, int n) {
                conv[0].add(data, n);
                frames[0] += n;
                if (durationMs > 0 && frames[0] % (rate[0] * 4L) < n) progress.progress(Math.min(1f, frames[0] * 1000f / rate[0] / durationMs), "Preparing the song…");
            }
        }, progress::cancelled);
        if (conv[0] == null) throw new IOException("The song decoded to no audio.");
        return conv[0].finish();
    }

    /** Model output for the whole song. */
    static final class Heard {
        float[] lp;
        int frames;
        Ctc.Vocab vocab;
    }

    static Heard listen(File modelDir, File song, long durationMs, VideoExporter.Listener progress) throws IOException {
        float[] audio = songAudio(song, durationMs, scaled(progress, 0, .1f));
        if (progress.cancelled()) throw new CancellationException();
        return listen(modelDir, audio, progress);
    }

    /** Runs the model over audio that is already 16 kHz mono. */
    static Heard listen(File modelDir, float[] audio, VideoExporter.Listener progress) throws IOException {
        Heard h = new Heard();
        try (SpeechModel m = new SpeechModel(modelDir)) {
            int[] T = new int[1];
            h.lp = m.listen(audio, T, scaled(progress, .1f, .97f));
            h.frames = T[0];
            h.vocab = m.vocab;
        }
        return h;
    }

    /** Times every line and word of the lyrics. */
    static AutoTiming.Result timeLyrics(Lyrics lyrics, Heard h) {
        List<String[]> lines = new ArrayList<>();
        for (Lyrics.Line l : lyrics.lines) lines.add(l.words());
        Ctc.Word[][] aligned = Ctc.align(h.lp, h.frames, h.vocab, lines, 2.5f);
        return AutoTiming.apply(lyrics, aligned, SpeechModel.FRAME_MS);
    }

    /** Times a text that has no line breaks yet (e.g. from Sunbird) and splits it into screen lines at the singer's pauses. */
    static Lyrics timeAndSplit(String text, Heard h) {
        List<String[]> one = AutoTiming.asOneLine(text);
        if (one.isEmpty()) return new Lyrics();
        String[] words = one.get(0);
        Ctc.Word[][] aligned = Ctc.align(h.lp, h.frames, h.vocab, one, 2.5f);
        long[] starts = new long[words.length], ends = new long[words.length];
        for (int k = 0; k < words.length; k++) {
            starts[k] = aligned[0][k].start >= 0 ? (long) aligned[0][k].start * SpeechModel.FRAME_MS : -1;
            ends[k] = aligned[0][k].end >= 0 ? (long) (aligned[0][k].end + 1) * SpeechModel.FRAME_MS : -1;
        }
        AutoTiming.fillGaps(starts, ends, words);
        for (int k = 0; k < words.length; k++) if (ends[k] < starts[k]) ends[k] = starts[k] + 200;
        return AutoTiming.blocks(java.util.Arrays.asList(words), starts, ends, Lyrics.LONG_LINE, 700);
    }

    /** Writes lyrics from what the model hears (rough: singing over music is much harder than speech). */
    static Lyrics transcribe(Heard h) {
        ArrayList<Ctc.Heard> words = Ctc.transcribe(h.lp, h.frames, h.vocab);
        ArrayList<String> text = new ArrayList<>();
        ArrayList<Long> s = new ArrayList<>(), e = new ArrayList<>();
        for (Ctc.Heard w : words) {
            if (w.score < .05f && w.text.length() < 2) continue; // stray noise
            text.add(w.text); s.add((long) w.start * SpeechModel.FRAME_MS); e.add((long) (w.end + 1) * SpeechModel.FRAME_MS);
        }
        long[] starts = new long[s.size()], ends = new long[e.size()];
        for (int i = 0; i < starts.length; i++) { starts[i] = s.get(i); ends[i] = e.get(i); }
        return AutoTiming.blocks(text, starts, ends, Lyrics.LONG_LINE, 700);
    }

    static VideoExporter.Listener scaled(VideoExporter.Listener p, float from, float to) {
        return new VideoExporter.Listener() {
            public void progress(float f, String stage) { p.progress(from + (to - from) * f, stage); }
            public boolean cancelled() { return p.cancelled(); }
        };
    }
}

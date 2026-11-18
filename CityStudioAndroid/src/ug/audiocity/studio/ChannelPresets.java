package ug.audiocity.studio;

import java.io.IOException;
import java.util.*;

/**
 * Factory channel-strip presets, measured on the track's own audio when applied, to the gain-staging targets used in
 * professional mixing:
 *  - sustained sources (vocals, bass, keys, guitars, synths): about -18 dBFS average (RMS of the active parts), the usual
 *    0 VU reference, both into the processing and out of the channel;
 *  - percussive sources (kick, snare, hats, percussion): peaks about -8 dBFS (drums are staged by their peaks, because a
 *    short hit has little average level);
 *  - every channel keeps at least 6 dB of headroom: output peaks at or below -6 dBFS, so the mix bus has room before mastering.
 * The compressor threshold is set from the measured level, so each preset gives its intended amount of gain reduction
 * on quiet and loud recordings alike. The result reports what was measured, so the numbers can be checked.
 */
public final class ChannelPresets {
    private ChannelPresets() {}

    public static final double SUSTAINED_TARGET = -18, PERCUSSIVE_PEAK_TARGET = -8, PEAK_CEILING = -6;

    public static final class Preset {
        public final String category, name; final boolean percussive; final double thresholdOffset, minGr, maxGr; final java.util.function.Consumer<Core.Fx> shape;
        Preset(String category, String name, boolean percussive, double thresholdOffset, double minGr, double maxGr, java.util.function.Consumer<Core.Fx> shape) {
            this.category = category; this.name = name; this.percussive = percussive; this.thresholdOffset = thresholdOffset; this.minGr = minGr; this.maxGr = maxGr; this.shape = shape;
        }
        /** The preset's processing before level calibration. */
        public Core.Fx profile() {
            Core.Fx f = new Core.Fx(); f.eq = true; f.hpf = true; f.peakGuard = true; f.low = f.mid = f.high = 0; f.comp = false; f.gate = false; f.deess = false; f.sat = false; f.delay = false; f.room = false;
            shape.accept(f); return f;
        }
        public String describe() { return percussive ? "Peaks staged to " + (int) PERCUSSIVE_PEAK_TARGET + " dBFS" : "Average level staged to " + (int) SUSTAINED_TARGET + " dBFS"; }
    }

    static Preset p(String cat, String name, boolean perc, double off, double minGr, double maxGr, java.util.function.Consumer<Core.Fx> s) { return new Preset(cat, name, perc, off, minGr, maxGr, s); }

    public static final List<Preset> ALL = Collections.unmodifiableList(Arrays.asList(
        // Vocals: low cut below the voice, less boxiness around 400-700 Hz, a little air, 3-6 dB of compression, de-essing, short space.
        p("Vocals", "Lead vocal", false, -5, 2, 6, f -> { f.highPassHz = 90; f.midHz = 500; f.mid = -1.5; f.high = 2; f.comp = true; f.ratio = 3; f.deess = true; f.deessAmount = .6; f.room = true; f.roomMix = .08; f.delay = true; f.delayMix = .05; f.leftBeats = .5; f.rightBeats = .75; f.delayFeedback = .22; }),
        p("Vocals", "Afro lead vocal", false, -5, 2, 6, f -> { f.highPassHz = 90; f.midHz = 650; f.mid = -1.5; f.high = 1.5; f.comp = true; f.ratio = 2.7; f.deess = true; f.deessAmount = .72; f.room = true; f.roomMix = .07; f.delay = true; f.delayMix = .06; f.leftBeats = .5; f.rightBeats = .75; f.delayFeedback = .22; }),
        p("Vocals", "Rap vocal", false, -6, 3, 7, f -> { f.highPassHz = 100; f.midHz = 600; f.mid = -1; f.high = 1.5; f.comp = true; f.ratio = 4; f.deess = true; f.deessAmount = .7; f.room = true; f.roomMix = .03; f.delay = true; f.delayMix = .03; f.leftBeats = .5; f.rightBeats = 1; f.delayFeedback = .18; }),
        p("Vocals", "Backing vocals", false, -5, 2, 6, f -> { f.highPassHz = 150; f.low = -2; f.midHz = 400; f.mid = -2; f.high = 1; f.comp = true; f.ratio = 3; f.deess = true; f.deessAmount = .7; f.room = true; f.roomMix = .18; }),
        // Drums: staged by peaks; kick and 808 keep the low end, boxiness cut, a little attack.
        p("Drums", "Kick", true, -4, 2, 6, f -> { f.highPassHz = 30; f.low = 2; f.midHz = 350; f.mid = -3; f.high = 1.5; f.comp = true; f.ratio = 4; }),
        p("Drums", "808", true, -4, 2, 6, f -> { f.highPassHz = 25; f.low = 1.5; f.midHz = 500; f.mid = -1; f.sat = true; f.drive = 2; f.comp = true; f.ratio = 3; }),
        p("Drums", "Snare / clap", true, -4, 2, 6, f -> { f.highPassHz = 80; f.midHz = 500; f.mid = -2; f.high = 2; f.comp = true; f.ratio = 4; f.room = true; f.roomMix = .1; }),
        p("Drums", "Hi-hats & shakers", true, 0, 0, 3, f -> { f.highPassHz = 300; f.low = -4; f.high = 1; }),
        p("Drums", "Percussion", true, -3, 1, 5, f -> { f.highPassHz = 120; f.midHz = 700; f.mid = -1; f.high = 1; f.comp = true; f.ratio = 2.5; f.room = true; f.roomMix = .08; }),
        p("Drums", "Drum bus glue", true, -1, 0.5, 3, f -> { f.highPassHz = 30; f.comp = true; f.ratio = 2; f.sat = true; f.drive = 1.5; }),
        // Bass: steady level (3-6 dB of compression), definition without mud.
        p("Bass", "Bass guitar", false, -5, 2, 6, f -> { f.highPassHz = 35; f.low = 1; f.midHz = 300; f.mid = -2; f.high = 1; f.comp = true; f.ratio = 4; }),
        p("Bass", "Synth bass", false, -4, 2, 6, f -> { f.highPassHz = 30; f.low = 1; f.midHz = 350; f.mid = -1.5; f.sat = true; f.drive = 1.5; f.comp = true; f.ratio = 3; }),
        p("Bass", "Amapiano log drum", false, -4, 2, 6, f -> { f.highPassHz = 30; f.low = 2; f.midHz = 400; f.mid = -1.5; f.sat = true; f.drive = 2.2; f.comp = true; f.ratio = 3; }),
        // Keys and guitars: gentle control, room around them, low end cleared for the bass.
        p("Keys", "Piano", false, -2.5, 1, 4, f -> { f.highPassHz = 60; f.low = -1; f.midHz = 400; f.mid = -1; f.high = 1; f.comp = true; f.ratio = 2; f.room = true; f.roomMix = .12; }),
        p("Keys", "Electric piano", false, -3, 1, 4, f -> { f.highPassHz = 70; f.midHz = 500; f.mid = -1; f.high = .5; f.comp = true; f.ratio = 2.5; f.room = true; f.roomMix = .1; f.delay = true; f.delayMix = .05; }),
        p("Guitar", "Clean guitar", false, -4, 2, 6, f -> { f.highPassHz = 90; f.low = -1.5; f.midHz = 400; f.mid = -1; f.high = 1.5; f.comp = true; f.ratio = 3; f.room = true; f.roomMix = .1; }),
        p("Guitar", "Highlife / sebene guitar", false, -4, 2, 6, f -> { f.highPassHz = 110; f.low = -2; f.midHz = 500; f.mid = -1; f.high = 2; f.comp = true; f.ratio = 3; f.room = true; f.roomMix = .1; f.delay = true; f.delayMix = .07; }),
        p("Synth", "Pad", false, -1, 0, 3, f -> { f.highPassHz = 120; f.low = -2; f.midHz = 600; f.mid = -1; f.high = .5; f.comp = true; f.ratio = 1.8; f.room = true; f.roomMix = .25; }),
        p("Synth", "Lead synth", false, -4, 2, 6, f -> { f.highPassHz = 120; f.midHz = 800; f.mid = -1; f.high = 1; f.comp = true; f.ratio = 3; f.room = true; f.roomMix = .12; f.delay = true; f.delayMix = .1; })));

    public static List<String> categories() { LinkedHashSet<String> c = new LinkedHashSet<>(); for (Preset p : ALL) c.add(p.category); return new ArrayList<>(c); }
    public static List<Preset> in(String category) { List<Preset> l = new ArrayList<>(); for (Preset p : ALL) if (p.category.equals(category)) l.add(p); return l; }

    public static final class Result {
        public Core.Fx fx; public double inputPeakDb, inputActiveDb, gainDb, reductionDb, outputActiveDb, outputPeakDb; public boolean measured;
        public String report(Preset p) {
            if (!measured) return "There was no audio on this track to measure, so the preset uses its standard levels. Apply it again after recording to stage it to your audio.";
            return String.format(Locale.US, "Measured your audio: average %.1f dBFS, peak %.1f dBFS.%nInput gain set to %+.1f dB.%nCompression: %.1f dB of gain reduction on average.%nChannel output: average %.1f dBFS, peak %.1f dBFS (%s; at least 6 dB of headroom kept).",
                inputActiveDb, inputPeakDb, gainDb, reductionDb, outputActiveDb, outputPeakDb, p.describe().toLowerCase(Locale.US));
        }
    }

    static double db(double v) { return 20 * Math.log10(Math.max(1e-9, v)); }
    static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }

    /** Measurements of a pass of audio: active-level RMS (ignoring silence), sample peak, average gain reduction. */
    static final class Stats { double activeDb, peakDb, grDb; boolean hasQuiet, any; double noiseDb; }

    static final int BLOCK = 960;

    static Stats measure(Core.Source src, int frames, Core.Processor fx) {
        int count = (frames + BLOCK - 1) / BLOCK; double[] rms = new double[count]; double peak = 0; float[] data = new float[BLOCK * 2]; double[] gr = new double[count];
        for (int b = 0; b < count; b++) {
            int n = Math.min(BLOCK, frames - b * BLOCK);
            for (int i = 0; i < n; i++) { data[i * 2] = src.sample(b * BLOCK + i, 0); data[i * 2 + 1] = src.sample(b * BLOCK + i, 1); }
            if (fx != null) { fx.apply(data, n); gr[b] = -20 * Math.log10(Math.max(1e-6, fx.compGain)); }
            double e = 0; for (int i = 0; i < n; i++) { double l = data[i * 2], r = data[i * 2 + 1]; e += (l * l + r * r) * .5; peak = Math.max(peak, Math.max(Math.abs(l), Math.abs(r))); }
            rms[b] = Math.sqrt(e / Math.max(1, n));
        }
        Stats s = new Stats(); s.peakDb = db(peak); double[] sorted = rms.clone(); Arrays.sort(sorted);
        double noise = sorted[(int) ((count - 1) * .1)], loud = sorted[(int) ((count - 1) * .95)]; s.any = loud > 1e-5; s.hasQuiet = noise < loud * .25; s.noiseDb = db(noise);
        double floor = loud * .1, e = 0, g = 0; long n = 0; int active = 0;
        for (int b = 0; b < count; b++) if (rms[b] > floor) { int len = Math.min(BLOCK, frames - b * BLOCK); e += rms[b] * rms[b] * len; n += len; g += gr[b]; active++; }
        s.activeDb = db(Math.sqrt(e / Math.max(1, n))); s.grDb = active == 0 ? 0 : g / active; return s;
    }

    /** Applies a preset measured on the given audio (up to its first 30 seconds of sound). */
    public static Result calibrate(Preset preset, Core.Source source, int bpm) {
        Result r = new Result(); Core.Fx f = preset.profile(); r.fx = f;
        int frames = source == null ? 0 : Math.min(source.frames(), Core.RATE * 30);
        if (frames < Core.RATE / 4) return r;
        Stats dry = measure(source, frames, null);
        if (!dry.any || dry.peakDb < -60) return r;
        r.measured = true; r.inputActiveDb = dry.activeDb; r.inputPeakDb = dry.peakDb;
        // Input stage: bring the source to the reference level before EQ and dynamics.
        double in = preset.percussive ? PERCUSSIVE_PEAK_TARGET - dry.peakDb : SUSTAINED_TARGET - dry.activeDb;
        in = Math.min(in, PEAK_CEILING + 3 - dry.peakDb); // never drive the input stage into clipping
        f.inputDb = clamp(in, -30, 30); r.gainDb = f.inputDb;
        f.gate = f.gate || (!preset.percussive && dry.hasQuiet && f.comp);
        f.gateDb = clamp(dry.noiseDb + f.inputDb + 10, -65, -40); f.gateFloor = .4;
        // Compressor threshold relative to the staged level, so the gain reduction matches the preset's intent.
        double staged = preset.percussive ? dry.activeDb + f.inputDb : SUSTAINED_TARGET;
        f.threshold = clamp(staged + preset.thresholdOffset, -40, -6);
        // Output stage: measure through the channel, then trim the output to the target and keep 6 dB of headroom.
        f.outputDb = 0; Core.Processor fx = new Core.Processor(); fx.configure(f, bpm); Stats wet = measure(source, frames, fx);
        double out = preset.percussive ? PERCUSSIVE_PEAK_TARGET - wet.peakDb : SUSTAINED_TARGET - wet.activeDb;
        out = Math.min(out, PEAK_CEILING - .3 - wet.peakDb); f.outputDb = clamp(out, -24, 24);
        fx = new Core.Processor(); fx.configure(f, bpm); Stats fin = measure(source, frames, fx);
        if (fin.peakDb > PEAK_CEILING) { f.outputDb -= fin.peakDb - PEAK_CEILING + .2; fx = new Core.Processor(); fx.configure(f, bpm); fin = measure(source, frames, fx); }
        r.reductionDb = fin.grDb; r.outputActiveDb = fin.activeDb; r.outputPeakDb = fin.peakDb;
        return r;
    }

    /** The track's own audio as one source: every clip at its place, from the first sound, with clip gain. */
    public static Core.Source trackAudio(Core.Track t) {
        long first = Long.MAX_VALUE, last = 0; for (Core.Clip c : t.clips) { first = Math.min(first, c.start); last = Math.max(last, c.start + c.length); }
        if (first == Long.MAX_VALUE) return null;
        final long from = first; final int frames = (int) Math.min(Core.RATE * 30L, last - first); final List<Core.Clip> clips = new ArrayList<>(t.clips);
        return new Core.Source() {
            public int frames() { return frames; }
            public float sample(int frame, int ch) {
                long at = from + frame; float v = 0;
                for (Core.Clip c : clips) { if (at < c.start || at >= c.start + c.length || c.source == null) continue; int i = (int) (c.offset + (at - c.start)); if (i >= 0 && i < c.source.frames()) v += c.source.sample(i, ch) * c.gain; }
                return v;
            }
        };
    }
}

package ug.audiocity.studio;

import java.util.*;
import ug.audiocity.studio.arrange.model.*;
import ug.audiocity.studio.arrange.model.ArrangeProject.*;
import ug.audiocity.studio.arrange.model.MusicalTime.*;

/** Deterministic stage-one contracts, independent of Android, storage and audio devices. */
public final class ArrangeModelTests {
    static int passed;
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); passed++; }
    static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { passed++; return; }
        throw new AssertionError("Accepted " + message);
    }
    static <T> List<T> of(T... items) { return Arrays.asList(items); }
    static ArrangeProject.Builder project() {
        ArrangeProject.Builder b = new ArrangeProject.Builder("project");
        b.sources.add(Source.midi("source", 1000000, of(new MidiEvent(MidiKind.NOTE, 240, 480, 0, 60, 100))));
        TrackBuilder t = new TrackBuilder("track", TrackType.SOFTWARE_INSTRUMENT);
        t.regions.add(new RegionBuilder("region", "track", "source", 0, 3840).build()); b.tracks.add(t.build()); return b;
    }
    static void time() {
        TempoMap constant = new TempoMap(of(Tempo.bpm(0, 120)));
        check(MusicalTime.PPQN == 960, "PPQN");
        check(constant.sampleAtTick(0, 48000) == 0, "origin");
        check(constant.sampleAtTick(960, 48000) == 24000, "quarter at 120");
        check(constant.sampleAtTick(3840, 44100) == 88200, "44.1k bar");
        TempoMap map = new TempoMap(of(Tempo.bpm(7680, 240), Tempo.bpm(0, 120), Tempo.bpm(3840, 60)));
        check(map.sampleAtTick(3840, 48000) == 96000, "tempo boundary");
        check(map.sampleAtTick(7680, 48000) == 288000, "second tempo segment");
        check(map.sampleAtTick(8640, 48000) == 300000, "third tempo segment");
        check(map.samplesIn(2880, 1920, 48000) == 72000, "region crossing tempo change");
        check(map.tickAtSample(96000, 48000) == 3840, "inverse boundary");
        Random random = new Random(17421);
        for (int rate : new int[]{8000, 44100, 48000, 96000, 192000, 384000}) {
            for (int i = 0; i < 2000; i++) {
                long tick = random.nextInt(12000000), frame = map.sampleAtTick(tick, rate);
                check(map.tickAtSample(frame, rate) == tick, "tick/sample round trip at " + rate);
                long arbitraryFrame = random.nextInt(10000000), nearest = map.tickAtSample(arbitraryFrame, rate);
                long error = Math.abs(map.sampleAtTick(nearest, rate) - arbitraryFrame);
                check(error <= rate / 120 + 1, "inverse error within half slowest tick");
            }
        }
        // 10000 deliberately unaligned changes: rounding must occur once, not once per segment.
        List<Tempo> many = new ArrayList<>(); long numerator = 0;
        for (int i = 0; i < 10000; i++) { long micros = 600001 + i % 3; many.add(new Tempo(i * 7L, micros)); numerator += 7 * micros; }
        TempoMap dense = new TempoMap(many);
        java.math.BigInteger n = java.math.BigInteger.valueOf(numerator).multiply(java.math.BigInteger.valueOf(44100));
        java.math.BigInteger d = java.math.BigInteger.valueOf(960000000L);
        check(dense.sampleAtTick(70000, 44100) == n.add(d.shiftRight(1)).divide(d).longValueExact(), "no accumulated sample rounding");
        long twoHoursTick = 120L * 120 * 960;
        check(constant.sampleAtTick(twoHoursTick, 48000) == 345600000L, "two hour audio");
        long huge = MusicalTime.MAX_TICK - 100;
        check(constant.tickAtSample(constant.sampleAtTick(huge, 384000), 384000) == huge, "large timeline integers remain exact");
        rejects(() -> new TempoMap(Collections.emptyList()), "empty tempo map");
        rejects(() -> new TempoMap(of(Tempo.bpm(1, 120))), "missing tick zero tempo");
        rejects(() -> new TempoMap(of(Tempo.bpm(0, 120), Tempo.bpm(0, 90))), "duplicate tempo");
        rejects(() -> new Tempo(0, 0), "zero tempo");
        rejects(() -> Tempo.bpm(0, 1001), "excessive BPM");
        rejects(() -> constant.sampleAtTick(-1, 48000), "negative tick");
        rejects(() -> constant.sampleAtTick(0, 0), "zero sample rate");
        rejects(() -> constant.tickAtSample(-1, 48000), "negative frame");
        rejects(() -> MusicalTime.end(MusicalTime.MAX_TICK, 1), "tick overflow");
        MeterMap meters = new MeterMap(of(new Meter(0, 4, 4), new Meter(7680, 7, 8), new Meter(14400, 3, 4)));
        check(meters.position(7679).bar == 1 && meters.position(7679).beat == 3 && meters.position(7679).subTick == 959, "4/4 final tick");
        check(meters.position(7680).bar == 2 && meters.position(7680).beat == 0, "7/8 starts new bar");
        check(meters.position(11040).bar == 3 && meters.position(11040).beat == 0, "7/8 bar length");
        check(meters.position(14400).bar == 4, "3/4 boundary");
        for (long bar = 0; bar < 5000; bar++) check(meters.position(meters.barStart(bar)).bar == bar, "bar inverse");
        check(meters.snapBar(10900) == 11040, "snap respects meter changes");
        check(meters.snapBar(1680) == 0 && meters.snapBar(1920) == 3840, "bar snap tie forward");
        rejects(() -> new Meter(0, 4, 3), "non power-of-two denominator");
        rejects(() -> new MeterMap(of(new Meter(0, 4, 4), new Meter(4000, 3, 4))), "mid-bar signature change");
        rejects(() -> new MeterMap(of(new Meter(0, 4, 4), new Meter(0, 3, 4))), "duplicate signature");
    }
    static void regions() {
        RegionMath.Window w = new RegionMath.Window(960, 3840, 480);
        check(w.contains(960) && !w.contains(4800), "half-open window");
        RegionMath.Window[] split = RegionMath.split(w, 1920);
        check(split[0].startTick == 960 && split[0].lengthTick == 960 && split[0].sourceOffsetTick == 480, "split left");
        check(split[1].startTick == 1920 && split[1].lengthTick == 2880 && split[1].sourceOffsetTick == 1440, "split right source offset");
        check(split[0].lengthTick + split[1].lengthTick == w.lengthTick, "split preserves duration");
        RegionMath.Window trim = RegionMath.trim(w, 1440, 4320);
        check(trim.sourceOffsetTick == 960 && trim.lengthTick == 2880, "trim preserves source phase");
        check(RegionMath.trim(w, 4800, 4800).lengthTick == 0, "zero length supported");
        rejects(() -> RegionMath.trim(w, 900, 2000), "trim outside source");
        rejects(() -> RegionMath.split(w, 960), "empty split");
        check(RegionMath.cycleTick(4800, 960, 4800) == 960, "loop exact boundary");
        check(RegionMath.cycleTick(9000, 960, 4800) == 1320, "loop overshoot");
        check(RegionMath.cycleTick(100, 960, 4800) == 100, "count-in before cycle");
        check(RegionMath.loopSourceTick(900, 1000, 960) == 940, "loop source offset");
        rejects(() -> RegionMath.loopSourceTick(0, 0, 0), "empty loop");
        check(MusicalTime.snap(120, 240) == 240 && MusicalTime.snap(119, 240) == 0, "grid tie");
        Random r = new Random(343);
        for (int i = 0; i < 10000; i++) {
            long grid = 1 + r.nextInt(9600), at = r.nextInt(100000000), snapped = MusicalTime.snap(at, grid);
            check(snapped % grid == 0 && Math.abs(snapped - at) <= (grid + 1) / 2, "nearest grid invariant");
            long start = r.nextInt(100000), length = 2 + r.nextInt(10000), cut = start + 1 + r.nextInt((int)length - 1);
            RegionMath.Window source = new RegionMath.Window(start, length, 42), parts[] = RegionMath.split(source, cut);
            check(parts[0].endTick() == parts[1].startTick && parts[1].sourceOffsetTick == 42 + parts[0].lengthTick, "random split continuous");
        }
    }
    static void documents() {
        ArrangeProject.Builder b = project(); ArrangeProject p = b.build();
        check(p.endTick() == 3840 && p.track("track") == p.tracks.get(0), "project lookup/end");
        check(p.track("missing") == null, "missing track lookup");
        b.tracks.clear(); b.sources.clear(); check(p.tracks.size() == 1 && p.sources.size() == 1, "builder cannot mutate published document");
        TrackBuilder changed = p.track("track").edit(); changed.name = "New"; changed.regions.clear();
        check(p.track("track").regions.size() == 1 && p.track("track").name.equals("Track"), "track builder detached");
        Region original = p.track("track").regions.get(0); RegionBuilder rb = original.edit(); rb.gain = 0.5; Region copy = rb.build();
        check(original.gain == 1 && copy.gain == 0.5 && original.id.equals(copy.id), "region edit preserves identity");
        try { p.tracks.clear(); throw new AssertionError("Mutable document"); } catch (UnsupportedOperationException expected) { passed++; }
        try { p.sources.clear(); throw new AssertionError("Mutable media"); } catch (UnsupportedOperationException expected) { passed++; }
        RegionBuilder adjacent = new RegionBuilder("adjacent", "track", "source", 3840, 100);
        check(!original.overlaps(adjacent.build()), "adjacent regions do not overlap");
        adjacent.startTick = 3839; check(original.overlaps(adjacent.build()), "one tick overlap");
        adjacent.lengthTick = 0; check(!original.overlaps(adjacent.build()), "empty region does not overlap");
        check(new ArrangeProject.Builder("empty").build().endTick() == 0, "empty project supported");
        RegionBuilder bad = original.edit(); bad.gain = Double.NaN; rejects(bad::build, "NaN gain");
        bad.gain = 1; bad.fadeIn = new Fade(4000, Curve.LINEAR); rejects(bad::build, "fade beyond region");
        rejects(() -> new MidiEvent(MidiKind.NOTE, 0, 0, 0, 60, 100), "zero note duration");
        rejects(() -> new MidiEvent(MidiKind.CONTROL, 0, 10, 0, 64, 127), "controller duration");
        rejects(() -> new MidiEvent(MidiKind.NOTE, 0, 10, 16, 60, 127), "MIDI channel");
        rejects(() -> Source.midi("m", 100, of(new MidiEvent(MidiKind.NOTE, 90, 20, 0, 60, 100))), "MIDI past source end");
        rejects(() -> Source.audio("a", "media/a.wav", 48000, 0, 100, 4), "audio channel count");
        rejects(() -> new Flex(true, 0, of(new Warp(0, 0), new Warp(100, 90), new Warp(110, 80))), "non-monotonic warp");
        for (String path : new String[]{"../x", "/x", "a/../x", "a//x", "C:/x", "a\\x", "./x", "a/", "a\0b"}) rejects(() -> ArrangeProject.mediaPath(path), "unsafe media path");
        check(ArrangeProject.mediaPath("media/Afro kick.wav").equals("media/Afro kick.wav"), "relative unicode/spaces path");
        ArrangeProject.Builder duplicate = project(); duplicate.tracks.add(duplicate.tracks.get(0)); rejects(duplicate::build, "duplicate track ID");
        ArrangeProject.Builder missing = project(); missing.sources.clear(); rejects(missing::build, "missing source reference");
        ArrangeProject.Builder out = project(); TrackBuilder track = out.tracks.get(0).edit(); RegionBuilder rr = original.edit(); rr.sourceOffsetTick = 999000;
        track.regions = of(rr.build()); out.tracks = of(track.build()); rejects(out::build, "region outside source");
        rr.loop = true; track.regions = of(rr.build()); out.tracks = of(track.build()); check(out.build().endTick() == 3840, "loop can outlast source");
        ArrangeProject.Builder abs = project(); TrackBuilder at = abs.tracks.get(0).edit(); RegionBuilder ar = original.edit(); ar.timeAnchor = TimeAnchor.ABSOLUTE;
        at.regions = of(ar.build()); abs.tracks = of(at.build()); rejects(abs::build, "absolute-time MIDI");
    }
    static void routesAndTakes() {
        ArrangeProject.Builder b = project(); TrackBuilder track = b.tracks.get(0).edit();
        TrackBuilder bus = new TrackBuilder("bus", TrackType.BUS), master = new TrackBuilder("master", TrackType.MASTER);
        bus.output = "master"; track.output = "bus"; track.sends.add(new Send("send", "bus", .5, true, false));
        track.inserts.add(new Insert("plugin", "compressor", false, 64, Collections.singletonMap("threshold", -18.0)));
        b.tracks = of(track.build(), bus.build(), master.build()); check(b.build().tracks.size() == 3, "routing and PDC metadata");
        bus.output = "bus"; b.tracks = of(track.build(), bus.build(), master.build()); rejects(b::build, "self route");
        bus.output = "master"; master.sends.add(new Send("feedback", "bus", 1, false, false));
        b.tracks = of(track.build(), bus.build(), master.build()); rejects(b::build, "send feedback");
        master.sends.clear(); track.output = "missing"; b.tracks = of(track.build(), bus.build(), master.build()); rejects(b::build, "dangling output");
        track.output = "bus"; track.stackParent = "bus"; bus.stackType = StackType.SUMMING;
        b.tracks = of(track.build(), bus.build(), master.build()); check(b.build().track("track").stackParent.equals("bus"), "summing stack");
        bus.stackParent = "track"; track.stackType = StackType.FOLDER;
        b.tracks = of(track.build(), bus.build(), master.build()); rejects(b::build, "stack cycle");
        Region a = new RegionBuilder("take1", "track", "source", 960, 3840).build();
        Region c = new RegionBuilder("take2", "track", "source", 960, 3840).build();
        TakeFolder f = new TakeFolder("folder", "Takes", "track", 960, 3840, of(a, c),
            of(new Comp(0, 1920, "take1", 20), new Comp(1920, 3840, "take2", 20)));
        check(f.comp.size() == 2 && f.takes.size() == 2, "take comp model");
        rejects(() -> new TakeFolder("f", "Takes", "track", 960, 3840, of(a, c), of(new Comp(0, 1920, "unknown", 0))), "missing take");
        rejects(() -> new TakeFolder("f", "Takes", "track", 960, 3840, of(a, c), of(new Comp(0, 2000, "take1", 0), new Comp(1920, 3840, "take2", 0))), "overlapping comp segments");
        Lane lane = new Lane("volume", "volume", AutomationMode.TOUCH, of(new Point(960, 0, Curve.LINEAR), new Point(0, 1, Curve.LINEAR)));
        check(lane.valueAt(480, 99) == .5 && lane.valueAt(2000, 99) == 0, "automation interpolation and chase");
        Lane step = new Lane("sustain", "cc64", AutomationMode.READ, of(new Point(0, 127, Curve.STEP), new Point(960, 0, Curve.STEP)));
        check(step.valueAt(959, -1) == 127 && step.valueAt(960, -1) == 0, "automation boundary");
        rejects(() -> new Lane("bad", "volume", AutomationMode.READ, of(new Point(0, 1, Curve.LINEAR), new Point(0, 0, Curve.LINEAR))), "duplicate automation tick");
    }
    public static void main(String[] args) { time(); regions(); documents(); routesAndTakes(); System.out.println(passed + " Arrange model and integer timing checks passed."); }
}

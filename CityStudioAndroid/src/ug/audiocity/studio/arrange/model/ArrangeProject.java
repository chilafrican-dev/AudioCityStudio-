package ug.audiocity.studio.arrange.model;

import java.util.*;
import static ug.audiocity.studio.arrange.model.MusicalTime.*;

/** Immutable editing document. Contains no Android objects, decoded PCM, open files or DSP state. */
public final class ArrangeProject {
    public static final int SCHEMA_VERSION = 1;
    public enum TrackType { AUDIO, SOFTWARE_INSTRUMENT, DRUMMER, AUX, BUS, MASTER }
    public enum StackType { NONE, FOLDER, SUMMING }
    public enum AutomationMode { READ, TOUCH, LATCH, WRITE }
    public enum Curve { LINEAR, EQUAL_POWER, EXPONENTIAL, STEP }
    public enum MidiKind { NOTE, CONTROL, PROGRAM, PITCH_BEND, CHANNEL_PRESSURE }
    public enum TimeAnchor { MUSICAL, ABSOLUTE }

    static String id(String id) {
        if (id == null || id.isEmpty() || id.length() > 128 || !id.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid identifier");
        return id;
    }
    static String text(String text) {
        if (text == null || text.length() > 4096 || text.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid text");
        return text;
    }
    static double number(double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Value outside range");
        return value;
    }
    static <T> T required(T value) { if (value == null) throw new IllegalArgumentException("Missing value"); return value; }
    static <T> List<T> list(Collection<T> values) {
        if (values == null || values.size() > 1000000) throw new IllegalArgumentException("Invalid list size");
        ArrayList<T> copy = new ArrayList<>(values); for (T value : copy) required(value);
        return Collections.unmodifiableList(copy);
    }
    /** Paths are project relative and platform independent. Importers must also reject symlinks on disk. */
    public static String mediaPath(String path) {
        if (path == null || path.isEmpty() || path.length() > 2048 || path.startsWith("/") || path.contains("\\") || path.contains(":"))
            throw new IllegalArgumentException("Media path must be relative");
        for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Unsafe media path");
        return path;
    }
    public static final class Range {
        public final long startTick, endTick;
        public final boolean enabled;
        public Range(long start, long end, boolean enabled) {
            startTick = tick(start); endTick = tick(end); this.enabled = enabled;
            if (end < start || enabled && end == start) throw new IllegalArgumentException("Invalid range");
        }
        public long lengthTick() { return endTick - startTick; }
        public boolean contains(long at) { return at >= startTick && at < endTick; }
    }
    public static final class Marker {
        public final String id, name;
        public final long tick, lengthTick;
        public final int color;
        public final boolean section;
        public Marker(String id, String name, long at, long length, int color, boolean section) {
            this.id = id(id); this.name = text(name); tick = MusicalTime.tick(at); lengthTick = MusicalTime.tick(length);
            end(at, length); this.color = color; this.section = section;
            if (section && length == 0) throw new IllegalArgumentException("Empty section");
        }
    }
    public static final class MidiEvent {
        public final MidiKind kind;
        public final long tick, lengthTick;
        public final int channel, data1, data2;
        public MidiEvent(MidiKind kind, long at, long length, int channel, int data1, int data2) {
            this.kind = required(kind); tick = MusicalTime.tick(at); lengthTick = MusicalTime.tick(length); end(at, length);
            if (channel < 0 || channel > 15 || data1 < 0 || data1 > (kind == MidiKind.PITCH_BEND ? 16383 : 127) || data2 < 0 || data2 > 127)
                throw new IllegalArgumentException("MIDI data");
            if (kind == MidiKind.NOTE ? length == 0 : length != 0) throw new IllegalArgumentException("MIDI event duration");
            this.channel = channel; this.data1 = data1; this.data2 = data2;
        }
    }
    public static final class Source {
        public final String id, audioPath;
        public final int sampleRate, channels;
        public final long frames, lengthTick;
        public final List<MidiEvent> midi;
        private Source(String id, String path, int rate, int channels, long frames, long length, List<MidiEvent> events) {
            this.id = id(id); audioPath = path; sampleRate = rate; this.channels = channels; this.frames = frames;
            lengthTick = tick(length); midi = list(events);
            for (MidiEvent event : midi) if (end(event.tick, event.lengthTick) > length) throw new IllegalArgumentException("MIDI outside source");
        }
        public static Source audio(String id, String path, int rate, int channels, long frames, long sourceLengthTick) {
            if (rate < 8000 || rate > 384000 || channels < 1 || channels > 64 || frames < 0) throw new IllegalArgumentException("Audio format");
            return new Source(id, mediaPath(path), rate, channels, frames, sourceLengthTick, Collections.emptyList());
        }
        public static Source midi(String id, long length, List<MidiEvent> events) {
            ArrayList<MidiEvent> sorted = new ArrayList<>(list(events)); sorted.sort(Comparator.comparingLong(e -> e.tick));
            return new Source(id, null, 0, 0, 0, length, sorted);
        }
        public boolean isAudio() { return audioPath != null; }
    }
    public static final class Point {
        public final long tick;
        public final double value;
        public final Curve curve;
        public Point(long tick, double value, Curve curve) { this.tick = MusicalTime.tick(tick); this.value = number(value, -1e12, 1e12); this.curve = required(curve); }
    }
    public static final class Lane {
        public final String id, parameter;
        public final AutomationMode mode;
        public final List<Point> points;
        public Lane(String id, String parameter, AutomationMode mode, List<Point> points) {
            this.id = id(id); this.parameter = text(parameter); this.mode = required(mode);
            ArrayList<Point> sorted = new ArrayList<>(list(points)); sorted.sort(Comparator.comparingLong(p -> p.tick));
            long previous = -1; for (Point point : sorted) { if (point.tick <= previous) throw new IllegalArgumentException("Duplicate automation tick"); previous = point.tick; }
            this.points = list(sorted);
        }
        public double valueAt(long at, double fallback) {
            tick(at); if (points.isEmpty()) return fallback;
            int lo = 0, hi = points.size();
            if (at <= points.get(0).tick) return points.get(0).value;
            while (lo + 1 < hi) { int mid = (lo + hi) >>> 1; if (points.get(mid).tick <= at) lo = mid; else hi = mid; }
            Point a = points.get(lo); if (lo + 1 == points.size() || a.curve == Curve.STEP) return a.value;
            Point b = points.get(lo + 1); double t = (at - a.tick) / (double)(b.tick - a.tick);
            if (a.curve == Curve.EXPONENTIAL) t *= t;
            else if (a.curve == Curve.EQUAL_POWER) t = Math.sin(t * Math.PI / 2);
            return a.value + (b.value - a.value) * t;
        }
    }
    public static final class Fade {
        public final long lengthTick;
        public final Curve curve;
        public Fade(long length, Curve curve) { lengthTick = tick(length); this.curve = required(curve); }
    }
    public static final class Warp {
        public final long sourceTick, targetTick;
        public Warp(long source, long target) { sourceTick = tick(source); targetTick = tick(target); }
    }
    public static final class Flex {
        public final boolean followTempo;
        public final int pitchCents;
        public final List<Warp> markers;
        public Flex(boolean followTempo, int pitchCents, List<Warp> markers) {
            if (pitchCents < -4800 || pitchCents > 4800) throw new IllegalArgumentException("Pitch range");
            long source = -1, target = -1;
            for (Warp warp : list(markers)) {
                if (warp.sourceTick <= source || warp.targetTick <= target) throw new IllegalArgumentException("Warp markers must increase");
                source = warp.sourceTick; target = warp.targetTick;
            }
            this.followTempo = followTempo; this.pitchCents = pitchCents; this.markers = list(markers);
        }
    }
    public static final class Region {
        public final String id, trackId, sourceId, name, crossfadeGroup;
        public final long startTick, lengthTick, sourceOffsetTick;
        public final int color;
        public final boolean mute, loop, locked, reversed;
        public final double gain;
        public final Fade fadeIn, fadeOut;
        public final Flex flex;
        public final List<Lane> automation;
        public final TimeAnchor timeAnchor;
        /** Absolute audio anchors are integer nanoseconds; never used for MIDI. */
        public final long absoluteStartNanos, absoluteLengthNanos;
        private Region(RegionBuilder b) {
            id = id(b.id); trackId = id(b.trackId); sourceId = id(b.sourceId); name = text(b.name);
            startTick = tick(b.startTick); lengthTick = tick(b.lengthTick); end(startTick, lengthTick);
            sourceOffsetTick = tick(b.sourceOffsetTick); end(sourceOffsetTick, lengthTick);
            color = b.color; mute = b.mute; loop = b.loop; locked = b.locked; reversed = b.reversed; gain = number(b.gain, 0, 64);
            fadeIn = required(b.fadeIn); fadeOut = required(b.fadeOut); flex = required(b.flex);
            if (fadeIn.lengthTick > lengthTick || fadeOut.lengthTick > lengthTick) throw new IllegalArgumentException("Fade exceeds region");
            crossfadeGroup = b.crossfadeGroup == null ? null : id(b.crossfadeGroup); automation = lanes(b.automation, lengthTick);
            timeAnchor = required(b.timeAnchor); absoluteStartNanos = b.absoluteStartNanos; absoluteLengthNanos = b.absoluteLengthNanos;
            if (absoluteStartNanos < 0 || absoluteLengthNanos < 0) throw new IllegalArgumentException("Negative absolute time");
            Math.addExact(absoluteStartNanos, absoluteLengthNanos);
            for (Warp warp : flex.markers) if (warp.targetTick > lengthTick) throw new IllegalArgumentException("Warp outside region");
        }
        public long endTick() { return end(startTick, lengthTick); }
        public RegionBuilder edit() { return new RegionBuilder(this); }
        public boolean overlaps(Region other) { return lengthTick > 0 && other.lengthTick > 0 && startTick < other.endTick() && other.startTick < endTick(); }
    }
    public static final class RegionBuilder {
        public String id, trackId, sourceId, name = "Region", crossfadeGroup;
        public long startTick, lengthTick, sourceOffsetTick, absoluteStartNanos, absoluteLengthNanos;
        public int color;
        public boolean mute, loop, locked, reversed;
        public double gain = 1;
        public Fade fadeIn = new Fade(0, Curve.LINEAR), fadeOut = new Fade(0, Curve.LINEAR);
        public Flex flex = new Flex(false, 0, Collections.emptyList());
        public List<Lane> automation = new ArrayList<>();
        public TimeAnchor timeAnchor = TimeAnchor.MUSICAL;
        public RegionBuilder(String id, String trackId, String sourceId, long start, long length) {
            this.id = id; this.trackId = trackId; this.sourceId = sourceId; startTick = start; lengthTick = length;
        }
        RegionBuilder(Region r) {
            id = r.id; trackId = r.trackId; sourceId = r.sourceId; name = r.name; crossfadeGroup = r.crossfadeGroup;
            startTick = r.startTick; lengthTick = r.lengthTick; sourceOffsetTick = r.sourceOffsetTick; color = r.color;
            mute = r.mute; loop = r.loop; locked = r.locked; reversed = r.reversed; gain = r.gain;
            fadeIn = r.fadeIn; fadeOut = r.fadeOut; flex = r.flex; automation = new ArrayList<>(r.automation);
            timeAnchor = r.timeAnchor; absoluteStartNanos = r.absoluteStartNanos; absoluteLengthNanos = r.absoluteLengthNanos;
        }
        public Region build() { return new Region(this); }
    }
    public static final class Insert {
        public final String id, plugin;
        public final boolean bypass;
        public final int latencySamples;
        public final Map<String, Double> parameters;
        public Insert(String id, String plugin, boolean bypass, int latencySamples, Map<String, Double> parameters) {
            this.id = id(id); this.plugin = text(plugin); this.bypass = bypass;
            if (latencySamples < 0 || latencySamples > 10000000) throw new IllegalArgumentException("Plugin latency");
            this.latencySamples = latencySamples; Map<String, Double> copy = new TreeMap<>();
            for (Map.Entry<String, Double> e : parameters.entrySet()) copy.put(text(e.getKey()), number(e.getValue(), -1e12, 1e12));
            this.parameters = Collections.unmodifiableMap(copy);
        }
    }
    public static final class Send {
        public final String id, destination;
        public final double gain;
        public final boolean preFader, mute;
        public Send(String id, String destination, double gain, boolean preFader, boolean mute) {
            this.id = id(id); this.destination = id(destination); this.gain = number(gain, 0, 16); this.preFader = preFader; this.mute = mute;
        }
    }
    public static final class Comp {
        public final long startTick, endTick, crossfadeTick;
        public final String takeId;
        public Comp(long start, long end, String takeId, long fade) {
            startTick = tick(start); endTick = tick(end); this.takeId = id(takeId); crossfadeTick = tick(fade);
            if (end <= start || fade > end - start) throw new IllegalArgumentException("Invalid comp segment");
        }
    }
    public static final class TakeFolder {
        public final String id, name, trackId;
        public final long startTick, lengthTick;
        public final List<Region> takes;
        public final List<Comp> comp;
        public TakeFolder(String id, String name, String trackId, long start, long length, List<Region> takes, List<Comp> comp) {
            this.id = id(id); this.name = text(name); this.trackId = id(trackId); startTick = tick(start); lengthTick = tick(length); end(start, length);
            this.takes = list(takes); this.comp = list(comp); Set<String> ids = new HashSet<>();
            for (Region r : takes) {
                if (!r.trackId.equals(trackId) || r.startTick < start || r.endTick() > end(start, length) || !ids.add(r.id))
                    throw new IllegalArgumentException("Invalid take");
            }
            long previous = 0;
            for (Comp c : comp) {
                if (!ids.contains(c.takeId) || c.startTick < previous || c.endTick > length) throw new IllegalArgumentException("Invalid comp selection");
                Region take = null; for (Region r : takes) if (r.id.equals(c.takeId)) take = r;
                if (take.startTick > start + c.startTick || take.endTick() < start + c.endTick) throw new IllegalArgumentException("Comp outside take");
                previous = c.endTick;
            }
        }
    }
    public static final class Track {
        public final String id, name, inputSource, output, stackParent;
        public final TrackType type;
        public final StackType stackType;
        public final int color, height;
        public final boolean mute, solo, recordArm, inputMonitoring, collapsed, frozen;
        public final double volume, pan;
        public final List<Insert> inserts;
        public final List<Send> sends;
        public final List<Lane> automation;
        public final List<Region> regions;
        public final List<TakeFolder> takeFolders;
        private Track(TrackBuilder b) {
            id = id(b.id); name = text(b.name); type = required(b.type); stackType = required(b.stackType); color = b.color; height = b.height;
            if (height < 24 || height > 2048) throw new IllegalArgumentException("Track height");
            mute = b.mute; solo = b.solo; recordArm = b.recordArm; inputMonitoring = b.inputMonitoring; collapsed = b.collapsed; frozen = b.frozen;
            inputSource = text(b.inputSource); output = b.output == null ? null : id(b.output); stackParent = b.stackParent == null ? null : id(b.stackParent);
            volume = number(b.volume, 0, 16); pan = number(b.pan, -1, 1); inserts = list(b.inserts); sends = list(b.sends);
            automation = lanes(b.automation, MAX_TICK); regions = list(b.regions); takeFolders = list(b.takeFolders);
            for (Region r : regions) if (!r.trackId.equals(id)) throw new IllegalArgumentException("Region on wrong track");
            for (TakeFolder f : takeFolders) if (!f.trackId.equals(id)) throw new IllegalArgumentException("Take folder on wrong track");
        }
        public TrackBuilder edit() { return new TrackBuilder(this); }
    }
    public static final class TrackBuilder {
        public String id, name = "Track", inputSource = "", output, stackParent;
        public TrackType type;
        public StackType stackType = StackType.NONE;
        public int color = 0xff7d9ba0, height = 96;
        public boolean mute, solo, recordArm, inputMonitoring, collapsed, frozen;
        public double volume = 1, pan;
        public List<Insert> inserts = new ArrayList<>();
        public List<Send> sends = new ArrayList<>();
        public List<Lane> automation = new ArrayList<>();
        public List<Region> regions = new ArrayList<>();
        public List<TakeFolder> takeFolders = new ArrayList<>();
        public TrackBuilder(String id, TrackType type) { this.id = id; this.type = type; }
        TrackBuilder(Track t) {
            id = t.id; name = t.name; type = t.type; stackType = t.stackType; color = t.color; height = t.height;
            mute = t.mute; solo = t.solo; recordArm = t.recordArm; inputMonitoring = t.inputMonitoring; collapsed = t.collapsed; frozen = t.frozen;
            inputSource = t.inputSource; output = t.output; stackParent = t.stackParent; volume = t.volume; pan = t.pan;
            inserts = new ArrayList<>(t.inserts); sends = new ArrayList<>(t.sends); automation = new ArrayList<>(t.automation);
            regions = new ArrayList<>(t.regions); takeFolders = new ArrayList<>(t.takeFolders);
        }
        public Track build() { return new Track(this); }
    }
    private static List<Lane> lanes(List<Lane> input, long limit) {
        List<Lane> result = list(input); Set<String> ids = new HashSet<>();
        for (Lane lane : result) {
            if (!ids.add(lane.id)) throw new IllegalArgumentException("Duplicate lane ID");
            for (Point p : lane.points) if (p.tick > limit) throw new IllegalArgumentException("Automation outside region");
        }
        return result;
    }
    public final String id, name;
    public final MusicalTime.TempoMap tempoMap;
    public final MusicalTime.MeterMap meterMap;
    public final Range cycle, punch;
    public final List<Marker> markers;
    public final List<Track> tracks;
    public final Map<String, Source> sources;
    private final Map<String, Track> tracksById;
    private ArrangeProject(Builder b) {
        id = id(b.id); name = text(b.name); tempoMap = required(b.tempoMap); meterMap = required(b.meterMap);
        cycle = required(b.cycle); punch = required(b.punch); markers = list(b.markers); tracks = list(b.tracks);
        Map<String, Source> media = new LinkedHashMap<>(); Set<String> ids = new HashSet<>(); ids.add(id);
        for (Source source : b.sources) { if (!ids.add(source.id)) throw new IllegalArgumentException("Duplicate ID"); media.put(source.id, source); }
        sources = Collections.unmodifiableMap(media);
        if (tracks.size() > 1024) throw new IllegalArgumentException("Too many tracks");
        Map<String, Track> index = new LinkedHashMap<>(); int masters = 0;
        for (Track t : tracks) { if (!ids.add(t.id)) throw new IllegalArgumentException("Duplicate ID"); index.put(t.id, t); if (t.type == TrackType.MASTER) masters++; }
        if (masters > 1) throw new IllegalArgumentException("Multiple master tracks");
        tracksById = Collections.unmodifiableMap(index);
        for (Marker m : markers) if (!ids.add(m.id)) throw new IllegalArgumentException("Duplicate ID");
        for (Track t : tracks) {
            if (t.output != null) destination(t.output);
            if (t.type == TrackType.MASTER && t.output != null) throw new IllegalArgumentException("Master has an output route");
            for (Insert insert : t.inserts) if (!ids.add(insert.id)) throw new IllegalArgumentException("Duplicate ID");
            for (Send send : t.sends) { if (!ids.add(send.id)) throw new IllegalArgumentException("Duplicate ID"); destination(send.destination); }
            for (Region r : t.regions) validateRegion(r, ids);
            for (TakeFolder folder : t.takeFolders) { if (!ids.add(folder.id)) throw new IllegalArgumentException("Duplicate ID"); for (Region r : folder.takes) validateRegion(r, ids); }
            HashSet<String> ancestors = new HashSet<>(); ancestors.add(t.id); String parent = t.stackParent;
            while (parent != null) {
                Track p = index.get(parent);
                if (p == null || p.stackType == StackType.NONE || !ancestors.add(parent)) throw new IllegalArgumentException("Invalid track stack");
                parent = p.stackParent;
            }
        }
        validateRouting();
    }
    private void destination(String id) {
        Track target = tracksById.get(id);
        if (target == null || target.type != TrackType.AUX && target.type != TrackType.BUS && target.type != TrackType.MASTER)
            throw new IllegalArgumentException("Output/send must target a bus, aux or master");
    }
    /** Kahn traversal avoids stack overflow on deep or malicious routing graphs. */
    private void validateRouting() {
        Map<String, Integer> incoming = new HashMap<>();
        for (Track track : tracks) incoming.put(track.id, 0);
        for (Track track : tracks) {
            if (track.output != null) incoming.put(track.output, incoming.get(track.output) + 1);
            for (Send send : track.sends) incoming.put(send.destination, incoming.get(send.destination) + 1);
        }
        ArrayDeque<Track> ready = new ArrayDeque<>();
        for (Track track : tracks) if (incoming.get(track.id) == 0) ready.addLast(track);
        int visited = 0;
        while (!ready.isEmpty()) {
            Track track = ready.removeFirst(); visited++;
            if (track.output != null) releaseRoute(track.output, incoming, ready);
            for (Send send : track.sends) releaseRoute(send.destination, incoming, ready);
        }
        if (visited != tracks.size()) throw new IllegalArgumentException("Routing feedback loop");
    }
    private void releaseRoute(String target, Map<String, Integer> incoming, ArrayDeque<Track> ready) {
        int count = incoming.get(target) - 1; incoming.put(target, count);
        if (count == 0) ready.addLast(tracksById.get(target));
    }
    private void validateRegion(Region r, Set<String> ids) {
        if (!ids.add(r.id)) throw new IllegalArgumentException("Duplicate region ID");
        Source source = sources.get(r.sourceId); if (source == null) throw new IllegalArgumentException("Unknown source");
        if (r.sourceOffsetTick > source.lengthTick || !r.loop && end(r.sourceOffsetTick, r.lengthTick) > source.lengthTick)
            throw new IllegalArgumentException("Region outside source");
        if (r.loop && source.lengthTick == 0) throw new IllegalArgumentException("Cannot loop empty source");
        if (!source.isAudio() && r.timeAnchor == TimeAnchor.ABSOLUTE) throw new IllegalArgumentException("MIDI must use musical time");
        for (Warp warp : r.flex.markers) if (warp.sourceTick > source.lengthTick) throw new IllegalArgumentException("Warp outside source");
    }
    public Track track(String id) { return tracksById.get(id); }
    public long endTick() {
        long last = 0; for (Track track : tracks) { for (Region r : track.regions) last = Math.max(last, r.endTick());
            for (TakeFolder f : track.takeFolders) last = Math.max(last, end(f.startTick, f.lengthTick)); }
        return last;
    }
    public Builder edit() { return new Builder(this); }
    public static final class Builder {
        public String id, name = "Untitled session";
        public MusicalTime.TempoMap tempoMap = new MusicalTime.TempoMap(Collections.singletonList(Tempo.bpm(0, 100)));
        public MusicalTime.MeterMap meterMap = new MusicalTime.MeterMap(Collections.singletonList(new Meter(0, 4, 4)));
        public Range cycle = new Range(0, 0, false), punch = new Range(0, 0, false);
        public List<Marker> markers = new ArrayList<>();
        public List<Track> tracks = new ArrayList<>();
        public List<Source> sources = new ArrayList<>();
        public Builder(String id) { this.id = id; }
        Builder(ArrangeProject p) {
            id = p.id; name = p.name; tempoMap = p.tempoMap; meterMap = p.meterMap; cycle = p.cycle; punch = p.punch;
            markers = new ArrayList<>(p.markers); tracks = new ArrayList<>(p.tracks); sources = new ArrayList<>(p.sources.values());
        }
        public ArrangeProject build() { return new ArrangeProject(this); }
    }
}

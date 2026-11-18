package ug.audiocity.studio.arrange.model;

import java.math.BigInteger;
import java.util.*;

/** Integer musical time. This exact reference implementation is for editors/tests, not callbacks. */
public final class MusicalTime {
    public static final int PPQN = 960;
    public static final long MAX_TICK = 1L << 40;
    private static final BigInteger DEN = BigInteger.valueOf(PPQN * 1000000L);
    private MusicalTime() {}
    public static long tick(long value) {
        if (value < 0 || value > MAX_TICK) throw new IllegalArgumentException("Tick outside project range");
        return value;
    }
    public static long end(long start, long length) {
        tick(start); tick(length);
        return tick(Math.addExact(start, length));
    }
    public static final class Tempo {
        public final long tick, microsPerQuarter;
        public Tempo(long tick, long microsPerQuarter) {
            this.tick = MusicalTime.tick(tick);
            if (microsPerQuarter < 60000 || microsPerQuarter > 60000000)
                throw new IllegalArgumentException("Tempo must be 1–1000 BPM");
            this.microsPerQuarter = microsPerQuarter;
        }
        public static Tempo bpm(long tick, int bpm) {
            if (bpm < 1 || bpm > 1000) throw new IllegalArgumentException("BPM");
            return new Tempo(tick, Math.round(60000000.0 / bpm));
        }
    }
    /** Piecewise constant tempo, with no cumulative sample rounding at segment boundaries. */
    public static final class TempoMap {
        public final List<Tempo> events;
        private final BigInteger[] prefix;
        public TempoMap(List<Tempo> input) {
            if (input == null || input.isEmpty() || input.size() > 100000) throw new IllegalArgumentException("Tempo events");
            for (Tempo tempo : input) if (tempo == null) throw new IllegalArgumentException("Missing tempo");
            ArrayList<Tempo> copy = new ArrayList<>(input);
            copy.sort(Comparator.comparingLong(t -> t.tick));
            if (copy.get(0).tick != 0) throw new IllegalArgumentException("First tempo must be at tick zero");
            prefix = new BigInteger[copy.size()]; prefix[0] = BigInteger.ZERO;
            for (int i = 1; i < copy.size(); i++) {
                Tempo previous = copy.get(i - 1), current = copy.get(i);
                if (current.tick == previous.tick) throw new IllegalArgumentException("Duplicate tempo tick");
                prefix[i] = prefix[i - 1].add(BigInteger.valueOf(current.tick - previous.tick)
                    .multiply(BigInteger.valueOf(previous.microsPerQuarter)));
            }
            events = Collections.unmodifiableList(copy);
        }
        private int segment(long at) {
            int lo = 0, hi = events.size();
            while (lo + 1 < hi) { int mid = (lo + hi) >>> 1; if (events.get(mid).tick <= at) lo = mid; else hi = mid; }
            return lo;
        }
        private BigInteger timeNumerator(long at) {
            tick(at); int i = segment(at); Tempo t = events.get(i);
            return prefix[i].add(BigInteger.valueOf(at - t.tick).multiply(BigInteger.valueOf(t.microsPerQuarter)));
        }
        public long sampleAtTick(long at, int rate) {
            rate(rate);
            return round(timeNumerator(at).multiply(BigInteger.valueOf(rate)), DEN);
        }
        public long tickAtSample(long sample, int rate) {
            rate(rate); if (sample < 0) throw new IllegalArgumentException("Negative sample");
            BigInteger target = BigInteger.valueOf(sample).multiply(DEN);
            int lo = 0, hi = events.size();
            while (lo + 1 < hi) { int mid = (lo + hi) >>> 1;
                if (prefix[mid].multiply(BigInteger.valueOf(rate)).compareTo(target) <= 0) lo = mid; else hi = mid;
            }
            BigInteger remainder = target.subtract(prefix[lo].multiply(BigInteger.valueOf(rate)));
            long delta = round(remainder, BigInteger.valueOf(events.get(lo).microsPerQuarter).multiply(BigInteger.valueOf(rate)));
            return end(events.get(lo).tick, delta);
        }
        public long samplesIn(long start, long length, int rate) {
            return sampleAtTick(end(start, length), rate) - sampleAtTick(start, rate);
        }
        private static long round(BigInteger a, BigInteger b) { return a.add(b.shiftRight(1)).divide(b).longValueExact(); }
        private static void rate(int rate) {
            if (rate < 8000 || rate > 384000) throw new IllegalArgumentException("Sample rate");
        }
    }
    public static final class Meter {
        public final long tick;
        public final int numerator, denominator;
        public Meter(long tick, int numerator, int denominator) {
            this.tick = MusicalTime.tick(tick);
            if (numerator < 1 || numerator > 64 || denominator < 1 || denominator > 64 || (denominator & (denominator - 1)) != 0)
                throw new IllegalArgumentException("Time signature");
            this.numerator = numerator; this.denominator = denominator;
        }
        public long beatTicks() { return PPQN * 4L / denominator; }
        public long barTicks() { return numerator * beatTicks(); }
    }
    public static final class BarBeat {
        /** Zero based internally. Display bars/beats with +1. */
        public final long bar, beat, subTick;
        BarBeat(long bar, long beat, long subTick) { this.bar = bar; this.beat = beat; this.subTick = subTick; }
    }
    public static final class MeterMap {
        public final List<Meter> events;
        private final long[] bars;
        public MeterMap(List<Meter> input) {
            if (input == null || input.isEmpty() || input.size() > 100000) throw new IllegalArgumentException("Meter events");
            for (Meter meter : input) if (meter == null) throw new IllegalArgumentException("Missing meter");
            ArrayList<Meter> copy = new ArrayList<>(input); copy.sort(Comparator.comparingLong(t -> t.tick));
            if (copy.get(0).tick != 0) throw new IllegalArgumentException("First meter must be at tick zero");
            bars = new long[copy.size()];
            for (int i = 1; i < copy.size(); i++) {
                long distance = copy.get(i).tick - copy.get(i - 1).tick, size = copy.get(i - 1).barTicks();
                if (distance <= 0 || distance % size != 0) throw new IllegalArgumentException("Meter change must start a bar");
                bars[i] = bars[i - 1] + distance / size;
            }
            events = Collections.unmodifiableList(copy);
        }
        private int segment(long at) {
            int lo = 0, hi = events.size();
            while (lo + 1 < hi) { int mid = (lo + hi) >>> 1; if (events.get(mid).tick <= at) lo = mid; else hi = mid; }
            return lo;
        }
        public BarBeat position(long at) {
            tick(at); int i = segment(at); Meter m = events.get(i); long local = at - m.tick;
            return new BarBeat(bars[i] + local / m.barTicks(), local % m.barTicks() / m.beatTicks(), local % m.beatTicks());
        }
        public long barStart(long bar) {
            if (bar < 0) throw new IllegalArgumentException("Negative bar");
            int lo = 0, hi = bars.length;
            while (lo + 1 < hi) { int mid = (lo + hi) >>> 1; if (bars[mid] <= bar) lo = mid; else hi = mid; }
            return end(events.get(lo).tick, Math.multiplyExact(bar - bars[lo], events.get(lo).barTicks()));
        }
        public long snapBar(long at) {
            BarBeat p = position(at); long left = barStart(p.bar), right = barStart(p.bar + 1);
            return at - left < right - at ? left : right;
        }
    }
    /** Absolute grid; exact halves round toward the later tick. */
    public static long snap(long at, long divisionTicks) {
        tick(at); if (divisionTicks <= 0 || divisionTicks > MAX_TICK) throw new IllegalArgumentException("Grid");
        long base = at / divisionTicks * divisionTicks, remainder = at - base;
        return remainder < divisionTicks - remainder ? base : tick(Math.addExact(base, divisionTicks));
    }
}

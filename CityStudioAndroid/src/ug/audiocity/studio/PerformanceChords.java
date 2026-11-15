package ug.audiocity.studio;
/** Chord progression presets for touch performance, kept independent of Android views. */
public final class PerformanceChords {
 private PerformanceChords(){}
 public static final String[] MAJOR={
  "Pop / Afrobeats · I–V–vi–IV", "Kidandali · I–IV–V–IV", "Afropop · I–V–IV–V",
  "Gospel · I–vi–IV–V", "R&B · ii–V–I–vi", "Ballad · vi–IV–I–V",
  "Reggae · I–IV–I–V", "Highlife · I–IV–ii–V", "Dancehall · vi–IV–I–V",
  "Soul · I–iii–IV–V", "Afropop · 6 chords", "Gospel · 8 chords",
  "R&B · 8 chords", "Worship · 8 chords"};
 public static final String[] MINOR={
  "Afrobeats minor · i–VI–III–VII", "Dancehall minor · i–VII–VI–VII",
  "Kidandali minor · i–iv–VII–III", "Reggae minor · i–VI–VII–i",
  "R&B minor · i–iv–VI–V", "Sad ballad · i–VI–iv–V",
  "Soul minor · i–VII–iv–V", "Gospel minor · i–VI–iv–VII",
  "Minor bounce · i–iv–i–VII", "Amapiano minor · i–III–VII–VI",
  "Afrobeats minor · 6 chords", "Worship minor · 8 chords",
  "R&B minor · 8 chords", "Minor ballad · 8 chords"};
 private static final int[][] M={
  {0,4,5,3},{0,3,4,3},{0,4,3,4},{0,5,3,4},{1,4,0,5},{5,3,0,4},
  {0,3,0,4},{0,3,1,4},{5,3,0,4},{0,2,3,4},{0,4,5,3,1,4},
  {0,3,0,4,5,1,4,0},{1,4,0,5,1,4,0,3},{0,4,5,3,0,4,3,0}};
 private static final int[][] m={
  {0,5,2,6},{0,6,5,6},{0,3,6,2},{0,5,6,0},{0,3,5,4},{0,5,3,4},
  {0,6,3,4},{0,5,3,6},{0,3,0,6},{0,2,6,5},{0,5,2,6,3,6},
  {0,3,5,6,0,3,6,0},{0,6,3,4,0,5,3,4},{0,5,3,4,0,6,5,0}};
 public static int[] degrees(boolean minor,int selected){int[][] presets=minor?m:M;return presets[Math.max(0,Math.min(selected,presets.length-1))].clone();}
 public static String[] titles(boolean minor){return (minor?MINOR:MAJOR).clone();}
 public static int degree(boolean minor,int selected,int index){int[] seq=degrees(minor,selected);return seq[Math.floorMod(index,seq.length)];}
 public static long nextBar(long frames,long barFrames){if(barFrames<=0)throw new IllegalArgumentException("Invalid bar");return frames%barFrames==0?frames:frames+(barFrames-frames%barFrames);}
 public static long recordStart(long frames,long barFrames,int bars){if(bars<0||bars>2)throw new IllegalArgumentException("Count-in must be off, one or two bars");return bars==0?frames:nextBar(frames,barFrames)+bars*barFrames;}
}

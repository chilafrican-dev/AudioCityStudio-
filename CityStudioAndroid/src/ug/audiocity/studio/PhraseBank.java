package ug.audiocity.studio;
/**
 * Beginner melody help. Every phrase uses only pentatonic notes of the chosen key, so any pad
 * sounds right over any chord of the progression. Each note is {pentatonic step, start, length}
 * with start and length counted in sixteenth notes (16 = one 4/4 bar).
 */
public final class PhraseBank {
 private PhraseBank(){}
 /** Pentatonic steps as degrees of the 7-note scale: major = 1 2 3 5 6, minor = 1 b3 4 5 b7. */
 public static final int[] MAJOR_PENT={0,1,2,4,5,7,8,9,11,12,14},MINOR_PENT={0,2,3,4,6,7,9,10,11,13,14};
 public static int[] pent(boolean minor){return minor?MINOR_PENT:MAJOR_PENT;}
 public static final String[] NAMES={"Climb ↗","Fall ↘","Hook","Call","Answer","Bounce","High lift","Resolve"};
 private static final int[][][] PHRASES={
  {{0,0,3},{1,3,3},{2,6,3},{3,9,3},{4,12,4}},
  {{4,0,3},{3,3,3},{2,6,3},{1,9,3},{0,12,4}},
  {{2,0,2},{2,2,2},{3,4,4},{2,8,2},{1,10,2},{0,12,4}},
  {{0,0,4},{2,4,4},{4,8,4},{2,12,4}},
  {{4,0,4},{2,4,2},{3,6,2},{1,8,4},{2,12,2},{0,14,2}},
  {{0,0,2},{3,2,2},{0,4,2},{3,6,2},{1,8,4},{4,12,4}},
  {{4,0,4},{5,4,4},{6,8,4},{5,12,2},{4,14,2}},
  {{3,0,3},{2,4,3},{1,8,3},{0,12,4}}};
 public static int count(){return PHRASES.length;}
 public static int[][] phrase(int index){int[][] src=PHRASES[Math.floorMod(index,PHRASES.length)];int[][] copy=new int[src.length][];for(int i=0;i<src.length;i++)copy[i]=src[i].clone();return copy;}
 /** MIDI note for a phrase step in the given key. */
 public static int note(int root,boolean minor,int step,int octave){int degree=pent(minor)[Math.max(0,Math.min(MAJOR_PENT.length-1,step))];int[] steps=minor?new int[]{0,2,3,5,7,8,10}:new int[]{0,2,4,5,7,9,11};return 12*(octave+1)+root+12*Math.floorDiv(degree,7)+steps[Math.floorMod(degree,7)];}
 /** Pentatonic degree filter: true when the scale degree is one of the five safe notes. */
 public static boolean safe(boolean minor,int degree){int d=Math.floorMod(degree,7);return minor?(d==0||d==2||d==3||d==4||d==6):(d==0||d==1||d==2||d==4||d==5);}
}

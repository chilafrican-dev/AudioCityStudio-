package ug.audiocity.studio;
/** A silent pre-roll: the song stays at its target until every preparation click has finished. */
public final class CountIn {
 private CountIn(){}
 public static long target(long cursor,boolean loop,long loopStart,long loopEnd){return Math.max(0,loop?(loopEnd>loopStart?loopStart:0):cursor);}
 public static long frames(int bpm,int beats){if(beats<0||beats>8)throw new IllegalArgumentException("Use zero to eight count-in beats");return Math.max(1,Core.beat(bpm))*(long)beats;}
 public static int number(long elapsed,int bpm,int beats){return (int)Math.min(beats,Math.max(1,elapsed/Math.max(1,Core.beat(bpm))+1));}
 public static void render(float[] out,int n,long elapsed,int bpm,float volume){
  long beat=Math.max(1,Core.beat(bpm));
  for(int i=0;i<n;i++){long local=(elapsed+i)%beat;float v=local<Core.RATE/35?(float)(Math.sin(local*2*Math.PI*1500/Core.RATE)*Math.exp(-local/(Core.RATE*.005))*.18*Math.max(0,Math.min(2,volume))):0;out[i*2]=out[i*2+1]=v;}
 }
}

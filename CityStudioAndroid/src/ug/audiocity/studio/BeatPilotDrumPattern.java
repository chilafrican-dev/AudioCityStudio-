package ug.audiocity.studio;

import java.util.ArrayList;
import java.util.List;
/** Non-destructive 8-lane drum grid. Four steps per beat, up to eight bars, velocity 0..127. */
public final class BeatPilotDrumPattern {
 public static final int MAX_BARS=8, LANES=8, STEPS=16;
 public final int bpm,bars;
 private final int[][][] velocities;
 public BeatPilotDrumPattern(int bpm,int bars){
  if(bpm<40||bpm>240||bars<1||bars>MAX_BARS)throw new IllegalArgumentException("Invalid tempo or bars");
  this.bpm=bpm;this.bars=bars;velocities=new int[bars][LANES][STEPS];
 }
 public int get(int bar,int lane,int step){check(bar,lane,step);return velocities[bar][lane][step];}
 public void set(int bar,int lane,int step,int velocity){check(bar,lane,step);if(velocity<0||velocity>127)throw new IllegalArgumentException("Velocity must be 0..127");velocities[bar][lane][step]=velocity;}
 private void check(int bar,int lane,int step){if(bar<0||bar>=bars||lane<0||lane>=LANES||step<0||step>=STEPS)throw new IllegalArgumentException("Invalid grid cell");}
 public BeatPilotDrumPattern copy(){BeatPilotDrumPattern p=new BeatPilotDrumPattern(bpm,bars);for(int b=0;b<bars;b++)for(int l=0;l<LANES;l++)System.arraycopy(velocities[b][l],0,p.velocities[b][l],0,STEPS);return p;}
 public BeatPilotDrumPattern resized(int newBars){BeatPilotDrumPattern p=new BeatPilotDrumPattern(bpm,newBars);for(int b=0;b<newBars;b++)for(int l=0;l<LANES;l++)System.arraycopy(velocities[Math.min(b,bars-1)][l],0,p.velocities[b][l],0,STEPS);return p;}
 public static BeatPilotDrumPattern starter(int bpm,int bars){BeatPilotDrumPattern p=new BeatPilotDrumPattern(bpm,bars);boolean[][] src=BeatPilotDrums.starter();for(int b=0;b<bars;b++)for(int l=0;l<LANES;l++)for(int s=0;s<STEPS;s++)if(src[l][s])p.set(b,l,s,l==0?115:l==1?105:86);return p;}
 /** v2 is plain ASCII; legacy 128-digit pattern remains readable. */
 public String encode(){StringBuilder out=new StringBuilder("v2|").append(bpm).append('|').append(bars).append('|');for(int b=0;b<bars;b++)for(int l=0;l<LANES;l++)for(int s=0;s<STEPS;s++){int v=get(b,l,s);out.append(Character.forDigit(v>>>4,16));out.append(Character.forDigit(v&15,16));}return out.toString();}
 public static BeatPilotDrumPattern decode(String value){if(value==null)throw new IllegalArgumentException("Missing drum pattern");
  if(!value.startsWith("v2|")){boolean[][] old=BeatPilotDrums.decode(value);BeatPilotDrumPattern p=new BeatPilotDrumPattern(BeatPilotDrums.tempo(value),4);for(int b=0;b<4;b++)for(int l=0;l<LANES;l++)for(int s=0;s<STEPS;s++)if(old[l][s])p.set(b,l,s,105);return p;}
  String[] parts=value.split("\\|",4);if(parts.length!=4)throw new IllegalArgumentException("Invalid drum pattern");
  int bpm,bars;try{bpm=Integer.parseInt(parts[1]);bars=Integer.parseInt(parts[2]);}catch(NumberFormatException x){throw new IllegalArgumentException("Invalid drum metadata",x);}
  BeatPilotDrumPattern p=new BeatPilotDrumPattern(bpm,bars);if(parts[3].length()!=bars*LANES*STEPS*2)throw new IllegalArgumentException("Invalid drum data length");int i=0;
  for(int b=0;b<bars;b++)for(int l=0;l<LANES;l++)for(int s=0;s<STEPS;s++){int hi=Character.digit(parts[3].charAt(i++),16),lo=Character.digit(parts[3].charAt(i++),16);if(hi<0||lo<0||((hi<<4)|lo)>127)throw new IllegalArgumentException("Invalid velocity");p.set(b,l,s,(hi<<4)|lo);}return p;
 }
 public List<Pro.Note> notes(){List<Pro.Note> result=new ArrayList<>();double framesStep=Core.RATE*60.0/(bpm*4.0);for(int b=0;b<bars;b++)for(int s=0;s<STEPS;s++)for(int l=0;l<LANES;l++){int v=get(b,l,s);if(v>0){long start=Math.round((b*16L+s)*framesStep);result.add(new Pro.Note(36+l,v,start,Math.max(1,Math.round(framesStep*.5)),9));}}return result;}
 public float[] render(float[][] samples){if(samples==null||samples.length!=LANES)throw new IllegalArgumentException("Missing drum samples");int frames=(int)Math.round(Core.RATE*60.0/bpm*4*bars);float[] out=new float[frames*2];float[] levels={.72f,.52f,.5f,.27f,.27f,.31f,.42f,.42f};double unit=Core.RATE*60.0/(bpm*4.0);
  for(int b=0;b<bars;b++)for(int s=0;s<STEPS;s++)for(int l=0;l<LANES;l++){int vel=get(b,l,s);if(vel==0)continue;float[] wave=samples[l];if(wave==null||wave.length%2!=0)throw new IllegalArgumentException("Missing sample "+l);int at=(int)Math.round((b*16L+s)*unit)*2;float gain=levels[l]*(vel/127f);for(int j=0;j<wave.length&&at+j<out.length;j++)out[at+j]+=wave[j]*gain;}
  for(int i=0;i<out.length;i++)out[i]=(float)Math.tanh(out[i]);return out;}
 public void writeMidi(java.io.OutputStream output)throws java.io.IOException{java.util.ArrayList<MidiTake.Event> events=new java.util.ArrayList<>();for(Pro.Note n:notes()){events.add(new MidiTake.Event((int)n.start,0x99,n.pitch,n.velocity));events.add(new MidiTake.Event((int)n.end(),0x89,n.pitch,0));}events.sort((a,b)->a.frame==b.frame?Integer.compare(a.status,b.status):Integer.compare(a.frame,b.frame));MidiTake take=new MidiTake(Synth.factory(0),bpm);take.events.addAll(events);take.writeMidi(output);}
}

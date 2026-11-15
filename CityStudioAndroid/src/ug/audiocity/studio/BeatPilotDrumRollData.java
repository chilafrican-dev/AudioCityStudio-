package ug.audiocity.studio;

import java.io.*;
import java.util.*;
/** Expressive, editable drum MIDI notes. Positions and lengths are stored in sixteenth-note ticks (96 PPQ per quarter). */
public final class BeatPilotDrumRollData {
 public static final int TICKS_PER_BEAT=96, TICKS_PER_BAR=384, MAX_NOTES=8192;
 public final int bpm, bars;
 public final ArrayList<Hit> hits=new ArrayList<>();
 public static final class Hit {
  public int lane,tick,length,velocity;
  public Hit(int lane,int tick,int length,int velocity){this.lane=lane;this.tick=tick;this.length=length;this.velocity=velocity;}
  public Hit copy(){return new Hit(lane,tick,length,velocity);}
 }
 public BeatPilotDrumRollData(int bpm,int bars){if(bpm<40||bpm>240||bars<1||bars>8)throw new IllegalArgumentException("Invalid roll tempo or bars");this.bpm=bpm;this.bars=bars;}
 public void add(int lane,int tick,int length,int velocity){if(hits.size()>=MAX_NOTES||lane<0||lane>=8||tick<0||tick>=bars*TICKS_PER_BAR||length<1||length>bars*TICKS_PER_BAR-tick||velocity<1||velocity>127)throw new IllegalArgumentException("Invalid drum note");hits.add(new Hit(lane,tick,length,velocity));}
 public BeatPilotDrumRollData copy(){BeatPilotDrumRollData d=new BeatPilotDrumRollData(bpm,bars);for(Hit h:hits)d.hits.add(h.copy());return d;}
 public static BeatPilotDrumRollData fromPattern(BeatPilotDrumPattern p){BeatPilotDrumRollData d=new BeatPilotDrumRollData(p.bpm,p.bars);for(int b=0;b<p.bars;b++)for(int l=0;l<8;l++)for(int s=0;s<16;s++){int v=p.get(b,l,s);if(v>0)d.add(l,b*384+s*24,12,v);}return d;}
 public static BeatPilotDrumRollData decode(String text){if(text==null)throw new IllegalArgumentException("Missing MIDI pattern");if(!text.startsWith("v3|"))return fromPattern(BeatPilotDrumPattern.decode(text));String[] x=text.split("\\|",4);if(x.length!=4)throw new IllegalArgumentException("Invalid drum MIDI");final int tempo,bars;try{tempo=Integer.parseInt(x[1]);bars=Integer.parseInt(x[2]);}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid tempo",e);}BeatPilotDrumRollData r=new BeatPilotDrumRollData(tempo,bars);if(x[3].isEmpty())return r;String[] notes=x[3].split(";");if(notes.length>MAX_NOTES)throw new IllegalArgumentException("Too many notes");for(String item:notes){String[] fields=item.split(",",-1);if(fields.length!=4)throw new IllegalArgumentException("Invalid note fields");try{r.add(Integer.parseInt(fields[0]),Integer.parseInt(fields[1]),Integer.parseInt(fields[2]),Integer.parseInt(fields[3]));}catch(NumberFormatException ex){throw new IllegalArgumentException("Invalid note value",ex);}}return r;}
 public String encode(){StringBuilder out=new StringBuilder("v3|").append(bpm).append('|').append(bars).append('|');for(int i=0;i<hits.size();i++){Hit h=hits.get(i);if(i>0)out.append(';');out.append(h.lane).append(',').append(h.tick).append(',').append(h.length).append(',').append(h.velocity);}return out.toString();}
 public List<MidiTake.Event> auditionEvents(int projectBpm){if(projectBpm<40||projectBpm>240)throw new IllegalArgumentException("Invalid project tempo");ArrayList<MidiTake.Event> events=new ArrayList<>();double framesPerTick=Core.beat(projectBpm)/(double)TICKS_PER_BEAT;for(Hit h:hits)events.add(new MidiTake.Event((int)Math.round(h.tick*framesPerTick),0x99,36+h.lane,h.velocity));events.sort((a,b)->Integer.compare(a.frame,b.frame));return events;}
 public List<Pro.Note> notes(){ArrayList<Pro.Note> n=new ArrayList<>();double framesPerTick=Core.RATE*60.0/(bpm*TICKS_PER_BEAT);for(Hit h:hits)n.add(new Pro.Note(36+h.lane,h.velocity,Math.round(h.tick*framesPerTick),Math.max(1,Math.round(h.length*framesPerTick)),9));n.sort((a,b)->Long.compare(a.start,b.start));return n;}
 public float[] render(float[][] waves){if(waves==null||waves.length!=8)throw new IllegalArgumentException("Missing drum sample bank");int length=(int)Math.round(Core.RATE*60.0/bpm*4*bars);float[] result=new float[length*2];float[] levels={.72f,.52f,.5f,.27f,.27f,.31f,.42f,.42f};double framesPerTick=Core.RATE*60.0/(bpm*TICKS_PER_BEAT);for(Hit h:hits){float[] wave=waves[h.lane];if(wave==null||wave.length%2!=0)throw new IllegalArgumentException("Invalid drum sample");int offset=(int)Math.round(h.tick*framesPerTick)*2;float gain=levels[h.lane]*h.velocity/127f;for(int j=0;j<wave.length&&offset+j<result.length;j++)result[offset+j]+=wave[j]*gain;}for(int j=0;j<result.length;j++)result[j]=(float)Math.tanh(result[j]);return result;}
 public void writeMidi(OutputStream out)throws IOException{ArrayList<MidiTake.Event> events=new ArrayList<>();for(Pro.Note n:notes()){events.add(new MidiTake.Event((int)n.start,0x99,n.pitch,n.velocity));events.add(new MidiTake.Event((int)n.end(),0x89,n.pitch,0));}events.sort((a,b)->a.frame==b.frame?Integer.compare(a.status,b.status):Integer.compare(a.frame,b.frame));MidiTake take=new MidiTake(Synth.factory(0),bpm);take.events.addAll(events);take.writeMidi(out);}
}

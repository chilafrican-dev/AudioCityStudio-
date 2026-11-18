package ug.audiocity.studio;

import java.util.ArrayList;
import java.util.List;
/** Non-destructive 8-lane drum grid. Four steps per beat, up to eight bars, velocity 0..127. */
public final class BeatPilotDrumPattern {
 public static final int MAX_BARS=8, LANES=8, STEPS=16;
 public final int bpm,bars;
 private final int[][][] velocities;
 private final int[] sampleChoices=new int[LANES];
 private final String[] userFiles=new String[LANES],userNames=new String[LANES];
 public boolean imported(int lane){return userFiles[lane]!=null;}
 public String userFile(int lane){return userFiles[lane];}
 public String sampleName(int lane){return imported(lane)?userNames[lane]:DrumSampleCatalog.title(lane,sampleChoices[lane]);}
 public void setImported(int lane,String file,String name){if(lane<0||lane>=LANES||!UserDrumPacks.validSampleFile(file))throw new IllegalArgumentException("Invalid imported drum sample");String title=name==null?"Imported sound":name.trim();if(title.isEmpty())title="Imported sound";userFiles[lane]=file;userNames[lane]=title.substring(0,Math.min(80,title.length()));sampleChoices[lane]=DrumSampleCatalog.defaultIndex(lane);}

 public int sampleChoice(int lane){return sampleChoices[lane];}
 public void setSampleChoice(int lane,int index){DrumSampleCatalog.validate(lane,index);sampleChoices[lane]=index;userFiles[lane]=null;userNames[lane]=null;}
 public String sampleFile(int lane){return imported(lane)?userFiles[lane]:DrumSampleCatalog.file(lane,sampleChoices[lane]);}
 public void copySamplesTo(BeatPilotDrumPattern target){System.arraycopy(sampleChoices,0,target.sampleChoices,0,LANES);System.arraycopy(userFiles,0,target.userFiles,0,LANES);System.arraycopy(userNames,0,target.userNames,0,LANES);}
 public BeatPilotDrumPattern(int bpm,int bars){
  if(bpm<40||bpm>240||bars<1||bars>MAX_BARS)throw new IllegalArgumentException("Invalid tempo or bars");
  this.bpm=bpm;this.bars=bars;for(int l=0;l<LANES;l++)sampleChoices[l]=DrumSampleCatalog.defaultIndex(l);velocities=new int[bars][LANES][STEPS];
 }
 public int get(int bar,int lane,int step){check(bar,lane,step);return velocities[bar][lane][step];}
 public void set(int bar,int lane,int step,int velocity){check(bar,lane,step);if(velocity<0||velocity>127)throw new IllegalArgumentException("Velocity must be 0..127");velocities[bar][lane][step]=velocity;}
 private void check(int bar,int lane,int step){if(bar<0||bar>=bars||lane<0||lane>=LANES||step<0||step>=STEPS)throw new IllegalArgumentException("Invalid grid cell");}
 public BeatPilotDrumPattern copy(){BeatPilotDrumPattern p=new BeatPilotDrumPattern(bpm,bars);copySamplesTo(p);for(int b=0;b<bars;b++)for(int l=0;l<LANES;l++)System.arraycopy(velocities[b][l],0,p.velocities[b][l],0,STEPS);return p;}
 public BeatPilotDrumPattern resized(int newBars){BeatPilotDrumPattern p=new BeatPilotDrumPattern(bpm,newBars);copySamplesTo(p);for(int b=0;b<newBars;b++)for(int l=0;l<LANES;l++)System.arraycopy(velocities[Math.min(b,bars-1)][l],0,p.velocities[b][l],0,STEPS);return p;}
 /** Manual editing grows with empty bars; repeating is a separate explicit action. */
 public BeatPilotDrumPattern resizedEmpty(int newBars){BeatPilotDrumPattern p=new BeatPilotDrumPattern(bpm,newBars);copySamplesTo(p);for(int b=0;b<Math.min(bars,newBars);b++)for(int l=0;l<LANES;l++)System.arraycopy(velocities[b][l],0,p.velocities[b][l],0,STEPS);return p;}
 public boolean discardsNotes(int newBars){if(newBars<1||newBars>MAX_BARS)throw new IllegalArgumentException("Invalid pattern length");for(int b=newBars;b<bars;b++)for(int l=0;l<LANES;l++)for(int step=0;step<STEPS;step++)if(get(b,l,step)>0)return true;return false;}
 public BeatPilotDrumPattern repeatedBar(int bar){if(bar<0||bar>=bars)throw new IllegalArgumentException("Invalid bar");BeatPilotDrumPattern p=new BeatPilotDrumPattern(bpm,bars);copySamplesTo(p);for(int b=0;b<bars;b++)for(int l=0;l<LANES;l++)System.arraycopy(velocities[bar][l],0,p.velocities[b][l],0,STEPS);return p;}
 public static BeatPilotDrumPattern starter(int bpm,int bars){BeatPilotDrumPattern p=new BeatPilotDrumPattern(bpm,bars);boolean[][] src=BeatPilotDrums.starter();for(int b=0;b<bars;b++)for(int l=0;l<LANES;l++)for(int s=0;s<STEPS;s++)if(src[l][s])p.set(b,l,s,l==0?115:l==1?105:86);return p;}
 /** v5 stores portable personal sample references; factory-only patterns retain v4. */
 public String encode(){boolean personal=false;for(String file:userFiles)if(file!=null)personal=true;StringBuilder out=new StringBuilder(personal?"v5|":"v4|").append(bpm).append('|').append(bars).append('|');for(int l=0;l<LANES;l++){if(l>0)out.append(',');out.append(sampleChoices[l]);}out.append('|');if(personal){for(int l=0;l<LANES;l++){if(l>0)out.append(',');if(userFiles[l]!=null)out.append(userFiles[l]);}out.append('|');for(int l=0;l<LANES;l++){if(l>0)out.append(',');if(userNames[l]!=null)out.append(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(userNames[l].getBytes(java.nio.charset.StandardCharsets.UTF_8)));}out.append('|');}for(int b=0;b<bars;b++)for(int l=0;l<LANES;l++)for(int step=0;step<STEPS;step++){int v=get(b,l,step);out.append(Character.forDigit(v>>>4,16));out.append(Character.forDigit(v&15,16));}return out.toString();}
 public static BeatPilotDrumPattern decode(String value){if(value==null||value.length()>65536)throw new IllegalArgumentException("Missing or oversized drum pattern");boolean v5=value.startsWith("v5|"),v4=value.startsWith("v4|");if(!v5&&!v4&&!value.startsWith("v2|")){boolean[][] old=BeatPilotDrums.decode(value);BeatPilotDrumPattern p=new BeatPilotDrumPattern(BeatPilotDrums.tempo(value),4);for(int b=0;b<4;b++)for(int l=0;l<LANES;l++)for(int step=0;step<STEPS;step++)if(old[l][step])p.set(b,l,step,105);return p;}
  int count=v5?7:v4?5:4;String[] parts=value.split("\\|",count);if(parts.length!=count)throw new IllegalArgumentException("Invalid drum pattern");int bpm,bars;try{bpm=Integer.parseInt(parts[1]);bars=Integer.parseInt(parts[2]);}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid drum metadata",e);}BeatPilotDrumPattern p=new BeatPilotDrumPattern(bpm,bars);
  if(v4||v5){String[] choices=parts[3].split(",",-1);if(choices.length!=LANES)throw new IllegalArgumentException("Invalid drum kit");try{for(int l=0;l<LANES;l++)p.setSampleChoice(l,Integer.parseInt(choices[l]));}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid sample choice",e);}}
  if(v5){String[] files=parts[4].split(",",-1),names=parts[5].split(",",-1);if(files.length!=LANES||names.length!=LANES)throw new IllegalArgumentException("Invalid imported kit");for(int l=0;l<LANES;l++){if(files[l].isEmpty()){if(!names[l].isEmpty())throw new IllegalArgumentException("Unmatched imported sample label");}else{if(names[l].length()>512)throw new IllegalArgumentException("Imported sample label too long");p.setImported(l,files[l],new String(java.util.Base64.getUrlDecoder().decode(names[l]),java.nio.charset.StandardCharsets.UTF_8));}}}
  String data=parts[count-1];if(data.length()!=bars*LANES*STEPS*2)throw new IllegalArgumentException("Invalid drum data length");int at=0;for(int b=0;b<bars;b++)for(int l=0;l<LANES;l++)for(int step=0;step<STEPS;step++){int hi=Character.digit(data.charAt(at++),16),lo=Character.digit(data.charAt(at++),16);if(hi<0||lo<0||((hi<<4)|lo)>127)throw new IllegalArgumentException("Invalid velocity");p.set(b,l,step,(hi<<4)|lo);}return p;
 }
 public List<Pro.Note> notes(){List<Pro.Note> result=new ArrayList<>();double framesStep=Core.RATE*60.0/(bpm*4.0);for(int b=0;b<bars;b++)for(int s=0;s<STEPS;s++)for(int l=0;l<LANES;l++){int v=get(b,l,s);if(v>0){long start=Math.round((b*16L+s)*framesStep);result.add(new Pro.Note(36+l,v,start,Math.max(1,Math.round(framesStep*.5)),9));}}return result;}
 public float[] render(float[][] samples){if(samples==null||samples.length!=LANES)throw new IllegalArgumentException("Missing drum samples");int frames=(int)Math.round(Core.RATE*60.0/bpm*4*bars);float[] out=new float[frames*2];float[] levels={.72f,.52f,.5f,.27f,.27f,.31f,.42f,.42f};double unit=Core.RATE*60.0/(bpm*4.0);
  for(int b=0;b<bars;b++)for(int s=0;s<STEPS;s++)for(int l=0;l<LANES;l++){int vel=get(b,l,s);if(vel==0)continue;float[] wave=samples[l];if(wave==null||wave.length%2!=0)throw new IllegalArgumentException("Missing sample "+l);int at=(int)Math.round((b*16L+s)*unit)*2;float gain=levels[l]*(vel/127f);for(int j=0;j<wave.length&&at+j<out.length;j++)out[at+j]+=wave[j]*gain;}
  for(int i=0;i<out.length;i++)out[i]=(float)Math.tanh(out[i]);return out;}
 public void writeMidi(java.io.OutputStream output)throws java.io.IOException{java.util.ArrayList<MidiTake.Event> events=new java.util.ArrayList<>();for(Pro.Note n:notes()){events.add(new MidiTake.Event((int)n.start,0x99,n.pitch,n.velocity));events.add(new MidiTake.Event((int)n.end(),0x89,n.pitch,0));}events.sort((a,b)->a.frame==b.frame?Integer.compare(a.status,b.status):Integer.compare(a.frame,b.frame));MidiTake take=new MidiTake(Synth.factory(0),bpm);take.events.addAll(events);take.writeMidi(output);}
}

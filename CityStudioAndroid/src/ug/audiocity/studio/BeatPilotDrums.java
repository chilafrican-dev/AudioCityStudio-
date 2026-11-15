package ug.audiocity.studio;

/** Small deterministic 16-step drum sequencer shared by editor and render path. */
public final class BeatPilotDrums {
 private BeatPilotDrums() {}
 public static final int LANES=8, STEPS=16;
 public static final String[] LABELS={"Kick","Snare","Clap","Closed Hat","Open Hat","Shaker","Conga","Bongo"};
 public static final String[] FILES={
 "060-soundza-heavy-punch-kick.wav", "005-soundza-crisp-snare-hit.wav",
 "010-soundza-tight-hand-clap.wav", "018-soundza-warm-closed-hi-hat.wav",
 "017-soundza-crisp-open-hi-hat.wav", "020-soundza-egg-shaker.wav",
 "033-soundza-conga-hit.wav", "029-soundza-open-bongo-hit.wav"};
 public static boolean[][] starter(){boolean[][] result=new boolean[LANES][STEPS];
  for(int i:new int[]{0,4,8,12})result[0][i]=true;
  for(int i:new int[]{4,12})result[1][i]=true;
  for(int i=0;i<16;i+=2)result[3][i]=true;
  for(int i:new int[]{3,7,11,15})result[5][i]=true;
  return result;
 }
 public static boolean[][] copy(boolean[][] source){validate(source);boolean[][] result=new boolean[LANES][STEPS];for(int i=0;i<LANES;i++)System.arraycopy(source[i],0,result[i],0,STEPS);return result;}
 public static void validate(boolean[][] steps){if(steps==null||steps.length!=LANES)throw new IllegalArgumentException("Expected eight drum lanes");for(boolean[] lane:steps)if(lane==null||lane.length!=STEPS)throw new IllegalArgumentException("Expected sixteen steps");}
 public static float[] render(int bpm,boolean[][] pattern,float[][] samples,int bars){
  validate(pattern);if(samples==null||samples.length!=LANES||bars<1||bars>8||bpm<40||bpm>240)throw new IllegalArgumentException("Invalid drum pattern parameters");
  float[] levels={.72f,.52f,.5f,.27f,.27f,.31f,.42f,.42f};
  float[] pcm=SampleAudio.pattern(bpm,pattern,samples,levels,bars);
  for(int i=0;i<pcm.length;i++)pcm[i]=(float)Math.tanh(pcm[i]);
  return pcm;
 }

 /** Compact lossless pattern encoding including tempo. Saved with audio clip for MIDI step editing. */
 public static String encode(boolean[][] pattern,int bpm){
  validate(pattern);if(bpm<40||bpm>240)throw new IllegalArgumentException("BPM");
  StringBuilder out=new StringBuilder().append(bpm).append(':');
  for(int lane=0;lane<LANES;lane++)for(int step=0;step<STEPS;step++)out.append(pattern[lane][step]?'1':'0');
  return out.toString();
 }
 public static boolean isValidPatternText(String s){
  try {decode(s);return true;}catch(RuntimeException e){return false;}
 }
 public static int tempo(String s){return Integer.parseInt(s.substring(0,s.indexOf(':')));}
 public static boolean[][] decode(String s){
  if(s==null)throw new IllegalArgumentException("Missing pattern");
  int colon=s.indexOf(':');
  if(colon<1||colon>3||s.length()!=colon+1+LANES*STEPS)throw new IllegalArgumentException("Invalid pattern");
  int bpm=tempo(s);if(bpm<40||bpm>240)throw new IllegalArgumentException("Tempo out of range");
  boolean[][] out=new boolean[LANES][STEPS];int p=colon+1;
  for(int lane=0;lane<LANES;lane++)for(int step=0;step<STEPS;step++){
   char ch=s.charAt(p++);if(ch!='0'&&ch!='1')throw new IllegalArgumentException("Invalid step");
   out[lane][step]=ch=='1';
  }
  return out;
 }
 /** MIDI notes are relative to the pattern clip, enabling step editing and standard MIDI export. */
 public static java.util.List<Pro.Note> notes(boolean[][] pattern,int bpm,int bars){
  validate(pattern);if(bpm<40||bpm>240||bars<1||bars>64)throw new IllegalArgumentException("Invalid note options");
  java.util.ArrayList<Pro.Note> notes=new java.util.ArrayList<>();
  long step=Math.round(Core.RATE*60.0/(bpm*4.0));
  for(int bar=0;bar<bars;bar++)for(int s=0;s<STEPS;s++)for(int lane=0;lane<LANES;lane++)
   if(pattern[lane][s])notes.add(new Pro.Note(36+lane,105,(bar*16L+s)*step,Math.max(1,step/2),9));
  return notes;
 }
 /** The engine reads the latest backing pattern in-place, without resetting the project transport. */
 public static final class LivePattern implements Core.Source {
  private volatile float[] phrase;
  private final int total;
  public LivePattern(float[] audio,int totalFrames){if(audio==null||audio.length<2||totalFrames<1)throw new IllegalArgumentException();phrase=audio;total=totalFrames;}
  public int frames(){return total;}
  public float sample(int frame,int channel){
   float[] data=phrase;int phraseFrames=data.length/2;
   return frame>=0&&frame<total?data[((frame%phraseFrames)*2)+(channel&1)]:0f;
  }
  public void replace(float[] audio){if(audio==null||audio.length<2)throw new IllegalArgumentException();phrase=audio;}
 }

 /** Export the recorded 16-step MIDI grid as a standards-compatible format-0 MIDI file. */
 public static void writeMidi(java.io.OutputStream output,boolean[][] steps,int bpm,int bars)throws java.io.IOException{
  java.util.List<Pro.Note> notes=notes(steps,bpm,bars);
  java.util.ArrayList<MidiTake.Event> events=new java.util.ArrayList<>();
  for(Pro.Note n:notes){
   events.add(new MidiTake.Event((int)n.start,0x99,n.pitch,n.velocity));
   events.add(new MidiTake.Event((int)n.end(),0x89,n.pitch,0));
  }
  events.sort((a,b)->a.frame==b.frame?Integer.compare(a.status,b.status):Integer.compare(a.frame,b.frame));
  MidiTake t=new MidiTake(Synth.factory(0),bpm);
  t.events.addAll(events);t.writeMidi(output);
 }
}

package ug.audiocity.studio;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Offline, deterministic BeatPilot beginner grooves. No cloud AI or payments. */
public final class BeatPilot {
 private BeatPilot() {}
 public static final String[] GENRES={"Kidandali / Afropop","Afrobeats","Dancehall","Soft R&B","Gospel"};
 public static final String[] FEELS={"Gentle","Balanced","Energetic"};
 public static final int[] DRUM_COLOR={0xff47bbae,0xffbe97dc,0xffe6b86d};
 public static int bassRoot(int root,boolean minor,int chordDegree){return PocketComposer.scale(root,minor,chordDegree,1);}
 public static int[] smartBassPads(int root,boolean minor,int chordDegree){
  int note=bassRoot(root,minor,chordDegree);
  return new int[]{note,note+7,note+12,note+2,note+5,note+9,note-5,note-12};
 }
 public static boolean[][] drumSteps(int genre,int feel,int bar){
  if(genre<0||genre>=GENRES.length||feel<0||feel>=FEELS.length)throw new IllegalArgumentException("Invalid genre or feel");
  int style=genre==2?1:genre==3?2:0;
  boolean[][] src=PocketComposer.drums(style,"VERSE",Math.floorMod(bar,4));
  if(genre==4){src[0][6]=false;src[0][11]=false;src[0][8]=true;}
  if(feel==0){for(int i=1;i<16;i+=2)src[2][i]=false;src[0][11]=false;}
  if(feel==2){for(int i=0;i<16;i+=4)src[2][i]=true;src[0][14]=true;}
  return src;
 }
 /** Explicit auto-build template, editable in the same manual pattern rack. */
 public static BeatPilotDrumPattern pattern(int genre,int feel,int bpm,int bars){BeatPilotDrumPattern pattern=new BeatPilotDrumPattern(bpm,bars);for(int b=0;b<bars;b++){boolean[][] hits=drumSteps(genre,feel,b);int[] lane={0,1,3};for(int part=0;part<3;part++)for(int step=0;step<16;step++)if(hits[part][step])pattern.set(b,lane[part],step,part==0?115:part==1?105:86);}return pattern;}
 public static List<Core.Track> harmony(Core.Session session,int genre,int root,boolean minor,int progression,int bars,long at){
  if(bars!=4&&bars!=8)throw new IllegalArgumentException("Choose four or eight bars");if(genre<0||genre>=GENRES.length||root<0||root>11)throw new IllegalArgumentException("Invalid music settings");
  Core.Track bass=new Core.Track("Auto · Bass",DRUM_COLOR[1]),chords=new Core.Track("Auto · Chords",DRUM_COLOR[2]);bass.volume=.76;chords.volume=.62;chords.fx.room=true;chords.fx.roomMix=.09;
  int[] degrees=PerformanceChords.degrees(minor,progression);int style=genre==2?1:genre==3?2:0;double bar=Core.RATE*60.0/session.bpm*4;
  if(at<0||at+Math.round(bar*bars)>Core.MAX_FRAMES)throw new IllegalArgumentException("Groove exceeds project length");
  for(int b=0;b<bars;b++)for(int part=0;part<2;part++){int degree=degrees[b%degrees.length];Core.Clip clip=ProMidi.clip(PocketComposer.chordName(root,minor,degree),PocketComposer.barNotes(part,root,minor,degree,session.bpm,style,false),Synth.factory(part==0?2:style==2?1:3),at+Math.round(b*bar),(int)Math.round(bar));(part==0?bass:chords).clips.add(clip);}
  return java.util.Arrays.asList(bass,chords);
 }
 /** Generates three separate, non-destructive Arrange tracks with a four- or eight-bar groove. */
 public static List<Core.Track> groove(Core.Session session,int genre,int feel,int root,boolean minor,int progression,int bars,long start)throws IOException {
  return groove(session,genre,feel,root,minor,progression,bars,start,null);
 }
 public static List<Core.Track> groove(Core.Session session,int genre,int feel,int root,boolean minor,int progression,int bars,long start,File libraryHome)throws IOException {
  if(session==null||session.directory==null)throw new IllegalArgumentException("Project is missing");
  if(bars!=4&&bars!=8)throw new IllegalArgumentException("Choose 4 or 8 bars");
  if(root<0||root>11||genre<0||genre>=GENRES.length||feel<0||feel>=FEELS.length)throw new IllegalArgumentException("Invalid song settings");
  long barFrames=Core.beat(session.bpm)*4L;
  if(start<0||start+barFrames*bars>Core.MAX_FRAMES)throw new IllegalArgumentException("Groove exceeds project length");
  File dir=new File(session.directory);if(!dir.isDirectory())throw new IOException("Project folder missing");
  Core.Track drums=new Core.Track("BeatPilot · Drums",DRUM_COLOR[0]);drums.volume=.7;
  Core.Track bass=new Core.Track("BeatPilot · Smart Bass",DRUM_COLOR[1]);bass.volume=.76;
  Core.Track chords=new Core.Track("BeatPilot · Chords",DRUM_COLOR[2]);chords.volume=.62;chords.fx.room=true;chords.fx.roomMix=.09;
  List<Core.Track> created=new ArrayList<>();created.add(drums);created.add(bass);created.add(chords);
  float[][] drumSamples=loadDrumSamples(libraryHome);
  int[] degrees=PerformanceChords.degrees(minor,progression);
  // Generate files off the UI thread. Roll back created audio files if any part fails.
  List<File> made=new ArrayList<>();
  try{
   for(int b=0;b<bars;b++){
    int degree=degrees[b%degrees.length];long pos=start+b*barFrames;
    boolean[][] hits=drumSteps(genre,feel,b);
    float[] d=drumSamples==null?Core.drumPattern(session.bpm,hits,1):sampledDrums(session.bpm,hits,drumSamples);
    int style=genre==2?1:genre==3?2:0;
    for(int n=0;n<3;n++){
     String name=(n==0?"Drum groove":n==1?"Bass · ":"Chords · ")+(n==0?(b+1):PocketComposer.chordName(root,minor,degree));
     Core.Clip clip;
     if(n==0){clip=AudioIO.store(dir,d,name,pos);made.add(new File(dir,clip.file));}
     else{
      int part=n-1;List<Pro.Note> notes=PocketComposer.barNotes(part,root,minor,degree,session.bpm,style,false);
      Synth.Config instrument=Synth.factory(part==0?2:style==2?1:3);
      clip=ProMidi.clip(name,notes,instrument,pos,(int)barFrames);
     }
     created.get(n).clips.add(clip);
    }
   }
   return created;
  }catch(Exception e){for(File file:made)file.delete();if(e instanceof IOException)throw (IOException)e;throw new IOException("Could not generate groove",e);}
 }
 /** Load only three essentials from the built-in licensed kit; fall back to the original synth if unavailable. */
 static float[][] loadDrumSamples(File libraryHome){
  if(libraryHome==null)return null;
  File dir=new File(libraryHome,"factory-soundza-afrobeats-drums/samples");
  String[] files={"soundza-heavy-punch-kick.wav","soundza-crisp-snare-hit.wav","soundza-tight-closed-hi-hat.wav"};
  float[][] sounds=new float[3][];
  try{for(int i=0;i<3;i++)sounds[i]=SampledInstruments.readWav(new File(dir,files[i]));return sounds;}
  catch(Exception missing){return null;}
 }
 static float[] sampledDrums(int bpm,boolean[][] steps,float[][] sounds){
  if(bpm<40||bpm>240||steps==null||steps.length<3||sounds==null||sounds.length<3)throw new IllegalArgumentException("Invalid drum kit");
  int bar=(int)(Core.beat(bpm)*4);float[] out=new float[bar*2];
  int step=(int)(Core.RATE*60.0/bpm/4);
  for(int lane=0;lane<3;lane++)for(int i=0;i<16;i++)if(steps[lane][i]){
   int at=i*step;float[] sound=sounds[lane];if(sound==null)continue;
   float gain=lane==0?.68f:lane==1?.58f:.38f;
   int frames=Math.min(sound.length/2,bar-at);
   for(int j=0;j<frames;j++){out[(at+j)*2]+=sound[j*2]*gain;out[(at+j)*2+1]+=sound[j*2+1]*gain;}
  }
  for(int i=0;i<out.length;i++)out[i]=(float)Math.tanh(out[i]);
  return out;
 }

}

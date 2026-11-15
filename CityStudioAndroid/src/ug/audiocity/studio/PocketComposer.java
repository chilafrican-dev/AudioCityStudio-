package ug.audiocity.studio;
import java.io.*;import java.util.*;
/** Original, deterministic musical building blocks for composing without a controller. */
public final class PocketComposer {
 public static final String[] ROOTS={"C","C♯","D","E♭","E","F","F♯","G","A♭","A","B♭","B"};
 public static final String[] STYLES={"Afropop bounce","Dancehall pocket","Soft R&B"};
 public static int scale(int root,boolean minor,int degree,int octave){int[] steps=minor?new int[]{0,2,3,5,7,8,10}:new int[]{0,2,4,5,7,9,11};return 12*(octave+1)+root+12*Math.floorDiv(degree,7)+steps[Math.floorMod(degree,7)];}
 public static int[] progression(boolean minor,int choice){int[][] major={{0,4,3,3},{0,5,3,4},{5,3,0,4}},small={{0,5,2,6},{0,3,5,4},{0,6,5,6}};if(choice<0||choice>2)throw new IllegalArgumentException("Choose a progression");return (minor?small:major)[choice].clone();}
 public static int[] chord(int root,boolean minor,int degree){return new int[]{scale(root,minor,degree,3),scale(root,minor,degree+2,3),scale(root,minor,degree+4,3)};}
 public static String noteName(int midi){return ROOTS[Math.floorMod(midi,12)]+(midi/12-1);}
 public static String chordName(int root,boolean minor,int degree){int[] c=chord(root,minor,degree);return ROOTS[c[0]%12]+(c[1]-c[0]==3?"m":"");}
 public static String section(int bar,int bars){if(bar<4)return "INTRO";if(bar>=bars-4)return "OUTRO";if(bars==16)return bar<8?"VERSE":"HOOK";return bar<12?"VERSE":bar<20?"HOOK":"VERSE 2";}
 public static boolean[][] drums(int style,String section,int variant){boolean[][] p=new boolean[3][16];boolean sparse=section.equals("INTRO")||section.equals("OUTRO");int[] kick=style==0?new int[]{0,6,8,11}:style==1?new int[]{0,7,10}:new int[]{0,7,8};for(int n:kick)if(!sparse||n==0||n==8)p[0][n]=true;for(int n:new int[]{4,12})p[1][n]=true;for(int i=0;i<16;i+=sparse?4:2)p[2][i]=true;if(!sparse&&style==0)p[2][3]=p[2][11]=true;if(variant==3&&!sparse){p[1][14]=true;p[2][15]=true;}return p;}
 static void mix(float[] out,float[] voice,int frame,float level){for(int i=0;i<voice.length&&frame*2+i<out.length;i++)out[frame*2+i]+=voice[i]*level;}
 public static float[] bar(int part,int root,boolean minor,int degree,int bpm,int style,boolean hook){if(bpm<40||bpm>240||root<0||root>11||part<0||part>2)throw new IllegalArgumentException("Invalid musical settings");int frames=(int)(Core.beat(bpm)*4);float[] out=new float[frames*2];double beat=60.0/bpm;Synth.Config sound=Synth.factory(part==0?2:part==1?(style==2?1:3):8);if(part==0){int note=scale(root,minor,degree,1);double[] times=style==1?new double[]{0,1.75,2.5}:new double[]{0,1.5,2,3.25};for(double at:times)mix(out,Synth.note(note,beat*.48,sound),(int)Math.round(at*Core.beat(bpm)),.78f);}else if(part==1){double[] times=style==2?new double[]{0}:new double[]{0,.75,1.5,2.75};for(double at:times)for(int note:chord(root,minor,degree))mix(out,Synth.note(note,beat*(style==2?3.1:.32),sound),(int)Math.round(at*Core.beat(bpm)),style==2?.4f:.48f);}else{int[] degrees={degree+7,degree+9,degree+8,degree+11};double[] times={.5,1.25,2.5,3.25};for(int n=0;n<degrees.length;n++)mix(out,Synth.note(scale(root,minor,degrees[n],3),beat*.35,sound),(int)Math.round(times[n]*Core.beat(bpm)),hook?.65f:.35f);}int fade=Math.min(frames/2,240);for(int i=0;i<fade;i++)for(int ch=0;ch<2;ch++)out[(frames-1-i)*2+ch]*=i/(float)fade;return out;}
 public static List<Pro.Note> barNotes(int part,int root,boolean minor,int degree,int bpm,int style,boolean hook){
  if(bpm<40||bpm>240||root<0||root>11||part<0||part>2)throw new IllegalArgumentException("Invalid musical settings");
  long beat=Core.beat(bpm);double beatSeconds=60.0/bpm;ArrayList<Pro.Note> notes=new ArrayList<>();
  if(part==0){
   int pitch=scale(root,minor,degree,1);double[] times=style==1?new double[]{0,1.75,2.5}:new double[]{0,1.5,2,3.25};
   for(double at:times)notes.add(new Pro.Note(pitch,94,Math.round(at*beat),Math.max(1,Math.round(beatSeconds*.48*Core.RATE)),0));
  }else if(part==1){
   double[] times=style==2?new double[]{0}:new double[]{0,.75,1.5,2.75};int velocity=style==2?40:51;
   for(double at:times)for(int pitch:chord(root,minor,degree))notes.add(new Pro.Note(pitch,velocity,Math.round(at*beat),Math.max(1,Math.round(beatSeconds*(style==2?3.1:.32)*Core.RATE)),0));
  }else{
   int[] degrees={degree+7,degree+9,degree+8,degree+11};double[] times={.5,1.25,2.5,3.25};int velocity=hook?77:33;
   for(int i=0;i<degrees.length;i++)notes.add(new Pro.Note(scale(root,minor,degrees[i],3),velocity,Math.round(times[i]*beat),Math.max(1,Math.round(beatSeconds*.35*Core.RATE)),0));
  }
  return notes;
 }
 public static Core.Session song(File directory,int root,boolean minor,int progression,int bpm,int style,int bars)throws Exception {
  if(style<0||style>2||(bars!=16&&bars!=32)||bpm<40||bpm>240||root<0||root>11)throw new IllegalArgumentException("Invalid song settings");
  Core.Session song=new Core.Session();song.directory=directory.getAbsolutePath();song.bpm=bpm;song.tuneKey=root;song.tuneScale=minor?2:1;song.tuneConfigured=true;song.title=STYLES[style]+" · "+ROOTS[root]+(minor?" minor":" major");String[] names={"Groove / drums","Root / bass","Harmony / keys","Hook / melody","Your vocal"};int[] colors={0xff50cfc9,0xffb294f4,0xffe8bc64,0xff6aace5,0xffe27988};for(int i=0;i<5;i++){Core.Track t=new Core.Track(names[i],colors[i]);t.volume=i==0?.65:i==1?.8:.65;if(i==2||i==3){t.fx.room=true;t.fx.roomMix=.1;}song.tracks.add(t);}song.tracks.get(4).fx.comp=true;song.tracks.get(4).fx.deess=true;
  int[] degrees=progression(minor,progression);Map<String,Core.Clip> cache=new HashMap<>();long barFrames=Core.beat(bpm)*4;
  for(int b=0;b<bars;b++){String section=section(b,bars);boolean hook=section.equals("HOOK");int degree=degrees[b%4];for(int part=0;part<4;part++){if(part==1&&(section.equals("INTRO")&&b<2||section.equals("OUTRO")&&b>=bars-2))continue;if(part==3&&!hook)continue;String key=part==0?"d"+section+":"+(b%4):part+":"+degree+":"+hook;Core.Clip base=cache.get(key);if(base==null){float[] samples=part==0?Core.drumPattern(bpm,drums(style,section,b%4),1):bar(part-1,root,minor,degree,bpm,style,hook);base=AudioIO.store(directory,samples,"Pattern",0);cache.put(key,base);}Core.Clip clip=base.copy();clip.start=b*barFrames;clip.name=section+" · "+(part==0?"Groove":chordName(root,minor,degree));song.tracks.get(part).clips.add(clip);}}
  AudioIO.save(song);return song;
 }
}

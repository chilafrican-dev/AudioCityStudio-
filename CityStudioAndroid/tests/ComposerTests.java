package ug.audiocity.studio;
import java.io.*;import java.nio.file.*;import java.util.*;
public class ComposerTests {
 static int passed;static void check(boolean c,String message){if(!c)throw new AssertionError(message);passed++;}
 public static void main(String[] args)throws Exception{
  for(int root=0;root<12;root++)for(boolean minor:new boolean[]{false,true}){
   int[] scale=minor?new int[]{0,2,3,5,7,8,10}:new int[]{0,2,4,5,7,9,11};boolean valid=true;
   for(int degree=0;degree<8;degree++){int note=PocketComposer.scale(root,minor,degree,4);valid&=note>=60&&note<96;valid&=Arrays.binarySearch(scale,Math.floorMod(note-root,12))>=0;}
   for(int p=0;p<3;p++)for(int degree:PocketComposer.progression(minor,p))for(int note:PocketComposer.chord(root,minor,degree))valid&=Arrays.binarySearch(scale,Math.floorMod(note-root,12))>=0;
   check(valid,"Every melody pad and progression chord stays in its chosen key: "+root+" "+minor);
  }
  check(PocketComposer.chordName(5,false,0).equals("F")&&PocketComposer.chordName(5,false,4).equals("C")&&PocketComposer.chordName(5,false,3).equals("B♭"),"F-C-B-flat chord labels and voicing");
  check(PocketComposer.chordName(9,true,0).equals("Am"),"Minor chord labels follow actual intervals");
  for(int style=0;style<3;style++)for(int part=0;part<3;part++){
   float[] sound=PocketComposer.bar(part,5,false,3,100,style,true);double energy=0;boolean finite=true;for(float v:sound){finite&=Float.isFinite(v)&&Math.abs(v)<1;energy+=v*v;}
   check(finite&&energy>1&&sound.length==Core.beat(100)*8,"Audible finite bar at exact project tempo: "+style+"/"+part);
   check(sound[sound.length-1]==0&&sound[sound.length-2]==0,"Musical bar ends at zero without a hard boundary");
  }
  for(int bars:new int[]{16,32}){
   File dir=Files.createTempDirectory("city-song").toFile();Core.Session s=PocketComposer.song(dir,5,false,0,105,0,bars);check(s.tracks.size()==5&&s.tracks.get(4).clips.isEmpty(),"Independent backing tracks and empty vocal track");check(s.end()==Core.beat(105)*4*bars,"Arrangement length exactly matches requested bars");
   boolean aligned=true,sections=true,shared=false;HashSet<String> files=new HashSet<>();for(int part=0;part<4;part++)for(Core.Clip c:s.tracks.get(part).clips){int bar=(int)(c.start/(Core.beat(105)*4));aligned&=c.start% (Core.beat(105)*4)==0&&c.length==Core.beat(105)*4;sections&=c.name.startsWith(PocketComposer.section(bar,bars));if(!files.add(c.file))shared=true;if(part==3)sections&=PocketComposer.section(bar,bars).equals("HOOK");}
   check(aligned&&sections,"Section-aware placement and hook melody timing");check(shared,"Repeated patterns share source files");Core.Session loaded=AudioIO.load(dir);check(loaded.end()==s.end()&&loaded.tracks.get(2).clips.size()==bars,"Generated project saves and reloads");
   Core.Renderer renderer=new Core.Renderer(loaded);float[] block=new float[1024];boolean safe=true;for(long pos=0;pos<s.end();pos+=48000){renderer.render(pos,512,block,false);for(float v:block)safe&=Float.isFinite(v)&&Math.abs(v)<=.892;}
   check(safe,"Generated arrangement renders within master headroom");for(File f:dir.listFiles())f.delete();dir.delete();
  }
  boolean reject=false;try{PocketComposer.song(new File("/unused"),5,false,0,20,0,16);}catch(IllegalArgumentException e){reject=true;}check(reject,"Invalid tempo is rejected before writing");
  MidiTake take=new MidiTake(Synth.factory(3),100);take.recording=false;take.frames=48000;take.events.add(new MidiTake.Event(0,144,65,100));take.events.add(new MidiTake.Event(0,144,69,100));take.events.add(new MidiTake.Event(0,144,72,100));take.events.add(new MidiTake.Event(24000,128,65,0));take.events.add(new MidiTake.Event(24000,128,69,0));take.events.add(new MidiTake.Event(24000,128,72,0));final double[] energy={0};take.print((l,r)->energy[0]+=l*l+r*r);check(energy[0]>1,"Touch chord events print audible audio without a MIDI device");ByteArrayOutputStream midi=new ByteArrayOutputStream();take.writeMidi(midi);check(midi.size()>40,"Touch take retains an exportable note source");
  ArrayList<String> sent=new ArrayList<>();TouchNotes fingers=new TouchNotes((status,note,velocity)->sent.add(status+":"+note));fingers.change(1,0,new int[]{60,64,67});fingers.change(2,1,new int[]{60});check(sent.size()==3,"Overlapping finger and chord share a sounding note");fingers.release(1);check(sent.size()==5&&!sent.contains("128:60")&&fingers.active(1),"Releasing a chord leaves another finger's note sounding");fingers.change(2,2,new int[]{62});check(sent.get(5).equals("128:60")&&sent.get(6).equals("144:62"),"Sliding ends the previous note and starts its neighbour");fingers.clear();check(sent.get(7).equals("128:62")&&!fingers.active(2),"Cancel and dismissal release all held notes");int count=sent.size();fingers.clear();fingers.release(99);check(sent.size()==count,"Repeated release does not create stuck-note state");
  System.out.println(passed+" controller-free composition checks passed.");
 }
}

package ug.audiocity.studio;
import java.io.*;import java.nio.file.*;
public final class BeatPilotSoundzaTests {
 static int checks=0;
 static void ok(boolean good,String reason){checks++;if(!good)throw new AssertionError(reason);}
 public static void main(String[] args)throws Exception{
  File tmp=Files.createTempDirectory("ac-soundza-").toFile();
  try{
   SampledInstruments library=new SampledInstruments(tmp);
   File archive=new File(args.length>0?args[0]:"assets","instruments/soundza-afrobeats-drums.zip");
   ok(archive.isFile(),"embedded licensed kit must exist");
   try(InputStream in=new FileInputStream(archive)){library.installBundledZip(in,"soundza-afrobeats-drums");}
   SampledInstruments.Info info=null;
   for(SampledInstruments.Info candidate:library.list())if(candidate.name.contains("Audio City Afrobeats Drums"))info=candidate;
   ok(info!=null,"drum kit must appear in instrument selector");
   SampledInstruments.Pack pack=library.load(info);
   ok(pack.regions.length==24,"all 24 drum hit regions must load");
   for(int note:new int[]{36,38,42,44,47,55,59})ok(pack.find(note,110).root==note,"drum pad MIDI note " +note);
   float[][] trio=BeatPilot.loadDrumSamples(library.folder());
   ok(trio!=null&&trio.length==3,"groove engine finds sampled kit");
   boolean[][] steps=BeatPilot.drumSteps(1,1,0);
   float[] rendered=BeatPilot.sampledDrums(100,steps,trio);
   ok(rendered.length==Core.beat(100)*8,"stereo bar is correct length");
   double energy=0;for(float x:rendered)energy+=x*x;
   ok(energy>1,"real samples render an audible groove");
   System.out.println("BeatPilotSoundzaTests passed "+checks+" checks");
  }finally{delete(tmp);}
 }
 static void delete(File f){if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File child:a)delete(child);}f.delete();}
}

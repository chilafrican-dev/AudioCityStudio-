package ug.audiocity.studio;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Regression suite for the user-uploaded four piano sample banks. */
public final class PianoLibraryTests {
 static int checks=0;
 static void check(boolean ok,String desc){checks++;if(!ok)throw new AssertionError(desc);}
 static void delete(File f){if(f.isDirectory()){File[] ls=f.listFiles();if(ls!=null)for(File s:ls)delete(s);}f.delete();}
 static String[] ids={"freepats-ydp-grand-compact","freepats-upright-kw-full","freepats-upright-kw-small","freepats-upright-kw-bright"};
 static int[] regions={150,66,26,26};
 static String[] display={"Grand Piano","Upright Piano - Full","Upright Piano - Lite","Upright Piano - Bright"};
 public static void main(String[] args)throws Exception{
  File assets=new File(args.length>0?args[0]:"assets","instruments");
  for(int q=0;q<ids.length;q++){
   String id=ids[q];File archive=new File(assets,id+".zip");
   check(archive.isFile(),id+" bundled asset must be present");
   File temp=Files.createTempDirectory("city-piano-test-").toFile();
   try{
    SampledInstruments library=new SampledInstruments(temp);
    try(InputStream in=new FileInputStream(archive)){library.installBundledZip(in,id);}
    List<SampledInstruments.Info> list=library.list();
    check(list.size()==1,"One instrument entry for "+id);
    check(list.get(0).name.contains(display[q]),"Friendly preset name: "+list.get(0).name);
    try(InputStream in=new FileInputStream(archive)){library.installBundledZip(in,id);}
    check(library.list().size()==1,"Factory installer must be idempotent");
    SampledInstruments.Pack pack=library.load(list.get(0));
    check(pack.regions.length==regions[q],"Expected mapped regions for "+id+" got "+pack.regions.length);
    for(int note:new int[]{36,48,60,72,84}){
     SampledInstruments.Region region=pack.find(note,100);
     check(region!=null&&region.pcm.length>0,"Sample accessible at note "+note);
     check(region.loopEnd<=region.pcm.length/2,"Sample loop must not exceed resampled sample length");
     check(region.low>=0&&region.high<=127,"MIDI mapping is valid");
    }
    if(id.contains("grand")){
     Set<String> ranges=new HashSet<>();for(SampledInstruments.Region region:pack.regions)ranges.add(region.lowVelocity+"-"+region.highVelocity);
     check(ranges.size()==5,"YDP grand keeps five velocity layers, got "+ranges.size());
     check(pack.source.contains("CC BY"),"Grand keeps the attribution license");
    } else check(pack.source.contains("CC0"),"Upright has CC0 license");
    SynthPoly player=new SynthPoly(Synth.factory(0));player.setSamplePack(pack);
    player.message(0x90,60,100);float[] block=new float[4096];player.render(block,2048);
    double energy=0;for(float n:block)energy+=n*n;
    check(energy>0.001,"Instrument must produce audible nonzero PCM: "+id);
    player.message(0x80,60,0);player.render(block,2048);
    check(player.activeVoices>0,"Note release must not cut off immediately");
    // Clear large decoded sample buffers before importing the next bank.
    player.setSamplePack(null);
   }finally{delete(temp);}
   System.gc();
   System.out.println("PASS "+id);
  }
  System.out.println("PianoLibraryTests passed "+checks+" checks");
 }
}

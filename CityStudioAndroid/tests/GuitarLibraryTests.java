package ug.audiocity.studio;
import java.io.*;import java.nio.file.*;import java.util.*;
public class GuitarLibraryTests {
 static void require(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void del(File f){if(f.isDirectory()){File[] fs=f.listFiles();if(fs!=null)for(File g:fs)del(g);}f.delete();}
 public static void main(String[] args)throws Exception{
 String[] variants={"clean","direct","jazz"};
 for(String v:variants){File a=new File(args[0],"instruments/freepats-electric-guitar-"+v+".zip");require(a.isFile(),"asset "+v);
 File tmp=Files.createTempDirectory("ac-guitar-").toFile();try{
 SampledInstruments lib=new SampledInstruments(tmp);
 try(InputStream in=new FileInputStream(a)){lib.installBundledZip(in,"freepats-electric-guitar-"+v);}
 require(lib.list().size()==1,"one instrument "+v);
 SampledInstruments.Info i=lib.list().get(0);
 require(i.name.startsWith("Audio City "),"brand "+i.name);
 SampledInstruments.Pack p=lib.load(i);
 require(p.regions.length==24,"24 regions "+v+" "+p.regions.length);
 require(p.source.contains("CC0"),"license "+v);
 SynthPoly player=new SynthPoly(Synth.factory(0));player.setSamplePack(p);player.message(0x90,60,105);
 float[] audio=new float[4096];player.render(audio,2048);double sum=0;for(float n:audio)sum+=n*n;
 require(sum>0.0001,"audible "+v+": "+sum);
 System.out.println("PASS Audio City "+v+" guitar, rms energy="+sum);
 }finally{del(tmp);}}
 }
}

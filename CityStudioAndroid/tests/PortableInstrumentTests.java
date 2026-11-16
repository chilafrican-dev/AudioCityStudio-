package ug.audiocity.studio;
import java.io.*;import java.nio.file.*;import java.util.*;
public final class PortableInstrumentTests {
 static int checks;static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void clean(File f){if(f.isDirectory())for(File c:f.listFiles())clean(c);f.delete();}
 public static void main(String[] args)throws Exception{
  File dir=Files.createTempDirectory("acs-portable-pack-").toFile();try{
   float[] pcm=new float[4000];for(int i=0;i<2000;i++)pcm[2*i]=pcm[2*i+1]=(float)Math.sin(i*.1)*.3f;
   SampledInstruments.Region r1=new SampledInstruments.Region(0,60,1,127,48,20,1800,true,.8f,pcm,12000),r2=new SampledInstruments.Region(61,127,1,127,72,20,1800,true,.8f,pcm,12000);
   SampledInstruments.Pack pack=new SampledInstruments.Pack("Custom keys","User imported source and license",new SampledInstruments.Region[]{r1,r2});
   String file=InstrumentArchive.write(pack,dir);SampledInstruments.Pack read=InstrumentArchive.read(dir,file);
   ok(read.name.equals(pack.name)&&read.source.equals(pack.source),"instrument name and attribution retained");ok(read.regions.length==2&&read.regions[0].pcm==read.regions[1].pcm,"shared PCM stays deduplicated");
   ok(Arrays.equals(read.regions[0].pcm,pcm)&&read.regions[0].root==48&&read.regions[1].root==72,"sample values and multisample mapping preserved");
   ok(read.regions[0].loop&&read.regions[0].loopStart==20&&read.regions[0].loopEnd==1800&&read.regions[0].releaseFrames==12000,"loop and release settings preserved");
   ok(InstrumentArchive.write(pack,dir).equals(file),"repeat backup reuses the same content filename");
   Core.Session s=new Core.Session();s.directory=dir.getAbsolutePath();Core.Track t=new Core.Track("Custom",0);s.tracks.add(t);
   List<Pro.Note> notes=new ArrayList<>(Arrays.asList(new Pro.Note(60,100,0,4000,0)));
   Core.Clip c=ProMidi.clip("Part",notes,Synth.factory(0),pack,"user-pack/keys.sfz",0,8000);t.clips.add(c);t.takes.add(c.copy());
   InstrumentArchive.embed(s);ok(c.instrumentFile!=null&&c.instrumentFile.equals(t.takes.get(0).instrumentFile),"one pack file covers the track and takes");
   ok(ProjectIO.files(s).contains(c.instrumentFile),"embedded samples are included in backup file list");ProjectIO.save(s);
   Core.Session loaded=ProjectIO.load(dir,f->{throw new IOException("No audio file should be requested");});
   ok(loaded.tracks.get(0).clips.get(0).instrumentFile.equals(c.instrumentFile),"project reopen preserves embedded sample reference");
   List<String> missing=ProjectInstruments.prepare(loaded,clip->{throw new IOException("No globally installed packs on this phone");});
   Core.Clip restored=loaded.tracks.get(0).clips.get(0);ok(missing.isEmpty()&&restored.samplePack!=null&&restored.samplePack.name.equals(pack.name),"project uses embedded instrument on a phone with no installed library");
   for(int frame:new int[]{0,400,1900,2000,4500})for(int ch=0;ch<2;ch++)ok(Math.abs(c.source.sample(frame,ch)-restored.source.sample(frame,ch))<.000001,"restored sample sound matches original");
   float[] otherPcm=pcm.clone();for(int i=0;i<otherPcm.length;i++)otherPcm[i]*=-.5f;SampledInstruments.Pack other=SampledInstruments.monoPack("New instrument",otherPcm,60,"User changed sound");
   ProMidi.assignInstrument(restored,Synth.factory(2),other,"another-pack/new.sfz");ProMidi.refresh(restored);ok(restored.instrumentFile==null,"changing instrument clears old embedded pack reference");
   loaded.tracks.get(0).takes.clear();ProjectIO.save(loaded);Core.Session changed=ProjectIO.load(dir,f->{throw new IOException("Unexpected audio");});ProjectInstruments.prepare(changed,clip->other);ok(changed.tracks.get(0).clips.get(0).samplePack.name.equals("New instrument"),"reopen keeps newly chosen sound instead of restoring old embedded samples");
   try{InstrumentArchive.read(dir,"../outside.acpack");throw new AssertionError("unsafe path accepted");}catch(IOException expected){checks++;}
   String bad="bad.acpack";Files.write(new File(dir,bad).toPath(),new byte[20]);try{InstrumentArchive.read(dir,bad);throw new AssertionError("bad header accepted");}catch(IOException expected){checks++;}
   System.out.println("PortableInstrumentTests passed "+checks+" checks");
  }finally{clean(dir);}
 }
}

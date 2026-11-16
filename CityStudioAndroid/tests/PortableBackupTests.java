package ug.audiocity.studio;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;
public final class PortableBackupTests{
 static int checks;static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}static void clean(File f){if(f.isDirectory())for(File c:f.listFiles())clean(c);f.delete();}
 public static void main(String[] args)throws Exception{
  File root=Files.createTempDirectory("acs-backup-").toFile(),source=new File(root,"source"),destination=new File(root,"restored");source.mkdir();destination.mkdir();try{
   Core.Session s=new Core.Session();s.directory=source.getAbsolutePath();float[] audio=new float[5000];Arrays.fill(audio,.3f);SampledInstruments.Pack pack=SampledInstruments.monoPack("My sample",audio,60,"User sample");Core.Track t=new Core.Track("Keys",0);s.tracks.add(t);t.clips.add(ProMidi.clip("Notes",new ArrayList<>(Arrays.asList(new Pro.Note(60,110,0,2000,0))),Synth.factory(0),pack,"custom/sample.wav",0,6000));
   ByteArrayOutputStream backup=new ByteArrayOutputStream();AudioIO.backup(backup,s);boolean samples=false;try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(backup.toByteArray()))){ZipEntry e;while((e=zip.getNextEntry())!=null)if(e.getName().endsWith(".acpack"))samples=true;}ok(samples,"portable backup contains imported instrument samples");
   Core.Session restored=AudioIO.restore(new ByteArrayInputStream(backup.toByteArray()),destination);List<String> missing=ProjectInstruments.prepare(restored,c->{throw new IOException("Factory library absent");});
   ok(missing.isEmpty()&&restored.tracks.get(0).clips.get(0).samplePack.name.equals(pack.name),"backup restores original instrument without external packs");ok(restored.tracks.get(0).clips.get(0).source.sample(300,0)>0,"restored project remains audible");
   Core.Session tooBig=new Core.Session();tooBig.directory=source.getAbsolutePath();Core.Track audioTrack=new Core.Track("Large",0);tooBig.tracks.add(audioTrack);Core.Clip large=new Core.Clip("Large",new Core.Memory(new float[16]),"large.pcm",0);audioTrack.clips.add(large);try(RandomAccessFile sparse=new RandomAccessFile(new File(source,"large.pcm"),"rw")){sparse.setLength(1073741825L);}
   try{AudioIO.backup(new ByteArrayOutputStream(),tooBig);throw new AssertionError("unrestorable oversized backup accepted");}catch(IOException expected){checks++;}new File(source,"large.pcm").delete();
   large.file="../outside.pcm";try{AudioIO.backup(new ByteArrayOutputStream(),tooBig);throw new AssertionError("out-of-project backup source accepted");}catch(IOException expected){checks++;}
   System.out.println("PortableBackupTests passed "+checks+" checks");
  }finally{clean(root);}
 }
}

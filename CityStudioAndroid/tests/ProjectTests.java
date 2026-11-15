package ug.audiocity.studio;
import java.io.*;import java.nio.file.*;import java.util.zip.*;import org.json.*;
public class ProjectTests {
 static int passed;static void check(boolean b,String m){if(!b)throw new AssertionError(m);passed++;}
 static byte[] archive(String name,byte[] content)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();ZipOutputStream z=new ZipOutputStream(bytes);z.putNextEntry(new ZipEntry(name));z.write(content);z.closeEntry();z.close();return bytes.toByteArray();}
 static void rejects(byte[] archive,File dest)throws Exception {dest.mkdirs();boolean failed=false;try{AudioIO.restore(new ByteArrayInputStream(archive),dest);}catch(Exception e){failed=true;}check(failed,"Invalid archive was accepted");check(!dest.exists()||dest.listFiles().length==0,"Failed restore must clean generated files");}
 public static void main(String[] args)throws Exception {
  File base=Files.createTempDirectory("city-project-test").toFile(),dir=new File(base,"original");dir.mkdirs();Core.Session s=new Core.Session();s.title="Nico’s session";s.directory=dir.getAbsolutePath();s.bpm=106;s.master=.72;Core.Track t=new Core.Track("Lead vocal",0xffdd7788);t.volume=.65;t.pan=-.3;t.fx.comp=true;t.fx.threshold=-21;t.fx.room=true;float[] tone=StudioTests.wave(12000,400,.2);Core.Clip c=AudioIO.store(dir,tone,"Take 1",2000);c.offset=1000;c.length=7000;t.clips.add(c);Core.Clip duplicate=c.copy();duplicate.start=10000;t.clips.add(duplicate);s.tracks.add(t);AudioIO.save(s);Core.Session loaded=AudioIO.load(dir);
  check(loaded.title.equals(s.title)&&loaded.bpm==106&&loaded.master==.72,"Session metadata roundtrip");Core.Track restored=loaded.tracks.get(0);check(restored.fx.comp&&restored.fx.room&&restored.fx.threshold==-21&&restored.pan==-.3&&restored.volume==.65,"Mixer and FX roundtrip");check(restored.clips.size()==2&&restored.clips.get(0).offset==1000&&restored.clips.get(0).length==7000,"Clip editing roundtrip");check(restored.clips.get(0).source==restored.clips.get(1).source,"Duplicate clips share a mapped source");
  float[] before=StudioTests.render(s,24000),after=StudioTests.render(loaded,24000);double difference=0;for(int i=0;i<before.length;i++)difference=Math.max(difference,Math.abs(before[i]-after[i]));check(difference<1e-7,"Restored project renders identical audio");
  ByteArrayOutputStream zip=new ByteArrayOutputStream();AudioIO.backup(zip,s);File destination=new File(base,"restored");destination.mkdirs();Core.Session portable=AudioIO.restore(new ByteArrayInputStream(zip.toByteArray()),destination);check(portable.title.equals(s.title)&&new File(destination,c.file).isFile(),"Portable archive restores source audio");
  boolean refused=false;try{AudioIO.restore(new ByteArrayInputStream(zip.toByteArray()),destination);}catch(IOException e){refused=true;}check(refused&&new File(destination,"session.json").isFile(),"Restore never overwrites existing project directory");
  rejects(archive("../escape.txt",new byte[]{1}),new File(base,"traversal"));check(!new File(base,"escape.txt").exists(),"Archive path cannot escape destination");
  rejects(archive("unknown.txt",new byte[]{1}),new File(base,"wrong-file"));
  rejects(archive("session.json",new byte[1048577]),new File(base,"too-large"));
  rejects(archive("source.pcm",new byte[]{1,2}),new File(base,"missing-manifest"));
  JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(dir,"session.json").toPath()),"UTF-8"));manifest.getJSONArray("tracks").getJSONObject(0).getJSONArray("clips").getJSONObject(0).put("file","../outside.pcm");Files.write(new File(dir,"session.json").toPath(),manifest.toString().getBytes("UTF-8"));boolean unsafe=false;try{AudioIO.load(dir);}catch(IOException e){unsafe=true;}check(unsafe,"Session cannot map an outside file");
  System.out.println(passed+" project and backup checks passed (host AtomicFile adapter, not Android device tests).");
 }
}

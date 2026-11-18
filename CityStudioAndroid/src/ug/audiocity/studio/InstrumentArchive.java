package ug.audiocity.studio;
import java.io.*;import java.nio.file.*;import java.security.*;import java.util.*;
/** Portable, bounded sample-pack snapshots for project backups. No executable content. */
public final class InstrumentArchive {
 private static final int MAGIC=0x41434950,VERSION=1,MAX_REGIONS=100000;private static final long MAX_MEMORY=128L*1024*1024,MAX_FILE=160L*1024*1024;
 private InstrumentArchive(){}
 static String key(Core.Clip c){return c.instrumentFile!=null?"data:"+c.instrumentFile:c.instrumentPackId;}
 public static void embed(Core.Session project)throws IOException{
  if(project.directory==null)throw new IOException("Project folder is unavailable");File directory=new File(project.directory);
  IdentityHashMap<SampledInstruments.Pack,String> written=new IdentityHashMap<>();
  for(Core.Clip c:clips(project))if(c.notes!=null&&c.samplePack!=null){String file=written.get(c.samplePack);if(file==null){file=write(c.samplePack,directory);written.put(c.samplePack,file);}c.instrumentFile=file;if(c.instrumentPackId==null)c.instrumentPackId="embedded:"+file;}
 }
 static List<Core.Clip> clips(Core.Session s){ArrayList<Core.Clip> clips=new ArrayList<>();for(Core.Track t:s.tracks){if(t.ephemeral)continue;clips.addAll(t.clips);clips.addAll(t.takes);for(Core.Clip c:t.slots)if(c!=null)clips.add(c);}if(s.reference!=null)clips.add(s.reference);return clips;}
 public static String write(SampledInstruments.Pack pack,File directory)throws IOException{
  if(pack.regions.length<1||pack.regions.length>MAX_REGIONS)throw new IOException("Invalid instrument region count");
  IdentityHashMap<float[],Integer> ids=new IdentityHashMap<>();ArrayList<float[]> data=new ArrayList<>();long bytes=0;
  for(SampledInstruments.Region r:pack.regions)if(!ids.containsKey(r.pcm)){if(r.pcm.length<2||(r.pcm.length&1)!=0)throw new IOException("Invalid instrument audio");bytes+=(long)r.pcm.length*4;if(bytes>MAX_MEMORY)throw new IOException("Instrument exceeds the 128 MB sample-memory limit");ids.put(r.pcm,data.size());data.add(r.pcm);}
  File temporary=File.createTempFile("instrument-",".tmp",directory);boolean complete=false;
  try{
   MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IOException(e);}
   try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(new FileOutputStream(temporary),digest),65536))){
    out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(pack.name==null?"Instrument":pack.name);out.writeUTF(pack.source==null?"":pack.source);out.writeInt(data.size());
    for(float[] pcm:data){out.writeInt(pcm.length/2);for(float sample:pcm)out.writeFloat(Float.isFinite(sample)?sample:0);}
    out.writeInt(pack.regions.length);
    for(SampledInstruments.Region r:pack.regions){out.writeInt(r.low);out.writeInt(r.high);out.writeInt(r.lowVelocity);out.writeInt(r.highVelocity);out.writeInt(r.root);out.writeInt(r.loopStart);out.writeInt(r.loopEnd);out.writeBoolean(r.loop);out.writeFloat(r.gain);out.writeInt(r.releaseFrames);out.writeInt(ids.get(r.pcm));}
   }
   StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.US,"%02x",b&255));String name="instrument-"+hash+".acpack";
   Files.move(temporary.toPath(),new File(directory,name).toPath(),StandardCopyOption.REPLACE_EXISTING);complete=true;return name;
  }finally{if(!complete)temporary.delete();}
 }
 public static SampledInstruments.Pack read(File directory,String name)throws IOException{
  if(name==null||!name.endsWith(".acpack")||name.contains("/")||name.contains("\\"))throw new IOException("Invalid embedded instrument path");
  File file=new File(directory,name);if(!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())||file.length()<16||file.length()>MAX_FILE)throw new IOException("Embedded instrument is missing or too large");
  try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(file),65536))){
   if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Unsupported embedded instrument format");String label=in.readUTF(),source=in.readUTF();int count=in.readInt();if(count<1||count>MAX_REGIONS)throw new IOException("Invalid sample count");
   float[][] data=new float[count][];long bytes=0;
   for(int i=0;i<count;i++){int frames=in.readInt();if(frames<1||frames>MAX_MEMORY/8)throw new IOException("Invalid sample length");bytes+=frames*8L;if(bytes>MAX_MEMORY)throw new IOException("Embedded samples exceed 128 MB");data[i]=new float[frames*2];for(int j=0;j<data[i].length;j++){float value=in.readFloat();data[i][j]=Float.isFinite(value)?value:0;}}
   int regions=in.readInt();if(regions<1||regions>MAX_REGIONS)throw new IOException("Invalid region count");SampledInstruments.Region[] maps=new SampledInstruments.Region[regions];
   for(int i=0;i<regions;i++){int low=in.readInt(),high=in.readInt(),lv=in.readInt(),hv=in.readInt(),root=in.readInt(),ls=in.readInt(),le=in.readInt();boolean loop=in.readBoolean();float gain=in.readFloat();int release=in.readInt(),id=in.readInt();
    if(low<0||high>127||high<low||lv<0||hv>127||hv<lv||root<0||root>127||id<0||id>=count||ls<0||le<ls||le>data[id].length/2||!Float.isFinite(gain)||gain<0||gain>16||release<1||release>Core.RATE*10)throw new IOException("Invalid embedded instrument mapping");
    maps[i]=new SampledInstruments.Region(low,high,lv,hv,root,ls,le,loop,gain,data[id],release);
   }
   if(in.read()!=-1)throw new IOException("Unexpected data in embedded instrument");return new SampledInstruments.Pack(label,source,maps);
  }
 }
}

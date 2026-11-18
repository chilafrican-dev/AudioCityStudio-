package ug.audiocity.studio;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;

/** Compact, offline sampled instrument player and safe WAV/SFZ ZIP importer. */
public final class SampledInstruments {
 public static final class Info {
  public final String id,name,filename;
  Info(String id,String name,String filename){this.id=id;this.name=name;this.filename=filename;}
 }
 public static final class Region {
  public final int low,high,lowVelocity,highVelocity,root,loopStart,loopEnd,releaseFrames;
  public final float[] pcm;
  public final float gain;
  public final boolean loop;
  Region(int low,int high,int lv,int hv,int root,int ls,int le,boolean loop,float gain,float[] pcm,int releaseFrames){
   this.low=low;this.high=high;lowVelocity=lv;highVelocity=hv;this.root=root;loopStart=ls;loopEnd=le;this.loop=loop;this.gain=gain;this.pcm=pcm;this.releaseFrames=releaseFrames;
  }
 }
 public static final class Pack {
  public final String name,source;public final Region[] regions;
  Pack(String n,String s,Region[] r){name=n;source=s;regions=r;}
  public Region find(int note,int velocity){Region best=null;int distance=Integer.MAX_VALUE;
   for(Region r:regions){int d=note<r.low?r.low-note:note>r.high?note-r.high:0;
    int v=velocity<r.lowVelocity?r.lowVelocity-velocity:velocity>r.highVelocity?velocity-r.highVelocity:0;
    int score=d*256+v;
    if(score<distance){distance=score;best=r;}
   }return best;
  }
 }
 private static final long MAX_ARCHIVE=512L*1024*1024,MAX_WAV=32L*1024*1024;
 private final File home;
 public SampledInstruments(File privateFiles){home=new File(privateFiles,"instrument_packs");if(!home.exists()&&!home.mkdirs())throw new IllegalStateException("Cannot create sample library directory");}
 public File folder(){return home;}
 private static String safe(String s){return s.replaceAll("[^A-Za-z0-9._ -]","_");}
 public synchronized List<Info> list(){List<Info> out=new ArrayList<>();File[] dirs=home.listFiles();if(dirs==null)return out;Arrays.sort(dirs,Comparator.comparing(File::getName));
  for(File dir:dirs)if(dir.isDirectory()&&!dir.getName().startsWith(".install-")){
   List<File> files=new ArrayList<>();visit(dir,dir,files,0);
   for(File f:files){String ext=f.getName().toLowerCase(Locale.US);if(!ext.endsWith(".sfz")&&!ext.endsWith(".wav"))continue;
    // SFZ packs may include their referenced WAVs; do not show those individual notes as instruments.
    boolean hasSFZ=false;for(File other:files)if(other.getName().toLowerCase(Locale.US).endsWith(".sfz")){hasSFZ=true;break;}
    if(hasSFZ&&ext.endsWith(".wav"))continue;
    String path=dir.getName()+"/"+dir.toURI().relativize(f.toURI()).getPath();
    String name=f.getName().replaceFirst("(?i)\\.(sfz|wav)$","").replace('_',' ');
    if(dir.getName().equals("factory-soundza-afrobeats-drums"))name="Audio City Afrobeats Drums";
    if(dir.getName().equals("factory-freepats-fm-piano2"))name="Audio City FM Electric Piano";
    if(dir.getName().equals("factory-freepats-ydp-grand-compact"))name="Audio City Grand Piano";
    if(dir.getName().equals("factory-freepats-upright-kw-full"))name="Audio City Upright Piano - Full";
    if(dir.getName().equals("factory-freepats-upright-kw-small"))name="Audio City Upright Piano - Lite";
    if(dir.getName().equals("factory-freepats-upright-kw-bright"))name="Audio City Upright Piano - Bright";
    if(dir.getName().equals("factory-freepats-electric-guitar-clean"))name="Audio City Clean Guitar";
    if(dir.getName().equals("factory-freepats-electric-guitar-direct"))name="Audio City Direct Guitar";
    if(dir.getName().equals("factory-freepats-electric-guitar-jazz"))name="Audio City Jazz Guitar";
    out.add(new Info(path,name,path));
   }
  }
  return out;
 }
 private static void visit(File root,File dir,List<File> out,int depth){if(depth>8)return;File[] entries=dir.listFiles();if(entries==null)return;Arrays.sort(entries,Comparator.comparing(File::getName));for(File f:entries){if(f.isDirectory())visit(root,f,out,depth+1);else out.add(f);}}
 /** Decoded instruments are large (tens of MB each). Share one decoded copy per file instead of decoding it again for every clip and preview. */
 private static final Map<String,java.lang.ref.WeakReference<Pack>> CACHE=new HashMap<>();
 private static Pack recent;
 public Pack load(Info info)throws IOException{
  File file=new File(home,info.filename);String key=file.getCanonicalPath()+"|"+file.length()+"|"+file.lastModified();
  synchronized(CACHE){java.lang.ref.WeakReference<Pack> ref=CACHE.get(key);Pack hit=ref==null?null:ref.get();if(hit!=null){recent=hit;return hit;}}
  Pack pack=decode(info);
  synchronized(CACHE){java.lang.ref.WeakReference<Pack> ref=CACHE.get(key);Pack hit=ref==null?null:ref.get();if(hit!=null){recent=hit;return hit;}CACHE.values().removeIf(r->r.get()==null);CACHE.put(key,new java.lang.ref.WeakReference<>(pack));recent=pack;}
  return pack;
 }
 private synchronized Pack decode(Info info)throws IOException{
  File file=new File(home,info.filename);String homePath=home.getCanonicalPath()+File.separator;
  if(!file.getCanonicalPath().startsWith(homePath)||!file.isFile())throw new IOException("Instrument sample is missing");
  if(info.filename.toLowerCase(Locale.US).endsWith(".wav"))return monoPack(info.name,readWav(file),60,"Imported WAV");
  Pack pack=readSFZ(file,info.name);
  if(info.filename.startsWith("factory-freepats-fm-piano2/"))return new Pack(pack.name,"FreePats / Roberto · CC0 1.0",pack.regions);
  if(info.filename.startsWith("factory-freepats-ydp-grand-compact/"))return new Pack(pack.name,"FreePats / Roberto · Zenph Studios / OLPC · CC BY 3.0",pack.regions);
  if(info.filename.startsWith("factory-freepats-upright-kw-"))return new Pack(pack.name,"FreePats / Gonzalo & Roberto · CC0 1.0",pack.regions);
  if(info.filename.startsWith("factory-freepats-electric-guitar-"))return new Pack(pack.name,"FreePats / original electric guitar contributors · CC0 1.0 (see included license)",pack.regions);
  return pack;
 }
 public static Pack monoPack(String name,float[] stereo,int root,String source){return new Pack(name,source,new Region[]{new Region(0,127,0,127,root,0,0,false,.80f,stereo,(int)(Core.RATE*.22))});}
 private static boolean permitted(String name){return name!=null&&!name.isEmpty()&&!name.startsWith("/")&&!name.startsWith("\\")&&!name.contains("..")&&!name.contains(":")&&!name.contains("\\");}
 /** Install an APK-bundled, licensed instrument exactly once; do not overwrite user packs. */
 public synchronized int installBundledZip(InputStream input,String id)throws IOException{
  String cleaned=safe(id).toLowerCase(Locale.US);
  if(cleaned.isEmpty())throw new IOException("Factory instrument ID is empty");
  File dest=new File(home,"factory-"+cleaned);
  File marker=new File(dest,".factory-complete");
  if(marker.isFile()&&findFirst(dest,".sfz")!=null){
   List<File> existingFiles=new ArrayList<>();visit(dest,dest,existingFiles,0);int wavCount=0;
   for(File f:existingFiles)if(f.isFile()&&f.getName().toLowerCase(Locale.US).endsWith(".wav"))wavCount++;
   if(wavCount>=12)return list().size();
  }
  // An interrupted first install is retried, but unrelated user packs are never touched.
  if(dest.exists())deleteTree(dest);
  return installArchive(input,dest,true);
 }
 public synchronized int installZip(InputStream input,String name)throws IOException{
  File dest=new File(home,"pack-"+System.currentTimeMillis()+"-"+safe(name).substring(0,Math.min(30,safe(name).length())));
  return installArchive(input,dest,false);
 }
 private int installArchive(InputStream input,File dest,boolean bundled)throws IOException{
  File staging=new File(home,".install-"+System.nanoTime());if(!staging.mkdirs())throw new IOException("Cannot prepare instrument pack");
  long total=0;int count=0;boolean valid=false;
  try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(input))){ZipEntry e;byte[] buffer=new byte[32768];while((e=zip.getNextEntry())!=null){
   if(++count>3000)throw new IOException("Too many files in this pack");String p=e.getName().replace('\\','/');
   if(!permitted(p)||p.startsWith("/")||p.contains("//"))throw new IOException("Unsafe ZIP entry: "+p);
   String ext=p.toLowerCase(Locale.US);if(e.isDirectory()){zip.closeEntry();continue;}
   // Only data needed by SFZ and optional attribution/readme files.
   if(!(ext.endsWith(".wav")||ext.endsWith(".sfz")||ext.endsWith(".txt")||ext.endsWith(".md"))){zip.closeEntry();continue;}
   if(ext.endsWith(".sfz")||ext.endsWith(".wav"))valid=true;
   File file=new File(staging,p);if(!file.getCanonicalPath().startsWith(staging.getCanonicalPath()+File.separator))throw new IOException("Unsafe ZIP path");
   if(!file.getParentFile().exists()&&!file.getParentFile().mkdirs())throw new IOException("Cannot create sample folder");
   try(OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){int n;long item=0;while((n=zip.read(buffer))!=-1){item+=n;total+=n;
      if(total>MAX_ARCHIVE||item>MAX_WAV&&ext.endsWith(".wav"))throw new IOException("Sample pack exceeds safe size limits (512 MB total, 32 MB per WAV)");out.write(buffer,0,n);}}
   zip.closeEntry();
  }}catch(IOException e){deleteTree(staging);throw e;}
  if(!valid){deleteTree(staging);throw new IOException("ZIP has no WAV or SFZ instrument");}
  // First instrument must be loadable. Prevent packs that only create broken menus.
  File first=findFirst(staging,".sfz");if(first==null)first=findFirst(staging,".wav");
  try{if(first==null)throw new IOException("No instrument files found");
   if(bundled&&first.getName().toLowerCase(Locale.US).endsWith(".sfz"))validateBundledSfz(first);
   else if(first.getName().toLowerCase(Locale.US).endsWith(".sfz"))readSFZ(first,first.getName());else readWav(first);
  }catch(IOException e){deleteTree(staging);throw e;}
  if(bundled){try(OutputStream marker=new FileOutputStream(new File(staging,".factory-complete"))){marker.write("Audio City factory sample pack v1".getBytes("UTF-8"));}}
  if(!staging.renameTo(dest)){deleteTree(staging);throw new IOException("Could not finish installing sample pack");}
  return list().size();
 }
 /** Validate factory pack without unpacking the entire piano into Java heap. */
 static void validateBundledSfz(File sfz)throws IOException{
  String doc=new String(readLimited(sfz,4*1024*1024),"UTF-8");
  java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)\\bsample\\s*=\\s*([^\\s]+)").matcher(doc);
  int count=0;File first=null;String base=sfz.getParentFile().getCanonicalPath()+File.separator;
  while(m.find()){
   String sample=m.group(1).replace('\\','/');
   if(!permitted(sample))throw new IOException("Unsafe bundled sample path");
   File f=new File(sfz.getParentFile(),sample);
   if(!f.getCanonicalPath().startsWith(base)||!f.isFile()||!f.getName().toLowerCase(Locale.US).endsWith(".wav"))throw new IOException("Missing bundled piano sample: "+sample);
   if(first==null)first=f;count++;
  }
  if(count==0||first==null)throw new IOException("Factory SFZ contains no playable WAV regions");
  readWav(first);
 }
 public synchronized int installWav(InputStream in,String filename)throws IOException{
  String name=safe(filename);if(!name.toLowerCase(Locale.US).endsWith(".wav"))name+=".wav";
  File target=new File(home,"pack-"+System.currentTimeMillis()+"-"+Math.abs(name.hashCode()));
  if(!target.mkdirs())throw new IOException("Cannot create instrument folder");File sample=new File(target,name);long bytes=0;
  try(OutputStream out=new FileOutputStream(sample)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){bytes+=n;if(bytes>MAX_WAV)throw new IOException("WAV sample exceeds 32 MB");out.write(b,0,n);}}
  catch(IOException e){deleteTree(target);throw e;}
  try{readWav(sample);}catch(IOException e){deleteTree(target);throw e;}return list().size();
 }
 static File findFirst(File dir,String suffix){File[] files=dir.listFiles();if(files==null)return null;Arrays.sort(files,Comparator.comparing(File::getName));for(File f:files){if(f.isDirectory()){File match=findFirst(f,suffix);if(match!=null)return match;}else if(f.getName().toLowerCase(Locale.US).endsWith(suffix))return f;}return null;}
 private static void deleteTree(File file){if(file.isDirectory()){File[] files=file.listFiles();if(files!=null)for(File child:files)deleteTree(child);}file.delete();}
 static int integer(Map<String,String> m,String key,int fallback){try{return Integer.parseInt(m.get(key));}catch(Exception ignored){return fallback;}}
 static int midi(String s,int fallback){if(s==null)return fallback;try{return Integer.parseInt(s);}catch(Exception ignored){}
  String p=s.trim().toUpperCase(Locale.US);if(p.length()<2)return fallback;String[] names={"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
  for(int i=11;i>=0;i--)if(p.startsWith(names[i]))try{return (Integer.parseInt(p.substring(names[i].length()))+1)*12+i;}catch(Exception ignored){}return fallback;
 }
 static float gain(Map<String,String> m){try{double db=Double.parseDouble(m.get("volume"));if(!Double.isFinite(db))return 1f;return (float)Math.pow(10,Math.max(-96,Math.min(24,db))/20);}catch(Exception ignored){return 1f;}}
 static Pack readSFZ(File sfz,String name)throws IOException{
  byte[] bytes=readLimited(sfz,4*1024*1024);String sfzText=new String(bytes,"UTF-8");
  // SFZ 1 subset: <control> default_path, <global>, <group>, <region>, key/lokey/hikey,
  // pitch_keycenter, lovel/hivel, volume, loop_mode, loop_start/end.
  sfzText=sfzText.replaceAll("(?m)//[^\\r\\n]*", "");
  java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("<(control|global|group|region)>|([^\\s=<>]+)=([^\\s<>]+)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sfzText);
  Map<String,String> globals=new HashMap<>(),group=new HashMap<>(),region=null;String section="";String defaultPath="";
  List<Map<String,String>> definitions=new ArrayList<>();
  while(matcher.find()){
   if(matcher.group(1)!=null){section=matcher.group(1).toLowerCase(Locale.US);
    if(section.equals("group"))group=new HashMap<>(globals);
    else if(section.equals("region")){region=new HashMap<>(globals);region.putAll(group);definitions.add(region);}continue;
   }
   String key=matcher.group(2).toLowerCase(Locale.US),value=matcher.group(3);
   if(section.equals("control")&&key.equals("default_path"))defaultPath=value;
   else if(section.equals("global"))globals.put(key,value);
   else if(section.equals("group"))group.put(key,value);
   else if(section.equals("region")&&region!=null)region.put(key,value);
  }
  if(definitions.isEmpty())throw new IOException("SFZ has no playable regions");
  Map<String,float[]> cache=new HashMap<>();List<Region> regions=new ArrayList<>();long memory=0;
  for(Map<String,String> d:definitions){String sample=d.get("sample");if(sample==null||!permitted(sample))continue;
   String relative=(defaultPath+sample).replace('\\','/');if(!permitted(relative))continue;
   File pcmFile=new File(sfz.getParentFile(),relative);
   if(!pcmFile.getCanonicalPath().startsWith(sfz.getParentFile().getCanonicalPath()+File.separator)||!pcmFile.isFile())continue;
   String id=pcmFile.getCanonicalPath();float[] pcm=cache.get(id);if(pcm==null){pcm=readWav(pcmFile);memory+=(long)pcm.length*4;if(memory>128L*1024*1024)throw new IOException("This instrument needs more than 128 MB of sample memory; use a smaller SFZ pack");cache.put(id,pcm);}
   int key=midi(d.get("key"),-1),root=midi(d.get("pitch_keycenter"),key>=0?key:60);
   int low=key>=0?key:midi(d.get("lokey"),root),high=key>=0?key:midi(d.get("hikey"),root);
   // Some libraries map just root keys without explicit ranges. Nearest sample will cover gaps.
   int lv=integer(d,"lovel",0),hv=integer(d,"hivel",127);
   int ls=Math.max(0,integer(d,"loop_start",0)),le=Math.max(0,integer(d,"loop_end",0));
   int sourceRate=wavSampleRate(pcmFile);
   if(sourceRate>0&&sourceRate!=Core.RATE){ls=(int)Math.round(ls*(double)Core.RATE/sourceRate);le=(int)Math.round(le*(double)Core.RATE/sourceRate);}
   ls=Math.min(pcm.length/2,ls);le=Math.min(pcm.length/2,le);
   String lm=d.get("loop_mode");boolean loop=("loop_continuous".equalsIgnoreCase(lm)||"loop_sustain".equalsIgnoreCase(lm))&&le>ls;
   double release=.22;try{release=Double.parseDouble(d.get("ampeg_release"));}catch(Exception ignored){}
   if(!Double.isFinite(release))release=.22;
   int releaseFrames=(int)Math.round(Core.RATE*Math.max(.02,Math.min(10,release)));
   regions.add(new Region(Math.max(0,low),Math.min(127,high),Math.max(0,lv),Math.min(127,hv),Math.max(0,Math.min(127,root)),ls,le,loop,gain(d),pcm,releaseFrames));
  }
  if(regions.isEmpty())throw new IOException("No supported WAV sample regions in SFZ");
  return new Pack(name,"Imported SFZ: "+sfz.getName(),regions.toArray(new Region[0]));
 }
 static byte[] readLimited(File f,int max)throws IOException{if(f.length()>max)throw new IOException("File too large");try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("File too large");out.write(b,0,n);}return out.toByteArray();}}
 /** Decode mono/stereo 16/24-bit integer WAV into Core.RATE stereo floats, with rate conversion. */
 static int wavSampleRate(File file)throws IOException{
  try(InputStream in=new BufferedInputStream(new FileInputStream(file))){
   byte[] header=readN(in,12);if(!new String(header,0,4,"US-ASCII").equals("RIFF")||!new String(header,8,4,"US-ASCII").equals("WAVE"))throw new IOException("Invalid WAV header");
   for(int i=0;i<120;i++){
    byte[] h=readN(in,8);int size=SampleAudio.le(h,4);if(size<0||size>MAX_WAV)throw new IOException("Unsupported WAV chunk");
    if(new String(h,0,4,"US-ASCII").equals("fmt ")){if(size<16)throw new IOException("Invalid WAV format");byte[] fmt=readN(in,16);int rate=SampleAudio.le(fmt,4);if(rate<8000||rate>192000)throw new IOException("Invalid sample rate");return rate;}
    long remaining=size+(size&1);while(remaining>0){long n=in.skip(remaining);if(n<=0){if(in.read()<0)throw new EOFException();n=1;}remaining-=n;}
   }
   throw new IOException("WAV format chunk is missing");
  }
 }
 static float[] readWav(File file)throws IOException{
  if(file.length()>MAX_WAV)throw new IOException("Sample is larger than 32 MB");
  try(InputStream raw=new BufferedInputStream(new FileInputStream(file))){
   byte[] header=readN(raw,12);if(!new String(header,0,4,"US-ASCII").equals("RIFF")||!new String(header,8,4,"US-ASCII").equals("WAVE"))throw new IOException("Only RIFF WAV samples are supported: "+file.getName());
   int ch=0,rate=0,bits=0,format=0;float[] decoded=null;
   for(int i=0;i<120;i++){byte[] h=new byte[8];if(raw.read(h,0,8)!=8)break;int size=SampleAudio.le(h,4);if(size<0||size>MAX_WAV)throw new IOException("Unsupported WAV chunk");String id=new String(h,0,4,"US-ASCII");
    if(id.equals("fmt ")){byte[] f=readN(raw,size);if(size<16)throw new IOException("Invalid WAV format");ByteBuffer b=ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);format=b.getShort(0)&65535;ch=b.getShort(2);rate=b.getInt(4);bits=b.getShort(14);
     // PCM data stored in WAVE_FORMAT_EXTENSIBLE (0xFFFE), common for 24-bit sampled pianos.
     if(format==65534&&size>=40){int subtype=b.getInt(24);if(subtype==1||subtype==3)format=subtype;}
     if((format!=1&&format!=3)||(ch!=1&&ch!=2)||(bits!=16&&bits!=24&&bits!=32)||rate<8000||rate>192000)throw new IOException("WAV must be mono/stereo 16/24-bit PCM or 32-bit float/integer");}
    else if(id.equals("data")){if(ch==0||rate==0)throw new IOException("WAV format must come before sample data");byte[] pcm=readN(raw,size);int step=bits/8,frames=size/(ch*step);decoded=new float[frames*2];for(int n=0;n<frames;n++){
      float left=wavValue(pcm,n*ch*step,bits,format),right=ch==1?left:wavValue(pcm,(n*ch+1)*step,bits,format);
      decoded[2*n]=left;decoded[2*n+1]=right;
     }break;
    }else{long left=size;while(left>0){long n=raw.skip(left);if(n<=0){if(raw.read()<0)throw new EOFException();n=1;}left-=n;}}
    if((size&1)!=0&&raw.read()<0)throw new EOFException();
   }
   if(decoded==null||decoded.length==0)throw new IOException("WAV contains no audio");
   if(rate==Core.RATE)return decoded;int src=decoded.length/2,dst=(int)Math.max(1,Math.round(src*(double)Core.RATE/rate));float[] out=new float[dst*2];
   for(int i=0;i<dst;i++){double pos=((double)i*rate)/Core.RATE;int j=Math.min(src-1,(int)pos),k=Math.min(src-1,j+1);float t=(float)(pos-j);out[2*i]=decoded[2*j]+(decoded[2*k]-decoded[2*j])*t;out[2*i+1]=decoded[2*j+1]+(decoded[2*k+1]-decoded[2*j+1])*t;}return out;
  }
 }
 static float wavValue(byte[] b,int at,int bits,int format){if(bits==16)return (short)((b[at]&255)|(b[at+1]<<8))/32768f;
  if(bits==24){int x=(b[at]&255)|((b[at+1]&255)<<8)|(b[at+2]<<16);return x/8388608f;}
  int x=(b[at]&255)|((b[at+1]&255)<<8)|((b[at+2]&255)<<16)|(b[at+3]<<24);
  float value=format==3?Float.intBitsToFloat(x):x/2147483648f;return Float.isFinite(value)?value:0;
 }
 static byte[] readN(InputStream in,int length)throws IOException{byte[] b=new byte[length];int i=0;while(i<length){int n=in.read(b,i,length-i);if(n<0)throw new EOFException();i+=n;}return b;}
}

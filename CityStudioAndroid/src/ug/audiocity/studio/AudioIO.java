package ug.audiocity.studio;
import android.content.*;import android.media.*;import android.net.Uri;import android.os.*;import org.json.*;import java.io.*;import java.nio.*;import java.nio.channels.*;import java.util.*;
public final class AudioIO {
 public static final class Disk implements Core.Source {final FloatBuffer samples;final int count;public Disk(File f)throws IOException {try(RandomAccessFile r=new RandomAccessFile(f,"r")){long bytes=r.length();if(bytes<8||bytes%8!=0||bytes>Core.MAX_FRAMES*8)throw new IOException("Unsupported clip size");samples=r.getChannel().map(FileChannel.MapMode.READ_ONLY,0,bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();count=(int)(bytes/8);}}public int frames(){return count;}public float sample(int frame,int ch){return samples.get(frame*2+ch);}}
 static final class Writer implements Closeable {final OutputStream out;final ByteBuffer bytes=ByteBuffer.allocate(32768).order(ByteOrder.LITTLE_ENDIAN);long frames;Writer(File f)throws IOException{out=new BufferedOutputStream(new FileOutputStream(f));}void sample(float l,float r)throws IOException{if(frames>=Core.MAX_FRAMES)throw new IOException("A clip can be at most 60 minutes in this first build.");if(bytes.remaining()<8)flush();bytes.putFloat(Float.isFinite(l)?l:0);bytes.putFloat(Float.isFinite(r)?r:0);frames++;}void flush()throws IOException{out.write(bytes.array(),0,bytes.position());bytes.clear();}public void close()throws IOException{flush();out.close();}}
 public static Core.Clip store(File dir,float[] data,String name,long at)throws IOException{File f=new File(dir,UUID.randomUUID()+".pcm");try(Writer w=new Writer(f)){for(int i=0;i<data.length;i+=2)w.sample(data[i],data[i+1]);}return new Core.Clip(name,new Disk(f),f.getName(),at);}
 public static Core.Clip decode(Context context,Uri uri,File dir,String name,long at)throws Exception {File target=new File(dir,UUID.randomUUID()+".pcm");MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;boolean success=false;try{ex.setDataSource(context,uri,null);int selected=-1;MediaFormat input=null;for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){selected=i;input=f;break;}}if(selected<0)throw new IOException("No supported audio stream found.");if(input.containsKey(MediaFormat.KEY_DURATION)&&input.getLong(MediaFormat.KEY_DURATION)>3600000000L)throw new IOException("Import clips up to 60 minutes long.");ex.selectTrack(selected);codec=MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));codec.configure(input,null,null,0);codec.start();int rate=input.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=input.getInteger(MediaFormat.KEY_CHANNEL_COUNT),encoding=AudioFormat.ENCODING_PCM_16BIT;boolean inDone=false,outDone=false;long lastProgress=SystemClock.elapsedRealtime();double next=0,increment=rate/(double)Core.RATE;long index=0;float prevL=0,prevR=0;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();try(Writer writer=new Writer(target)){while(!outDone){if(SystemClock.elapsedRealtime()-lastProgress>30000)throw new IOException("Audio decoder stopped responding.");if(!inDone){int id=codec.dequeueInputBuffer(10000);if(id>=0){ByteBuffer b=codec.getInputBuffer(id);b.clear();int size=ex.readSampleData(b,0);if(size<0){codec.queueInputBuffer(id,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inDone=true;}else{codec.queueInputBuffer(id,0,size,ex.getSampleTime(),0);ex.advance();lastProgress=SystemClock.elapsedRealtime();}}}int id=codec.dequeueOutputBuffer(info,10000);if(id==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();int newRate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);if(index>0&&newRate!=rate)throw new IOException("Changing sample rates are not supported.");rate=newRate;increment=rate/(double)Core.RATE;channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;if(channels<1||channels>2)throw new IOException("Import mono or stereo audio.");if(encoding!=AudioFormat.ENCODING_PCM_FLOAT&&encoding!=AudioFormat.ENCODING_PCM_16BIT)throw new IOException("This decoder PCM format is not supported.");}else if(id>=0){ByteBuffer data=codec.getOutputBuffer(id);if(info.size>0&&data!=null){data.position(info.offset);data.limit(info.offset+info.size);data.order(ByteOrder.LITTLE_ENDIAN);int width=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;while(data.remaining()>=width*channels){float l=width==4?data.getFloat():data.getShort()/32768f;float r=channels==1?l:width==4?data.getFloat():data.getShort()/32768f;if(index==0){prevL=l;prevR=r;}while(next<=index){double a=index==0?0:next-(index-1);a=Math.max(0,Math.min(1,a));writer.sample((float)(prevL+(l-prevL)*a),(float)(prevR+(r-prevR)*a));next+=increment;}prevL=l;prevR=r;index++;}lastProgress=SystemClock.elapsedRealtime();}outDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(id,false);}}}if(target.length()==0)throw new IOException("The audio file is empty.");Core.Clip c=new Core.Clip(name,new Disk(target),target.getName(),at);success=true;return c;}finally{try{ex.release();}catch(Exception ignored){}if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}if(!success)target.delete();}}
 static JSONObject fx(Core.Fx f)throws JSONException{return ProjectIO.fx(f);}
 static Core.Fx readFx(JSONObject j){return ProjectIO.readFx(j);}
 static double clamp(double n,double a,double b){return Double.isFinite(n)?Math.max(a,Math.min(b,n)):a;}
 public static void save(Core.Session s)throws Exception {ProjectIO.save(s);}
 public static Core.Session load(File dir)throws Exception {return ProjectIO.load(dir,Disk::new);}
 public static Core.Session retimeProject(Core.Session original,int bpm,boolean preservePitch)throws Exception {if(bpm<40||bpm>240)throw new IOException("Use a tempo between 40 and 240 BPM.");double speed=bpm/(double)original.bpm;if(original.loopEnd/speed>Core.MAX_FRAMES)throw new IOException("The new tempo would move the loop range beyond the 60-minute limit.");if(preservePitch&&(speed<.5||speed>2))throw new IOException("For pitch-preserving stretching, change tempo by 0.5×–2× per step. Use an intermediate tempo or choose resampling.");Core.Session result=original.copy();java.util.LinkedHashMap<String,Core.Source> sources=new java.util.LinkedHashMap<>();for(Core.Track t:result.tracks)for(Core.Clip c:allClips(t)){if((c.start+c.length)/speed>Core.MAX_FRAMES||c.source.frames()/speed>Core.MAX_FRAMES)throw new IOException("The new tempo would exceed the 60-minute timeline or source-file limit.");if(c.notes!=null&&c.drumPattern==null)continue;sources.put(c.file,c.source);if(c.tuneSourceFile!=null&&!sources.containsKey(c.tuneSourceFile)){Disk dry=drySource(c,new File(original.directory));if(dry.frames()/speed>Core.MAX_FRAMES)throw new IOException("The retimed original vocal would exceed 60 minutes.");sources.put(c.tuneSourceFile,dry);}}java.util.HashMap<String,String> names=new java.util.HashMap<>();java.util.HashMap<String,Disk> rendered=new java.util.HashMap<>();java.util.ArrayList<File> created=new java.util.ArrayList<>();boolean success=false;try{for(java.util.Map.Entry<String,Core.Source> source:sources.entrySet()){File file=new File(original.directory,java.util.UUID.randomUUID()+".pcm");created.add(file);try(Writer writer=new Writer(file)){TimeStretch.Sink sink=(l,r)->writer.sample(l,r);if(preservePitch)TimeStretch.preservePitch(source.getValue(),speed,sink);else TimeStretch.resample(source.getValue(),speed,sink);}names.put(source.getKey(),file.getName());rendered.put(source.getKey(),new Disk(file));}for(Core.Track t:result.tracks)for(Core.Clip c:allClips(t)){if(c.notes!=null&&c.drumPattern==null){ProMidi.retime(c,speed);rewriteMidi(c,result,bpm,created);continue;}c.stretchFile=null;String oldFile=c.file,oldDryFile=c.tuneSourceFile;int oldOffset=c.offset,oldDryOffset=c.tuneSourceOffset;int offset=(int)Math.round(c.offset/speed),length=(int)Math.round((c.offset+(long)c.length)/speed)-offset;c.start=Math.round(c.start/speed);c.envelopeOffset=(int)Math.round(c.envelopeOffset/speed);c.envelopeLength=Math.max(1,(int)Math.round(c.envelopeLength/speed));c.envelopeOffset=Math.min(c.envelopeOffset,c.envelopeLength-1);c.fadeIn=(int)Math.round(c.fadeIn/speed);c.fadeOut=(int)Math.round(c.fadeOut/speed);c.source=rendered.get(oldFile);c.file=names.get(oldFile);c.offset=Math.min(offset,c.source.frames()-1);if(oldDryFile!=null){c.tuneSourceFile=names.get(oldDryFile);c.tuneSourceOffset=Math.max(0,(int)Math.round((oldDryOffset+(long)oldOffset)/speed)-c.offset);}c.length=Math.max(1,Math.min(length,Math.min(c.source.frames()-c.offset,c.envelopeLength-c.envelopeOffset)));if(oldDryFile!=null){int available=rendered.get(oldDryFile).frames()-c.tuneSourceOffset-c.offset;if(available<1)throw new IOException("Retimed original vocal boundaries do not match.");c.length=Math.min(c.length,available);}c.peaks=Core.peaks(c.source);if(c.drumPattern!=null){c.drumPattern=BeatPilotDrumRollData.retempo(c.drumPattern,bpm);c.notes=BeatPilotDrumRollData.decode(c.drumPattern).notes();rewriteMidi(c,result,bpm,created);}}ProjectIO.retimeExtras(result,speed);result.bpm=bpm;if(!preservePitch&&bpm!=original.bpm){result.tuneKey=0;result.tuneScale=0;result.tuneConfigured=false;}success=true;return result;}finally{if(!success)for(File f:created)f.delete();}}
 static void rewriteMidi(Core.Clip c,Core.Session project,int bpm,List<File> created)throws IOException{
  if(c.midiFile==null||c.notes==null)return;
  File file=new File(project.directory,UUID.randomUUID()+".mid");created.add(file);
  MidiTake take=Pro.take(c.instrument==null?Synth.factory(0):c.instrument,bpm,c.notes,null);
  try(OutputStream out=new FileOutputStream(file)){take.writeMidi(out);}c.midiFile=file.getName();
 }
 static java.util.List<Core.Clip> allClips(Core.Track t){java.util.ArrayList<Core.Clip> all=new java.util.ArrayList<>(t.clips);all.addAll(t.takes);for(Core.Clip c:t.slots)if(c!=null)all.add(c);return all;}
 public static Disk drySource(Core.Clip clip,File directory)throws IOException{if(clip.tuneSourceFile==null)return null;File path=new File(directory,clip.tuneSourceFile);if(!path.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))throw new IOException("Invalid original vocal path.");return new Disk(path);}
 public static void restoreUntunedClip(Core.Clip clip,File directory)throws IOException{Disk dry=drySource(clip,directory);if(dry==null)throw new IOException("This older clip has no preserved dry source. Re-import the original recording.");long offset=clip.tuneSourceOffset+(long)clip.offset;if(offset<0||offset+clip.length>dry.frames())throw new IOException("Original vocal boundaries no longer match.");clip.source=dry;clip.file=clip.tuneSourceFile;clip.offset=(int)offset;clip.tuneSourceFile=null;clip.tuneSourceOffset=0;clip.vocalNotes=null;clip.peaks=Core.peaks(dry);}
 public static PitchCorrector.Report correctClip(Core.Clip clip,File directory,PitchCorrector.Config config)throws Exception {File file=new File(directory,java.util.UUID.randomUUID()+".pcm");boolean success=false;try{Disk dry=drySource(clip,directory);final Core.Source original=dry==null?clip.source:dry;final int offset=(dry==null?0:clip.tuneSourceOffset)+clip.offset,length=clip.length;final String originalFile=dry==null?clip.file:clip.tuneSourceFile;if(offset<0||offset+(long)length>original.frames())throw new IOException("Original vocal boundaries no longer match.");Core.Source region=new Core.Source(){public int frames(){return length;}public float sample(int f,int ch){return original.sample(offset+f,ch);}};PitchCorrector.Report report;try(Writer writer=new Writer(file)){report=PitchCorrector.correct(region,config,(l,r)->writer.sample(l,r));}Disk source=new Disk(file);clip.source=source;clip.file=file.getName();clip.offset=0;clip.length=source.frames();clip.tuneSourceFile=originalFile;clip.tuneSourceOffset=offset;clip.vocalNotes=null;clip.peaks=Core.peaks(source);success=true;return report;}finally{if(!success)file.delete();}}
    /** The untouched recording under a clip (the preserved dry vocal when the clip was tuned or edited before). */
    public static final class VocalRegion { public final Core.Source region; public final int offset, length; public final String originalFile; VocalRegion(Core.Source r, int o, int l, String f) { region = r; offset = o; length = l; originalFile = f; } }
    public static VocalRegion vocalRegion(Core.Clip clip, File directory) throws IOException {
        Disk dry = drySource(clip, directory); final Core.Source original = dry == null ? clip.source : dry; final int offset = (dry == null ? 0 : clip.tuneSourceOffset) + clip.offset, length = clip.length;
        if (offset < 0 || offset + (long) length > original.frames()) throw new IOException("Original vocal boundaries no longer match.");
        return new VocalRegion(new Core.Source() { public int frames() { return length; } public float sample(int f, int ch) { return original.sample(offset + f, ch); } }, offset, length, dry == null ? clip.file : clip.tuneSourceFile);
    }
    /** Renders the vocal editor's note edits from the dry recording into a new file; the dry file is kept so edits stay re-editable and undoable. */
    public static void vocalEditClip(Core.Clip clip, File directory, VocalRegion r, ProVocal.Analysis analysis, java.util.List<ProVocal.Note> notes) throws Exception {
        File file = new File(directory, java.util.UUID.randomUUID() + ".pcm"); boolean success = false;
        try {
            try (Writer writer = new Writer(file)) { ProVocal.render(r.region, analysis, notes, (l, rr) -> writer.sample(l, rr)); }
            Disk source = new Disk(file); clip.source = source; clip.file = file.getName(); clip.offset = 0; clip.length = source.frames(); clip.tuneSourceFile = r.originalFile; clip.tuneSourceOffset = r.offset;
            clip.vocalNotes = new java.util.ArrayList<>(); for (ProVocal.Note n : notes) clip.vocalNotes.add(n.copy()); clip.vocalOffset = r.offset; clip.vocalLength = r.length; clip.peaks = Core.peaks(source); success = true;
        } finally { if (!success) file.delete(); }
    }

 public static void backup(OutputStream destination,Core.Session session)throws Exception {
  InstrumentArchive.embed(session);save(session);java.util.LinkedHashSet<String> names=new java.util.LinkedHashSet<>();names.add("session.json");names.addAll(ProjectIO.files(session));
  if(names.size()>2000)throw new IOException("Project backup has too many files (maximum 2000).");long total=0;File directory=new File(session.directory).getCanonicalFile();
  for(String name:names){File file=new File(directory,name).getCanonicalFile();if(!directory.equals(file.getParentFile())||!file.isFile())throw new IOException("Missing or invalid project source: "+name);total+=file.length();if(total>1073741824L)throw new IOException("Project backup exceeds the supported 1 GB unpacked limit. Split the project before backing it up.");if(name.equals("session.json")&&file.length()>1048576)throw new IOException("Project metadata exceeds the supported backup limit.");}
  java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(destination);byte[] block=new byte[65536];for(String name:names){File file=new File(session.directory,name);zip.putNextEntry(new java.util.zip.ZipEntry(name));try(InputStream in=new FileInputStream(file)){int n;while((n=in.read(block))!=-1)zip.write(block,0,n);}zip.closeEntry();}zip.finish();zip.flush();
 }
 public static Core.Session restore(InputStream source,File directory)throws Exception {
  File[] existing=directory.listFiles();if(existing==null||existing.length!=0)throw new IOException("Restore requires a new empty project folder.");boolean restored=false;java.util.HashSet<String> names=new java.util.HashSet<>();try{
  java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(source);java.util.zip.ZipEntry entry;byte[] buffer=new byte[65536];long total=0;int count=0;while((entry=zip.getNextEntry())!=null){String name=entry.getName();if(entry.isDirectory()||name.contains("/")||name.contains("\\")||(!name.equals("session.json")&&!name.endsWith(".pcm")&&!name.endsWith(".mid")&&!name.endsWith(".acpack"))||!names.add(name)||++count>2000)throw new IOException("Invalid project archive.");File file=new File(directory,name);if(!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))throw new IOException("Invalid archive path.");long fileBytes=0;try(OutputStream out=new BufferedOutputStream(new FileOutputStream(file))){int n;while((n=zip.read(buffer))!=-1){fileBytes+=n;total+=n;if(fileBytes>(name.equals("session.json")?1048576:Core.MAX_FRAMES*8)||total>1073741824L)throw new IOException("Project backup exceeds supported size.");out.write(buffer,0,n);}}zip.closeEntry();}if(!names.contains("session.json"))throw new IOException("This is not an Audio City Studio project backup.");Core.Session result=load(directory);restored=true;return result;}finally{if(!restored){for(String name:names)new File(directory,name).delete();directory.delete();}}
 }
 public static long tailFrames(Core.Session s){return Export.tailFrames(s);}
 public static void wav(OutputStream target,Core.Session s)throws Exception {wav(target,s,16);}
 public static void wav(OutputStream target,Core.Session s,int bits)throws Exception {Export.wav(target,s,bits);}
 /** AAC-LC in an MP4 (.m4a) container, encoded with the phone's MediaCodec encoder. 16-bit TPDF-dithered input. */
 public static void aac(FileDescriptor fd,Core.Session s,int bitrate)throws Exception{long frames=Math.min(Core.MAX_FRAMES,s.end()+tailFrames(s));MediaFormat format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,Core.RATE,2);format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);format.setInteger(MediaFormat.KEY_BIT_RATE,bitrate);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384);MediaCodec codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);MediaMuxer muxer=null;boolean started=false;try{codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();started=true;muxer=new MediaMuxer(fd,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);final MediaMuxer mux=muxer;final int[] track={-1};final long[] queued={0};final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();final Random dither=new Random(902);
  Pro.renderOffline(s,frames,(block,n)->{int done=0;long waited=0;while(done<n){int id=codec.dequeueInputBuffer(10000);if(id<0){drainAac(codec,mux,info,track,false);if(++waited>3000)throw new IOException("The AAC encoder stopped responding.");continue;}waited=0;ByteBuffer in=codec.getInputBuffer(id);in.clear();in.order(ByteOrder.LITTLE_ENDIAN);int can=Math.min(n-done,in.remaining()/4);for(int i=0;i<can;i++){in.putShort((short)Export.quantise(block[2*(done+i)],dither,32768));in.putShort((short)Export.quantise(block[2*(done+i)+1],dither,32768));}codec.queueInputBuffer(id,0,can*4,queued[0]*1000000L/Core.RATE,0);queued[0]+=can;done+=can;drainAac(codec,mux,info,track,false);}});
  int id;long waited=0;while((id=codec.dequeueInputBuffer(10000))<0){drainAac(codec,mux,info,track,false);if(++waited>3000)throw new IOException("The AAC encoder stopped responding.");}codec.queueInputBuffer(id,0,0,queued[0]*1000000L/Core.RATE,MediaCodec.BUFFER_FLAG_END_OF_STREAM);drainAac(codec,mux,info,track,true);if(track[0]<0)throw new IOException("The AAC encoder produced no audio.");mux.stop();}finally{if(started)try{codec.stop();}catch(Exception ignored){}codec.release();if(muxer!=null)try{muxer.release();}catch(Exception ignored){}}}
 static void drainAac(MediaCodec codec,MediaMuxer mux,MediaCodec.BufferInfo info,int[] track,boolean end)throws IOException{int idle=0;while(true){int out=codec.dequeueOutputBuffer(info,end?10000:0);if(out==MediaCodec.INFO_TRY_AGAIN_LATER){if(!end)return;if(++idle>500)throw new IOException("The AAC encoder did not finish.");continue;}if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){if(track[0]>=0)throw new IOException("The AAC encoder changed format twice.");track[0]=mux.addTrack(codec.getOutputFormat());mux.start();continue;}if(out<0)continue;ByteBuffer data=codec.getOutputBuffer(out);if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0)info.size=0;if(info.size>0&&track[0]>=0&&data!=null){data.position(info.offset);data.limit(info.offset+info.size);mux.writeSampleData(track[0],data,info);}codec.releaseOutputBuffer(out,false);if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)return;}}
 /** Audio output stream used by the engine: AAudio (native, exclusive/MMAP when the device allows) or AudioTrack. */
 abstract static class Output{
  abstract void start()throws IOException;abstract int write(float[] data,int offset,int samples)throws IOException;abstract long heard();abstract int burst();abstract int bufferFrames();abstract String describe();abstract void interrupt();abstract void close();
  static Output open(boolean lowLatency,boolean preferNative,int burstHint)throws IOException{
   if(lowLatency&&preferNative&&NativeAudio.AVAILABLE){long h=NativeAudio.open(Core.RATE,2,true,2);if(h==0)h=NativeAudio.open(Core.RATE,2,false,2);if(h!=0)return new NativeOutput(h);}
   return new TrackOutput(lowLatency,burstHint);}
 }
 static final class TrackOutput extends Output{final AudioTrack t;final int burst;
  TrackOutput(boolean lowLatency,int burstHint){int min=AudioTrack.getMinBufferSize(Core.RATE,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT);t=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(Core.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min,4096)).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();burst=Math.max(32,Math.min(512,burstHint));if(lowLatency)try{t.setBufferSizeInFrames(burst*2);}catch(Exception ignored){}}
  void start(){t.play();}int write(float[] d,int o,int n){return t.write(d,o,n,AudioTrack.WRITE_BLOCKING);}long heard(){return t.getPlaybackHeadPosition()&0xffffffffL;}int burst(){return burst;}int bufferFrames(){return t.getBufferSizeInFrames();}String describe(){return "AudioTrack (Java)";}
  void interrupt(){try{t.pause();}catch(Exception ignored){}}void close(){try{t.pause();t.flush();t.release();}catch(Exception ignored){}}}
 static final class NativeOutput extends Output{final long h;volatile boolean closed;NativeOutput(long handle){h=handle;}
  void start()throws IOException{int r=NativeAudio.start(h);if(r<0)throw new IOException("AAudio could not start ("+r+").");}
  int write(float[] d,int o,int n){if(closed)return -1;int frames=NativeAudio.write(h,d,o,n/2,200000000L);return frames<0?frames:frames*2;}
  long heard(){return NativeAudio.framesRead(h);}int burst(){return NativeAudio.framesPerBurst(h);}int bufferFrames(){return NativeAudio.bufferFrames(h);}
  String describe(){return NativeAudio.exclusive(h)?"AAudio · exclusive (MMAP)":"AAudio · shared";}
  void interrupt(){NativeAudio.stop(h);}synchronized void close(){if(closed)return;closed=true;NativeAudio.stop(h);NativeAudio.close(h);}}
 public static final class Engine {
  volatile boolean playing,loop,click,recording;
  /** Low-latency mode uses the device burst size for blocks and a two-burst output buffer. */
  public volatile boolean lowLatency;
  public volatile int burstFrames=192,latencyFrames;
  /** Input monitoring: microphone frames pushed by Capture, optionally through a track's channel FX. */
  public volatile Pro.Ring monitor;
  public volatile double monitorGain=1;
  public volatile Core.Track monitorTrack;
  /** The renderer currently playing, for live loudness metering. */
  public volatile Core.Renderer live;
  volatile long position;
  public volatile long transportFrames;
  public volatile float clickVolume=1f;
  public volatile long countInRemaining,countInTotal;
  public volatile int countInBeats;
  volatile SynthPoly midiMonitor;
  volatile float peak,peakLeft,peakRight,peakHold,reduction;
  volatile boolean overloaded;
  volatile String error;
  /** Prefer the native AAudio stream (libaudiocity.so) when it is built into the APK. */
  public volatile boolean useNative=true;
  /** Playback level; lowered while recording through the phone speaker so less beat reaches the microphone. */
  public volatile float outputGain=1f;
  public volatile String outputPath="";
  private Thread thread;
  private volatile Output device;
  public synchronized void play(Core.Session session){playCounted(session,0);}
  public synchronized void playCounted(Core.Session session,int beats){
   if(playing)return;
   if(thread!=null&&thread.isAlive()){error="Audio output is still stopping. Try Play again.";return;}
   if(loop&&session.loopEnd>session.loopStart){
    if(position<session.loopStart||position>=session.loopEnd)position=session.loopStart;
   }else if(loop&&position>=TakeBounds.loopEnd(session))position=0;
   playing=true;
   long start=position;
   countInBeats=beats;countInTotal=CountIn.frames(session.bpm,beats);countInRemaining=countInTotal;
   transportFrames=start-countInTotal;
   thread=new Thread(()->{
    Core.Renderer renderer=new Core.Renderer(session);live=renderer;
    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
    Output track=null;
    try{
     track=Output.open(lowLatency,useNative,burstFrames);device=track;outputPath=track.describe();
     int block=lowLatency?Math.max(32,Math.min(512,track.burst())):512;
     latencyFrames=track.bufferFrames();track.start();
     float[] data=new float[Math.max(1024,block*2)],midiData=new float[Math.max(1024,block*2)],mon=new float[Math.max(1024,block*2)];
     Core.Processor monitorFx=null;Core.Fx monitorSource=null;
     long cursor=start,heardBase=start-countInTotal;
     long preRoll=countInTotal;
     while(playing){
      long end=recording?Core.MAX_FRAMES:Math.min(Core.MAX_FRAMES,Math.max(Core.beat(session.bpm)*4,session.end()+tailFrames(session)));
      boolean ranged=session.loopEnd>session.loopStart;
      long loopStart=ranged?session.loopStart:0,loopEnd=ranged?session.loopEnd:TakeBounds.loopEnd(session,renderer.tempoMap());
      if(loop&&cursor>=loopEnd){cursor=loopStart;renderer=new Core.Renderer(session);live=renderer;}
      if(!loop&&cursor>=end){playing=false;break;}
      boolean preparing=preRoll>0;
      int n=(int)Math.min(block,preparing?preRoll:(loop?loopEnd:end)-cursor);
      renderer.clickLevel=.12f*Math.max(0f,Math.min(2f,clickVolume));
      if(preparing)CountIn.render(data,n,countInTotal-preRoll,session.bpm,clickVolume);
      else renderer.render(cursor,n,data,click);
      float midiPeakL=0,midiPeakR=0;
      SynthPoly midi=midiMonitor;
      if(midi!=null){
       midi.render(midiData,n,cursor);
       for(int i=0;i<n*2;i++){
        double combined=data[i]+midiData[i]*session.master;
        renderer.overloaded|=Math.abs(combined)>1;
        data[i]=(float)Math.max(-.89125,Math.min(.89125,combined));
        if(i%2==0)midiPeakL=Math.max(midiPeakL,Math.abs(data[i]));
        else midiPeakR=Math.max(midiPeakR,Math.abs(data[i]));
       }
      }
      Pro.Ring ring=monitor;
      if(ring!=null){
       java.util.Arrays.fill(mon,0,n*2,0);ring.mixInto(mon,n,1,Math.max(block*4,1024));
       Core.Track mt=monitorTrack;
       if(mt!=null&&!mt.bypassFx){
        if(monitorFx==null||monitorSource!=mt.fx){monitorFx=new Core.Processor();monitorSource=mt.fx;}
        monitorFx.configure(mt.fx,session.bpm);monitorFx.apply(mon,n);
       }
       double g=monitorGain*session.master;
       for(int i=0;i<n*2;i++){double combined=data[i]+mon[i]*g;data[i]=(float)Math.max(-.89125,Math.min(.89125,combined));}
      }
      float og=outputGain;if(og!=1f)for(int gi=0;gi<n*2;gi++)data[gi]*=og;
      int written=0;
      while(playing&&written<n*2){
       int v=track.write(data,written,n*2-written);
       if(v<=0){if(!playing)break;throw new IOException("Audio output error "+v);}
       written+=v;
      }
      if(preparing){preRoll-=written/2;countInRemaining=preRoll;}else cursor+=written/2;
      transportFrames+=written/2;
      long heard=heardBase+track.heard();
      position=heard<start?start:loop?ProEdit.loopPosition(heard,loopStart,loopEnd):Math.min(heard,cursor);
      peakLeft=midi==null?renderer.peakLeft:midiPeakL;peakRight=midi==null?renderer.peakRight:midiPeakR;
      peak=Math.max(peakLeft,peakRight);peakHold=Math.max(peakHold,peak);
      reduction=renderer.limiterDb;overloaded|=renderer.overloaded;
     }
     if(!loop&&position>session.end())position=session.end();
    }catch(Exception e){error=e.getMessage();playing=false;}
    finally{
     if(track!=null)track.close();device=null;countInRemaining=0;peak=peakLeft=peakRight=reduction=0;
     for(Core.Track t:session.tracks)t.meter=t.meterL=t.meterR=t.reduction=0;
    }
   },"CityStudio-Audio");
   thread.start();
  }
 public synchronized void pause(){playing=false;Output t=device;if(t!=null)t.interrupt();Thread old=thread;if(old!=null&&old!=Thread.currentThread())try{old.join(1200);}catch(InterruptedException e){Thread.currentThread().interrupt();}if(old==null||!old.isAlive())thread=null;}
 public void stop(){pause();position=0;}
 public void seek(long frames){pause();position=Math.max(0,Math.min(Core.MAX_FRAMES,frames));}
 }
 public static final class Capture {
  public volatile boolean running;public volatile float meter;public volatile String error,route;
  /** Optional input device (for example a USB audio interface), first input channel (0-based) and whether to record a stereo pair. */
  public volatile AudioDeviceInfo device;public volatile boolean echoCancel;public volatile int channel=0;public volatile boolean stereo=false;
  /** When set, every recorded frame is also pushed here for input monitoring. */
  public volatile Pro.Ring monitor;
  private Thread thread;private AudioRecord recorder;
  static AudioRecord open(int source,AudioDeviceInfo device,int channel,boolean stereo,int[] channelsOut){
   int positional=stereo?AudioFormat.CHANNEL_IN_STEREO:AudioFormat.CHANNEL_IN_MONO;
   AudioFormat.Builder format=new AudioFormat.Builder().setSampleRate(Core.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT);
   boolean indexed=false;if(device!=null){int most=0;for(int c:device.getChannelCounts())most=Math.max(most,c);indexed=channel>0||most>2;}
   if(indexed)format.setChannelIndexMask(stereo?(3<<channel):(1<<channel));else format.setChannelMask(positional);
   int min=AudioRecord.getMinBufferSize(Core.RATE,positional,AudioFormat.ENCODING_PCM_16BIT);
   AudioRecord r;try{r=new AudioRecord.Builder().setAudioSource(source).setAudioFormat(format.build()).setBufferSizeInBytes(Math.max(min*2,8192)).build();}catch(Exception e){return null;}
   if(r.getState()!=AudioRecord.STATE_INITIALIZED){r.release();return null;}
   if(device!=null&&!r.setPreferredDevice(device)){r.release();return null;}
   channelsOut[0]=Math.max(1,r.getChannelCount());return r;}
  public void begin(File file,Runnable finished){running=true;route=null;final AudioDeviceInfo wanted=device;final int first=channel;final boolean pair=stereo;thread=new Thread(()->{AudioRecord r=null;try{int[] channels={1};
   int[] order=echoCancel?new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION,MediaRecorder.AudioSource.MIC}:new int[]{MediaRecorder.AudioSource.UNPROCESSED,MediaRecorder.AudioSource.MIC};for(int source:order){r=open(source,wanted,first,pair,channels);if(r!=null)break;}
   if(r!=null&&echoCancel){try{if(android.media.audiofx.AcousticEchoCanceler.isAvailable()){android.media.audiofx.AcousticEchoCanceler aec=android.media.audiofx.AcousticEchoCanceler.create(r.getAudioSessionId());if(aec!=null)aec.setEnabled(true);}}catch(Exception ignored){}}
   if(r==null&&wanted!=null){r=open(MediaRecorder.AudioSource.MIC,null,0,false,channels);if(r!=null)route="The selected input could not be opened, so the phone microphone was used.";}
   if(r==null)throw new IOException("Microphone could not be opened.");
   recorder=r;r.startRecording();int width=channels[0];short[] samples=new short[1024*width];
   try(Writer w=new Writer(file)){while(running&&w.frames<Core.MAX_FRAMES){int count=r.read(samples,0,samples.length);if(count<0){if(!running)break;throw new IOException("Microphone read error "+count);}float max=0;Pro.Ring ring=monitor;for(int i=0;i+width-1<count;i+=width){float left=samples[i]/32768f,right=width>1?samples[i+1]/32768f:left;w.sample(left,right);if(ring!=null)ring.push(left,right);max=Math.max(max,Math.max(Math.abs(left),Math.abs(right)));}meter=max;}}
  }catch(Exception e){error=e.getMessage();}finally{running=false;if(r!=null){try{r.stop();}catch(Exception ignored){}r.release();}recorder=null;finished.run();}},"CityStudio-Record");thread.start();}
  public void stop(){running=false;AudioRecord r=recorder;if(r!=null)try{r.stop();}catch(Exception ignored){}}
 }
}

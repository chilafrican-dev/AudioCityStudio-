package ug.audiocity.studio;
import java.io.*;import java.util.*;import java.util.function.LongSupplier;
public final class MidiTake {
 public static final class Event {public final int frame,status,a,b;Event(int f,int s,int a,int b){frame=f;status=s;this.a=a;this.b=b;}}
 public final Synth.Config config;public final SampledInstruments.Pack samplePack;public volatile String samplePackId;public final int bpm;public final long started=System.nanoTime();public final ArrayList<Event> events=new ArrayList<>();public volatile boolean recording=true;public int frames;
 public volatile long projectStop=-1;private volatile int frameLimit=(int)Core.MAX_FRAMES;
 public void limitTo(int frames){if(frames<1)throw new IllegalArgumentException("Invalid take length");frameLimit=Math.min((int)Core.MAX_FRAMES,frames);}
 public boolean complete(){return !pending()&&elapsedFrames()>=frameLimit;}
 private final LongSupplier transport;private final long startFrame;
 public MidiTake(Synth.Config c,int bpm){this(c,bpm,null,0);}
 public MidiTake(Synth.Config c,int bpm,LongSupplier transport,long startFrame){this(c,bpm,transport,startFrame,null);}
 public MidiTake(Synth.Config c,int bpm,LongSupplier transport,long startFrame,SampledInstruments.Pack pack){this(c,bpm,transport,startFrame,pack,null);}
 public MidiTake(Synth.Config c,int bpm,LongSupplier transport,long startFrame,SampledInstruments.Pack pack,String packId){samplePack=pack;samplePackId=packId;config=c.copy();this.bpm=bpm;this.transport=transport;this.startFrame=startFrame;}
 public boolean pending(){return transport!=null&&transport.getAsLong()<startFrame;}
 public int elapsedFrames(){long count=transport==null?(System.nanoTime()-started)*Core.RATE/1000000000L:transport.getAsLong()-startFrame;return (int)Math.min(frameLimit,Math.max(0,count));}
 /** Maps a System.nanoTime() moment to the song frame the musician heard then (the audio engine's clock). */
 public volatile java.util.function.LongUnaryOperator clock;
 public void add(int status,int a,int b){add(status,a,b,System.nanoTime());}
 /**
  * Records an event at the moment it happened (touch or MIDI timestamp), placed where the musician heard the song at
  * that moment: output latency and UI delays do not push notes late. Notes played up to a 1/8 beat early, during the
  * last click of the count-in, land on the first beat instead of being lost.
  */
 public synchronized void add(int status,int a,int b,long nanos){
  if(!recording||events.size()>=100000)return;
  java.util.function.LongUnaryOperator c=clock;long frame;
  if(c==null){if(pending()||complete())return;frame=elapsedFrames();}
  else{frame=c.applyAsLong(nanos)-startFrame;if(frame<-Core.beat(bpm)/8)return;frame=Math.max(0,frame);if(frame>=frameLimit)return;}
  events.add(new Event((int)frame,status,a,b));
 }
 public void finish(){finish(System.nanoTime());}
 /** Stops recording at that moment; the take's length is where the musician heard the song when Stop was pressed. */
 public synchronized void finish(long nanos){if(!recording)return;recording=false;java.util.function.LongUnaryOperator c=clock;frames=c==null?elapsedFrames():(int)Math.max(0,Math.min(frameLimit,c.applyAsLong(nanos)-startFrame));for(Event e:events)frames=Math.max(frames,e.frame);for(int ch=0;ch<16;ch++){events.add(new Event(frames,176|ch,64,0));events.add(new Event(frames,176|ch,123,0));}}
 static int releaseTailFrames(Synth.Config config,SampledInstruments.Pack pack){int tail=(int)((Math.max(config.release,1.8)+.05)*Core.RATE);if(pack!=null)for(SampledInstruments.Region region:pack.regions)tail=Math.max(tail,region.pcm.length/2);return tail;}
 public void print(TimeStretch.Sink sink)throws IOException{boolean notes=false;for(Event e:events)if((e.status&240)==144&&e.b>0)notes=true;if(!notes)throw new IOException("No MIDI notes were received.");SynthPoly synth=new SynthPoly(config);synth.setSamplePack(samplePack);int end=(int)Math.min(Core.MAX_FRAMES,(long)frames+releaseTailFrames(config,samplePack));float[] block=new float[1024];int pos=0,event=0;while(pos<end){while(event<events.size()&&events.get(event).frame<=pos){Event e=events.get(event++);synth.message(e.status,e.a,e.b);}int n=Math.min(512,end-pos);if(event<events.size())n=Math.min(n,Math.max(1,events.get(event).frame-pos));synth.render(block,n);for(int i=0;i<n;i++)sink.sample(block[2*i],block[2*i+1]);pos+=n;}}
 static void variable(OutputStream out,int value)throws IOException{int buffer=value&127;while((value>>=7)>0){buffer<<=8;buffer|=(value&127)|128;}while(true){out.write(buffer&255);if((buffer&128)!=0)buffer>>=8;else break;}}
 public void writeMidi(OutputStream destination)throws IOException{ByteArrayOutputStream track=new ByteArrayOutputStream();track.write(new byte[]{0,(byte)255,81,3});int tempo=60000000/bpm;track.write((tempo>>16)&255);track.write((tempo>>8)&255);track.write(tempo&255);int previous=0;for(Event e:events){int tick=(int)Math.round(e.frame/(double)Core.RATE*bpm/60*480);variable(track,Math.max(0,tick-previous));previous=tick;track.write(e.status);track.write(e.a);if((e.status&240)!=192&&(e.status&240)!=208)track.write(e.b);}track.write(new byte[]{0,(byte)255,47,0});DataOutputStream out=new DataOutputStream(destination);out.writeBytes("MThd");out.writeInt(6);out.writeShort(0);out.writeShort(1);out.writeShort(480);out.writeBytes("MTrk");out.writeInt(track.size());track.writeTo(out);out.flush();}
}

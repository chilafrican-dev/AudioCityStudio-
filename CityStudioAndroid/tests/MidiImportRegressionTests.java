package ug.audiocity.studio;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;
public final class MidiImportRegressionTests {
 static int checks;static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static MidiTake.Event e(int at,int status,int a,int b){return new MidiTake.Event(at,status,a,b);}
 public static void main(String[] args)throws Exception{
  String which=args.length==0||args[0].equals("assets")?"all":args[0];
  if(which.equals("all")||which.equals("sustain"))sustain();
  if(which.equals("all")||which.equals("controls"))controls();
  if(which.equals("all")||which.equals("wav"))wav();
  if(which.equals("all")||which.equals("global"))global();
  if(which.equals("all")||which.equals("project"))project();
  if(which.equals("all")||which.equals("export"))export();
  System.out.println("MidiImportRegressionTests passed "+checks+" checks");
 }
 static void sustain(){
  List<MidiTake.Event> events=Arrays.asList(e(0,0x90,60,100),e(10,0xB0,64,127),e(100,0x80,60,0),e(300,0xB0,64,0));
  List<Pro.Note> notes=Pro.notes(events,null);ok(notes.size()==1&&notes.get(0).length==300,"saved live MIDI must retain sustain-pedal duration");
  events=Arrays.asList(e(0,0x90,60,100),e(20,0xB0,123,0),e(500,0x91,67,100),e(600,0x81,67,0));
  notes=Pro.notes(events,null);ok(notes.get(0).length==20,"All Notes Off ends held notes at its own time, not at end of take");
  events=Arrays.asList(e(0,0x90,60,100),e(0,0x91,64,100),e(10,0xB0,64,127),e(100,0x80,60,0),e(120,0x81,64,0),e(200,0xB0,120,0),e(600,0xB0,64,0));
  notes=Pro.notes(events,null);ok(notes.get(0).length==200&&notes.get(1).length==120,"sustain and panic messages stay on their MIDI channel");
 }
 static void controls(){
  Core.Session s=new Core.Session();ProLive.Mapping m=new ProLive.Mapping(0,12,false,ProLive.PLAY);
  ok(ProLive.PLAY.equals(ProLive.apply(s,m,127)),"first controller press triggers Play");
  ok(ProLive.apply(s,m,127)==null&&ProLive.apply(s,m,100)==null,"held/repeated controller values do not toggle Play again");
  ok(ProLive.apply(s,m,0)==null&&ProLive.PLAY.equals(ProLive.apply(s,m,100)),"release rearms controller for next press");
 }
 static void project()throws Exception{
  float[] audio=new float[10000];Arrays.fill(audio,.2f);SampledInstruments.Pack pack=SampledInstruments.monoPack("saved piano",audio,60,"test");
  List<Pro.Note> notes=new ArrayList<>(Arrays.asList(new Pro.Note(60,100,0,4000,0)));
  Core.Session s=new Core.Session();Core.Track track=new Core.Track("Keys",0);s.tracks.add(track);
  for(int i=0;i<3;i++){Core.Clip c=ProMidi.clip("part"+i,notes,Synth.factory(0),null,"pack-test/piano.sfz",i*10000,8000);if(i==0)track.clips.add(c);else if(i==1)track.takes.add(c);else track.slots.add(c);}
  s.reference=ProMidi.clip("reference",notes,Synth.factory(0),null,"pack-test/piano.sfz",0,8000);
  int[] loads={0};List<String> failed=ProjectInstruments.prepare(s,c->{loads[0]++;return pack;});
  ok(loads[0]==1&&failed.isEmpty(),"one shared sample load for clips, takes, launcher and reference");
  for(Core.Clip c:Arrays.asList(track.clips.get(0),track.takes.get(0),track.slots.get(0),s.reference))ok(c.samplePack==pack&&c.source instanceof ProMidi.LiveSource,"saved sample is assigned before playback");
  ProjectInstruments.prepare(s,c->{throw new AssertionError("already loaded pack was loaded again");});checks++;
  Core.Session missing=new Core.Session();Core.Track mt=new Core.Track("Missing",0);missing.tracks.add(mt);
  for(int i=0;i<2;i++)mt.clips.add(ProMidi.clip("saved",notes,Synth.factory(0),null,"pack-missing/piano.sfz",0,8000));
  int[] attempts={0};failed=ProjectInstruments.prepare(missing,c->{attempts[0]++;throw new IOException("Not installed");});
  ok(attempts[0]==1&&failed.size()==1,"report a missing instrument once for the whole project");
  for(Core.Clip c:mt.clips)ok(c.notes.size()==1&&c.length==8000&&c.instrumentPackId.equals("pack-missing/piano.sfz")&&c.source.frames()>=c.offset+c.length&&c.source.sample(500,0)==0,"missing pack keeps notes intact without using a wrong synth");
  failed=ProjectInstruments.prepare(missing,c->pack);ok(failed.isEmpty()&&mt.clips.get(0).samplePack==pack&&mt.clips.get(0).source.sample(500,0)>0,"reinstalled pack restores sound without rewriting notes");
  ProLive.Mapping mapped=new ProLive.Mapping(0,60,true,ProLive.PLAY);
  ok(mapped.consumes(0x90,60)&&mapped.consumes(0x80,60),"mapped channel consumes its pad press and release");
  ok(!mapped.consumes(0x91,60)&&!mapped.consumes(0x81,60),"mapped pad cannot swallow another MIDI channel's note release");
 }
 static void export()throws Exception{
  List<Pro.Note> notes=new ArrayList<>(Arrays.asList(new Pro.Note(60,100,0,10000,0),new Pro.Note(67,90,20000,4000,0)));
  Core.Clip clip=ProMidi.clip("Live",notes,Synth.factory(0),50000,30000);
  ok(MidiClipExport.available(clip)&&clip.midiFile==null,"live clip can export without a printed MIDI file");
  clip.notes.get(0).pitch=72;clip.offset=5000;clip.length=16000;
  byte[] bytes=MidiClipExport.bytes(clip,new File("unused"),120);List<Pro.Note> read=Pro.notes(Pro.readMidi(new ByteArrayInputStream(bytes)),null);
  ok(read.size()==2&&read.get(0).pitch==72,"export uses edited notes, not an old file");
  ok(read.get(0).start==0&&read.get(0).length==5000,"left trim exports only the visible note portion");
  ok(read.get(1).start==15000&&read.get(1).length==1000,"right trim cuts note and rebases time to clip beginning");
  ok(clip.notes.get(0).start==0&&clip.notes.get(0).length==10000,"export does not alter the project notes");
 }
 static byte[] wave(boolean junk){int samples=400,size=44+samples*2+(junk?12:0);ByteBuffer b=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes());b.putInt(size-8);b.put("WAVE".getBytes());if(junk){b.put("JUNK".getBytes());b.putInt(3);b.put(new byte[]{1,2,3,0});}b.put("fmt ".getBytes());b.putInt(16);b.putShort((short)1);b.putShort((short)1);b.putInt(44100);b.putInt(88200);b.putShort((short)2);b.putShort((short)16);b.put("data".getBytes());b.putInt(samples*2);for(int i=0;i<samples;i++)b.putShort((short)(Math.sin(i*.1)*10000));return b.array();}
 static void clean(File f){if(f.isDirectory())for(File c:f.listFiles())clean(c);f.delete();}
 static void wav()throws Exception{
  File dir=Files.createTempDirectory("acs-wav-header-").toFile();try{
   File f=new File(dir,"note.wav");Files.write(f.toPath(),wave(true));
   ok(SampledInstruments.readWav(f).length>400,"WAV with odd-sized metadata chunk is decoded");
   ok(SampledInstruments.wavSampleRate(f)==44100,"sample-rate detection accepts metadata before fmt");
   File sfz=new File(dir,"piano.sfz");Files.write(sfz.toPath(),"<region> sample=note.wav key=60 loop_mode=loop_continuous loop_start=20 loop_end=300".getBytes("UTF-8"));
   SampledInstruments.Pack pack=SampledInstruments.readSFZ(sfz,"test");ok(pack.regions.length==1&&pack.regions[0].loop,"SFZ can load the same legal WAV");
  }finally{clean(dir);}
 }
 static void global()throws Exception{
  File dir=Files.createTempDirectory("acs-sfz-global-").toFile();try{
   Files.write(new File(dir,"note.wav").toPath(),wave(false));File sfz=new File(dir,"instrument.sfz");
   Files.write(sfz.toPath(),"<global> ampeg_release=1.5 pitch_keycenter=48 lovel=40 hivel=90 <region> sample=note.wav lokey=0 hikey=127".getBytes("UTF-8"));
   SampledInstruments.Region r=SampledInstruments.readSFZ(sfz,"test").regions[0];
   ok(r.releaseFrames==72000&&r.root==48&&r.lowVelocity==40&&r.highVelocity==90,"regions inherit global SFZ settings even without a group");
  }finally{clean(dir);}
 }
}

package ug.audiocity.studio;
import java.util.*;import java.util.concurrent.atomic.AtomicLong;
public final class RecordedClipBoundsTests {
 static int checks;static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static MidiTake take(int bpm,int frames,int noteEnd){MidiTake t=new MidiTake(Synth.factory(0),bpm);t.recording=false;t.frames=frames;t.events.add(new MidiTake.Event(0,144,60,100));t.events.add(new MidiTake.Event(noteEnd,128,60,0));return t;}
 public static void main(String[] args){
  for(int bpm:new int[]{40,85,100,137,240})for(int signature:new int[]{3,4,6}){
   Core.Session s=new Core.Session();s.bpm=bpm;s.timeSignature=signature;long bar=new Pro.TempoMap(s).frameAt(signature),start=bar*2,end=bar*4;
   AtomicLong clock=new AtomicLong(start-bar);MidiTake t=new MidiTake(Synth.factory(0),bpm,clock::get,start);t.limitTo((int)(end-start));t.projectStop=end;
   ok(t.pending()&&!t.complete(),"count-in does not consume recording length");clock.set(start);t.add(144,60,100);clock.set(end+Core.beat(bpm));t.add(144,67,100);t.finish();
   ok(t.frames==end-start&&t.complete(),"late UI stop cannot extend recorded loop duration");ok(t.events.stream().noneMatch(e->e.status==144&&e.a==67),"notes from a second loop pass are not recorded");
   MidiTake fitted=TakeBounds.fit(t,start,s,true);Core.Clip c=ProMidi.clip("Loop take",ProMidi.fromTake(fitted),fitted.config,start,fitted.frames);
   ok(c.start==start&&c.start+c.length==end,"visible MIDI clip ends exactly at loop end");ok(ProMidi.fromTake(fitted).stream().allMatch(n->n.end()<=fitted.frames),"quantized note releases never pass loop end");
   s.loopStart=start;s.loopEnd=end;ok(TakeBounds.loopEnd(s)==end,"explicit loop range is respected");
   MidiTake free=take(bpm,(int)(bar+500), (int)(bar-Core.beat(bpm)/2));free=TakeBounds.fit(free,0,s,true);ok(free.frames==bar,"slightly late Stop does not add an empty extra bar");
   MidiTake longNote=TakeBounds.fit(take(bpm,(int)(bar+1000),(int)(bar+1000)),0,s,false);ok(longNote.frames==new Pro.TempoMap(s).frameAt(signature*2),"free recording keeps a note crossing the bar and closes at the next bar");
  }
  Core.Session s=new Core.Session();s.bpm=100;s.timeSignature=4;long bar=new Pro.TempoMap(s).frameAt(4);long cursor=1234;
  MidiTake free=TakeBounds.fit(take(100,10000,5000),cursor,s,true);ok(cursor+free.frames==bar,"a take from a mid-bar cursor ends on a bar boundary");
  Core.Track backing=new Core.Track("Beat",0);Core.Clip shortBeat=new Core.Clip("Beat",new Core.Memory(new float[1000]),"beat.pcm",0);backing.clips.add(shortBeat);s.tracks.add(backing);ok(TakeBounds.loopEnd(s)==bar,"whole-song loop is at least one complete bar");
  shortBeat.start=bar;ok(TakeBounds.loopEnd(s)==bar*2,"whole-song loop rounds up to a complete bar");
  s.tempoChanges.add(new Pro.TempoChange(1,140));Pro.TempoMap map=new Pro.TempoMap(s);long at=map.frameAt(4),stop=map.frameAt(8);
  MidiTake variable=take(100,(int)(stop-at+500),(int)(stop-at-1000));MidiTake aligned=TakeBounds.fit(variable,at,s,true);ok(at+aligned.frames==stop,"bar ends follow tempo changes");
  MidiTake loop=take(100,(int)bar,(int)bar);loop.projectStop=bar;SampledInstruments.Pack pack=SampledInstruments.monoPack("Long piano",new float[48000*8],60,"test");MidiTake sampled=new MidiTake(loop.config,100,null,0,pack,"test/long.wav");sampled.recording=false;sampled.frames=loop.frames;sampled.projectStop=bar;sampled.events.addAll(loop.events);MidiTake fitted=TakeBounds.fit(sampled,0,s,true);
  Core.Clip c=ProMidi.clip("Piano",ProMidi.fromTake(fitted),fitted.config,fitted.samplePack,fitted.samplePackId,0,fitted.frames);ok(c.length==bar&&c.source.frames()>c.length,"long instrument release lives in the source without extending visible clip");
  System.out.println("RecordedClipBoundsTests passed "+checks+" checks");
 }
}

package ug.audiocity.studio;
import java.util.*;
/** Musical recording bounds; playback tails stay in the source, not the visible clip length. */
public final class TakeBounds {
 private TakeBounds(){}
 public static long barEnd(Core.Session session,long frame){return barEnd(new Pro.TempoMap(session),frame);}
 private static long barEnd(Pro.TempoMap map,long frame){long at=Math.max(0,frame);int beats=map.beatsPerBar();long bar=(long)Math.floor(map.beatAt(at)/beats);if(map.frameAt(bar*(double)beats)<at)bar++;return Math.min(Core.MAX_FRAMES,map.frameAt(Math.max(1,bar)*(double)beats));}
 /** Loop start: the chosen range, otherwise the bar where the first clip begins (so a song starting at bar 2 loops from bar 2). */
 public static long loopStart(Core.Session session){return loopStart(session,new Pro.TempoMap(session));}
 public static long loopStart(Core.Session session,Pro.TempoMap map){
  if(session.loopEnd>session.loopStart)return session.loopStart;
  long first=Long.MAX_VALUE;for(Core.Track t:session.tracks)for(Core.Clip c:t.clips)first=Math.min(first,c.start);
  if(first==Long.MAX_VALUE||first<=0)return 0;
  int beats=map.beatsPerBar();long bar=(long)Math.floor(map.beatAt(first)/beats);return Math.max(0,Math.min(first,map.frameAt(bar*(double)beats)));
 }
 public static long loopEnd(Core.Session session){return loopEnd(session,new Pro.TempoMap(session));}
 public static long loopEnd(Core.Session session,Pro.TempoMap map){return session.loopEnd>session.loopStart?session.loopEnd:barEnd(map,Math.max(1,session.end()));}
 public static MidiTake fit(MidiTake source,long start,Core.Session session,boolean quantize){return fit(source,start,session,quantize?4:0,true);}
 /**
  * division: 0 keeps the performance exactly as played; 1, 2, 4, 8 quantize to 1/4, 1/8, 1/16, 1/32 notes on the tempo map.
  * extendToBar: the region ends on the next bar line (notes are never moved or stretched); otherwise it ends where recording stopped.
  */
 public static MidiTake fit(MidiTake source,long start,Core.Session session,int division,boolean extendToBar){boolean quantize=division>0;
  ArrayList<MidiTake.Event> other=new ArrayList<>();List<Pro.Note> notes=Pro.notes(source.events,other);Pro.TempoMap map=new Pro.TempoMap(session);
  long required=1;
  for(Pro.Note note:notes){
   if(quantize){long a=map.snap(start+note.start,division),b=map.snap(start+note.end(),division);b=Math.max(b,map.frameAt(map.beatAt(a)+1.0/division));note.start=Math.max(0,a-start);note.length=Math.max(1,b-start-note.start);}
   required=Math.max(required,note.end());
  }
  // A captured loop end is an upper bound, not the length of a manually stopped take.
  long end;
  if(source.projectStop>=0&&start+(long)source.frames>=source.projectStop)end=source.projectStop;
  else if(!extendToBar){end=start+Math.max(required,Math.max(1,source.frames));if(source.projectStop>=0)end=Math.min(end,source.projectStop);}
  else{end=map.frameAt(Math.round(map.beatAt(start+source.frames)/map.beatsPerBar())*(double)map.beatsPerBar());if(end<start+required||end<=start)end=barEnd(session,start+required);if(source.projectStop>=0)end=Math.min(end,source.projectStop);}
  int length=(int)Math.max(1,Math.min(Core.MAX_FRAMES-start,end-start));
  for(Iterator<Pro.Note> it=notes.iterator();it.hasNext();){Pro.Note note=it.next();if(note.start>=length)it.remove();else note.length=Math.min(note.length,length-note.start);}
  other.removeIf(event->event.frame<0||event.frame>length);
  MidiTake generated=Pro.take(source.config,source.bpm,notes,other),take=new MidiTake(source.config,source.bpm,null,0,source.samplePack,source.samplePackId);
  take.recording=false;take.frames=length;take.projectStop=source.projectStop;take.events.addAll(generated.events);
  for(int ch=0;ch<16;ch++){take.events.add(new MidiTake.Event(length,176|ch,64,0));take.events.add(new MidiTake.Event(length,176|ch,123,0));}
  return take;
 }
}

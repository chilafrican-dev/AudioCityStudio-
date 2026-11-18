package ug.audiocity.studio;
import java.util.*;import java.io.*;import java.nio.file.*;
public final class MelodyClipLengthTests {
 static int n;static void ok(boolean b,String why){n++;if(!b)throw new AssertionError(why);}static void clean(File f){if(f.isDirectory())for(File c:f.listFiles())clean(c);f.delete();}
 static MidiTake take(int bpm,int frames,long stop,int off){MidiTake t=new MidiTake(Synth.factory(0),bpm);t.recording=false;t.frames=frames;t.projectStop=stop;t.events.add(new MidiTake.Event(0,144,60,104));t.events.add(new MidiTake.Event(off,128,60,0));return t;}
 static Core.Source silent(final int frames){return new Core.Source(){public int frames(){return frames;}public float sample(int f,int c){return 0;}};}
 public static void main(String[] args)throws Exception{
  for(int bpm:new int[]{40,85,93,100,137,240})for(int sig:new int[]{3,4,6}){Core.Session s=new Core.Session();s.bpm=bpm;s.timeSignature=sig;long bar=new Pro.TempoMap(s).frameAt(sig);
   MidiTake shortTake=TakeBounds.fit(take(bpm,(int)bar+500,8*bar,(int)bar/2),0,s,true);ok(shortTake.frames==bar,"stopping a melody early must not fill the entire eight-bar loop");
   MidiTake complete=TakeBounds.fit(take(bpm,(int)(8*bar),8*bar,(int)bar/2),0,s,true);ok(complete.frames==8*bar,"an automatically completed full loop retains its full recording range");
   MidiTake fromRange=TakeBounds.fit(take(bpm,(int)bar+500,6*bar,(int)bar/2),2*bar,s,true);ok(Math.abs(fromRange.frames-bar)<=1,"early stop inside a selected loop range retains only the recorded bars");
   ok(ProMidi.fromTake(shortTake).size()==1&&ProMidi.fromTake(shortTake).get(0).end()<=bar,"short clip keeps the recorded note inside its bar");
  }
  Core.Session s=new Core.Session();int bar=(int)(Core.beat(100)*4);Core.Clip original=new Core.Clip("MIDI performance",silent(bar*4),"old.pcm",bar*2);original.offset=1000;original.length=bar;original.envelopeOffset=1000;original.envelopeLength=bar*4;original.gain=.42;original.fadeIn=200;original.fadeOut=300;Core.Clip current=original;
  for(int i=0;i<20;i++){Core.Clip printed=ProMidi.reprintedWindow(current,silent(bar*8+i*100),"new.pcm","new.mid");ok(printed.start==original.start&&printed.offset==1000&&printed.length==bar,"repeated note prints never grow or move a trimmed melody clip");ok(printed.fadeIn==200&&printed.fadeOut==300&&printed.gain==.42&&printed.envelopeOffset==1000,"reprinting preserves gain and fade geometry");current=printed;}
  try{ProMidi.reprintedWindow(original,silent(original.offset+original.length-1),"bad.pcm","bad.mid");throw new AssertionError("short source accepted");}catch(IllegalArgumentException expected){n++;}ok(original.length==bar&&original.start==bar*2,"failed print leaves original geometry untouched");
  ArrayList<Pro.Note> notes=new ArrayList<>();notes.add(new Pro.Note(60,104,0,bar/2,0));Core.Clip longClip=ProMidi.clip("Touch performance",notes,Synth.factory(0),0,bar*8);Core.Source source=longClip.source;Core.Clip before=longClip.copy();ok(ProMidi.trimRecordedEmptyEnd(s,longClip)&&longClip.length==bar,"explicit repair trims a recorded melody's empty end to the last note's bar");ok(longClip.source==source&&longClip.notes.size()==1&&before.length==bar*8,"repair retains source notes and the full Undo snapshot");ok(!ProMidi.trimRecordedEmptyEnd(s,longClip),"melody length repair is idempotent");Core.Clip custom=ProMidi.clip("My custom MIDI region",notes,Synth.factory(0),0,bar*8);ok(!ProMidi.trimRecordedEmptyEnd(s,custom)&&custom.length==bar*8,"custom MIDI region lengths and intentional rests are not silently altered");
  Core.Track melody=new Core.Track("Piano",0);melody.clips.add(longClip);s.tracks.add(melody);File dir=Files.createTempDirectory("acs-melody-length-").toFile();try{s.directory=dir.getAbsolutePath();ProjectIO.save(s);Core.Session reopened=AudioIO.load(dir);ok(reopened.tracks.get(0).clips.get(0).length==bar,"repaired melody length survives project reopen");}finally{clean(dir);}
  System.out.println("MelodyClipLengthTests passed "+n+" checks");
 }
}

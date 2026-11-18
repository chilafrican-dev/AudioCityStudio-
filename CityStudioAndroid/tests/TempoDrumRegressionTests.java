package ug.audiocity.studio;
import java.io.*;import java.nio.file.*;import java.util.*;
public final class TempoDrumRegressionTests {
 static int checks;static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void clean(File f){if(f.isDirectory())for(File c:f.listFiles())clean(c);f.delete();}
 public static void main(String[] args)throws Exception{
  File dir=Files.createTempDirectory("city-drum-tempo-").toFile();
  try{
   Core.Session s=new Core.Session();s.directory=dir.getAbsolutePath();s.bpm=100;s.loopStart=Core.beat(100)*4;s.loopEnd=Core.beat(100)*8;
   BeatPilotDrumPattern pattern=BeatPilotDrumPattern.starter(100,1);float[][] waves=new float[8][];for(int lane=0;lane<8;lane++){waves[lane]=new float[1600];for(int i=0;i<800;i++)waves[lane][i*2]=waves[lane][i*2+1]=(float)(Math.sin(i*(lane+1)*.04)*.35);}
   float[] audio=pattern.render(waves);Core.Clip drum=AudioIO.store(dir,audio,"Drums",s.loopStart);drum.drumPattern=pattern.encode();drum.notes=pattern.notes();drum.midiFile="original.mid";try(OutputStream out=new FileOutputStream(new File(dir,drum.midiFile))){pattern.writeMidi(out);}
   Core.Track track=new Core.Track("Drums",0);track.clips.add(drum);s.tracks.add(track);
   Core.Clip live=ProMidi.clip("Keys",new ArrayList<>(Arrays.asList(new Pro.Note(60,100,0,24000,0))),Synth.factory(3),0,48000);live.midiFile="keys-original.mid";try(OutputStream out=new FileOutputStream(new File(dir,live.midiFile))){Pro.take(live.instrument,100,live.notes,null).writeMidi(out);}Core.Track keys=new Core.Track("Keys",0);keys.clips.add(live);s.tracks.add(keys);
   Core.Session changed=AudioIO.retimeProject(s,200,false);Core.Clip newDrum=changed.tracks.get(0).clips.get(0);
   ok(newDrum.source instanceof AudioIO.Disk,"tempo change must keep sampled drums as audio, never replace drums with a synth");
   ok(newDrum.length==drum.length/2&&newDrum.start==drum.start/2,"drum audio follows new tempo");
   ok(BeatPilotDrumRollData.decode(newDrum.drumPattern).bpm==200,"drum editing metadata has the new tempo");
   ok(BeatPilotDrumPattern.decode(newDrum.drumPattern).sampleFile(0).equals(pattern.sampleFile(0)),"retiming preserves sample identity");
   ok(newDrum.notes.size()==drum.notes.size()&&newDrum.notes.get(1).start==Math.round(drum.notes.get(1).start/2.0),"editable drum note timing follows audio");
   ok(changed.loopStart==s.loopStart/2&&changed.loopEnd==s.loopEnd/2,"loop range follows project tempo");
   ok(!newDrum.midiFile.equals(drum.midiFile),"tempo change generates new MIDI source without overwriting the old one");
   List<MidiTake.Event> exported;try(InputStream in=new FileInputStream(new File(dir,newDrum.midiFile))){exported=Pro.readMidi(in);}
   List<Pro.Note> printed=Pro.notes(exported,null);ok(printed.size()==newDrum.notes.size(),"drum MIDI export retains all notes");
   ok(Math.abs(printed.get(1).start-newDrum.notes.get(1).start)<=2,"drum MIDI export tempo matches edited project");
   Core.Clip newKeys=changed.tracks.get(1).clips.get(0);ok(!newKeys.midiFile.equals(live.midiFile),"live MIDI export source is updated after retiming");
   ok(drum.drumPattern.equals(pattern.encode())&&s.bpm==100,"original project and drum pattern remain undoable");
   ProjectIO.save(changed);Core.Session reopened=AudioIO.load(dir);Core.Clip reopenedDrum=reopened.tracks.get(0).clips.get(0);
   ok(reopenedDrum.source instanceof AudioIO.Disk&&reopenedDrum.drumPattern.equals(newDrum.drumPattern),"save/reopen preserves drum sound and editable data");
   ok(reopened.loopStart==changed.loopStart&&reopened.loopEnd==changed.loopEnd,"retimed loop range survives reopening");
   Core.Session tooLong=new Core.Session();tooLong.directory=dir.getAbsolutePath();tooLong.bpm=100;Core.Track longTrack=new Core.Track("Long MIDI",0);Core.Clip late=ProMidi.clip("Late",new ArrayList<>(Arrays.asList(new Pro.Note(60,100,0,1000,0))),Synth.factory(0),Core.MAX_FRAMES-48000,24000);longTrack.clips.add(late);tooLong.tracks.add(longTrack);
   try{AudioIO.retimeProject(tooLong,50,false);throw new AssertionError("oversized MIDI tempo change accepted");}catch(IOException expected){checks++;}
   tooLong.tracks.clear();tooLong.loopStart=Core.MAX_FRAMES-48000;tooLong.loopEnd=Core.MAX_FRAMES;
   try{AudioIO.retimeProject(tooLong,50,false);throw new AssertionError("oversized loop tempo change accepted");}catch(IOException expected){checks++;}
   System.out.println("TempoDrumRegressionTests passed "+checks+" checks");
  }finally{clean(dir);}
 }
}

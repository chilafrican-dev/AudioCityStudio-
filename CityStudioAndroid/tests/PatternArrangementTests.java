package ug.audiocity.studio;
import java.io.*;import java.nio.file.*;import java.util.*;
public final class PatternArrangementTests {
 static int n;static void ok(boolean b,String why){n++;if(!b)throw new AssertionError(why);}static void clean(File f){if(f.isDirectory())for(File c:f.listFiles())clean(c);f.delete();}
 public static void main(String[] args)throws Exception{
  File dir=Files.createTempDirectory("acs-pattern-arrange-").toFile();try{
   Core.Session s=new Core.Session();s.directory=dir.getAbsolutePath();s.bpm=240;BeatPilotDrumPattern data=new BeatPilotDrumPattern(240,1);data.set(0,0,0,110);data.set(0,1,4,100);PatternLibrary.Entry entry=PatternLibrary.add(s,"Pattern 1",data);
   float[][] kit=new float[8][];for(int lane=0;lane<8;lane++){kit[lane]=new float[2000];for(int f=0;f<1000;f++)kit[lane][f*2]=kit[lane][f*2+1]=(float)(Math.sin(f*(lane+1)*.04)*.4);}
   List<Core.Clip> placed=PatternArrangement.place(s,entry.id,kit,0,2);ok(s.tracks.size()==8&&placed.size()==16,"eight separate tracks, two pattern placements");
   for(int lane=0;lane<8;lane++){Core.Track t=s.tracks.get(lane);ok(t.name.equals(BeatPilotDrums.LABELS[lane])&&t.clips.size()==2,"drum line has its own Arrange and mixer channel");for(Core.Clip clip:t.clips){ok(clip.patternLane==lane&&clip.patternId.equals(entry.id),"clip is linked to its pattern and lane");for(Pro.Note note:clip.notes)ok(note.pitch==36+lane,"no other drum notes are mixed into this line");}}
   Core.Track kick=s.tracks.get(0);kick.name="My kick";kick.volume=.31;kick.pan=-.2;data.set(0,2,2,100);PatternArrangement.save(s,entry.id,"Verse",data,kit);
   ok(kick.volume==.31&&kick.pan==-.2&&kick.name.equals("My kick"),"pattern edits preserve independent mixer settings");ok(s.tracks.get(2).clips.get(0).notes.size()==1,"adding clap updates an existing empty clap lane");ok(s.tracks.stream().allMatch(t->t.clips.stream().allMatch(c->c.name.equals("Verse"))),"rename and edits update every placed repeat");
   Core.Clip trimmed=kick.clips.get(0);trimmed.offset=1000;trimmed.envelopeOffset=1000;trimmed.length=12000;PatternArrangement.save(s,entry.id,"Verse",data,kit);ok(kick.clips.get(0).offset==1000&&kick.clips.get(0).length==12000,"saving pattern preserves an independently trimmed lane clip");
   PatternLibrary.Entry copy=PatternLibrary.duplicate(s,entry.id);PatternArrangement.place(s,copy.id,kit,96000,1);ok(s.tracks.size()==8&&s.tracks.get(0).clips.size()==3,"Pattern 2 reuses independent sound tracks");
   ProjectIO.save(s);Core.Session opened=AudioIO.load(dir);ok(opened.patterns.size()==2&&opened.tracks.size()==8&&opened.tracks.get(0).clips.get(0).patternId.equals(entry.id),"pattern bank and linked separate tracks survive reopening");
   ByteArrayOutputStream backup=new ByteArrayOutputStream();AudioIO.backup(backup,opened);File restore=new File(dir,"restore");restore.mkdir();Core.Session restored=AudioIO.restore(new ByteArrayInputStream(backup.toByteArray()),restore);ok(restored.patterns.size()==2&&restored.tracks.get(2).clips.get(0).patternLane==2,"portable backup preserves editable pattern links");
   Core.Session auto=new Core.Session();auto.directory=dir.getAbsolutePath();auto.bpm=240;BeatPilotDrumPattern generated=BeatPilot.pattern(0,1,240,4);ok(!PatternLibrary.empty(generated)&&auto.tracks.isEmpty()&&auto.patterns.isEmpty(),"auto template is opt-in and does not mutate a project by itself");PatternLibrary.Entry autoEntry=PatternLibrary.add(auto,"Auto",generated);PatternArrangement.place(auto,autoEntry.id,kit,0,1);auto.tracks.addAll(BeatPilot.harmony(auto,0,5,false,0,4,0));ok(auto.tracks.size()==10,"optional Auto Build separates all eight drums plus bass and chords");
   System.out.println("PatternArrangementTests passed "+n+" checks");
  }finally{clean(dir);}
 }
}

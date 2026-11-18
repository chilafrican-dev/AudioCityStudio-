package ug.audiocity.studio;
import java.io.*;import java.nio.file.*;
public final class PatternBankTests {
 static int n;static void ok(boolean b,String why){n++;if(!b)throw new AssertionError(why);}static void clean(File f){if(f.isDirectory())for(File c:f.listFiles())clean(c);f.delete();}
 public static void main(String[] args)throws Exception{
  Core.Session s=new Core.Session();ok(s.patterns.isEmpty()&&s.tracks.isEmpty(),"session does not build beats automatically");PatternLibrary.Entry blank=PatternLibrary.blank(s);ok(blank.name.equals("Pattern 1")&&PatternLibrary.empty(PatternLibrary.pattern(s,blank)),"manual Pattern 1 starts empty");
  BeatPilotDrumPattern data=PatternLibrary.pattern(s,blank);data.set(0,0,0,110);data.set(0,1,4,95);PatternLibrary.replace(s,blank.id,"Verse",data);PatternLibrary.Entry duplicate=PatternLibrary.duplicate(s,blank.id);ok(!duplicate.id.equals(blank.id)&&duplicate.data.equals(PatternLibrary.find(s,blank.id).data),"copy creates independent named pattern");
  BeatPilotDrumPattern changed=PatternLibrary.pattern(s,duplicate);changed.set(0,0,0,0);PatternLibrary.replace(s,duplicate.id,"Chorus",changed);ok(PatternLibrary.pattern(s,PatternLibrary.find(s,blank.id)).get(0,0,0)==110,"editing Pattern 2 keeps Pattern 1 intact");
  Core.Session undo=s.copy();PatternLibrary.replace(s,blank.id,"Changed",new BeatPilotDrumPattern(100,1));ok(PatternLibrary.find(undo,blank.id).name.equals("Verse"),"undo snapshots preserve pattern bank");
  File dir=Files.createTempDirectory("acs-pattern-bank-").toFile();try{undo.directory=dir.getAbsolutePath();ProjectIO.save(undo);Core.Session opened=ProjectIO.load(dir,file->{throw new IOException("No audio should be needed");});ok(opened.patterns.size()==2&&PatternLibrary.find(opened,blank.id).name.equals("Verse"),"named patterns survive project reopen");
   Core.Session template=ProjectIO.fromTemplate(ProjectIO.template(undo),dir.getAbsolutePath());ok(template.patterns.size()==2&&PatternLibrary.find(template,duplicate.id).name.equals("Chorus"),"reusable templates retain patterns");
  }finally{clean(dir);}
  for(int lane=0;lane<8;lane++){BeatPilotDrumPattern part=PatternLibrary.lane(data,lane);for(Pro.Note note:part.notes())ok(note.pitch==36+lane,"each pattern line contains only its own drum");}
  s.bpm=140;ok(PatternLibrary.pattern(s,duplicate).bpm==140,"pattern notes follow project tempo without automatic content");
  System.out.println("PatternBankTests passed "+n+" checks");
 }
}

package ug.audiocity.studio;
import java.util.*;import java.io.*;
public class BeatPilotLiveMidiTests{
 static int n=0;
 static void ok(boolean value){if(!value)throw new AssertionError("Check "+n);n++;}
 public static void main(String[] args)throws Exception{
  boolean[][] p=BeatPilotDrums.starter();
  String s=BeatPilotDrums.encode(p,100);
  ok(s.equals(BeatPilotDrums.encode(BeatPilotDrums.decode(s),100)));
  ok(BeatPilotDrums.tempo(s)==100);
  ok(BeatPilotDrums.notes(p,100,4).size()>0);
  ok(BeatPilotDrums.notes(p,100,4).stream().allMatch(x->x.channel==9&&x.pitch>=36&&x.pitch<44));
  ByteArrayOutputStream midi=new ByteArrayOutputStream();BeatPilotDrums.writeMidi(midi,p,100,4);
  byte[] b=midi.toByteArray();ok(b.length>60&&b[0]=='M'&&b[1]=='T'&&b[2]=='h'&&b[3]=='d');
  float[] a=new float[]{.7f,-.7f,.2f,-.2f};
  BeatPilotDrums.LivePattern live=new BeatPilotDrums.LivePattern(a,100);
  ok(live.sample(0,0)==.7f && live.sample(2,0)==.7f);
  live.replace(new float[]{.1f,.2f});
  ok(live.sample(0,0)==.1f&&live.sample(1,1)==.2f);
  Core.Session project=new Core.Session();project.bpm=100;
  Core.Track t=new Core.Track("Audio City MIDI Drums",3);
  Core.Clip clip=new Core.Clip("Drum pattern",new Core.Memory(new float[32]),"example.wav",0);
  clip.drumPattern=s;clip.notes=BeatPilotDrums.notes(p,100,4);
  t.clips.add(clip);project.tracks.add(t);
  Core.Track tmp=new Core.Track("Preview",2);tmp.ephemeral=true;project.tracks.add(tmp);
  java.io.File dir=java.nio.file.Files.createTempDirectory("beatpilot-midi").toFile();project.directory=dir.getAbsolutePath();
  ProjectIO.save(project);
  String json=new String(java.nio.file.Files.readAllBytes(new File(dir,"session.json").toPath()),"UTF-8");
  ok(json.contains("drumPattern")&&!json.contains("Preview"));
  ok(json.contains("Audio City MIDI Drums"));
  System.out.println(n+" BeatPilot live MIDI checks passed");
 }
}
package ug.audiocity.studio;
import java.io.*;
public class BeatPilotMultiBarTests {
 static int n;
 static void ok(boolean v){n++;if(!v)throw new AssertionError("test "+n);}
 public static void main(String[] args)throws Exception{
  float[][] sound=new float[8][];for(int i=0;i<8;i++){sound[i]=new float[960];sound[i][0]=.8f;sound[i][1]=.8f;}
  for(int bars:new int[]{1,2,4,8}){
   BeatPilotDrumPattern p=new BeatPilotDrumPattern(100,bars);
   p.set(0,0,0,127);p.set(bars-1,2,15,42);
   BeatPilotDrumPattern q=BeatPilotDrumPattern.decode(p.encode());
   ok(q.bars==bars);ok(q.get(0,0,0)==127);ok(q.get(bars-1,2,15)==42);
   ok(q.notes().size()==2);ok(q.notes().get(0).velocity==127 || q.notes().get(1).velocity==127);
   float[] pcm=q.render(sound);ok(pcm.length==(int)Math.round(Core.RATE*60.0/100*4*bars)*2);ok(Math.abs(pcm[0])>.1);
   ByteArrayOutputStream out=new ByteArrayOutputStream();q.writeMidi(out);ok(out.size()>30);ok(new String(out.toByteArray(),0,4,"US-ASCII").equals("MThd"));
  }
  BeatPilotDrumPattern initial=BeatPilotDrumPattern.starter(92,1);BeatPilotDrumPattern larger=initial.resized(4);ok(larger.bars==4);ok(larger.get(3,0,0)>0);
  BeatPilotDrumPattern old=BeatPilotDrumPattern.decode(BeatPilotDrums.encode(BeatPilotDrums.starter(),100));ok(old.bars==4);ok(old.get(2,1,4)==105);
  try{BeatPilotDrumPattern.decode("v2|100|1|xyz");throw new AssertionError("invalid data accepted");}catch(IllegalArgumentException expected){n++;}
  try{initial.set(0,0,0,129);throw new AssertionError("invalid velocity accepted");}catch(IllegalArgumentException expected){n++;}
  System.out.println(n+" multibar MIDI drum checks passed");
 }
}

package ug.audiocity.studio;
import java.io.*;import java.util.*;
public class BeatPilotDrumRollTests {
 static int checks;
 static void ok(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 public static void main(String[] argv)throws Exception{
  for(int bars:new int[]{1,2,4,8}){
   BeatPilotDrumRollData r=new BeatPilotDrumRollData(100,bars);
   r.add(0,0,96,127);r.add(7,bars*384-6,6,1);r.add(2,35,19,68);
   BeatPilotDrumRollData copy=BeatPilotDrumRollData.decode(r.encode());ok(copy.bars==bars);ok(copy.hits.size()==3);ok(copy.hits.get(2).tick==35);ok(copy.hits.get(2).length==19);ok(copy.hits.get(2).velocity==68);
   ok(copy.notes().size()==3);
   List<MidiTake.Event> preview=copy.auditionEvents(120);ok(preview.size()==3);ok(preview.get(0).status==0x99&&preview.get(0).a==36&&preview.get(0).b==127);ok(preview.get(1).frame==(int)Math.round(Core.beat(120)*35/96.0));ok(preview.stream().allMatch(e->(e.status&15)==9));
   float[][] sounds=new float[8][];for(int i=0;i<8;i++){sounds[i]=new float[400];sounds[i][0]=1;}float[] audio=copy.render(sounds);ok(audio.length==(int)Math.round(Core.RATE*60.0/100*4*bars)*2);ok(audio[0]>.01);
   ByteArrayOutputStream out=new ByteArrayOutputStream();copy.writeMidi(out);ok(out.size()>40);ok(new String(out.toByteArray(),0,4,"US-ASCII").equals("MThd"));
   BeatPilotDrumRollData old=BeatPilotDrumRollData.decode(BeatPilotDrumPattern.starter(100,bars).encode());ok(old.hits.size()>0);
  }
  for(String broken:new String[]{"v3|100|4|0,-1,8,100","v3|100|4|0,0,0,90","v3|100|4|8,0,10,90","v3|100|4|0,0,10,128","v3|100|4|0,0,10,bad"}){try{BeatPilotDrumRollData.decode(broken);throw new AssertionError("accepted bad MIDI");}catch(IllegalArgumentException expected){checks++;}}
  System.out.println(checks+" expressive drum MIDI checks passed");
 }
}

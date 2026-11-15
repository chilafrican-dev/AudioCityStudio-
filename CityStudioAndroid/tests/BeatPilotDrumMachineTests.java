package ug.audiocity.studio;
import java.util.*;
public class BeatPilotDrumMachineTests {
 static int checks=0;
 static void ok(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
 public static void main(String[] args){
  boolean[][] p=BeatPilotDrums.starter();ok(p.length==8);ok(p[0][0]);ok(p[1][4]);ok(p[3][2]);
  boolean[][] copy=BeatPilotDrums.copy(p);copy[0][0]=false;ok(p[0][0]);
  float[][] sounds=new float[8][];for(int i=0;i<8;i++){sounds[i]=new float[2400];sounds[i][0]=.3f;sounds[i][1]=.3f;}
  float[] out=BeatPilotDrums.render(100,p,sounds,1);ok(out.length>Core.RATE*2);ok(out[0]>.1f);
  float[] signal=new float[48000*2];for(int i=0;i<48000;i++){float z=(float)Math.sin(2*Math.PI*220*i/48000);signal[2*i]=z;signal[2*i+1]=z;}
  float[] longer=BeatPilotLoopStretch.stretch(signal,57600);ok(longer.length==115200);ok(Float.isFinite(longer[10000]));
  float[] shorter=BeatPilotLoopStretch.stretch(signal,40000);ok(shorter.length==80000);ok(Float.isFinite(shorter[20000]));
  System.out.println(checks+" BeatPilot drum/loop checks passed");
 }
}

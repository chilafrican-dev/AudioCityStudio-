package ug.audiocity.studio;
import java.util.*;
public final class SamplePlaybackRegressionTests {
 static int checks;static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static SampledInstruments.Pack pack(float[] data,boolean loop){return new SampledInstruments.Pack("test","test",new SampledInstruments.Region[]{new SampledInstruments.Region(0,127,0,127,60,0,data.length/2,loop,1,data,4800)});}
 static float[] play(SampledInstruments.Pack pack,int bend){SynthPoly synth=new SynthPoly(Synth.factory(0));synth.setSamplePack(pack);synth.message(224,bend&127,(bend>>7)&127);synth.message(144,60,127);float[] out=new float[Core.RATE/5*2];synth.render(out,out.length/2);return out;}
 static int crossings(float[] audio){int n=0;for(int i=1000;i<audio.length/2;i++)if(audio[(i-1)*2]<=0&&audio[i*2]>0)n++;return n;}
 public static void main(String[] args){
  float[] shortLoop=new float[128];Arrays.fill(shortLoop,.5f);float[] out=play(pack(shortLoop,true),8192);
  for(int i=300;i<1000;i++)ok(out[i*2]>.1f&&Math.abs(out[i*2]-out[300*2])<.001f,"short sample loop does not retrigger attack or pulse after each wrap");
  SynthPoly released=new SynthPoly(Synth.factory(0));released.setSamplePack(pack(shortLoop,true));released.message(144,60,127);released.render(new float[1024],512);released.message(128,60,0);released.render(new float[1024],512);ok(released.activeVoices>0,"looped note release is not cut at the next sample boundary");released.render(new float[12000],6000);ok(released.activeVoices==0,"looped note ends when its release envelope finishes");
  float[] tone=new float[Core.RATE*2];for(int i=0;i<Core.RATE;i++)tone[2*i]=tone[2*i+1]=(float)Math.sin(i*2*Math.PI*440/Core.RATE)*.4f;
  int normal=crossings(play(pack(tone,false),8192)),bent=crossings(play(pack(tone,false),16383));ok(bent>normal*1.08,"pitch bend changes sampled-instrument pitch");
  for(int x:new int[]{0x7f800000,0xff800000,0x7fc00000}){byte[] bytes={(byte)x,(byte)(x>>8),(byte)(x>>16),(byte)(x>>24)};ok(SampledInstruments.wavValue(bytes,0,32,3)==0,"non-finite float samples are silenced before DSP");}
  Map<String,String> gain=new HashMap<>();gain.put("volume","NaN");ok(SampledInstruments.gain(gain)==1,"invalid SFZ gain cannot poison playback");gain.put("volume","9999");ok(SampledInstruments.gain(gain)<=16,"extreme SFZ volume is bounded");
  System.out.println("SamplePlaybackRegressionTests passed "+checks+" checks");
 }
}

package ug.audiocity.studio;
import java.util.*;import java.util.concurrent.atomic.AtomicLong;
/** Regression checks for real pre-roll, safe gestures, and instrument continuity. */
public final class BeatWorkspaceTests{
 static int n;static void check(boolean b,String why){n++;if(!b)throw new AssertionError(why);}
 static void close(float a,float b,String why){check(Math.abs(a-b)<.000001f,why+": "+a+" vs "+b);}
 public static void main(String[] args)throws Exception{
  for(int bpm:new int[]{40,85,100,137,240}){
   long beat=Core.beat(bpm),cursor=beat*7+123;
   check(CountIn.target(cursor,false,beat*4,beat*8)==cursor,"no loop starts at exact cursor");
   check(CountIn.target(cursor,true,beat*4,beat*8)==beat*4,"loop starts at loop boundary");
   check(CountIn.target(cursor,true,0,0)==0,"whole-song loop starts at zero");
   long duration=CountIn.frames(bpm,4);check(duration==beat*4,"exactly four beats of pre-roll");
   AtomicLong clock=new AtomicLong(cursor-duration);MidiTake t=new MidiTake(Synth.factory(3),bpm,clock::get,cursor);
   t.add(144,60,100);check(t.pending()&&t.events.isEmpty(),"no recorded notes during pre-roll");
   for(int k=0;k<4;k++){
    check(CountIn.number(k*beat,bpm,4)==k+1,"visible 1 2 3 4");float[] samples=new float[1024];CountIn.render(samples,512,k*beat,bpm,1);
    double energy=0;for(float f:samples)energy+=f*f;check(energy>0,"each click is audible");
   }
   clock.set(cursor);t.add(144,60,100);check(!t.pending()&&t.events.get(0).frame==0,"first take sample belongs to exact song target");
   clock.set(cursor+beat*12);check(t.elapsedFrames()==beat*12,"recording clock stays continuous across loops");
  }
  ArrangeGesture g=new ArrangeGesture();g.begin(0,100,100,10,true);check(g.move(100,140,101)==ArrangeGesture.PAN,"quick swipe pans instead of moving clip");
  check(g.move(900,200,101)==ArrangeGesture.PAN,"swipe cannot turn into edit later");
  g.begin(0,100,100,10,true);check(g.move(500,105,100)==ArrangeGesture.WAIT,"finger jitter does not edit");check(g.move(600,140,101)==ArrangeGesture.EDIT,"held clip can be deliberately moved");
  g.begin(0,100,100,10,false);check(g.move(600,140,101)==ArrangeGesture.PAN,"Select mode never moves clips");
  g.begin(0,100,100,10,true);g.pan();check(g.move(600,140,101)==ArrangeGesture.PAN,"pinch disables clip editing");
  float scroll=ArrangeGesture.zoomScroll(250,120,40,80);close((scroll+120)/80f,(250+120)/40f,"pinch keeps timeline point under fingers");
  List<Pro.Note> notes=new ArrayList<>();notes.add(new Pro.Note(60,110,0,12000,0));notes.add(new Pro.Note(67,100,18000,14000,0));
  for(int patch:new int[]{0,1,3,7,10}){
   Core.Clip c=ProMidi.clip("test",notes,Synth.factory(patch),48000,60000);
   float[] expected=new float[100000];for(int f=0;f<50000;f++){expected[f*2]=c.source.sample(f,0);expected[f*2+1]=c.source.sample(f,1);}
   for(int f:new int[]{0,3000,15000,21000,35000,49000,1,21000})for(int ch=0;ch<2;ch++)close(c.source.sample(f,ch),expected[f*2+ch],"same synth sound after seek/restart "+patch);
   Core.Clip copy=c.copy();check(c.source!=copy.source,"duplicate has an independent playback cursor");
   int size=c.source.frames();for(int k=0;k<10;k++){copy=copy.copy();ProMidi.refresh(copy);}check(copy.source.frames()==size,"copy/undo/refresh does not grow release duration");
   for(int f:new int[]{4000,2000,19000,0}){close(copy.source.sample(f,0),expected[f*2],"duplicate retains original instrument");close(c.source.sample(f,1),expected[f*2+1],"interleaved readers retain original sound");}
  }
  float[] wav=new float[120000];for(int f=0;f<wav.length/2;f++)wav[f*2]=wav[f*2+1]=(float)(Math.sin(f*.11)*.4);
  SampledInstruments.Pack pack=SampledInstruments.monoPack("piano",wav,60,"test");
  Core.Clip c=ProMidi.clip("sampled",notes,Synth.factory(3),pack,"factory-test/piano.sfz",48000,60000);
  float[] before=new float[80000];for(int f=0;f<40000;f++){before[f*2]=c.source.sample(f,0);before[f*2+1]=c.source.sample(f,1);}
  c.start=0;Core.Clip moved=c.copy();check(moved.samplePack==pack&&moved.instrumentPackId.equals("factory-test/piano.sfz"),"moving to beginning keeps sample identity");
  for(int f:new int[]{3000,15000,21000,35000,0})for(int ch=0;ch<2;ch++)close(moved.source.sample(f,ch),before[f*2+ch],"sample attacks and release tails survive seeks");
  System.out.println("BeatWorkspaceTests passed "+n+" checks");
 }
}

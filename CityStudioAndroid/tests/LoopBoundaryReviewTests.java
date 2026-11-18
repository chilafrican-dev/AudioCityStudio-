package ug.audiocity.studio;
/** Fractional-frame musical boundaries must not silently add an extra bar. */
public final class LoopBoundaryReviewTests {
 static int n;static void ok(boolean b,String why){n++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  Core.Session s=new Core.Session();for(int bpm=40;bpm<=240;bpm++){s.bpm=bpm;Pro.TempoMap map=new Pro.TempoMap(s);for(int bars:new int[]{1,2,4,8}){long frame=map.frameAt(bars*4);ok(TakeBounds.barEnd(s,frame)==frame,"exact rounded bar boundary at "+bpm+" BPM, "+bars+" bars");ok(TakeBounds.barEnd(s,frame-1)==frame,"sample before boundary stays in bar");ok(TakeBounds.barEnd(s,frame+1)==map.frameAt((bars+1)*4),"sample after boundary advances one bar");}}
  s.bpm=93;final int frames=(int)Math.round(Core.RATE*60.0/93*4);Core.Track t=new Core.Track("Pattern",0);t.clips.add(new Core.Clip("Pattern",new Core.Source(){public int frames(){return frames;}public float sample(int f,int c){return 0;}},"",0));s.tracks.add(t);ok(TakeBounds.loopEnd(s)==frames,"93 BPM whole-song loop has no extra silent bar");
  s.loopStart=400;s.loopEnd=800;ok(TakeBounds.loopEnd(s)==800,"explicit user loop bounds are preserved");System.out.println("LoopBoundaryReviewTests passed "+n+" checks");
 }
}

package ug.audiocity.studio;
import java.util.*;
public final class WorkflowRegressionTests {
 static int checks;static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  String test=args.length==0||args[0].equals("assets")?"all":args[0];
  if(test.equals("all")||test.equals("preview"))preview();
  if(test.equals("all")||test.equals("templates"))templates();
  if(test.equals("all")||test.equals("controls"))controls();
  System.out.println("WorkflowRegressionTests passed "+checks+" checks");
 }
 static void preview()throws Exception{
  Core.Session s=new Core.Session();Core.Track real=new Core.Track("Song",0);float[] silent=new float[2048];real.clips.add(new Core.Clip("Song",new Core.Memory(silent),"song.pcm",0));s.tracks.add(real);
  Core.Track preview=new Core.Track("Drum preview",0);preview.ephemeral=true;float[] tone=new float[2048];Arrays.fill(tone,.4f);preview.clips.add(new Core.Clip("Preview",new Core.Memory(tone),"temporary.pcm",0));s.tracks.add(preview);
  Core.Session history=s.copy();ok(history.tracks.size()==1&&!history.tracks.get(0).ephemeral,"Undo/save/export snapshot must exclude temporary preview track");
  preview.clips.clear();ok(history.tracks.size()==1,"closing drums cannot leave a ghost preview in undo");
  preview.clips.add(new Core.Clip("Preview",new Core.Memory(tone),"temporary.pcm",0));
  ok(!ProjectIO.files(s).contains("temporary.pcm"),"portable backup excludes unsaved preview assets");
  final double[] energy={0};Pro.renderOffline(s,1024,(audio,n)->{for(int i=0;i<n*2;i++)energy[0]+=audio[i]*audio[i];});
  ok(energy[0]==0,"offline render must not mix temporary drum monitoring into the saved song");
  Core.Clip reference=new Core.Clip("Reference",new Core.Memory(silent),"reference.pcm",0);reference.midiFile="reference.mid";reference.tuneSourceFile="reference-dry.pcm";reference.stretchFile="reference-original.pcm";s.reference=reference;
  ok(ProjectIO.files(s).containsAll(Arrays.asList("song.pcm","reference.pcm","reference.mid","reference-dry.pcm","reference-original.pcm")),"backup keeps every referenced source");
  reference.file="";ok(!ProjectIO.files(s).contains(""),"live reference does not add a directory as a backup file");
 }
 static void templates()throws Exception{
  for(int snap:new int[]{-2,-1,0,1,2,4,8,16}){
   Core.Session s=new Core.Session();s.snapDivision=snap;s.tracks.add(new Core.Track("Keys",0));
   Core.Session loaded=ProjectIO.fromTemplate(ProjectIO.template(s),"unused");ok(loaded.snapDivision==snap,"template must preserve Snap "+snap);
  }
 }
 static void controls(){
  Core.Session s=new Core.Session();Core.Track t=new Core.Track("Track",0);s.tracks.add(t);
  for(String target:new String[]{"mute","solo"}){
   ProLive.Mapping cc=new ProLive.Mapping(0,12,false,"track:"+t.id+":"+target);
   ProLive.apply(s,cc,127);ok(target.equals("mute")?t.mute:t.solo,"MIDI CC enables "+target);
   ProLive.apply(s,cc,0);ok(!(target.equals("mute")?t.mute:t.solo),"MIDI CC disables "+target);
   ProLive.apply(s,cc,64);ok(target.equals("mute")?t.mute:t.solo,"CC high threshold enables "+target);
   ProLive.apply(s,cc,63);ok(!(target.equals("mute")?t.mute:t.solo),"CC low threshold disables "+target);
   ProLive.Mapping pad=new ProLive.Mapping(0,12,true,"track:"+t.id+":"+target);
   ProLive.apply(s,pad,100);ok(target.equals("mute")?t.mute:t.solo,"pad toggles "+target+" on");ProLive.apply(s,pad,100);ok(!(target.equals("mute")?t.mute:t.solo),"pad toggles "+target+" off");
  }
 }
}

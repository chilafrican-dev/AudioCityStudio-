package ug.audiocity.studio;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** End-to-end ordinary-save tests for generated lane audio, routing and project-owned sample packs. */
public final class AiDrumTracksTests {
    static int checks;
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    static float[][] kit(){float[][] waves=new float[8][];for(int lane=0;lane<8;lane++){waves[lane]=new float[4800];for(int i=0;i<2400;i++)waves[lane][i*2]=waves[lane][i*2+1]=(float)(Math.sin(i*(.09+lane*.02))*(.12+lane*.01));}return waves;}
    static String[] titles(){String[] names=new String[8];for(int i=0;i<8;i++)names[i]="My imported "+AiDrumTracks.NAMES[i];return names;}
    static String[] credits(){String[] names=new String[8];for(int i=0;i<8;i++)names[i]="Original user-pack attribution "+i;return names;}
    static void clean(File file){File[] children=file.listFiles();if(children!=null)for(File f:children)clean(f);file.delete();}
    public static void main(String[] args)throws Exception{
        File dir=Files.createTempDirectory("ai-lane-roundtrip").toFile();
        try{
            check(AiDrumTracks.requiredTracks(true,true,true,true)==11,"eight drums plus three musical tracks");
            check(AiDrumTracks.requiredTracks(false,false,false,true)==8,"all eight lanes counted before commit");
            check(AiDrumTracks.requiredTracks(false,true,false,false)==1,"drums remain optional");
            Core.Session project=new Core.Session();project.directory=dir.getAbsolutePath();project.bpm=120;
            List<Pro.Note> notes=Arrays.asList(new Pro.Note(36,110,0,1000,9),new Pro.Note(37,100,1200,700,9),new Pro.Note(39,90,600,500,9));
            List<Core.Track> tracks=AiDrumTracks.prepare(dir,notes,24000,96000,kit(),titles(),credits());
            check(project.tracks.isEmpty(),"worker prepares without mutating the project");project.tracks.addAll(tracks);
            Set<String> ids=new HashSet<>(),files=new HashSet<>();
            for(int lane=0;lane<8;lane++){
                Core.Track track=tracks.get(lane);Core.Clip clip=track.clips.get(0);
                check(track.name.equals("AI · "+AiDrumTracks.NAMES[lane]),"separate named track");check(ids.add(track.id),"independent mixer identity");
                check(clip.start==24000&&clip.length==96000&&clip.offset==0,"exact visible clip bounds without release-tail growth");
                check(clip.instrumentFile!=null&&new File(dir,clip.instrumentFile).isFile(),"sample pack saved before ordinary project save");
                check(ProjectIO.files(project).contains(clip.instrumentFile),"selected sample travels with backup");files.add(clip.instrumentFile);
                check(clip.samplePack.regions.length==1&&clip.samplePack.regions[0].root==36+lane,"one-shot keeps drum pitch and lane mapping");
                for(Pro.Note note:clip.notes)check(note.pitch==36+lane&&note.channel==9,"notes never leak into another drum lane");
                check(clip.samplePack.name.equals(titles()[lane])&&clip.samplePack.source.equals(credits()[lane]),"personal sound name and attribution preserved");
            }
            check(files.size()==8,"lane samples independently portable");
            check(tracks.get(2).clips.get(0).notes.isEmpty(),"empty clap remains available for editing");
            ProjectIO.save(project); // Ordinary Save, deliberately no InstrumentArchive.embed or backup call.
            Core.Session reopened=ProjectIO.load(dir,f->{throw new IOException("Unexpected printed media");});
            List<String> missing=ProjectInstruments.prepare(reopened,clip->{throw new IOException("No global sample library installed");});
            check(missing.isEmpty(),"ordinary reopen needs no globally installed pack");
            for(int lane=0;lane<8;lane++){
                Core.Clip before=tracks.get(lane).clips.get(0),after=reopened.tracks.get(lane).clips.get(0);
                check(after.instrumentFile.equals(before.instrumentFile),"ordinary reopen retains portable file");
                check(after.samplePack.name.equals(titles()[lane])&&after.samplePack.source.equals(credits()[lane]),"ordinary reopen keeps selected sound and credits");
                for(int frame:new int[]{0,1,100,600,1200,1800,2399,3000})for(int ch=0;ch<2;ch++)check(Math.abs(before.source.sample(frame,ch)-after.source.sample(frame,ch))<1e-7,"reopened lane audio matches byte-derived samples");
            }
            // Repeat generation reuses content-addressed pack files; sample memory is not bundled into APKs.
            List<Core.Track> second=AiDrumTracks.prepare(dir,notes,0,96000,kit(),titles(),credits());
            for(int i=0;i<8;i++)check(second.get(i).clips.get(0).instrumentFile.equals(tracks.get(i).clips.get(0).instrumentFile),"same sound reuses archive");
            // Only kick contributes in this window; muting/soloing the independent channel changes only that path.
            Core.Renderer normal=new Core.Renderer(project);float[] mixed=new float[512];normal.render(24000,256,mixed,false);double energy=0;for(float x:mixed)energy+=Math.abs(x);check(energy>0,"kick is audible before other lanes start");
            tracks.get(0).mute=true;Core.Renderer mute=new Core.Renderer(project);mute.render(24000,256,mixed,false);energy=0;for(float x:mixed)energy+=Math.abs(x);check(energy==0,"muting kick leaves other drum lanes independent");tracks.get(0).mute=false;
            tracks.get(2).solo=true;Core.Renderer solo=new Core.Renderer(project);solo.render(24000,256,mixed,false);energy=0;for(float x:mixed)energy+=Math.abs(x);check(energy==0,"solo on an empty clap does not play kick/snare/hat");
            // Recover factory-only AI clips created by the previous Claude build.
            Core.Session legacy=new Core.Session();legacy.directory=dir.getAbsolutePath();Core.Track old=new Core.Track("AI · Drums",1);legacy.tracks.add(old);
            float[] one=kit()[0];SampledInstruments.Pack original=SampledInstruments.monoPack("Legacy factory kit",one,36,"Factory attribution");
            old.clips.add(ProMidi.clip("AI · Drums",Arrays.asList(new Pro.Note(36,100,0,1000,9)),Synth.factory(0),original,"embedded:ai-drum-pads",0,24000));ProjectIO.save(legacy);
            Core.Session restored=ProjectIO.load(dir,f->{throw new IOException("Unexpected media");});
            ProjectInstruments.prepare(restored,c->{check(c.instrumentPackId.equals("embedded:ai-drum-pads"),"legacy resolver receives known ID");return original;});
            check(AiDrumTracks.embedLegacy(restored)==1,"recovered legacy kit persisted");check(AiDrumTracks.embedLegacy(restored)==0,"migration is idempotent");ProjectIO.save(restored);
            Core.Session twice=ProjectIO.load(dir,f->{throw new IOException("Unexpected media");});
            check(ProjectInstruments.prepare(twice,c->{throw new IOException("No global kit");}).isEmpty(),"recovered kit survives subsequent ordinary reopen");
            double sound=0;for(int i=0;i<256;i++)sound+=Math.abs(twice.tracks.get(0).clips.get(0).source.sample(i,0));check(sound>0,"legacy AI drums remain audible");
            try{AiDrumTracks.prepare(dir,Arrays.asList(new Pro.Note(60,100,0,10,9)),0,1000,kit(),titles(),credits());throw new AssertionError("Invalid pitch accepted");}catch(IllegalArgumentException expected){checks++;}
            try{AiDrumTracks.prepare(dir,notes,Core.MAX_FRAMES,1000,kit(),titles(),credits());throw new AssertionError("Project overflow accepted");}catch(IllegalArgumentException expected){checks++;}
            float[][] bad=kit();bad[7][0]=Float.NaN;
            try{AiDrumTracks.prepare(dir,notes,0,96000,bad,titles(),credits());throw new AssertionError("NaN accepted");}catch(IllegalArgumentException expected){checks++;}
            System.out.println("AiDrumTracksTests passed: "+checks+" checks");
        }finally{clean(dir);}
    }
}

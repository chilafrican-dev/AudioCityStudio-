package ug.audiocity.studio;
import java.io.*;import java.util.*;
/** Prepare saved MIDI instruments before enabling project playback, sharing each pack's sample buffers. */
public final class ProjectInstruments {
 private ProjectInstruments(){}
 private static final class MissingSource implements Core.Source {final int frames;MissingSource(Core.Clip clip){frames=(int)Math.min(Core.MAX_FRAMES,Math.max(clip.envelopeLength,clip.offset+(long)clip.length));}public int frames(){return frames;}public float sample(int frame,int channel){return 0;}}
 public interface Resolver {SampledInstruments.Pack load(Core.Clip clip)throws IOException;}
 public static List<String> prepare(Core.Session project,Resolver resolver){
  ArrayList<Core.Clip> clips=new ArrayList<>();for(Core.Track track:project.tracks){clips.addAll(track.clips);clips.addAll(track.takes);for(Core.Clip clip:track.slots)if(clip!=null)clips.add(clip);}if(project.reference!=null)clips.add(project.reference);
  Map<String,SampledInstruments.Pack> loaded=new HashMap<>();LinkedHashMap<String,String> failed=new LinkedHashMap<>();
  for(Core.Clip clip:clips)if(clip.notes!=null&&clip.instrumentPackId!=null&&clip.samplePack!=null)loaded.put(InstrumentArchive.key(clip),clip.samplePack);
  for(Core.Clip clip:clips){
   if(clip.notes==null||clip.instrumentPackId==null||clip.samplePack!=null)continue;String id=clip.instrumentPackId,key=InstrumentArchive.key(clip);
   try{
    if(failed.containsKey(key))throw new IOException("Instrument unavailable");
    SampledInstruments.Pack pack=loaded.get(key);if(pack==null){if(clip.instrumentFile!=null&&project.directory!=null)try{pack=InstrumentArchive.read(new File(project.directory),clip.instrumentFile);}catch(IOException unavailable){}if(pack==null)pack=resolver.load(clip);if(pack==null)throw new IOException("Instrument unavailable");loaded.put(key,pack);}
    clip.samplePack=pack;ProMidi.refresh(clip);
   }catch(IOException error){failed.put(key,id);clip.source=new MissingSource(clip);}
  }
  return new ArrayList<>(failed.values());
 }
}

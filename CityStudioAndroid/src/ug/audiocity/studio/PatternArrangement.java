package ug.audiocity.studio;
import java.io.*;import java.util.*;
/** Prints independent drum lanes and links their clips to a reusable pattern. Call off the UI thread. */
public final class PatternArrangement {
 private PatternArrangement(){}
 private static final int[] COLORS={0xff72bca0,0xffa2b9dc,0xffd9a3b2,0xffd4bd83,0xffa4ba82,0xffbd9dd4,0xff81bfc8,0xffd4a47f};
 static Core.Track laneTrack(Core.Session s,int lane){for(Core.Track t:s.tracks)if(!t.ephemeral)for(Core.Clip c:t.clips)if(c.patternId!=null&&c.patternLane==lane)return t;return null;}
 public static int requiredTracks(Core.Session s){int count=0;for(int l=0;l<8;l++)if(laneTrack(s,l)==null)count++;return count;}
 /** Tracks needed for the lanes this pattern actually uses: empty lanes never get a track. */
 public static int requiredTracks(Core.Session s,BeatPilotDrumPattern pattern){int count=0;for(int l=0;l<8;l++)if(!PatternLibrary.empty(PatternLibrary.lane(pattern,l))&&laneTrack(s,l)==null)count++;return count;}
 private static boolean full(Core.Clip clip,long frames){return clip.offset==0&&Math.abs(clip.length-frames)<=1;}
 private static long visibleLength(Core.Clip clip,long oldFrames,long newFrames){return full(clip,oldFrames)?newFrames:Math.max(1,Math.min(clip.length,newFrames-Math.min(clip.offset,newFrames-1)));}
 private static void validate(Core.Session s){if(s.directory==null)throw new IllegalArgumentException("Project folder is missing");if(s.timeSignature!=4||!s.tempoChanges.isEmpty())throw new IllegalArgumentException("The pattern rack currently needs 4/4 and one project tempo");}
 private static boolean overlaps(long a,long lengthA,long b,long lengthB){return a<b+lengthB&&b<a+lengthA;}
 /** Keep a run of full repeated instances consecutive when its pattern changes length. */
 private static Map<Core.Clip,Long> startPlan(Core.Session s,String id,long oldFrames,long newFrames,boolean repairOnly){
  Map<Core.Clip,Long> planned=new IdentityHashMap<>();
  for(Core.Track track:s.tracks)for(int lane=0;lane<8;lane++){
   ArrayList<Core.Clip> group=new ArrayList<>();for(Core.Clip clip:track.clips)if(id.equals(clip.patternId)&&clip.patternLane==lane&&full(clip,oldFrames))group.add(clip);
   group.sort(Comparator.comparingLong(c->c.start));long originalEnd=-1,plannedEnd=-1;
   for(Core.Clip clip:group){long at=clip.start;boolean connected=originalEnd>=0&&(repairOnly?at<originalEnd:at<=originalEnd+1);if(connected)at=plannedEnd;else if(plannedEnd>at)throw new IllegalArgumentException("The longer pattern would overlap a separately positioned copy. Move that copy to a free bar first.");planned.put(clip,at);originalEnd=clip.start+oldFrames;plannedEnd=at+newFrames;}
  }return planned;
 }
 private static void validatePlan(Core.Session s,Map<Core.Clip,Long> starts,Map<Core.Clip,Long> lengths){
  for(Core.Track track:s.tracks)for(Core.Clip clip:track.clips)if(starts.containsKey(clip)||lengths.containsKey(clip)){
   long at=starts.getOrDefault(clip,clip.start),length=lengths.getOrDefault(clip,(long)clip.length);if(at<0||at+length>Core.MAX_FRAMES)throw new IllegalArgumentException("The re-spaced pattern would pass the project limit. Shorten the repeats or move them earlier.");
   for(Core.Clip other:track.clips)if(other!=clip){long otherAt=starts.getOrDefault(other,other.start),otherLength=lengths.getOrDefault(other,(long)other.length);if(overlaps(at,length,otherAt,otherLength)&&!overlaps(clip.start,clip.length,other.start,other.length))throw new IllegalArgumentException("The longer pattern would overlap another clip on "+track.name+". Move that section to a free bar first.");}
  }
 }
 /** Repair already-saved overlapping full pattern copies without reprinting or deleting anything. */
 public static int repairOverlaps(Core.Session s){synchronized(s){validate(s);Map<Core.Clip,Long> starts=new IdentityHashMap<>();for(PatternLibrary.Entry entry:s.patterns){BeatPilotDrumPattern p=PatternLibrary.pattern(s,entry);long frames=Math.round(Core.RATE*60.0/s.bpm*4*p.bars);starts.putAll(startPlan(s,entry.id,frames,frames,true));}validatePlan(s,starts,Collections.emptyMap());int changed=0;for(Map.Entry<Core.Clip,Long> entry:starts.entrySet())if(entry.getKey().start!=entry.getValue()){entry.getKey().start=entry.getValue();changed++;}return changed;}}
 private static Core.Clip[] render(Core.Session s,PatternLibrary.Entry entry,BeatPilotDrumPattern pattern,float[][] samples,List<File> made)throws IOException{
  Core.Clip[] lanes=new Core.Clip[8];Core.Clip silence=null;
  for(int lane=0;lane<8;lane++){
   BeatPilotDrumPattern part=PatternLibrary.lane(pattern,lane);Core.Clip clip;
   if(PatternLibrary.empty(part)&&silence!=null)clip=silence.copy();
   else{clip=AudioIO.store(new File(s.directory),part.render(samples),entry.name,0);made.add(new File(s.directory,clip.file));String midi=UUID.randomUUID()+".mid";try(OutputStream out=new FileOutputStream(new File(s.directory,midi))){part.writeMidi(out);}made.add(new File(s.directory,midi));clip.midiFile=midi;if(PatternLibrary.empty(part))silence=clip;}
   clip.name=entry.name;clip.patternId=entry.id;clip.patternLane=lane;clip.drumPattern=part.encode();clip.notes=part.notes();lanes[lane]=clip;
  }return lanes;
 }
 public static List<Core.Clip> place(Core.Session s,String id,float[][] samples,long cursor,int repeats)throws IOException{
  validate(s);if(repeats<1||repeats>8)throw new IllegalArgumentException("Place one to eight repeats");PatternLibrary.Entry entry=PatternLibrary.find(s,id);if(entry==null)throw new IllegalArgumentException("Pattern not found");BeatPilotDrumPattern pattern=PatternLibrary.pattern(s,entry);if(PatternLibrary.empty(pattern))throw new IllegalArgumentException("Add at least one step before placing this pattern");
  if(s.tracks.size()+requiredTracks(s,pattern)>Core.MAX_TRACKS)throw new IllegalArgumentException("Not enough free tracks for this pattern's drum lanes");
  long bar=Math.round(Core.RATE*60.0/s.bpm*4),at=PerformanceChords.nextBar(cursor,bar),length=Math.round(Core.RATE*60.0/s.bpm*4*pattern.bars);if(at+length*repeats>Core.MAX_FRAMES)throw new IllegalArgumentException("Pattern would pass the project limit");
  // Reject partial overlaps too, rather than only exactly duplicated starts.
  for(int lane=0;lane<8;lane++){Core.Track target=laneTrack(s,lane);if(target!=null)for(Core.Clip clip:target.clips)if(id.equals(clip.patternId)&&overlaps(at,length*repeats,clip.start,clip.length))throw new IllegalArgumentException("This pattern overlaps an existing copy. Move the cursor after its end or choose a free bar.");}
  ArrayList<File> made=new ArrayList<>();try{
   Core.Clip[] sources=render(s,entry,pattern,samples,made);ArrayList<Core.Clip> placed=new ArrayList<>();
   for(int lane=0;lane<8;lane++){if(PatternLibrary.empty(PatternLibrary.lane(pattern,lane)))continue;Core.Track track=laneTrack(s,lane);if(track==null){track=new Core.Track(BeatPilotDrums.LABELS[lane],COLORS[lane]);s.tracks.add(track);}for(int repeat=0;repeat<repeats;repeat++){Core.Clip clip=sources[lane].copy();clip.start=at+length*repeat;track.clips.add(clip);placed.add(clip);}}
   return placed;
  }catch(Exception e){for(File file:made)file.delete();if(e instanceof IOException)throw(IOException)e;throw new IOException(e.getMessage(),e);}
 }
 /** After an edit: lanes that gained steps get clips at every placed copy; lanes that became empty lose theirs. */
 private static void syncLanes(Core.Session s,String id,BeatPilotDrumPattern pattern,Core.Clip[] sources,Map<Core.Clip,Core.Clip> replacements){
  boolean[] used=new boolean[8];for(int l=0;l<8;l++)used[l]=!PatternLibrary.empty(PatternLibrary.lane(pattern,l));
  TreeMap<Long,Core.Clip> copies=new TreeMap<>();boolean[][] has=null;ArrayList<Long> starts=new ArrayList<>();
  for(Core.Track t:s.tracks)for(Core.Clip c:t.clips)if(id.equals(c.patternId)&&c.patternLane>=0&&c.patternLane<8&&!copies.containsKey(c.start))copies.put(c.start,c);
  starts.addAll(copies.keySet());has=new boolean[starts.size()][8];
  for(Core.Track t:s.tracks)for(Core.Clip c:t.clips)if(id.equals(c.patternId)&&c.patternLane>=0&&c.patternLane<8){int i=starts.indexOf(c.start);if(i>=0)has[i][c.patternLane]=true;}
  for(int lane=0;lane<8;lane++){
   if(used[lane]){for(int i=0;i<starts.size();i++)if(!has[i][lane]){Core.Track track=laneTrack(s,lane);if(track==null){if(s.tracks.size()>=Core.MAX_TRACKS)continue;track=new Core.Track(BeatPilotDrums.LABELS[lane],COLORS[lane]);s.tracks.add(track);}Core.Clip ref=copies.get(starts.get(i));Core.Clip clip=sources[lane].copy();clip.start=starts.get(i);clip.offset=ref.offset;clip.length=ref.length;track.clips.add(clip);}}
   else{final int gone=lane;for(Core.Track t:s.tracks)t.clips.removeIf(c->id.equals(c.patternId)&&c.patternLane==gone);}
  }
  for(int lane=0;lane<8;lane++)if(!used[lane])for(Core.Track t:new ArrayList<>(s.tracks))if(t.clips.isEmpty()&&t.name.equals(BeatPilotDrums.LABELS[lane])&&t.color==COLORS[lane])s.tracks.remove(t);
 }
 public static Map<Core.Clip,Core.Clip> save(Core.Session s,String id,String name,BeatPilotDrumPattern pattern,float[][] samples)throws IOException{synchronized(s){return saveLocked(s,id,name,pattern,samples);}}
 private static Map<Core.Clip,Core.Clip> saveLocked(Core.Session s,String id,String name,BeatPilotDrumPattern pattern,float[][] samples)throws IOException{
  validate(s);PatternLibrary.Entry previous=PatternLibrary.find(s,id);if(previous==null)throw new IllegalArgumentException("Pattern not found");PatternLibrary.Entry next=new PatternLibrary.Entry(id,name,pattern.encode());long length=Math.round(Core.RATE*60.0/pattern.bpm*4*pattern.bars),oldFrames=Math.round(Core.RATE*60.0/s.bpm*4*PatternLibrary.pattern(s,previous).bars);boolean placed=false;
  Map<Core.Clip,Long> starts=startPlan(s,id,oldFrames,length,false),lengths=new IdentityHashMap<>();
  for(Core.Track t:s.tracks)for(Core.Clip c:t.clips)if(id.equals(c.patternId)&&c.patternLane>=0&&c.patternLane<8){placed=true;lengths.put(c,visibleLength(c,oldFrames,length));}
  if(!placed){PatternLibrary.replace(s,id,name,pattern);return Collections.emptyMap();}
  validatePlan(s,starts,lengths);
  ArrayList<File> made=new ArrayList<>();try{
   Core.Clip[] sources=render(s,next,pattern,samples,made);Map<Core.Clip,Core.Clip> replacements=new IdentityHashMap<>();
   for(Core.Track t:s.tracks)for(int i=0;i<t.clips.size();i++){Core.Clip old=t.clips.get(i);if(!id.equals(old.patternId)||old.patternLane<0||old.patternLane>=8)continue;Core.Clip clip=sources[old.patternLane].copy();clip.start=starts.getOrDefault(old,old.start);if(!full(old,oldFrames)){clip.offset=(int)Math.min(old.offset,length-1);clip.length=(int)visibleLength(old,oldFrames,length);clip.envelopeOffset=Math.min(old.envelopeOffset,clip.envelopeLength-clip.length);}clip.gain=old.gain;clip.fadeIn=old.fadeIn;clip.fadeOut=old.fadeOut;t.clips.set(i,clip);replacements.put(old,clip);}
   syncLanes(s,id,pattern,sources,replacements);
   PatternLibrary.replace(s,id,name,pattern);return replacements;
  }catch(Exception e){for(File file:made)file.delete();if(e instanceof IOException)throw(IOException)e;throw new IOException(e.getMessage(),e);}
 }
}

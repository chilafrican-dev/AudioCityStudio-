package ug.audiocity.studio;
import java.util.*;
/** Project-owned, reusable drum patterns. New patterns are empty; generation is always explicit. */
public final class PatternLibrary {
 public static final int MAX_PATTERNS=32;private PatternLibrary(){}
 public static final class Entry {public final String id,name,data;public Entry(String id,String name,String data){if(id==null||id.isEmpty()||id.length()>64)throw new IllegalArgumentException("Invalid pattern ID");BeatPilotDrumPattern.decode(data);this.id=id;this.name=title(name);this.data=data;}public Entry copy(){return new Entry(id,name,data);}}
 private static String title(String value){String name=value==null?"":value.trim();if(name.isEmpty())name="Pattern";return name.substring(0,Math.min(40,name.length()));}
 public static Entry find(Core.Session s,String id){for(Entry entry:s.patterns)if(entry.id.equals(id))return entry;return null;}
 public static Entry add(Core.Session s,String name,BeatPilotDrumPattern pattern){if(s.patterns.size()>=MAX_PATTERNS)throw new IllegalArgumentException("A project can have up to 32 patterns");Entry entry=new Entry(UUID.randomUUID().toString(),name,pattern.encode());s.patterns.add(entry);return entry;}
 public static Entry blank(Core.Session s){return add(s,"Pattern "+(s.patterns.size()+1),new BeatPilotDrumPattern(s.bpm,1));}
 public static Entry duplicate(Core.Session s,String id){Entry entry=find(s,id);if(entry==null)throw new IllegalArgumentException("Pattern not found");return add(s,entry.name+" copy",pattern(s,entry));}
 public static BeatPilotDrumPattern pattern(Core.Session s,Entry entry){BeatPilotDrumPattern old=BeatPilotDrumPattern.decode(entry.data),next=new BeatPilotDrumPattern(s.bpm,old.bars);old.copySamplesTo(next);for(int b=0;b<old.bars;b++)for(int l=0;l<8;l++)for(int step=0;step<16;step++)next.set(b,l,step,old.get(b,l,step));return next;}
 public static Entry replace(Core.Session s,String id,String name,BeatPilotDrumPattern pattern){Entry entry=new Entry(id,name,pattern.encode());for(int i=0;i<s.patterns.size();i++)if(s.patterns.get(i).id.equals(id)){s.patterns.set(i,entry);return entry;}throw new IllegalArgumentException("Pattern not found");}
 public static BeatPilotDrumPattern lane(BeatPilotDrumPattern pattern,int lane){if(lane<0||lane>=8)throw new IllegalArgumentException("Invalid drum lane");BeatPilotDrumPattern result=new BeatPilotDrumPattern(pattern.bpm,pattern.bars);pattern.copySamplesTo(result);for(int b=0;b<pattern.bars;b++)for(int step=0;step<16;step++)result.set(b,lane,step,pattern.get(b,lane,step));return result;}
 public static boolean empty(BeatPilotDrumPattern pattern){for(int b=0;b<pattern.bars;b++)for(int l=0;l<8;l++)for(int step=0;step<16;step++)if(pattern.get(b,l,step)>0)return false;return true;}
}

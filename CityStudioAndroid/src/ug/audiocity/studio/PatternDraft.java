package ug.audiocity.studio;
import java.util.*;
/** Immutable draft captured on the UI thread and persisted on the serial project job queue. */
final class PatternDraft {
 final String id,name;private final BeatPilotDrumPattern data;
 PatternDraft(String id,String name,BeatPilotDrumPattern current){this.id=id;this.name=name;data=current.copy();}
 BeatPilotDrumPattern pattern(){return data.copy();}
 boolean matches(String currentId,String currentName,BeatPilotDrumPattern current){return id.equals(currentId)&&name.equals(currentName)&&data.encode().equals(current.encode());}
 Map<Core.Clip,Core.Clip> save(Core.Session target,float[][] kit)throws Exception{synchronized(target){Map<Core.Clip,Core.Clip> updated=PatternArrangement.save(target,id,name,data,kit);AudioIO.save(target.copy());return updated;}}
}

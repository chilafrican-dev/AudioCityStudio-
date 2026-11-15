package ug.audiocity.studio;
import java.util.*;
/** Reference-count overlapping finger notes so releasing a chord cannot cut another finger's note. */
public final class TouchNotes {
 public interface Output {void message(int status,int note,int velocity);}
 private final Output output;private final HashMap<Integer,Integer> cells=new HashMap<>();private final HashMap<Integer,int[]> notes=new HashMap<>();private final int[] holds=new int[128];
 public TouchNotes(Output output){this.output=output;}
 public boolean active(int cell){return cells.containsValue(cell);}
 public void change(int pointer,int cell,int[] pitches){if(cells.containsKey(pointer)&&cells.get(pointer)==cell)return;for(int note:pitches)if(note<0||note>127)throw new IllegalArgumentException("Touch note outside MIDI range");release(pointer);if(cell<0)return;cells.put(pointer,cell);notes.put(pointer,pitches.clone());for(int note:pitches)if(holds[note]++==0)output.message(144,note,100);}
 public void release(int pointer){int[] old=notes.remove(pointer);cells.remove(pointer);if(old!=null)for(int note:old)if(--holds[note]==0)output.message(128,note,0);}
 public void clear(){for(int pointer:new ArrayList<Integer>(notes.keySet()))release(pointer);}
}

package ug.audiocity.studio;
import java.io.*;import java.util.*;
/** Exports the current editable notes; legacy printed takes keep their original controller data. */
public final class MidiClipExport {
 private MidiClipExport(){}
 public static boolean available(Core.Clip clip){return clip!=null&&(clip.notes!=null||clip.midiFile!=null);}
 public static byte[] bytes(Core.Clip clip,File directory,int bpm)throws IOException{
  if(!available(clip))throw new IOException("This clip has no MIDI source");
  if(clip.notes!=null){
   ArrayList<Pro.Note> visible=new ArrayList<>();long from=clip.offset,to=from+clip.length;
   for(Pro.Note note:clip.notes){long a=Math.max(from,note.start),b=Math.min(to,note.end());if(b>a)visible.add(new Pro.Note(note.pitch,note.velocity,a-from,b-a,note.channel));}
   MidiTake take=Pro.take(clip.instrument==null?Synth.factory(0):clip.instrument,bpm,visible,null);ByteArrayOutputStream out=new ByteArrayOutputStream();take.writeMidi(out);return out.toByteArray();
  }
  File file=new File(directory,clip.midiFile);if(!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))throw new IOException("Invalid MIDI source path");
  ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
  byte[] bytes=out.toByteArray();if(bytes.length>29&&bytes[22]==0&&(bytes[23]&255)==255&&bytes[24]==81&&bytes[25]==3){int tempo=60000000/bpm;bytes[26]=(byte)(tempo>>16);bytes[27]=(byte)(tempo>>8);bytes[28]=(byte)tempo;}return bytes;
 }
}

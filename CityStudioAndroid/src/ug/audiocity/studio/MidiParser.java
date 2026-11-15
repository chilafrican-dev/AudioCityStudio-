package ug.audiocity.studio;
/** Handles MIDI 1.0 channel messages, running status and fragmented packets. */
public final class MidiParser {
 public interface Listener {void message(int status,int first,int second,long timestamp);}
 final Listener listener;int running,needed,received;int[] data=new int[2];boolean sysex;
 public MidiParser(Listener l){listener=l;}
 public synchronized void reset(){running=needed=received=0;sysex=false;}
 public synchronized void feed(byte[] bytes,int offset,int count,long timestamp){for(int i=offset;i<offset+count;i++){int value=bytes[i]&255;if(value>=248)continue;if(value==240){sysex=true;running=0;received=0;continue;}if(value==247){sysex=false;continue;}if(sysex)continue;if((value&128)!=0){received=0;if(value>=128&&value<240){running=value;needed=((value&240)==192||(value&240)==208)?1:2;}else running=0;continue;}if(running==0)continue;data[received++]=value;if(received==needed){listener.message(running,data[0],needed==2?data[1]:0,timestamp);received=0;}}}
}

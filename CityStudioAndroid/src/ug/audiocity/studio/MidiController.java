package ug.audiocity.studio;
import android.content.*;import android.media.midi.*;import android.bluetooth.*;import android.os.*;import java.io.*;
public final class MidiController {
 public interface Listener{void event(int status,int a,int b);void status(String message);}
 final MidiManager manager;final Handler handler;final Listener listener;MidiDevice device;MidiOutputPort port;public String name="Not connected";public volatile boolean connected;volatile boolean disposed;int generation;
 final MidiParser parser;final MidiReceiver receiver;MidiManager.DeviceCallback callback;
 public MidiController(Context context,Handler handler,Listener listener){this.handler=handler;this.listener=listener;manager=(MidiManager)context.getSystemService(Context.MIDI_SERVICE);callback=new MidiManager.DeviceCallback(){public void onDeviceRemoved(MidiDeviceInfo info){if(device!=null&&device.getInfo().getId()==info.getId()){close();listener.status("MIDI device disconnected.");}}};if(manager!=null)manager.registerDeviceCallback(callback,handler);parser=new MidiParser((s,a,b,t)->listener.event(s,a,b));receiver=new MidiReceiver(){public void onSend(byte[] data,int offset,int count,long timestamp){parser.feed(data,offset,count,timestamp);}};}
 public MidiDeviceInfo[] devices(){return manager==null?new MidiDeviceInfo[0]:manager.getDevices();}
 public void open(MidiDeviceInfo info,int output,String label){close();if(manager==null){listener.status("Android MIDI service is unavailable.");return;}final int ticket=++generation;try{manager.openDevice(info,opened->accept(opened,output,label,ticket),handler);}catch(Exception e){listener.status(e.getMessage());}}
 public void openBluetooth(BluetoothDevice bluetooth){close();if(manager==null){listener.status("Android MIDI service is unavailable.");return;}final int ticket=++generation;try{manager.openBluetoothDevice(bluetooth,opened->accept(opened,0,"Bluetooth MIDI controller",ticket),handler);}catch(Exception e){listener.status(e.getMessage());}}
 void accept(MidiDevice opened,int output,String label,int ticket){if(disposed||ticket!=generation){if(opened!=null)try{opened.close();}catch(Exception ignored){}return;}attach(opened,output,label);}
 void attach(MidiDevice opened,int output,String label){if(opened==null){listener.status("Could not open this MIDI device.");return;}try{device=opened;port=device.openOutputPort(output);if(port==null)throw new IOException("The device has no available MIDI note-output port.");port.connect(receiver);name=label;connected=true;listener.status("Connected: "+label);}catch(Exception e){close();listener.status(e.getMessage());}}
 public void dispose(){disposed=true;close();if(manager!=null&&callback!=null)manager.unregisterDeviceCallback(callback);}
 public void close(){generation++;parser.reset();connected=false;name="Not connected";try{if(port!=null)port.close();}catch(Exception ignored){}try{if(device!=null)device.close();}catch(Exception ignored){}port=null;device=null;}
}

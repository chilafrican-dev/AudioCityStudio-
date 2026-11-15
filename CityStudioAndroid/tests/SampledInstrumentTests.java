package ug.audiocity.studio;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;
public final class SampledInstrumentTests {
 static int checks;
 static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static byte[] wave(int rate,int bits,int frames){int size=frames*bits/8;ByteBuffer b=ByteBuffer.allocate(44+size).order(ByteOrder.LITTLE_ENDIAN);
  b.put("RIFF".getBytes());b.putInt(36+size);b.put("WAVEfmt ".getBytes());b.putInt(16);b.putShort((short)1);b.putShort((short)1);b.putInt(rate);b.putInt(rate*(bits/8));b.putShort((short)(bits/8));b.putShort((short)bits);b.put("data".getBytes());b.putInt(size);
  for(int i=0;i<frames;i++){double tone=Math.sin(i*2*Math.PI*440/rate)*.8;if(bits==16)b.putShort((short)Math.round(tone*30000));else{int x=(int)(tone*7500000);b.put((byte)x);b.put((byte)(x>>8));b.put((byte)(x>>16));}}return b.array();}
 static byte[] zip(String[][] namesAndContents,byte[] sound){try(ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream z=new ZipOutputStream(out)){
   for(String[] pair:namesAndContents){z.putNextEntry(new ZipEntry(pair[0]));if(pair[1].equals("_WAV_"))z.write(sound);else z.write(pair[1].getBytes("UTF-8"));z.closeEntry();}z.finish();return out.toByteArray();}catch(Exception e){throw new RuntimeException(e);}}
 public static void main(String[] args)throws Exception{
  File dir=Files.createTempDirectory("city-instrument-tests-").toFile();try{
   SampledInstruments library=new SampledInstruments(dir);
   byte[] wav=wave(44100,16,11025);
   int count=library.installWav(new ByteArrayInputStream(wav),"Melody.wav");
   require(count==1,"single WAV installed");SampledInstruments.Pack one=library.load(library.list().get(0));
   require(one.regions.length==1,"single-sample playable");require(one.regions[0].pcm.length>20000,"resampled to 48k");
   String sfz="<group> lovel=1 hivel=127\n<region> sample=tone.wav lokey=48 hikey=72 pitch_keycenter=60\n";
   byte[] zip=zip(new String[][]{{"instrument.sfz",sfz},{"tone.wav","_WAV_"},{"LICENSE.txt","CC0 demo fixture only"}},wav);
   library.installZip(new ByteArrayInputStream(zip),"test-pack.zip");
   require(library.list().size()==2,"SFZ installed");
   SampledInstruments.Info sfzInfo=null;for(SampledInstruments.Info i:library.list())if(i.filename.endsWith(".sfz"))sfzInfo=i;
   require(sfzInfo!=null,"SFZ appears in picker");SampledInstruments.Pack pack=library.load(sfzInfo);
   require(pack.regions.length==1,"region mapped");require(pack.find(60,90).root==60,"root note mapped");
   SynthPoly player=new SynthPoly(Synth.factory(0));player.setSamplePack(pack);player.message(0x90,60,120);
   float[] block=new float[1024];player.render(block,512);float energy=0;for(float v:block)energy+=v*v;
   require(energy>.002,"real WAV sounds via polyphonic voice");
   player.message(0x80,60,0);for(int i=0;i<30;i++)player.render(block,512);
   require(player.activeVoices==0,"voice released");
   MidiTake take=new MidiTake(Synth.factory(0),100,null,0,pack);take.add(0x90,60,110);Thread.sleep(75);take.add(0x80,60,0);take.finish();
   final int[] samples={0};final double[] magnitude={0};take.print((l,r)->{samples[0]++;magnitude[0]+=Math.abs(l)+Math.abs(r);});
   require(samples[0]>1000,"recorded take printed");require(magnitude[0]>5,"recorded take uses sample pack");
   try{library.installZip(new ByteArrayInputStream(zip(new String[][]{{"../hack.wav","_WAV_"}},wav)),"bad.zip");throw new AssertionError("ZIP traversal accepted");}catch(IOException expected){checks++;}
   // Regression: 44.1 kHz samples longer than ~1.1 seconds must not overflow
   // int multiplication during conversion to the 48 kHz engine rate.
   File realisticPianoNote=new File(dir,"44k-long-piano-note.wav");
   Files.write(realisticPianoNote.toPath(),wave(44100,16,55125));
   float[] resampled=SampledInstruments.readWav(realisticPianoNote);
   require(resampled.length==120000,"44.1k -> 48k resampled note length");
   require(resampled[100000] > -1.01f && resampled[100000] < 1.01f,"long 44.1 kHz sample decoded without overflow");
   // Verify the actual 12-note FreePats factory piano bundle, not a mock sound.
   File factory=new File(args.length>0?args[0]:"assets","instruments/freepats-fm-piano2.zip");
   require(factory.isFile(),"bundled FreePats piano asset is present");
   int before=library.list().size();
   try(InputStream input=new FileInputStream(factory)){library.installBundledZip(input,"freepats-fm-piano2");}
   require(library.list().size()==before+1,"factory piano installed once");
   try(InputStream input=new FileInputStream(factory)){library.installBundledZip(input,"freepats-fm-piano2");}
   require(library.list().size()==before+1,"reopening app does not create a duplicate piano");
   SampledInstruments.Info pianoInfo=null;
   for(SampledInstruments.Info i:library.list())if(i.name.contains("Audio City FM Electric Piano"))pianoInfo=i;
   require(pianoInfo!=null,"factory electric piano named and displayed in picker");
   SampledInstruments.Pack piano=library.load(pianoInfo);
   require(piano.regions.length==12,"original 12 multisample regions are loaded");
   require(piano.find(60,90).root==60,"middle C plays its mapped C4 piano sample");
   require(piano.find(30,90).root==30,"low F-sharp plays mapped source sample");
   require(piano.find(72,90).root==72,"higher C plays mapped source sample");
   require(piano.find(60,90).releaseFrames==Math.round(1.9*Core.RATE),"SFZ 1.9-second note release is honored");
   SynthPoly factoryPlayer=new SynthPoly(Synth.factory(0));factoryPlayer.setSamplePack(piano);
   factoryPlayer.message(0x90,60,110);float[] pianoBlock=new float[2048];factoryPlayer.render(pianoBlock,1024);
   float pianoEnergy=0;for(float f:pianoBlock)pianoEnergy+=f*f;
   require(pianoEnergy>.0001,"bundled FM piano produces audible samples");
   factoryPlayer.message(0x80,60,0);
   require(factoryPlayer.activeVoices>0,"piano release tail is not cut off immediately");
   System.out.println("SampledInstrumentTests passed "+checks+" checks");
  }finally{clean(dir);}
 }
 static void clean(File f){if(f.isDirectory()){File[] c=f.listFiles();if(c!=null)for(File child:c)clean(child);}f.delete();}
}

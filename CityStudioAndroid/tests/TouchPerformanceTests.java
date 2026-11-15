package ug.audiocity.studio;
import java.util.concurrent.atomic.AtomicLong;
/** SDK-free checks for progression order and transport-relative MIDI count-ins. */
public final class TouchPerformanceTests {
 static int checks;
 static void expect(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
 public static void main(String[] args){
  expect(PerformanceChords.MAJOR.length>=12,"major progression choices");
  expect(PerformanceChords.MINOR.length>=12,"minor progression choices");
  int[] pop=PerformanceChords.degrees(false,0);
  expect(pop.length==4&&pop[0]==0&&pop[1]==4&&pop[2]==5&&pop[3]==3,"correct I-V-vi-IV order");
  for(boolean minor:new boolean[]{false,true})for(int preset=0;preset<14;preset++){
   int[] seq=PerformanceChords.degrees(minor,preset);
   expect(seq.length>=4&&seq.length<=8,"supported progression lengths");
   for(int i=0;i<8;i++)expect(PerformanceChords.degree(minor,preset,i)>=0&&PerformanceChords.degree(minor,preset,i)<=6,"valid scale degrees");
  }
  final long beat=Core.beat(100),bar=beat*4;
  expect(PerformanceChords.nextBar(0,bar)==0,"start on downbeat");
  expect(PerformanceChords.nextBar(beat+1,bar)==bar,"round to following bar");
  expect(PerformanceChords.recordStart(0,bar,0)==0,"count-in off");
  expect(PerformanceChords.recordStart(0,bar,1)==bar,"one bar count-in");
  expect(PerformanceChords.recordStart(0,bar,2)==bar*2,"two bar count-in");
  expect(PerformanceChords.recordStart(beat,bar,1)==bar*2,"complete next bar before recording");
  AtomicLong clock=new AtomicLong(0);
  MidiTake take=new MidiTake(Synth.factory(3),100,clock::get,bar);
  expect(take.pending(),"armed before downbeat");
  take.add(144,60,100);expect(take.events.isEmpty(),"never record count-in notes");
  clock.set(bar);expect(!take.pending(),"recording begins at downbeat");
  take.add(144,60,100);clock.set(bar+beat/2);take.add(128,60,0);
  expect(take.events.size()==2,"captures note on and off");
  expect(take.events.get(0).frame==0,"onset exactly on downbeat");
  expect(take.events.get(1).frame==beat/2,"timeline-based timestamp");
  clock.set(bar*3);take.finish();
  expect(take.frames==bar*2,"long take follows continuous transport, even across loops");
  System.out.println("TouchPerformanceTests passed "+checks+" checks");
 }
}

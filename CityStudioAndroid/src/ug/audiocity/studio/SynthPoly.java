package ug.audiocity.studio;
import java.util.*;
/** Shared polyphonic voice renderer for live MIDI and printed MIDI performances. */
public final class SynthPoly {
 private static final class Scheduled {
  final long frame,order;final int status,a,b;
  Scheduled(long frame,long order,int status,int a,int b){this.frame=frame;this.order=order;this.status=status;this.a=a;this.b=b;}
 }
 static final class Voice {final int channel,note;final Synth.Config config;final double velocity,frequency;double phase,phase2,age,offAt=-1,offLevel,lpL,lpR;boolean pending;double[] string;int stringAt;double stringPosition;float left,right;
  Voice(int ch,int key,int vel,Synth.Config c){channel=ch;note=key;velocity=Math.pow(vel/127.0,.8);config=c.copy();frequency=440*Math.pow(2,(key-69)/12.0);int length=Math.max(2,(int)Math.round(Core.RATE/frequency));string=new double[length];Random random=new Random(91823+key*17+c.patch*701);for(int i=0;i<length;i++)string[i]=(random.nextDouble()*2-1)*.6;if(c.patch==10)for(int i=0;i<length;i++)string[i]=(string[i]+string[(i+1)%length])*.5;}
  void release(){if(offAt<0){offAt=age;offLevel=Synth.preEnvelope(age,config);}}boolean done(){return offAt>=0&&age>=offAt+config.release+.01;}
  void tick(double bend){Synth.Config c=config;double f=frequency*Math.pow(2,bend/12),actual=f;if(c.patch==7)actual*=1+.6*Math.exp(-age*36);if(c.patch==11)actual*=1+2.2*Math.exp(-age*48);phase+=2*Math.PI*actual/Core.RATE;phase2+=2*Math.PI*f*Math.pow(2,c.detune/1200)/Core.RATE;double x=0,spread=0;switch(c.patch){case 0:x=Math.sin(phase)*.5+Math.sin(phase*2)*.22*Math.exp(-age*3)+Math.sin(phase*3)*.12*Math.exp(-age*5)+Math.sin(phase*4)*.06*Math.exp(-age*7)+Math.sin(phase2)*.3+Math.sin(phase2*2)*.1*Math.exp(-age*3);spread=(Math.sin(phase2)-Math.sin(phase))*.06;break;case 1:x=Math.sin(phase+1.8*Math.sin(phase*2)*Math.exp(-age*4))*.55+Math.sin(phase2+1.2*Math.sin(phase2*2)*Math.exp(-age*4))*.3+Math.sin(phase*14)*.05*Math.exp(-age*14);spread=(Math.sin(phase2)-Math.sin(phase))*.08;break;case 2:x=Math.tanh((Math.sin(phase)*.6+Math.sin(phase*2)*.25+Math.sin(phase*3)*.1+Math.sin(phase*.5)*.25)*1.3)*.75;break;case 3:case 10:stringAt=(int)stringPosition;double fraction=stringPosition-stringAt;x=string[stringAt]+(string[(stringAt+1)%string.length]-string[stringAt])*fraction;string[stringAt]=(x+string[(stringAt+1)%string.length])*.5*(c.patch==3?.995:.998);stringPosition=(stringPosition+Math.pow(2,bend/12))%string.length;x=x*1.6+Math.sin(phase*2)*.08*Math.exp(-age*15);break;case 4:x=(Math.sin(phase)+Math.sin(phase2)+Math.sin(phase*.5)*.4)*.33;spread=(Math.sin(phase2)-Math.sin(phase))*.13;break;case 5:x=Math.sin(phase)*.65+Math.sin(phase*2.706)*.23*Math.exp(-age*3)+Math.sin(phase*5.41)*.1*Math.exp(-age*6);break;case 6:x=(Synth.saw(phase,f)+Synth.saw(phase2,f*Math.pow(2,c.detune/1200)))*.45;spread=(Math.sin(phase2)-Math.sin(phase))*.07;break;case 7:x=Math.sin(phase+Math.sin(phase*2)*1.4*Math.exp(-age*9))*.8;break;case 8:x=Math.sin(phase)*.65+Math.sin(phase*4)*.23*Math.exp(-age*18)+Math.sin(phase*9.9)*.08*Math.exp(-age*30);break;case 9:x=Synth.saw(phase,f)*.6+Synth.saw(phase2,f*Math.pow(2,c.detune/1200))*.3;break;case 12:x=Math.sin(phase)*.95;break;case 13:x=Synth.saw(phase,f)*.6+Math.sin(phase*.5)*.3;break;case 14:x=(Math.sin(phase)+Math.sin(phase*3)/3+Math.sin(phase*5)/5+Math.sin(phase*7)/7)*.62+Math.sin(phase*.5)*.2;break;case 15:x=Math.sin(phase)*.7+Math.sin(phase*2)*.3*Math.exp(-age*6)+Math.sin(phase*3)*.12*Math.exp(-age*10);break;case 16:x=Math.sin(phase)*.92+Math.sin(phase*2)*.04;break;case 11:x=Math.tanh((Math.sin(phase)+Math.sin(phase*2)*.08)*1.2)*.85;break;}double env=offAt<0?Synth.preEnvelope(age,c):offLevel*Math.max(0,1-(age-offAt)/c.release),alpha=1-Math.exp(-2*Math.PI*(250+c.tone*c.tone*14500)/Core.RATE);lpL+=alpha*((x-spread)*env*.31*velocity-lpL);lpR+=alpha*((x+spread)*env*.31*velocity-lpR);left=(float)lpL;right=(float)lpR;age+=1.0/Core.RATE;}
 }
 static final class SampleVoice {
  final int channel,note;final SampledInstruments.Region region;final double velocity,step;
  double at=0,releaseFrame=-1,pitchStep,lastBend,lowL,lowR;long ageFrames;boolean pending;float left,right;
  SampleVoice(int ch,int note,int vel,SampledInstruments.Region r){channel=ch;this.note=note;region=r;velocity=Math.pow(vel/127.0,.8)*Math.max(.01,Math.min(2,r.gain));step=Math.pow(2,(note-r.root)/12.0);pitchStep=step;}
  void release(){if(releaseFrame<0)releaseFrame=0;}
  boolean done(){return !region.loop&&at>=region.pcm.length/2.0||releaseFrame>=region.releaseFrames;}
  void tick(double bend){int n=region.pcm.length/2;double pos=at;
   if(region.loop&&region.loopEnd>region.loopStart&&pos>=region.loopEnd){pos=region.loopStart+(pos-region.loopStart)%(region.loopEnd-region.loopStart);at=pos;}
   if(n<1||pos>=n){left=right=0;at=n;return;}
   int i=(int)pos,j=Math.min(n-1,i+1);double frac=pos-i;
   float envelope=(float)Math.min(1,ageFrames/(Core.RATE*.004));
   if(releaseFrame>=0){envelope*=Math.max(0,1-releaseFrame/(double)region.releaseFrames);releaseFrame++;}
   left=(float)((region.pcm[i*2]+(region.pcm[j*2]-region.pcm[i*2])*frac)*velocity*envelope*.6);
   right=(float)((region.pcm[i*2+1]+(region.pcm[j*2+1]-region.pcm[i*2+1])*frac)*velocity*envelope*.6);
   if(channel!=9){lowL+=.034*(left-lowL);lowR+=.034*(right-lowR);left=(float)(Math.tanh((left+.3*lowL)*1.1)/1.1);right=(float)(Math.tanh((right+.3*lowR)*1.1)/1.1);}
   if(bend!=lastBend){pitchStep=step*Math.pow(2,bend/12.0);lastBend=bend;}at+=pitchStep;ageFrames++;
  }
 }
 private final SampledInstruments.Pack[] samplePacks=new SampledInstruments.Pack[16];
 final ArrayList<SampleVoice> sampled=new ArrayList<>();
 public synchronized void setSamplePack(SampledInstruments.Pack pack){silence();Arrays.fill(samplePacks,pack);}
 public synchronized void setSamplePack(int channel,SampledInstruments.Pack pack){if(channel<0||channel>15)throw new IllegalArgumentException("Invalid MIDI channel");samplePacks[channel]=pack;voices.removeIf(v->v.channel==channel);sampled.removeIf(v->v.channel==channel);scheduled.removeIf(e->(e.status&15)==channel);sustain[channel]=false;activeVoices=voices.size()+sampled.size();}
 public synchronized SampledInstruments.Pack getSamplePack(){return samplePacks[0];}
 public synchronized SampledInstruments.Pack getSamplePack(int channel){if(channel<0||channel>15)throw new IllegalArgumentException("Invalid MIDI channel");return samplePacks[channel];}
 final ArrayList<Voice> voices=new ArrayList<>();final Synth.Config[] config=new Synth.Config[16];final boolean[] sustain=new boolean[16];final double[] bend=new double[16];public volatile int activeVoices;
 private final PriorityQueue<Scheduled> scheduled=new PriorityQueue<>((x,y)->{int byFrame=Long.compare(x.frame,y.frame);return byFrame!=0?byFrame:Long.compare(x.order,y.order);});
 private long scheduleOrder;
 public SynthPoly(Synth.Config c){setConfig(c);} public synchronized void setConfig(Synth.Config c){for(int i=0;i<16;i++)config[i]=c.copy();}
 public synchronized void setConfig(int channel,Synth.Config c){if(channel<0||channel>15||c==null)throw new IllegalArgumentException("Invalid MIDI instrument");config[channel]=c.copy();}
 public synchronized void message(int status,int a,int b){int channel=status&15,type=status&240;
  if(type==144&&b>0){
   for(Voice v:voices)if(v.channel==channel&&v.note==a)v.release();
   for(SampleVoice v:sampled)if(v.channel==channel&&v.note==a)v.release();
   if(voices.size()+sampled.size()>=16){if(!sampled.isEmpty())sampled.remove(0);else voices.remove(0);}
   SampledInstruments.Pack pack=samplePacks[channel];
   if(pack!=null){SampledInstruments.Region r=pack.find(a,b);if(r!=null)sampled.add(new SampleVoice(channel,a,b,r));}
   else voices.add(new Voice(channel,a,b,config[channel]));
  }else if(type==128||type==144&&b==0){
   for(Voice v:voices)if(v.channel==channel&&v.note==a){if(sustain[channel])v.pending=true;else v.release();}
   for(SampleVoice v:sampled)if(v.channel==channel&&v.note==a){if(sustain[channel])v.pending=true;else v.release();}
  }else if(type==176&&a==64){sustain[channel]=b>=64;if(!sustain[channel]){
   for(Voice v:voices)if(v.channel==channel&&v.pending)v.release();
   for(SampleVoice v:sampled)if(v.channel==channel&&v.pending)v.release();
  }}else if(type==176&&(a==120||a==123)){
   for(Voice v:voices)if(v.channel==channel)v.release();for(SampleVoice v:sampled)if(v.channel==channel)v.release();
   if(a==120){voices.removeIf(v->v.channel==channel);sampled.removeIf(v->v.channel==channel);}
  }else if(type==224)bend[channel]=(((b<<7)|a)-8192)/8192.0*2;
  else if(type==192&&samplePacks[channel]==null)config[channel]=Synth.factory(a%Synth.NAMES.length);
  activeVoices=voices.size()+sampled.size();
 }
 /** Queue note events on the audio transport clock, so previews and live patterns share the live voice renderer. */
 public synchronized void schedule(List<MidiTake.Event> events,long delayFrames,int channel){
  if(events==null||channel<0||channel>15)throw new IllegalArgumentException("Invalid MIDI schedule");
  if(events.size()+scheduled.size()>100000)throw new IllegalArgumentException("MIDI schedule is too large");
  long startFrame=renderedFrames+Math.max(0,delayFrames);
  for(MidiTake.Event event:events){
   int type=event.status&240;
   if(type!=128&&type!=144&&type!=176&&type!=192&&type!=208&&type!=224)continue;
   long at=Math.max(0,startFrame+(long)event.frame);
   scheduled.add(new Scheduled(at,scheduleOrder++,type|channel,event.a,event.b));
  }
 }
 public synchronized void scheduleMessage(long delayFrames,int status,int a,int b){
  scheduled.add(new Scheduled(renderedFrames+Math.max(0,delayFrames),scheduleOrder++,status,a,b));
 }
 public synchronized void cancelScheduled(int channel){
  if(channel<0||channel>15)throw new IllegalArgumentException("Invalid MIDI channel");
  scheduled.removeIf(e->(e.status&15)==channel);
  voices.removeIf(v->v.channel==channel);sampled.removeIf(v->v.channel==channel);
  sustain[channel]=false;activeVoices=voices.size()+sampled.size();
 }
 private long renderedFrames,transportPosition=-1;
 /** Renders queued events at exact sample offsets in the shared MIDI audio stream. */
 public synchronized void render(float[] out,int frames){
  renderFrames(out,frames);
  transportPosition=-1;
 }
 /** Renders MIDI at the project's current transport position for beat-aligned scheduling. */
 public synchronized void render(float[] out,int frames,long projectFrame){
  if(projectFrame<0)throw new IllegalArgumentException("Invalid transport position");
  renderFrames(out,frames);
  transportPosition=projectFrame+frames;
 }
 /** Schedules a pattern on the next transport grid using the same clock as its audio rendering. */
 public synchronized long scheduleNextGrid(List<MidiTake.Event> events,int channel,long gridFrames,long fallbackFrame){
  if(gridFrames<1||fallbackFrame<0)throw new IllegalArgumentException("Invalid MIDI schedule grid");
  long position=transportPosition>=0?transportPosition:fallbackFrame;
  long delay=gridFrames-Math.floorMod(position,gridFrames);
  schedule(events,delay,channel);
  return delay;
 }
 private void renderFrames(float[] out,int frames){
  if(frames<0||out.length<frames*2)throw new IllegalArgumentException("Invalid MIDI output buffer");
  if(scheduled.isEmpty()){renderVoices(out,frames);renderedFrames+=frames;return;}
  Arrays.fill(out,0,frames*2,0);float[] segment=new float[Math.max(2,frames*2)];int written=0;
  while(written<frames){
   long at=renderedFrames+written;
   while(!scheduled.isEmpty()&&scheduled.peek().frame<=at){Scheduled e=scheduled.poll();message(e.status,e.a,e.b);}
   int count=frames-written;
   if(!scheduled.isEmpty())count=(int)Math.min(count,Math.max(1,scheduled.peek().frame-at));
   renderVoices(segment,count);
   System.arraycopy(segment,0,out,written*2,count*2);written+=count;
  }renderedFrames+=frames;
 }
 public synchronized void silence(){voices.clear();sampled.clear();scheduled.clear();Arrays.fill(sustain,false);Arrays.fill(bend,0);activeVoices=0;}
 private void renderVoices(float[] out,int frames){Arrays.fill(out,0,frames*2,0);
  for(Voice v:voices)for(int i=0;i<frames;i++){v.tick(bend[v.channel]);out[2*i]+=v.left;out[2*i+1]+=v.right;}
  for(SampleVoice v:sampled)for(int i=0;i<frames;i++){v.tick(bend[v.channel]);out[2*i]+=v.left;out[2*i+1]+=v.right;}
  for(int i=0;i<frames*2;i++)out[i]=(float)(Math.tanh(out[i]*.8)*.7);
  voices.removeIf(Voice::done);sampled.removeIf(SampleVoice::done);activeVoices=voices.size()+sampled.size();
 }
}

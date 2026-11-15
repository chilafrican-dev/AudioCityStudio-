package ug.audiocity.studio;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** BeatPilot drum pad + editable step pattern workspace. */
final class BeatPilotDrumScreen {
 final MainActivity a;
 private BeatPilotDrumPattern pattern;
 private Core.Clip initialClip;
 private int currentBar=0;
 private int inputVelocity=105;
 private final float[][] samples=new float[BeatPilotDrums.LANES][];
 private BeatPilotDrums.LivePattern livePattern;
 private Core.Track liveTrack;
 private final java.util.concurrent.atomic.AtomicInteger edits=new java.util.concurrent.atomic.AtomicInteger();
 BeatPilotDrumScreen(MainActivity activity){a=activity;}
 void openClip(Core.Clip clip){if(clip==null||clip.drumPattern==null){a.toast("Not a BeatPilot MIDI drum clip");return;}if(clip.drumPattern.startsWith("v3|")){Core.Track t=null;for(Core.Track tr:a.session.tracks)if(tr.clips.contains(clip)){t=tr;break;}if(t!=null)new BeatPilotDrumPianoRoll(a,t,clip).show();return;}initialClip=clip;open();}
 private TextView label(String value){return a.label(value,13,MainActivity.TEXT,false);}
 private LinearLayout column(){LinearLayout b=a.column();b.setPadding(a.dp(12),a.dp(10),a.dp(12),a.dp(10));b.setBackgroundColor(MainActivity.BG);return b;}
 private void load()throws IOException{
  for(int i=0;i<samples.length;i++)try(InputStream in=a.getAssets().open("samples/builtin/soundza/"+BeatPilotDrums.FILES[i])){samples[i]=SampleAudio.readWav(in);}
 }
 static SampledInstruments.Pack pack(float[][] samples){SampledInstruments.Region[] regions=new SampledInstruments.Region[samples.length];
  for(int i=0;i<samples.length;i++)regions[i]=new SampledInstruments.Region(36+i,36+i,1,127,36+i,0,0,false,1f,samples[i],240);
  return new SampledInstruments.Pack("Audio City Drum Pads","Soundza drum one-shots · see bundled license",regions);
 }
 void open(){if(!a.canEdit()||a.working)return;
  a.busy("Preparing Audio City drum pads…",()->{
   load();a.runOnUiThread(this::show);
  });
 }
 private void refreshLive(){
  final BeatPilotDrums.LivePattern target=livePattern;
  if(liveTrack!=null)liveTrack.mute=false;
  if(target==null||a.session==null)return;
  final int generation=edits.incrementAndGet();
  final BeatPilotDrumPattern snapshot=pattern.copy();
  
  new Thread(()->{
   float[] phrase=snapshot.render(samples);
   if(generation==edits.get()&&livePattern==target)target.replace(phrase);
  },"BeatPilot-live-pattern-update").start();
 }
 private void closeLive(){
  edits.incrementAndGet();
  if(liveTrack!=null&&a.session!=null)a.session.tracks.remove(liveTrack);
  liveTrack=null;livePattern=null;
 }
 private void show(){
  if(a.dead||a.session==null)return;
  if(a.session.timeSignature!=4){a.toast("Drum machine currently supports 4/4 projects.");return;}
  if(pattern==null){try{pattern=initialClip!=null?BeatPilotDrumPattern.decode(initialClip.drumPattern):BeatPilotDrumPattern.starter(a.session.bpm,1);}catch(RuntimeException ex){a.toast("Saved drum pattern is invalid");return;}}
  final SampledInstruments.Pack originalDrumPack=a.liveMidi.voices.getSamplePack(9);
  a.liveMidi.voices.setSamplePack(9,pack(samples));a.liveMidi.start();
  final Core.Session project=a.session;
  // Non-destructive monitoring track: loops sample data in lockstep with the project timeline.
  // Do not save this temporary track as a user recording.
  livePattern=new BeatPilotDrums.LivePattern(pattern.render(samples),Core.RATE*60*10);
  liveTrack=new Core.Track("BeatPilot Live Preview (temporary)",0xff536c89);liveTrack.ephemeral=true;
  Core.Clip monitoring=new Core.Clip("Live Drum Preview",livePattern,"",0);
  monitoring.length=livePattern.frames();monitoring.envelopeLength=monitoring.length;
  liveTrack.clips.add(monitoring);project.tracks.add(liveTrack);

  LinearLayout page=column();page.addView(a.label("AUDIO CITY DRUMS",20,MainActivity.TEXT,true));
  page.addView(label("Live pads + multi-bar MIDI grid · tap to toggle, hold to set velocity"));
  final TextView status=a.label("Eight real drum sounds · 16-step pattern",12,MainActivity.ACCENT,false);page.addView(status);
  LinearLayout pads=new LinearLayout(a);pads.setOrientation(LinearLayout.VERTICAL);page.addView(pads,new LinearLayout.LayoutParams(-1,a.dp(168)));
  for(int row=0;row<2;row++){LinearLayout line=new LinearLayout(a);pads.addView(line,new LinearLayout.LayoutParams(-1,0,1));
   for(int col=0;col<4;col++){final int lane=row*4+col;Button button=a.button(BeatPilotDrums.LABELS[lane],()->{
     a.liveMidi.voices.message(0x99,36+lane,110); // non-looping sample ends naturally
     status.setText("Played: "+BeatPilotDrums.LABELS[lane]);
    });line.addView(button,new LinearLayout.LayoutParams(0,-1,1));}
  }
  LinearLayout toolbar=new LinearLayout(a);page.addView(toolbar,new LinearLayout.LayoutParams(-1,a.dp(43)));
  final Button preset=a.button("Starter",()->{}),clear=a.button("Clear",()->{});
  toolbar.addView(preset,new LinearLayout.LayoutParams(0,-1,1));toolbar.addView(clear,new LinearLayout.LayoutParams(0,-1,1));
  final Core.Clip[] edited={initialClip};
  final Runnable[] redrawUiHolder={null};
  Button edit=a.button("Edit saved MIDI",()->{
   ArrayList<Core.Clip> matches=new ArrayList<>();
   for(Core.Track t:project.tracks)if(!t.ephemeral)for(Core.Clip c:t.clips)if(c.drumPattern!=null)matches.add(c);
   if(matches.isEmpty()){a.toast("No saved BeatPilot drum patterns yet.");return;}
   String[] options=new String[matches.size()];
   for(int i=0;i<matches.size();i++)options[i]=matches.get(i).name+" · "+(i+1);
   new AlertDialog.Builder(a).setTitle("Edit an Arrange drum pattern").setItems(options,(d,which)->{
    Core.Clip chosen=matches.get(which);pattern=BeatPilotDrumPattern.decode(chosen.drumPattern);currentBar=0;
    edited[0]=chosen;redrawUiHolder[0].run();refreshLive();
    status.setText("Editing saved MIDI steps: "+chosen.name);
   }).show();
  });toolbar.addView(edit,new LinearLayout.LayoutParams(0,-1,1));
  LinearLayout barLine=new LinearLayout(a);page.addView(barLine,new LinearLayout.LayoutParams(-1,a.dp(44)));
  final Button barLabel=a.button("Bar 1/1",()->{});
  final Button previous=a.button("◀",()->{}),next=a.button("▶",()->{}),length=a.button("Bars: 1",()->{});
  barLine.addView(previous,new LinearLayout.LayoutParams(0,-1,.55f));barLine.addView(barLabel,new LinearLayout.LayoutParams(0,-1,1.2f));barLine.addView(next,new LinearLayout.LayoutParams(0,-1,.55f));barLine.addView(length,new LinearLayout.LayoutParams(0,-1,1.3f));
  LinearLayout velocityLine=new LinearLayout(a);page.addView(velocityLine,new LinearLayout.LayoutParams(-1,a.dp(43)));
  final Button velDown=a.button("Velocity −",()->{}),velLabel=a.button("105",()->{}),velUp=a.button("Velocity +",()->{});
  velocityLine.addView(velDown,new LinearLayout.LayoutParams(0,-1,1));velocityLine.addView(velLabel,new LinearLayout.LayoutParams(0,-1,.7f));velocityLine.addView(velUp,new LinearLayout.LayoutParams(0,-1,1));
  final ScrollView scroll=new ScrollView(a);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  final LinearLayout steps=column();scroll.addView(steps);
  final Button[][] cells=new Button[BeatPilotDrumPattern.LANES][BeatPilotDrumPattern.STEPS];
  for(int row=0;row<BeatPilotDrums.LANES;row++){
   final int lane=row;steps.addView(label(BeatPilotDrums.LABELS[row]));
   for(int half=0;half<2;half++){
    LinearLayout line=new LinearLayout(a);steps.addView(line,new LinearLayout.LayoutParams(-1,a.dp(42)));
    for(int col=0;col<8;col++){
     final int step=half*8+col;Button cell=a.button("",()->{int v=pattern.get(currentBar,lane,step);pattern.set(currentBar,lane,step,v==0?inputVelocity:0);paint(cells[lane][step],pattern.get(currentBar,lane,step));refreshLive();});
     cell.setTextSize(11);cell.setOnLongClickListener(v->{pattern.set(currentBar,lane,step,inputVelocity);paint(cells[lane][step],inputVelocity);refreshLive();return true;});cells[lane][step]=cell;line.addView(cell,new LinearLayout.LayoutParams(0,-1,1));
    }
   }
  }
  Runnable redraw=()->{
   for(int r=0;r<BeatPilotDrumPattern.LANES;r++)for(int i=0;i<BeatPilotDrumPattern.STEPS;i++)paint(cells[r][i],pattern.get(currentBar,r,i));
   barLabel.setText("Bar "+(currentBar+1)+"/"+pattern.bars);length.setText("Bars: "+pattern.bars);
   velLabel.setText(""+inputVelocity);
  };redrawUiHolder[0]=redraw;
  previous.setOnClickListener(v->{currentBar=(currentBar+pattern.bars-1)%pattern.bars;redraw.run();});
  next.setOnClickListener(v->{currentBar=(currentBar+1)%pattern.bars;redraw.run();});
  length.setOnClickListener(v->new AlertDialog.Builder(a).setTitle("Drum pattern length")
   .setItems(new String[]{"1 bar","2 bars","4 bars","8 bars"},(d,which)->{
    pattern=pattern.resized(new int[]{1,2,4,8}[which]);currentBar=Math.min(currentBar,pattern.bars-1);redraw.run();refreshLive();
   }).show());
  velDown.setOnClickListener(v->{inputVelocity=Math.max(1,inputVelocity-10);redraw.run();});
  velUp.setOnClickListener(v->{inputVelocity=Math.min(127,inputVelocity+10);redraw.run();});
  preset.setOnClickListener(v->{pattern=BeatPilotDrumPattern.starter(project.bpm,pattern.bars);redraw.run();refreshLive();});
  clear.setOnClickListener(v->{for(int l=0;l<8;l++)for(int s=0;s<16;s++)pattern.set(currentBar,l,s,0);redraw.run();refreshLive();});
  redraw.run();
  LinearLayout controls=new LinearLayout(a);page.addView(controls,new LinearLayout.LayoutParams(-1,a.dp(53)));
  Button loops=a.button("♫ Loops",()->openLoops());
  page.addView(loops,new LinearLayout.LayoutParams(-1,a.dp(42)));
  Button play=a.button("▶ Play beat",()->{if(a.engine.playing)a.engine.pause();else if(a.requestAudioFocus())a.engine.play(a.session);a.updateTransport();});
  Button save=a.button("✓ Keep drum pattern",()->{});
  controls.addView(play,new LinearLayout.LayoutParams(0,-1,1));controls.addView(save,new LinearLayout.LayoutParams(0,-1,1.5f));
  Button close=a.button("Close drums",()->{});page.addView(close,new LinearLayout.LayoutParams(-1,a.dp(40)));
  final AlertDialog dialog=new AlertDialog.Builder(a).setView(page).create();
  final Runnable[] ticker={null};ticker[0]=()->{if(dialog.isShowing()&&!a.dead){play.setText(a.engine.playing?"Ⅱ Pause beat":"▶ Play beat");a.handler.postDelayed(ticker[0],175);}};
  save.setOnClickListener(v->{if(a.working)return;BeatPilotDrumPattern snapshot=pattern.copy();
   long bar=Core.beat(project.bpm)*Math.max(1,project.timeSignature);long pos=edited[0]==null?PerformanceChords.nextBar(a.engine.position,bar):edited[0].start;
   if(pos+bar*snapshot.bars>Core.MAX_FRAMES){a.toast("Move the playhead earlier to save the drum pattern.");return;}
   a.checkpoint();a.busy("Saving drum pattern to Arrange…",()->{
    float[] audio=snapshot.render(samples);
    File dir=new File(project.directory);Core.Clip clip=AudioIO.store(dir,audio,"Audio City Drums · "+snapshot.bars+" bars",pos);
    clip.drumPattern=snapshot.encode();
    clip.notes=snapshot.notes();
    String midiName="beatpilot-"+java.util.UUID.randomUUID()+".mid";
    try(FileOutputStream midiOut=new FileOutputStream(new File(dir,midiName))){
     snapshot.writeMidi(midiOut);
    }
    clip.midiFile=midiName;
    a.runOnUiThread(()->{if(a.session!=project){new File(dir,clip.file).delete();a.toast("Project changed; drum pattern not inserted.");return;}
     Core.Track track=null;
     if(edited[0]!=null){
      for(Core.Track candidate:project.tracks)if(candidate.clips.contains(edited[0])){track=candidate;break;}
     }
     if(track==null){track=new Core.Track("Audio City MIDI Drums",0xff47bbae);project.tracks.add(track);}
     else track.clips.remove(edited[0]);
     track.clips.add(clip);edited[0]=clip;
     a.selected=track;a.clip=clip;if(liveTrack!=null)liveTrack.mute=true;a.save(false);
     a.toast("Drum MIDI pattern saved. Reopen Edit saved MIDI to change steps.");
    });
   });
  });
  close.setOnClickListener(v->dialog.dismiss());
  dialog.setOnDismissListener(v->{closeLive();a.handler.removeCallbacks(ticker[0]);for(int i=0;i<8;i++)a.liveMidi.voices.message(0x89,36+i,0);a.liveMidi.voices.setSamplePack(9,originalDrumPack);});
  dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);a.handler.post(ticker[0]);
 }
 private static final String[] LOOP_FILES={
  "094-soundza-bright-african-perc-loop.wav","095-soundza-fast-african-perc-loop.wav",
  "096-soundza-deep-african-drum-loop.wav","097-soundza-driving-percussion-loop.wav",
  "098-soundza-afrobeat-percussion-loop.wav","099-soundza-tight-tom-loop.wav",
  "100-soundza-driving-djembe-loop.wav","101-soundza-afrobeat-djembe-loop.wav",
  "102-soundza-acoustic-drum-loop.wav","103-soundza-tambourine-loop-120-bpm.wav",
  "104-soundza-rhythmic-tambourine-loop.wav","105-soundza-tambourine-loop-125-bpm.wav",
  "106-soundza-samba-tambourine-loop.wav","107-soundza-long-ethnic-drum-loop.wav",
  "108-soundza-african-hand-perc-loop.wav","112-soundza-driving-udu-loop.wav",
  "113-soundza-middle-east-percussion-loop.wav","139-soundza-accelerated-bendir-loop.wav",
  "141-soundza-warm-frame-drum-loop.wav"};
 void openLoops(){
  if(a.session==null||a.working)return;
  if(a.session.timeSignature!=4){a.toast("Loop browser currently supports 4/4 projects.");return;}
  LinearLayout body=column();body.addView(label("Choose a bundled loop. Set the correct number of bars before fitting it to the song tempo."));
  final Spinner names=new Spinner(a);String[] titles=new String[LOOP_FILES.length];for(int i=0;i<titles.length;i++)titles[i]=LOOP_FILES[i].substring(4).replace("soundza-","").replace(".wav","").replace('-', ' ');
  names.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,titles));body.addView(names);
  body.addView(label("Loop musical length"));final Spinner bars=new Spinner(a);bars.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,new String[]{"1 bar","2 bars","4 bars"}));body.addView(bars);
  body.addView(label("Pitch-preserving drum stretch is approximate. For unusual-length loops, verify the bar count and audition the result."));
  new AlertDialog.Builder(a).setTitle("BeatPilot · Loop Browser").setView(body).setNegativeButton("Cancel",null).setPositiveButton("Add synced loop to Arrange",(dialog,which)->{
   if(a.working)return;final Core.Session project=a.session;final int index=names.getSelectedItemPosition();final int count=new int[]{1,2,4}[bars.getSelectedItemPosition()];
   final long bar=Core.beat(project.bpm)*Math.max(1,project.timeSignature);final long pos=PerformanceChords.nextBar(a.engine.position,bar);
   if(pos+bar*count>Core.MAX_FRAMES){a.toast("Move the playhead earlier to add this loop.");return;}
   a.checkpoint();a.busy("Syncing loop to "+project.bpm+" BPM…",()->{
    float[] source;try(InputStream in=a.getAssets().open("samples/builtin/soundza/"+LOOP_FILES[index])){source=SampleAudio.readWav(in);}
    int target=Math.toIntExact(bar*count);
    double ratio=target/(source.length/2.0);
    if(ratio<.5||ratio>2.0)throw new IOException("Loop length does not match the selected bars closely enough. Choose another bar count or a different loop.");
    float[] synced=BeatPilotLoopStretch.stretch(source,target);
    File dir=new File(project.directory);Core.Clip clip=AudioIO.store(dir,synced,"Audio City · "+titles[index],pos);
    a.runOnUiThread(()->{if(a.session!=project){new File(dir,clip.file).delete();a.toast("Project changed; loop not added.");return;}
     Core.Track track=new Core.Track("Audio City · Percussion Loop",0xffa785d0);track.clips.add(clip);project.tracks.add(track);a.selected=track;a.clip=clip;if(liveTrack!=null)liveTrack.mute=true;a.save(false);a.toast("Synced loop added to Arrange.");
    });
   });
  }).show();
 }
 private void paint(Button b,int velocity){b.setText(velocity>0?""+velocity:"·");b.setAlpha(velocity>0?.45f+.55f*(velocity/127f):.38f);}
}

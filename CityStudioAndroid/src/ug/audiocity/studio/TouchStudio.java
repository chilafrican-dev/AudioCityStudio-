package ug.audiocity.studio;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Beginner-friendly performance interface that shares the DAW's transport. */
final class TouchStudio {
 final MainActivity a;
 TouchStudio(MainActivity activity){a=activity;}
 Spinner spinner(LinearLayout box,String[] choices,int selected){
  Spinner s=new Spinner(a);s.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,choices));
  s.setSelection(Math.max(0,Math.min(selected,choices.length-1)));
  box.addView(s,new LinearLayout.LayoutParams(-1,a.dp(47)));return s;
 }
 LinearLayout box(){LinearLayout b=a.column();b.setPadding(a.dp(14),a.dp(11),a.dp(14),a.dp(10));b.setBackgroundColor(MainActivity.BG);return b;}
 void caption(LinearLayout b,String text){TextView t=a.label(text,12,MainActivity.DIM,false);t.setPadding(0,a.dp(5),0,a.dp(6));b.addView(t);}
 String[] progressions(int root,boolean minor){String[] labels=new String[3];for(int p=0;p<3;p++){StringBuilder s=new StringBuilder();for(int degree:PocketComposer.progression(minor,p)){if(s.length()>0)s.append(" · ");s.append(PocketComposer.chordName(root,minor,degree));}labels[p]=s.toString();}return labels;}
 void starter(){if(!a.canEdit())return;ScrollView scroll=new ScrollView(a);LinearLayout b=box();scroll.addView(b);b.addView(a.label("FROM IDEA TO A SONG",20,MainActivity.TEXT,true));caption(b,"Choose a feeling. Build a backing, then play your melody or record a vocal. Each instrument gets its own editable track.");Spinner style=spinner(b,PocketComposer.STYLES,0);caption(b,"KEY");Spinner root=spinner(b,PocketComposer.ROOTS,5);Spinner scale=spinner(b,new String[]{"Major · bright","Natural minor · moody"},0);Spinner progression=spinner(b,progressions(5,false),0);AdapterView.OnItemSelectedListener transpose=new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> v){}public void onItemSelected(AdapterView<?> v,View view,int pos,long id){int previous=Math.max(0,progression.getSelectedItemPosition());progression.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,progressions(root.getSelectedItemPosition(),scale.getSelectedItemPosition()==1)));progression.setSelection(previous);}};root.setOnItemSelectedListener(transpose);scale.setOnItemSelectedListener(transpose);EditText bpm=a.numberField(b,"TEMPO / BPM",String.valueOf(a.session.bpm));Spinner length=spinner(b,new String[]{"16 bars · compact idea","32 bars · longer arrangement"},0);caption(b,"Intro → verse → hook → outro. Drums change at section boundaries, bass follows the chords, and the hook adds a melody. This makes a new project; your current project stays saved. Everything works offline; use these patterns as a starting point for your own song.");new AlertDialog.Builder(a).setTitle("Song Starter").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Build my song",(d,w)->{try{int tempo=Integer.parseInt(bpm.getText().toString());if(tempo<40||tempo>240)throw new NumberFormatException();final int r=root.getSelectedItemPosition(),p=progression.getSelectedItemPosition(),s=style.getSelectedItemPosition(),bars=length.getSelectedItemPosition()==0?16:32;final boolean minor=scale.getSelectedItemPosition()==1;a.engine.stop();AudioIO.save(a.session.copy());a.busy("Building your song on separate tracks…",()->{File dir=a.newDir();Core.Session song=PocketComposer.song(dir,r,minor,p,tempo,s,bars);a.runOnUiThread(()->{a.screen="ARRANGE";a.bind(song);a.selected=song.tracks.get(4);a.handler.post(()->a.fitTimeline());a.refresh();a.toast("Your backing is ready. Play it, record a vocal, or use Sounds → Play & record.");});});}catch(NumberFormatException e){a.toast("Choose a tempo between 40 and 240 BPM.");}catch(Exception e){a.error("Could not save current project",e.getMessage());}}).show();}

 /** BeatPilot one-touch groove creation: new tracks are sent directly into Arrange. */
 void beatPilot(){if(!a.canEdit())return;new AlertDialog.Builder(a).setTitle("How do you want to build?").setItems(new String[]{"Start from scratch · Pattern rack","Auto-build a beat · optional"},(d,i)->{if(i==0)new BeatPilotDrumScreen(a).open();else autoBeat();}).setNegativeButton("Cancel",null).show();}
 void autoBeat(){
  if(!a.canEdit()||a.session==null)return;
  if(a.session.timeSignature!=4||!a.session.tempoChanges.isEmpty()){a.toast("Auto Build currently uses 4/4 and one project tempo.");return;}
  ScrollView scroll=new ScrollView(a);LinearLayout page=box();scroll.addView(page);
  page.addView(a.label("AUTO BUILD · OPTIONAL",19,MainActivity.TEXT,true));
  caption(page,"Optional help: make an editable drum pattern plus separate bass and chords. Each drum sound has its own track. You can also build everything manually in the pattern rack.");
  caption(page,"1 · MUSIC STYLE");Spinner genre=spinner(page,BeatPilot.GENRES,0);
  caption(page,"2 · FEELING");Spinner feel=spinner(page,BeatPilot.FEELS,1);
  caption(page,"3 · SONG KEY");Spinner root=spinner(page,PocketComposer.ROOTS,a.session.tuneConfigured?a.session.tuneKey:5);
  Spinner scale=spinner(page,new String[]{"Major · happy","Minor · moody"},a.session.tuneScale==2?1:0);
  caption(page,"4 · CHORDS");Spinner progression=spinner(page,PerformanceChords.titles(scale.getSelectedItemPosition()==1),0);
  scale.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> v){}public void onItemSelected(AdapterView<?> v,View view,int pos,long id){progression.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,PerformanceChords.titles(pos==1)));}});
  caption(page,"5 · PATTERN LENGTH");Spinner bars=spinner(page,new String[]{"4 bars","8 bars"},0);
  caption(page,"Beats are added at the project cursor without replacing your existing tracks. Your project tempo stays unchanged. You can Undo if you want to try again.");
  new AlertDialog.Builder(a).setTitle("BeatPilot · free and offline").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Make My Beat",(d,w)->{
   final Core.Session project=a.session;final int g=genre.getSelectedItemPosition(),f=feel.getSelectedItemPosition(),r=root.getSelectedItemPosition(),p=progression.getSelectedItemPosition(),count=bars.getSelectedItemPosition()==0?4:8;
   final boolean minor=scale.getSelectedItemPosition()==1;
   long beat=Core.beat(project.bpm)*4L;final long at=PerformanceChords.nextBar(a.engine.position,beat);
   if(at+count*beat>Core.MAX_FRAMES){a.toast("Move the playhead earlier to make room for the beat.");return;}
   if(project.patterns.size()>=PatternLibrary.MAX_PATTERNS||project.tracks.size()+PatternArrangement.requiredTracks(project)+2>Core.MAX_TRACKS){a.toast("Make room for a new pattern and its separate tracks first.");return;}
   a.checkpoint();a.busy("Building optional pattern and separate drum channels…",()->{
    float[][] kit=BeatPilotDrumScreen.loadKit(a);BeatPilotDrumPattern data=BeatPilot.pattern(g,f,project.bpm,count);PatternLibrary.Entry entry=PatternLibrary.add(project,"Auto pattern "+(project.patterns.size()+1),data);
    java.util.List<Core.Track> music;
    try{music=BeatPilot.harmony(project,g,r,minor,p,count,at);PatternArrangement.place(project,entry.id,kit,at,1);}catch(Exception error){project.patterns.remove(entry);throw error;}
    a.runOnUiThread(()->{
     if(a.session!=project)return;project.tracks.addAll(music);project.tuneConfigured=true;project.tuneKey=r;project.tuneScale=minor?2:1;
     a.selected=music.get(1);a.clip=music.get(1).clips.get(0);a.screen="ARRANGE";a.save(false);a.refresh();
     new AlertDialog.Builder(a).setTitle("Your optional beat is ready").setMessage("The pattern is saved in the rack. Kick, snare, clap, hats, shaker, conga and bongo each have their own track, with bass and chords separate too. Edit the pattern or add your own playing.").setNegativeButton("Arrange",null).setPositiveButton("Open patterns",(dd,ww)->a.handler.post(()->new BeatPilotDrumScreen(a).openPattern(entry.id))).show();
    });
   });
  }).show();
 }

 /** A full-height, fixed-pad performance surface. Advanced options live in a separate dialog. */
 void perform(){
  if(!a.canEdit())return;
  if(!a.requestAudioFocus())return;
  a.liveMidi.voices.setConfig(a.synthConfig);a.liveMidi.voices.setSamplePack(a.selectedSampleInstrument);a.liveMidi.start();
  final LinearLayout page=box();
  page.setPadding(a.dp(11),a.dp(9),a.dp(11),a.dp(8));
  page.addView(a.label("PLAY IT. KEEP THE TAKE.",19,MainActivity.TEXT,true));
  caption(page,"Your take gets its own track · Loop on: recording stops at the loop end");
  final Spinner root=new Spinner(a),scale=new Spinner(a),mode=new Spinner(a),octave=new Spinner(a),progression=new Spinner(a),layout=new Spinner(a),countIn=new Spinner(a);
  final Spinner[] spinners={root,scale,mode,octave,progression,layout,countIn};
  final String[] patches=Synth.NAMES;
  root.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,PocketComposer.ROOTS));root.setSelection(a.session.tuneConfigured?a.session.tuneKey:7);
  scale.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,new String[]{"Major","Minor"}));scale.setSelection(a.session.tuneConfigured&&a.session.tuneScale==2?1:0);
  mode.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,new String[]{"Chord pads","Melody pads"}));mode.setSelection(0);
  octave.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,new String[]{"Low · octave 2","Mid · octave 3","High · octave 4","Air · octave 5"}));octave.setSelection(2);
  progression.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,PerformanceChords.titles(scale.getSelectedItemPosition()==1)));
  layout.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,new String[]{"4 chord pads","6 chord pads","8 chord pads"}));
  countIn.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,new String[]{"Off","4 clicks","8 clicks"}));countIn.setSelection(1);
  final boolean[] playbackClick={a.engine.click},recordClick={a.engine.click};
  final TextView status=a.label("Ready · play chords while your beat runs",12,MainActivity.ACCENT,true);
  final TextView chordTitle=a.label("",13,MainActivity.TEXT,true);
  final TextView beatTitle=a.label("1   ·   2   ·   3   ·   4",12,MainActivity.DIM,false);
  final PadGrid pads=new PadGrid(root,scale,mode,octave,progression,layout);
  final LinearLayout top=new LinearLayout(a);page.addView(top,new LinearLayout.LayoutParams(-1,a.dp(44)));
  final Button instrument=a.button(a.selectedSampleInstrument==null?patches[a.synthConfig.patch]+"  ▾":a.selectedSampleInstrument.name+"  ▾",()->{}),options=a.button("⚙ Settings",()->{}),beatPilotButton=a.button("♫ Beats",()->{}),drumButton=a.button("♬ Drums",()->{});
  top.addView(instrument,new LinearLayout.LayoutParams(0,-1,1.3f));top.addView(beatPilotButton,new LinearLayout.LayoutParams(0,-1,1.05f));top.addView(drumButton,new LinearLayout.LayoutParams(0,-1,.85f));top.addView(options,new LinearLayout.LayoutParams(0,-1,.95f));
  LinearLayout info=new LinearLayout(a);info.setGravity(Gravity.CENTER_VERTICAL);page.addView(info,new LinearLayout.LayoutParams(-1,a.dp(43)));
  info.addView(chordTitle,new LinearLayout.LayoutParams(0,-1,1));
  final Button clickButton=a.button(playbackClick[0]?"CLICK ON":"CLICK OFF",()->{playbackClick[0]=!playbackClick[0];});
  info.addView(clickButton,new LinearLayout.LayoutParams(a.dp(114),a.dp(40)));
  page.addView(status,new LinearLayout.LayoutParams(-1,a.dp(31)));
  page.addView(beatTitle,new LinearLayout.LayoutParams(-1,a.dp(27)));
  // The playable pads receive all remaining space. No count-in view can overlay them.
  LinearLayout.LayoutParams padParams=new LinearLayout.LayoutParams(-1,0,1);
  padParams.topMargin=a.dp(4);padParams.bottomMargin=a.dp(4);
  page.addView(pads,padParams);
  LinearLayout recording=new LinearLayout(a);page.addView(recording,new LinearLayout.LayoutParams(-1,a.dp(50)));
  Button record=a.button("● Start recording",()->{}),stopTake=a.button("■ Stop recording",()->{});
  recording.addView(record,new LinearLayout.LayoutParams(0,-1,1));recording.addView(stopTake,new LinearLayout.LayoutParams(0,-1,1));
  LinearLayout project=new LinearLayout(a);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,a.dp(48));pp.topMargin=a.dp(5);page.addView(project,pp);
  Button play=a.button("▶ Play beat",()->{}),loop=a.button("LOOP",()->{}),halt=a.button("■ Stop beat",()->{});
  project.addView(play,new LinearLayout.LayoutParams(0,-1,1.2f));project.addView(loop,new LinearLayout.LayoutParams(0,-1,.75f));project.addView(halt,new LinearLayout.LayoutParams(0,-1,1.1f));
  Button close=a.button("Close performance",()->{});
  LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,a.dp(39));cp.topMargin=a.dp(5);page.addView(close,cp);
  final AlertDialog dialog=new AlertDialog.Builder(a).setView(page).create();
  final Runnable updateClick=()->a.engine.click=(playbackClick[0]&&a.engine.playing)||(recordClick[0]&&a.midiTake!=null);
  final Runnable updateChords=()->{
    boolean minor=scale.getSelectedItemPosition()==1;
    int[] seq=PerformanceChords.degrees(minor,progression.getSelectedItemPosition());
    StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(4,seq.length);i++){if(i>0)b.append(" · ");b.append(PocketComposer.chordName(root.getSelectedItemPosition(),minor,seq[i]));}
    chordTitle.setText(b.toString());pads.invalidate();
  };
  final AdapterView.OnItemSelectedListener redraw=new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int pos,long id){pads.releaseAll();updateChords.run();}};
  for(Spinner s:new Spinner[]{root,mode,octave,progression,layout})s.setOnItemSelectedListener(redraw);
  scale.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,View view,int pos,long id){int old=progression.getSelectedItemPosition();progression.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,PerformanceChords.titles(pos==1)));progression.setSelection(old);pads.releaseAll();updateChords.run();}});
  instrument.setOnClickListener(v->{
   ArrayList<String> menu=new ArrayList<>();for(String patch:patches)menu.add("SYNTH · "+patch);
   final String[] drums={"bongo_high","bongo_low","conga_open","quinto_high","cowbell_open","clave_light","tambourine_hit","shaker_small","cabasa_hit"};
   final String[] drumNames={"Sampled High Bongo · CC0","Sampled Low Bongo · CC0","Sampled Conga · CC0","Sampled Quinto · CC0","Sampled Cowbell · CC0","Sampled Clave · CC0","Sampled Tambourine · CC0","Sampled Shaker · CC0","Sampled Cabasa · CC0"};
   for(String n:drumNames)menu.add(n);
   final List<SampledInstruments.Info> installed=a.instrumentLibrary.list();
   for(SampledInstruments.Info item:installed)menu.add((item.filename.startsWith("factory-")?"BUILT-IN · ":"SAMPLED · ")+item.name);
   menu.add("＋ Install a free instrument pack");
   final String[] groups=new String[menu.size()];for(int g=0;g<groups.length;g++){if(g<patches.length)groups[g]="Synth · "+Synth.SECTIONS[Synth.sectionOf(g)];else if(g<patches.length+drums.length)groups[g]="Sampled · percussion";else if(g==groups.length-1)groups[g]="More";else groups[g]=MainActivity.sampleGroup(installed.get(g-patches.length-drums.length).name);}
   a.pickGrouped("Choose playable instrument",menu.toArray(new String[0]),groups,i->{
    if(a.midiTake!=null){a.toast("Finish recording before changing instruments.");return;}
    if(i==menu.size()-1){a.realInstrumentLibrary();return;}
    pads.releaseAll();
    if(i<patches.length){a.selectedSampleInstrument=null;a.selectedSampleInstrumentId=null;a.synthConfig=Synth.factory(i);a.liveMidi.voices.setSamplePack(null);
      a.liveMidi.voices.setConfig(a.synthConfig);instrument.setText(patches[i]+"  ▾");}
    else if(i<patches.length+drums.length){int index=i-patches.length;
      try{if(a.sampleBank==null)throw new IOException("Sample bank unavailable");float[] wav=a.sampleBank.load(a.sampleBank.byId(drums[index]));
       a.selectedSampleInstrument=SampledInstruments.monoPack(drumNames[index],wav,60,"Versilian Studios / VCSL · CC0");a.selectedSampleInstrumentId="bank:"+drums[index];
       a.liveMidi.voices.setSamplePack(a.selectedSampleInstrument);instrument.setText(drumNames[index]+"  ▾");}
      catch(Exception e){a.error("Instrument",e.getMessage());}
    }else{SampledInstruments.Info sampleInfo=installed.get(i-patches.length-drums.length);
      a.busy("Loading instrument samples…",()->{
       SampledInstruments.Pack pack=a.instrumentLibrary.load(sampleInfo);
       a.runOnUiThread(()->{a.selectedSampleInstrument=pack;a.selectedSampleInstrumentId=sampleInfo.filename;a.liveMidi.voices.setSamplePack(pack);instrument.setText(pack.name+"  ▾");a.toast("Ready: "+pack.name+" ("+pack.regions.length+" sample regions)");});
      });}
   });
  });
  options.setOnClickListener(v->{
   if(a.midiTake!=null){a.toast("Stop recording before changing performance settings.");return;}
   ScrollView sc=new ScrollView(a);LinearLayout settings=box();sc.addView(settings);
   caption(settings,"KEY / SCALE");settings.addView(root);settings.addView(scale);
   caption(settings,"PROGRESSION");settings.addView(progression);
   caption(settings,"PAD MODE / OCTAVE / LAYOUT");settings.addView(mode);settings.addView(octave);settings.addView(layout);
   caption(settings,"RECORD COUNT-IN");settings.addView(countIn);
   CheckBox playback=new CheckBox(a),duringRecording=new CheckBox(a);
   playback.setText("Metronome during playback");playback.setChecked(playbackClick[0]);settings.addView(playback);
   duringRecording.setText("Metronome during recording");duringRecording.setChecked(recordClick[0]);settings.addView(duringRecording);
   caption(settings,"CLICK VOLUME");SeekBar clickVolume=new SeekBar(a);clickVolume.setMax(200);clickVolume.setProgress(Math.round(a.engine.clickVolume*100));settings.addView(clickVolume);
   new AlertDialog.Builder(a).setTitle("Performance settings").setView(sc).setPositiveButton("Apply",(d,w)->{
     playbackClick[0]=playback.isChecked();recordClick[0]=duringRecording.isChecked();a.engine.clickVolume=clickVolume.getProgress()/100f;updateClick.run();clickButton.setText(playbackClick[0]?"CLICK ON":"CLICK OFF");updateChords.run();
     // Restore our spinners to detached parent; only the temporary settings dialog owns them.
   }).setOnDismissListener(d->{for(Spinner s:spinners)if(s.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)s.getParent()).removeView(s);}).show();
  });
  clickButton.setOnClickListener(v->{playbackClick[0]=!playbackClick[0];clickButton.setText(playbackClick[0]?"CLICK ON":"CLICK OFF");updateClick.run();});
  play.setOnClickListener(v->{if(a.midiTake!=null){a.toast("Stop recording before pausing the beat.");return;}if(a.engine.playing)a.engine.pause();else if(a.requestAudioFocus()){if(!a.engine.loop&&a.session.end()>0&&a.engine.position>=a.session.end())a.engine.position=0;a.engine.play(a.session);}updateClick.run();a.updateTransport();});
  loop.setOnClickListener(v->{a.engine.loop=!a.engine.loop;loop.setText(a.engine.loop?"LOOP ON":"LOOP OFF");a.updateTransport();});
  halt.setOnClickListener(v->{pads.releaseAll();if(a.midiTake!=null)a.finishMidiTake();a.engine.stop();updateClick.run();a.updateTransport();});
  stopTake.setOnClickListener(v->{if(a.midiTake==null)return;pads.releaseAll();a.finishMidiTake();updateClick.run();});
  record.setOnClickListener(v->{
   if(a.midiTake!=null||a.working)return;
   if(a.session.tracks.size()>=Core.MAX_TRACKS){a.toast("No free track for this Play It take.");return;}
   pads.releaseAll();
   a.startCountedMidiTake(true,new int[]{0,4,8}[Math.max(0,countIn.getSelectedItemPosition())]);
   updateClick.run();
  });
  drumButton.setOnClickListener(v->{if(a.midiTake!=null){a.toast("Stop recording before opening drum pads.");return;}dialog.dismiss();new BeatPilotDrumScreen(a).open();});
  beatPilotButton.setOnClickListener(v->{if(a.midiTake!=null){a.toast("Stop the current take before making a new beat.");return;}dialog.dismiss();beatPilot();});
  close.setOnClickListener(v->dialog.dismiss());
  final Runnable[] tick={null};tick[0]=()->{
   if(!dialog.isShowing()||a.dead)return;
   MidiTake take=a.midiTake;boolean busy=a.working;
   long beat=Math.max(1,Core.beat(a.session.bpm));int sig=Math.max(1,a.session.timeSignature);
   if(take!=null&&take.pending()){
    int beatNum=CountIn.number(a.engine.countInTotal-a.engine.countInRemaining,a.session.bpm,a.engine.countInBeats);
    status.setText("GET READY · song starts after the last click");
    beatTitle.setText("COUNT  "+beatNum+" / "+a.engine.countInBeats);
   }else{
    int n=(int)Math.floorMod(a.engine.position/beat,sig)+1;
    beatTitle.setText("BEAT "+n+" / "+sig+"  ·  "+a.session.bpm+" BPM");
    status.setText(take!=null?"● RECORDING · "+String.format(Locale.US,"%.1f s",take.elapsedFrames()/(double)Core.RATE):busy?"Saving your take…":"Ready · hold, slide or play several fingers");
   }
   record.setEnabled(take==null&&!busy);stopTake.setEnabled(take!=null);
   play.setEnabled(take==null);loop.setEnabled(take==null&&!busy);loop.setText(a.engine.loop?"LOOP ON":"LOOP OFF");
   play.setText(a.engine.playing?"Ⅱ Pause beat":"▶ Play beat");
   instrument.setEnabled(take==null&&!busy);options.setEnabled(take==null&&!busy);
   updateClick.run();a.handler.postDelayed(tick[0],45);
  };
  dialog.setOnDismissListener(d->{a.handler.removeCallbacks(tick[0]);pads.releaseAll();if(a.midiTake!=null)a.finishMidiTake();a.engine.click=(playbackClick[0]&&a.engine.playing)||(recordClick[0]&&a.midiTake!=null);if(!a.midi.connected)a.liveMidi.stop();});
  dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);
  updateChords.run();a.handler.post(tick[0]);
 }

 /** Draws real instrument pads, not a representation of chord names. */
 final class PadGrid extends View {
  final Spinner root,scale,mode,octave,progression,layout;
  final Paint paint=new Paint(3);
  final TouchNotes fingers=new TouchNotes((status,note,velocity)->{
   a.liveMidi.voices.message(status,note,velocity);
   MidiTake take=a.midiTake;if(take!=null)take.add(status,note,velocity);
  });
  PadGrid(Spinner r,Spinner s,Spinner m,Spinner o,Spinner p,Spinner l){
   super(a);root=r;scale=s;mode=m;octave=o;progression=p;layout=l;
   setMinimumHeight(a.dp(210));setContentDescription("Playable chord and melody pads. Multitouch enabled.");
  }
  int count(){return mode.getSelectedItemPosition()==1?8:new int[]{4,6,8}[Math.min(2,layout.getSelectedItemPosition())];}
  int cols(){int c=count();return c==4?2:c==6?3:4;}
  int cell(float x,float y){if(x<0||y<0||x>=getWidth()||y>=getHeight())return -1;return Math.min(1,(int)(y/(getHeight()/2f)))*cols()+Math.min(cols()-1,(int)(x/(getWidth()/(float)cols())));}
  int[] voice(int cell){int r=root.getSelectedItemPosition();boolean minor=scale.getSelectedItemPosition()==1;
   if(mode.getSelectedItemPosition()==1)return new int[]{PocketComposer.scale(r,minor,cell,octave.getSelectedItemPosition()+2)};
   int degree=PerformanceChords.degree(minor,progression.getSelectedItemPosition(),cell);
   int[] c=PocketComposer.chord(r,minor,degree);int shift=(octave.getSelectedItemPosition()-1)*12;
   for(int i=0;i<c.length;i++)c[i]+=shift;return c;
  }
  void change(int pointer,int cell){fingers.change(pointer,cell,cell<0?new int[0]:voice(cell));invalidate();}
  void releaseAll(){fingers.clear();invalidate();}
  protected void onDraw(Canvas canvas){super.onDraw(canvas);int count=count(),cols=cols();float w=getWidth()/(float)cols,h=getHeight()/2f;
   paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextAlign(Paint.Align.CENTER);
   for(int i=0;i<count;i++){
    boolean held=fingers.active(i);float x=(i%cols)*w,y=(i/cols)*h;
    paint.setColor(held?0xffa1c886:0xff444f58);canvas.drawRoundRect(x+4,y+4,x+w-4,y+h-4,a.dp(12),a.dp(12),paint);
    paint.setColor(held?0xff17231c:MainActivity.TEXT);paint.setTextSize(a.dp(cols>=4?16:23));
    int[] notes=voice(i);boolean melody=mode.getSelectedItemPosition()==1;
    String label=melody?PocketComposer.noteName(notes[0]):PocketComposer.chordName(root.getSelectedItemPosition(),scale.getSelectedItemPosition()==1,PerformanceChords.degree(scale.getSelectedItemPosition()==1,progression.getSelectedItemPosition(),i));
    canvas.drawText(label,x+w/2,y+h*.50f,paint);
    paint.setTextSize(a.dp(11));canvas.drawText((melody?"NOTE ":"CHORD ")+(i+1),x+w/2,y+h*.71f,paint);
   }
  }
  public boolean onTouchEvent(MotionEvent e){
   if(a.working||a.dead||!a.midiForeground||(a.midiTake!=null&&a.midiTake.pending())){releaseAll();return true;}
   int action=e.getActionMasked(),index=e.getActionIndex();
   if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
    getParent().requestDisallowInterceptTouchEvent(true);
    if(!a.liveMidi.running){if(!a.requestAudioFocus())return true;a.liveMidi.start();}
    change(e.getPointerId(index),cell(e.getX(index),e.getY(index)));
   }else if(action==MotionEvent.ACTION_MOVE){
    for(int i=0;i<e.getPointerCount();i++)change(e.getPointerId(i),cell(e.getX(i),e.getY(i)));
   }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){fingers.release(e.getPointerId(index));invalidate();}
   else if(action==MotionEvent.ACTION_CANCEL){releaseAll();}
   return true;
  }
 }
}

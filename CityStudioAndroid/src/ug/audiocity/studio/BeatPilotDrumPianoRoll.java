package ug.audiocity.studio;

import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Eight-lane, free-position drum piano roll with per-note length/velocity and sampled-audio reprint. */
final class BeatPilotDrumPianoRoll {
 final MainActivity a;
 final Core.Track track;
 final Core.Clip original;
 final float[][] waves=new float[8][];
 BeatPilotDrumRollData data;
 ArrayDeque<BeatPilotDrumRollData> history=new ArrayDeque<>();
 int velocity=105,grid=24;
 TextView hint;
 BeatPilotDrumPianoRoll(MainActivity a,Core.Track t,Core.Clip c){this.a=a;track=t;original=c;data=BeatPilotDrumRollData.decode(c.drumPattern);}
 void load()throws IOException{for(int i=0;i<8;i++)try(InputStream in=a.getAssets().open("samples/builtin/soundza/"+BeatPilotDrums.FILES[i])){waves[i]=SampleAudio.readWav(in);}}
 void auditionLane(int lane,int velocity){a.liveMidi.voices.message(0x90,36+lane,velocity);}
 void snapshot(){history.push(data.copy());while(history.size()>40)history.removeLast();}
 void show(){if(!a.canEdit()||a.working)return;a.busy("Loading Audio City drum piano roll…",()->{load();a.runOnUiThread(this::editor);});}
 void editor(){
  final SampledInstruments.Pack originalPack=a.selectedSampleInstrument;
  a.liveMidi.voices.setSamplePack(BeatPilotDrumScreen.pack(waves));a.liveMidi.start();
  LinearLayout root=a.column();root.setPadding(a.dp(9),a.dp(10),a.dp(9),a.dp(10));
  root.addView(a.label("AUDIO CITY · DRUM PIANO ROLL",18,MainActivity.TEXT,true));
  root.addView(a.label("Tap to add/select • drag to move • drag right edge to resize • long-press to delete",12,MainActivity.TEXT,false));
  hint=a.label("8 drum lanes • "+data.bars+" bars • "+data.hits.size()+" MIDI notes",12,MainActivity.ACCENT,false);root.addView(hint);
  RollCanvas canvas=new RollCanvas();root.addView(canvas,new LinearLayout.LayoutParams(-1,a.dp(345)));
  TextView vel=a.label("Velocity: "+velocity,13,MainActivity.TEXT,false);root.addView(vel);
  SeekBar bar=new SeekBar(a);bar.setMax(126);bar.setProgress(velocity-1);bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean u){velocity=p+1;vel.setText("Velocity: "+velocity);}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});root.addView(bar);
  LinearLayout actions=a.column();root.addView(actions);
  LinearLayout previewRow=new LinearLayout(a);actions.addView(previewRow);
  previewRow.addView(a.button("▶ Hear drum pattern",()->{a.preview(data.copy().render(waves));}),new LinearLayout.LayoutParams(0,a.dp(46),1));
  previewRow.addView(a.button("■ Stop preview",()->a.audition.stop()),new LinearLayout.LayoutParams(0,a.dp(46),1));
  root.addView(a.label("Tap a drum lane label or an existing note to hear its sample.",12,MainActivity.DIM,false));
  LinearLayout row=new LinearLayout(a);actions.addView(row);row.addView(a.button("Apply velocity",()->{if(canvas.selected!=null){snapshot();canvas.selected.velocity=velocity;canvas.invalidate();update();}}),new LinearLayout.LayoutParams(0,a.dp(46),1));row.addView(a.button("Delete",()->{if(canvas.selected!=null){snapshot();data.hits.remove(canvas.selected);canvas.selected=null;canvas.invalidate();update();}}),new LinearLayout.LayoutParams(0,a.dp(46),1));row.addView(a.button("Undo",()->{if(!history.isEmpty()){data=history.pop();canvas.selected=null;canvas.invalidate();update();}}),new LinearLayout.LayoutParams(0,a.dp(46),1));
  LinearLayout r2=new LinearLayout(a);actions.addView(r2);r2.addView(a.button("Grid 1/16",()->{grid=24;canvas.invalidate();}),new LinearLayout.LayoutParams(0,a.dp(42),1));r2.addView(a.button("Grid 1/32",()->{grid=12;canvas.invalidate();}),new LinearLayout.LayoutParams(0,a.dp(42),1));r2.addView(a.button("Free timing",()->{grid=1;canvas.invalidate();}),new LinearLayout.LayoutParams(0,a.dp(42),1));
  LinearLayout r3=new LinearLayout(a);actions.addView(r3);r3.addView(a.button("Zoom −",()->{canvas.pixelsPerBeat=Math.max(a.dp(24),canvas.pixelsPerBeat/1.4f);canvas.invalidate();}),new LinearLayout.LayoutParams(0,a.dp(40),1));r3.addView(a.button("Zoom +",()->{canvas.pixelsPerBeat=Math.min(a.dp(180),canvas.pixelsPerBeat*1.4f);canvas.invalidate();}),new LinearLayout.LayoutParams(0,a.dp(40),1));
  ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.addView(root);
  AlertDialog dialog=new AlertDialog.Builder(a).setTitle(original.name).setView(scroll).setNegativeButton("Discard",null).setPositiveButton("Save MIDI + audio",null).create();
  dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->save(dialog)));
  dialog.setOnDismissListener(d->{for(int i=0;i<8;i++)a.liveMidi.voices.message(0x80,36+i,0);a.liveMidi.voices.setSamplePack(originalPack);a.audition.stop();});
  dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout(-1,-1);
 }
 void update(){hint.setText("8 drum lanes • "+data.bars+" bars • "+data.hits.size()+" MIDI notes");}
 void save(AlertDialog dialog){if(a.working)return;final BeatPilotDrumRollData snapshot=data.copy();final Core.Session project=a.session;final long pos=original.start;
  a.checkpoint();a.busy("Updating drum MIDI and sampled playback…",()->{
   float[] audio=snapshot.render(waves);File dir=new File(project.directory);Core.Clip fresh=AudioIO.store(dir,audio,original.name,pos);
   fresh.drumPattern=snapshot.encode();fresh.notes=snapshot.notes();String file="beatpilot-roll-"+UUID.randomUUID()+".mid";
   try(FileOutputStream out=new FileOutputStream(new File(dir,file))){snapshot.writeMidi(out);}fresh.midiFile=file;
   a.runOnUiThread(()->{
    if(a.session!=project||!track.clips.contains(original)){new File(dir,fresh.file).delete();new File(dir,file).delete();a.toast("Project changed; edit was not saved.");return;}
    int index=track.clips.indexOf(original);track.clips.set(index,fresh);a.clip=fresh;a.selected=track;a.save(false);a.refresh();dialog.dismiss();a.toast("Drum piano roll saved to Arrange as editable MIDI + audio.");
   });
  });
 }
 final class RollCanvas extends View {
  final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
  final float labelWidth=a.dp(76),rowHeight=a.dp(38);
  float pixelsPerBeat=a.dp(64),scrollX=0,lastX,lastY,downX,downY;
  BeatPilotDrumRollData.Hit selected;
  int gesture;long pressedAt;
  int baseTick,baseLength,baseLane;boolean changed;
  RollCanvas(){super(a);setContentDescription("Drum MIDI piano roll editor");}
  float x(int tick){return labelWidth+(tick/(float)BeatPilotDrumRollData.TICKS_PER_BEAT)*pixelsPerBeat-scrollX;}
  int tick(float x){return Math.max(0,Math.min(data.bars*384-1,Math.round((x-labelWidth+scrollX)/pixelsPerBeat*96)));}
  int snap(int t){return grid==1?t:Math.max(0,Math.round(t/(float)grid)*grid);}
  int lane(float y){return Math.max(0,Math.min(7,(int)(y/rowHeight)));}
  BeatPilotDrumRollData.Hit hit(float xx,float yy){int lane=lane(yy),t=tick(xx);for(int i=data.hits.size()-1;i>=0;i--){BeatPilotDrumRollData.Hit h=data.hits.get(i);if(h.lane==lane&&t>=h.tick&&t<=h.tick+h.length)return h;}return null;}
  @Override protected void onDraw(Canvas c){c.drawColor(0xff22282d);p.setTextSize(a.dp(11));
   for(int i=0;i<8;i++){p.setColor(i%2==0?0xff303941:0xff293239);c.drawRect(labelWidth,i*rowHeight,getWidth(),(i+1)*rowHeight,p);p.setColor(0xffdce4e6);c.drawText(BeatPilotDrums.LABELS[i],a.dp(4),i*rowHeight+rowHeight*.61f,p);}
   int total=data.bars*384;for(int tick=0;tick<=total;tick+=24){float xx=x(tick);if(xx<labelWidth||xx>getWidth())continue;p.setColor(tick%384==0?0xff91a2a6:tick%96==0?0xff52616a:0xff38464d);c.drawRect(xx,0,xx+(tick%384==0?2:1),8*rowHeight,p);}
   for(BeatPilotDrumRollData.Hit h:data.hits){float left=x(h.tick),right=x(h.tick+h.length);if(right<labelWidth||left>getWidth())continue;p.setColor(h==selected?0xffe2eec0:android.graphics.Color.rgb(50+h.velocity/4,110+h.velocity/2,95));c.drawRoundRect(Math.max(labelWidth,left),h.lane*rowHeight+3,Math.max(labelWidth+2,right),h.lane*rowHeight+rowHeight-4,a.dp(3),a.dp(3),p);p.setColor(0xffefffff);c.drawRect(Math.max(labelWidth,right-a.dp(5)),h.lane*rowHeight+7,Math.max(labelWidth,right-a.dp(3)),(h.lane+1)*rowHeight-8,p);}
  }
  @Override public boolean onTouchEvent(MotionEvent e){float xx=e.getX(),yy=e.getY();switch(e.getActionMasked()){
   case MotionEvent.ACTION_DOWN:
    getParent().requestDisallowInterceptTouchEvent(true);downX=lastX=xx;downY=lastY=yy;pressedAt=android.os.SystemClock.uptimeMillis();changed=false;
    if(xx<labelWidth){gesture=4;return true;}
    selected=hit(xx,yy);if(selected!=null){auditionLane(selected.lane,selected.velocity);baseTick=selected.tick;baseLength=selected.length;baseLane=selected.lane;gesture=(x(selected.tick+selected.length)-xx<a.dp(18))?2:1;}else gesture=3;
    invalidate();return true;
   case MotionEvent.ACTION_MOVE:
    if(Math.abs(xx-downX)>a.dp(5)||Math.abs(yy-downY)>a.dp(5)){
     if(gesture==1&&selected!=null){if(!changed){snapshot();changed=true;}int oldTick=selected.tick,oldLane=selected.lane;selected.tick=Math.min(data.bars*384-selected.length,snap(Math.max(0,baseTick+tick(xx)-tick(downX))));selected.lane=Math.max(0,Math.min(7,baseLane+Math.round((yy-downY)/rowHeight)));if(selected.tick!=oldTick||selected.lane!=oldLane)auditionLane(selected.lane,selected.velocity);}
     else if(gesture==2&&selected!=null){if(!changed){snapshot();changed=true;}int oldLength=selected.length;selected.length=Math.max(1,Math.min(data.bars*384-selected.tick,snap(Math.max(grid,baseLength+tick(xx)-tick(downX)))));if(selected.length!=oldLength)auditionLane(selected.lane,selected.velocity);}
     else if(gesture==3){scrollX=Math.max(0,scrollX-(xx-lastX));}invalidate();
    }lastX=xx;lastY=yy;return true;
   case MotionEvent.ACTION_UP:
    getParent().requestDisallowInterceptTouchEvent(false);
    if(gesture==4){auditionLane(lane(yy),velocity);invalidate();return true;}
    if(!changed&&xx>=labelWidth){if(selected!=null&&android.os.SystemClock.uptimeMillis()-pressedAt>550){snapshot();data.hits.remove(selected);selected=null;}
     else if(selected==null){int pos=snap(tick(xx));if(pos>=data.bars*384)pos=data.bars*384-1;int len=Math.min(24,data.bars*384-pos);snapshot();data.add(lane(yy),pos,len,velocity);selected=data.hits.get(data.hits.size()-1);auditionLane(selected.lane,selected.velocity);}}
    update();invalidate();return true;
   default:return true;
   }
  }
 }
}

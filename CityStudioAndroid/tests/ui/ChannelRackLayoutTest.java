package ug.audiocity.studio;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import java.io.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real Android measure/layout/draw tests. Audio, disk loading and app startup are isolated. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=33,qualifiers="w393dp-h820dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ChannelRackLayoutTest {
    public static class Harness extends MainActivity {
        @Override boolean requestAudioFocus(){return false;}
        @Override void updateTransport(){}
        @Override void save(boolean announce){}
        @Override void refresh(){}
        @Override void busy(String message,Task task){} // View tests do not perform media I/O.
    }
    static int px(Harness a,int dp){return Math.round(dp*a.getResources().getDisplayMetrics().density);}
    static void layout(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}

    @Test public void fullPatternScreenHasVisiblePads() throws Exception {
        Harness a=Robolectric.buildActivity(Harness.class).get();a.setTheme(android.R.style.Theme_Material_NoActionBar);
        a.session=new Core.Session();a.session.bpm=100;PatternLibrary.blank(a.session);
        a.liveMidi=new LiveMidi(a.engine,Synth.factory(0));
        BeatPilotDrumScreen rack=new BeatPilotDrumScreen(a);
        ReflectionHelpers.setField(rack,"project",a.session);
        float[][] waves=ReflectionHelpers.getField(rack,"samples");for(int i=0;i<8;i++)waves[i]=new float[200];
        ReflectionHelpers.callInstanceMethod(rack,"show");
        Dialog dialog=ReflectionHelpers.getField(rack,"dialog");
        try {
            View decor=dialog.getWindow().getDecorView();layout(decor,px(a,393),px(a,820));
            ReflectionHelpers.callInstanceMethod(rack,"resizeGrid");layout(decor,px(a,393),px(a,820));
            android.widget.Button[][] pads=ReflectionHelpers.getField(rack,"cells");
            int missing=0;for(int l=0;l<8;l++)for(int s=0;s<16;s++)if(pads[l][s].getWidth()<px(a,5))missing++;
            android.widget.HorizontalScrollView viewport=ReflectionHelpers.getField(rack,"gridScroll");
            System.out.println("Pattern rack: missing/narrow pads="+missing+", first pad="+pads[0][0].getWidth()+"px, viewport="+viewport.getWidth()+"px, matrix="+viewport.getChildAt(0).getWidth()+"px");
            boolean before=Boolean.getBoolean("rack.before");
            if(before)assertTrue("Old layout must reproduce the disappearing pads",missing>0);
            else {
                assertEquals("Every one of the 128 step switches must have visible width",0,missing);
                assertTrue(pads[0][0].getWidth()>=px(a,12));
                assertTrue(pads[7][15].getHeight()>=px(a,32));
                BeatPilotDrumPattern data=ReflectionHelpers.getField(rack,"pattern");
                pads[0][0].performClick();assertEquals(105,data.get(0,0,0));
                pads[0][0].performClick();assertEquals(0,data.get(0,0,0));
                android.graphics.Rect visible=new android.graphics.Rect();
                for(int l=0;l<8;l++)for(int s=0;s<16;s++){visible.set(0,0,pads[l][s].getWidth(),pads[l][s].getHeight());((ViewGroup)decor).offsetDescendantRectToMyCoords(pads[l][s],visible);assertTrue("Pad stays inside visible sheet",visible.left>=0&&visible.top>=0&&visible.right<=decor.getWidth()&&visible.bottom<=decor.getHeight());}
                // Render native Android widgets and the same Pad drawable used in the APK.
                for(int l=0;l<8;l++)for(int s=0;s<16;s++){pads[l][s].setSelected((l==0&&(s==0||s==8))||(l==1&&(s==4||s==12))||(l==3&&s%2==0));pads[l][s].setActivated(s==8);}
            }
            File out=new File(System.getProperty("rack.output","build/ui-layout"));assertTrue(out.isDirectory()||out.mkdirs());
            Bitmap bitmap=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(bitmap));
            try(OutputStream file=new FileOutputStream(new File(out,before?"pattern-before.png":"pattern-after.png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,file));}bitmap.recycle();
        } finally {rack.dispose();a.jobs.shutdownNow();}
    }

    @Test public void gridFillsWrapContentParentAtPhoneAndTabletWidths() {
        if(Boolean.getBoolean("rack.before"))return;
        Harness a=Robolectric.buildActivity(Harness.class).get();a.setTheme(android.R.style.Theme_Material_NoActionBar);
        try {
            for(int dp:new int[]{240,320,360,393,480,800}) {
                ChannelRackView rack=new ChannelRackView(a);ScrollView parent=new ScrollView(a);parent.addView(rack,new android.widget.FrameLayout.LayoutParams(-1,-2));
                layout(parent,px(a,dp),px(a,450));assertEquals("Rack fills actual scroll viewport",px(a,dp),rack.getMeasuredWidth());
                for(int lane=0;lane<8;lane++)for(int step=0;step<16;step++)assertTrue("Pad survives wrap-content measurement at "+dp+"dp",rack.cells[lane][step].getWidth()>=px(a,12));
                int fit=rack.cells[0][0].getWidth();rack.setVisibleSteps(8);layout(parent,px(a,dp),px(a,450));
                assertTrue("Zoom increases physical pad width",rack.cells[0][0].getWidth()>fit);
                assertTrue("Zoomed grid scrolls to later steps",rack.cells[0][15].getRight()>rack.scroll.getWidth());
                rack.setVisibleSteps(16);layout(parent,px(a,dp),px(a,450));assertEquals("Fit restores widths",fit,rack.cells[0][0].getWidth());
                for(int step=0;step<16;step++)for(int lane=1;lane<8;lane++)assertEquals("Columns align across all lanes",rack.cells[0][step].getLeft(),rack.cells[lane][step].getLeft());
            }
        } finally {a.jobs.shutdownNow();}
    }
}

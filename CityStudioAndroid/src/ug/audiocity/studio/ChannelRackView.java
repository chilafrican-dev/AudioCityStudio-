package ug.audiocity.studio;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

/** Measured, full-width channel rack. No zero-width weighted scroll-content bootstrap. */
final class ChannelRackView extends LinearLayout {
    static final int LANES = 8, STEPS = 16;
    final Button[][] cells = new Button[LANES][STEPS];
    final Button[] sounds = new Button[LANES], arrows = new Button[LANES];
    final TextView[] meters = new TextView[LANES];
    final HorizontalScrollView scroll;
    private final LinearLayout names, matrix, numbers;
    private final LinearLayout[] rows = new LinearLayout[LANES];
    private final float density;
    private int visibleSteps = 16;
    private static final int[] COLORS = {0xffbd704f,0xff607fb2,0xffbb568c,0xff8073b5,0xffa6519c,0xff879e4c,0xffb48850,0xff59959e};

    ChannelRackView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL); setBaselineAligned(false);
        names = new LinearLayout(context); names.setOrientation(VERTICAL);
        addView(names,new LinearLayout.LayoutParams(dp(104),ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView channel = text("CHANNEL",8); names.addView(channel,new LinearLayout.LayoutParams(-1,dp(24)));
        scroll = new HorizontalScrollView(context); scroll.setFillViewport(true); scroll.setHorizontalScrollBarEnabled(true);
        scroll.setContentDescription("Drum steps. Swipe horizontally when zoomed.");
        addView(scroll,new LinearLayout.LayoutParams(dp(256),-2));
        matrix = new LinearLayout(context); matrix.setOrientation(VERTICAL);
        scroll.addView(matrix,new android.widget.FrameLayout.LayoutParams(dp(268),-2));
        numbers = new LinearLayout(context); matrix.addView(numbers,new LinearLayout.LayoutParams(-1,dp(24)));
        for(int step=0;step<STEPS;step++) {
            TextView n=text(step%4==0?String.valueOf(step/4+1):"·",9);n.setGravity(Gravity.CENTER);
            numbers.addView(n,stepLayout(dp(16),step,-1));
        }
        for(int lane=0;lane<LANES;lane++) {
            LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(0,dp(2),0,dp(2));
            names.addView(header,new LinearLayout.LayoutParams(-1,dp(36)));
            meters[lane]=text("▏",15);meters[lane].setTextColor(0xff263238);header.addView(meters[lane],new LinearLayout.LayoutParams(dp(7),-2));
            Button sound=button(BeatPilotDrums.LABELS[lane]);sound.setTextSize(9);sound.setMaxLines(2);sound.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);sound.setPadding(dp(5),0,0,0);sound.setEllipsize(android.text.TextUtils.TruncateAt.END);sound.setBackground(StudioFinish.channel(density,COLORS[lane]));sounds[lane]=sound;
            header.addView(sound,new LinearLayout.LayoutParams(0,-1,1));
            Button arrow=button("♪▾");arrow.setTextSize(13);arrow.setBackground(StudioFinish.channel(density,COLORS[lane]));arrow.setContentDescription("Choose "+BeatPilotDrums.LABELS[lane]+" sample");arrows[lane]=arrow;
            header.addView(arrow,new LinearLayout.LayoutParams(dp(34),-1));
            rows[lane]=new LinearLayout(context);matrix.addView(rows[lane],new LinearLayout.LayoutParams(-1,dp(36)));
            for(int step=0;step<STEPS;step++) {
                Button pad=button("");pad.setPadding(0,0,0,0);pad.setBackground(new StudioFinish.Pad(density,(step/4)%2==0));
                pad.setContentDescription(BeatPilotDrums.LABELS[lane]+", step "+(step+1));cells[lane][step]=pad;
                rows[lane].addView(pad,stepLayout(dp(16),step,-1));
            }
        }
    }
    private TextView text(String value,int size){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(size);t.setTextColor(0xffd4e0e5);return t;}
    private Button button(String label){Button b=new Button(getContext());b.setText(label);b.setAllCaps(false);b.setTextColor(0xffeeeae2);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setIncludeFontPadding(false);b.setPadding(0,0,0,0);b.setElevation(0);return b;}
    private int dp(float value){return Math.max(1,Math.round(value*density));}
    private LinearLayout.LayoutParams stepLayout(int width,int step,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,height);if(step>0&&step%4==0)p.leftMargin=dp(3);return p;}
    void setVisibleSteps(int count){if(count!=8&&count!=16)throw new IllegalArgumentException("Show eight or sixteen steps");if(count!=visibleSteps){visibleSteps=count;requestLayout();scroll.post(()->scroll.scrollTo(0,0));}}
    int visibleSteps(){return visibleSteps;}
    void revealStep(int step){if(visibleSteps==8&&step>=0&&step<STEPS)scroll.smoothScrollTo(cells[0][step<8?0:8].getLeft(),0);}
    private void width(View v,int width){ViewGroup.LayoutParams p=v.getLayoutParams();if(p.width!=width){p.width=width;v.setLayoutParams(p);}}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getMode(widthSpec)==MeasureSpec.UNSPECIFIED?getResources().getDisplayMetrics().widthPixels:MeasureSpec.getSize(widthSpec);
        width=Math.max(dp(128),width);
        int header=Math.min(dp(104),Math.max(dp(76),width*28/100));
        int viewport=Math.max(dp(24),width-header);
        width(names,header);width(scroll,viewport);
        int gap=dp(3),visibleGaps=visibleSteps/4-1;
        int usable=Math.max(0,viewport-visibleGaps*gap),base=Math.max(dp(12),usable/visibleSteps);
        int remainder=base==usable/visibleSteps?usable%visibleSteps:0,total=0;
        for(int step=0;step<STEPS;step++) {
            int cellWidth=base+(step%visibleSteps<remainder?1:0);total+=cellWidth+(step>0&&step%4==0?gap:0);
            width(numbers.getChildAt(step),cellWidth);
            for(int lane=0;lane<LANES;lane++)width(cells[lane][step],cellWidth);
        }
        width(matrix,total);
        // ScrollView may offer AT_MOST to wrap-content children. Fill its actual viewport
        // before distributing step widths, rather than deriving a viewport from empty pads.
        super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),heightSpec);
    }
}

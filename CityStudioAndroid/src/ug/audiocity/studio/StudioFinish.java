package ug.audiocity.studio;
import android.graphics.*;import android.graphics.drawable.*;
/** Lightweight vector-drawn metalwork: no large bitmaps or extra APK assets. */
final class StudioFinish {
 static Drawable button(float density){StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_pressed},surface(density,0xff4d504e,0xff202422,0xffd5b97d));states.addState(new int[]{android.R.attr.state_selected},surface(density,0xff494438,0xff28271f,0xffbda16b));states.addState(new int[]{},surface(density,0xff353a3f,0xff1d2125,0xff50575c));return states;}
 static GradientDrawable surface(float d,int top,int bottom,int edge){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});g.setCornerRadius(5*d);g.setStroke(Math.max(1,(int)d),edge);return g;}
 static final class Chassis extends Drawable {
  final Paint p=new Paint(3);final float d;Chassis(float density){d=density;}
  public void draw(Canvas c){Rect b=getBounds();p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(0,b.top,0,b.bottom,0xff1f232b,0xff171a21,Shader.TileMode.CLAMP));c.drawRect(b,p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d);p.setColor(0xff2b303b);c.drawRoundRect(new RectF(b.left+d,b.top+d,b.right-d,b.bottom-d),10*d,10*d,p);p.setStyle(Paint.Style.FILL);}
  public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.OPAQUE;}
 }
 static Drawable rackPanel(float d){GradientDrawable g=surface(d,0xff424e55,0xff303a41,0xff59656d);g.setCornerRadius(4*d);return g;}
 static Drawable rackKey(float d){StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_pressed},surface(d,0xff627078,0xff3b474e,0xff8e9a9f));states.addState(new int[]{android.R.attr.state_selected},surface(d,0xff627241,0xff3d4b30,0xffa0be58));states.addState(new int[]{},surface(d,0xff49565e,0xff323e45,0xff5b6971));return states;}
 static int mix(int color,int other,float ratio){int r=(int)(((color>>16)&255)*(1-ratio)+((other>>16)&255)*ratio),g=(int)(((color>>8)&255)*(1-ratio)+((other>>8)&255)*ratio),b=(int)((color&255)*(1-ratio)+(other&255)*ratio);return 0xff000000|(r<<16)|(g<<8)|b;}
 static Drawable channel(float d,int color){StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_pressed},surface(d,mix(color,0xffe1e7e8,.22f),mix(color,0xff141c22,.35f),mix(color,0xfff1f5f5,.3f)));states.addState(new int[]{},surface(d,mix(color,0xff4a555d,.28f),mix(color,0xff18242b,.48f),mix(color,0xffa7b2b6,.25f)));return states;}
 /** Small channel-rack switches. Four neutral steps alternate with four muted red steps. */
 static final class Pad extends Drawable {
  private final Paint p=new Paint(3);private final float d;private final boolean neutral;private boolean on,head,pressed;
  Pad(float density,boolean group){d=density;neutral=group;}
  protected boolean onStateChange(int[] states){boolean a=false,b=false,c=false;for(int state:states){a|=state==android.R.attr.state_selected;b|=state==android.R.attr.state_activated;c|=state==android.R.attr.state_pressed;}if(a==on&&b==head&&c==pressed)return false;on=a;head=b;pressed=c;invalidateSelf();return true;}
  public boolean isStateful(){return true;}
  public void draw(Canvas c){Rect b=getBounds();float gap=1.1f*d,face=Math.min(21*d,b.height()-5*d),top=b.exactCenterY()-face/2;RectF r=new RectF(b.left+gap,top,b.right-gap,top+face);if(r.width()<=2*d||face<=0)return;
   // Recess and lower lip give each switch its own physical edge.
   p.setShader(null);p.setColor(0xff1d282e);c.drawRoundRect(r.left-d,r.top-d,r.right+d,r.bottom+2*d,2*d,2*d,p);
   int hi=neutral?(on?0xffd8e2e4:0xff526068):(on?0xffe5aaaa:0xff73585d),lo=neutral?(on?0xffa5b7bd:0xff344148):(on?0xffb97980:0xff4e3d43);if(pressed){hi=0xffeaf0ec;lo=0xffa9bfac;}
   p.setShader(new LinearGradient(0,r.top,0,r.bottom,hi,lo,Shader.TileMode.CLAMP));c.drawRoundRect(r,1.8f*d,1.8f*d,p);p.setShader(null);
   p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(.8f*d);p.setColor(head?0xffc0ec64:on?0xffc7d0d0:0xff29343a);c.drawRoundRect(r,1.8f*d,1.8f*d,p);p.setStyle(Paint.Style.FILL);
   p.setColor(on?0x99ffffff:0x558c9ba2);c.drawRect(r.left+1.5f*d,r.top+d,r.right-1.5f*d,r.top+2*d,p);
   // Dark inset at the top of inactive switches, a pale glint on active ones.
   p.setColor(on?(neutral?0xfff1f5f6:0xffffd6d2):0xff263238);c.drawRoundRect(r.left+2*d,r.top+3*d,r.right-2*d,r.top+5*d,.5f*d,.5f*d,p);
   if(head){p.setColor(0xffc0ec64);c.drawRoundRect(r.left+d,r.bottom-2.5f*d,r.right-d,r.bottom-d,.6f*d,.6f*d,p);}
  }
  public void setAlpha(int alpha){p.setAlpha(alpha);}public void setColorFilter(ColorFilter filter){p.setColorFilter(filter);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
 }
}

package ug.audiocity.studio;
/** Swipes pan. A clip can only move after a stationary hold; a pinch never edits it. */
public final class ArrangeGesture {
 public static final int WAIT=0,PAN=1,EDIT=2;
 private long down;private float x,y,slop;private boolean clip;private int state;
 public void begin(long time,float x,float y,float slop,boolean clip){down=time;this.x=x;this.y=y;this.slop=slop;this.clip=clip;state=WAIT;}
 public int move(long time,float x,float y){if(state!=WAIT)return state;float dx=x-this.x,dy=y-this.y;if(Math.hypot(dx,dy)<=slop)return WAIT;state=clip&&time-down>=350&&Math.abs(dx)>Math.abs(dy)*1.3?EDIT:PAN;return state;}
 public void pan(){state=PAN;}
 public static float zoomScroll(float scroll,float anchor,float oldZoom,float newZoom){return Math.max(0,(scroll+anchor)*newZoom/oldZoom-anchor);}
}

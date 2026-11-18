package ug.audiocity.studio;
/** A press belongs to the displayed bar at finger-down, even if playback follows another bar later. */
final class DrumStepPress {
 private int bar=-1;
 void down(int displayedBar){bar=displayedBar;}
 void cancel(){bar=-1;}
 int consume(int displayedBar){int chosen=bar<0?displayedBar:bar;bar=-1;return chosen;}
}

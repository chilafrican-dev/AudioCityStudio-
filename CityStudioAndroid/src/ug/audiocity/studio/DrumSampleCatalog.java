package ug.audiocity.studio;
/** Stable allowlisted factory/personal one-shots. Paths are never read from arbitrary project text. */
public final class DrumSampleCatalog {
 public static final int AFRO_BASE=1000;private DrumSampleCatalog(){}
 public static final String[][] FILES={
  {"000-soundza-acoustic-kick-drum.wav","003-soundza-deep-house-kick.wav","004-soundza-deep-kick.wav","058-soundza-hard-punchy-kick-drum.wav","059-soundza-heavy-808-kick.wav","060-soundza-heavy-punch-kick.wav","061-soundza-resonant-kick.wav","062-soundza-compressed-kick-hit.wav","063-soundza-bright-acoustic-kick.wav","064-soundza-acoustic-bass-drum.wav","065-soundza-warm-acoustic-kick.wav","066-soundza-deep-acoustic-kick-hit.wav","067-soundza-deep-rough-kick.wav","068-soundza-heavy-thud-kick.wav","069-soundza-warm-deep-kick.wav","070-soundza-hip-hop-sub-kick.wav","092-soundza-big-room-kick.wav"},
  {"005-soundza-crisp-snare-hit.wav","006-soundza-warm-acoustic-snare.wav","007-soundza-snare-hit.wav","008-soundza-tight-snare-crack.wav","009-soundza-crisp-wood-snare.wav","080-soundza-finger-snare-hit.wav","081-soundza-bright-acoustic-snare.wav","082-soundza-crisp-electronic-snare.wav","083-soundza-bouncing-hybrid-snare.wav"},
  {"001-soundza-thick-hand-clap.wav","002-soundza-choir-clap-gospel-vocal.wav","010-soundza-tight-hand-clap.wav","011-soundza-short-crisp-clap.wav","084-soundza-crisp-handclap.wav","085-soundza-punchy-handclap.wav","086-soundza-clap.wav","132-soundza-long-layered-clap.wav"},
  {"015-soundza-tight-closed-hi-hat.wav","016-soundza-crisp-hi-hat-pedal.wav","018-soundza-warm-closed-hi-hat.wav","071-soundza-crisp-closed-hi-hat.wav","072-soundza-bright-hi-hat.wav","073-soundza-accent-jazz-hi-hat.wav","076-soundza-mouth-hi-hat.wav","077-soundza-close-hard-hi-hat.wav","078-soundza-bright-closed-hi-hat.wav","079-soundza-glassy-hi-hat.wav"},
  {"017-soundza-crisp-open-hi-hat.wav","074-soundza-open-hi-hat.wav","075-soundza-analog-hi-hat-open.wav"},
  {"019-soundza-egg-shaker-hit.wav","020-soundza-egg-shaker.wav","021-soundza-accent-egg-shaker.wav","022-soundza-plastic-egg-shaker.wav","023-soundza-crisp-cabasa.wav","024-soundza-soft-maraca-hit.wav","025-soundza-bright-maracas-shake.wav","026-soundza-wooden-maraca-hit.wav","027-soundza-sizzling-maraca-hit.wav","045-soundza-shekere-strike.wav","046-soundza-reverberant-shekere-hit.wav"},
  {"030-soundza-deep-conga-hit.wav","032-soundza-warm-conga-hit.wav","033-soundza-conga-hit.wav","034-soundza-low-conga-hit.wav","040-soundza-conga-slap.wav"},
  {"028-soundza-low-bongo-hit.wav","029-soundza-open-bongo-hit.wav","031-soundza-warm-bongo-hit.wav","035-soundza-short-bongo-hand-hit.wav","036-soundza-long-bongo-hand-hit.wav","037-soundza-bongo-hit-in-d.wav","038-soundza-dark-bongo-hit.wav","039-soundza-bongo-tapping.wav","093-soundza-bright-bongo-hit.wav"},
 };
 public static int defaultIndex(int lane){for(int i=0;i<FILES[lane].length;i++)if(FILES[lane][i].equals(BeatPilotDrums.FILES[lane]))return i;return 0;}
 public static boolean isAfro(int lane,int index){return index>=AFRO_BASE;}
 public static int afroChoice(int lane,int index){if(index<0||index>=AfroDrumPack.FILES.length)throw new IllegalArgumentException("Unknown Afro sample");return AFRO_BASE+index;}
 public static String file(int lane,int index){validate(lane,index);return isAfro(lane,index)?AfroDrumPack.FILES[index-AFRO_BASE]:FILES[lane][index];}
 public static String title(int lane,int index){if(isAfro(lane,index))return AfroDrumPack.TITLES[index-AFRO_BASE];String name=FILES[lane][index];name=name.substring(12,name.length()-4).replace('-', ' ');return Character.toUpperCase(name.charAt(0))+name.substring(1);}
 public static void validate(int lane,int index){if(lane<0||lane>=8||index<0||!(index<FILES[lane].length||index>=AFRO_BASE&&index<AFRO_BASE+AfroDrumPack.FILES.length))throw new IllegalArgumentException("Unknown drum sample");}
}

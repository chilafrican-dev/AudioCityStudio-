package ug.audiocity.studio;

/** Offline WSOLA-style transient-conscious time stretching; processes stereo in phase together.
 * Approximate algorithm. Intended for percussion, not guaranteed studio-grade on sustained music.
 */
public final class BeatPilotLoopStretch {
 private BeatPilotLoopStretch(){}
 public static float[] stretch(float[] source,int framesOut){
  if(source==null||source.length<4096||source.length%2!=0||framesOut<1024||framesOut>Core.RATE*90)throw new IllegalArgumentException("Invalid loop length");
  int n=source.length/2;if(n==framesOut)return source.clone();
  float[] result=new float[framesOut*2];
  final int grain=1024,hop=512,search=128;
  double ratio=n/(double)framesOut;
  // first segment, no overlap
  for(int k=0;k<Math.min(grain,framesOut);k++){result[2*k]=source[2*k];result[2*k+1]=source[2*k+1];}
  for(int out=hop;out<framesOut;out+=hop){
   int expected=(int)Math.round(out*ratio);int best=Math.min(Math.max(0,expected),Math.max(0,n-grain));
   double bestScore=-Double.MAX_VALUE;
   int low=Math.max(0,best-search),high=Math.min(Math.max(0,n-grain),best+search);
   // stereo-locked correlation; sample every fourth frame for mobile CPU
   for(int candidate=low;candidate<=high;candidate+=4){double dot=0,aa=1.e-10,bb=1.e-10;
    int overlap=Math.min(hop,framesOut-out);
    for(int t=0;t<overlap;t+=4){int ref=(out+t)*2,src=(candidate+t)*2;
     double x=result[ref]+result[ref+1],y=source[src]+source[src+1];dot+=x*y;aa+=x*x;bb+=y*y;
    }
    double score=dot/Math.sqrt(aa*bb);
    if(score>bestScore){bestScore=score;best=candidate;}
   }
   int limit=Math.min(grain,framesOut-out);
   for(int t=0;t<limit;t++){
    int src=Math.min(n-1,best+t)*2,dst=(out+t)*2;
    if(t<hop){float alpha=t/(float)hop;
      result[dst]=result[dst]*(1-alpha)+source[src]*alpha;
      result[dst+1]=result[dst+1]*(1-alpha)+source[src+1]*alpha;
    }else{result[dst]=source[src];result[dst+1]=source[src+1];}
   }
  }
  // fade last samples gently to prevent loop wrap clicks without changing the source asset.
  int fade=Math.min(96,framesOut/8);for(int t=0;t<fade;t++){
   float blend=t/(float)fade;int end=(framesOut-fade+t)*2,start=t*2;
   result[end]=result[end]*(1-blend)+result[start]*blend;
   result[end+1]=result[end+1]*(1-blend)+result[start+1]*blend;
  }
  return result;
 }
}

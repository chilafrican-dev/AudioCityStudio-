package ug.audiocity.studio;
public final class PhraseBankTests {
 static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);}
 public static void main(String[] a){
  check(PhraseBank.count()==8&&PhraseBank.NAMES.length==8,"eight phrases");
  for(int root=0;root<12;root++)for(int m=0;m<2;m++){boolean minor=m==1;
   for(int i=0;i<PhraseBank.count();i++){int[][] p=PhraseBank.phrase(i);int end=0;
    for(int[] n:p){check(n[1]>=0&&n[2]>0&&n[1]+n[2]<=16,"timing in one bar "+i);end=Math.max(end,n[1]+n[2]);
     int midi=PhraseBank.note(root,minor,n[0],3);check(midi>=0&&midi<=127,"midi range");
     int degree=PhraseBank.pent(minor)[n[0]];check(PhraseBank.safe(minor,degree),"pentatonic note root="+root+" minor="+minor+" phrase="+i);}
    check(end<=16,"fits a bar");}}
  PhraseBank.phrase(0)[0][0]=99;check(PhraseBank.phrase(0)[0][0]!=99,"defensive copy");
  System.out.println("PhraseBankTests OK");
 }
}

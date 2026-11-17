package ug.audiocity.studio;

public class SpeakerChoiceTests {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] a) {
        SpeakerChoice.Result[] r = {
            new SpeakerChoice.Result(0, 0, -20), new SpeakerChoice.Result(1, 0, -27), new SpeakerChoice.Result(2, 0, -21),
            new SpeakerChoice.Result(3, 0, -29), new SpeakerChoice.Result(0, 1, Double.NaN)};
        check(SpeakerChoice.recommend(r) == 1, "left-only wins: 7 dB less beat beats the earpiece after its quietness penalty");
        r[3] = new SpeakerChoice.Result(3, 0, -40);
        check(SpeakerChoice.recommend(r) == 3, "earpiece wins when it removes far more beat");
        check(SpeakerChoice.recommend(new SpeakerChoice.Result[]{null, new SpeakerChoice.Result(0, 0, Double.NaN)}) == -1, "nothing supported");
        float[] s = {1, .5f, -1, 0}; SpeakerChoice.route(s, 2, 1);
        check(s[0] == .75f && s[1] == 0 && s[2] == -.5f && s[3] == 0, "left-only routing keeps the mix on one side");
        check(SpeakerChoice.describe(r[1], -20).contains("7 dB less"), "describe");
        System.out.println("speaker choice checks passed");
    }
}

package ug.audiocity.studio;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import java.util.ArrayList;

/** Plays a short test sound through each speaker layout and measures how much reaches each microphone. */
final class SpeakerTest {
    private final MainActivity a;
    SpeakerTest(MainActivity a) { this.a = a; }

    static AudioDeviceInfo earpiece(MainActivity a) {
        try { AudioManager am = (AudioManager) a.getSystemService(android.content.Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) return d; } catch (Exception ignored) {}
        return null;
    }

    void open() {
        new AlertDialog.Builder(a).setTitle("Speaker test")
            .setMessage("Put the phone where you'll hold it while recording, stay quiet, and tap Start. The app plays a short sound through each speaker layout (about 20 seconds) and measures how much of it reaches the microphone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Start", (d, w) -> run()).show();
    }

    private void run() {
        a.engine.pause();
        a.busy("Testing speakers… stay quiet", () -> {
            final AudioDeviceInfo ear = earpiece(a);
            final ArrayList<SpeakerChoice.Result> results = new ArrayList<>();
            for (int mic = 0; mic < 2; mic++) for (int route = 0; route < 4; route++) {
                double db = Double.NaN;
                if (route != 3 || ear != null) { try { db = measure(route, mic, ear); } catch (Exception e) { db = Double.NaN; } }
                results.add(new SpeakerChoice.Result(route, mic, db));
            }
            final SpeakerChoice.Result[] all = results.toArray(new SpeakerChoice.Result[0]);
            a.runOnUiThread(() -> show(all));
        });
    }

    private void show(final SpeakerChoice.Result[] all) {
        final int best = SpeakerChoice.recommend(all);
        if (best < 0) { a.error("Speaker test", "The microphone could not be measured on this phone."); return; }
        final double base = all[0].bleedDb;
        String[] rows = new String[all.length];
        for (int i = 0; i < all.length; i++) rows[i] = (i == best ? "★ " : "") + SpeakerChoice.describe(all[i], base);
        final int[] pick = {best};
        new AlertDialog.Builder(a).setTitle("Pick a recording setup")
            .setSingleChoiceItems(rows, best, (d, i) -> pick[0] = i)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use this", (d, w) -> {
                SpeakerChoice.Result r = all[pick[0]];
                if (Double.isNaN(r.bleedDb)) { a.toast("That setup isn't supported on this phone."); return; }
                a.getPreferences(android.content.Context.MODE_PRIVATE).edit().putInt("speakerRoute", r.route).putInt("speakerMic", r.mic).apply();
                a.toast("Speaker recordings will use: " + SpeakerChoice.ROUTES[r.route] + " + " + SpeakerChoice.MICS[r.mic].toLowerCase() + ".");
            }).show();
    }

    /** Bleed level in dB for one layout: plays band-limited noise and measures the mic above its noise floor. */
    private double measure(int route, int mic, AudioDeviceInfo ear) throws Exception {
        int rate = Core.RATE, frames = (int) (rate * 1.2);
        float[] sound = new float[frames * 2]; java.util.Random rnd = new java.util.Random(11); double lp = 0, hp = 0, prev = 0;
        for (int i = 0; i < frames; i++) { double n = rnd.nextGaussian(); lp += .25 * (n - lp); hp = .97 * (hp + lp - prev); prev = lp; double env = Math.min(1, Math.min(i, frames - i) / (rate * .02)); float v = (float) (.18 * hp * env); sound[i * 2] = v; sound[i * 2 + 1] = v; }
        SpeakerChoice.route(sound, frames, route);
        int source = mic == 1 ? MediaRecorder.AudioSource.CAMCORDER : MediaRecorder.AudioSource.UNPROCESSED;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try { rec = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, 16384)); }
        catch (Exception e) { rec = null; }
        if ((rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) && mic == 0) { if (rec != null) rec.release(); rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, 16384)); }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); return Double.NaN; }
        AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(sound.length * 4).build();
        try {
            if (route == 3) { if (ear == null || !track.setPreferredDevice(ear)) return Double.NaN; }
            track.write(sound, 0, sound.length, AudioTrack.WRITE_BLOCKING);
            short[] buf = new short[1024]; rec.startRecording();
            double noise = power(rec, buf, (int) (rate * .5));
            track.play();
            power(rec, buf, (int) (rate * .15));
            double during = power(rec, buf, (int) (rate * .9));
            track.stop();
            return SpeakerChoice.bleedDb(during, noise);
        } finally { try { rec.stop(); } catch (Exception ignored) {} rec.release(); track.release(); }
    }

    private static double power(AudioRecord r, short[] buf, int frames) {
        double sum = 0; int got = 0;
        while (got < frames) { int n = r.read(buf, 0, Math.min(buf.length, frames - got)); if (n <= 0) break; for (int i = 0; i < n; i++) { double v = buf[i] / 32768.0; sum += v * v; } got += n; }
        return got == 0 ? 0 : sum / got;
    }
}

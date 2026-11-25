package ug.audiocity.lyrics;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;

/** Decodes any audio file Android can play (MP3, AAC/M4A, WAV, FLAC, OGG, Opus…) to 16-bit PCM, mono or stereo. */
final class PcmDecoder {
    interface Sink {
        /** Called once before any PCM, with the rate and channel count (1 or 2) of the data that follows. */
        void format(int rate, int channels) throws IOException;
        /** Interleaved samples; {@code frames} frames are valid. The array is reused between calls. */
        void pcm(short[] data, int frames) throws IOException;
    }
    interface Cancel { boolean cancelled(); }

    /** Duration from the container in ms, or 0 when unknown. */
    static long duration(File file) {
        MediaExtractor ex = new MediaExtractor();
        try { ex.setDataSource(file.getAbsolutePath()); int t = audioTrack(ex); if (t < 0) return 0; MediaFormat f = ex.getTrackFormat(t); return f.containsKey(MediaFormat.KEY_DURATION) ? f.getLong(MediaFormat.KEY_DURATION) / 1000 : 0; }
        catch (Exception e) { return 0; } finally { ex.release(); }
    }

    static int audioTrack(MediaExtractor ex) {
        for (int i = 0; i < ex.getTrackCount(); i++) { String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME); if (mime != null && mime.startsWith("audio/")) return i; }
        return -1;
    }

    static void decode(File file, Sink sink, Cancel cancel) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        boolean started = false;
        try {
            ex.setDataSource(file.getAbsolutePath());
            int track = audioTrack(ex);
            if (track < 0) throw new IOException("This file has no audio track.");
            ex.selectTrack(track);
            MediaFormat format = ex.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            try { codec = MediaCodec.createDecoderByType(mime); } catch (Exception e) { throw new IOException("This phone cannot decode " + mime + " audio."); }
            codec.configure(format, null, null, 0);
            codec.start();
            started = true;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, formatSent = false;
            int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            short[] out = new short[0];
            int idle = 0;
            while (true) {
                if (cancel != null && cancel.cancelled()) throw new CancellationException();
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(5000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0) { codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                        else { codec.queueInputBuffer(in, 0, n, Math.max(0, ex.getSampleTime()), 0); ex.advance(); }
                    }
                }
                int id = codec.dequeueOutputBuffer(info, 5000);
                if (id == MediaCodec.INFO_TRY_AGAIN_LATER) { if (inputDone && ++idle > 2000) throw new IOException("The audio decoder stopped responding."); continue; }
                idle = 0;
                if (id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = codec.getOutputFormat();
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    continue;
                }
                if (id < 0) continue;
                ByteBuffer data = codec.getOutputBuffer(id);
                if (data != null && info.size > 0) {
                    if (!formatSent) { sink.format(rate, Math.min(2, channels)); formatSent = true; }
                    data.position(info.offset); data.limit(info.offset + info.size); data.order(ByteOrder.LITTLE_ENDIAN);
                    int bytesPerSample = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : encoding == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
                    int frames = info.size / (bytesPerSample * channels), outCh = Math.min(2, channels);
                    if (out.length < frames * outCh) out = new short[frames * outCh];
                    for (int i = 0; i < frames; i++) for (int c = 0; c < channels; c++) {
                        short v;
                        if (bytesPerSample == 4) v = (short) Math.max(-32768, Math.min(32767, Math.round(data.getFloat() * 32767)));
                        else if (bytesPerSample == 1) v = (short) (((data.get() & 255) - 128) << 8);
                        else v = data.getShort();
                        if (c < outCh) out[i * outCh + c] = v;
                    }
                    sink.pcm(out, frames);
                }
                codec.releaseOutputBuffer(id, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
            if (!formatSent) throw new IOException("The song decoded to no audio.");
        } finally {
            if (codec != null) { if (started) try { codec.stop(); } catch (Exception ignored) {} codec.release(); }
            ex.release();
        }
    }

    /** Loudness envelope (0..1, {@link Project#ENVELOPE_RATE} per second) and exact duration in ms (returned in out[0]). */
    static float[] envelope(File file, long[] durationOut, Cancel cancel) throws IOException {
        final float[][] env = {new float[4096]};
        final int[] count = {0}, rate = {44100}, ch = {2}, inWindow = {0};
        final double[] sum = {0};
        final long[] frames = {0};
        decode(file, new Sink() {
            public void format(int r, int c) { rate[0] = r; ch[0] = c; }
            public void pcm(short[] d, int n) {
                int window = Math.max(1, rate[0] / Project.ENVELOPE_RATE);
                for (int i = 0; i < n; i++) {
                    double s = 0;
                    for (int c = 0; c < ch[0]; c++) { double v = d[i * ch[0] + c] / 32768.0; s += v * v; }
                    sum[0] += s / ch[0];
                    if (++inWindow[0] >= window) {
                        if (count[0] == env[0].length) env[0] = java.util.Arrays.copyOf(env[0], env[0].length * 2);
                        env[0][count[0]++] = (float) Math.sqrt(sum[0] / inWindow[0]);
                        sum[0] = 0; inWindow[0] = 0;
                    }
                }
                frames[0] += n;
            }
        }, cancel);
        if (durationOut != null && durationOut.length > 0) durationOut[0] = frames[0] * 1000 / Math.max(1, rate[0]);
        return normalise(java.util.Arrays.copyOf(env[0], count[0]));
    }

    /** Scales RMS values so loud passages reach about 1, with a little smoothing so visuals do not flicker. */
    static float[] normalise(float[] e) {
        if (e.length == 0) return e;
        float[] sorted = e.clone();
        java.util.Arrays.sort(sorted);
        float ref = Math.max(1e-4f, sorted[(int) (sorted.length * .97f) - (sorted.length > 1 ? 1 : 0)]);
        float[] out = new float[e.length];
        float s = 0;
        for (int i = 0; i < e.length; i++) {
            float v = Math.min(1f, e[i] / ref);
            s = v > s ? s + (v - s) * .6f : s + (v - s) * .15f; // fast attack, slower release
            out[i] = s;
        }
        return out;
    }
}

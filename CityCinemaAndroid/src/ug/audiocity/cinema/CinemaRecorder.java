package ug.audiocity.cinema;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Recording pipeline: Camera2 → persistent encoder surface → H.264/HEVC (8-bit or 10-bit HLG) → MP4,
 * plus an always-running audio engine (32-bit float capture, meters, digital gain) that feeds AAC into the
 * MP4 and writes a separate 32-bit float WAV for double-system sound. Video and audio share the camera's
 * sensor clock, so lip-sync does not drift.
 */
final class CinemaRecorder {
  static final String TAG = "CityCinema";

  /** Everything needed to configure a take. */
  static final class Config {
    int width = 1920, height = 1080;
    double fps = 24;
    /** Playback rate for off-speed (slow-motion) takes; 0 = real time. */
    double conformFps = 0;
    boolean hevc = true, tenBitHlg = false, cbr = false;
    long bitrate = 50_000_000L;
    float gopSeconds = 1f;
    int orientationHint = 0;
    boolean audio = true;
    /** True when the camera sensor timestamps use the boot-time clock (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME). */
    boolean realtimeClock = false;
  }

  /** What the hardware encoder can do, used to clamp UI choices. */
  static final class EncoderCaps {
    String mime; String name = "none"; long maxBitrate = 20_000_000L; boolean main10, cbr, vbr; boolean available;
    MediaCodecInfo.VideoCapabilities video;

    boolean supports(int w, int h, double fps) {
      if (video == null) return false;
      try { return video.areSizeAndRateSupported(w, h, fps); } catch (RuntimeException e) { return video.isSizeSupported(w, h); }
    }
  }

  private static final java.util.Map<String, EncoderCaps> CAPS = new java.util.HashMap<>();

  /** Hardware encoder capabilities for a MIME type (cached: enumerating codecs is slow). */
  static synchronized EncoderCaps caps(String mime) {
    EncoderCaps cached = CAPS.get(mime);
    if (cached == null) { cached = queryCaps(mime); CAPS.put(mime, cached); }
    return cached;
  }

  private static EncoderCaps queryCaps(String mime) {
    EncoderCaps out = new EncoderCaps(); out.mime = mime;
    for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
      if (!info.isEncoder() || info.isSoftwareOnly()) continue;
      boolean match = false; for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) match = true;
      if (!match) continue;
      MediaCodecInfo.CodecCapabilities c = info.getCapabilitiesForType(mime);
      out.available = true; out.name = info.getName(); out.video = c.getVideoCapabilities();
      out.maxBitrate = out.video.getBitrateRange().getUpper();
      MediaCodecInfo.EncoderCapabilities e = c.getEncoderCapabilities();
      out.cbr = e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
      out.vbr = e.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
      for (MediaCodecInfo.CodecProfileLevel p : c.profileLevels)
        if (MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime) && p.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) out.main10 = true;
      break; // first hardware encoder is the platform's preferred one
    }
    return out;
  }

  // ------------------------------------------------------------------------------------------------ video

  private final Surface persistentSurface;
  private MediaCodec video;
  private Config config;
  private MediaMuxer muxer;
  private Thread drainThread;
  private final Object muxLock = new Object();
  private int videoTrack = -1, audioTrack = -1;
  private MediaFormat videoFormat, audioFormat;
  private boolean muxerStarted;
  private int pendingVideo;
  private long baseUs = -1, lastVideoUs = -1, lastAudioUs = -1, lastSlot = -1;
  private final List<Pending> pending = new ArrayList<>();
  private volatile boolean recording;
  volatile long framesWritten, droppedFrames, bytesWritten, recordStartedMs;
  private AudioEngine audioEngine;

  private static final class Pending { boolean isVideo; byte[] data; long ptsUs; int flags; }

  CinemaRecorder(Surface persistentSurface) { this.persistentSurface = persistentSurface; }

  boolean isRecording() { return recording; }

  Config config() { return config; }

  /**
   * Configures (but does not start) the encoder on the persistent surface, so the surface has a buffer size
   * before the camera session is created. Call again after every take and whenever the format changes.
   */
  void prepare(Config c) throws IOException {
    releaseCodec();
    config = c;
    String mime = c.hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
    MediaFormat f = MediaFormat.createVideoFormat(mime, c.width, c.height);
    f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    f.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.min(Integer.MAX_VALUE, c.bitrate));
    f.setInteger(MediaFormat.KEY_FRAME_RATE, CinemaMath.nominalFps(c.fps));
    f.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, c.gopSeconds);
    f.setInteger(MediaFormat.KEY_PRIORITY, 0);
    if (c.fps > 60) f.setInteger(MediaFormat.KEY_OPERATING_RATE, CinemaMath.nominalFps(c.fps));
    f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
    if (c.tenBitHlg) {
      f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
      f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
      f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG);
    } else {
      f.setInteger(MediaFormat.KEY_PROFILE, c.hevc ? MediaCodecInfo.CodecProfileLevel.HEVCProfileMain : MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
      f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
      f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
    }
    EncoderCaps caps = caps(mime);
    if (caps.available && (c.cbr ? caps.cbr : caps.vbr))
      f.setInteger(MediaFormat.KEY_BITRATE_MODE, c.cbr ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
    String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f);
    if (name == null && !c.tenBitHlg) { f.removeKey(MediaFormat.KEY_PROFILE); name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f); }
    video = name != null ? MediaCodec.createByCodecName(name) : MediaCodec.createEncoderByType(mime);
    try {
      video.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    } catch (RuntimeException e) {
      if (c.tenBitHlg) { video.release(); video = null; throw new IOException("This encoder can't record 10-bit HLG at this size and frame rate.", e); }
      f.removeKey(MediaFormat.KEY_PROFILE); f.removeKey(MediaFormat.KEY_BITRATE_MODE);
      video.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }
    video.setInputSurface(persistentSurface);
  }

  /** Starts a take. The camera must start sending frames to the persistent surface right after this returns. */
  void start(FileDescriptor mp4, AudioEngine audio, FileChannel wav) throws IOException {
    if (video == null) prepare(config);
    muxer = new MediaMuxer(mp4, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    muxer.setOrientationHint(config.orientationHint);
    videoTrack = audioTrack = -1; videoFormat = audioFormat = null; muxerStarted = false;
    baseUs = lastVideoUs = lastAudioUs = lastSlot = -1; pending.clear(); pendingVideo = 0;
    stopRequestedAt = -1; framesWritten = droppedFrames = bytesWritten = 0; recordStartedMs = SystemClock.elapsedRealtime();
    boolean withAudio = config.audio && config.conformFps <= 0 && audio != null && audio.isRunning();
    audioEngine = withAudio ? audio : null;
    video.start();
    recording = true;
    if (withAudio) {
      try { audio.beginTake(this, wav); }
      catch (IOException | RuntimeException e) { Log.w(TAG, "audio encoder unavailable, recording video only", e); audioEngine = null; }
    }
    drainThread = new Thread(this::drainVideo, "cinema-video");
    drainThread.setPriority(Thread.MAX_PRIORITY);
    drainThread.start();
  }

  /** Ends the take and finalises the MP4. Blocks until everything is written. Returns false if nothing usable was recorded. */
  boolean stop() {
    if (!recording) return false;
    recording = false;
    try { video.signalEndOfInputStream(); } catch (RuntimeException e) { Log.w(TAG, "EOS", e); }
    if (audioEngine != null) audioEngine.endTake();
    try { drainThread.join(4000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    boolean ok;
    synchronized (muxLock) {
      ok = muxerStarted && framesWritten > 0;
      try { if (muxerStarted) muxer.stop(); } catch (RuntimeException e) { ok = false; Log.w(TAG, "muxer stop", e); }
      try { muxer.release(); } catch (RuntimeException ignored) {}
      muxer = null;
    }
    releaseCodec();
    return ok;
  }

  void release() { if (recording) stop(); releaseCodec(); }

  private void releaseCodec() {
    if (video == null) return;
    try { video.stop(); } catch (RuntimeException ignored) {}
    try { video.release(); } catch (RuntimeException ignored) {}
    video = null;
  }

  private void drainVideo() {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    long frameUs = CinemaMath.frameDurationNs(config.fps) / 1000;
    MediaCodec codec = video;
    while (true) {
      int index;
      try { index = codec.dequeueOutputBuffer(info, 10_000); } catch (RuntimeException e) { Log.w(TAG, "video dequeue", e); break; }
      if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!recording && stopTimedOut()) break; continue; }
      if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { synchronized (muxLock) { videoFormat = codec.getOutputFormat(); maybeStartMuxer(); } continue; }
      if (index < 0) continue;
      ByteBuffer buf = codec.getOutputBuffer(index);
      boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
      if (buf != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
        synchronized (muxLock) {
          if (baseUs < 0) { baseUs = info.presentationTimeUs; maybeStartMuxer(); }
          if (lastVideoUs >= 0 && info.presentationTimeUs - lastVideoUs > frameUs * 3 / 2)
            droppedFrames += (info.presentationTimeUs - lastVideoUs) / frameUs - 1;
          writeSample(true, buf, info);
        }
      }
      codec.releaseOutputBuffer(index, false);
      if (eos) break;
    }
  }

  private long stopRequestedAt = -1;

  private boolean stopTimedOut() {
    long now = SystemClock.elapsedRealtime();
    if (stopRequestedAt < 0) stopRequestedAt = now;
    return now - stopRequestedAt > 3000;
  }

  /** Called by the audio engine (on its thread) for each encoded AAC buffer or the AAC format. */
  void onAudioFormat(MediaFormat format) { synchronized (muxLock) { audioFormat = format; maybeStartMuxer(); } }

  void onAudioSample(ByteBuffer buf, MediaCodec.BufferInfo info) { synchronized (muxLock) { writeSample(false, buf, info); } }

  private void maybeStartMuxer() {
    if (muxerStarted || muxer == null || videoFormat == null || baseUs < 0) return;
    // Wait for the AAC format, but never more than ~1.5 s of video: then record picture only rather than lose the take.
    if (audioEngine != null && audioFormat == null && pendingVideo < 45) return;
    videoTrack = muxer.addTrack(videoFormat);
    if (audioEngine != null && audioFormat != null) audioTrack = muxer.addTrack(audioFormat);
    else if (audioEngine != null) Log.w(TAG, "no audio format in time; recording without audio");
    muxer.start();
    muxerStarted = true;
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    for (Pending p : pending) {
      info.set(0, p.data.length, p.ptsUs, p.flags);
      writeStarted(p.isVideo, ByteBuffer.wrap(p.data), info);
    }
    pending.clear();
  }

  private void writeSample(boolean isVideo, ByteBuffer buf, MediaCodec.BufferInfo info) {
    if (!muxerStarted) {
      if (pending.size() > 600) return; // never let a stuck start grow without bound
      maybeStartMuxer();
    }
    if (!muxerStarted) {
      if (isVideo) pendingVideo++;
      Pending p = new Pending(); p.isVideo = isVideo; p.ptsUs = info.presentationTimeUs; p.flags = info.flags;
      p.data = new byte[info.size]; buf.position(info.offset); buf.get(p.data, 0, info.size); pending.add(p);
      return;
    }
    writeStarted(isVideo, buf, info);
  }

  private void writeStarted(boolean isVideo, ByteBuffer buf, MediaCodec.BufferInfo info) {
    long pts = info.presentationTimeUs - baseUs;
    if (pts < 0) return; // audio from before the first frame
    if (!isVideo && audioTrack < 0) return;
    if (isVideo) {
      // Constant frame rate: snap each frame to its slot on the exact (e.g. 24000/1001) frame grid. A dropped frame
      // leaves a gap instead of shifting later frames, so sync holds; two frames in one slot (camera running slightly
      // fast in auto exposure) keep only the first. Off-speed takes are re-timed onto the playback grid.
      double frameUs = 1e6 / CinemaMath.exactFps(config.fps);
      long slot = Math.round(pts / frameUs);
      if (slot <= lastSlot) return;
      lastSlot = slot;
      pts = Math.round(slot * (config.conformFps > 0 ? 1e6 / CinemaMath.exactFps(config.conformFps) : frameUs));
    }
    if (isVideo) { if (pts <= lastVideoUs) return; lastVideoUs = pts; } else { if (pts <= lastAudioUs) return; lastAudioUs = pts; }
    MediaCodec.BufferInfo out = new MediaCodec.BufferInfo();
    out.set(info.offset, info.size, pts, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
    buf.position(info.offset); buf.limit(info.offset + info.size);
    try {
      muxer.writeSampleData(isVideo ? videoTrack : audioTrack, buf, out);
      bytesWritten += info.size;
      if (isVideo) framesWritten++;
    } catch (RuntimeException e) { Log.w(TAG, "write", e); }
  }

  // ------------------------------------------------------------------------------------------------ audio

  /**
   * Continuously captures audio for metering. During a take it also encodes AAC into the MP4 and writes the
   * float WAV. Runs on its own high-priority thread.
   */
  static final class AudioEngine {
    static final int RATE = 48_000, CHUNK = 1024;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    int channels = 2;
    boolean floatInput = true;
    volatile float gainDb = 0;
    volatile float peakL, peakR, holdL, holdR;
    volatile boolean clipped;
    private long holdUntilL, holdUntilR;
    private volatile CinemaRecorder target;
    private volatile boolean inTake;
    /** Camera-clock time of the current/last take's first WAV sample, or -1. */
    volatile long wavStartNs = -1;
    private volatile FileChannel wav;
    private volatile boolean endRequested;
    private MediaCodec aac;
    private long wavDataBytes;
    private final Object takeLock = new Object();
    boolean realtimeClock;
    String inputName = "Built-in mic";

    boolean isRunning() { return running; }

    /** Starts capture. Throws SecurityException without RECORD_AUDIO. */
    void start(int source, AudioDeviceInfo device, boolean realtimeClock) {
      stop();
      this.realtimeClock = realtimeClock;
      int[][] attempts = {{AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT}, {AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT},
          {AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT}, {AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT}};
      for (int[] a : attempts) {
        int min = AudioRecord.getMinBufferSize(RATE, a[0], a[1]);
        if (min <= 0) continue;
        try {
          AudioRecord r = new AudioRecord.Builder().setAudioSource(source)
              .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(a[0]).setEncoding(a[1]).build())
              .setBufferSizeInBytes(Math.max(min * 4, CHUNK * 8 * 4)).build();
          if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); continue; }
          if (device != null) r.setPreferredDevice(device);
          record = r; channels = a[0] == AudioFormat.CHANNEL_IN_STEREO ? 2 : 1; floatInput = a[1] == AudioFormat.ENCODING_PCM_FLOAT;
          break;
        } catch (UnsupportedOperationException | IllegalArgumentException e) { Log.w(TAG, "audio attempt", e); }
      }
      if (record == null) return;
      inputName = device != null ? String.valueOf(device.getProductName()) : "Built-in mic";
      record.startRecording();
      running = true;
      thread = new Thread(this::loop, "cinema-audio");
      thread.setPriority(Thread.MAX_PRIORITY);
      thread.start();
    }

    void stop() {
      running = false;
      if (thread != null) { try { thread.join(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } thread = null; }
      if (record != null) { try { record.stop(); } catch (RuntimeException ignored) {} record.release(); record = null; }
    }

    /**
     * Starts a take. With a recorder, AAC is encoded into its MP4; with recorder == null (RAW mode) only the WAV is
     * written. wavStartNs then holds the camera-clock time of the WAV's first sample, for sync.
     */
    void beginTake(CinemaRecorder recorder, FileChannel wavOut) throws IOException {
      synchronized (takeLock) {
        wavStartNs = -1;
        if (recorder != null) {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, channels);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, channels == 2 ? 320_000 : 192_000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * channels * 2 * 2);
        aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        aac.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        aac.start();
        }
        wav = wavOut; wavDataBytes = 0; endRequested = false;
        if (wav != null) { wav.position(0); wav.write(wavHeader(channels, 0)); }
        target = recorder;
        inTake = true;
      }
    }

    /** Requests the end of the take and waits (briefly) for the AAC tail to be written. */
    void endTake() {
      endRequested = true;
      long deadline = SystemClock.elapsedRealtime() + 2000;
      while (inTake && SystemClock.elapsedRealtime() < deadline) {
        if (!running) { synchronized (takeLock) { finishTake(); } break; }
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
      }
    }

    private void loop() {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
      float[] f = new float[CHUNK * channels];
      short[] s = floatInput ? null : new short[CHUNK * channels];
      ByteBuffer pcm16 = ByteBuffer.allocate(CHUNK * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
      ByteBuffer pcm32 = ByteBuffer.allocate(CHUNK * channels * 4).order(ByteOrder.LITTLE_ENDIAN);
      AudioTimestamp ts = new AudioTimestamp();
      long framesRead = 0;
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      while (running) {
        int n = floatInput ? record.read(f, 0, f.length, AudioRecord.READ_BLOCKING) : record.read(s, 0, s.length, AudioRecord.READ_BLOCKING);
        if (n <= 0) { if (n < 0) { Log.w(TAG, "audio read " + n); try { Thread.sleep(5); } catch (InterruptedException e) { break; } } continue; }
        if (!floatInput) for (int i = 0; i < n; i++) f[i] = s[i] / 32768f;
        int frames = n / channels;
        long chunkStartFrame = framesRead;
        framesRead += frames;
        // Timestamp of this chunk's first frame on the camera's clock.
        long ptsNs;
        int base = realtimeClock ? AudioTimestamp.TIMEBASE_BOOTTIME : AudioTimestamp.TIMEBASE_MONOTONIC;
        if (record.getTimestamp(ts, base) == AudioRecord.SUCCESS) ptsNs = ts.nanoTime + (chunkStartFrame - ts.framePosition) * CinemaMath.NS / RATE;
        else ptsNs = (realtimeClock ? SystemClock.elapsedRealtimeNanos() : System.nanoTime()) - frames * CinemaMath.NS / RATE;
        float gain = (float) CinemaMath.dbToGain(gainDb);
        float pl = 0, pr = 0; boolean clip = false;
        for (int i = 0; i < n; i++) {
          float v = f[i] * gain;
          if (v > 1f) { v = 1f; clip = true; } else if (v < -1f) { v = -1f; clip = true; }
          f[i] = v;
          float a = Math.abs(v);
          if (channels == 1 || (i & 1) == 0) { if (a > pl) pl = a; } else if (a > pr) pr = a;
        }
        if (channels == 1) pr = pl;
        updateMeters(pl, pr, clip);
        synchronized (takeLock) {
          if (!inTake) continue;
          if (wavStartNs < 0) wavStartNs = ptsNs;
          if (wav != null) {
            pcm32.clear(); for (int i = 0; i < n; i++) pcm32.putFloat(f[i]); pcm32.flip();
            try { while (pcm32.hasRemaining()) wavDataBytes += wav.write(pcm32); } catch (IOException e) { Log.w(TAG, "wav", e); wav = null; }
          }
          if (aac != null) {
            pcm16.clear(); for (int i = 0; i < n; i++) pcm16.putShort((short) Math.round(f[i] * 32767f)); pcm16.flip();
            feedAac(pcm16, ptsNs / 1000, endRequested, info);
          }
          if (endRequested) finishTake();
        }
      }
      synchronized (takeLock) { if (inTake) finishTake(); }
    }

    private void feedAac(ByteBuffer pcm, long ptsUs, boolean eos, MediaCodec.BufferInfo info) {
      try {
        while (pcm.hasRemaining() || eos) {
          int in = aac.dequeueInputBuffer(5_000);
          if (in >= 0) {
            ByteBuffer dst = aac.getInputBuffer(in);
            int size = Math.min(dst.remaining(), pcm.remaining());
            int consumedFrames = (pcm.position()) / (2 * channels);
            ByteBuffer slice = pcm.duplicate(); slice.limit(pcm.position() + size); dst.put(slice); pcm.position(pcm.position() + size);
            boolean last = eos && !pcm.hasRemaining();
            aac.queueInputBuffer(in, 0, size, ptsUs + consumedFrames * 1_000_000L / RATE, last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
            if (last) break;
          }
          drainAac(info, false);
        }
        drainAac(info, eos);
      } catch (RuntimeException e) { Log.w(TAG, "aac", e); }
    }

    private void drainAac(MediaCodec.BufferInfo info, boolean untilEos) {
      long deadline = SystemClock.elapsedRealtime() + 500;
      while (true) {
        int out = aac.dequeueOutputBuffer(info, untilEos ? 10_000 : 0);
        if (out == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!untilEos || SystemClock.elapsedRealtime() > deadline) return; continue; }
        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { CinemaRecorder t = target; if (t != null) t.onAudioFormat(aac.getOutputFormat()); continue; }
        if (out < 0) continue;
        ByteBuffer b = aac.getOutputBuffer(out);
        CinemaRecorder t = target;
        if (b != null && t != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) t.onAudioSample(b, info);
        boolean done = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        aac.releaseOutputBuffer(out, false);
        if (done) return;
      }
    }

    private void finishTake() {
      if (aac != null) { try { aac.stop(); } catch (RuntimeException ignored) {} aac.release(); aac = null; }
      if (wav != null) {
        try { wav.position(0); wav.write(wavHeader(channels, wavDataBytes)); wav.force(true); } catch (IOException e) { Log.w(TAG, "wav header", e); }
        wav = null;
      }
      target = null;
      inTake = false;
    }

    private void updateMeters(float l, float r, boolean clip) {
      long now = SystemClock.elapsedRealtime();
      // Ballistics: instant attack, ~20 dB/s fall, 1.5 s peak hold.
      float fall = 0.89f;
      peakL = Math.max(l, peakL * fall); peakR = Math.max(r, peakR * fall);
      if (l >= holdL || now > holdUntilL) { holdL = l; holdUntilL = now + 1500; }
      if (r >= holdR || now > holdUntilR) { holdR = r; holdUntilR = now + 1500; }
      if (clip) clipped = true;
    }
  }

  /** 44-byte-plus WAV header for 32-bit IEEE float PCM at 48 kHz, with the fact chunk float WAVs require. */
  static ByteBuffer wavHeader(int channels, long dataBytes) {
    ByteBuffer h = ByteBuffer.allocate(58).order(ByteOrder.LITTLE_ENDIAN);
    long data = Math.min(dataBytes, 0xffffffffL - 58);
    h.put(new byte[] {'R', 'I', 'F', 'F'}).putInt((int) (50 + data)).put(new byte[] {'W', 'A', 'V', 'E'});
    h.put(new byte[] {'f', 'm', 't', ' '}).putInt(18).putShort((short) 3).putShort((short) channels).putInt(AudioEngine.RATE)
        .putInt(AudioEngine.RATE * channels * 4).putShort((short) (channels * 4)).putShort((short) 32).putShort((short) 0);
    h.put(new byte[] {'f', 'a', 'c', 't'}).putInt(4).putInt((int) (data / (channels * 4)));
    h.put(new byte[] {'d', 'a', 't', 'a'}).putInt((int) data);
    h.flip();
    return h;
  }

  static int sourceFor(String name) {
    switch (name) {
      case "Unprocessed": return MediaRecorder.AudioSource.UNPROCESSED;
      case "Voice": return MediaRecorder.AudioSource.VOICE_RECOGNITION;
      case "Mic": return MediaRecorder.AudioSource.MIC;
      default: return MediaRecorder.AudioSource.CAMCORDER;
    }
  }
}

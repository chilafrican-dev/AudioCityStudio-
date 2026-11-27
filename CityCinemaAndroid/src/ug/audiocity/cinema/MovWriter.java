package ug.audiocity.cinema;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal QuickTime (.mov) writer for ProRes (pure Java, host-testable): one ProRes video track at a constant
 * frame rate, an optional 24-bit little-endian PCM audio track and a QuickTime timecode (tmcd) track, with
 * nclc colour tags, field/pixel-aspect atoms and an orientation matrix. Media data is written as it arrives
 * into a 64-bit mdat; the movie header is written at the end by finish().
 */
final class MovWriter {
  static final class Config {
    int width, height;
    String fourcc = "apch", compressorName = "Apple ProRes 422 HQ";
    double fps = 24;
    int primaries = 1, transfer = 1, matrix = 1;
    int audioChannels = 2, sampleRate = 48_000; // 0 channels = no audio track
    long startTimecodeFrames;
    boolean dropFrame;
    int rotationDegrees; // 0 or 180
    String reelName = "";
  }

  private final FileChannel out;
  private final Config c;
  private final int timescale, frameDuration;
  private long pos, mdatStart;
  private final List<Long> videoOffsets = new ArrayList<>();
  private final List<Integer> videoSizes = new ArrayList<>();
  private final List<Long> audioChunkOffsets = new ArrayList<>();
  private final List<Integer> audioChunkFrames = new ArrayList<>();
  private long audioFrames, tmcdOffset;
  private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
  private int audioBuffered;
  private boolean finished;

  MovWriter(FileChannel out, Config c) throws IOException {
    this.out = out; this.c = c;
    int nominal = CinemaMath.nominalFps(c.fps);
    boolean ntsc = Math.abs(c.fps - nominal) > 0.001;
    timescale = nominal * 1000;
    frameDuration = ntsc ? 1001 : 1000;
    out.position(0);
    pos = 0;
    Atom ftyp = new Atom("ftyp").str("qt  ").u32(0x200).str("qt  ");
    write(ftyp.done());
    write(new Atom("wide").done());
    mdatStart = pos;
    ByteBuffer mdat = ByteBuffer.allocate(16);
    mdat.putInt(1).put("mdat".getBytes(StandardCharsets.US_ASCII)).putLong(0); // 64-bit size patched in finish()
    mdat.flip();
    write(mdat);
    // The timecode track's single sample: the frame number of the first frame.
    tmcdOffset = pos;
    ByteBuffer tc = ByteBuffer.allocate(4); tc.putInt((int) c.startTimecodeFrames); tc.flip();
    write(tc);
  }

  /**
   * Appends one encoded frame. copies > 1 makes the frame stand in for that many frame slots (used when frames had
   * to be dropped): the data is stored once and referenced repeatedly, so the file keeps a constant frame rate.
   */
  synchronized void writeVideo(ByteBuffer frame, int copies) throws IOException {
    long at = pos;
    int size = frame.remaining();
    write(frame);
    for (int i = 0; i < Math.max(1, copies); i++) { videoOffsets.add(at); videoSizes.add(size); }
  }

  /** Appends audio (interleaved floats, -1..1). Written as 24-bit PCM in chunks of about half a second. */
  synchronized void writeAudio(float[] samples, int frames) throws IOException {
    if (c.audioChannels <= 0) return;
    int n = frames * c.audioChannels;
    byte[] b = new byte[n * 3];
    for (int i = 0; i < n; i++) {
      float f = samples[i];
      int v = f >= 1f ? 8_388_607 : f <= -1f ? -8_388_608 : Math.round(f * 8_388_607f);
      b[i * 3] = (byte) v; b[i * 3 + 1] = (byte) (v >> 8); b[i * 3 + 2] = (byte) (v >> 16);
    }
    audioBuffer.write(b, 0, b.length);
    audioBuffered += frames;
    if (audioBuffered >= c.sampleRate / 2) flushAudio();
  }

  long videoFrames() { return videoOffsets.size(); }

  long audioFramesWritten() { return audioFrames + audioBuffered; }

  long bytesWritten() { return pos; }

  private void flushAudio() throws IOException {
    if (audioBuffered == 0) return;
    audioChunkOffsets.add(pos);
    audioChunkFrames.add(audioBuffered);
    write(ByteBuffer.wrap(audioBuffer.toByteArray()));
    audioFrames += audioBuffered;
    audioBuffer.reset();
    audioBuffered = 0;
  }

  /** Writes the movie header and fixes up the media data size. The channel is left open. */
  synchronized void finish() throws IOException {
    if (finished) return;
    finished = true;
    flushAudio();
    long mdatSize = pos - mdatStart;
    ByteBuffer size = ByteBuffer.allocate(8); size.putLong(mdatSize); size.flip();
    out.write(size, mdatStart + 8);
    write(ByteBuffer.wrap(moov()));
    out.force(true);
  }

  private void write(ByteBuffer b) throws IOException {
    while (b.hasRemaining()) pos += out.write(b, pos);
  }

  // ------------------------------------------------------------------------------------------------ movie header

  private byte[] moov() {
    long frames = videoOffsets.size();
    long videoDuration = frames * frameDuration; // in timescale units
    boolean audio = c.audioChannels > 0 && audioFrames > 0;
    int videoId = 1, audioId = 2, tcId = audio ? 3 : 2;
    Atom moov = new Atom("moov");
    moov.add(mvhd(videoDuration, tcId + 1));
    moov.add(videoTrack(videoId, videoDuration, tcId));
    if (audio) moov.add(audioTrack(audioId, Math.round(audioFrames * (double) timescale / c.sampleRate)));
    moov.add(timecodeTrack(tcId, videoDuration));
    return moov.done().array();
  }

  private Atom mvhd(long duration, int nextTrack) {
    Atom a = new Atom("mvhd").u32(0).u32(0).u32(0).u32(timescale).u32(duration).u32(0x00010000).u16(0x0100).zeros(10);
    matrix(a, 0, 0, 0);
    return a.zeros(24).u32(nextTrack);
  }

  private static void matrix(Atom a, int rotation, int w, int h) {
    if (rotation == 180) a.u32(0xFFFF0000).u32(0).u32(0).u32(0).u32(0xFFFF0000).u32(0).u32((long) w << 16).u32((long) h << 16).u32(0x40000000);
    else a.u32(0x00010000).u32(0).u32(0).u32(0).u32(0x00010000).u32(0).u32(0).u32(0).u32(0x40000000);
  }

  private Atom tkhd(int id, long duration, int volume, int w, int h, int rotation) {
    Atom a = new Atom("tkhd").u32(0x0000000F).u32(0).u32(0).u32(id).u32(0).u32(duration).zeros(8).u16(0).u16(0).u16(volume).u16(0);
    matrix(a, rotation, w, h);
    return a.u32((long) w << 16).u32((long) h << 16);
  }

  private static Atom mdhd(int scale, long duration) { return new Atom("mdhd").u32(0).u32(0).u32(0).u32(scale).u32(duration).u16(0).u16(0); }

  private static Atom hdlr(String type, String sub, String name) {
    return new Atom("hdlr").u32(0).str(type).str(sub).str("appl").u32(0).u32(0).pascal(name);
  }

  private static Atom dinf() { return new Atom("dinf").add(new Atom("dref").u32(0).u32(1).add(new Atom("alis").u32(1))); }

  private Atom videoTrack(int id, long duration, int tcId) {
    Atom trak = new Atom("trak");
    trak.add(tkhd(id, duration, 0, c.width, c.height, c.rotationDegrees));
    trak.add(new Atom("tref").add(new Atom("tmcd").u32(tcId)));
    Atom stsd = new Atom("stsd").u32(0).u32(1);
    Atom entry = new Atom(c.fourcc).zeros(6).u16(1).u16(0).u16(0).str("ccin").u32(0).u32(512)
        .u16(c.width).u16(c.height).u32(0x00480000).u32(0x00480000).u32(0).u16(1).fixedPascal(c.compressorName, 32).u16(24).u16(0xFFFF);
    entry.add(new Atom("colr").str("nclc").u16(c.primaries).u16(c.transfer).u16(c.matrix));
    entry.add(new Atom("fiel").u8(1).u8(0));
    entry.add(new Atom("pasp").u32(1).u32(1));
    stsd.add(entry);
    Atom stbl = new Atom("stbl").add(stsd);
    stbl.add(new Atom("stts").u32(0).u32(1).u32(videoOffsets.size()).u32(frameDuration));
    stbl.add(new Atom("stsc").u32(0).u32(1).u32(1).u32(1).u32(1));
    Atom stsz = new Atom("stsz").u32(0).u32(0).u32(videoSizes.size());
    for (int s : videoSizes) stsz.u32(s);
    stbl.add(stsz);
    Atom co64 = new Atom("co64").u32(0).u32(videoOffsets.size());
    for (long o : videoOffsets) co64.u64(o);
    stbl.add(co64);
    Atom minf = new Atom("minf").add(new Atom("vmhd").u32(1).u16(0x40).u16(0x8000).u16(0x8000).u16(0x8000))
        .add(hdlr("dhlr", "alis", "Alias Data Handler")).add(dinf()).add(stbl);
    trak.add(new Atom("mdia").add(mdhd(timescale, duration)).add(hdlr("mhlr", "vide", "Video Media Handler")).add(minf));
    return trak;
  }

  private Atom audioTrack(int id, long movieDuration) {
    Atom trak = new Atom("trak").add(tkhd(id, movieDuration, 0x0100, 0, 0, 0));
    int ch = c.audioChannels, bpf = 3 * ch;
    // SoundDescription version 2: linear PCM, 24-bit signed little-endian, packed.
    Atom entry = new Atom("lpcm").zeros(6).u16(1).u16(2).u16(0).u32(0)
        .u16(3).u16(16).u16(0xFFFE).u16(0).u32(0x00010000).u32(72).f64(c.sampleRate).u32(ch).u32(0x7F000000)
        .u32(24).u32(0x4 | 0x8).u32(bpf).u32(1);
    Atom stbl = new Atom("stbl").add(new Atom("stsd").u32(0).u32(1).add(entry));
    stbl.add(new Atom("stts").u32(0).u32(1).u32(audioFrames).u32(1));
    // stsc: runs of chunks with the same number of frames
    List<long[]> runs = new ArrayList<>();
    for (int i = 0; i < audioChunkFrames.size(); i++) {
      int n = audioChunkFrames.get(i);
      if (runs.isEmpty() || runs.get(runs.size() - 1)[1] != n) runs.add(new long[] {i + 1, n});
    }
    Atom stsc = new Atom("stsc").u32(0).u32(runs.size());
    for (long[] r : runs) stsc.u32(r[0]).u32(r[1]).u32(1);
    stbl.add(stsc);
    stbl.add(new Atom("stsz").u32(0).u32(bpf).u32(audioFrames));
    Atom co64 = new Atom("co64").u32(0).u32(audioChunkOffsets.size());
    for (long o : audioChunkOffsets) co64.u64(o);
    stbl.add(co64);
    Atom minf = new Atom("minf").add(new Atom("smhd").u32(0).u16(0).u16(0)).add(hdlr("dhlr", "alis", "Alias Data Handler")).add(dinf()).add(stbl);
    trak.add(new Atom("mdia").add(mdhd(c.sampleRate, audioFrames)).add(hdlr("mhlr", "soun", "Sound Media Handler")).add(minf));
    return trak;
  }

  private Atom timecodeTrack(int id, long duration) {
    Atom trak = new Atom("trak").add(tkhd(id, duration, 0, 0, 0, 0));
    int flags = (c.dropFrame ? 0x1 : 0) | 0x2; // drop frame, wraps at 24 hours
    Atom entry = new Atom("tmcd").zeros(6).u16(1).u32(0).u32(flags).u32(timescale).u32(frameDuration).u8(CinemaMath.nominalFps(c.fps)).u8(0);
    if (c.reelName != null && !c.reelName.isEmpty()) {
      byte[] reel = c.reelName.getBytes(StandardCharsets.US_ASCII);
      entry.add(new Atom("name").u16(reel.length).u16(0).bytes(reel));
    }
    Atom stbl = new Atom("stbl").add(new Atom("stsd").u32(0).u32(1).add(entry));
    stbl.add(new Atom("stts").u32(0).u32(1).u32(1).u32(duration));
    stbl.add(new Atom("stsc").u32(0).u32(1).u32(1).u32(1).u32(1));
    stbl.add(new Atom("stsz").u32(0).u32(4).u32(1));
    stbl.add(new Atom("co64").u32(0).u32(1).u64(tmcdOffset));
    Atom tcmi = new Atom("tcmi").u32(0).u16(0).u16(0).u16(12).u16(0).u16(0xFFFF).u16(0xFFFF).u16(0xFFFF).u16(0).u16(0).u16(0).pascal("Lucida Grande");
    Atom gmhd = new Atom("gmhd").add(new Atom("gmin").u32(0).u16(0x40).u16(0x8000).u16(0x8000).u16(0x8000).u16(0).u16(0)).add(new Atom("tmcd").add(tcmi));
    Atom minf = new Atom("minf").add(gmhd).add(hdlr("dhlr", "alis", "Alias Data Handler")).add(dinf()).add(stbl);
    trak.add(new Atom("mdia").add(mdhd(timescale, duration)).add(hdlr("mhlr", "tmcd", "Time Code Media Handler")).add(minf));
    return trak;
  }

  // ------------------------------------------------------------------------------------------------ atom builder

  /** A QuickTime atom under construction: big-endian fields, child atoms, size computed at the end. */
  static final class Atom {
    final String type;
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    Atom(String type) { this.type = type; }
    Atom u8(int v) { body.write(v); return this; }
    Atom u16(int v) { body.write(v >> 8); body.write(v); return this; }
    Atom u32(long v) { for (int s = 24; s >= 0; s -= 8) body.write((int) (v >> s)); return this; }
    Atom u64(long v) { u32(v >>> 32); return u32(v & 0xffffffffL); }
    Atom f64(double v) { return u64(Double.doubleToLongBits(v)); }
    Atom zeros(int n) { for (int i = 0; i < n; i++) body.write(0); return this; }
    Atom str(String s) { return bytes(s.getBytes(StandardCharsets.US_ASCII)); }
    Atom bytes(byte[] b) { body.write(b, 0, b.length); return this; }
    Atom pascal(String s) { byte[] b = s.getBytes(StandardCharsets.US_ASCII); body.write(b.length); return bytes(b); }
    Atom fixedPascal(String s, int size) {
      byte[] b = s.getBytes(StandardCharsets.US_ASCII);
      int n = Math.min(b.length, size - 1);
      body.write(n); body.write(b, 0, n);
      return zeros(size - 1 - n);
    }
    Atom add(Atom child) { ByteBuffer b = child.done(); body.write(b.array(), 0, b.limit()); return this; }
    ByteBuffer done() {
      byte[] content = body.toByteArray();
      ByteBuffer b = ByteBuffer.allocate(8 + content.length).order(ByteOrder.BIG_ENDIAN);
      b.putInt(8 + content.length).put(type.getBytes(StandardCharsets.US_ASCII)).put(content);
      b.flip();
      return b;
    }
  }

  /** Aligns audio chunks to the first video frame: pads with silence or trims, so audio and picture start together. */
  static final class AudioAligner {
    private final int channels, rate;
    private long videoStartNs = -1, written, clockShiftNs = Long.MIN_VALUE;
    private final ArrayDeque<Object[]> early = new ArrayDeque<>();

    AudioAligner(int channels, int rate) { this.channels = channels; this.rate = rate; }

    /** Sets the time of video frame 0 and releases any audio that arrived before it. */
    void videoStarted(long ns, MovWriter w) throws IOException {
      if (videoStartNs >= 0) return;
      videoStartNs = ns;
      while (!early.isEmpty()) { Object[] e = early.poll(); add((float[]) e[0], (Integer) e[1], (Long) e[2], w); }
    }

    /** Adds a chunk whose first sample was captured at ptsNs (on the same clock as the video frames). */
    void add(float[] samples, int frames, long ptsNs, MovWriter w) throws IOException {
      if (videoStartNs < 0) {
        if (early.size() < 400) early.add(new Object[] {samples.clone(), frames, ptsNs});
        return;
      }
      // Safety net: audio more than 2 s from the first frame means the two used different clocks; re-align.
      if (clockShiftNs == Long.MIN_VALUE) clockShiftNs = Math.abs(ptsNs - videoStartNs) > 2 * CinemaMath.NS ? ptsNs - videoStartNs : 0;
      ptsNs -= clockShiftNs;
      long start = Math.round((ptsNs - videoStartNs) * (double) rate / CinemaMath.NS);
      if (start + frames <= written) return;            // entirely before what we already have
      if (start > written) {                             // gap: fill with silence (keeps sync after a hiccup)
        long gap = Math.min(start - written, rate * 2L);
        float[] silence = new float[(int) (gap * channels)];
        w.writeAudio(silence, (int) gap);
        written += gap;
      }
      int skip = (int) Math.max(0, written - start);
      if (skip >= frames) return;
      float[] part = skip == 0 ? samples : java.util.Arrays.copyOfRange(samples, skip * channels, frames * channels);
      w.writeAudio(part, frames - skip);
      written += frames - skip;
    }
  }
}

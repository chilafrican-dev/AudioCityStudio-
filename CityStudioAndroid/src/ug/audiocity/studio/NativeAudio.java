package ug.audiocity.studio;

/**
 * Optional native AAudio output (libaudiocity.so from jni/audiocity_aaudio.cpp). build.sh compiles it when ANDROID_NDK_ROOT
 * is set; without it, or if the device refuses the stream, the engine uses AudioTrack.
 */
final class NativeAudio {
    private NativeAudio() {}
    static final boolean AVAILABLE;
    static { boolean ok = false; try { System.loadLibrary("audiocity"); ok = true; } catch (Throwable ignored) {} AVAILABLE = ok; }
    /** Opens a low-latency float output stream; returns 0 on failure. bufferBursts sets the buffer to that many bursts. */
    static native long open(int sampleRate, int channels, boolean exclusive, int bufferBursts);
    static native int start(long handle);
    /** Blocking write of interleaved frames starting at offsetSamples; returns frames written or a negative AAudio error. */
    static native int write(long handle, float[] data, int offsetSamples, int frames, long timeoutNanos);
    static native long framesRead(long handle);
    static native int framesPerBurst(long handle);
    static native int bufferFrames(long handle);
    static native boolean exclusive(long handle);
    static native int xruns(long handle);
    static native void stop(long handle);
    static native void close(long handle);
}

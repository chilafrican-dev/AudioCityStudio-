// Native AAudio output for Audio City Studio. Built by build.sh when ANDROID_NDK_ROOT is set (API 30+).
// The Java engine renders blocks and writes them here; AAudio gives the lowest-latency path Android offers
// (exclusive MMAP streams on supported devices). Any failure returns an error so Java can fall back to AudioTrack.
#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <vector>
#include <mutex>

namespace {
struct Stream {
    AAudioStream* stream = nullptr;
    int32_t channels = 2;
    std::vector<float> scratch;
    std::mutex lock;
};
Stream* from(jlong h) { return reinterpret_cast<Stream*>(h); }
}

extern "C" {

JNIEXPORT jlong JNICALL Java_ug_audiocity_studio_NativeAudio_open(JNIEnv*, jclass, jint rate, jint channels, jboolean exclusive, jint bursts) {
    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return 0;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, exclusive ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setSampleRate(builder, rate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    AAudioStream* stream = nullptr;
    aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || stream == nullptr) return 0;
    // The engine renders at a fixed 48 kHz stereo; refuse anything else rather than playing at the wrong speed.
    if (AAudioStream_getSampleRate(stream) != rate || AAudioStream_getChannelCount(stream) != channels || AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_FLOAT) {
        AAudioStream_close(stream);
        return 0;
    }
    int32_t burst = AAudioStream_getFramesPerBurst(stream);
    if (burst > 0 && bursts > 0) AAudioStream_setBufferSizeInFrames(stream, burst * bursts);
    auto* s = new Stream();
    s->stream = stream;
    s->channels = channels;
    __android_log_print(ANDROID_LOG_INFO, "CityStudio", "AAudio %s, burst %d, buffer %d",
                        AAudioStream_getSharingMode(stream) == AAUDIO_SHARING_MODE_EXCLUSIVE ? "exclusive" : "shared",
                        burst, AAudioStream_getBufferSizeInFrames(stream));
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jint JNICALL Java_ug_audiocity_studio_NativeAudio_start(JNIEnv*, jclass, jlong h) {
    Stream* s = from(h);
    if (!s || !s->stream) return AAUDIO_ERROR_NULL;
    return AAudioStream_requestStart(s->stream);
}

JNIEXPORT jint JNICALL Java_ug_audiocity_studio_NativeAudio_write(JNIEnv* env, jclass, jlong h, jfloatArray data, jint offset, jint frames, jlong timeout) {
    Stream* s = from(h);
    if (!s || !s->stream || frames <= 0) return frames <= 0 ? 0 : AAUDIO_ERROR_NULL;
    jsize length = env->GetArrayLength(data);
    jint samples = frames * s->channels;
    if (offset < 0 || offset + samples > length) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    std::lock_guard<std::mutex> guard(s->lock);
    if (!s->stream) return AAUDIO_ERROR_INVALID_STATE;
    if (static_cast<jint>(s->scratch.size()) < samples) s->scratch.resize(samples);
    env->GetFloatArrayRegion(data, offset, samples, s->scratch.data());
    return AAudioStream_write(s->stream, s->scratch.data(), frames, timeout);
}

JNIEXPORT jlong JNICALL Java_ug_audiocity_studio_NativeAudio_framesRead(JNIEnv*, jclass, jlong h) {
    Stream* s = from(h);
    return s && s->stream ? static_cast<jlong>(AAudioStream_getFramesRead(s->stream)) : 0;
}
JNIEXPORT jint JNICALL Java_ug_audiocity_studio_NativeAudio_framesPerBurst(JNIEnv*, jclass, jlong h) {
    Stream* s = from(h);
    return s && s->stream ? AAudioStream_getFramesPerBurst(s->stream) : 0;
}
JNIEXPORT jint JNICALL Java_ug_audiocity_studio_NativeAudio_bufferFrames(JNIEnv*, jclass, jlong h) {
    Stream* s = from(h);
    return s && s->stream ? AAudioStream_getBufferSizeInFrames(s->stream) : 0;
}
JNIEXPORT jboolean JNICALL Java_ug_audiocity_studio_NativeAudio_exclusive(JNIEnv*, jclass, jlong h) {
    Stream* s = from(h);
    return s && s->stream && AAudioStream_getSharingMode(s->stream) == AAUDIO_SHARING_MODE_EXCLUSIVE ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL Java_ug_audiocity_studio_NativeAudio_xruns(JNIEnv*, jclass, jlong h) {
    Stream* s = from(h);
    return s && s->stream ? AAudioStream_getXRunCount(s->stream) : 0;
}
// stop() may be called from another thread while a write is blocked; requestStop makes the write return promptly.
JNIEXPORT void JNICALL Java_ug_audiocity_studio_NativeAudio_stop(JNIEnv*, jclass, jlong h) {
    Stream* s = from(h);
    if (s && s->stream) AAudioStream_requestStop(s->stream);
}
JNIEXPORT void JNICALL Java_ug_audiocity_studio_NativeAudio_close(JNIEnv*, jclass, jlong h) {
    Stream* s = from(h);
    if (!s) return;
    {
        std::lock_guard<std::mutex> guard(s->lock);
        if (s->stream) { AAudioStream_close(s->stream); s->stream = nullptr; }
    }
    delete s;
}

}  // extern "C"

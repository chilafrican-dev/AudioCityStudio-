/* JNI bridge for ug.audiocity.cinema.ProRes. */
#include <jni.h>
#include <stdlib.h>
#include "prores.h"

static uint8_t *addr(JNIEnv *env, jobject buf) { return buf ? (uint8_t *) (*env)->GetDirectBufferAddress(env, buf) : NULL; }

JNIEXPORT jlong JNICALL Java_ug_audiocity_cinema_ProRes_nativeEncode(JNIEnv *env, jclass cls,
    jobject y, jint yStride, jobject u, jobject v, jint cStride, jint width, jint height,
    jint profile, jint primaries, jint transfer, jint matrix, jobject out, jfloatArray stats, jobject hint) {
  prores_frame f = { width, height, addr(env, y), addr(env, u), addr(env, v), yStride, cStride };
  uint8_t *h = addr(env, hint);
  prores_params p = { profile, 0, primaries, transfer, matrix, h, h ? (int) (*env)->GetDirectBufferCapacity(env, hint) : 0 };
  uint8_t *o = addr(env, out);
  if (!f.y || !f.u || !f.v || !o) return -10;
  prores_stats s = { 0, 0, 0 };
  long n = prores_encode(&f, &p, o, (size_t) (*env)->GetDirectBufferCapacity(env, out), &s);
  if (stats && (*env)->GetArrayLength(env, stats) >= 2) {
    jfloat st[2] = { s.average_q, (jfloat) s.overflow_slices };
    (*env)->SetFloatArrayRegion(env, stats, 0, 2, st);
  }
  return (jlong) n;
}

JNIEXPORT void JNICALL Java_ug_audiocity_cinema_ProRes_nativeCopyPlane(JNIEnv *env, jclass cls,
    jobject src, jint rowStride, jint pixelStride, jint width, jint height, jobject dst, jint dstStride) {
  uint8_t *s = addr(env, src), *d = addr(env, dst);
  if (s && d) prores_copy_plane(s, rowStride, pixelStride, width, height, d, dstStride);
}

JNIEXPORT jlong JNICALL Java_ug_audiocity_cinema_ProRes_nativeMaxFrameBytes(JNIEnv *env, jclass cls, jint w, jint h) {
  return (jlong) prores_max_frame_bytes(w, h);
}

JNIEXPORT jint JNICALL Java_ug_audiocity_cinema_ProRes_nativeBitsPerMb(JNIEnv *env, jclass cls, jint profile) {
  return prores_bits_per_mb(profile);
}

JNIEXPORT jint JNICALL Java_ug_audiocity_cinema_ProRes_nativeSlices(JNIEnv *env, jclass cls, jint w, jint h) {
  return prores_slices(w, h);
}

JNIEXPORT jboolean JNICALL Java_ug_audiocity_cinema_ProRes_nativeUsesNeon(JNIEnv *env, jclass cls) {
  return prores_uses_neon() ? JNI_TRUE : JNI_FALSE;
}

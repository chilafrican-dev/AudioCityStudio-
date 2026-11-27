/* JNI bridge for ug.audiocity.cinema.RawNative. */
#include <jni.h>
#include "rawtools.h"

static void *addr(JNIEnv *env, jobject b) { return b ? (*env)->GetDirectBufferAddress(env, b) : NULL; }

JNIEXPORT void JNICALL Java_ug_audiocity_cinema_RawNative_copy(JNIEnv *env, jclass c, jobject src, jint rowStride,
    jint x0, jint y0, jint w, jint h, jboolean quad, jobject dst) {
  uint8_t *s = addr(env, src); uint16_t *d = addr(env, dst);
  if (s && d) raw_copy(s, rowStride, x0, y0, w, h, quad, d);
}

JNIEXPORT void JNICALL Java_ug_audiocity_cinema_RawNative_preview(JNIEnv *env, jclass c, jobject pixels, jint w, jint h,
    jint cfa, jfloatArray black, jint white, jfloatArray gains, jint ow, jint oh, jintArray argb) {
  uint16_t *p = addr(env, pixels);
  if (!p) return;
  jfloat bl[4], g[3];
  (*env)->GetFloatArrayRegion(env, black, 0, 4, bl);
  (*env)->GetFloatArrayRegion(env, gains, 0, 3, g);
  jint *out = (*env)->GetIntArrayElements(env, argb, NULL);
  raw_preview(p, w, h, cfa, bl, white, g, ow, oh, (uint32_t *) out);
  (*env)->ReleaseIntArrayElements(env, argb, out, 0);
}

JNIEXPORT jlong JNICALL Java_ug_audiocity_cinema_RawNative_lj92(JNIEnv *env, jclass c, jobject pixels, jint w, jint h,
    jint bits, jint tw, jint th, jobject out, jintArray tileSizes) {
  uint16_t *p = addr(env, pixels); uint8_t *o = addr(env, out);
  if (!p || !o) return -10;
  jint *sizes = (*env)->GetIntArrayElements(env, tileSizes, NULL);
  long n = lj92_tiles(p, w, h, bits, tw, th, o, (size_t) (*env)->GetDirectBufferCapacity(env, out), (uint32_t *) sizes);
  (*env)->ReleaseIntArrayElements(env, tileSizes, sizes, 0);
  return (jlong) n;
}

JNIEXPORT jlong JNICALL Java_ug_audiocity_cinema_RawNative_lj92MaxBytes(JNIEnv *env, jclass c, jint w, jint h, jint tw, jint th) {
  return (jlong) lj92_max_bytes(w, h, tw, th);
}

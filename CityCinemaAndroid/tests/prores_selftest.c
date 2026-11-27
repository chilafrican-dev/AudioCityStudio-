/*
 * Native ProRes self-test and benchmark. Encodes a deterministic moving test sequence and prints an FNV-1a
 * fingerprint of every encoded frame plus the speed. Builds that must be bit-identical (x86 scalar, ARM scalar,
 * ARM NEON) must print the same fingerprints: see tools/prores-check.sh.
 * Usage: prores_selftest [width height frames profile [scene]]  scene: hard (default: noise + fine detail) or camera
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "../jni/prores.h"

static uint32_t rng = 1;
static uint32_t next(void) { rng = rng * 1664525u + 1013904223u; return rng >> 8; }

static int camera_scene = 0;

static void fill(uint8_t *y, uint8_t *u, uint8_t *v, int w, int h, int t) {
  int cw = (w + 1) / 2, ch = (h + 1) / 2;
  for (int j = 0; j < h; j++)
    for (int i = 0; i < w; i++) {
      int base = (i * 255 / w + t * 3) & 255;
      int dx = i - w / 2 - t * 4, dy = j - h / 2, r2 = dx * dx + dy * dy, ring = 0;
      for (int k = 1; k * k * 576 <= r2; k++) ring ^= 1; /* rings 24 px apart, no floating point */
      int detail = j < h / 3 ? ((i / 2 + j / 2) % 2) * 30 : 0;
      int val = (ring ? base : 255 - base) / 2 + detail + 40 + (int) (next() % 9) - 4;
      if (camera_scene) /* smooth light falloff, a few hard edges, mild sensor noise */
        val = 30 + (i * 140 / w) + (j * 50 / h) + (ring ? 25 : 0) + ((i / 97 + j / 61 + t) % 7 == 0 ? 40 : 0) + (int) (next() % 3) - 1;
      y[j * w + i] = (uint8_t) (val < 0 ? 0 : val > 255 ? 255 : val);
    }
  for (int j = 0; j < ch; j++)
    for (int i = 0; i < cw; i++) { u[j * cw + i] = (uint8_t) (68 + ((i + t * 2) * 3) % 120); v[j * cw + i] = (uint8_t) (78 + (j * 5) % 100); }
}

int main(int argc, char **argv) {
  int w = argc > 1 ? atoi(argv[1]) : 1920, h = argc > 2 ? atoi(argv[2]) : 1080, n = argc > 3 ? atoi(argv[3]) : 6;
  int profile = argc > 4 ? atoi(argv[4]) : 3;
  camera_scene = argc > 5 && strcmp(argv[5], "camera") == 0;
  int cw = (w + 1) / 2, ch = (h + 1) / 2;
  uint8_t *y = malloc((size_t) w * h), *u = malloc((size_t) cw * ch), *v = malloc((size_t) cw * ch);
  size_t cap = prores_max_frame_bytes(w, h);
  uint8_t *out = malloc(cap), *hint = calloc((size_t) prores_slices(w, h), 1);
  prores_params p = { profile, 0, 1, 1, 6, hint, prores_slices(w, h) };
  double total_ms = 0;
  long bytes = 0;
  for (int t = 0; t < n; t++) {
    fill(y, u, v, w, h, t);
    prores_frame f = { w, h, y, u, v, w, cw };
    prores_stats s;
    struct timespec a, b;
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &a);
    long size = prores_encode(&f, &p, out, cap, &s);
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &b);
    if (size <= 0) { fprintf(stderr, "encode failed: %ld\n", size); return 1; }
    double ms = (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6;
    if (t > 0) total_ms += ms; /* frame 0 warms up and has no quantiser memory */
    bytes += size;
    uint32_t hash = 2166136261u;
    for (long i = 0; i < size; i++) hash = (hash ^ out[i]) * 16777619u;
    printf("frame %d: %ld bytes, q %.2f, fingerprint %08x\n", t, size, s.average_q, hash);
  }
  printf("%s: %dx%d profile %d, %.1f ms/frame (%.1f fps on one core), %ld KB/frame\n", prores_uses_neon() ? "NEON" : "scalar",
         w, h, profile, total_ms / (n > 1 ? n - 1 : 1), (n > 1 ? n - 1 : 1) * 1000.0 / total_ms, bytes / 1024 / n);
  return 0;
}

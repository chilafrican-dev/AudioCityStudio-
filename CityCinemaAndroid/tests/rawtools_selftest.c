/* RAW tools self-test: compresses a deterministic 8K-class Bayer frame and prints a fingerprint and the speed.
 * NEON and scalar builds must print the same fingerprint. Usage: rawtools_selftest [width height whitelevel] */
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include "../jni/rawtools.h"

int main(int argc, char **argv) {
  int w = argc > 1 ? atoi(argv[1]) : 7680, h = argc > 2 ? atoi(argv[2]) : 4320, white = argc > 3 ? atoi(argv[3]) : 4095;
  int bits = 2; while (bits < 16 && (1 << bits) - 1 < white) bits++;
  uint16_t *px = malloc((size_t) w * h * 2), *crop = malloc((size_t) (w / 2) * (h / 2) * 2);
  uint32_t seed = 1;
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++) {
      seed = seed * 1664525u + 1013904223u;
      int v = 64 + (x * white / 2) / w + (y * white / 4) / h + (((x >> 7) + (y >> 6)) % 3) * white / 10 + (int) ((seed >> 24) % 9) - 4;
      px[(size_t) y * w + x] = (uint16_t) (v < 0 ? 0 : v > white ? white : v);
    }
  int tiles = ((w + 255) / 256) * ((h + 255) / 256);
  uint32_t *sizes = malloc(tiles * 4);
  size_t cap = lj92_max_bytes(w, h, 256, 256);
  uint8_t *out = malloc(cap);
  struct timespec a, b;
  clock_gettime(CLOCK_THREAD_CPUTIME_ID, &a);
  long n = lj92_tiles(px, w, h, bits, 256, 256, out, cap, sizes);
  clock_gettime(CLOCK_THREAD_CPUTIME_ID, &b);
  if (n <= 0) { fprintf(stderr, "lj92 failed %ld\n", n); return 1; }
  uint32_t hash = 2166136261u;
  for (long i = 0; i < n; i++) hash = (hash ^ out[i]) * 16777619u;
  raw_copy((const uint8_t *) px, w * 2, 8, 4, w / 2, h / 2, 1, crop);
  for (long i = 0; i < (long) (w / 2) * (h / 2); i++) hash = (hash ^ crop[i]) * 16777619u;
  double ms = (b.tv_sec - a.tv_sec) * 1e3 + (b.tv_nsec - a.tv_nsec) / 1e6;
  printf("lj92 %dx%d %d-bit: %ld bytes (%.2f:1 vs packed), %.0f ms per frame on one core, fingerprint %08x\n",
         w, h, bits, n, (double) w * h * bits / 8 / n, ms, hash);
  return 0;
}

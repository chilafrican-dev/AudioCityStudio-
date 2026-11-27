/* City Cinema ProRes 422 encoder: see prores.c. */
#ifndef CITYCINEMA_PRORES_H
#define CITYCINEMA_PRORES_H
#include <stddef.h>
#include <stdint.h>

/* Profiles. Four-character codes in a QuickTime file: apco, apcs, apcn, apch. */
enum { PRORES_PROXY = 0, PRORES_LT = 1, PRORES_422 = 2, PRORES_HQ = 3 };

/* An 8-bit YUV 4:2:0 frame (full-range Camera2 YCbCr). Chroma planes are (width+1)/2 x (height+1)/2. */
typedef struct {
  int width, height;
  const uint8_t *y, *u, *v;
  int y_stride, c_stride;
} prores_frame;

typedef struct {
  int profile;
  int bits_per_mb;  /* 0 = the profile's target */
  int color_primaries, transfer, matrix; /* ISO/IEC 23091-2 codes, e.g. 1 = BT.709, 6 = BT.601 */
} prores_params;

typedef struct { float average_q; int slices, overflow_slices; } prores_stats;

/* Encodes one frame. Returns its size in bytes, or a negative error. Thread-safe. */
long prores_encode(const prores_frame *f, const prores_params *p, uint8_t *out, size_t cap, prores_stats *stats);

/* An output buffer size that always suffices for a frame of this size. */
size_t prores_max_frame_bytes(int width, int height);

/* The quantisation matrix used for a profile (row-major). */
void prores_matrix(int profile, uint8_t out[64]);

int prores_bits_per_mb(int profile);

/* Copies an image plane with any pixel stride into a compact plane. */
void prores_copy_plane(const uint8_t *src, int row_stride, int pixel_stride, int width, int height, uint8_t *dst, int dst_stride);
#endif

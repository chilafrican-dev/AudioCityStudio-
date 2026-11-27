/*
 * City Cinema ProRes 422 encoder (Proxy, LT, 422, HQ), written from the bitstream published in SMPTE RDD 36.
 *
 * Input: one camera frame as 8-bit YUV 4:2:0 (full-range "JFIF" YCbCr, as Camera2 delivers it).
 * Output: one ProRes 422 frame ('icpf'), 10-bit 4:2:2 limited range, progressive, slices of 8 macroblocks.
 *
 * Per slice: 4:2:0 -> 4:2:2 vertical chroma interpolation and 8 -> 10-bit legal-range mapping, 8x8 DCT,
 * then a per-slice quantiser chosen to meet the profile's bit budget (Apple's published target data rates).
 * No global mutable state: several threads may encode different frames at the same time.
 */
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "prores.h"

/* ------------------------------------------------------------------------------------------------ tables */

/* Progressive scan order (RDD 36). */
static const uint8_t SCAN[64] = {
     0,  1,  8,  9,  2,  3, 10, 11, 16, 17, 24, 25, 18, 19, 26, 27,
     4,  5, 12, 20, 13,  6,  7, 14, 21, 28, 29, 22, 15, 23, 30, 31,
    32, 33, 40, 48, 41, 34, 35, 42, 49, 56, 57, 50, 43, 36, 37, 44,
    51, 58, 59, 52, 45, 38, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63 };

/* Adaptive codebooks (RDD 36): byte = rice_order << 5 | exp_golomb_order << 2 | (switch_bits - 1). */
static const uint8_t DC_CB[7] = { 0x04, 0x28, 0x28, 0x4D, 0x4D, 0x70, 0x70 };
static const uint8_t RUN_CB[16] = { 0x06, 0x06, 0x05, 0x05, 0x04, 0x29, 0x29, 0x29, 0x29, 0x28, 0x28, 0x28, 0x28, 0x28, 0x28, 0x4C };
static const uint8_t LEVEL_CB[10] = { 0x04, 0x0A, 0x05, 0x06, 0x04, 0x28, 0x28, 0x28, 0x28, 0x4C };
#define FIRST_DC_CB 0xB8

/* Profiles: four-character codes and the bits per macroblock that give Apple's published target rates
 * (1920x1080 at 29.97: Proxy 45, LT 102, 422 147, HQ 220 Mb/s; 8160 macroblocks per frame). */
static const int BITS_PER_MB[4] = { 184, 417, 601, 900 };
static const int QMIN[4] = { 2, 1, 1, 1 }, QMAX[4] = { 16, 12, 10, 8 };
/* Matrix steepness: how fast quantisation grows with frequency. Original values, tuned per profile. */
static const float MATRIX_SLOPE[4] = { 1.2f, 0.65f, 0.30f, 0.06f };

static float DCT_K[8][8];
static uint16_t Y10[256], C10[256];
static pthread_once_t once = PTHREAD_ONCE_INIT;

static void init_tables(void) {
  for (int u = 0; u < 8; u++)
    for (int x = 0; x < 8; x++)
      DCT_K[u][x] = (float) ((u == 0 ? M_SQRT1_2 : 1.0) * cos((2 * x + 1) * u * M_PI / 16.0));
  for (int i = 0; i < 256; i++) {
    /* full-range 8-bit -> limited-range 10-bit: Y 0..255 -> 64..940, C 0..255 (128 centre) -> 64..960 */
    Y10[i] = (uint16_t) lrint(64 + i * 876.0 / 255.0);
    int c = (int) lrint(512 + (i - 128) * 896.0 / 255.0);
    C10[i] = (uint16_t) (c < 64 ? 64 : c > 960 ? 960 : c);
  }
}

void prores_matrix(int profile, uint8_t out[64]) {
  if (profile < 0 || profile > 3) profile = 3;
  for (int r = 0; r < 8; r++)
    for (int c = 0; c < 8; c++) {
      int v = (int) lrintf(4 + MATRIX_SLOPE[profile] * powf((float) (r + c), 1.5f));
      out[r * 8 + c] = (uint8_t) (v > 63 ? 63 : v);
    }
}

int prores_bits_per_mb(int profile) { return BITS_PER_MB[profile < 0 || profile > 3 ? 3 : profile]; }

/* ------------------------------------------------------------------------------------------------ bit writer */

typedef struct { uint8_t *p, *start, *end; uint64_t acc; int bits; int overflow; } bitw;

static void bw_init(bitw *b, uint8_t *buf, size_t cap) { b->p = b->start = buf; b->end = buf + cap; b->acc = 0; b->bits = 0; b->overflow = 0; }

/* Appends n (0..32) bits, MSB first. Pending bits live in the low end of a 64-bit accumulator and are flushed
 * 32 at a time. */
static inline void put_bits(bitw *b, int n, uint32_t v) {
  if (n == 0) return;
  b->acc = (b->acc << n) | (n == 32 ? v : (v & ((1u << n) - 1)));
  b->bits += n;
  if (b->bits >= 32) {
    b->bits -= 32;
    uint32_t word = (uint32_t) (b->acc >> b->bits);
    if (b->p + 4 <= b->end) { b->p[0] = (uint8_t) (word >> 24); b->p[1] = (uint8_t) (word >> 16); b->p[2] = (uint8_t) (word >> 8); b->p[3] = (uint8_t) word; b->p += 4; }
    else b->overflow = 1;
  }
}

static inline void put_zeros(bitw *b, int n) { while (n > 32) { put_bits(b, 32, 0); n -= 32; } put_bits(b, n, 0); }

/* Pads to a byte boundary and writes out the pending bits. */
static void bw_flush(bitw *b) {
  if (b->bits & 7) put_bits(b, 8 - (b->bits & 7), 0);
  while (b->bits >= 8) {
    b->bits -= 8;
    if (b->p < b->end) *b->p++ = (uint8_t) (b->acc >> b->bits); else b->overflow = 1;
  }
}

static size_t bw_bytes(const bitw *b) { return (size_t) (b->p - b->start); }

/* Rice / exp-Golomb hybrid codeword (RDD 36, 7.1.1), written with one put_bits call when it fits in 32 bits. */
static inline void put_vlc(bitw *b, unsigned cb, unsigned val) {
  unsigned switch_bits = (cb & 3) + 1, rice = cb >> 5, expo = (cb >> 2) & 7;
  unsigned switch_val = switch_bits << rice;
  if (val >= switch_val) {
    val -= switch_val - (1u << expo);
    int e = 31 - __builtin_clz(val);
    int zeros = e - (int) expo + (int) switch_bits, len = zeros + e + 1;
    if (len <= 32) put_bits(b, len, val); /* leading zeros are implicit in the value */
    else { put_zeros(b, zeros); put_bits(b, e + 1, val); }
  } else {
    unsigned e = val >> rice, len = e + 1 + rice;
    uint32_t code = (1u << rice) | (val & ((1u << rice) - 1));
    if (len <= 32) put_bits(b, (int) len, code);
    else { put_zeros(b, (int) e); put_bits(b, (int) (rice + 1), code); }
  }
}

static inline unsigned make_code(int x) { return (unsigned) ((x * 2) ^ (x >> 31)); }

/* Rounded division toward the nearest integer (both signs). */
static inline int div_round(int a, int q) { return a >= 0 ? (a + q / 2) / q : -((-a + q / 2) / q); }

/* ------------------------------------------------------------------------------------------------ DCT */

/* Forward DCT of an 8x8 block of 10-bit samples, scaled as ProRes expects (mid-grey DC = 0x4000). */
static void fdct8x8(const uint16_t *src, int stride, int16_t *out) {
  float tmp[8][8];
  for (int y = 0; y < 8; y++) {
    const uint16_t *row = src + y * stride;
    for (int u = 0; u < 8; u++) {
      float s = 0;
      for (int x = 0; x < 8; x++) s += DCT_K[u][x] * row[x];
      tmp[y][u] = s;
    }
  }
  for (int v = 0; v < 8; v++)
    for (int u = 0; u < 8; u++) {
      float s = 0;
      for (int y = 0; y < 8; y++) s += DCT_K[v][y] * tmp[y][u];
      long c = lrintf(s);
      out[v * 8 + u] = (int16_t) (c > 32767 ? 32767 : c < -32768 ? -32768 : c);
    }
}

/* ------------------------------------------------------------------------------------------------ slices */

#define MAX_SLICE_MBS 8

typedef struct {
  int16_t luma[MAX_SLICE_MBS * 4][64];
  int16_t cb[MAX_SLICE_MBS * 2][64];
  int16_t cr[MAX_SLICE_MBS * 2][64];
  uint16_t ypix[16][MAX_SLICE_MBS * 16];
  uint16_t cbpix[16][MAX_SLICE_MBS * 8];
  uint16_t crpix[16][MAX_SLICE_MBS * 8];
  uint8_t trial[3][MAX_SLICE_MBS * 4 * 64 * 6];
} slice_work;

/* Fetches a 16-row strip of the frame for one slice, converting to 10-bit 4:2:2 with edge replication. */
static void load_slice(const prores_frame *f, int mbx, int mby, int mbs, slice_work *w) {
  int x0 = mbx * 16, y0 = mby * 16, width = mbs * 16;
  int cw = (f->width + 1) / 2, ch = (f->height + 1) / 2;
  for (int r = 0; r < 16; r++) {
    int y = y0 + r; if (y >= f->height) y = f->height - 1;
    const uint8_t *src = f->y + (size_t) y * f->y_stride;
    for (int c = 0; c < width; c++) { int x = x0 + c; if (x >= f->width) x = f->width - 1; w->ypix[r][c] = Y10[src[x]]; }
    /* 4:2:0 chroma sits between luma rows: 4:2:2 row y = 3/4 of the nearest 4:2:0 row + 1/4 of the next nearest. */
    int j = y >> 1, k = (y & 1) ? j + 1 : j - 1;
    if (k < 0) k = 0;
    if (k >= ch) k = ch - 1;
    if (j >= ch) j = ch - 1;
    const uint8_t *u0 = f->u + (size_t) j * f->c_stride, *u1 = f->u + (size_t) k * f->c_stride;
    const uint8_t *v0 = f->v + (size_t) j * f->c_stride, *v1 = f->v + (size_t) k * f->c_stride;
    for (int c = 0; c < width / 2; c++) {
      int x = x0 / 2 + c; if (x >= cw) x = cw - 1;
      w->cbpix[r][c] = (uint16_t) ((3 * C10[u0[x]] + C10[u1[x]] + 2) >> 2);
      w->crpix[r][c] = (uint16_t) ((3 * C10[v0[x]] + C10[v1[x]] + 2) >> 2);
    }
  }
  for (int m = 0; m < mbs; m++) {
    /* luma block order inside a macroblock: top-left, top-right, bottom-left, bottom-right */
    fdct8x8(&w->ypix[0][m * 16], MAX_SLICE_MBS * 16, w->luma[m * 4 + 0]);
    fdct8x8(&w->ypix[0][m * 16 + 8], MAX_SLICE_MBS * 16, w->luma[m * 4 + 1]);
    fdct8x8(&w->ypix[8][m * 16], MAX_SLICE_MBS * 16, w->luma[m * 4 + 2]);
    fdct8x8(&w->ypix[8][m * 16 + 8], MAX_SLICE_MBS * 16, w->luma[m * 4 + 3]);
    /* 4:2:2 chroma: two 8x8 blocks per macroblock, top then bottom */
    fdct8x8(&w->cbpix[0][m * 8], MAX_SLICE_MBS * 8, w->cb[m * 2 + 0]);
    fdct8x8(&w->cbpix[8][m * 8], MAX_SLICE_MBS * 8, w->cb[m * 2 + 1]);
    fdct8x8(&w->crpix[0][m * 8], MAX_SLICE_MBS * 8, w->cr[m * 2 + 0]);
    fdct8x8(&w->crpix[8][m * 8], MAX_SLICE_MBS * 8, w->cr[m * 2 + 1]);
  }
}

/* Entropy-codes one component of a slice at quantiser q. Returns bytes written (byte aligned). */
static size_t code_plane(int16_t (*blocks)[64], int nblocks, const uint8_t *matrix, int q, uint8_t *out, size_t cap, int *overflow) {
  bitw b; bw_init(&b, out, cap);
  int qm[64];
  for (int i = 0; i < 64; i++) qm[i] = matrix[i] * q;
  /* DC: first value absolute, then sign-adaptive differences */
  int prev = div_round(blocks[0][0] - 0x4000, qm[0]);
  put_vlc(&b, FIRST_DC_CB, make_code(prev));
  int cb = 5, sign = 0;
  for (int i = 1; i < nblocks; i++) {
    int dc = div_round(blocks[i][0] - 0x4000, qm[0]);
    int delta = dc - prev, new_sign = delta >> 31;
    delta = (delta ^ sign) - sign;
    unsigned code = make_code(delta);
    put_vlc(&b, DC_CB[cb], code);
    cb = code < 6 ? (int) code : 6;
    sign = new_sign; prev = dc;
  }
  /* AC: coefficients interleaved across all blocks of the slice, in scan order, run/level coded */
  int prev_run = 4, prev_level = 2, run = 0;
  for (int i = 1; i < 64; i++) {
    int pos = SCAN[i], qq = qm[pos];
    /* Slight dead zone: round magnitudes at 0.4 instead of 0.5. Fewer tiny levels, cleaner flat areas. */
    int bias = (qq * 2) / 5, zero_below = qq - bias;
    /* Division by multiplication: floor(x / qq) for x < 2^16 using a 2^32 / qq reciprocal (exact in this range). */
    uint64_t recip = (0xFFFFFFFFull / (uint64_t) qq) + 1;
    for (int k = 0; k < nblocks; k++) {
      int c = blocks[k][pos];
      int a = c < 0 ? -c : c;
      if (a < zero_below) { run++; continue; } /* most coefficients: quantise to zero without dividing */
      int level = (int) (((uint64_t) (a + bias) * recip) >> 32);
      if (level) {
        put_vlc(&b, RUN_CB[prev_run], (unsigned) run);
        put_vlc(&b, LEVEL_CB[prev_level], (unsigned) (level - 1));
        put_bits(&b, 1, c < 0 ? 1 : 0);
        prev_run = run < 15 ? run : 15;
        prev_level = level < 9 ? level : 9;
        run = 0;
      } else run++;
    }
  }
  bw_flush(&b);
  if (b.overflow) *overflow = 1;
  return bw_bytes(&b);
}

/* Codes all three components of a slice at quantiser q into the trial buffers. Returns 1 if it fits the budget. */
static int code_slice(slice_work *w, int mbs, const uint8_t *matrix, int q, size_t budget, size_t *sy, size_t *su, size_t *sv, int *of) {
  *of = 0;
  *sy = code_plane(w->luma, mbs * 4, matrix, q, w->trial[0], sizeof(w->trial[0]), of);
  *su = code_plane(w->cb, mbs * 2, matrix, q, w->trial[1], sizeof(w->trial[1]), of);
  *sv = code_plane(w->cr, mbs * 2, matrix, q, w->trial[2], sizeof(w->trial[2]), of);
  return !*of && *sy + *su + *sv <= budget;
}

/* ------------------------------------------------------------------------------------------------ frame */

static void be16(uint8_t *p, unsigned v) { p[0] = (uint8_t) (v >> 8); p[1] = (uint8_t) v; }
static void be32(uint8_t *p, uint32_t v) { p[0] = (uint8_t) (v >> 24); p[1] = (uint8_t) (v >> 16); p[2] = (uint8_t) (v >> 8); p[3] = (uint8_t) v; }

static int slices_in_row(int mb_width) {
  int n = 0, mbs = MAX_SLICE_MBS;
  for (int x = 0; x < mb_width; x += mbs) { while (mb_width - x < mbs) mbs >>= 1; n++; }
  return n;
}

size_t prores_max_frame_bytes(int width, int height) {
  size_t mbs = (size_t) ((width + 15) / 16) * ((height + 15) / 16);
  return 256 + mbs * (2 + 16) + mbs * 4096; /* generous: ~4 KB per macroblock worst case */
}

long prores_encode(const prores_frame *f, const prores_params *p, uint8_t *out, size_t cap, prores_stats *stats) {
  pthread_once(&once, init_tables);
  if (!f || !p || !out || f->width < 16 || f->height < 16 || f->width > 8192 || f->height > 8192) return -1;
  int profile = p->profile < 0 || p->profile > 3 ? 3 : p->profile;
  uint8_t matrix[64];
  prores_matrix(profile, matrix);
  int mb_w = (f->width + 15) / 16, mb_h = (f->height + 15) / 16;
  int per_row = slices_in_row(mb_w), nslices = per_row * mb_h;
  int budget_per_mb = BITS_PER_MB[profile];
  if (p->bits_per_mb > 0) budget_per_mb = p->bits_per_mb;

  /* frame container + frame header (with both quantisation matrices) */
  size_t hdr = 8 + 20 + 128;
  size_t pic_hdr = 8 + (size_t) nslices * 2;
  if (cap < hdr + pic_hdr + 64) return -2;
  uint8_t *o = out;
  memcpy(o + 4, "icpf", 4);
  uint8_t *h = o + 8;
  be16(h, 20 + 128);           /* frame header size */
  be16(h + 2, 0);              /* version 0: 4:2:2, no alpha */
  memcpy(h + 4, "ccin", 4);    /* encoder identifier */
  be16(h + 8, (unsigned) f->width);
  be16(h + 10, (unsigned) f->height);
  h[12] = 2 << 6;              /* chroma 4:2:2, progressive */
  h[13] = 0;                   /* aspect ratio / frame rate: unspecified */
  h[14] = (uint8_t) p->color_primaries;
  h[15] = (uint8_t) p->transfer;
  h[16] = (uint8_t) p->matrix;
  h[17] = 0;                   /* no alpha */
  h[18] = 0;                   /* reserved */
  h[19] = 0x03;                /* custom luma and chroma matrices follow */
  memcpy(h + 20, matrix, 64);
  memcpy(h + 84, matrix, 64);

  uint8_t *pic = out + hdr;
  pic[0] = 0x40;               /* picture header: 8 bytes */
  be16(pic + 5, (unsigned) nslices);
  pic[7] = 3 << 4;             /* log2(8 macroblocks per slice) */
  uint8_t *table = pic + 8;
  uint8_t *cursor = table + nslices * 2;

  slice_work *w = (slice_work *) malloc(sizeof(slice_work));
  if (!w) return -3;
  long q_sum = 0; int over = 0;
  int slice = 0;
  int row_q = profile == 0 ? 6 : 3;
  for (int my = 0; my < mb_h; my++) {
    int mbs = MAX_SLICE_MBS, q = row_q;
    for (int mx = 0; mx < mb_w; mx += mbs, slice++) {
      while (mb_w - mx < mbs) mbs >>= 1;
      load_slice(f, mx, my, mbs, w);
      size_t budget = (size_t) budget_per_mb * mbs / 8;
      size_t room = (size_t) (out + cap - cursor);
      if (room < 6 + 3 * sizeof(w->trial[0])) { free(w); return -2; }
      size_t sy = 0, su = 0, sv = 0;
      int of = 0;
      /* Start from the neighbouring slice's quantiser. If it fits, try up to two finer steps; otherwise go
       * coarser until it fits or the profile's limit is reached (the frame may then run a little over). */
      if (code_slice(w, mbs, matrix, q, budget, &sy, &su, &sv, &of)) {
        /* Only worth trying a finer quantiser when this one left a good margin (~1 step is ~10-15% bits). */
        for (int down = 0; down < 2 && q > QMIN[profile] && (sy + su + sv) * 100 < budget * 86; down++) {
          if (code_slice(w, mbs, matrix, q - 1, budget, &sy, &su, &sv, &of)) q--;
          else { code_slice(w, mbs, matrix, q, budget, &sy, &su, &sv, &of); break; }
        }
      } else {
        while (q < QMAX[profile] && !code_slice(w, mbs, matrix, q + 1, budget, &sy, &su, &sv, &of)) q++;
        if (q < QMAX[profile]) q++;  /* q + 1 fitted: the trial buffers hold it */
        while (of && q < 128) { q += 4; if (q > 128) q = 128; code_slice(w, mbs, matrix, q, budget, &sy, &su, &sv, &of); } /* never overflow a slice */
      }
      if (of) over++;
      /* slice header: size 6 bytes, quantiser, luma and Cb sizes (Cr is implied) */
      cursor[0] = 6 << 3;
      cursor[1] = (uint8_t) q;
      be16(cursor + 2, (unsigned) sy);
      be16(cursor + 4, (unsigned) su);
      memcpy(cursor + 6, w->trial[0], sy);
      memcpy(cursor + 6 + sy, w->trial[1], su);
      memcpy(cursor + 6 + sy + su, w->trial[2], sv);
      size_t slice_bytes = 6 + sy + su + sv;
      be16(table + slice * 2, (unsigned) slice_bytes);
      cursor += slice_bytes;
      q_sum += q;
      if (mx == 0) row_q = q;
    }
  }
  free(w);
  be32(pic + 1, (uint32_t) (cursor - pic));
  long frame_size = (long) (cursor - out);
  be32(out, (uint32_t) frame_size);
  if (stats) { stats->average_q = nslices ? (float) q_sum / nslices : 0; stats->slices = nslices; stats->overflow_slices = over; }
  return frame_size;
}

/* ------------------------------------------------------------------------------------------------ helpers */

void prores_copy_plane(const uint8_t *src, int row_stride, int pixel_stride, int width, int height, uint8_t *dst, int dst_stride) {
  for (int y = 0; y < height; y++) {
    const uint8_t *s = src + (size_t) y * row_stride;
    uint8_t *d = dst + (size_t) y * dst_stride;
    if (pixel_stride == 1) memcpy(d, s, (size_t) width);
    else for (int x = 0; x < width; x++) d[x] = s[(size_t) x * pixel_stride];
  }
}

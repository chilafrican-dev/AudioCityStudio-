/*
 * City Cinema ProRes 422 encoder (Proxy, LT, 422, HQ), written from the bitstream published in SMPTE RDD 36.
 *
 * Input: one camera frame as 8-bit YUV 4:2:0 (full-range "JFIF" YCbCr, as Camera2 delivers it).
 * Output: one ProRes 422 frame ('icpf'), 10-bit 4:2:2 limited range, progressive, slices of 8 macroblocks.
 *
 * Per slice: 4:2:0 -> 4:2:2 vertical chroma interpolation and 8 -> 10-bit legal-range mapping, 8x8 DCT,
 * then a per-slice quantiser chosen to meet the profile's bit budget (Apple's published target data rates).
 * No global mutable state: several threads may encode different frames at the same time.
 *
 * Everything is integer arithmetic with a scalar and a NEON (ARMv8/ARMv7) implementation of the hot paths
 * (DCT and quantisation). Both compute exactly the same values, so NEON and scalar builds are bit-identical;
 * tools/prores-check.sh compares them. Build with -DPRORES_NO_NEON to force the scalar path.
 */
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "prores.h"

#if (defined(__ARM_NEON) || defined(__ARM_NEON__)) && !defined(PRORES_NO_NEON)
#include <arm_neon.h>
#define PRORES_NEON 1
#else
#define PRORES_NEON 0
#endif

int prores_uses_neon(void) { return PRORES_NEON; }

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
/* Matrix steepness (per mille): how fast quantisation grows with frequency. Original values, tuned per profile. */
static const int MATRIX_SLOPE[4] = { 1200, 650, 300, 60 };
/* round(1000 * n^1.5) for n = 0..14 (n = row + column), so the matrix needs no floating point. */
static const int POW15[15] = { 0, 1000, 2828, 5196, 8000, 11180, 14697, 18520, 22627, 27000, 31623, 36483, 41569, 46872, 52383 };

/* 13-bit fixed-point DCT basis: round(8192 * C(u) * cos((2x + 1) u pi / 16)), C(0) = 1/sqrt(2). */
static const int16_t DCT_K[8][8] = {
  {  5793,   5793,   5793,   5793,   5793,   5793,   5793,   5793 },
  {  8035,   6811,   4551,   1598,  -1598,  -4551,  -6811,  -8035 },
  {  7568,   3135,  -3135,  -7568,  -7568,  -3135,   3135,   7568 },
  {  6811,  -1598,  -8035,  -4551,   4551,   8035,   1598,  -6811 },
  {  5793,  -5793,  -5793,   5793,   5793,  -5793,  -5793,   5793 },
  {  4551,  -8035,   1598,   6811,  -6811,  -1598,   8035,  -4551 },
  {  3135,  -7568,   7568,  -3135,  -3135,   7568,  -7568,   3135 },
  {  1598,  -4551,   6811,  -8035,   8035,  -6811,   4551,  -1598 },
};
static int16_t DCT_KT[8][8]; /* transposed: DCT_KT[x][u] = DCT_K[u][x] */
static uint16_t Y10[256], C10[256];
static pthread_once_t once = PTHREAD_ONCE_INIT;

static void init_tables(void) {
  for (int u = 0; u < 8; u++) for (int x = 0; x < 8; x++) DCT_KT[x][u] = DCT_K[u][x];
  for (int i = 0; i < 256; i++) {
    /* full-range 8-bit -> limited-range 10-bit, in 16.16 fixed point: Y 0..255 -> 64..940, C (128 centre) -> 64..958 */
    Y10[i] = (uint16_t) ((i * 225135 + 64 * 65536 + 32768) >> 16);
    int c = ((i - 128) * 230277 + 512 * 65536 + 32768) >> 16;
    C10[i] = (uint16_t) (c < 64 ? 64 : c > 960 ? 960 : c);
  }
}

void prores_matrix(int profile, uint8_t out[64]) {
  if (profile < 0 || profile > 3) profile = 3;
  for (int r = 0; r < 8; r++)
    for (int c = 0; c < 8; c++) {
      int v = 4 + (MATRIX_SLOPE[profile] * POW15[r + c] + 500000) / 1000000;
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

/* The same codeword as put_vlc, returned as (value, length) for merging; only valid when *len <= 32. */
static inline uint32_t vlc_code(unsigned cb, unsigned val, int *len) {
  unsigned switch_bits = (cb & 3) + 1, rice = cb >> 5, expo = (cb >> 2) & 7;
  unsigned switch_val = switch_bits << rice;
  if (val >= switch_val) {
    val -= switch_val - (1u << expo);
    int e = 31 - __builtin_clz(val);
    *len = (e - (int) expo + (int) switch_bits) + e + 1;
    return val;
  }
  *len = (int) ((val >> rice) + 1 + rice);
  return (1u << rice) | (val & ((1u << rice) - 1));
}

static inline unsigned make_code(int x) { return (unsigned) ((x * 2) ^ (x >> 31)); }

/* Rounded division toward the nearest integer (both signs). */
static inline int div_round(int a, int q) { return a >= 0 ? (a + q / 2) / q : -((-a + q / 2) / q); }

/* ------------------------------------------------------------------------------------------------ DCT */

/*
 * Forward DCT of an 8x8 block of 10-bit samples, scaled as ProRes expects (mid-grey DC = 0x4000):
 * out[v][u] = C(u) C(v) sum f(x,y) cos(..u..) cos(..v..). Separable, 13-bit integer basis K:
 *   pass 1 (rows):    t[y][u] = round(sum_x K[u][x] f[y][x] / 2^11)      (= 4 x the true row transform, fits int16)
 *   pass 2 (columns): c[v][u] = round(sum_y K[v][y] t[y][u] / 2^15)      (saturated to int16)
 * K is exactly symmetric (even u) / antisymmetric (odd u), so each sum is computed as a butterfly over
 * f[x] +- f[7-x]: half the multiplies, exactly the same integer result. Scalar and NEON are bit-identical.
 */
#if PRORES_NEON
/* Transposes eight int16x8 rows in place. */
static inline void transpose8(int16x8_t r[8]) {
  int16x8x2_t a0 = vtrnq_s16(r[0], r[1]), a1 = vtrnq_s16(r[2], r[3]), a2 = vtrnq_s16(r[4], r[5]), a3 = vtrnq_s16(r[6], r[7]);
  int32x4x2_t b0 = vtrnq_s32(vreinterpretq_s32_s16(a0.val[0]), vreinterpretq_s32_s16(a1.val[0]));
  int32x4x2_t b1 = vtrnq_s32(vreinterpretq_s32_s16(a0.val[1]), vreinterpretq_s32_s16(a1.val[1]));
  int32x4x2_t b2 = vtrnq_s32(vreinterpretq_s32_s16(a2.val[0]), vreinterpretq_s32_s16(a3.val[0]));
  int32x4x2_t b3 = vtrnq_s32(vreinterpretq_s32_s16(a2.val[1]), vreinterpretq_s32_s16(a3.val[1]));
#define LO(x) vget_low_s32(x)
#define HI(x) vget_high_s32(x)
  r[0] = vreinterpretq_s16_s32(vcombine_s32(LO(b0.val[0]), LO(b2.val[0])));
  r[1] = vreinterpretq_s16_s32(vcombine_s32(LO(b1.val[0]), LO(b3.val[0])));
  r[2] = vreinterpretq_s16_s32(vcombine_s32(LO(b0.val[1]), LO(b2.val[1])));
  r[3] = vreinterpretq_s16_s32(vcombine_s32(LO(b1.val[1]), LO(b3.val[1])));
  r[4] = vreinterpretq_s16_s32(vcombine_s32(HI(b0.val[0]), HI(b2.val[0])));
  r[5] = vreinterpretq_s16_s32(vcombine_s32(HI(b1.val[0]), HI(b3.val[0])));
  r[6] = vreinterpretq_s16_s32(vcombine_s32(HI(b0.val[1]), HI(b2.val[1])));
  r[7] = vreinterpretq_s16_s32(vcombine_s32(HI(b1.val[1]), HI(b3.val[1])));
#undef LO
#undef HI
}

static void fdct8x8(const uint16_t *src, int stride, int16_t *out) {
  int16x8_t v[8];
  for (int y = 0; y < 8; y++) v[y] = vreinterpretq_s16_u16(vld1q_u16(src + y * stride));
  transpose8(v); /* v[x] = column x, lanes over y */
  /* pass 1: butterflies across columns, 8 rows at once (sums of two 10-bit samples fit int16) */
  int16x8_t sum[4], dif[4], t[8];
  for (int x = 0; x < 4; x++) { sum[x] = vaddq_s16(v[x], v[7 - x]); dif[x] = vsubq_s16(v[x], v[7 - x]); }
  for (int u = 0; u < 8; u++) {
    const int16x8_t *in = (u & 1) ? dif : sum;
    int32x4_t lo = vmull_n_s16(vget_low_s16(in[0]), DCT_K[u][0]), hi = vmull_n_s16(vget_high_s16(in[0]), DCT_K[u][0]);
    for (int x = 1; x < 4; x++) { lo = vmlal_n_s16(lo, vget_low_s16(in[x]), DCT_K[u][x]); hi = vmlal_n_s16(hi, vget_high_s16(in[x]), DCT_K[u][x]); }
    t[u] = vcombine_s16(vrshrn_n_s32(lo, 11), vrshrn_n_s32(hi, 11)); /* t[u], lanes over y */
  }
  transpose8(t); /* t[y], lanes over u */
  /* pass 2: butterflies across rows, in 32 bits (sums of two row coefficients can exceed int16) */
  int32x4_t sl[4], sh[4], dl[4], dh[4];
  for (int y = 0; y < 4; y++) {
    sl[y] = vaddl_s16(vget_low_s16(t[y]), vget_low_s16(t[7 - y])); sh[y] = vaddl_s16(vget_high_s16(t[y]), vget_high_s16(t[7 - y]));
    dl[y] = vsubl_s16(vget_low_s16(t[y]), vget_low_s16(t[7 - y])); dh[y] = vsubl_s16(vget_high_s16(t[y]), vget_high_s16(t[7 - y]));
  }
  for (int w = 0; w < 8; w++) {
    const int32x4_t *il = (w & 1) ? dl : sl, *ih = (w & 1) ? dh : sh;
    int32x4_t lo = vmulq_n_s32(il[0], DCT_K[w][0]), hi = vmulq_n_s32(ih[0], DCT_K[w][0]);
    for (int y = 1; y < 4; y++) { lo = vmlaq_n_s32(lo, il[y], DCT_K[w][y]); hi = vmlaq_n_s32(hi, ih[y], DCT_K[w][y]); }
    vst1q_s16(out + w * 8, vcombine_s16(vqrshrn_n_s32(lo, 15), vqrshrn_n_s32(hi, 15)));
  }
}
#else
static void fdct8x8(const uint16_t *src, int stride, int16_t *out) {
  int32_t t[8][8];
  for (int y = 0; y < 8; y++) {
    const uint16_t *row = src + y * stride;
    int32_t sum[4], dif[4];
    for (int x = 0; x < 4; x++) { sum[x] = (int32_t) row[x] + row[7 - x]; dif[x] = (int32_t) row[x] - row[7 - x]; }
    for (int u = 0; u < 8; u++) {
      const int32_t *in = (u & 1) ? dif : sum;
      int32_t s = DCT_K[u][0] * in[0] + DCT_K[u][1] * in[1] + DCT_K[u][2] * in[2] + DCT_K[u][3] * in[3];
      t[y][u] = (int16_t) ((s + 1024) >> 11);
    }
  }
  for (int u = 0; u < 8; u++) {
    int32_t sum[4], dif[4];
    for (int y = 0; y < 4; y++) { sum[y] = t[y][u] + t[7 - y][u]; dif[y] = t[y][u] - t[7 - y][u]; }
    for (int v = 0; v < 8; v++) {
      const int32_t *in = (v & 1) ? dif : sum;
      int32_t s = DCT_K[v][0] * in[0] + DCT_K[v][1] * in[1] + DCT_K[v][2] * in[2] + DCT_K[v][3] * in[3];
      int32_t c = (int32_t) (((int64_t) s + 16384) >> 15);
      out[v * 8 + u] = (int16_t) (c > 32767 ? 32767 : c < -32768 ? -32768 : c);
    }
  }
}
#endif

/*
 * Quantisation. AC coefficients of a slice component are kept in coding order: scan position i (1..63) major,
 * block k minor, so all values of one run share one quantiser and quantise as contiguous vectors. For scan
 * position i the quantiser is qq = matrix[SCAN[i]] * q, with a 0.4 rounding point (a small dead zone: fewer tiny
 * levels, cleaner flat areas): level = floor((|c| + bias) * ceil(2^16 / qq) / 2^16), sign restored.
 * Scalar and NEON compute identical values.
 */
typedef struct { uint32_t bias[64], recip[64]; } quant_table; /* indexed by scan position */

static void make_quant(const uint8_t *matrix, int q, quant_table *t) {
  for (int i = 0; i < 64; i++) {
    uint32_t qq = (uint32_t) matrix[SCAN[i]] * (uint32_t) q;
    t->bias[i] = (qq * 2) / 5;
    t->recip[i] = (65536 + qq - 1) / qq;
  }
}

/* n (the number of blocks: 4, 8, 16 or 32) is always a multiple of 4; NEON handles 8 at a time plus a tail of 4. */
static void quantise(const int16_t *co, int n, const quant_table *t, int16_t *lev) {
  for (int i = 1; i < 64; i++) {
    const int16_t *c = co + (i - 1) * n;
    int16_t *l = lev + (i - 1) * n;
    uint32_t bias = t->bias[i], recip = t->recip[i];
    int k = 0;
#if PRORES_NEON
    uint32x4_t vb = vdupq_n_u32(bias), vr = vdupq_n_u32(recip);
    for (; k + 8 <= n; k += 8) {
      int16x8_t x = vld1q_s16(c + k);
      uint16x8_t a = vreinterpretq_u16_s16(vabsq_s16(x));
      uint32x4_t lo = vshrq_n_u32(vmulq_u32(vaddq_u32(vmovl_u16(vget_low_u16(a)), vb), vr), 16);
      uint32x4_t hi = vshrq_n_u32(vmulq_u32(vaddq_u32(vmovl_u16(vget_high_u16(a)), vb), vr), 16);
      int16x8_t m = vreinterpretq_s16_u16(vcombine_u16(vmovn_u32(lo), vmovn_u32(hi)));
      vst1q_s16(l + k, vbslq_s16(vcltq_s16(x, vdupq_n_s16(0)), vnegq_s16(m), m));
    }
#endif
    for (; k < n; k++) {
      int x = c[k];
      uint32_t a = (uint32_t) (x < 0 ? -x : x);
      int16_t m = (int16_t) (uint16_t) (((a + bias) * recip) >> 16);
      l[k] = x < 0 ? (int16_t) -m : m;
    }
  }
}

/* One byte per level: 0xFF where the level is non-zero. Used to jump straight to the next non-zero level. */
static inline uint64_t nonzero_bytes(const int16_t *l) {
#if PRORES_NEON
  uint8x8_t nz = vmovn_u16(vtstq_s16(vld1q_s16(l), vld1q_s16(l)));
  return vget_lane_u64(vreinterpret_u64_u8(nz), 0);
#else
  uint64_t m = 0;
  for (int i = 0; i < 8; i++) if (l[i]) m |= 0xFFull << (8 * i);
  return m;
#endif
}

/* ------------------------------------------------------------------------------------------------ slices */

#define MAX_SLICE_MBS 8

typedef struct {
  int16_t luma[MAX_SLICE_MBS * 4][64];
  int16_t cb[MAX_SLICE_MBS * 2][64];
  int16_t cr[MAX_SLICE_MBS * 2][64];
  int16_t co[3][63 * MAX_SLICE_MBS * 4];   /* AC coefficients in coding order, per component */
  int16_t lev[63 * MAX_SLICE_MBS * 4 + 8];  /* quantised levels in coding order (+8: whole-vector reads) */
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
    int c = 0;
#if PRORES_NEON
    /* interior: the LUT's own fixed-point formula, 16 pixels at a time (bit-identical to Y10[]) */
    const uint32x4_t ky = vdupq_n_u32(225135), oy = vdupq_n_u32(64 * 65536 + 32768);
    for (; c + 16 <= width && x0 + c + 16 <= f->width; c += 16) {
      uint8x16_t px = vld1q_u8(src + x0 + c);
      uint16x8_t lo = vmovl_u8(vget_low_u8(px)), hi = vmovl_u8(vget_high_u8(px));
      uint32x4_t a0 = vshrq_n_u32(vmlaq_u32(oy, vmovl_u16(vget_low_u16(lo)), ky), 16), a1 = vshrq_n_u32(vmlaq_u32(oy, vmovl_u16(vget_high_u16(lo)), ky), 16);
      uint32x4_t a2 = vshrq_n_u32(vmlaq_u32(oy, vmovl_u16(vget_low_u16(hi)), ky), 16), a3 = vshrq_n_u32(vmlaq_u32(oy, vmovl_u16(vget_high_u16(hi)), ky), 16);
      vst1q_u16(&w->ypix[r][c], vcombine_u16(vmovn_u32(a0), vmovn_u32(a1)));
      vst1q_u16(&w->ypix[r][c + 8], vcombine_u16(vmovn_u32(a2), vmovn_u32(a3)));
    }
#endif
    for (; c < width; c++) { int x = x0 + c; if (x >= f->width) x = f->width - 1; w->ypix[r][c] = Y10[src[x]]; }
    /* 4:2:0 chroma sits between luma rows: 4:2:2 row y = 3/4 of the nearest 4:2:0 row + 1/4 of the next nearest. */
    int j = y >> 1, k = (y & 1) ? j + 1 : j - 1;
    if (k < 0) k = 0;
    if (k >= ch) k = ch - 1;
    if (j >= ch) j = ch - 1;
    const uint8_t *u0 = f->u + (size_t) j * f->c_stride, *u1 = f->u + (size_t) k * f->c_stride;
    const uint8_t *v0 = f->v + (size_t) j * f->c_stride, *v1 = f->v + (size_t) k * f->c_stride;
    c = 0;
#if PRORES_NEON
    /* interior chroma: C10[] formula (16.16 fixed point, clamped) and the 3:1 row blend, 8 samples at a time */
    {
      const int32x4_t kc = vdupq_n_s32(230277), oc = vdupq_n_s32(512 * 65536 + 32768), c128 = vdupq_n_s32(128);
      const uint16x8_t cmin = vdupq_n_u16(64), cmax = vdupq_n_u16(960);
#define C10V(p) ({ uint16x8_t w16 = vmovl_u8(vld1_u8(p)); \
        int32x4_t l = vsubq_s32(vreinterpretq_s32_u32(vmovl_u16(vget_low_u16(w16))), c128); \
        int32x4_t h = vsubq_s32(vreinterpretq_s32_u32(vmovl_u16(vget_high_u16(w16))), c128); \
        l = vshrq_n_s32(vmlaq_s32(oc, l, kc), 16); h = vshrq_n_s32(vmlaq_s32(oc, h, kc), 16); \
        vminq_u16(vmaxq_u16(vcombine_u16(vqmovun_s32(l), vqmovun_s32(h)), cmin), cmax); })
      for (; c + 8 <= width / 2 && x0 / 2 + c + 8 <= cw; c += 8) {
        int x = x0 / 2 + c;
        uint16x8_t a = C10V(u0 + x), bb = C10V(u1 + x);
        vst1q_u16(&w->cbpix[r][c], vshrq_n_u16(vaddq_u16(vaddq_u16(vmulq_n_u16(a, 3), bb), vdupq_n_u16(2)), 2));
        a = C10V(v0 + x); bb = C10V(v1 + x);
        vst1q_u16(&w->crpix[r][c], vshrq_n_u16(vaddq_u16(vaddq_u16(vmulq_n_u16(a, 3), bb), vdupq_n_u16(2)), 2));
      }
#undef C10V
    }
#endif
    for (; c < width / 2; c++) {
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
  /* AC coefficients into coding order (scan position major, block minor), once per slice */
  int nl = mbs * 4, nc = mbs * 2;
  for (int i = 1; i < 64; i++) {
    int pos = SCAN[i];
    int16_t *dl = w->co[0] + (i - 1) * nl, *du = w->co[1] + (i - 1) * nc, *dv = w->co[2] + (i - 1) * nc;
    for (int k = 0; k < nl; k++) dl[k] = w->luma[k][pos];
    for (int k = 0; k < nc; k++) { du[k] = w->cb[k][pos]; dv[k] = w->cr[k][pos]; }
  }
}

/* Entropy-codes one component of a slice from its quantised levels. Returns bytes written (byte aligned). */
static size_t code_plane(int16_t (*blocks)[64], const int16_t *lev, int nblocks, int dc_q, uint8_t *out, size_t cap, int *overflow) {
  bitw b; bw_init(&b, out, cap);
  /* DC: first value absolute, then sign-adaptive differences */
  int prev = div_round(blocks[0][0] - 0x4000, dc_q);
  put_vlc(&b, FIRST_DC_CB, make_code(prev));
  int cb = 5, sign = 0;
  for (int i = 1; i < nblocks; i++) {
    int dc = div_round(blocks[i][0] - 0x4000, dc_q);
    int delta = dc - prev, new_sign = delta >> 31;
    delta = (delta ^ sign) - sign;
    unsigned code = make_code(delta);
    put_vlc(&b, DC_CB[cb], code);
    cb = code < 6 ? (int) code : 6;
    sign = new_sign; prev = dc;
  }
  /* AC: levels already in coding order; runs of zeros are skipped 8 at a time */
  int prev_run = 4, prev_level = 2, last = -1, total = 63 * nblocks;
  for (int base = 0; base < total; base += 8) {
    uint64_t m = nonzero_bytes(lev + base);
    while (m) {
      int j = __builtin_ctzll(m) >> 3, idx = base + j;
      m &= ~(0xFFull << (8 * j));
      if (idx >= total) break;
      int level = lev[idx], a = level < 0 ? -level : level, run = idx - last - 1;
      last = idx;
      int lr, ll;
      uint32_t cr = vlc_code(RUN_CB[prev_run], (unsigned) run, &lr), cl = vlc_code(LEVEL_CB[prev_level], (unsigned) (a - 1), &ll);
      if (lr + ll < 32) put_bits(&b, lr + ll + 1, (cr << (ll + 1)) | (cl << 1) | (level < 0 ? 1u : 0u)); /* run, level, sign in one write */
      else {
        put_vlc(&b, RUN_CB[prev_run], (unsigned) run);
        put_vlc(&b, LEVEL_CB[prev_level], (unsigned) (a - 1));
        put_bits(&b, 1, level < 0 ? 1 : 0);
      }
      prev_run = run < 15 ? run : 15;
      prev_level = a < 9 ? a : 9;
    }
  }
  bw_flush(&b);
  if (b.overflow) *overflow = 1;
  return bw_bytes(&b);
}

/* Quantises and codes all three components of a slice at quantiser q into the trial buffers.
 * Returns 1 if the slice fits the budget. */
static int code_slice(slice_work *w, int mbs, const uint8_t *matrix, int q, size_t budget, size_t *sy, size_t *su, size_t *sv, int *of) {
  quant_table t;
  make_quant(matrix, q, &t);
  int dc_q = matrix[0] * q;
  *of = 0;
  int nl = mbs * 4, nc = mbs * 2;
  /* levels past the end of a component are read (as whole vectors) but never coded: keep them zero */
  memset(w->lev + 63 * nl, 0, 8 * sizeof(int16_t));
  quantise(w->co[0], nl, &t, w->lev);
  *sy = code_plane(w->luma, w->lev, nl, dc_q, w->trial[0], sizeof(w->trial[0]), of);
  memset(w->lev + 63 * nc, 0, 8 * sizeof(int16_t));
  quantise(w->co[1], nc, &t, w->lev);
  *su = code_plane(w->cb, w->lev, nc, dc_q, w->trial[1], sizeof(w->trial[1]), of);
  quantise(w->co[2], nc, &t, w->lev);
  *sv = code_plane(w->cr, w->lev, nc, dc_q, w->trial[2], sizeof(w->trial[2]), of);
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

int prores_slices(int width, int height) { return slices_in_row((width + 15) / 16) * ((height + 15) / 16); }

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
  uint8_t *hint = p->q_hint && p->q_hint_slices == mb_h * slices_in_row(mb_w) ? p->q_hint : NULL;
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
      /* Pictures change slowly: the same slice's quantiser in the previous frame is usually right first time. */
      if (hint && hint[slice] >= 1 && hint[slice] <= 128) q = hint[slice];
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
      if (hint) hint[slice] = (uint8_t) q;
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

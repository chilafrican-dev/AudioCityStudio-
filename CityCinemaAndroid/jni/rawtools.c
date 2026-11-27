/*
 * City Cinema RAW tools: lossless JPEG (ITU T.81 process 14, "LJ92") compression of Bayer frames for CinemaDNG,
 * cropping + quad-Bayer remosaic while copying out of the camera buffer, and a fast RGB monitor image.
 *
 * LJ92 layout follows the Adobe DNG SDK: each W x H tile of CFA samples is one lossless JPEG of (W/2) x H with two
 * interleaved components, so predictor 1 (left neighbour) always predicts from the same CFA colour two pixels away.
 * Each tile gets a Huffman table built from its own statistics. NEON computes the prediction differences; the
 * output is identical to the scalar path.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "rawtools.h"

#if (defined(__ARM_NEON) || defined(__ARM_NEON__)) && !defined(PRORES_NO_NEON)
#include <arm_neon.h>
#define RAW_NEON 1
#else
#define RAW_NEON 0
#endif

/* ------------------------------------------------------------------------------------------------ copy */

/* Quad Bayer (2x2 same-colour cells) to Bayer by pixel shuffle: inside each 4x4 tile, swap rows 1<->2 and columns
 * 1<->2. A quad pattern RRGG/RRGG/GGBB/GGBB becomes RGRG/GBGB/RGRG/GBGB with the binned CFA order. */
static inline int shuffle4(int i) { int k = i & 3; return (i & ~3) | (k == 1 ? 2 : k == 2 ? 1 : k); }

void raw_copy(const uint8_t *src, int row_stride, int x0, int y0, int w, int h, int quad, uint16_t *dst) {
  for (int r = 0; r < h; r++) {
    const uint16_t *s = (const uint16_t *) (src + (size_t) (y0 + (quad ? shuffle4(r) : r)) * row_stride) + x0;
    uint16_t *d = dst + (size_t) r * w;
    if (!quad) { memcpy(d, s, (size_t) w * 2); continue; }
    int c = 0;
    for (; c + 4 <= w; c += 4) { d[c] = s[c]; d[c + 1] = s[c + 2]; d[c + 2] = s[c + 1]; d[c + 3] = s[c + 3]; }
    for (; c < w; c++) d[c] = s[shuffle4(c)];
  }
}

/* ------------------------------------------------------------------------------------------------ preview */

static int cfa_color(int cfa, int row, int col) {
  static const int p[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
  return p[cfa & 3][(row & 1) * 2 + (col & 1)];
}

void raw_preview(const uint16_t *px, int w, int h, int cfa, const float black[4], int white, const float gains[3],
                 int out_w, int out_h, uint32_t *argb) {
  /* Display curve: BT.709 OETF on white-balanced linear values, in a 4096-entry table. */
  uint8_t curve[4096];
  for (int i = 0; i < 4096; i++) {
    double x = i / 4095.0, y = x < 0.018 ? 4.5 * x : 1.099 * __builtin_pow(x, 0.45) - 0.099;
    curve[i] = (uint8_t) (y * 255 + 0.5);
  }
  int bx = (w / out_w) & ~1, by = (h / out_h) & ~1;
  if (bx < 2) bx = 2;
  if (by < 2) by = 2;
  float range = (float) white - black[0];
  if (range < 1) range = 1;
  for (int oy = 0; oy < out_h; oy++)
    for (int ox = 0; ox < out_w; ox++) {
      /* average four 2x2 CFA cells spread over the block: cheap noise reduction */
      float acc[3] = {0, 0, 0}, cnt[3] = {0, 0, 0};
      for (int sy = 0; sy < 2; sy++)
        for (int sx = 0; sx < 2; sx++) {
          int y0 = oy * by + (sy * by / 2 & ~1), x0 = ox * bx + (sx * bx / 2 & ~1);
          if (y0 + 1 >= h || x0 + 1 >= w) continue;
          for (int r = 0; r < 2; r++)
            for (int c = 0; c < 2; c++) {
              int col = cfa_color(cfa, r, c);
              acc[col] += (float) px[(size_t) (y0 + r) * w + x0 + c] - black[r * 2 + c];
              cnt[col] += 1;
            }
        }
      uint32_t o = 0xff000000u;
      for (int k = 0; k < 3; k++) {
        float v = cnt[k] > 0 ? acc[k] / cnt[k] / range * gains[k] : 0;
        int i = (int) (v * 4095);
        i = i < 0 ? 0 : i > 4095 ? 4095 : i;
        o |= (uint32_t) curve[i] << (16 - 8 * k);
      }
      argb[(size_t) oy * out_w + ox] = o;
    }
}

/* ------------------------------------------------------------------------------------------------ LJ92 */

typedef struct { uint8_t *p, *end; uint64_t acc; int bits; int overflow; } jbits;

/* Writes the top bytes of the accumulator, stuffing a 0x00 after every 0xFF (T.81 F.1.2.3). */
static inline void jput(jbits *b, int n, uint32_t v) {
  if (n == 0) return;
  b->acc = (b->acc << n) | (v & ((n == 32) ? 0xffffffffu : ((1u << n) - 1)));
  b->bits += n;
  while (b->bits >= 8) {
    b->bits -= 8;
    uint8_t byte = (uint8_t) (b->acc >> b->bits);
    if (b->p + 2 > b->end) { b->overflow = 1; return; }
    *b->p++ = byte;
    if (byte == 0xFF) *b->p++ = 0x00;
  }
}

static void jflush(jbits *b) { if (b->bits & 7) jput(b, 8 - (b->bits & 7), 0xFF); } /* pad with 1-bits */

/* Builds JPEG Huffman code lengths (max 16) for symbols 0..16 from their frequencies (T.81 Annex K.2/K.3). */
static void build_huffman(const uint32_t freq_in[17], uint8_t bits_out[17], uint8_t huffval[17], int *nsym, uint8_t len_of[17], uint16_t code_of[17]) {
  long freq[18]; int codesize[18], others[18];
  for (int i = 0; i < 17; i++) freq[i] = freq_in[i];
  freq[17] = 1; /* reserved symbol: guarantees no code is all ones */
  for (int i = 0; i < 18; i++) { codesize[i] = 0; others[i] = -1; }
  for (;;) {
    int c1 = -1, c2 = -1;
    for (int i = 0; i < 18; i++) if (freq[i] && (c1 < 0 || freq[i] <= freq[c1])) c1 = i;
    for (int i = 0; i < 18; i++) if (freq[i] && i != c1 && (c2 < 0 || freq[i] <= freq[c2])) c2 = i;
    if (c2 < 0) break;
    freq[c1] += freq[c2]; freq[c2] = 0;
    codesize[c1]++;
    while (others[c1] >= 0) { c1 = others[c1]; codesize[c1]++; }
    others[c1] = c2;
    codesize[c2]++;
    while (others[c2] >= 0) { c2 = others[c2]; codesize[c2]++; }
  }
  int count[40] = {0};
  for (int i = 0; i < 18; i++) if (codesize[i]) count[codesize[i]]++;
  for (int i = 39; i > 16; i--) /* limit code lengths to 16 bits */
    while (count[i] > 0) {
      int j = i - 2;
      while (count[j] == 0) j--;
      count[i] -= 2; count[i - 1]++; count[j + 1] += 2; count[j]--;
    }
  int top = 16; while (count[top] == 0) top--;
  count[top]--; /* drop the reserved symbol */
  for (int i = 1; i <= 16; i++) bits_out[i] = (uint8_t) count[i];
  /* symbols ordered by code size, then value */
  int n = 0;
  for (int len = 1; len <= 32; len++) for (int i = 0; i < 17; i++) if (codesize[i] == len) huffval[n++] = (uint8_t) i;
  *nsym = n;
  /* canonical codes, assigning the (possibly adjusted) lengths in that order (T.81 Annex C) */
  memset(len_of, 0, 17);
  int k = 0; uint16_t code = 0;
  for (int len = 1; len <= 16; len++) {
    for (int i = 0; i < bits_out[len]; i++, k++) { len_of[huffval[k]] = (uint8_t) len; code_of[huffval[k]] = code++; }
    code <<= 1;
  }
}

static inline int ssss_of(int d) { if (d == 0) return 0; int a = d < 0 ? -d : d; return 32 - __builtin_clz((unsigned) a); }

/* Prediction differences of one tile row: predictor 1 (two samples left), Rb for the first sample of each
 * component, 2^(P-1) on the very first row. Differences are modulo 2^16, as T.81 defines them. */
static void row_diffs(const uint16_t *row, const uint16_t *above, int n, int first, int16_t *diff) {
  if (above) { diff[0] = (int16_t) (row[0] - above[0]); diff[1] = (int16_t) (row[1] - above[1]); }
  else { diff[0] = (int16_t) (row[0] - first); diff[1] = (int16_t) (row[1] - first); }
  int x = 2;
#if RAW_NEON
  for (; x + 8 <= n; x += 8)
    vst1q_s16(diff + x, vreinterpretq_s16_u16(vsubq_u16(vld1q_u16(row + x), vld1q_u16(row + x - 2))));
#endif
  for (; x < n; x++) diff[x] = (int16_t) (row[x] - row[x - 2]);
}

long lj92_tiles(const uint16_t *px, int w, int h, int bits, int tw, int th, uint8_t *out, size_t cap, uint32_t *tile_sizes) {
  if (w < 2 || h < 1 || tw < 2 || (tw & 1) || th < 1 || bits < 2 || bits > 16) return -1;
  int ntx = (w + tw - 1) / tw, nty = (h + th - 1) / th;
  uint16_t *tile = malloc((size_t) tw * th * sizeof(uint16_t));
  int16_t *diff = malloc((size_t) tw * th * sizeof(int16_t));
  if (!tile || !diff) { free(tile); free(diff); return -3; }
  uint32_t maxv = (1u << bits) - 1;
  uint8_t *o = out, *end = out + cap;
  for (int ty = 0; ty < nty; ty++)
    for (int tx = 0; tx < ntx; tx++) {
      /* gather the tile, replicating edge pixels of the same CFA colour beyond the image */
      for (int r = 0; r < th; r++) {
        int y = ty * th + r;
        if (y >= h) { y = h - 2 + ((y - h) & 1); if (y < 0) y = h - 1; } /* last row of the same CFA parity */
        const uint16_t *src = px + (size_t) y * w;
        for (int c = 0; c < tw; c++) {
          int x = tx * tw + c;
          if (x >= w) { x = w - 2 + ((x - w) & 1); if (x < 0) x = w - 1; }
          uint16_t v = src[x];
          tile[r * tw + c] = v > maxv ? (uint16_t) maxv : v;
        }
      }
      uint32_t freq[17] = {0};
      for (int r = 0; r < th; r++) {
        row_diffs(tile + r * tw, r ? tile + (r - 1) * tw : NULL, tw, 1 << (bits - 1), diff + r * tw);
        for (int c = 0; c < tw; c++) freq[ssss_of(diff[r * tw + c])]++;
      }
      uint8_t bl[17], hv[17], len_of[17]; uint16_t code_of[17]; int nsym;
      build_huffman(freq, bl, hv, &nsym, len_of, code_of);
      size_t need = 2 + (4 + 1 + 16 + nsym) + 2 + 2 + 14 + 2 + 10 + 2;
      if ((size_t) (end - o) < need + 16) { free(tile); free(diff); return -2; }
      uint8_t *start = o;
      /* SOI, DHT, SOF3, SOS */
      *o++ = 0xFF; *o++ = 0xD8;
      *o++ = 0xFF; *o++ = 0xC4; int lh = 2 + 1 + 16 + nsym; *o++ = (uint8_t) (lh >> 8); *o++ = (uint8_t) lh; *o++ = 0x00;
      for (int i = 1; i <= 16; i++) *o++ = bl[i];
      for (int i = 0; i < nsym; i++) *o++ = hv[i];
      *o++ = 0xFF; *o++ = 0xC3; *o++ = 0; *o++ = 14; *o++ = (uint8_t) bits;
      *o++ = (uint8_t) (th >> 8); *o++ = (uint8_t) th; *o++ = (uint8_t) ((tw / 2) >> 8); *o++ = (uint8_t) (tw / 2);
      *o++ = 2; *o++ = 1; *o++ = 0x11; *o++ = 0; *o++ = 2; *o++ = 0x11; *o++ = 0;
      *o++ = 0xFF; *o++ = 0xDA; *o++ = 0; *o++ = 10; *o++ = 2; *o++ = 1; *o++ = 0x00; *o++ = 2; *o++ = 0x00;
      *o++ = 1; /* predictor 1 */ *o++ = 0; *o++ = 0; /* point transform 0 */
      jbits b = { o, end - 2, 0, 0, 0 };
      for (int i = 0; i < tw * th; i++) {
        int d = diff[i], s = ssss_of(d);
        if (d == -32768) s = 16;
        uint32_t extra = s == 16 ? 0 : (uint32_t) (d < 0 ? d - 1 : d) & ((1u << s) - 1);
        if (len_of[s] + s <= 32 && s < 16) jput(&b, len_of[s] + s, ((uint32_t) code_of[s] << s) | extra);
        else { jput(&b, len_of[s], code_of[s]); if (s < 16) jput(&b, s, extra); }
        if (b.overflow) { free(tile); free(diff); return -2; }
      }
      jflush(&b);
      o = b.p;
      if (end - o < 2) { free(tile); free(diff); return -2; }
      *o++ = 0xFF; *o++ = 0xD9;
      tile_sizes[ty * ntx + tx] = (uint32_t) (o - start);
    }
  free(tile); free(diff);
  return (long) (o - out);
}

size_t lj92_max_bytes(int w, int h, int tw, int th) {
  size_t ntiles = (size_t) ((w + tw - 1) / tw) * ((h + th - 1) / th);
  /* worst case: 16-bit code + 16 extra bits per sample, plus stuffing, plus headers */
  return ntiles * ((size_t) tw * th * 5 + 256);
}

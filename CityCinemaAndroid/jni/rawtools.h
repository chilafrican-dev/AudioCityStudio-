/* City Cinema RAW tools: see rawtools.c. */
#ifndef CITYCINEMA_RAWTOOLS_H
#define CITYCINEMA_RAWTOOLS_H
#include <stddef.h>
#include <stdint.h>

/* Copies a w x h crop at (x0, y0) of 16-bit samples out of a camera buffer; quad != 0 remosaics quad Bayer to
 * Bayer by pixel shuffle (x0, y0 must then be multiples of 4). dst is compact (stride w). */
void raw_copy(const uint8_t *src, int row_stride, int x0, int y0, int w, int h, int quad, uint16_t *dst);

/* Downscaled, white-balanced BT.709 RGB monitor image (ARGB) of a Bayer frame. */
void raw_preview(const uint16_t *px, int w, int h, int cfa, const float black[4], int white, const float gains[3],
                 int out_w, int out_h, uint32_t *argb);

/* Lossless JPEG (LJ92) compression of a Bayer frame into tw x th tiles (tw even), DNG style. Returns the total
 * bytes written to out (tiles back to back), with each tile's size in tile_sizes, or a negative error. */
long lj92_tiles(const uint16_t *px, int w, int h, int bits, int tw, int th, uint8_t *out, size_t cap, uint32_t *tile_sizes);

/* An output size that always suffices for lj92_tiles. */
size_t lj92_max_bytes(int w, int h, int tw, int th);
#endif

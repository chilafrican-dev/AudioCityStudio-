"""Checks CinemaTests' sample DNGs with LibRaw (pip install rawpy numpy): every sample, levels, CFA, render."""
import sys
import numpy as np
import rawpy

def expected(h, w, white):
    y, x = np.mgrid[0:h, 0:w]
    return (x * 7 + y * 3 + ((y & 1) * 2 + (x & 1)) * 50) % (white + 1)

for bits in (12, 16):
    path = f"{sys.argv[1]}/sample{bits}.dng"
    with rawpy.imread(path) as raw:
        img = raw.raw_image_visible
        assert img.shape == (48, 64), img.shape
        assert np.array_equal(img, expected(48, 64, 4095)), f"{bits}-bit samples differ"
        assert raw.white_level == 4095, raw.white_level
        assert list(raw.black_level_per_channel) == [256] * 4, raw.black_level_per_channel
        assert raw.raw_pattern.tolist() == [[1, 0], [2, 3]] or raw.raw_pattern.tolist() == [[1, 0], [2, 1]], raw.raw_pattern
        assert raw.color_desc.startswith(b"RGB"), raw.color_desc
        rgb = raw.postprocess(use_camera_wb=True)
        assert rgb.shape == (48, 64, 3)
        print(f"{bits}-bit DNG ok: samples exact, pattern {raw.raw_pattern.tolist()}, wb {np.round(raw.camera_whitebalance, 3).tolist()}")

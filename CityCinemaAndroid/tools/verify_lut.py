"""Applies the City Log to Rec.709 .cube with FFmpeg's lut3d filter and checks the mapping.
Usage: python verify_lut.py <ffmpeg> <cube>"""
import subprocess, sys
import numpy as np

ff, cube = sys.argv[1], sys.argv[2]
codes = np.array([95 / 1023, 0.42, 0.6, 0.76])  # log black, 18% grey, +2.3 stops, ISP clip with 2 stops of headroom
img = np.repeat(np.round(codes * 65535).astype("<u2"), 3).reshape(1, 4, 3)
out = subprocess.run([ff, "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb48le", "-s", "4x1", "-i", "-", "-vf", f"lut3d={cube}:interp=trilinear",
                      "-f", "rawvideo", "-pix_fmt", "rgb48le", "-"], input=img.tobytes(), capture_output=True, check=True).stdout
v = np.frombuffer(out, "<u2").reshape(4, 3)[:, 0] / 65535
assert v[0] < 0.01 and abs(v[1] - 0.41) < 0.04 and v[1] < v[2] < v[3] <= 1, v
print("City Log LUT via FFmpeg lut3d: black %.3f, grey %.3f, +2.3 stops %.3f, clip %.3f" % tuple(v))

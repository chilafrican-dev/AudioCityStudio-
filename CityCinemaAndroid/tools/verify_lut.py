"""Applies each log-to-Rec.709 .cube with FFmpeg's lut3d filter and checks it: black to black, the log's 18% grey
to near Rec.709 grey, highlights rising and staying in range.
Usage: python verify_lut.py <ffmpeg> <dir with the .cube files>"""
import glob, os, subprocess, sys
import numpy as np

ff, folder = sys.argv[1], sys.argv[2]
grey = {"CityLog": 0.42, "RED_Log3G10": 1 / 3, "Sony_S-Log3": 420 / 1023, "BMD_Film_Gen_5": 0.383562}
black = {"CityLog": 95 / 1023, "RED_Log3G10": 0.091551, "Sony_S-Log3": 95 / 1023, "BMD_Film_Gen_5": 0.092466}
cubes = sorted(glob.glob(f"{folder}/*_to_Rec709.cube"))
assert len(cubes) == 4, cubes
for cube in cubes:
    name = os.path.basename(cube).replace("_to_Rec709.cube", "")
    codes = np.array([black[name], grey[name], grey[name] + 0.15, grey[name] + 0.3])
    img = np.repeat(np.round(codes * 65535).astype("<u2"), 3).reshape(1, 4, 3)
    out = subprocess.run([ff, "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb48le", "-s", "4x1", "-i", "-", "-vf", f"lut3d={cube}:interp=tetrahedral",
                          "-f", "rawvideo", "-pix_fmt", "rgb48le", "-"], input=img.tobytes(), capture_output=True, check=True).stdout
    v = np.frombuffer(out, "<u2").reshape(4, 3)[:, 0] / 65535
    assert v[0] < 0.02 and abs(v[1] - 0.41) < 0.04 and v[1] < v[2] < v[3] <= 1, (name, v)
    print(f"{name} LUT via FFmpeg lut3d: black {v[0]:.3f}, grey {v[1]:.3f}, highlights {v[2]:.3f} / {v[3]:.3f}")

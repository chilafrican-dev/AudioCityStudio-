"""Decodes ProResHostTest's .mov files with FFmpeg and checks them: codec/profile, 4:2:2 10-bit, frame rate,
timecode, 24-bit audio, frame count, and PSNR against the source (mapped exactly as the encoder maps it).
Usage: python verify_prores.py <dir> <ffmpeg> <width> <height> <frames>"""
import json, subprocess, sys
import numpy as np

d, ffmpeg, W, H, N = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
N += 1 if N > 5 else 0  # frame 5 is stored twice (a filled slot)
cw, ch = (W + 1) // 2, (H + 1) // 2
ffprobe_like = [ffmpeg, "-hide_banner"]

def reference():
    raw = np.fromfile(f"{d}/test_Proxy.yuv", dtype=np.uint8).reshape(N, -1)
    y = raw[:, :W * H].reshape(N, H, W).astype(np.float64)
    u = raw[:, W * H:W * H + cw * ch].reshape(N, ch, cw).astype(np.float64)
    v = raw[:, W * H + cw * ch:].reshape(N, ch, cw).astype(np.float64)
    y10 = np.rint(64 + y * 876 / 255)
    c10 = lambda c: np.clip(np.rint(512 + (c - 128) * 896 / 255), 64, 960)
    def up(c):  # 4:2:0 -> 4:2:2, exactly as the encoder: 3/4 nearest row + 1/4 next nearest
        c = c10(c)
        rows = np.arange(H)
        j = np.minimum(rows // 2, ch - 1)
        k = np.where(rows % 2 == 1, rows // 2 + 1, rows // 2 - 1)
        k = np.clip(k, 0, ch - 1)
        return np.floor((3 * c[:, j, :] + c[:, k, :] + 2) / 4)
    return y10, up(u), up(v)

ref = reference()
for name, min_psnr in (("Proxy", 33), ("LT", 37), ("422", 41), ("HQ", 47)):  # a deliberately hard, noisy test picture
    path = f"{d}/test_{name}.mov"
    info = subprocess.run([ffmpeg, "-hide_banner", "-i", path], capture_output=True, text=True).stderr
    assert "prores" in info and "yuv422p10" in info, info
    assert "23.98 fps" in info or "23.976" in info, info
    assert "timecode        : 01:00:02:05" in info, info
    assert "pcm_s24le" in info and "48000 Hz" in info and "stereo" in info, info
    raw = subprocess.run([ffmpeg, "-v", "error", "-i", path, "-f", "rawvideo", "-pix_fmt", "yuv422p10le", "-"], capture_output=True, check=True).stdout
    frames = np.frombuffer(raw, dtype="<u2")
    per = W * H + 2 * (W // 2) * H
    assert frames.size == per * N, (frames.size / per, N)
    frames = frames.reshape(N, per)
    y = frames[:, :W * H].reshape(N, H, W)
    u = frames[:, W * H:W * H + (W // 2) * H].reshape(N, H, W // 2)
    v = frames[:, W * H + (W // 2) * H:].reshape(N, H, W // 2)
    def psnr(a, b):
        mse = np.mean((a.astype(np.float64) - b) ** 2)
        return 99 if mse == 0 else 10 * np.log10(1023 ** 2 / mse)
    py, pu, pv = psnr(y, ref[0]), psnr(u, ref[1][:, :, :W // 2]), psnr(v, ref[2][:, :, :W // 2])
    audio = subprocess.run([ffmpeg, "-v", "error", "-i", path, "-map", "0:a", "-f", "s32le", "-"], capture_output=True, check=True).stdout
    a = np.frombuffer(audio, dtype="<i4").reshape(-1, 2) / 2 ** 31
    assert abs(a.shape[0] - N * 48000 / (24000 / 1001)) < 4 * N, a.shape
    assert abs(np.max(np.abs(a[:, 0])) - 0.25) < 0.01 and np.allclose(a[:, 0], -a[:, 1], atol=1e-6), "audio"
    print(f"ProRes {name}: decoded {N} frames, PSNR Y {py:.1f} dB, Cb {pu:.1f} dB, Cr {pv:.1f} dB; audio + timecode ok")
    assert min(py, pu, pv) >= min_psnr, f"{name} PSNR too low"

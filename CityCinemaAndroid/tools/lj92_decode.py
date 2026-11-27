"""Independent lossless JPEG (ITU T.81 process 14) decoder for City Cinema's DNG tiles, written from the spec.
Used by the tests as a second reference next to LibRaw. Usage: python lj92_decode.py file.dng  (prints samples check)"""
import struct, sys
import numpy as np

def decode_tile(buf):
    i, huff, bits, comps, height, width, pred = 2, None, 0, 0, 0, 0, 1
    assert buf[:2] == b"\xff\xd8"
    while True:
        assert buf[i] == 0xFF, hex(buf[i]); marker = buf[i + 1]; length = struct.unpack(">H", buf[i + 2:i + 4])[0]
        seg = buf[i + 4:i + 2 + length]
        if marker == 0xC4:
            counts = seg[1:17]; vals = seg[17:17 + sum(counts)]
            huff, code, k = {}, 0, 0
            for ln in range(1, 17):
                for _ in range(counts[ln - 1]): huff[(ln, code)] = vals[k]; code += 1; k += 1
                code <<= 1
        elif marker == 0xC3:
            bits, height, width, comps = seg[0], struct.unpack(">H", seg[1:3])[0], struct.unpack(">H", seg[3:5])[0], seg[5]
        elif marker == 0xDA:
            pred = seg[1 + 2 * seg[0]]; i = i + 2 + length; break
        i += 2 + length
    # entropy-coded data with byte stuffing removed
    data, j = bytearray(), i
    while True:
        b = buf[j]
        if b == 0xFF:
            if buf[j + 1] == 0x00: data.append(0xFF); j += 2; continue
            break
        data.append(b); j += 1
    bitpos = 0
    def read(n):
        nonlocal bitpos
        v = 0
        for _ in range(n):
            v = (v << 1) | ((data[bitpos >> 3] >> (7 - (bitpos & 7))) & 1); bitpos += 1
        return v
    out = np.zeros((height, width * comps), dtype=np.int64)
    assert pred == 1
    for y in range(height):
        for x in range(width * comps):
            ln, code = 0, 0
            while (ln, code) not in huff:
                code = (code << 1) | read(1); ln += 1
                assert ln <= 16, "bad code"
            s = huff[(ln, code)]
            if s == 16: d = 32768
            elif s == 0: d = 0
            else:
                d = read(s)
                if d < (1 << (s - 1)): d -= (1 << s) - 1
            if y == 0 and x < comps: p = 1 << (bits - 1)
            elif x < comps: p = out[y - 1, x]
            else: p = out[y, x - comps]
            out[y, x] = (p + d) & 0xFFFF
    return out, bits

def read_dng_tiles(path):
    f = open(path, "rb").read()
    ifd = struct.unpack("<I", f[4:8])[0]; n = struct.unpack("<H", f[ifd:ifd + 2])[0]; tags = {}
    for k in range(n):
        tag, typ, cnt, val = struct.unpack("<HHII", f[ifd + 2 + 12 * k:ifd + 14 + 12 * k])
        if typ == 4 and cnt > 1: val = list(struct.unpack(f"<{cnt}I", f[val:val + 4 * cnt]))
        tags[tag] = val
    w, h, tw, th = tags[256], tags[257], tags[322], tags[323]
    offs, counts = tags[324], tags[325]
    offs = offs if isinstance(offs, list) else [offs]; counts = counts if isinstance(counts, list) else [counts]
    img = np.zeros(((h + th - 1) // th * th, (w + tw - 1) // tw * tw), dtype=np.int64)
    ntx = (w + tw - 1) // tw
    for t, (o, c) in enumerate(zip(offs, counts)):
        tile, _ = decode_tile(f[o:o + c])
        ty, tx = divmod(t, ntx)
        img[ty * th:(ty + 1) * th, tx * tw:(tx + 1) * tw] = tile
    return img[:h, :w]

if __name__ == "__main__":
    for path in sys.argv[1:]:
        img = read_dng_tiles(path)
        src = np.fromfile(path + ".raw", dtype="<u2").reshape(img.shape)
        assert np.array_equal(img, src), f"{path}: {np.count_nonzero(img != src)} samples differ"
        print(f"{path.split('/')[-1]}: spec decoder reproduces every sample")

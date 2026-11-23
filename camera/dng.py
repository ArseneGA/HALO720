"""HALO 720 — écriture DNG en flux, peu gourmande en mémoire.

Le capteur livre du RAW 10 bits « CSI-2 packed » (4 pixels = 5 octets, le 5e octet porte les
2 bits de poids faible des 4 pixels). Le DNG veut du 10 bits empaqueté « poids fort d'abord ».
On convertit bande par bande (128 lignes) : < 1 Mo de mémoire de travail, ≈ 0,3 s pour 12 Mpx.
Les étiquettes reprennent celles de picamera2/pidng (matrice couleur, neutre, niveaux de noir).
"""

import json
import os
import struct
from datetime import datetime

import numpy as np

RGB_TO_XYZ = np.array([[0.4124564, 0.3575761, 0.1804375],
                       [0.2126729, 0.7151522, 0.0721750],
                       [0.0193339, 0.1191920, 0.9503041]])

CFA = {"RGGB": [0, 1, 1, 2], "GRBG": [1, 0, 2, 1], "GBRG": [1, 2, 0, 1], "BGGR": [2, 1, 1, 0]}


def repack_csi2p_to_dng(block):
    """block : (n, 5) uint8 CSI-2 -> (n, 5) uint8 DNG 10 bits MSB d'abord."""
    b0, b1, b2, b3, b4 = (block[:, i] for i in range(5))
    out = np.empty_like(block)
    out[:, 0] = b0
    out[:, 1] = ((b4 & 3) << 6) | (b1 >> 2)
    out[:, 2] = ((b1 & 3) << 6) | (((b4 >> 2) & 3) << 4) | (b2 >> 4)
    out[:, 3] = ((b2 & 15) << 4) | (((b4 >> 4) & 3) << 2) | (b3 >> 6)
    out[:, 4] = ((b3 & 63) << 2) | (b4 >> 6)
    return out


def _srat(x, den=10000):
    return (int(round(x * den)), den)


def _rat_exposure(s):
    s = max(1e-6, float(s))
    if s < 1:
        d = round(1 / s)
        if abs(1 / s - d) < 0.02 * d:
            return (1, d)
        return (round(s * 1_000_000), 1_000_000)
    return (round(s * 1000), 1000)


class _Ifd:
    TYPES = {"BYTE": (1, "B"), "ASCII": (2, "B"), "SHORT": (3, "H"), "LONG": (4, "I"),
             "RATIONAL": (5, "II"), "SRATIONAL": (10, "ii")}

    def __init__(self):
        self.entries = []

    def add(self, tag, typ, values):
        if typ == "ASCII":
            values = list(values.encode("ascii", "replace") + b"\0")
        elif not isinstance(values, (list, tuple)) or (typ in ("RATIONAL", "SRATIONAL") and
                                                         isinstance(values, tuple)):
            values = [values]
        self.entries.append((tag, typ, values))

    def pack(self, offset):
        """Renvoie les octets de l'IFD (entrées + données hors entrée), placé à `offset`."""
        self.entries.sort(key=lambda e: e[0])
        n = len(self.entries)
        data_off = offset + 2 + 12 * n + 4
        head, extra = b"", b""
        for tag, typ, values in self.entries:
            code, f = self.TYPES[typ]
            if typ in ("RATIONAL", "SRATIONAL"):
                raw = b"".join(struct.pack("<" + f, *v) for v in values)
            else:
                raw = struct.pack("<" + f * len(values), *values)
            count = len(values)
            if len(raw) <= 4:
                head += struct.pack("<HHI", tag, code, count) + raw.ljust(4, b"\0")
            else:
                head += struct.pack("<HHII", tag, code, count, data_off + len(extra))
                extra += raw + (b"\0" if len(raw) % 2 else b"")
        return struct.pack("<H", n) + head + struct.pack("<I", 0) + extra


def write_dng(path, rawbuf, raw_config, md, info, strip_rows=128):
    """Écrit le DNG `path` à partir du tampon RAW CSI-2 10 bits (copie ou tampon caméra)."""
    w, h = raw_config["size"]
    stride = raw_config["stride"]
    fmt = str(raw_config["format"])
    order = next((k for k in CFA if k in fmt), "BGGR")
    bpp = 10
    row_bytes = w * bpp // 8
    black = [int(v) >> (16 - bpp) for v in md.get("SensorBlackLevels", (4096,) * 4)]
    gr, gb = md.get("ColourGains", (1.0, 1.0))
    ccm = np.array(md.get("ColourCorrectionMatrix", (1, 0, 0, 0, 1, 0, 0, 0, 1)), dtype=float).reshape(3, 3)
    cm1 = np.linalg.inv(RGB_TO_XYZ.dot(ccm).dot(np.diag([gr, 1.0, gb])))
    exposure_s = md.get("ExposureTime", 10000) / 1e6
    iso = int(md.get("AnalogueGain", 1.0) * md.get("DigitalGain", 1.0) * 100)
    orient = {0: 1, 90: 6, 180: 3, 270: 8}.get(info.get("orientation", 0), 1)
    dt = datetime.fromtimestamp(info.get("time", 0)).strftime("%Y:%m:%d %H:%M:%S")
    desc = json.dumps({"look": info.get("look"), "zoom": info.get("zoom"), "crop": info.get("crop"),
                       "series": info.get("series"), "note": "RAW capteur entier ; crop = zone du JPEG"})

    ifd = _Ifd()
    ifd.add(254, "LONG", 0)
    ifd.add(256, "LONG", w)
    ifd.add(257, "LONG", h)
    ifd.add(258, "SHORT", bpp)
    ifd.add(259, "SHORT", 1)
    ifd.add(262, "SHORT", 32803)
    ifd.add(270, "ASCII", desc)
    ifd.add(271, "ASCII", "HALO 720")
    ifd.add(272, "ASCII", "Camera Module 3 NoIR 720nm")
    ifd.add(273, "LONG", 0)                      # corrigé plus bas
    ifd.add(274, "SHORT", orient)
    ifd.add(277, "SHORT", 1)
    ifd.add(278, "LONG", h)
    ifd.add(279, "LONG", row_bytes * h)
    ifd.add(284, "SHORT", 1)
    ifd.add(305, "ASCII", "HALO 720")
    ifd.add(306, "ASCII", dt)
    ifd.add(33421, "SHORT", [2, 2])
    ifd.add(33422, "BYTE", CFA[order])
    ifd.add(33434, "RATIONAL", [_rat_exposure(exposure_s)])
    ifd.add(34855, "SHORT", min(65535, iso))
    ifd.add(36867, "ASCII", dt)
    ifd.add(50706, "BYTE", [1, 4, 0, 0])
    ifd.add(50707, "BYTE", [1, 1, 0, 0])
    ifd.add(50708, "ASCII", "HALO 720 IMX708 NoIR")
    ifd.add(50713, "SHORT", [2, 2])
    ifd.add(50714, "SHORT", black)
    ifd.add(50717, "SHORT", (1 << bpp) - 1)
    ifd.add(50721, "SRATIONAL", [_srat(v) for v in cm1.flatten()])
    ifd.add(50723, "SRATIONAL", [_srat(v) for v in np.eye(3).flatten()])
    ifd.add(50728, "RATIONAL", [(10000, int(gr * 10000)), (10000, 10000), (10000, int(gb * 10000))])
    ifd.add(50730, "SRATIONAL", [(1, 1)])
    ifd.add(50778, "SHORT", 21)                  # D65
    ifd.add(50936, "ASCII", "HALO 720")
    # 1er passage pour connaître la taille de l'en-tête, puis on place les données après
    hdr = ifd.pack(8)
    data_start = 8 + len(hdr)
    data_start += (-data_start) % 16
    ifd.entries = [(t, ty, ([data_start] if t == 273 else v)) for t, ty, v in ifd.entries]
    hdr = ifd.pack(8)
    buf = np.frombuffer(rawbuf, dtype=np.uint8) if not isinstance(rawbuf, np.ndarray) else rawbuf.reshape(-1)
    tmp = path + ".tmp" if not path.endswith(".tmp") else path
    with open(tmp, "wb") as f:
        f.write(b"II*\0" + struct.pack("<I", 8) + hdr)
        f.write(b"\0" * (data_start - 8 - len(hdr)))
        for r0 in range(0, h, strip_rows):
            r1 = min(h, r0 + strip_rows)
            block = buf[r0 * stride:r1 * stride].reshape(r1 - r0, stride)[:, :row_bytes]
            f.write(repack_csi2p_to_dng(np.ascontiguousarray(block).reshape(-1, 5)).tobytes())
        f.flush()
        os.fsync(f.fileno())
    if tmp != path:
        os.replace(tmp, path)

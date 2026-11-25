#!/usr/bin/env python3
"""crop.py board x0 y0 x1 y1 out.png  (repère module) : F.Cu (rouge) + B.Cu (bleu) superposés."""
import subprocess, sys
from PIL import Image, ImageChops
b, x0, y0, x1, y1, out = sys.argv[1], *map(float, sys.argv[2:6]), sys.argv[6]
BX0, BX1, BY0, BY1 = -6.0, 37.0, -66.0, 3.5
if len(sys.argv) > 7:
    BX0, BX1, BY0, BY1 = map(float, sys.argv[7:11])
ims = []
for lay in ("F.Cu", "B.Cu"):
    subprocess.run(["kicad-cli", "pcb", "export", "svg", "--mode-single", "--fit-page-to-board", "--exclude-drawing-sheet",
                    "-l", f"{lay},Edge.Cuts", "-o", f"/tmp/_{lay}.svg", b], capture_output=True)
    subprocess.run(["rsvg-convert", "-z", "16", "-b", "white", f"/tmp/_{lay}.svg", "-o", f"/tmp/_{lay}.png"])
    ims.append(Image.open(f"/tmp/_{lay}.png").convert("RGB"))
W, H = ims[0].size
sx, sy = W / (BX1 - BX0), H / (BY1 - BY0)
box = (int((x0 - BX0) * sx), int((y0 - BY0) * sy), int((x1 - BX0) * sx), int((y1 - BY0) * sy))
a, c = ims[0].crop(box), ims[1].crop(box)
m = ImageChops.multiply(a, c)
m.thumbnail((1400, 1400))
m.save(out)

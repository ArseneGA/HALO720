#!/usr/bin/env python3
"""Empreintes du projet HALO 720 v3 (KiCad 9).

- Hirose DF40C-100DS-0.4V(51) : position des pastilles reprise de l'empreinte officielle
  Raspberry Pi CM4IO (Raspberry-Pi-4-Compute-Module.kicad_mod, CC BY 4.0) : 0,2 x 0,7 mm,
  pas 0,4 mm, rangées à +/-1,54 mm (= motif recommandé Hirose : 3,78 / 2,38 mm).
  Une empreinte par connecteur (J1 broches 1-100, J2 broches 101-200), origine au centre.
- OSRAM OSLON Black SFH 4715AS : motif recommandé de la datasheet (p. 11) : 3 pastilles
  2,8 mm de large ; cathode en haut (0,55 mm), anode au centre (1,0 mm, pastille thermique)
  et en bas (0,55 mm). Le centre est l'ANODE (pas isolé).
"""
import os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "kicad", "halo720.pretty")
OUTF = os.path.join(HERE, "..", "kicad_flash", "halo720.pretty")
CM4IO = sys.argv[1] if len(sys.argv) > 1 else "/home/claude/ext/rpi-cm4-carrier-template/hardware/libraries/CM4IO/CM4IO.pretty/Raspberry-Pi-4-Compute-Module.kicad_mod"

def f(v):
    s = ("%.4f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s

def head(name, descr, attr="smd"):
    return [f'(footprint "{name}"', '  (version 20241229)', '  (generator "halo720")', '  (layer "F.Cu")',
            f'  (descr "{descr}")', f'  (attr {attr})']

def text(kind, val, x, y, layer, hide=False):
    h = " (hide yes)" if hide else ""
    return f'  (property "{kind}" "{val}" (at {f(x)} {f(y)} 0) (layer "{layer}"){h} (effects (font (size 0.8 0.8) (thickness 0.12))))'

def line(x1, y1, x2, y2, layer, w=0.12):
    return f'  (fp_line (start {f(x1)} {f(y1)}) (end {f(x2)} {f(y2)}) (stroke (width {w}) (type solid)) (layer "{layer}"))'

def rect(x1, y1, x2, y2, layer, w=0.05):
    return [line(x1, y1, x2, y1, layer, w), line(x2, y1, x2, y2, layer, w), line(x2, y2, x1, y2, layer, w), line(x1, y2, x1, y1, layer, w)]

def pad(num, x, y, w, h, shape="rect", extra=""):
    return (f'  (pad "{num}" smd {shape} (at {f(x)} {f(y)}) (size {f(w)} {f(h)}) '
            f'(layers "F.Cu" "F.Paste" "F.Mask"){extra})')

def cm4_pads():
    t = open(CM4IO).read()
    pads = {}
    for m in re.finditer(r'\(pad "(\d+)" smd rect \(at ([-\d.]+) ([-\d.]+) 270\) \(size 0\.2 0\.7\)', t):
        pads[int(m.group(1))] = (float(m.group(2)), float(m.group(3)))
    holes = [(float(a), float(b)) for a, b in re.findall(r'np_thru_hole circle \(at ([-\d.]+) ([-\d.]+)\)', t)]
    return pads, holes

def df40(name, pads, first):
    sel = {n: pads[n] for n in range(first, first + 100)}
    xs = [p[0] for p in sel.values()]; ys = [p[1] for p in sel.values()]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    L = head(name, "Hirose DF40C-100DS-0.4V(51), réceptacle 100 broches pas 0,4 mm, hauteur 1,5 mm (CM4) ; même motif de pastilles que les versions DF40HC 2,5 à 4,0 mm")
    L += [text("Reference", "REF**", 0, -11.8, "F.SilkS"), text("Value", name, 0, 11.8, "F.Fab"),
          text("Footprint", "", 0, 0, "F.Fab", True)]
    # corps 22,6 x 3,38 mm (A x largeur) ; pastilles 0,7 de long en X
    L += rect(-1.69, -11.3, 1.69, 11.3, "F.Fab", 0.1)
    L += rect(-2.2, -11.6, 2.2, 11.6, "F.CrtYd", 0.05)
    y1 = min(ys) - cy
    p1 = sel[first]; x1 = p1[0] - cx
    L.append(line(-2.15, -11.45, 2.15, -11.45, "F.SilkS")); L.append(line(-2.15, 11.45, 2.15, 11.45, "F.SilkS"))
    # repère broche 1
    L.append(f'  (fp_circle (center {f(x1 - 0.75 if x1 < 0 else x1 + 0.75)} {f(p1[1] - cy)}) (end {f((x1 - 0.75 if x1 < 0 else x1 + 0.75) + 0.12)} {f(p1[1] - cy)}) (stroke (width 0.24) (type solid)) (fill yes) (layer "F.SilkS"))')
    for n, (x, y) in sorted(sel.items()):
        L.append(pad(n, x - cx, y - cy, 0.7, 0.2))
    L.append(")")
    return "\n".join(L) + "\n", (cx, cy)

def oslon():
    name = "OSRAM_OSLON_Black_SFH4715AS"
    L = head(name, "OSRAM OSLON Black SFH 4715AS, LED IR 850 nm 3,75 x 3,75 mm ; motif recommandé datasheet (pastille centrale = anode)")
    L += [text("Reference", "REF**", 0, -2.9, "F.SilkS"), text("Value", "SFH 4715AS", 0, 2.9, "F.Fab"),
          text("Footprint", "", 0, 0, "F.Fab", True)]
    L += rect(-1.875, -1.875, 1.875, 1.875, "F.Fab", 0.1)
    L += rect(-2.2, -2.2, 2.2, 2.2, "F.CrtYd", 0.05)
    L += [line(-2.0, -2.0, 2.0, -2.0, "F.SilkS"), line(-2.0, 2.0, 2.0, 2.0, "F.SilkS"),
          line(-2.0, -2.0, -2.0, -1.4, "F.SilkS"),
          f'  (fp_text user "K" (at -2.7 -1.125 0) (layer "F.SilkS") (effects (font (size 0.8 0.8) (thickness 0.15))))']
    L.append(pad(1, 0, -1.125, 2.8, 0.55))          # cathode
    L.append(pad(2, 0, 0, 2.8, 1.0))                # anode (pastille thermique)
    L.append(pad(2, 0, 1.125, 2.8, 0.55))           # anode
    L.append(")")
    return "\n".join(L) + "\n"

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True); os.makedirs(OUTF, exist_ok=True)
    pads, holes = cm4_pads()
    assert len(pads) == 200, len(pads)
    centers = {}
    for name, first in (("Hirose_DF40HC3.0-100DS_CM4_J1", 1), ("Hirose_DF40HC3.0-100DS_CM4_J2", 101)):
        txt, c = df40(name, pads, first)
        open(os.path.join(OUT, name + ".kicad_mod"), "w").write(txt)
        centers[name] = c
    o = oslon()
    open(os.path.join(OUTF, "OSRAM_OSLON_Black_SFH4715AS.kicad_mod"), "w").write(o)
    import json
    json.dump({"connectors": centers, "holes": holes,
               "note": "repère CM4IO : trous du CM4 aux 4 coins (33 x 48 mm)"},
              open(os.path.join(HERE, "cm4_geometry.json"), "w"), indent=1)
    print("centres :", centers, "trous :", holes)

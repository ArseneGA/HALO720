#!/usr/bin/env python3
"""Sérigraphie lisible : références masquées (elles restent sur la couche Fab et dans le CPL),
étiquettes utiles ajoutées à la main."""
import os, sys
import pcbnew
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import place_main as P
from pcb_common import vec, MM

def text(b, s, x, y, layer, size=0.8, rot=0, mirror=False):
    t = pcbnew.PCB_TEXT(b)
    t.SetText(s)
    t.SetPosition(vec(*P.M(x, y)))
    t.SetLayer(layer)
    t.SetTextSize(pcbnew.VECTOR2I(MM(size), MM(size)))
    t.SetTextThickness(MM(max(0.15, size * 0.15)))
    t.SetTextAngleDegrees(rot)
    if mirror:
        t.SetMirrored(True)
    b.Add(t)

b = pcbnew.LoadBoard(P.PCB)
for fp in b.GetFootprints():
    for fld in fp.GetFields():
        fld.SetVisible(False)
    for it in fp.GraphicalItems():
        if isinstance(it, pcbnew.PCB_TEXT) and it.GetLayer() in (pcbnew.F_SilkS, pcbnew.B_SilkS):
            it.SetVisible(False)
F, B = pcbnew.F_SilkS, pcbnew.B_SilkS
text(b, "CAMERA", 17.0, -57.0, F, 0.8)
text(b, "USB-C", 31.6, -57.0, F, 0.8)
text(b, "DECL.", -2.9, -57.2, F, 0.8)
text(b, "MARCHE", 3.4, -57.2, F, 0.8)
text(b, "ETAT", -3.6, -52.6, F, 0.8)
text(b, "ACT", 2.9, -52.6, F, 0.8)
text(b, "BOOT", 7.6, -55.0, F, 0.8)
text(b, "HALO 720 v3", 16.0, -8.0, F, 1.2)
text(b, "rev A  2026", 16.0, -6.2, F, 0.8)
text(b, "BATT", 26.4, -36.4, B, 0.8, mirror=True)
text(b, "+", 26.6, -41.0, B, 1.2, mirror=True)
text(b, "-", 26.6, -39.0, B, 1.2, mirror=True)
text(b, "FLASH", 26.4, -3.6, B, 0.8, mirror=True)
text(b, "+", 26.6, -8.0, B, 1.2, mirror=True)
text(b, "-", 26.6, -6.0, B, 1.2, mirror=True)
text(b, "HALO 720 v3 - alimentation / flash", 14.0, -51.0, B, 0.8, mirror=True)
pcbnew.SaveBoard(P.PCB, b)
print("sérigraphie mise à jour")

#!/usr/bin/env python3
"""Placement de la carte principale HALO 720 v3 (repère « module » : origine = trou du CM4 côté
antenne, x vers J2, y vers le haut de la carte négatif). Le décalage OX/OY rend tout positif."""
import json
import os
import sys

import pcbnew

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pcb_common import *  # noqa

HERE = os.path.dirname(os.path.abspath(__file__))
KDIR = os.path.join(HERE, "..", "kicad")
SCH = os.path.join(KDIR, "halo720_v3.kicad_sch")
PCB = os.path.join(KDIR, "halo720_v3.kicad_pcb")
PROJLIB = os.path.join(KDIR, "halo720.pretty")

OX, OY = 60.0, 130.0          # décalage du repère module vers la feuille
X0, X1, Y0, Y1 = -6.0, 37.0, -66.0, 3.5   # contour de la carte (repère module)
ANT = (3.5, 18.5, -4.5, 3.5)  # zone sans cuivre sous l'antenne du CM4 (x0, x1, y0, y1)

# ref: (x, y, rot, bottom)   -- repère module
TOP = {
    "J1": (-0.46, -21.5, 0), "J2": (33.46, -21.5, 0),
    "J3": (17.0, Y0 + 4.925, 180),          # caméra, ouverture vers le bord haut
    "J5": (31.6, Y0 + 3.7, 180),            # USB-C, ouverture vers le bord haut
    "U4": (31.6, -54.2, 0),                 # ESD USB
    "R1": (35.4, -55.6, 90), "R2": (35.4, -53.6, 90),
    "C1": (27.4, -55.4, 90),
    "SW1": (-2.9, -61.8, 90), "SW2": (3.4, -61.8, 90),
    "R18": (-5.0, -56.3, 90),
    "D3": (-3.6, -53.6, 0), "R19": (-0.6, -53.6, 0),
    "D4": (2.9, -53.6, 0), "R21": (5.9, -53.6, 0),
    "JP1": (7.6, -56.4, 0),
    "C14": (11.0, -56.4, 0), "C15": (13.6, -56.4, 0),
}
BOT = {
    # --- chargeur IP5306 (haut) ---
    "U2": (10.0, -44.0, 0),
    "C2": (4.6, -41.4, 0), "C3": (4.6, -39.9, 0),          # VBUS
    "R3": (7.4, -18.2, 0), "R4": (7.4, -16.8, 0),          # tirages I2C
    "L1": (17.6, -44.2, 90),
    "C8": (12.4, -39.4, 0), "C9": (12.4, -37.2, 0),        # +5V
    "C4": (22.4, -47.6, 90), "C5": (24.8, -47.6, 90),      # +BATT
    "J6": (31.8, -40.0, 270),                              # batterie, ouverture bord droit
    # --- flash (milieu) ---
    "U3": (15.0, -28.6, 0),
    "L2": (15.0, -22.8, 180),                              # pad 1 (+BATT) à droite
    "D1": (9.6, -26.4, 90),
    "C12": (6.4, -24.6, 90), "C13": (6.4, -20.8, 90),      # VLED
    "C10": (19.8, -29.8, 90), "C11": (19.8, -25.8, 90),    # +BATT entrée boost
    "R11": (11.4, -32.6, 0), "R12": (14.2, -32.6, 0), "D2": (12.8, -34.8, 0),
    "R9": (17.4, -32.6, 0),
    "Q2": (23.4, -19.0, 0), "R8": (19.8, -19.0, 0),
    "R10": (22.0, -14.8, 0),
    "J7": (31.6, -7.0, 270),                               # carte flash, ouverture bord droit
    # --- divers ---
    "Q1": (3.6, -30.2, 0), "R5": (3.6, -32.8, 0),
    "C6": (9.0, -13.0, 90), "C7": (11.6, -13.0, 90),       # +5V près du CM4
}
HOLES = [(0, 0), (33, 0), (0, -48), (33, -48)]
TPS = {"TP1": (-4.6, -34.0), "TP2": (-4.6, -36.2), "TP3": (-4.6, -8.0), "TP4": (-4.6, -6.0),
       "TP5": (2.0, -50.6), "TP6": (7.0, -8.5), "TP7": (24.6, -38.0), "TP8": (9.4, -8.5),
       "TP9": (22.0, -8.0), "TP10": (11.8, -8.5)}


def M(x, y):
    return x + OX, y + OY


def build():
    comps, pins = read_netlist(SCH)
    b = pcbnew.BOARD()
    set_rules(b)
    for ref, (x, y, r) in TOP.items():
        place(b, comps, pins, ref, *M(x, y), r, False, PROJLIB)
    for ref, (x, y, r) in BOT.items():
        place(b, comps, pins, ref, *M(x, y), r, True, PROJLIB)
    # vias thermiques de l'IP5306 : 0,25 mm (comme les autres vias) au lieu de 0,20 mm,
    # sinon JLCPCB impose l'option 0,2 mm + test Kelvin + FR4 TG155 (~37 $ de plus)
    for p in b.FindFootprintByReference("U2").Pads():
        if p.GetDrillSize().x and p.GetDrillSize().x < MM(0.25):
            p.SetDrillSize(pcbnew.VECTOR2I(MM(0.25), MM(0.25)))
    for i, (x, y) in enumerate(HOLES):
        place(b, comps, pins, f"H{i + 1}", *M(x, y), 0, False, PROJLIB)
    for ref, (x, y) in TPS.items():
        place(b, comps, pins, ref, *M(x, y), 0, True, PROJLIB)
    missing = set(comps) - {f.GetReference() for f in b.GetFootprints()}
    if missing:
        raise SystemExit(f"composants non placés : {sorted(missing)}")
    add_outline(b, [M(X0, Y0), M(X1, Y0), M(X1, Y1), M(X0, Y1)])
    return b, comps, pins


def courtyard_overlaps(b):
    """Recouvrements de courtyard sur une même face (rectangles englobants)."""
    out = []
    fps = list(b.GetFootprints())
    def box(fp):
        layer = pcbnew.F_CrtYd if not fp.IsFlipped() else pcbnew.B_CrtYd
        bb = fp.GetCourtyard(layer).BBox() if fp.GetCourtyard(layer).OutlineCount() else fp.GetBoundingBox(False)
        return bb
    for i in range(len(fps)):
        for j in range(i + 1, len(fps)):
            a, c = fps[i], fps[j]
            if a.IsFlipped() != c.IsFlipped():
                continue
            if a.GetReference().startswith(("H", "TP")) and c.GetReference().startswith(("H", "TP")):
                continue
            if box(a).Intersects(box(c)):
                out.append((a.GetReference(), c.GetReference()))
    return out


if __name__ == "__main__":
    b, _, _ = build()
    b.BuildConnectivity()
    ov = courtyard_overlaps(b)
    print("recouvrements :", ov)
    pcbnew.SaveBoard(PCB, b)
    print("écrit", PCB)

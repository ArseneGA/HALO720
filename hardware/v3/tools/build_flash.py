#!/usr/bin/env python3
"""Carte flash HALO 720 v3 : aluminium 1 couche, 24 x 14 mm, 2 LED OSRAM en série + JST-PH.

   J1.1 (LED_A) -> D1 anode ; D1 cathode -> D2 anode (LED_MID) ; D2 cathode -> J1.2 (LED_K)
   Grandes surfaces de cuivre par net = dissipateur (la carte alu évacue la chaleur)."""
import json
import os
import subprocess
import sys

import pcbnew

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pcb_common import *  # noqa

HERE = os.path.dirname(os.path.abspath(__file__))
KDIR = os.path.join(HERE, "..", "kicad_flash")
SCH = os.path.join(KDIR, "halo720_v3_flash.kicad_sch")
PCB = os.path.join(KDIR, "halo720_v3_flash.kicad_pcb")
LIB = os.path.join(KDIR, "halo720.pretty")
OX, OY = 100.0, 100.0
W, H = 24.0, 14.0


def M(x, y):
    return x + OX, y + OY


def main():
    comps, pins = read_netlist(SCH)
    b = pcbnew.BOARD()
    set_rules(b, clearance=0.25, track=0.3, layers=2)
    place(b, comps, pins, "D1", *M(8.0, 9.8), 0, False, LIB)
    place(b, comps, pins, "D2", *M(8.0, 4.2), 0, False, LIB)
    place(b, comps, pins, "J1", *M(19.0, 7.0), 90, False, LIB)
    place(b, comps, pins, "H1", *M(2.6, 2.6), 0, False, LIB)
    place(b, comps, pins, "H2", *M(2.6, 11.4), 0, False, LIB)
    add_outline(b, [M(0, 0), M(W, 0), M(W, H), M(0, H)])
    n = {p.GetParentFootprint().GetReference() + "." + p.GetNumber(): p for fp in b.GetFootprints() for p in fp.Pads()}
    A, MID, K = "/LED_A", "/LED_MID", "/LED_K"
    j1p1, j1p2 = n["J1.1"].GetPosition(), n["J1.2"].GetPosition()
    p = lambda v: (v.x / 1e6, v.y / 1e6)
    # pistes larges (1,2 mm) qui garantissent la continuité ; les zones élargissent le cuivre
    y1, y2 = p(j1p1)[1] - OY, p(j1p2)[1] - OY
    # K : cathode de D2 (haut) -> J1.2 ; A : anode de D1 (bas) -> J1.1 ; MID : D1 cathode -> D2 anode
    add_track(b, K, pcbnew.F_Cu, M(8.0, 3.075), M(8.0, 1.8), 0.5)
    add_track(b, K, pcbnew.F_Cu, M(8.0, 1.8), M(13.0, 1.8), 1.0)
    add_track(b, K, pcbnew.F_Cu, M(13.0, 1.8), M(13.0, y2), 1.0)
    add_track(b, K, pcbnew.F_Cu, M(13.0, y2), p(j1p2), 1.0)
    add_track(b, A, pcbnew.F_Cu, M(8.0, 10.4), M(8.0, 12.2), 1.2)
    add_track(b, A, pcbnew.F_Cu, M(8.0, 12.2), M(13.0, 12.2), 1.0)
    add_track(b, A, pcbnew.F_Cu, M(13.0, 12.2), M(13.0, y1), 1.0)
    add_track(b, A, pcbnew.F_Cu, M(13.0, y1), p(j1p1), 1.0)
    add_track(b, MID, pcbnew.F_Cu, M(8.0, 5.325), M(8.0, 8.675), 0.5)
    # surfaces de cuivre (dissipation : les pastilles centrales = anodes)
    add_zone(b, K, pcbnew.F_Cu, [M(5.2, 0.6), M(16.5, 0.6), M(16.5, 6.7), M(11.8, 6.7), M(11.8, 2.5), M(5.2, 2.5)],
             clearance=0.3, priority=2, thermal=False)
    add_zone(b, A, pcbnew.F_Cu, [M(5.2, 13.4), M(16.5, 13.4), M(16.5, 7.3), M(11.8, 7.3), M(11.8, 9.2), M(5.2, 9.2)],
             clearance=0.3, priority=2, thermal=False)
    add_zone(b, MID, pcbnew.F_Cu, [M(0.6, 0.6), M(5.0, 0.6), M(5.0, 3.6), M(11.4, 3.6), M(11.4, 9.0),
                                   M(5.0, 9.0), M(5.0, 13.4), M(0.6, 13.4)],
             clearance=0.3, priority=1, thermal=False)
    # sérigraphie
    for fp in b.GetFootprints():
        for fld in fp.GetFields():
            fld.SetVisible(False)
    t = pcbnew.PCB_TEXT(b)
    t.SetText("FLASH IR")
    t.SetPosition(vec(*M(19.5, 1.4)))
    t.SetLayer(pcbnew.F_SilkS)
    t.SetTextSize(pcbnew.VECTOR2I(MM(0.8), MM(0.8)))
    t.SetTextThickness(MM(0.15))
    b.Add(t)
    for s, y in (("+", y1), ("-", y2)):
        t = pcbnew.PCB_TEXT(b)
        t.SetText(s)
        t.SetPosition(vec(*M(11.6, y)))
        t.SetLayer(pcbnew.F_SilkS)
        t.SetTextSize(pcbnew.VECTOR2I(MM(1.0), MM(1.0)))
        t.SetTextThickness(MM(0.18))
        b.Add(t)
    for pd in n.values():
        print(pd.GetParentFootprint().GetReference(), pd.GetNumber(), round(pd.GetPosition().x/1e6-OX,2), round(pd.GetPosition().y/1e6-OY,2), pd.GetNetname())
    pcbnew.SaveBoard(PCB, b)
    subprocess.run([sys.executable, "-c", "import pcbnew,sys; b=pcbnew.LoadBoard(sys.argv[1]); "
                    "pcbnew.ZONE_FILLER(b).Fill(b.Zones()); pcbnew.SaveBoard(sys.argv[1], b)", PCB], check=True)
    out = "/tmp/drc_flash.json"
    subprocess.run(["kicad-cli", "pcb", "drc", "--format", "json", "--severity-all", "--schematic-parity",
                    "-o", out, PCB], capture_output=True)
    r = json.load(open(out))
    from collections import Counter
    print("violations :", Counter((v["type"], v["severity"]) for v in r["violations"]))
    print("connexions manquantes :", len(r["unconnected_items"]), "; parité :", len(r["schematic_parity"]))
    for v in r["violations"]:
        if v["severity"] == "error":
            print("  ", v["type"], [i["description"][:60] for i in v["items"]])


if __name__ == "__main__":
    main()

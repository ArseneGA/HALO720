#!/usr/bin/env python3
"""Finition de la carte principale : vias de couture GND, liaisons GND/+5V restantes,
remplissage des plans, DRC. Boucle jusqu'à ce qu'il ne reste aucune connexion manquante."""
import json
import os
import re
import subprocess
import sys

import numpy as np
import pcbnew

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import place_main as P
from router import Router, RES

PCB = P.PCB


def drc(path, out="/tmp/drc_main.json"):
    subprocess.run(["kicad-cli", "pcb", "drc", "--format", "json", "--severity-all", "--schematic-parity",
                    "-o", out, path], capture_output=True)
    return json.load(open(out))


def fill(b):
    pcbnew.ZONE_FILLER(b).Fill(b.Zones())


def stitch(b, r, pitch=3.0):
    """Vias GND régulières là où il y a de la place (relient pour F.Cu, B.Cu et In1)."""
    free, via_ok = r.free_maps("GND", 0.1)
    # via éloignée de 0,6 mm de tout cuivre d'un autre net, hors bords
    n = 0
    step = int(pitch / RES)
    for y in range(step // 2, r.H, step):
        for x in range(step // 2, r.W, step):
            if via_ok[y, x]:
                r.add_copper_via("GND", r.xy(y, x))
                n += 1
                # mise à jour locale : la nouvelle via est du cuivre GND
        free, via_ok = r.free_maps("GND", 0.1) if n % 25 == 0 else (free, via_ok)
    return n


def prune_vias(b):
    """Supprime les vias qui ne relient rien sur une des deux couches (hors GND / +5V : plans)."""
    removed = 0
    tracks = list(b.GetTracks())
    for v in [t for t in tracks if t.Type() == pcbnew.PCB_VIA_T]:
        net = v.GetNetname()
        if net in ("GND", "+5V"):
            continue
        p = v.GetPosition()
        layers = set()
        for t in tracks:
            if t.Type() == pcbnew.PCB_VIA_T or t.GetNetname() != net:
                continue
            if t.HitTest(p, int(v.GetWidth(pcbnew.F_Cu) / 2)):
                layers.add(t.GetLayer())
        for fp in b.GetFootprints():
            for pd in fp.Pads():
                if pd.GetNetname() == net and pd.HitTest(p):
                    layers |= {l for l in (pcbnew.F_Cu, pcbnew.B_Cu) if pd.IsOnLayer(l)}
        if len(layers & {pcbnew.F_Cu, pcbnew.B_Cu}) < 2:
            # via inutile : on raccorde les pistes qui y arrivent (elles sont sur une seule couche)
            b.Remove(v)
            removed += 1
    return removed


def prune_dangling(b):
    """Retire les bouts de piste qui ne mènent nulle part (itératif)."""
    total = 0
    while True:
        tracks = [t for t in b.GetTracks() if t.Type() == pcbnew.PCB_TRACE_T]
        vias = [t for t in b.GetTracks() if t.Type() == pcbnew.PCB_VIA_T]
        pads = [p for fp in b.GetFootprints() for p in fp.Pads()]
        dead = []
        for t in tracks:
            net = t.GetNetname()
            for e in (t.GetStart(), t.GetEnd()):
                ok = False
                for o in tracks:
                    if o is not t and o.GetNetname() == net and o.GetLayer() == t.GetLayer() and o.HitTest(e, int(t.GetWidth() / 2)):
                        ok = True; break
                if not ok:
                    ok = any(v.GetNetname() == net and v.HitTest(e, int(t.GetWidth() / 2)) for v in vias)
                if not ok:
                    ok = any(p.GetNetname() == net and p.IsOnLayer(t.GetLayer()) and p.HitTest(e, int(t.GetWidth() / 2)) for p in pads)
                if not ok and net in ("GND", "+5V"):
                    ok = True        # raccordé par un plan
                if not ok:
                    dead.append(t); break
        for t in dead:
            b.Remove(t)
        return len(dead)


def island_vias(b, unc):
    """Chaque morceau de remplissage GND (F.Cu / B.Cu) doit contenir une via GND vers In1."""
    r = Router(b)
    free, via_ok = r.free_maps("GND", 0.1)
    vias = [t.GetPosition() for t in b.GetTracks() if t.Type() == pcbnew.PCB_VIA_T and t.GetNetname() == "GND"]
    vias += [p.GetPosition() for fp in b.GetFootprints() for p in fp.Pads()
             if p.GetNetname() == "GND" and p.GetAttribute() == pcbnew.PAD_ATTRIB_PTH]
    n = 0
    for z in b.Zones():
        if z.GetIsRuleArea() or z.GetNetname() != "GND" or z.GetLayer() not in (pcbnew.F_Cu, pcbnew.B_Cu):
            continue
        fp_ = z.GetFilledPolysList(z.GetLayer())
        for i in range(fp_.OutlineCount()):
            ol = fp_.Outline(i)
            if any(ol.PointInside(v) for v in vias):
                continue
            pts = [(ol.CPoint(k).x / 1e6, ol.CPoint(k).y / 1e6) for k in range(ol.PointCount())]
            m = np.zeros((r.H, r.W), bool)
            r._poly(m, pts, True)
            cand = np.argwhere(m & via_ok)
            if len(cand) == 0:
                # pas de place pour une via dans l'îlot : via de sortie depuis un pad GND de l'îlot
                for f in b.GetFootprints():
                    for pd in f.Pads():
                        bbx = ol.BBox()
                        bbx.Inflate(pcbnew.FromMM(0.5))
                        if pd.GetNetname() == "GND" and pd.IsOnLayer(z.GetLayer()) and bbx.Contains(pd.GetPosition()):
                            if r.fanout(pd, "GND", w=0.25, max_len=4.0):
                                n += 1
                                break
                            # sinon : piste vers le cuivre GND le plus proche (via, pad) hors de l'îlot
                            fr, vo = r.free_maps("GND", 0.125)
                            code = r.code_of("GND")
                            src = r.pad_mask(pd)
                            dst = [(r.cu[0] == code) & ~m & r.holes, (r.cu[1] == code) & r.holes & ~m]
                            path = r.astar(fr, vo, src, dst, via_cost=40)
                            if path:
                                r.commit("GND", path, 0.25)
                                n += 1
                                break
                    else:
                        continue
                    break
                continue
            c = cand.mean(axis=0)
            k = int(np.argmin(((cand - c) ** 2).sum(axis=1)))
            y, x = cand[k]
            r.add_copper_via("GND", r.xy(y, x))
            free, via_ok = r.free_maps("GND", 0.1)
            n += 1
    return n


def _unused(b, unc):
    r = Router(b)
    free, via_ok = r.free_maps("GND", 0.1)
    from scipy.ndimage import distance_transform_edt
    n = 0
    for u in unc:
        for item in u["items"]:
            if not item["description"].startswith("Zone [GND]"):
                continue
            x, y = item["pos"]["x"], item["pos"]["y"]
            cx, cy = r.c(x, y)
            yy, xx = np.nonzero(via_ok)
            if len(yy) == 0:
                continue
            d = (yy - cy) ** 2 + (xx - cx) ** 2
            k = int(np.argmin(d))
            if d[k] * RES * RES <= 1.5 ** 2:
                r.add_copper_via("GND", r.xy(yy[k], xx[k]))
                free, via_ok = r.free_maps("GND", 0.1)
                n += 1
    return n


def main():
    b = pcbnew.LoadBoard(PCB)
    r = Router(b)
    n = stitch(b, r)
    print("vias de couture GND :", n)
    for it in range(4):
        fill(b)
        pcbnew.SaveBoard(PCB, b)
        rep = drc(PCB)
        unc = rep.get("unconnected_items", [])
        print(f"passe {it} : {len(unc)} connexions manquantes, {len(rep.get('violations', []))} violations")
        if not unc:
            break
        # pour chaque pad GND / +5V isolé : via vers le plan
        r = Router(b)
        fixed = 0
        seen = set()
        for u in unc:
            for item in u["items"]:
                m = re.match(r"Pad (\S+) \[(.*?)\] of (\S+)", item["description"])
                if not m:
                    continue
                num, net, ref = m.groups()
                if net not in ("GND", "+5V") or (ref, num) in seen:
                    continue
                seen.add((ref, num))
                fp = b.FindFootprintByReference(ref)
                for pd in fp.Pads():
                    if pd.GetNumber() == num:
                        if r.fanout(pd, net, w=0.3, max_len=4.0):
                            fixed += 1
                        break
        fixed += island_vias(b, unc)
        print("  vias ajoutées :", fixed)
    fill(b)
    pcbnew.SaveBoard(PCB, b)
    rep = drc(PCB)
    v = rep.get("violations", [])
    from collections import Counter
    print("violations :", Counter((x["type"], x["severity"]) for x in v))
    print("connexions manquantes :", len(rep.get("unconnected_items", [])))
    print("parité schéma :", len(rep.get("schematic_parity", [])))
    return rep


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "prune":
        b = pcbnew.LoadBoard(PCB)
        print("vias inutiles retirées :", prune_vias(b))
        pcbnew.SaveBoard(PCB, b)
    elif len(sys.argv) > 1 and sys.argv[1] == "prunetracks":
        b = pcbnew.LoadBoard(PCB)
        print("bouts de piste retirés :", prune_dangling(b))
        pcbnew.SaveBoard(PCB, b)
    else:
        main()

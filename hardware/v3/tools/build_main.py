#!/usr/bin/env python3
"""Construit, route et vérifie la carte principale HALO 720 v3."""
import json
import os
import subprocess
import sys
import time

import pcbnew

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pcb_common import *  # noqa
import place_main as P
from router import Router

LOG = []


def log(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True)
    LOG.append(s)


M = P.M
G = lambda x, y: M(x, y)                  # repère module -> feuille


def pin_xy(conn, n):
    """Centre du pad n d'un connecteur DF40 (repère module)."""
    cx = -0.46 if conn == "J1" else 33.46
    k = n - (1 if conn == "J1" else 101)
    x = cx - 1.54 if k % 2 == 0 else cx + 1.54
    y = -21.5 - 9.8 + (k // 2) * 0.4
    return x, y


def zones(b):
    x0, x1, y0, y1 = P.X0 + 0.3, P.X1 - 0.3, P.Y0 + 0.3, P.Y1 - 0.3
    full = [M(x0, y0), M(x1, y0), M(x1, y1), M(x0, y1)]
    add_zone(b, "GND", pcbnew.In1_Cu, full, clearance=0.2, priority=0, thermal=False)
    add_zone(b, "+5V", pcbnew.In2_Cu, full, clearance=0.2, priority=0, thermal=False)
    add_zone(b, "GND", pcbnew.F_Cu, full, clearance=0.2, priority=0, thermal=False)
    add_zone(b, "GND", pcbnew.B_Cu, full, clearance=0.2, priority=0, thermal=False)
    a = P.ANT
    add_keepout(b, [M(a[0], a[2]), M(a[1], a[2]), M(a[1], a[3] + 1), M(a[0], a[3] + 1)],
                [pcbnew.F_Cu, pcbnew.In1_Cu, pcbnew.In2_Cu, pcbnew.B_Cu])


def df40_gnd(r):
    """Masse des DF40 : vias dans l'allée centrale, reliées aux pads GND voisins."""
    n = 0
    for conn, first, cx in (("J1", 1, -0.46), ("J2", 101, 33.46)):
        gnd = sorted({pin_xy(conn, k)[1] for k in range(first, first + 100)
                      if P_CM4[k][0] == "GND"})
        # regroupe les y proches (< 0,85 mm) : une via par groupe
        groups, cur = [], [gnd[0]]
        for y in gnd[1:]:
            if y - cur[0] < 0.85:
                cur.append(y)
            else:
                groups.append(cur)
                cur = [y]
        groups.append(cur)
        for grp in groups:
            vy = sum(grp) / len(grp)
            r.add_copper_via("GND", G(cx, vy))
            n += 1
            for k in range(first, first + 100):
                if P_CM4[k][0] != "GND":
                    continue
                px, py = pin_xy(conn, k)
                if py in grp:
                    inner = px + 0.25 if px < cx else px - 0.25
                    r.add_copper_track("GND", 0, G(inner, py), G(cx, vy), 0.2)
    log(f"vias GND des connecteurs CM4 : {n}")


def cm4_5v(r):
    """Broches +5V du CM4 (J1 77..87, rangée impaire) : barre + 3 vias vers le plan +5V (In2)."""
    ys = [pin_xy("J1", k)[1] for k in (77, 79, 81, 83, 85, 87)]
    xb = -2.45
    r.add_copper_track("+5V", 0, G(xb, ys[0]), G(xb, ys[-1]), 0.3)
    for y in (ys[0], ys[2], ys[4]):
        r.add_copper_track("+5V", 0, G(xb, y), G(-3.75, y), 0.3)
        r.add_copper_via("+5V", G(-3.75, y))
    log("barre +5V du CM4 : 6 broches, 3 vias")


def corridors(r):
    """Sortie perpendiculaire imposée sur 1,2 mm pour chaque rangée des connecteurs du CM4."""
    for conn, first, cx in (("J1", 1, -0.46), ("J2", 101, 33.46)):
        for side in (-1, 1):            # -1 = rangée impaire (x - 1,54), +1 = paire
            x_end = cx + side * (1.54 + 0.35)
            xa, xb = sorted((x_end, x_end + side * 1.2))
            ya, yb = -31.3 - 0.3, -11.7 + 0.3
            lanes = {}
            for k in range(first, first + 100):
                px, py = pin_xy(conn, k)
                if (px > cx) != (side > 0):
                    continue
                net = PINS.get((conn, str(k)))
                if not net or net.startswith("unconnected") or net == "GND":
                    continue
                lanes.setdefault(net, []).append((xa - 0.05, xb + 0.05, py - 0.08, py + 0.08))
            ga, gb = G(xa, ya), G(xb, yb)
            r.add_corridor(ga[0], gb[0], ga[1], gb[1],
                           {n: [(G(a, c)[0], G(b_, d)[0], G(a, c)[1], G(b_, d)[1]) for a, b_, c, d in v]
                            for n, v in lanes.items()})


def j1_fanout(r):
    """Éventail de sortie de J1 : chaque broche utilisée sort tout droit puis rejoint une via,
    les vias étant alignées en colonne au pas de 0,9 mm (ordre conservé, aucun croisement)."""
    n = 0
    for side, dist in ((-1, 2.3), (1, 3.0)):
        x_end = -0.46 + side * (1.54 + 0.35)
        used = []
        for k in range(1, 101):
            px, py = pin_xy("J1", k)
            if (px > -0.46) != (side > 0):
                continue
            net = PINS.get(("J1", str(k)))
            if not net or net.startswith("unconnected") or net in ("GND", "+5V"):
                continue
            used.append((py, net, px))
        used.sort()
        # positions des vias : pas mini 0,9 mm, au plus près des broches
        vy = []
        for py, _, _ in used:
            vy.append(max(py, vy[-1] + 0.9) if vy else py)
        # recentrage de chaque grappe
        i = 0
        while i < len(used):
            j = i
            while j + 1 < len(used) and vy[j + 1] - vy[j] < 0.9 + 1e-6:
                j += 1
            shift = (sum(u[0] for u in used[i:j + 1]) - sum(vy[i:j + 1])) / (j - i + 1)
            if i > 0:
                shift = max(shift, vy[i - 1] + 0.9 - vy[i])
            for k in range(i, j + 1):
                vy[k] += shift
            i = j + 1
        xv = x_end + side * dist
        for (py, net, px), yv in zip(used, vy):
            x1 = x_end + side * 0.6
            r.add_copper_track(net, 0, G(px, py), G(x1, py), 0.15)
            r.add_copper_track(net, 0, G(x1, py), G(xv, yv), 0.15)
            r.add_copper_via(net, G(xv, yv))
            n += 1
    log(f"éventail J1 : {n} broches")


def csi_bus(r):
    """Bus CSI-2 : 10 pistes parallèles J2 -> J3 (paires 100 ohms, même ordre des deux côtés)."""
    j3x, j3y = P.TOP["J3"][0], P.TOP["J3"][1]
    pad_y = j3y + 1.85
    pairs = [(115, 2), (117, 3), (121, 5), (123, 6), (127, 8), (129, 9),
             (133, 11), (135, 12), (139, 14), (141, 15)]
    for n2, n3 in pairs:
        net = PINS[("J2", str(n2))]
        assert net == PINS[("J3", str(n3))], (n2, n3)
        x2, y2 = pin_xy("J2", n2)
        x3 = j3x + 5.25 - 0.5 * (n3 - 1)
        r.add_copper_track(net, 0, G(x2, y2), G(x3, y2), 0.15)
        r.add_copper_track(net, 0, G(x3, y2), G(x3, pad_y), 0.15)
    log("bus CSI : 10 pistes posées")


ORDER = [  # net, largeur (mm), coût de via
    ("IP_SW", 0.8, 200), ("+BATT", 0.8, 120), ("BST_SW", 0.6, 200), ("VLED", 0.6, 80),
    ("LED_K", 0.6, 80), ("LED_SENSE", 0.5, 80), ("VBUS", 0.5, 60),
    ("USB_DP", 0.15, 40), ("USB_DN", 0.15, 40),
    ("CAM_SCL", 0.15, 30), ("CAM_SDA", 0.15, 30), ("CAM_EN", 0.15, 30),
    ("+3V3", 0.2, 30),
    ("I2C1_SCL", 0.15, 30), ("I2C1_SDA", 0.15, 30), ("PWR_KEY", 0.2, 30), ("PWR_OFF", 0.15, 30),
    ("FLASH_EN", 0.15, 30), ("FLASH_G", 0.15, 30), ("LED_FB", 0.15, 30), ("OVP", 0.15, 30),
    ("BTN_SHUTTER", 0.15, 30), ("LED_STATUS", 0.15, 30), ("LED_ST_A", 0.15, 30),
    ("LED_ACT_N", 0.15, 30), ("LED_ACT_A", 0.15, 30),
    ("CC1", 0.15, 30), ("CC2", 0.15, 30),
    ("UART_TX", 0.15, 30), ("UART_RX", 0.15, 30), ("RUN_PG", 0.15, 30), ("GLOBAL_EN", 0.15, 30),
    ("RPIBOOT_N", 0.15, 30),
]


def N(name):
    """Nom de net réel sur la carte (les étiquettes locales du schéma sont préfixées par « / »)."""
    return name if name in ("GND", "+5V", "+3V3", "+BATT", "VBUS", "") or name.startswith("/") else "/" + name


def main():
    global PINS, P_CM4
    t0 = time.time()
    P_CM4 = {int(k): v for k, v in json.load(open(os.path.join(P.HERE, "cm4_pins.json"))).items()}
    b, comps, PINS = P.build()
    zones(b)
    b.BuildConnectivity()
    r = Router(b)
    df40_gnd(r)
    cm4_5v(r)
    j1_fanout(r)
    csi_bus(r)
    nets_present = {p.GetNetname() for fp in b.GetFootprints() for p in fp.Pads()}
    done = {"GND", "+5V", ""}
    failed = []
    # sorties I2C / KEY de l'IP5306 : via immédiate (sinon VBUS les enferme)
    u2 = b.FindFootprintByReference("U2")
    for pd in u2.Pads():
        if pd.GetNumber() in ("2", "3", "5"):
            log("via de sortie U2." + pd.GetNumber(), r.fanout(pd, pd.GetNetname(), w=0.15, max_len=2.5))
    # masse de la protection USB : via avant de router D+/D- (sinon la broche 2 est enfermée)
    u4 = b.FindFootprintByReference("U4")
    for pd in u4.Pads():
        if pd.GetNumber() == "2":
            log("via de masse U4.2", r.fanout(pd, "GND", w=0.3, max_len=2.0))
    # +5V local (côté IP5306) puis nets de l'éventail J1, du haut vers le bas
    r.route_net("+5V", 0.6, via_cost=200, log=log, refs={"U2", "C8", "C9"})
    fan_nets = []
    for k in range(1, 101):
        n_ = PINS.get(("J1", str(k)))
        if n_ and not n_.startswith("unconnected") and n_ not in ("GND", "+5V", "+3V3") and n_ not in fan_nets:
            fan_nets.append(n_.lstrip("/"))
    widths = {n: (w, vc) for n, w, vc in ORDER}
    order = [o for o in ORDER if o[0] in ("IP_SW", "+BATT", "BST_SW", "VLED", "LED_K", "LED_SENSE", "VBUS")]
    order += [(n, *widths.get(n, (0.15, 30))) for n in fan_nets]
    order += [o for o in ORDER if o not in order]
    for net, w, vc in order:
        if N(net) in done:
            continue
        if N(net) not in nets_present:
            continue
        ok = r.route_net(N(net), w, via_cost=vc, log=log)
        log(f"{net:12s} {'ok' if ok else 'ÉCHEC'}  ({time.time() - t0:.0f} s)")
        done.add(N(net))
        if not ok:
            failed.append(net)
    rest = sorted(n for n in nets_present - done if not n.startswith("unconnected"))
    for net in rest:
        ok = r.route_net(net, 0.15, log=log)
        log(f"{net:12s} {'ok' if ok else 'ÉCHEC'} (reste)")
        if not ok:
            failed.append(net)
    # vias d'alimentation vers les plans
    fan_fail = []
    for fp in b.GetFootprints():
        for p in fp.Pads():
            if p.GetNetname() == "+5V" and fp.GetReference() not in ("J1",):
                if not r.fanout(p, "+5V", w=0.4, max_len=6.0):
                    fan_fail.append(f"{fp.GetReference()}.{p.GetNumber()}")
    log("vias +5V manquantes :", fan_fail)
    pcbnew.SaveBoard(P.PCB, b)
    log(f"routage terminé en {time.time() - t0:.0f} s ; échecs : {failed}")
    return b, r


if __name__ == "__main__":
    main()

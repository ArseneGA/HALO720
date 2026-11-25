#!/usr/bin/env python3
"""HALO 720 v3 - génère le schéma KiCad 7 de la carte porteuse CM4.

Le schéma est décrit ici composant par composant (broche -> net). Le script :
  * écrit la bibliothèque de symboles du projet (CM4 J1/J2, IP5306-I2C) ;
  * place les symboles, tire un petit fil sur chaque broche et y pose une
    étiquette de net, un symbole d'alimentation ou une croix « non connecté » ;
  * écrit halo720_v3.kicad_sch + .kicad_pro + tables de bibliothèques ;
  * écrit la liste des connexions attendue (expected_nets.json) que
    check_netlist.py compare à la netlist exportée par KiCad.

Usage : python3 gen_schematic.py <dossier_projet>
"""
import json
import math
import os
import sys
import uuid as _uuid
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from kicadlib import extract_symbol, pins as lib_pins  # noqa: E402

KLIB = "/usr/share/kicad/symbols/"
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "kicad"))
PROJECT = "halo720_v3"
G = 2.54
NS = _uuid.UUID("7f1b6c2e-4a2d-4c51-9a8e-0d7200000003")


def uid(*parts):
    return str(_uuid.uuid5(NS, "/".join(str(p) for p in parts)))


ROOT_UUID = uid("root")
POWER_SYMS = {"GND": "power:GND", "+5V": "power:+5V", "+3V3": "power:+3V3",
              "VBUS": "power:VBUS", "+BATT": "power:+BATT"}
# sens « naturel » du corps de chaque symbole d'alimentation (vers où il pointe)
POWER_BODY = {"GND": "down"}

# --------------------------------------------------------------------------
# Bibliothèque du projet : connecteurs CM4 (J1 = broches 1-100, J2 = 101-200)
# et IP5306-I2C.
# --------------------------------------------------------------------------
CM4 = {int(k): v for k, v in json.load(open(os.path.join(HERE, "cm4_pins.json"))).items()}


def f(v):
    s = ("%.4f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def sym_cm4(name, first, title):
    """Connecteur DF40 100 broches avec les noms du CM4 (impairs à gauche)."""
    w = 25.4
    top = 63.5
    lines = [f'  (symbol "{name}" (in_bom yes) (on_board yes)',
             f'    (property "Reference" "J" (at 0 {f(top + 5.08)} 0) (effects (font (size 1.27 1.27))))',
             f'    (property "Value" "{name}" (at 0 {f(top + 2.54)} 0) (effects (font (size 1.27 1.27))))',
             '    (property "Footprint" "" (at 0 0 0) (effects (font (size 1.27 1.27)) hide))',
             '    (property "Datasheet" "https://datasheets.raspberrypi.com/cm4/cm4-datasheet.pdf" (at 0 0 0) (effects (font (size 1.27 1.27)) hide))',
             f'    (property "ki_description" "{title}" (at 0 0 0) (effects (font (size 1.27 1.27)) hide))',
             f'    (symbol "{name}_0_1"',
             f'      (rectangle (start {f(-w + G)} {f(top + G)}) (end {f(w - G)} {f(top - 50 * G)})'
             ' (stroke (width 0.254) (type default)) (fill (type background)))',
             '    )',
             f'    (symbol "{name}_1_1"']
    for k in range(100):
        num = first + k
        pname, ptype = CM4[num]
        if num in (84,):           # une seule sortie 3V3 « power_out » pour l'ERC
            ptype = "passive"
        if num in (88,):
            ptype = "passive"
        row = k // 2
        y = top - row * G
        if k % 2 == 0:   # impair -> gauche
            x, a = -w, 0
        else:
            x, a = w, 180
        lines.append(f'      (pin {ptype} line (at {f(x)} {f(y)} {a}) (length 2.54)'
                     f' (name "{pname}" (effects (font (size 1.016 1.016))))'
                     f' (number "{num}" (effects (font (size 1.016 1.016)))))')
    lines += ['    )', '  )']
    return "\n".join(lines)


def sym_ip5306():
    name = "IP5306-I2C"
    pins_ = [  # num, name, type, x, y, angle
        ("1", "VIN", "power_in", -15.24, 7.62, 0),
        ("5", "KEY", "input", -15.24, 2.54, 0),
        ("2", "L1/SCL", "input", -15.24, -2.54, 0),
        ("3", "L2/SDA", "bidirectional", -15.24, -5.08, 0),
        ("4", "L3/IRQ", "output", -15.24, -7.62, 0),
        ("8", "VOUT", "power_out", 15.24, 7.62, 180),
        ("7", "SW", "passive", 15.24, 0, 180),
        ("6", "BAT", "power_in", 15.24, -5.08, 180),
        ("9", "EP/GND", "power_in", 0, -15.24, 90),
    ]
    lines = [f'  (symbol "{name}" (in_bom yes) (on_board yes)',
             '    (property "Reference" "U" (at -10.16 13.97 0) (effects (font (size 1.27 1.27))))',
             f'    (property "Value" "{name}" (at 5.08 13.97 0) (effects (font (size 1.27 1.27))))',
             '    (property "Footprint" "" (at 0 0 0) (effects (font (size 1.27 1.27)) hide))',
             '    (property "Datasheet" "https://www.lcsc.com/product-detail/C488349.html" (at 0 0 0) (effects (font (size 1.27 1.27)) hide))',
             f'    (symbol "{name}_0_1"',
             '      (rectangle (start -12.7 11.43) (end 12.7 -12.7) (stroke (width 0.254) (type default)) (fill (type background)))',
             '    )',
             f'    (symbol "{name}_1_1"']
    for num, pn, t, x, y, a in pins_:
        lines.append(f'      (pin {t} line (at {f(x)} {f(y)} {a}) (length 2.54)'
                     f' (name "{pn}" (effects (font (size 1.27 1.27))))'
                     f' (number "{num}" (effects (font (size 1.27 1.27)))))')
    lines += ['    )', '  )']
    return "\n".join(lines)


PROJECT_SYMS = {
    "CM4_J1": sym_cm4("CM4_J1", 1, "CM4 connecteur J1 (broches 1-100), Hirose DF40C-100DS-0.4V(51)"),
    "CM4_J2": sym_cm4("CM4_J2", 101, "CM4 connecteur J2 (broches 101-200), Hirose DF40C-100DS-0.4V(51)"),
    "IP5306-I2C": sym_ip5306(),
}


def lib_text(lib_id):
    lib, name = lib_id.split(":")
    if lib == "halo720":
        t = PROJECT_SYMS[name]
    else:
        t = extract_symbol(KLIB + lib + ".kicad_sym", name)
    if "(extends" in "\n".join(t.split("\n")[:3]):
        raise ValueError(f"{lib_id} hérite d'un autre symbole")
    first, rest = t.split("\n", 1)
    first = first.replace(f'(symbol "{name}"', f'(symbol "{lib_id}"', 1)
    return first + "\n" + rest


_pin_cache = {}


def lib_pin_list(lib_id):
    if lib_id not in _pin_cache:
        _pin_cache[lib_id] = lib_pins(lib_text(lib_id))
    return _pin_cache[lib_id]


# --------------------------------------------------------------------------
# Géométrie
# --------------------------------------------------------------------------
DIRS = {"right": (1, 0), "left": (-1, 0), "up": (0, -1), "down": (0, 1)}


def rot(x, y, r):
    """Rotation anti-horaire de r degrés dans le repère y-vers-le-haut."""
    c, s = round(math.cos(math.radians(r))), round(math.sin(math.radians(r)))
    return x * c - y * s, x * s + y * c


def to_sch(X, Y, px, py, r):
    x, y = rot(px, py, r)
    return round(X + x, 4), round(Y - y, 4)


def outward(a, r):
    """Direction (écran) qui s'éloigne du corps pour une broche d'angle a."""
    dx, dy = rot(math.cos(math.radians(a + 180)), math.sin(math.radians(a + 180)), r)
    dx, dy = round(dx), round(-dy)
    for k, v in DIRS.items():
        if v == (dx, dy):
            return k
    raise ValueError((a, r))


# rotation (anti-horaire, degrés) pour qu'un symbole dont le corps pointe vers
# `base` pointe vers `want`
ORDER = ["up", "left", "down", "right"]


def power_rot(base, want):
    return ((ORDER.index(want) - ORDER.index(base)) % 4) * 90


# --------------------------------------------------------------------------
# Construction du schéma
# --------------------------------------------------------------------------
class Sch:
    def __init__(self, project=PROJECT):
        self.project = project
        self.root = ROOT_UUID if project == PROJECT else uid("root", project)
        self.items = []
        self.libs = set()
        self.expected = {}     # (ref, pin) -> net
        self.pwr_n = 0
        self.flags = 0
        self.parts = []        # pour la BOM

    def text(self, s, x, y, size=1.27, bold=False):
        b = " bold" if bold else ""
        s = s.replace('"', "'")
        self.items.append(f'  (text "{s}" (at {f(x)} {f(y)} 0) (effects (font (size {size} {size}){b}) (justify left bottom)) (uuid {uid("t", x, y, s)}))')

    def wire(self, x1, y1, x2, y2):
        self.items.append(f'  (wire (pts (xy {f(x1)} {f(y1)}) (xy {f(x2)} {f(y2)})) (stroke (width 0) (type default)) (uuid {uid("w", x1, y1, x2, y2)}))')

    def label(self, net, x, y, d):
        ang = {"right": 0, "up": 90, "left": 180, "down": 270}[d]
        just = "left" if d in ("right", "up") else "right"
        self.items.append(f'  (label "{net}" (at {f(x)} {f(y)} {ang}) (fields_autoplaced yes) (effects (font (size 1.27 1.27)) (justify {just} bottom)) (uuid {uid("l", net, x, y)}))')

    def noconnect(self, x, y):
        self.items.append(f'  (no_connect (at {f(x)} {f(y)}) (uuid {uid("nc", x, y)}))')

    def _symbol(self, lib_id, ref, value, x, y, r, unit=1, fp="", props=None, in_bom=True, hide_ref=False, ref_at=None, val_at=None, val_angle=None, hide_val=False):
        self.libs.add(lib_id)
        props = props or {}
        ra = ref_at or (x, y - 6)
        va = val_at or (x, y + 6)
        hr = " hide" if hide_ref else ""
        fa = 90 if r in (90, 270) else 0
        if val_angle is None:
            val_angle = fa
        out = [f'  (symbol (lib_id "{lib_id}") (at {f(x)} {f(y)} {r}) (unit {unit})',
               f'    (in_bom {"yes" if in_bom else "no"}) (on_board yes) (dnp no)',
               f'    (uuid {uid("s", ref, unit)})',
               f'    (property "Reference" "{ref}" (at {f(ra[0])} {f(ra[1])} {fa}) (effects (font (size 1.27 1.27)){hr}))',
               f'    (property "Value" "{value}" (at {f(va[0])} {f(va[1])} {val_angle}) (effects (font (size 1.27 1.27)){" hide" if hide_val else ""}))',
               f'    (property "Footprint" "{fp}" (at {f(x)} {f(y)} 0) (effects (font (size 1.27 1.27)) hide))',
               f'    (property "Datasheet" "" (at {f(x)} {f(y)} 0) (effects (font (size 1.27 1.27)) hide))']
        for k, v in props.items():
            out.append(f'    (property "{k}" "{v}" (at {f(x)} {f(y)} 0) (effects (font (size 1.27 1.27)) hide))')
        seen = set()
        for p in lib_pin_list(lib_id):
            if p["num"] in seen:
                continue
            seen.add(p["num"])
            out.append(f'    (pin "{p["num"]}" (uuid {uid("p", ref, unit, p["num"])}))')
        out.append(f'    (instances (project "{self.project}" (path "/{self.root}" (reference "{ref}") (unit {unit}))))')
        out.append('  )')
        self.items.append("\n".join(out))

    def power(self, net, x, y, d):
        self.pwr_n += 1
        lib = POWER_SYMS[net]
        r = power_rot(POWER_BODY.get(net, "up"), d)
        dx, dy = DIRS[d]
        # le texte du symbole se place un peu au-delà du corps
        ta = (x + dx * 5.5, y + dy * 5.5 - (0.635 if dx else 0))
        self._symbol(lib, f"#PWR{self.pwr_n:03d}", net, x, y, r, in_bom=False, hide_ref=True,
                     ref_at=(x, y), val_at=ta, val_angle=90 if dx else 0, hide_val=(net == "GND" and dx != 0))

    def flag(self, net, x, y):
        """PWR_FLAG + fil + symbole d'alimentation (pour l'ERC)."""
        self.flags += 1
        self._symbol("power:PWR_FLAG", f"#FLG{self.flags:02d}", "PWR_FLAG", x, y, 0, in_bom=False,
                     hide_ref=True, ref_at=(x, y), val_at=(x, y - 3.5))
        self.wire(x, y, x, y + G)
        self.power(net, x, y + G, "down")

    def part(self, ref, lib_id, value, x, y, nets, r=0, fp="", lcsc="", mpn="", note="", stub=G,
             ref_at=None, val_at=None, bom=True):
        """Place un composant ; nets = {numéro de broche: net | None (NC)}."""
        props = {}
        if lcsc:
            props["LCSC"] = lcsc
        if mpn:
            props["MPN"] = mpn
        self._symbol(lib_id, ref, value, x, y, r, fp=fp, props=props, ref_at=ref_at, val_at=val_at,
                     in_bom=bom)
        if bom:
            self.parts.append(dict(ref=ref, value=value, fp=fp, lcsc=lcsc, mpn=mpn, note=note))
        done = {}
        pl = lib_pin_list(lib_id)
        nums = {p["num"] for p in pl}
        missing = set(nets) - nums
        if missing:
            raise KeyError(f"{ref}: broches inconnues {missing}")
        for p in pl:
            num = p["num"]
            if p["type"] == "no_connect":
                continue
            if num not in nets:
                raise KeyError(f"{ref}: broche {num} ({p['name']}) non décrite")
            net = nets[num]
            px, py = to_sch(x, y, p["x"], p["y"], r)
            key = (px, py)
            if key in done:
                if done[key] != net:
                    raise ValueError(f"{ref}.{num}: broches empilées sur des nets différents")
                self.expected[(ref, num)] = net
                continue
            done[key] = net
            self.expected[(ref, num)] = net
            if net is None:
                self.noconnect(px, py)
                continue
            d = outward(p["a"], r)
            dx, dy = DIRS[d]
            ex, ey = round(px + dx * stub, 4), round(py + dy * stub, 4)
            self.wire(px, py, ex, ey)
            if net in POWER_SYMS:
                self.power(net, ex, ey, d)
            else:
                self.label(net, ex, ey, d)

    paper = "A2"
    comment1 = "Carte porteuse Raspberry Pi CM4 Wi-Fi eMMC - fabrication et assemblage complets en usine"

    def write(self, path, title, rev):
        libs = "\n".join(lib_text(l) for l in sorted(self.libs))
        today = date.today().isoformat()
        body = "\n".join(self.items)
        txt = f"""(kicad_sch (version 20250114) (generator "eeschema") (generator_version "9.0")

  (uuid {self.root})

  (paper "{self.paper}")

  (title_block
    (title "{title}")
    (date "{today}")
    (rev "{rev}")
    (company "HALO 720 - Arsène Galliez")
    (comment 1 "{self.comment1}")
    (comment 2 "Généré par hardware/v3/tools/gen_schematic.py")
  )

  (lib_symbols
{libs}
  )

{body}

  (sheet_instances
    (path "/" (page "1"))
  )
)
"""
        open(path, "w", encoding="utf-8").write(txt)


# --------------------------------------------------------------------------
# Références LCSC (vérifiées sur jlcpcb.com / lcsc.com le 25/11/2026 ;
# « B » = Basic, « E » = Extended chez JLCPCB)
# --------------------------------------------------------------------------
FP_R = "Resistor_SMD:R_0402_1005Metric"
FP_C = "Capacitor_SMD:C_0402_1005Metric"
FP_C06 = "Capacitor_SMD:C_0603_1608Metric"
FP_C08 = "Capacitor_SMD:C_0805_2012Metric"
R_LCSC = {"10k": "C25744", "100k": "C25741", "1k": "C11702", "5.1k": "C25905", "2.2k": "C25879",
          "47k": "C25792", "100": "C25076", "330": "C25104"}
C100N = ("100nF", FP_C, "C1525")
C1U = ("1uF", FP_C, "C52923")
C10U = ("10uF", FP_C06, "C19702")
C22U = ("22uF 25V", FP_C08, "C45783")


def build():
    s = Sch()
    R = "Device:R"
    C = "Device:C"

    def res(ref, val, x, y, a, b, r=0, note="", lcsc=None, fp=FP_R, mpn=""):
        if r == 0:
            ra, va = (x + 2.54, y - 1.27), (x + 2.54, y + 1.27)
        else:
            ra, va = (x, y - 2.54), (x, y + 2.54)
        s.part(ref, R, val, x, y, {"1": a, "2": b}, r=r, fp=fp, lcsc=lcsc or R_LCSC[val], note=note,
               mpn=mpn, ref_at=ra, val_at=va)

    def cap(ref, spec, x, y, a, b="GND"):
        val, fp, lc = spec
        s.part(ref, C, val, x, y, {"1": a, "2": b}, fp=fp, lcsc=lc,
               ref_at=(x + 2.54, y - 1.27), val_at=(x + 2.54, y + 1.27))

    def title(n, txt, x, y, sub=None):
        s.text(f"{n} - {txt}", x, y, 2.54, True)
        if sub:
            for i, l in enumerate(sub):
                s.text(l, x, y + 5.08 + i * 3.81)

    # ================= Bloc 1 : CM4 =================
    title(1, "Compute Module 4 Wi-Fi eMMC 16 Go", 17.78, 25.4,
          ["CM4101016 clipsé en usine sur 2 x Hirose DF40C-100DS-0.4V(51) (hauteur 1,5 mm, comme la carte CM4 IO officielle) : rien sous le CM4 côté dessus.",
           "Pas de cuivre sous l'antenne du CM4. GPIO_VREF = 3,3 V."])
    j1 = {}
    for n in range(1, 101):
        name, _ = CM4[n]
        j1[str(n)] = "GND" if name == "GND" else None
    j1.update({
        "77": "+5V", "79": "+5V", "81": "+5V", "83": "+5V", "85": "+5V", "87": "+5V",
        "84": "+3V3", "86": "+3V3", "78": "+3V3",
        "21": "LED_ACT_N",
        "46": "FLASH_EN",       # GPIO22 (comme la v2)
        "47": "PWR_OFF",        # GPIO23 -> appui KEY de l'IP5306 (extinction)
        "48": "LED_STATUS",     # GPIO27 (comme la v2)
        "50": "BTN_SHUTTER",    # GPIO17 (comme la v2)
        "56": "I2C1_SCL",       # GPIO3
        "58": "I2C1_SDA",       # GPIO2
        "55": "UART_TX",        # GPIO14
        "51": "UART_RX",        # GPIO15
        "80": "CAM_SCL", "82": "CAM_SDA", "97": "CAM_EN",
        "92": "RUN_PG", "93": "RPIBOOT_N", "99": "GLOBAL_EN",
    })
    s.part("J1", "halo720:CM4_J1", "DF40C-100DS-0.4V(51)", 80.01, 110.49, j1,
           fp="halo720:Hirose_DF40HC3.0-100DS_CM4_J1", lcsc="C597931", mpn="DF40C-100DS-0.4V(51)",
           note="CM4 broches 1-100", ref_at=(80.01, 41.91), val_at=(80.01, 44.45))
    j2 = {}
    for n in range(101, 201):
        name, _ = CM4[n]
        j2[str(n)] = "GND" if name == "GND" else None
    j2.update({
        "103": "USB_DN", "105": "USB_DP",
        "115": "CAM_D0_N", "117": "CAM_D0_P", "121": "CAM_D1_N", "123": "CAM_D1_P",
        "127": "CAM_C_N", "129": "CAM_C_P", "133": "CAM_D2_N", "135": "CAM_D2_P",
        "139": "CAM_D3_N", "141": "CAM_D3_P",
    })
    s.part("J2", "halo720:CM4_J2", "DF40C-100DS-0.4V(51)", 80.01, 285.75, j2,
           fp="halo720:Hirose_DF40HC3.0-100DS_CM4_J2", lcsc="C597931", mpn="DF40C-100DS-0.4V(51)",
           note="CM4 broches 101-200", ref_at=(80.01, 217.17), val_at=(80.01, 219.71))

    # ================= Bloc 2 : USB-C + IP5306 =================
    ox, oy = 160.02, 25.4
    title(2, "USB-C + charge LiPo et 5 V (IP5306-I2C)", ox, oy,
          ["VBUS ne va qu'à l'IP5306. D+/D- vont à l'USB 2.0 du CM4 (périphérique : rpiboot, USB-Ethernet).",
           "IP5306 : I2C adresse 0x75 (tirages 2,2k à 3,3 V), sortie 5 V / 2,4 A max, charge 2,1 A max."])
    usb = {"A1": "GND", "A12": "GND", "B1": "GND", "B12": "GND", "S1": "GND",
           "A4": "VBUS", "A9": "VBUS", "B4": "VBUS", "B9": "VBUS",
           "A5": "CC1", "B5": "CC2", "A6": "USB_DP", "B6": "USB_DP", "A7": "USB_DN", "B7": "USB_DN",
           "A8": None, "B8": None}
    s.part("J5", "Connector:USB_C_Receptacle_USB2.0_16P", "TYPE-C-31-M-12", ox + 15.24, oy + 45.72, usb,
           fp="Connector_USB:USB_C_Receptacle_HRO_TYPE-C-31-M-12", lcsc="C165948", mpn="TYPE-C-31-M-12",
           ref_at=(ox + 15.24, oy + 26.67), val_at=(ox + 15.24, oy + 29.21))
    s.part("U4", "Power_Protection:USBLC6-2P6", "USBLC6-2SC6", ox + 20.32, oy + 91.44,
           {"1": "USB_DP", "6": "USB_DP", "3": "USB_DN", "4": "USB_DN", "5": "VBUS", "2": "GND"},
           fp="Package_TO_SOT_SMD:SOT-23-6", lcsc="C7519", mpn="USBLC6-2SC6",
           ref_at=(ox + 27.94, oy + 85.09), val_at=(ox + 27.94, oy + 87.63))
    res("R1", "5.1k", ox + 50.8, oy + 91.44, "CC1", "GND")
    res("R2", "5.1k", ox + 63.5, oy + 91.44, "CC2", "GND")
    cap("C1", C10U, ox + 76.2, oy + 91.44, "VBUS")

    ux, uy = ox + 116.84, oy + 45.72
    s.part("U2", "halo720:IP5306-I2C", "IP5306-I2C", ux, uy,
           {"1": "VBUS", "5": "PWR_KEY", "2": "I2C1_SCL", "3": "I2C1_SDA", "4": None,
            "8": "+5V", "7": "IP_SW", "6": "+BATT", "9": "GND"},
           fp="Package_SO:SOIC-8-1EP_3.9x4.9mm_P1.27mm_EP2.41x3.3mm_ThermalVias", lcsc="C488349",
           mpn="IP5306-I2C", note="ESOP-8, pastille centrale à la masse (vias thermiques)",
           ref_at=(ux - 7.62, uy - 13.97), val_at=(ux + 5.08, uy - 13.97))
    s.part("L1", "Device:L", "1uH", ux + 30.48, uy + 3.81, {"1": "IP_SW", "2": "+BATT"},
           fp="Inductor_SMD:L_Changjiang_FNR6045S", lcsc="C135299", mpn="SMNR6045-1R0M",
           note="6x6 mm, Isat 9,85 A", ref_at=(ux + 33.02, uy + 2.54), val_at=(ux + 33.02, uy + 5.08))
    cap("C2", C10U, ux - 27.94, uy + 30.48, "VBUS")
    cap("C3", C100N, ux - 15.24, uy + 30.48, "VBUS")
    cap("C4", C22U, ux + 40.64, uy + 30.48, "+BATT")
    cap("C5", C22U, ux + 53.34, uy + 30.48, "+BATT")
    res("R3", "2.2k", ux - 53.34, uy - 2.54, "+3V3", "I2C1_SCL")
    res("R4", "2.2k", ux - 43.18, uy - 2.54, "+3V3", "I2C1_SDA")
    for i, ref in enumerate(["C6", "C7", "C8"]):
        cap(ref, C22U, ux - 12.7 + i * 12.7, uy + 63.5, "+5V")
    cap("C9", C100N, ux + 25.4, uy + 63.5, "+5V")
    s.part("J6", "Connector_Generic:Conn_01x02", "LiPo 1S", ux + 53.34, uy + 63.5, {"1": "+BATT", "2": "GND"},
           fp="Connector_JST:JST_PH_S2B-PH-SM4-TB_1x02-1MP_P2.00mm_Horizontal", lcsc="C295747",
           mpn="S2B-PH-SM4-TB(LF)(SN)", note="vérifier la polarité du câble de la batterie",
           ref_at=(ux + 55.88, uy + 58.42), val_at=(ux + 55.88, uy + 68.58))
    s.text("C6-C9 : au plus près des broches +5V du CM4.", ux - 15, uy + 78.74)
    s.text("J6 batterie : 1 = + , 2 = -  (sens des câbles JST-PH variable : vérifier !)", ux - 15, uy + 82.55)

    # drapeaux d'alimentation (ERC)
    s.flag("VBUS", ox + 40.64, oy + 109.22)
    s.flag("GND", ox + 53.34, oy + 109.22)
    s.flag("+BATT", ox + 66.04, oy + 109.22)

    # bouton marche + extinction logicielle
    kx, ky = ox, oy + 134.62
    s.text("Marche : appui court sur SW2. Arrêt : en fin d'extinction, le CM4 envoie 2 appuis sur KEY (GPIO23 -> Q1).", kx, ky - 7.62)
    s.part("SW2", "Switch:SW_Push", "MARCHE", kx + 15.24, ky + 5.08, {"1": "PWR_KEY", "2": "GND"},
           fp="Button_Switch_SMD:SW_Push_1P1T_XKB_TS-1187A", lcsc="C318884", mpn="TS-1187A-B-A-B",
           ref_at=(kx + 15.24, ky + 1.27), val_at=(kx + 15.24, ky + 8.89))
    s.part("Q1", "Transistor_FET:Q_NMOS_GSD", "2N7002", kx + 63.5, ky + 5.08,
           {"1": "PWR_OFF", "2": "GND", "3": "PWR_KEY"},
           fp="Package_TO_SOT_SMD:SOT-23", lcsc="C8545", mpn="2N7002",
           ref_at=(kx + 68.58, ky + 3.81), val_at=(kx + 68.58, ky + 6.35))
    res("R5", "100k", kx + 43.18, ky + 7.62, "PWR_OFF", "GND")

    # ================= Bloc 3 : flash IR =================
    fx, fy = 160.02, 182.88
    title(3, "Flash IR 850 nm (MT3608 en courant constant)", fx, fy,
          ["GPIO22 (FLASH_EN) allume le boost ET le MOSFET Q2 : au repos la LED est coupée (aucune fuite).",
           "Courant : I = 0,6 V / R10 = 0,88 A dans 2 LED OSRAM SFH 4715AS en série (carte flash alu)."])
    s.part("U3", "Regulator_Switching:MT3608", "MT3608", fx + 50.8, fy + 33.02,
           {"1": "BST_SW", "2": "GND", "3": "LED_FB", "4": "FLASH_EN", "5": "+BATT"},
           fp="Package_TO_SOT_SMD:SOT-23-6", lcsc="C84817", mpn="MT3608",
           ref_at=(fx + 50.8, fy + 25.4), val_at=(fx + 50.8, fy + 43.18))
    s.part("L2", "Device:L", "4.7uH", fx + 50.8, fy + 17.78, {"1": "+BATT", "2": "BST_SW"}, r=270,
           fp="Inductor_SMD:L_Sunlord_MWSA0518S", lcsc="C408347", mpn="MWSA0518S-4R7MT",
           note="Isat 4 A", ref_at=(fx + 50.8, fy + 15.24), val_at=(fx + 50.8, fy + 20.32))
    s.part("D1", "Device:D_Schottky", "SS34", fx + 88.9, fy + 30.48, {"1": "VLED", "2": "BST_SW"}, r=180,
           fp="Diode_SMD:D_SMA", lcsc="C8678", mpn="SS34",
           ref_at=(fx + 88.9, fy + 26.67), val_at=(fx + 88.9, fy + 34.29))
    cap("C10", C22U, fx + 7.62, fy + 58.42, "+BATT")
    cap("C11", C22U, fx + 20.32, fy + 58.42, "+BATT")
    res("R9", "100k", fx + 33.02, fy + 58.42, "FLASH_EN", "GND")
    cap("C12", C22U, fx + 111.76, fy + 30.48, "VLED")
    cap("C13", C22U, fx + 124.46, fy + 30.48, "VLED")
    s.part("J7", "Connector_Generic:Conn_01x02", "CARTE FLASH", fx + 142.24, fy + 30.48, {"1": "VLED", "2": "LED_K"},
           fp="Connector_JST:JST_PH_S2B-PH-SM4-TB_1x02-1MP_P2.00mm_Horizontal", lcsc="C295747",
           mpn="S2B-PH-SM4-TB(LF)(SN)", note="câble JST-PH 2 fils vers la carte flash",
           ref_at=(fx + 144.78, fy + 25.4), val_at=(fx + 144.78, fy + 35.56))
    s.text("J7 -> carte flash (2 LED OSRAM en série)", fx + 125, fy + 41.91)
    s.part("Q2", "Transistor_FET:Q_NMOS_GSD", "AO3400A", fx + 152.4, fy + 63.5,
           {"1": "FLASH_G", "2": "LED_SENSE", "3": "LED_K"},
           fp="Package_TO_SOT_SMD:SOT-23", lcsc="C20917", mpn="AO3400A",
           ref_at=(fx + 157.48, fy + 62.23), val_at=(fx + 157.48, fy + 64.77))
    res("R8", "100", fx + 111.76, fy + 63.5, "FLASH_EN", "FLASH_G", r=90)
    res("R10", "0.68", fx + 154.94, fy + 101.6, "LED_SENSE", "GND", fp="Resistor_SMD:R_2512_6332Metric",
        lcsc="C705617", mpn="RL2512FK-070R68L", note="2512 1 W - 0,52 W pendant le flash")
    s.text("R10 : 2512, 1 W", fx + 158.75, fy + 107.95)
    res("R11", "1k", fx + 76.2, fy + 63.5, "LED_SENSE", "LED_FB", r=90)
    res("R12", "1k", fx + 76.2, fy + 78.74, "LED_FB", "OVP", r=90)
    s.part("D2", "Device:D_Zener", "10V", fx + 104.14, fy + 78.74, {"1": "VLED", "2": "OVP"}, r=180,
           fp="Diode_SMD:D_SOD-123", lcsc="C2103", mpn="BZT52C10",
           ref_at=(fx + 104.14, fy + 74.93), val_at=(fx + 104.14, fy + 82.55))
    s.text("D2 + R12 : LED débranchée -> tension limitée vers 11 V.", fx + 60, fy + 90.17)
    s.text("Torche limitée à 30 s par le logiciel.", fx + 60, fy + 93.98)

    # ================= Bloc 4 : caméra =================
    cx, cy = 345.44, 25.4
    title(4, "Caméra (FPC 22 broches 0,5 mm, CAM1)", cx, cy,
          ["Camera Module 3 NoIR, nappe 22->15 broches (type Pi Zero).",
           "I2C caméra = SDA0/SCL0 (GPIO44/45, tirage 1,8k dans le CM4)."])
    cam = {"1": "GND", "2": "CAM_D0_N", "3": "CAM_D0_P", "4": "GND", "5": "CAM_D1_N", "6": "CAM_D1_P",
           "7": "GND", "8": "CAM_C_N", "9": "CAM_C_P", "10": "GND", "11": "CAM_D2_N", "12": "CAM_D2_P",
           "13": "GND", "14": "CAM_D3_N", "15": "CAM_D3_P", "16": "GND", "17": "CAM_EN", "18": None,
           "19": "GND", "20": "CAM_SCL", "21": "CAM_SDA", "22": "+3V3", "MP": "GND"}
    s.part("J3", "Connector_Generic_MountingPin:Conn_01x22_MountingPin", "FH12-22S-0.5SH(55)", cx + 25.4, cy + 50.8, cam,
           fp="Connector_FFC-FPC:Hirose_FH12-22S-0.5SH_1x22-1MP_P0.50mm_Horizontal", lcsc="C597981", mpn="FH12-22S-0.5SH(55)",
           ref_at=(cx + 27.94, cy + 19.05), val_at=(cx + 27.94, cy + 21.59))
    cap("C14", C10U, cx + 45.72, cy + 50.8, "+3V3")
    cap("C15", C100N, cx + 58.42, cy + 50.8, "+3V3")

    # ================= Bloc 5 : mémoire + programmation =================
    sx, sy = 345.44, 129.54
    title(5, "Mémoire eMMC et programmation en usine", sx, sy,
          ["CM4 avec eMMC 16 Go intégrée : pas de carte microSD (bus SD du CM4 non utilisé).",
           "Usine : ponter JP1 (BOOT) à la pince, brancher l'USB-C, rpiboot puis copie de l'image HALO."])
    s.part("JP1", "Jumper:SolderJumper_2_Open", "BOOT", sx + 35.56, sy + 25.4, {"1": "RPIBOOT_N", "2": "GND"},
           fp="Jumper:SolderJumper-2_P1.3mm_Open_RoundedPad1.0x1.5mm", bom=False,
           ref_at=(sx + 35.56, sy + 21.59), val_at=(sx + 35.56, sy + 29.21))

    # ================= Bloc 6 : interface =================
    ix, iy = 452.12, 25.4
    title(6, "Interface (brochage de la v2)", ix, iy,
          ["Déclencheur GPIO17 (appui long 3 s = extinction), LED GPIO27, flash GPIO22.",
           "Boutons et LED soudés sur la carte : le boîtier n'a que des poussoirs et une fenêtre."])
    s.part("SW1", "Switch:SW_Push", "DÉCLENCHEUR", ix + 76.2, iy + 30.48, {"1": "BTN_SHUTTER", "2": "GND"},
           fp="Button_Switch_SMD:SW_Push_1P1T_XKB_TS-1187A", lcsc="C318884", mpn="TS-1187A-B-A-B",
           ref_at=(ix + 76.2, iy + 26.67), val_at=(ix + 76.2, iy + 34.29))
    res("R18", "10k", ix + 104.14, iy + 30.48, "+3V3", "BTN_SHUTTER")
    s.part("D3", "Device:LED", "VERTE", ix + 63.5, iy + 58.42, {"1": "GND", "2": "LED_ST_A"}, r=180,
           fp="LED_SMD:LED_0603_1608Metric", lcsc="C72043", mpn="KT-0603G",
           ref_at=(ix + 63.5, iy + 54.61), val_at=(ix + 63.5, iy + 62.23))
    res("R19", "1k", ix + 15.24, iy + 58.42, "LED_STATUS", "LED_ST_A", r=90)
    s.part("D4", "Device:LED", "ROUGE (ACT)", ix + 63.5, iy + 88.9, {"1": "LED_ACT_N", "2": "LED_ACT_A"}, r=180,
           fp="LED_SMD:LED_0603_1608Metric", lcsc="C2286", mpn="KT-0603R",
           ref_at=(ix + 63.5, iy + 85.09), val_at=(ix + 63.5, iy + 92.71))
    res("R21", "1k", ix + 15.24, iy + 88.9, "+3V3", "LED_ACT_A", r=90)

    # ================= Bloc 7 : test / flashage =================
    tx, ty = 452.12, 129.54
    title(7, "Points de test", tx, ty,
          ["Points de test pour le contrôle en usine (tensions, UART).",
           "H1-H4 : trous M2,5 alignés sur ceux du CM4 (entretoises 1,5 mm)."])
    tps = [("TP1", "UART_TX"), ("TP2", "UART_RX"), ("TP3", "RUN_PG"), ("TP4", "GLOBAL_EN"),
           ("TP5", "RPIBOOT_N"), ("TP6", "+5V"), ("TP7", "+BATT"), ("TP8", "GND"), ("TP9", "VLED"), ("TP10", "+3V3")]
    for i, (ref, net) in enumerate(tps):
        xx = tx + 5.08 + (i % 5) * 20.32
        yy = ty + 22.86 + (i // 5) * 27.94
        s.part(ref, "Connector:TestPoint", net, xx, yy, {"1": net},
               fp="TestPoint:TestPoint_Pad_D1.0mm", bom=False,
               ref_at=(xx + 1.27, yy - 3.81), val_at=(xx + 1.27, yy - 6.35))
    for i in range(4):
        hx = tx + 50.8 + i * 12.7
        s._symbol("Mechanical:MountingHole", f"H{i + 1}", "M2.5", hx, ty + 81.28, 0,
                  fp="MountingHole:MountingHole_2.7mm_M2.5", in_bom=False,
                  ref_at=(hx, ty + 77.47), val_at=(hx, ty + 85.09))

    # ================= Notes =================
    nx, ny = 345.44, 248.92
    notes = [
        "NOTES DE CONCEPTION",
        "- CM4 Wi-Fi 1 Go eMMC 16 Go (CM4101016), fourni et clipsé par l'usine. Wi-Fi 2,4 / 5 GHz : point d'accès en 5 GHz.",
        "- Conso CM4 : ~0,4 A au repos, jusqu'à 1,4 A (datasheet). IP5306 : 5 V / 2,4 A max.",
        "- Pic batterie (CM4 + flash) : ~2,6 A + ~1,8 A. LiPo 1S >= 2000 mAh avec protection (PCM) >= 5 A.",
        "- Charge IP5306 jusqu'à 2,1 A par défaut : la réduire en I2C au démarrage, LiPo acceptant >= 1C.",
        "- USB-C branché : l'IP5306 alimente aussi le 5 V, l'appareil reste allumé pendant la charge.",
        "- Logiciel : dtoverlay=imx708 (CAM1), dtoverlay=dwc2,dr_mode=peripheral (USB gadget).",
        "- GPIO libres : 4-13, 16, 18-21, 24-26.",
        "- Empreintes DF40HC : pastilles de l'empreinte officielle CM4IO (Raspberry Pi), motif Hirose 0,2 x 0,7 mm.",
        "- Usine : CM4 clipsé, eMMC programmée, caméra et carte flash branchées, test. Batterie branchée par l'utilisateur.",
    ]
    for i, tx_ in enumerate(notes):
        s.text(tx_, nx, ny + i * 5.08, 2.0 if i == 0 else 1.5, i == 0)
    return s


def build_flash():
    s = Sch("halo720_v3_flash")
    s.paper = "A4"
    s.comment1 = "Carte flash IR sur aluminium (MCPCB 1 couche) - fabrication et assemblage en usine"
    s.text("Carte flash IR 850 nm - aluminium 1 couche, 1,6 mm", 25.4, 25.4, 2.54, True)
    for i, l in enumerate([
            "2 LED OSRAM SFH 4715AS (OSLON Black, 850 nm, double jonction) en série, 0,88 A fixés par la carte principale.",
            "Vf typique 2,9 V par LED à 1 A (max 3,4 V) : environ 6 V au total.",
            "Flux typique 1,53 W par LED à 1 A (datasheet OSRAM), soit plus que la LED 10 W de la v2.",
            "Pastille thermique des LED sur le cuivre de la carte alu (vérifier sur la datasheet qu'elle est isolée).",
            "Câble JST-PH 2 fils vers J7 de la carte principale, fourni et branché par l'usine."]):
        s.text(l, 25.4, 31.75 + i * 3.81)
    s.part("J1", "Connector_Generic:Conn_01x02", "VERS CARTE CM4", 45.72, 76.2, {"1": "LED_A", "2": "LED_K"},
           fp="Connector_JST:JST_PH_S2B-PH-SM4-TB_1x02-1MP_P2.00mm_Horizontal", lcsc="C295747",
           mpn="S2B-PH-SM4-TB(LF)(SN)", ref_at=(48.26, 71.12), val_at=(48.26, 81.28))
    s.part("D1", "Device:LED", "SFH 4715AS", 76.2, 76.2, {"1": "LED_MID", "2": "LED_A"}, r=180,
           fp="halo720:OSRAM_OSLON_Black_SFH4715AS", lcsc="C2880156", mpn="SFH 4715AS",
           note="IR 850 nm, OSLON Black", ref_at=(76.2, 72.39), val_at=(76.2, 80.01))
    s.part("D2", "Device:LED", "SFH 4715AS", 116.84, 76.2, {"1": "LED_K", "2": "LED_MID"}, r=180,
           fp="halo720:OSRAM_OSLON_Black_SFH4715AS", lcsc="C2880156", mpn="SFH 4715AS",
           note="IR 850 nm, OSLON Black", ref_at=(116.84, 72.39), val_at=(116.84, 80.01))
    for i in range(2):
        hx = 147.32 + i * 12.7
        s._symbol("Mechanical:MountingHole", f"H{i + 1}", "M2", hx, 76.2, 0,
                  fp="MountingHole:MountingHole_2.2mm_M2", in_bom=False,
                  ref_at=(hx, 72.39), val_at=(hx, 80.01))
    return s


def write_project(s, out=None, title="HALO 720 v3 - carte porteuse CM4", tag=""):
    out = out or OUT
    project = s.project
    OUTD = out
    os.makedirs(OUTD, exist_ok=True)
    s.write(os.path.join(OUTD, project + ".kicad_sch"), title, "A")
    lib = "(kicad_symbol_lib (version 20220914) (generator halo720)\n" + \
          "\n".join(PROJECT_SYMS[k] for k in sorted(PROJECT_SYMS)) + "\n)\n"
    open(os.path.join(OUTD, "halo720.kicad_sym"), "w", encoding="utf-8").write(lib)
    open(os.path.join(OUTD, "sym-lib-table"), "w").write(
        '(sym_lib_table\n  (lib (name "halo720")(type "KiCad")(uri "${KIPRJMOD}/halo720.kicad_sym")(options "")(descr "HALO 720 v3"))\n)\n')
    open(os.path.join(OUTD, "fp-lib-table"), "w").write(
        '(fp_lib_table\n  (lib (name "halo720")(type "KiCad")(uri "${KIPRJMOD}/halo720.pretty")(options "")(descr "HALO 720 v3"))\n)\n')
    os.makedirs(os.path.join(OUTD, "halo720.pretty"), exist_ok=True)
    pro = {"meta": {"filename": project + ".kicad_pro", "version": 1},
           "schematic": {"legacy_lib_dir": "", "legacy_lib_list": []},
           "sheets": [[s.root, ""]], "boards": [], "libraries": {"pinned_footprint_libs": [], "pinned_symbol_libs": []},
           "text_variables": {}}
    pro_path = os.path.join(OUTD, project + ".kicad_pro")
    if not os.path.exists(pro_path):   # ne jamais écraser : il contient les règles de la carte (DRC)
        json.dump(pro, open(pro_path, "w"), indent=2)
    exp = {f"{r}.{p}": n for (r, p), n in s.expected.items()}
    json.dump(exp, open(os.path.join(HERE, f"expected_nets{tag}.json"), "w"), indent=0, sort_keys=True)
    json.dump(s.parts, open(os.path.join(HERE, f"parts{tag}.json"), "w"), indent=1, ensure_ascii=False)
    print("schéma écrit :", OUTD, "-", len(s.parts), "composants BOM,", len(exp), "broches")


if __name__ == "__main__":
    write_project(build())
    write_project(build_flash(), os.path.join(OUT, "..", "kicad_flash"), "HALO 720 v3 - carte flash IR", "_flash")

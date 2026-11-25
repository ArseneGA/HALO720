#!/usr/bin/env python3
"""Compare la netlist exportée par KiCad au câblage décrit dans gen_schematic.py."""
import json, os, subprocess, sys, tempfile
import xml.etree.ElementTree as ET
HERE = os.path.dirname(os.path.abspath(__file__))
FLASH = len(sys.argv) > 1 and sys.argv[1] == "flash"
sch = os.path.join(HERE, "..", "kicad_flash" if FLASH else "kicad", "halo720_v3_flash.kicad_sch" if FLASH else "halo720_v3.kicad_sch")
xml = os.path.join(tempfile.gettempdir(), "halo_net.xml")
subprocess.run(["kicad-cli", "sch", "export", "netlist", "--format", "kicadxml", "-o", xml, sch], check=True, capture_output=True)
exp = json.load(open(os.path.join(HERE, "expected_nets_flash.json" if FLASH else "expected_nets.json")))
got = {}
for net in ET.parse(xml).getroot().find("nets"):
    name = net.get("name").lstrip("/")
    for node in net.findall("node"):
        got[f'{node.get("ref")}.{node.get("pin")}'] = name
errs = []
groups = {}
for key, want in exp.items():
    have = got.get(key)
    if have is None:
        errs.append(f"{key}: absent de la netlist"); continue
    if want is None:
        if not have.startswith("unconnected-"):
            errs.append(f"{key}: devrait être NC, est sur {have}")
        continue
    if have != want:
        errs.append(f"{key}: attendu {want}, obtenu {have}")
    groups.setdefault(want, set()).add(have)
# un net attendu = un seul net KiCad, et inversement
inv = {}
for want, haves in groups.items():
    if len(haves) > 1:
        errs.append(f"net {want} coupé en {haves}")
    for h in haves:
        inv.setdefault(h, set()).add(want)
for h, wants in inv.items():
    if len(wants) > 1:
        errs.append(f"nets fusionnés dans {h}: {wants}")
# nets à une seule broche (hors NC et points de test)
count = {}
for k, n in got.items():
    count.setdefault(n, []).append(k)
single = [n for n, ks in count.items() if len(ks) == 1 and not n.startswith("unconnected-")]
print(f"{len(exp)} broches vérifiées, {len(groups)} nets, {len(errs)} erreur(s)")
for e in errs: print("  ERREUR", e)
if single: print("  nets à une seule broche :", single)
sys.exit(1 if errs else 0)

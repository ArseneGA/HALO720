#!/usr/bin/env python3
"""BOM JLCPCB (Comment, Designator, Footprint, LCSC Part #) à partir de parts.json."""
import csv, json, os, re
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "fabrication")
os.makedirs(OUT, exist_ok=True)
parts = json.load(open(os.path.join(HERE, "parts.json")))
groups = {}
for p in parts:
    key = (p["mpn"] or p["value"], p["fp"].split(":")[-1], p["lcsc"], p["mpn"])
    groups.setdefault(key, []).append(p["ref"])
def nat(r):
    m = re.match(r"([A-Z]+)(\d+)", r)
    return (m.group(1), int(m.group(2)))
rows = sorted(groups.items(), key=lambda kv: nat(sorted(kv[1], key=nat)[0]))
with open(os.path.join(OUT, "BOM_JLCPCB.csv"), "w", newline="", encoding="utf-8") as fh:
    w = csv.writer(fh)
    w.writerow(["Comment", "Designator", "Footprint", "LCSC Part #"])
    for (val, fp, lcsc, mpn), refs in rows:
        w.writerow([mpn or val, ",".join(sorted(refs, key=nat)), fp, lcsc])
print(len(rows), "lignes,", len(parts), "composants")

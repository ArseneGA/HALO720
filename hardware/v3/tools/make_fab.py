#!/usr/bin/env python3
"""Fichiers d'usine pour les 2 cartes : Gerber + perçage (zip), BOM et CPL au format JLCPCB/PCBWay,
rendus 3D, PDF de l'implantation. Relance aussi la DRC et refuse de produire si elle n'est pas propre."""
import csv
import json
import os
import shutil
import subprocess
import sys
import unicodedata
import zipfile
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
V3 = os.path.normpath(os.path.join(HERE, ".."))
FAB = os.path.join(V3, "fabrication")
DOCS = os.path.normpath(os.path.join(V3, "..", "..", "docs", "v3"))
BOARDS = [
    dict(tag="carte_CM4", pcb=os.path.join(V3, "kicad", "halo720_v3.kicad_pcb"), parts="parts.json",
         sch=os.path.join(V3, "kicad", "halo720_v3.kicad_sch"), pdf="HALO720_v3_schema.pdf",
         layers=["F.Cu", "In1.Cu", "In2.Cu", "B.Cu", "F.Mask", "B.Mask", "F.Paste", "B.Paste",
                 "F.Silkscreen", "B.Silkscreen", "Edge.Cuts"]),
    dict(tag="flash", pcb=os.path.join(V3, "kicad_flash", "halo720_v3_flash.kicad_pcb"), parts="parts_flash.json",
         sch=os.path.join(V3, "kicad_flash", "halo720_v3_flash.kicad_sch"), pdf="HALO720_v3_flash_schema.pdf",
         layers=["F.Cu", "F.Mask", "F.Paste", "F.Silkscreen", "Edge.Cuts"]),
]


def run(*a):
    r = subprocess.run(list(a), capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"échec : {' '.join(a)}\n{r.stdout}\n{r.stderr}")
    return r.stdout


def drc(pcb):
    out = "/tmp/drc_fab.json"
    run("kicad-cli", "pcb", "drc", "--format", "json", "--severity-all", "--schematic-parity", "-o", out, pcb)
    r = json.load(open(out))
    err = [v for v in r["violations"] if v["severity"] == "error"]
    return len(err), len(r["unconnected_items"]), len(r["schematic_parity"]), len(r["violations"]) - len(err)


def schematic(b):
    """ERC (0 erreur exigée), PDF du schéma dans docs/v3, netlist dans fabrication/."""
    out = "/tmp/erc_fab.json"
    run("kicad-cli", "sch", "erc", "--format", "json", "--severity-error", "-o", out, b["sch"])
    n = sum(len(s["violations"]) for s in json.load(open(out))["sheets"])
    if n:
        sys.exit(f"{b['tag']} : ERC {n} erreur(s)")
    run("kicad-cli", "sch", "export", "pdf", "-o", os.path.join(DOCS, b["pdf"]), b["sch"])
    run("kicad-cli", "sch", "export", "netlist", "-o",
        os.path.join(FAB, os.path.basename(b["sch"]).replace(".kicad_sch", ".net")), b["sch"])


def bom(b, outdir):
    """BOM JLC : Comment, Designator, Footprint, LCSC Part #, + MPN pour PCBWay."""
    parts = json.load(open(os.path.join(HERE, b["parts"])))
    groups = OrderedDict()
    for p in parts:
        if not p.get("in_bom", True):
            continue
        fp = p.get("fp", "").split(":")[-1]
        # une ligne par référence LCSC : JLCPCB refuse la même pièce sur deux lignes
        g = groups.setdefault(p.get("lcsc") or (p["value"], fp), {"vals": [], "fp": fp, "lcsc": p.get("lcsc", ""),
                                                                   "mpn": p.get("mpn", ""), "refs": []})
        g["vals"].append(p["value"])
        g["refs"].append(p["ref"])
    fn = os.path.join(outdir, f"BOM_{b['tag']}.csv")
    with open(fn, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["Comment", "Designator", "Footprint", "LCSC Part #", "Manufacturer Part", "Quantity"])
        for g in sorted(groups.values(), key=lambda g: g["refs"][0]):
            refs, fp, lcsc, mpn = g["refs"], g["fp"], g["lcsc"], g["mpn"]
            val = g["vals"][0] if len(set(g["vals"])) == 1 else (mpn or g["vals"][0])
            refs = sorted(refs, key=lambda r: (r.rstrip("0123456789"), int(r[len(r.rstrip("0123456789")):] or 0)))
            # JLCPCB lit le CSV en GBK : pas d'accents dans le commentaire
            val = unicodedata.normalize("NFKD", val).encode("ascii", "ignore").decode()
            w.writerow([val, ",".join(refs), fp, lcsc, mpn, len(refs)])
    return fn, sum(len(g["refs"]) for g in groups.values())


def set_origin(pcb):
    """Origine perçage/placement = coin bas-gauche du contour (référence attendue par JLCPCB/PCBWay)."""
    code = ("import pcbnew,sys; b=pcbnew.LoadBoard(sys.argv[1]); r=b.GetBoardEdgesBoundingBox(); "
            "b.GetDesignSettings().SetAuxOrigin(pcbnew.VECTOR2I(r.GetLeft() + 50000, r.GetBottom() - 50000)); "
            "pcbnew.SaveBoard(sys.argv[1], b); print(r.GetWidth()/1e6 - 0.1, r.GetHeight()/1e6 - 0.1)")
    return [float(v) for v in run(sys.executable, "-c", code, pcb).split()[-2:]]


# corrections d'orientation constatées dans l'aperçu de placement JLCPCB (modèles de leur bibliothèque)
JLC_ROT = {"J1": 90, "J2": 90}


def cpl(b, outdir):
    """CPL JLC : Designator, Mid X, Mid Y, Layer, Rotation (mm, origine = coin bas-gauche de la carte)."""
    raw = "/tmp/pos_" + b["tag"] + ".csv"
    run("kicad-cli", "pcb", "export", "pos", "--format", "csv", "--units", "mm", "--side", "both",
        "--exclude-dnp", "--use-drill-file-origin", "-o", raw, b["pcb"])
    parts = {p["ref"] for p in json.load(open(os.path.join(HERE, b["parts"]))) if p.get("in_bom", True)}
    rows = list(csv.DictReader(open(raw)))
    fn = os.path.join(outdir, f"CPL_{b['tag']}.csv")
    with open(fn, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["Designator", "Mid X", "Mid Y", "Layer", "Rotation"])
        n = 0
        for r in rows:
            if r["Ref"] not in parts:
                continue
            rot = (float(r["Rot"]) + JLC_ROT.get(r["Ref"], 0)) % 360
            w.writerow([r["Ref"], r["PosX"] + "mm", r["PosY"] + "mm", "Top" if r["Side"] == "top" else "Bottom",
                        f"{rot:.6f}"])
            n += 1
    return fn, n, parts - {r["Ref"] for r in rows}


def gerbers(b, outdir):
    g = os.path.join("/tmp", "gerb_" + b["tag"])
    shutil.rmtree(g, ignore_errors=True)
    os.makedirs(g)
    run("kicad-cli", "pcb", "export", "gerbers", "--layers", ",".join(b["layers"]), "--subtract-soldermask",
        "--no-netlist", "--use-drill-file-origin", "-o", g, b["pcb"])
    run("kicad-cli", "pcb", "export", "drill", "--format", "excellon", "--drill-origin", "absolute",
        "--excellon-units", "mm", "--excellon-separate-th",
        "-o", g + "/", b["pcb"])
    fn = os.path.join(outdir, f"GERBER_{b['tag']}.zip")
    with zipfile.ZipFile(fn, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(os.listdir(g)):
            z.write(os.path.join(g, f), f)
    return fn, sorted(os.listdir(g))


def renders(b, outdir):
    out = []
    for side in ("top", "bottom"):
        fn = os.path.join(outdir, f"rendu3D_{b['tag']}_{side}.png")
        run("kicad-cli", "pcb", "render", "--side", side, "--quality", "high", "-w", "1600", "-h", "1200",
            "--background", "opaque", "--floor", "-o", fn, b["pcb"])
        out.append(fn)
    lay = "F.Fab,F.Silkscreen,Edge.Cuts" if b["tag"] == "flash" else "F.Fab,F.Silkscreen,B.Fab,B.Silkscreen,Edge.Cuts"
    run("kicad-cli", "pcb", "export", "pdf", "--layers", lay, "--mode-separate", "--black-and-white",
        "-o", outdir + "/", b["pcb"])
    return out


def main():
    os.makedirs(FAB, exist_ok=True)
    report = []
    for b in BOARDS:
        schematic(b)
        e, u, par, warn = drc(b["pcb"])
        if e or u or par:
            sys.exit(f"{b['tag']} : DRC pas propre (erreurs {e}, non connectés {u}, parité {par}) - rien produit")
        d = os.path.join(FAB, b["tag"])
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d)
        w, h = set_origin(b["pcb"])
        gz, gl = gerbers(b, d)
        bf, nb = bom(b, d)
        cf, nc, missing = cpl(b, d)
        if missing:
            sys.exit(f"{b['tag']} : composants de la BOM absents du PCB : {missing}")
        renders(b, d)
        import make_assembly
        make_assembly.build(b["tag"], b["pcb"], d)
        report.append(f"{b['tag']}: ERC 0, DRC 0 erreur / 0 non connecté / parité 0 ({warn} avertissements sérigraphie), "
                      f"{w:.1f} x {h:.1f} mm, {len(gl)} fichiers Gerber+perçage, BOM {nb} composants, CPL {nc} positions")
    print("\n".join(report))


if __name__ == "__main__":
    main()

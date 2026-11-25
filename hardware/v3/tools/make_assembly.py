#!/usr/bin/env python3
"""Fichiers pour un assembleur européen (Proto-Electronics) : appelé par make_fab.py après la BOM et le CPL.

- BOM_proto-electronics_<carte>.csv : fabricant + référence fabricant pour chaque ligne (séparateur ;)
- ASSEMBLAGE_<carte>.zip : placement (.txt et .xlsx) + plan d'implantation PDF avec les repères
"""
import csv
import os
import subprocess
import sys
import tempfile
import zipfile

# LCSC -> (fabricant, référence fabricant, description). Choix validés avec Proto-Electronics le 25/11/2026.
MPN = {
    "C19702": ("Samsung Electro-Mechanics", "CL10A106KP8NNNC", "Condensateur céramique 10 µF 10 V X5R 0603"),
    "C1525": ("Samsung Electro-Mechanics", "CL05B104KO5NNNC", "Condensateur céramique 100 nF 16 V X7R 0402"),
    "C45783": ("Samsung Electro-Mechanics", "CL21A226MAQNNNE", "Condensateur céramique 22 µF 25 V X5R 0805"),
    "C8678": ("Vishay", "SS34-E3/57T", "Diode Schottky 40 V 3 A SMA"),
    "C2103": ("Diodes Incorporated", "BZT52C10-7-F", "Zener 10 V 500 mW SOD-123"),
    "C72043": ("Kingbright", "APT1608SGC", "LED verte 0603"),
    "C2286": ("Kingbright", "APT1608EC", "LED rouge 0603"),
    "C597931": ("Hirose", "DF40C-100DS-0.4V(51)", "Connecteur carte à carte 100 broches 0,4 mm, hauteur 1,5 mm (CM4)"),
    "C597981": ("Hirose", "FH12-22S-0.5SH(55)", "Connecteur FPC 22 broches 0,5 mm"),
    "C165948": ("HRO Electronics (Korean Hroparts)", "TYPE-C-31-M-12", "Connecteur USB-C 16 broches"),
    "C295747": ("JST", "S2B-PH-SM4-TB(LF)(SN)", "Connecteur JST-PH 2 broches CMS horizontal"),
    "C135299": ("Changjiang Microelectronics", "SMNR6045-1R0M", "Inductance 1 µH blindée 6 x 6 mm, Isat >= 4 A"),
    "C2045677": ("Bourns", "SRP5030T-4R7M", "Inductance 4,7 µH blindée 5,7 x 5,2 mm, Isat 4,6 A"),
    "C8545": ("Nexperia", "2N7002,215", "MOSFET N 60 V SOT-23"),
    "C20917": ("Alpha & Omega Semiconductor", "AO3400A", "MOSFET N 30 V 5,7 A SOT-23"),
    "C25905": ("Yageo", "RC0402FR-075K1L", "Résistance 5,1 kΩ 1 % 0402"),
    "C705617": ("Yageo", "RL2512FK-070R68L", "Résistance 0,68 Ω 1 % 1 W 2512"),
    "C11702": ("Yageo", "RC0402FR-071KL", "Résistance 1 kΩ 1 % 0402"),
    "C25744": ("Yageo", "RC0402FR-0710KL", "Résistance 10 kΩ 1 % 0402"),
    "C25879": ("Yageo", "RC0402FR-072K2L", "Résistance 2,2 kΩ 1 % 0402"),
    "C25741": ("Yageo", "RC0402FR-07100KL", "Résistance 100 kΩ 1 % 0402"),
    "C25076": ("Yageo", "RC0402FR-07100RL", "Résistance 100 Ω 1 % 0402"),
    "C318884": ("XKB Connection", "TS-1187A-B-A-B", "Bouton poussoir CMS 5,1 x 5,1 mm"),
    "C488349": ("Injoinic", "IP5306-I2C", "Gestion batterie LiPo : charge 2,1 A + boost 5 V 2,4 A, I2C"),
    "C84817": ("XI'AN Aerosemi", "MT3608", "Convertisseur boost 2 A SOT-23-6"),
    "C7519": ("STMicroelectronics", "USBLC6-2SC6", "Protection ESD USB SOT-23-6"),
    "C2880156": ("ams OSRAM", "SFH 4715AS", "LED infrarouge 850 nm OSLON Black"),
}

NOTES = {
    "carte_CM4": [
        ("top", "F", "Plan d'implantation - carte CM4 - face TOP (composants)",
         ["JP1 (pont de soudure BOOT) : laisser OUVERT. TP1-TP10 : points de test, aucun composant.",
          "J1/J2 : connecteurs Hirose DF40C (pas 0,4 mm) du Compute Module 4.",
          "Le CM4, la camera et le cable flash ne sont PAS montes par l'assembleur."]),
        ("bot", "B", "Plan d'implantation - carte CM4 - face BOT (vue de dessous, miroir)",
         ["U2 IP5306 : pastille thermique centrale a braser (vias thermiques sous la pastille).",
          "D1 SS34 et D2 BZT52C10 : barre = cathode. J6 batterie, J7 carte flash (JST-PH).",
          "Vue de dessous : l'image est en miroir par rapport a la vue TOP."]),
    ],
    "flash": [
        ("top", "F", "Plan d'implantation - carte flash IR (aluminium 1 couche) - face TOP",
         ["D1, D2 : OSRAM SFH 4715AS. Polarite : la petite pastille isolee = CATHODE, cote du 'K' en serigraphie.",
          "La grande pastille centrale = ANODE (reliee electriquement, pas une pastille thermique isolee).",
          "J1 : JST-PH S2B-PH-SM4-TB, entree du cable vers le bord droit de la carte."]),
    ],
}

FAB_PCB = r'''
import pcbnew, sys
b = pcbnew.LoadBoard(sys.argv[1])
for fp in b.GetFootprints():
    for g in list(fp.GraphicalItems()):
        if g.GetClass() in ("PCB_TEXT", "FP_TEXT") and g.GetLayer() in (pcbnew.F_Fab, pcbnew.B_Fab):
            g.SetLayer(pcbnew.Cmts_User)
    r = fp.Reference()
    r.SetVisible(True)
    r.SetLayer(pcbnew.B_Fab if fp.IsFlipped() else pcbnew.F_Fab)
    r.SetPosition(fp.GetPosition())
    sz = 0.6 if fp.GetReference()[0] in "CRDQ" else 0.9
    r.SetTextSize(pcbnew.VECTOR2I(pcbnew.FromMM(sz), pcbnew.FromMM(sz)))
    r.SetTextThickness(pcbnew.FromMM(0.1))
    r.SetKeepUpright(True)
pcbnew.SaveBoard(sys.argv[2], b)
'''


def proto_bom(tag, d):
    rows = list(csv.DictReader(open(os.path.join(d, f"BOM_{tag}.csv"))))
    out = os.path.join(d, f"BOM_proto-electronics_{tag}.csv")
    with open(out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["Repères", "Quantité", "Fabricant", "Référence fabricant", "Description", "Boîtier", "Réf. LCSC"])
        for r in rows:
            if r["LCSC Part #"] not in MPN:
                sys.exit(f"make_assembly : référence fabricant inconnue pour {r['LCSC Part #']} ({r['Designator']})")
            m = MPN[r["LCSC Part #"]]
            w.writerow([r["Designator"], r["Quantity"], m[0], m[1], m[2], r["Footprint"], r["LCSC Part #"]])
    return out


def plan_pdf(tag, pcb, d, tmp):
    from PIL import Image
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.units import mm
    from reportlab.pdfgen import canvas
    import cairosvg
    fab = os.path.join(tmp, "fab.kicad_pcb")
    subprocess.run([sys.executable, "-c", FAB_PCB, pcb, fab], check=True, capture_output=True)
    pdf = os.path.join(d, f"Plan_implantation_{tag}.pdf")
    c = canvas.Canvas(pdf, pagesize=A4)
    W, H = A4
    for side, L, title, notes in NOTES[tag]:
        svg, png = os.path.join(tmp, side + ".svg"), os.path.join(tmp, side + ".png")
        cmd = ["kicad-cli", "pcb", "export", "svg", "--layers", f"{L}.Fab,Edge.Cuts", "--mode-single",
               "--fit-page-to-board", "--black-and-white", "--exclude-drawing-sheet", "-o", svg, fab]
        if side == "bot":
            cmd.insert(4, "--mirror")
        subprocess.run(cmd, check=True, capture_output=True)
        cairosvg.svg2png(url=svg, write_to=png, output_width=2400, background_color="white")
        c.setFont("Helvetica-Bold", 14)
        c.drawString(15 * mm, H - 18 * mm, title)
        c.setFont("Helvetica", 9)
        y = H - 25 * mm
        for n in notes:
            c.drawString(15 * mm, y, n)
            y -= 4.5 * mm
        im = Image.open(png)
        ar = im.height / im.width
        w = min(W - 30 * mm, (y - 20 * mm) / ar)
        c.drawImage(png, (W - w) / 2, y - 5 * mm - w * ar, w, w * ar)
        c.setFont("Helvetica", 8)
        c.drawString(15 * mm, 12 * mm, f"HALO 720 v3 rev A - {tag} - Arsene Galliez")
        c.showPage()
    c.save()
    return pdf


def placement(tag, d, tmp):
    import openpyxl
    rows = list(csv.reader(open(os.path.join(d, f"CPL_{tag}.csv"))))
    bom = {}
    for r in csv.DictReader(open(os.path.join(d, f"BOM_{tag}.csv"))):
        for x in r["Designator"].split(","):
            bom[x] = (r["Comment"], r["Footprint"])
    out = [["Repere", "X (mm)", "Y (mm)", "Face", "Rotation (deg)", "Valeur", "Boitier"]]
    for r in rows[1:]:
        v, f = bom.get(r[0], ("", ""))
        out.append([r[0], r[1].replace("mm", ""), r[2].replace("mm", ""), r[3], r[4], v, f])
    txt = os.path.join(tmp, f"Placement_{tag}.txt")
    open(txt, "w").write("\n".join("\t".join(x) for x in out) + "\n")
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Placement"
    for i, x in enumerate(out):
        ws.append([float(v) if i and j in (1, 2, 4) else v for j, v in enumerate(x)])
    ws.append([])
    ws.append(["Origine (0,0) = coin bas-gauche de la carte, vue de dessus. Face BOT : X vu de dessus."])
    xl = os.path.join(tmp, f"Placement_{tag}.xlsx")
    wb.save(xl)
    return txt, xl


def build(tag, pcb, d):
    with tempfile.TemporaryDirectory() as tmp:
        bom = proto_bom(tag, d)
        pdf = plan_pdf(tag, pcb, d, tmp)
        files = placement(tag, d, tmp) + (pdf,)
        z = os.path.join(d, f"ASSEMBLAGE_{tag}.zip")
        with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED) as zf:
            for f in files:
                zf.write(f, os.path.basename(f))
    return bom, z

# HALO 720 v3 — cartes électroniques (rév. A)

- `kicad/` : carte principale CM4, 4 couches, 43 × 69,5 mm (KiCad 9). `kicad_flash/` : carte flash aluminium 24 × 14 mm.
- `fabrication/carte_CM4/` et `fabrication/flash/` : **fichiers à envoyer à l'usine** (Gerber zip, BOM, CPL, PDF, rendus 3D).
- `tools/` : tout est généré par script, du schéma aux fichiers d'usine.

Chaîne complète (KiCad 9 + Python 3, scipy, matplotlib) :

```bash
cd hardware/v3/tools
python3 gen_schematic.py ../kicad        # schémas des 2 cartes
python3 check_netlist.py; python3 check_netlist.py flash   # câblage = description
python3 gen_footprints.py                # empreintes DF40 (CM4), LED OSRAM
bash run_main.sh && python3 silk_main.py # placement + routage carte CM4 (≈ 10 min), DRC
python3 build_flash.py                   # carte flash, DRC
python3 make_fab.py                      # ERC + DRC + PDF schémas + Gerber/BOM/CPL/rendus (refuse si erreur)
```

Documentation : `docs/v3/SCHEMA_V3.md` (français), `docs/v3/FACTORY_ASSEMBLY_EN.md` (usine, anglais),
`docs/v3/IMAGE_EMMC.md`, `docs/v3/QUOTE_REQUEST_EN.md`. Logiciel : `camera/` et `camera/v3/`.
Brochage CM4 et empreintes DF40 repris de la carte officielle CM4IO (Raspberry Pi, CC BY 4.0).

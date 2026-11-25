# HALO 720 v3 — carte porteuse CM4

- `kicad/` : projet KiCad 9 (schéma + bibliothèque `halo720`). Empreintes du projet à créer dans `halo720.pretty/`.
- `fabrication/` : nomenclature JLCPCB (`BOM_JLCPCB.csv`) et netlist.
- `tools/` : le schéma est généré par script.

```bash
cd hardware/v3/tools
python3 gen_schematic.py ../kicad   # régénère le schéma
python3 check_netlist.py            # compare la netlist KiCad au câblage décrit
python3 make_bom.py                 # régénère la BOM JLCPCB
kicad-cli sch erc ../kicad/halo720_v3.kicad_sch
```

Documentation : `docs/v3/SCHEMA_V3.md` et `docs/v3/HALO720_v3_schema.pdf`.
Brochage CM4 repris de la bibliothèque officielle CM4IO (Raspberry Pi, CC BY 4.0).

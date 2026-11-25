# HALO 720 v3 — carte porteuse CM4

- `kicad/` : carte principale CM4 (KiCad 9). `kicad_flash/` : carte flash aluminium.
- `fabrication/` : nomenclatures (`BOM_carte_CM4.csv`, `BOM_flash.csv`) et netlists.
- `tools/` : le schéma est généré par script.

```bash
cd hardware/v3/tools
python3 gen_schematic.py ../kicad   # régénère le schéma
python3 check_netlist.py; python3 check_netlist.py flash
python3 make_bom.py; python3 make_bom.py flash
kicad-cli sch erc ../kicad/halo720_v3.kicad_sch
```

Documentation : `docs/v3/SCHEMA_V3.md` et `docs/v3/HALO720_v3_schema.pdf`.
Brochage CM4 repris de la bibliothèque officielle CM4IO (Raspberry Pi, CC BY 4.0).

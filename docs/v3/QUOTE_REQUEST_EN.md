# Quote request (message to send to PCBWay / JLCPCB / Alibaba suppliers)

> **Subject: RFQ – turnkey PCBA + box build, 2 prototypes then 10 units (Raspberry Pi CM4 carrier + aluminium LED board)**
>
> Hello,
>
> I would like a quote for a complete turnkey job (PCB + components + assembly + box build + functional test):
>
> **Board 1 – CM4 carrier**: 43.0 × 69.5 mm, 4 layers FR-4 1.6 mm, ENIG, min track/space 0.15 mm, vias 0.5/0.25 mm,
> parts on both sides (47 parts, 29 BOM lines, two Hirose DF40 0.4 mm-pitch connectors).
> **Board 2 – IR LED board**: 24 × 14 mm, aluminium MCPCB 1 layer 1.6 mm, 3 parts (2 × OSRAM SFH 4715AS, 1 JST-PH).
>
> **Box build per unit**: buy and mount a Raspberry Pi CM4101016 (Wi-Fi, 1 GB, 16 GB eMMC), a Camera Module 3 NoIR
> and its 22→15-pin ribbon, a 2-pin JST-PH cable; program the eMMC with my image (rpiboot); run my test script
> (about 3 min, PASS/FAIL report). No battery.
>
> Quantities: **2 units** (prototypes) then **10 units**. Delivery to France, DDP please.
> Gerber, BOM (LCSC numbers), CPL, schematics and the assembly/test instructions are attached.
>
> Could you tell me: price for each quantity (boards, parts, CM4 and camera sourcing, assembly, test, shipping),
> lead time, and any DFM remarks?
>
> Thank you,
> Arsène Galliez

Attach: the two `GERBER_*.zip`, `BOM_*.csv`, `CPL_*.csv`, both schematic PDFs and `FACTORY_ASSEMBLY_EN.md` (export as PDF).

Tip: if a supplier cannot buy the CM4 or the camera, ask for the price **without** them; you then send those parts
to the factory yourself (Raspberry Pi approved resellers).

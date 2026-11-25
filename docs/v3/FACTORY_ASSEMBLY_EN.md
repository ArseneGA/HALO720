# HALO 720 v3 — Manufacturing package (PCBA + box build)

Quantity: **2 prototypes first**, then **10 units**. Customer: Arsène Galliez (France).
Everything below is assembled and tested by the factory. The customer only connects the battery.

## 1. Files

| Board | Folder | Contents |
|---|---|---|
| Main board (CM4 carrier) | `hardware/v3/fabrication/carte_CM4/` | `GERBER_carte_CM4.zip` (Gerber X2 + Excellon), `BOM_carte_CM4.csv`, `CPL_carte_CM4.csv`, assembly PDFs (`*-F_Fab.pdf`, `*-B_Fab.pdf`), 3D renders |
| Flash board (aluminium) | `hardware/v3/fabrication/flash/` | `GERBER_flash.zip`, `BOM_flash.csv`, `CPL_flash.csv`, assembly PDF, 3D render |

Schematics: `docs/v3/HALO720_v3_schema.pdf`, `docs/v3/HALO720_v3_flash_schema.pdf`. KiCad 9 sources: `hardware/v3/kicad*`.
BOM/CPL use the JLCPCB format (LCSC part numbers + manufacturer part numbers). CPL origin = lower-left corner of the board,
bottom-side X coordinates seen from the top. **Please check component rotations in your DFM preview** (IC pin 1, diodes, LEDs, connectors).

## 2. Bare boards

| | Main board | Flash board |
|---|---|---|
| Size | 43.0 × 69.5 mm | 24.0 × 14.0 mm |
| Material | FR-4 TG150, **4 layers**, 1.6 mm | **Aluminium** MCPCB, 1 layer, 1.6 mm, ≥ 1 W/m·K |
| Copper | 1 oz outer / 0.5 oz inner | 1 oz |
| Stack-up | L1 signals + GND · L2 **GND plane** · L3 **+5 V plane** · L4 signals + GND | single layer |
| Min track / space | 0.15 / 0.15 mm | 1.0 / 0.3 mm |
| Vias | 0.5 mm pad / 0.25 mm drill (230), tented | none |
| Surface finish | **ENIG** (0.4 mm-pitch Hirose DF40 connectors) | HASL lead-free or ENIG |
| Solder mask / silk | green / white, both sides | white mask / black silk preferred (green OK) |
| Impedance control | not required (CSI-2 traces < 25 mm) | — |

## 3. Assembly — main board (both sides)

- **Top**: J1/J2 Hirose DF40C-100DS-0.4V(51) (CM4 sockets), J3 FH12-22S-0.5SH(55) camera FPC, J5 USB-C,
  SW1/SW2 push buttons, D3/D4 LEDs, small passives.
- **Bottom**: IP5306-I2C power management (U2, exposed pad soldered), MT3608 flash driver (U3), inductors, J6 (battery) and J7 (flash) JST-PH SMD connectors.
- JP1 "BOOT" is a solder jumper: **leave it open** (it is bridged with tweezers only during eMMC programming, see §5).
- Test points TP1–TP10 are bare pads (no part).
- Not in the BOM: mounting holes, JP1, test points.

## 4. Assembly — flash board

2 × OSRAM **SFH 4715AS** (OSLON Black, 850 nm) + 1 × JST-PH S2B-PH-SM4-TB. The LED centre pad is the **anode** (electrically connected), not an isolated thermal pad:
use the footprint exactly as designed. Reflow profile per OSRAM recommendation.

## 5. Box build (per unit)

Parts purchased by the factory:

| Part | Qty | Notes |
|---|---|---|
| Raspberry Pi Compute Module 4 **CM4101016** (Wi-Fi, 1 GB RAM, 16 GB eMMC) | 1 | must be the Wi-Fi + eMMC version |
| Raspberry Pi **Camera Module 3 NoIR** (standard lens, 75°) | 1 | |
| Camera ribbon cable 22-pin → 15-pin, 0.5 mm pitch, ~100 mm (Pi Zero type) | 1 | 22-pin end into J3 |
| JST-PH 2-pin cable, 2.0 mm, ~100 mm, both ends female housing, wires 1:1 (red pin 1, black pin 2) | 1 | flash board J1 ↔ main board J7 |
| M2.5 × 1.5 mm brass standoffs (1.5 mm stack height) + M2.5 screws | 4 + 4 | fix the CM4 to the main board |

Steps:

1. Press the CM4 onto J1/J2 (CM4 antenna towards the copper-free area at the board edge, next to the "HALO 720 v3" text), fix with 4 standoffs and screws.
2. **Program the eMMC** with the image supplied by the customer (`halo720_v3_YYYYMMDD.img.xz`):
   1. Bridge JP1 "BOOT" (tweezers or a temporary blob of solder) and connect USB-C to the PC.
   2. Connect power: 3.7–4.2 V bench supply (≥ 3 A) on J6 (+ = pin marked "+", silkscreen "BATT"), then press **MARCHE (SW2)**.
   3. On the PC run `rpiboot` (github.com/raspberrypi/usbboot): the eMMC appears as a USB drive.
   4. Write the image with Raspberry Pi Imager ("Use custom") or `xzcat image.img.xz | sudo dd of=/dev/sdX bs=4M conv=fsync`.
   5. Disconnect, **remove the JP1 bridge**, check it is open.
3. Connect the camera ribbon into J3 (FH12 is a bottom-contact connector: contacts towards the PCB; lock the latch; check on the first unit) and the Camera Module 3 NoIR.
4. Connect the flash board cable to J7 (silkscreen "FLASH").
5. Run the **factory test** (§6). Label each unit with its CM4 serial number and PASS.
6. Packing: main board + camera + flash board as one assembly in an ESD bag with foam. **No battery is shipped.**

## 6. Factory test (about 3 minutes per unit)

Setup: bench supply 3.8 V / 3 A on J6, USB-C to the test PC, camera facing a white card 20 cm away in a dark box.

1. Press MARCHE (SW2). The green LED shows the camera is ready after ~20–30 s. The PC sees a USB network adapter.
2. `ssh pi@10.55.0.1` (password supplied with the image), then `sudo python3 ~/HALO/v3/factory_test.py`.
3. Follow the prompts (press the shutter button, confirm both LEDs blink).
4. The script prints **PASS** or **FAIL** and saves `~/HALO/factory_test_<serial>.json`. Please send all reports with the shipment.
5. `sudo shutdown -h now`: the unit must switch itself off (5 V rail drops) within ~15 s.

Tests performed: CM4 + 16 GB eMMC detected · no under-voltage · IP5306 charger on I2C, configured, battery level read ·
Wi-Fi access point "HALO-720-xxxx" on 5 GHz · camera IMX708 detected and image OK · IR flash lights the scene (brightness ×1.5 minimum) ·
shutter button · green and red LEDs. Current from the 3.8 V supply should be ≈ 0.4–0.6 A idle and ≈ 2 A during the flash.

## 7. Questions to the factory

- DFM remarks on DF40 (0.4 mm) footprints and on the IP5306 exposed pad with thermal vias.
- Lead time and price for 2 prototypes, then 10 units, with box build and test; shipping to France (DDP).

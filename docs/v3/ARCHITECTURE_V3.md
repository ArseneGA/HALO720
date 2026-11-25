# HALO 720 v3 — électronique miniaturisée pour une série de 10

Statut : **choix validés le 25/11/2026**, **schéma rév. A terminé** (voir [SCHEMA_V3.md](SCHEMA_V3.md)). Rien n'est commandé.

## Choix validés

| Sujet | Choix |
|---|---|
| Plateforme | **Raspberry Pi Compute Module 4** Wi-Fi 2,4 + 5 GHz, 1 Go, **eMMC 16 Go** (CM4101016) — logiciel installé en usine |
| Fabrication | **tout en usine** (PCBWay turnkey + box build) : cartes, CM4, caméra, flash, logiciel, test. Seule la batterie est branchée par l'utilisateur |
| Batterie | **LiPo plate 1S** ≈ 2000 mAh (≈ 50 × 34 × 10 mm), connecteur JST-PH |
| Flash | **carte flash aluminium séparée** : 2 LED OSRAM SFH 4715AS 850 nm soudées en usine (remplace la LED 10 W) |
| Filtre | **plaquette de verre 720 nm ≈ 15 mm** collée devant l'objectif (fin du Zomei 37 mm) |
| Logiciel | nouveau logiciel (à définir), même caméra : Camera Module 3 NoIR, libcamera |

## Architecture électrique

```
USB-C ──► IP5306-I2C : charge LiPo 2,1 A · boost 5 V 2,4 A · niveau batterie en I2C
             │                       │
         LiPo 1S                5 V ──► CM4 (connecteurs Hirose DF40 2 × 100 broches)
             │                               ├── CSI-2 (22 broches) ──► Camera Module 3 NoIR
             │                               ├── eMMC 16 Go (programmée en usine)
             │                               ├── Wi-Fi 2,4 / 5 GHz (antenne du module)
             │                               ├── I2C ──► IP5306-I2C (niveau batterie)
             │                               └── GPIO : flash, déclencheur, LED témoin
             └──► MT3608 en courant constant 0,88 A ──► carte flash alu : 2 × OSRAM SFH 4715AS
USB-C (données) ──► USB 2.0 du CM4 : flasher la microSD / rpiboot, maintenance
```

## Taille visée

- Carte porteuse **≈ 55 × 42 mm**, même empreinte que le CM4 posé dessus (55 × 40 mm),
  composants des deux côtés, 4 couches si nécessaire pour le routage.
- Hauteur électronique ≈ 8–10 mm (CM4 + connecteurs + carte), LiPo plate dessous.
- Encombrement total visé, hors filtre et poignées : **≈ 60 × 45 × 30 mm** pour électronique +
  batterie + caméra (contre le boîtier v2 de ≈ 198 × 114 × 90 mm).

## Flash (historique : LED 10 W, remplacée par la carte flash alu — voir SCHEMA_V3.md)

La LED dissipe ≈ 3 W de chaleur quand elle est allumée. En usage flash (éclairs < 1 s), la chaleur est
négligeable ; c'est la torche qui dimensionne le dissipateur.

- Dissipateur **à la taille du support de la LED** (≈ 25 × 25 mm, 6 à 10 mm d'épaisseur), ou la LED
  vissée sur une plaque aluminium intégrée à la face avant.
- Torche limitée à **30 s** par le logiciel (au lieu de 60 s), puis refroidissement.
- À valider par l'essai 6 (chaleur) sur le premier prototype.
- Le dissipateur doit rester **loin de l'antenne Wi-Fi** du CM4 (métal = portée réduite).

## Points à préciser pendant le schéma

1. Câble caméra : connecteur CSI 22 broches sur la carte + nappe 22 → 15 broches pour la Camera Module 3.
2. Brochage GPIO du CM4 (flash, déclencheur, LED témoin, I2C) — logiciel à adapter.
3. Choix d'antenne : antenne intégrée au CM4 ou antenne externe (connecteur U.FL) selon le boîtier.
4. Pièces JLCPCB « Basic » en priorité ; les connecteurs DF40, l'IP5306 et le connecteur CSI sont des
   pièces « Extended » (frais par référence).

## Budget par appareil (estimation, à affiner)

| Poste | Estimation |
|---|---|
| Compute Module 4 Lite Wi-Fi 1 Go | ≈ 40 € |
| Camera Module 3 NoIR + nappe | ≈ 30 € |
| Plaquette filtre 720 nm | ≈ 3–5 € |
| Carte porteuse assemblée (lot de 10, port compris) | ≈ 12–18 € |
| LiPo ≈ 2000 mAh | ≈ 5 € |
| LED IR 10 W + petit dissipateur | ≈ 5 € |
| microSD, boîtier PETG, visserie | ≈ 10 € |
| **Total** | **≈ 105–115 €** |

## Étapes suivantes

1. ~~Schéma KiCad complet + nomenclature LCSC.~~ Fait (rév. A, 25/11/2026).
2. Placement et routage, contrôle DRC, rendu 3D de la carte.
3. Fichiers JLCPCB (Gerber, BOM, CPL) et devis ; devis Europe pour comparaison.
4. Relecture ensemble, commande d'un lot prototype (2 cartes assemblées) avant les 10.
5. Boîtier v3 autour de la carte, de la LiPo, de la caméra et du flash.

# HALO 720 v3 — schémas électroniques (rév. A)

Statut : **schémas terminés, à relire avant le dessin des cartes.** Rien n'est commandé.

**Principe : tout est fait en usine.** L'usine fabrique et assemble les deux cartes, clipse le CM4,
installe le logiciel, branche la caméra et la carte flash, puis teste. Tu reçois un bloc prêt à
poser dans le boîtier ; il ne reste qu'à **brancher la batterie** (un connecteur détrompé).

| Carte | Schéma | Nomenclature |
|---|---|---|
| Carte principale CM4 (≈ 55 × 42 mm, FR4 4 couches) | [`HALO720_v3_schema.pdf`](HALO720_v3_schema.pdf) | `hardware/v3/fabrication/BOM_carte_CM4.csv` (26 lignes, 48 composants) |
| Carte flash IR (aluminium 1 couche, ≈ 20 × 14 mm) | [`HALO720_v3_flash_schema.pdf`](HALO720_v3_flash_schema.pdf) | `hardware/v3/fabrication/BOM_flash.csv` (2 lignes, 3 composants) |

Projets KiCad 9 : `hardware/v3/kicad/` et `hardware/v3/kicad_flash/`. Les schémas sont **générés par script**
(`hardware/v3/tools/gen_schematic.py`) ; `check_netlist.py` compare la netlist exportée par KiCad au câblage décrit.

## Vérifications faites

| Contrôle | Carte CM4 | Carte flash |
|---|---|---|
| ERC KiCad 9 | 0 erreur (avertissements : empreintes DF40HC et FH12-22S à dessiner) | 0 erreur (empreinte LED OSRAM à dessiner) |
| Netlist ↔ câblage voulu | 354 broches, 45 nets, 0 écart | 6 broches, 3 nets, 0 écart |
| Références LCSC | vérifiées sur jlcpcb.com / lcsc.com le 25/11/2026 | idem |

## Ce que fait l'usine (assemblage complet)

1. Fabrique et assemble la carte CM4 et la carte flash aluminium.
2. Achète et **clipse le CM4** (CM4101016 : Wi-Fi, 1 Go, eMMC 16 Go) et visse ses 4 entretoises.
3. **Programme l'eMMC** : bouton BOOT (SW3) maintenu + USB-C → `rpiboot` → copie de l'image HALO.
4. Branche la **nappe caméra** (Camera Module 3 NoIR, nappe 22 → 15) et le **câble JST-PH** de la carte flash.
5. Teste : démarrage, point d'accès Wi-Fi, photo, éclair (procédure de test fournie avec la commande).

Ce qui reste pour toi : poser le bloc dans le boîtier imprimé, **brancher la LiPo** et fermer.
La batterie voyage à part (plus simple pour le transport des batteries lithium).

## Carte principale — les 7 blocs

1. **CM4 Wi-Fi eMMC 16 Go** sur 2 connecteurs Hirose DF40HC(3.0) : 1,5 mm libres sous le CM4 pour des petits composants. GPIO_VREF à 3,3 V.
   Brochage **identique à la v2** : déclencheur GPIO17, LED GPIO27, flash GPIO22. GPIO23 = extinction, GPIO2/3 = I2C de l'IP5306.
2. **USB-C + IP5306-I2C** : charge la LiPo (2,1 A max), fabrique le 5 V (2,4 A max), se pilote en I2C (0x75).
   L'USB-C sert aussi à parler au CM4 (programmation, USB-Ethernet). Bouton **MARCHE** ; arrêt propre commandé par le CM4.
3. **Flash** : convertisseur MT3608 en courant constant, **0,88 A** (0,6 V / 0,68 Ω). GPIO22 allume le convertisseur
   et le MOSFET Q2 : LED totalement coupée au repos. Zener 10 V : tension limitée vers 11 V si la carte flash est débranchée.
4. **Caméra** : FPC 22 broches 0,5 mm (Hirose FH12-22S, comme la carte CM4 IO officielle).
5. **Mémoire** : eMMC du CM4, **plus de carte microSD** ; bouton BOOT pour la programmation en usine.
6. **Interface** : déclencheur, marche, LED verte et LED rouge **soudés sur la carte** — le boîtier n'a que des poussoirs et une fenêtre.
7. **Points de test** pour le contrôle en usine, 4 trous M2,5 alignés sur le CM4.

## Carte flash

- **2 LED OSRAM SFH 4715AS** (OSLON Black, 850 nm) en série sur carte **aluminium** (la carte sert de dissipateur).
- Par LED à 1 A : Vf 2,9 V typique (≤ 3,4 V), flux ≈ 1,5 W → les deux ensemble éclairent **plus que la LED 10 W** de la v2.
- Reliée à J7 de la carte principale par un câble JST-PH 2 fils.
- **À vérifier au dessin** : que la pastille thermique de la LED est bien isolée électriquement (datasheet OSRAM).

## Calculs

| Point | Calcul | Marge |
|---|---|---|
| Courant LED | 0,6 V / 0,68 Ω = 0,88 A | LED max 1 A |
| Puissance R10 | 0,6 V × 0,88 A = 0,53 W | résistance 1 W |
| Tension LED | 2 × 2,9 V + 0,6 V ≈ 6,4 V (max ≈ 7,4 V) | Zener 10 V (min 9,4 V) |
| Courant batterie pendant l'éclair | 6,4 V × 0,88 A / (0,85 × 3,3 V) ≈ 2 A | bobine 4 A, MT3608 4 A |
| 5 V du CM4 | 0,4 A repos, jusqu'à 1,4 A | IP5306 : 2,4 A |

## Fourni par l'usine (achats « box build »)

| Pièce | Remarque |
|---|---|
| Raspberry Pi CM4101016 (Wi-Fi, 1 Go, eMMC 16 Go) | clipsé sur J1/J2 |
| Camera Module 3 NoIR + nappe 22 → 15 broches (Pi Zero) | branchée sur J3 |
| Câble JST-PH 2 fils ≈ 100 mm | carte flash ↔ J7 |
| Entretoises M2,5 × 3 mm + vis | fixent le CM4 |

À fournir de ton côté : **LiPo 1S** (≥ 2000 mAh, protection intégrée, câble JST-PH 2 broches, **sens + / − vérifié**),
**plaquette filtre 720 nm**, le **boîtier** imprimé.

## Points à valider avec toi

1. **Batterie** : charge jusqu'à 2,1 A → LiPo d'environ 2500 mAh, ou charge réduite par logiciel. Protection ≈ 5 A en pointe.
2. **USB-C branché** : l'appareil reste allumé pendant la charge. Ça te va ?
3. **Antenne Wi-Fi** : celle du CM4 suffit sauf boîtier métallique.

## Étapes suivantes

1. Relecture des deux schémas.
2. Empreintes manquantes (DF40HC, FH12-22S, LED OSRAM) d'après les plans constructeur.
3. Placement / routage des deux cartes, DRC, rendus 3D ; position des boutons et LED calée sur le boîtier.
4. Logiciel HALO v3 (image à programmer en usine) + procédure de test usine.
5. Devis PCBWay « turnkey + box build » : 2 prototypes, puis 10 appareils.

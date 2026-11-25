# HALO 720 v3 — schémas électroniques (rév. A)

Statut : **dossier de fabrication complet (rév. A)** : schémas, cartes routées, fichiers d'usine, logiciel et test usine.
Rien n'est commandé. Prochaine étape : devis (voir [QUOTE_REQUEST_EN.md](QUOTE_REQUEST_EN.md)).

**Principe : tout est fait en usine.** L'usine fabrique et assemble les deux cartes, clipse le CM4,
installe le logiciel, branche la caméra et la carte flash, puis teste. Tu reçois un bloc prêt à
poser dans le boîtier ; il ne reste qu'à **brancher la batterie** (un connecteur détrompé).

| Carte | Schéma | Nomenclature |
|---|---|---|
| Carte principale CM4 (**43 × 69,5 mm**, FR4 4 couches) | [`HALO720_v3_schema.pdf`](HALO720_v3_schema.pdf) | `hardware/v3/fabrication/carte_CM4/BOM_carte_CM4.csv` (29 lignes, 47 composants) |
| Carte flash IR (aluminium 1 couche, **24 × 14 mm**) | [`HALO720_v3_flash_schema.pdf`](HALO720_v3_flash_schema.pdf) | `hardware/v3/fabrication/flash/BOM_flash.csv` (2 lignes, 3 composants) |

Projets KiCad 9 : `hardware/v3/kicad/` et `hardware/v3/kicad_flash/`. Les schémas sont **générés par script**
(`hardware/v3/tools/gen_schematic.py`) ; `check_netlist.py` compare la netlist exportée par KiCad au câblage décrit.

## Vérifications faites

| Contrôle | Carte CM4 | Carte flash |
|---|---|---|
| ERC KiCad 9 | 0 erreur | 0 erreur |
| Netlist ↔ câblage voulu | 354 broches, 45 nets, 0 écart | 6 broches, 3 nets, 0 écart |
| DRC du circuit imprimé (avec parité schéma ↔ carte) | 0 erreur, 0 connexion manquante, 0 écart (9 avertissements de sérigraphie, sans effet) | 0 erreur, 0 avertissement |
| Fichiers d'usine | Gerber + perçage, BOM, CPL, PDF d'implantation, rendus 3D | idem |
| Références LCSC | vérifiées sur jlcpcb.com / lcsc.com le 25/11/2026 | idem |

## Ce que fait l'usine (assemblage complet)

1. Fabrique et assemble la carte CM4 et la carte flash aluminium.
2. Achète et **clipse le CM4** (CM4101016 : Wi-Fi, 1 Go, eMMC 16 Go) et visse ses 4 entretoises.
3. **Programme l'eMMC** : pont de soudure **JP1 BOOT** fermé + USB-C → `rpiboot` → copie de l'image HALO, puis JP1 rouvert.
4. Branche la **nappe caméra** (Camera Module 3 NoIR, nappe 22 → 15) et le **câble JST-PH** de la carte flash.
5. Teste : démarrage, point d'accès Wi-Fi, photo, éclair (procédure de test fournie avec la commande).

Ce qui reste pour toi : poser le bloc dans le boîtier imprimé, **brancher la LiPo** et fermer.
La batterie voyage à part (plus simple pour le transport des batteries lithium).

## Carte principale — les 7 blocs

1. **CM4 Wi-Fi eMMC 16 Go** sur 2 connecteurs Hirose DF40C (1,5 mm), hauteur 1,5 mm (comme la carte CM4 IO officielle ; la version 3 mm DF40HC(3.0) est en rupture). Aucun composant sous le CM4 côté dessus. GPIO_VREF à 3,3 V.
   Brochage **identique à la v2** : déclencheur GPIO17, LED GPIO27, flash GPIO22. GPIO23 = extinction, GPIO2/3 = I2C de l'IP5306.
2. **USB-C + IP5306-I2C** : charge la LiPo (2,1 A max), fabrique le 5 V (2,4 A max), se pilote en I2C (0x75).
   L'USB-C sert aussi à parler au CM4 (programmation, USB-Ethernet). Bouton **MARCHE** ; arrêt propre commandé par le CM4.
3. **Flash** : convertisseur MT3608 en courant constant, **0,88 A** (0,6 V / 0,68 Ω). GPIO22 allume le convertisseur
   et le MOSFET Q2 : LED totalement coupée au repos. Zener 10 V : tension limitée vers 11 V si la carte flash est débranchée.
4. **Caméra** : FPC 22 broches 0,5 mm (Hirose FH12-22S, comme la carte CM4 IO officielle).
5. **Mémoire** : eMMC du CM4, **plus de carte microSD** ; pont de soudure JP1 BOOT pour la programmation en usine.
6. **Interface** : déclencheur, marche, LED verte et LED rouge **soudés sur la carte** — le boîtier n'a que des poussoirs et une fenêtre.
7. **Points de test** pour le contrôle en usine, 4 trous M2,5 alignés sur le CM4.

## Carte flash

- **2 LED OSRAM SFH 4715AS** (OSLON Black, 850 nm) en série sur carte **aluminium** (la carte sert de dissipateur).
- Par LED à 1 A : Vf 2,9 V typique (≤ 3,4 V), flux ≈ 1,5 W → les deux ensemble éclairent **plus que la LED 10 W** de la v2.
- Reliée à J7 de la carte principale par un câble JST-PH 2 fils.
- Vérifié au dessin : la grande pastille centrale de la LED est **l'anode** (pas isolée). Le cuivre de la carte est donc découpé
  en 3 surfaces (+, milieu, −) qui servent chacune de dissipateur ; la carte alu évacue la chaleur.

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
| Entretoises M2,5 × 1,5 mm + vis | fixent le CM4 |

À fournir de ton côté : **LiPo 1S** (≥ 2000 mAh, protection intégrée, câble JST-PH 2 broches, **sens + / − vérifié**),
**plaquette filtre 720 nm**, le **boîtier** imprimé.

## Points à valider avec toi

1. **Batterie** : charge jusqu'à 2,1 A → LiPo d'environ 2500 mAh, ou charge réduite par logiciel. Protection ≈ 5 A en pointe.
2. **USB-C branché** : l'appareil reste allumé pendant la charge. Ça te va ?
3. **Antenne Wi-Fi** : celle du CM4 suffit sauf boîtier métallique.

## Cartes dessinées

| | Carte CM4 | Carte flash |
|---|---|---|
| Taille | 43 × 69,5 mm (le CM4 fait 40 × 55 mm) | 24 × 14 mm, 2 trous M2 |
| Couches | 4 : signaux/masse · plan de masse · plan 5 V · signaux/masse | 1 (aluminium) |
| Dessus | CM4 (J1/J2), caméra J3, USB-C, déclencheur, marche, LED, JP1 | 2 LED + JST-PH (sortie de câble sur le bord) |
| Dessous | chargeur IP5306, flash MT3608, connecteurs batterie J6 et flash J7 | — |
| Points sensibles | bus caméra CSI-2 tiré en parallèle (< 25 mm, ≈ 100 Ω), pas de cuivre sous l'antenne du CM4, 136 vias de masse | larges surfaces par net |

Le routage est fait par script (`hardware/v3/tools/run_main.sh`, routeur maison) et vérifié par la DRC de KiCad.
Les modèles 3D manquent pour quelques pièces (DF40, boutons, LED OSRAM) : sans effet sur la fabrication.

## Logiciel v3

- `camera/halo_camera.py` (le même logiciel que la v2 : même brochage) + `camera/v3/` :
  `install_v3.sh` (CM4, caméra CAM1, Wi-Fi **5 GHz** canal 36, USB-Ethernet), `halo_power.py` (IP5306 : charge 1 A,
  niveau batterie dans l'app, extinction à batterie vide), `halo-poweroff` (2 appuis sur KEY en fin d'arrêt = coupure du 5 V),
  `halo-firstboot` (nom Wi-Fi unique par appareil), `factory_test.py` (test usine).
- Torche limitée à 30 s sur la v3.
- Image eMMC pour l'usine : [IMAGE_EMMC.md](IMAGE_EMMC.md). Dossier usine en anglais : [FACTORY_ASSEMBLY_EN.md](FACTORY_ASSEMBLY_EN.md).

## Ce qui reste à valider sur le premier prototype

1. Démarrage caméra sur CAM1 (`dtoverlay=imx708,cam1`) et Wi-Fi 5 GHz en point d'accès.
2. Registres IP5306 (réglage relu par `halo_power.py`, sinon réglages d'usine de la puce, l'appareil marche quand même).
3. Extinction complète par double appui, chaleur des LED en torche 30 s, autonomie.

## Étapes suivantes

1. Devis PCBWay / JLCPCB / fournisseurs Alibaba avec [QUOTE_REQUEST_EN.md](QUOTE_REQUEST_EN.md) : **2 prototypes**, puis 10.
2. À réception : image eMMC maître ([IMAGE_EMMC.md](IMAGE_EMMC.md)), essais ci-dessus.
3. Boîtier v3 autour de la carte (43 × 69,5 mm), de la LiPo, de la caméra et de la carte flash.

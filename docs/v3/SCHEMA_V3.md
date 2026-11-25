# HALO 720 v3 — schéma de la carte porteuse (rév. A)

Statut : **schéma terminé, à relire avant le dessin de la carte.** Rien n'est commandé.

- Schéma lisible : [`HALO720_v3_schema.pdf`](HALO720_v3_schema.pdf) (une page A2, 7 blocs).
- Projet KiCad 9 : `hardware/v3/kicad/` (`halo720_v3.kicad_sch` + bibliothèque `halo720.kicad_sym`).
- Nomenclature JLCPCB : `hardware/v3/fabrication/BOM_JLCPCB.csv` (30 lignes, 57 composants posés).
- Le schéma est **généré par script** (`hardware/v3/tools/gen_schematic.py`) : chaque broche y est décrite
  à la main, puis `check_netlist.py` compare la netlist exportée par KiCad à cette description.

## Vérifications faites

| Contrôle | Résultat |
|---|---|
| ERC KiCad 9 (règles électriques) | 0 erreur ; seuls 4 avertissements « empreinte introuvable » pour les 3 empreintes à dessiner (DF40HC, FH12-22S, TF-01A) |
| Netlist KiCad ↔ câblage voulu | 384 broches, 52 nets, 0 écart, aucun net coupé ou fusionné |
| Références LCSC | vérifiées une par une sur jlcpcb.com / lcsc.com le 25/11/2026 |

## Les 7 blocs

### 1. Compute Module 4
- CM4 Lite Wi-Fi 1 Go (**CM4101000**), acheté à part et clipsé sur 2 connecteurs **Hirose DF40HC(3.0)-100DS-0.4V(51)** (LCSC C424649).
- Hauteur 3 mm : il reste 1,5 mm sous le CM4 pour poser des petits composants → carte plus petite.
- GPIO_VREF relié au 3,3 V (obligatoire d'après la datasheet : jamais en l'air).
- **Brochage repris de la v2** : déclencheur GPIO17, LED GPIO27, flash GPIO22. Le logiciel actuel reste compatible.
- Ajouts : GPIO23 = extinction de l'alimentation, GPIO2/3 = I2C vers l'IP5306, GPIO14/15 = UART sur points de test.

### 2. USB-C, charge et 5 V (IP5306-I2C)
- L'USB-C sert à la fois à **charger** et à **parler au CM4** (USB 2.0 en mode périphérique) : flasher la microSD avec `rpiboot`, ou se connecter en USB-Ethernet sans Wi-Fi.
- **IP5306-I2C** : charge la LiPo, fabrique le 5 V (2,4 A max, charge 2,1 A max) et se pilote en I2C (adresse 0x75).
- Bobine 1 µH 6 × 6 mm (saturation 9,85 A), condensateurs 22 µF au plus près du CM4.
- Bouton **MARCHE** (SW2) sur la broche KEY de l'IP5306. Pour couper proprement, le CM4 envoie lui-même 2 appuis sur KEY à la fin de l'arrêt (GPIO23 → transistor Q1) : la carte SD n'est jamais coupée en pleine écriture.

### 3. Flash IR (remplace le module MOSFET + le XL4015 de la v2)
- Convertisseur **MT3608** monté en courant constant : il régule 0,6 V aux bornes de R10.
  **I = 0,6 / 0,68 Ω = 0,88 A**, comme les 0,87 A réglés sur ton XL4015.
- GPIO22 allume le convertisseur **et** le MOSFET Q2 en série avec la LED : au repos la LED est coupée (sinon la batterie passerait par la diode et la LED pourrait luire).
- Protection LED débranchée : diode Zener 8,2 V + R12 → la tension plafonne vers 9,4 V.
- R10 dissipe 0,52 W pendant l'éclair : résistance 2512 de 1 W. Torche limitée à 30 s par le logiciel.

### 4. Caméra
- Connecteur FPC **22 broches 0,5 mm** (Hirose FH12-22S, le même que la carte officielle CM4 IO).
- Nappe **22 → 15 broches** (type Pi Zero) vers la Camera Module 3 NoIR.
- I2C de la caméra sur SDA0/SCL0 du CM4 (GPIO44/45), alimentation caméra commandée par CAM_GPIO.

### 5. microSD
- Le CM4 **Lite** démarre sur la microSD. Lecteur push-push TF-01A.
- Tirages 47 kΩ sur CMD et DAT0-3 (recommandés par la norme SD).

### 6. Interface
- Déclencheur SW1 sur la carte + **connecteur façade J8** (JST-SH 5 broches) pour déporter déclencheur, marche et LED dans le boîtier.
- LED verte d'état (GPIO27) et LED rouge d'activité du CM4.

### 7. Points de test et flashage
- 10 points de test (UART, 5 V, batterie, 3,3 V, flash…), pont à souder **JP1** pour forcer le mode `rpiboot`.
- 4 trous M2,5 alignés sur ceux du CM4.

## Calculs de dimensionnement

| Point | Calcul | Marge |
|---|---|---|
| Courant LED | 0,6 V / 0,68 Ω = 0,88 A | identique à la v2 |
| Puissance R10 | 0,6 V × 0,88 A = 0,53 W | résistance 1 W |
| Courant batterie du flash | 6,6 V × 0,88 A / (0,85 × 3,3 V) ≈ 2,1 A | bobine 4 A, MT3608 4 A |
| Tension max si LED débranchée | 8,2 V + 0,6 mA × 2 kΩ ≈ 9,4 V | condensateurs 25 V |
| 5 V du CM4 | 0,4 A au repos, jusqu'à 1,4 A (datasheet CM4) | IP5306 : 2,4 A |

## Hors carte (à acheter à part, par appareil)

| Pièce | Remarque |
|---|---|
| Raspberry Pi CM4 Lite Wi-Fi 1 Go — CM4101000 | clipsé sur J1/J2 |
| Camera Module 3 NoIR + nappe 22 → 15 broches (Pi Zero) | |
| LiPo 1S plate ≥ 2000 mAh, avec protection, câble JST-PH 2 broches | **vérifier le sens + / −** avant de brancher |
| LED IR 850 nm 10 W + petit dissipateur | câble JST-PH 2 broches (J7) |
| Plaquette filtre 720 nm ≈ 15 mm | |
| microSD | |
| Entretoises M2,5 × 3 mm + vis | fixent le CM4 |

## Points à valider avec toi

1. **Batterie** : l'IP5306 charge jusqu'à 2,1 A. Il faut une LiPo qui l'accepte (≈ 2500 mAh ou plus, ou une 2000 mAh annoncée « 1C »), ou bien le logiciel réduit la charge au démarrage. La protection de la LiPo doit supporter ≈ 5 A en pointe (CM4 + flash ensemble).
2. **USB-C branché** : l'appareil reste allumé pendant la charge (comportement de l'IP5306). Ça te va ?
3. **Façade** : boutons et LED soudés sur la carte, ou déportés dans le boîtier par J8 ? Les deux sont prévus ; on peut retirer l'un des deux pour gagner de la place.
4. **Antenne Wi-Fi** : antenne intégrée au CM4 (rien à ajouter) ou externe U.FL si le boîtier est en métal.

## Étapes suivantes

1. Tu relis le schéma (PDF) et on tranche les 4 points ci-dessus.
2. Dessin des 3 empreintes manquantes d'après les plans constructeur (DF40HC, FH12-22S, TF-01A).
3. Placement et routage (4 couches, paires USB et CSI en impédance contrôlée), DRC, rendu 3D.
4. Gerber + BOM + CPL pour JLCPCB, devis 2 prototypes puis 10 cartes.

# HALO 720 v3 — achats pour les 2 prototypes (25/11/2026)

## Déjà commandé / en cours

| Quoi | Où | État |
|---|---|---|
| 2 cartes CM4 + 2 cartes flash alu assemblées (+ 3 nues de chaque) | JLCPCB, panier | ≈ 213 $ — **à payer quand J3 arrive**, coupon PCBA 20 $, avant le 25/12 |
| 7 × connecteur caméra FH12-22S-0.5SH(55) (C597981) | JLCPCB, précommande | 9,96 $ payé, arrivée ≈ 23/12 → sélectionner J3 dans « Mon inventaire » |
| LED IR (4 × OSRAM SFH 4715AS) | incluses dans la carte flash JLCPCB | rien à acheter |

## Déjà possédé

- 1 Camera Module 3 NoIR, 1 nappe 22 → 15 broches, Pi Zero 2 W (pas utilisables comme CM4).

## À acheter

| Pièce | Qté | Où | Prix vu le 25/11/2026 |
|---|---|---|---|
| Compute Module 4 Wi-Fi + eMMC : **CM4102008** (2 Go / 8 Go), ou CM4101008 / CM4104008 — jamais « …000 » (sans eMMC), pas de CM5 | 2 | **Kubii** 67,50 € (rupture → alerte) · RaspberryPi.dk CM4102008 739 kr ≈ 99 €, CM4101008 609 kr ≈ 82 € (secours) · Berrybase 1 Go/8 Go 78,60 € (rupture) · Kubii 2 Go/16 Go 138 € (en stock) · **BCD Atlantik** (bcdatlantik.shop, UK) 65,63 £ + 35 £ port France + TVA/douane à l'arrivée (secours) | |
| Camera Module 3 NoIR, objectif standard 75° (SC0873) | 1 | ✅ **commandée** 25/11 chez RaspberryPi.dk, commande n° 234746 (Kubii en rupture) | 239 kr |
| Nappe caméra 22 → 15 broches (« Camera Cable Standard–Mini », 200 mm) | 2 | ✅ **commandées** 25/11 chez RaspberryPi.dk — total commande 341,76 kr ≈ 45,80 € port Mondial Relay compris | 36 kr |
| LiPo **103665 3000 mAh** (68 × 35 × 10 mm), PH2.0, protection — Meshnology lot de 2, Amazon ASIN B0FJ1ZTVB9 — ✅ **commandée** 25/11, livraison 3/12 | 2 | Amazon | 29,48 € les 2 |
| Fiches JST-PH 2.0 pré-câblées GTIWUNG, 20 paires 80 mm — ✅ **commandées** 25/11, livraison 3/12 (câble J7↔J1 à souder fiche femelle–femelle) | 1 lot | Amazon | 6,99 € |
| Câble USB-C ↔ USB-C coudé 90°, 30 cm, USB 2.0 480 Mbps, silicone plat (YFYWINSH) — ✅ **commandé** 25/11, livraison 3/12 | 1 | Amazon | 6,99 € |
| Rondelles/entretoises M2,5 × 1,5 mm + vis M2,5 × 4 mm | 8 + 8 | Amazon | ≈ 5 € |
| Vis M2 × 4 mm (carte flash) | 4 | Amazon | — |
| Câble USB-C silicone souple, données USB 2.0 | 1 | Amazon | ≈ 10 € |
| Filtre 720 nm **HWB720** (≈ RG715), Ø 12,5 × 2 mm sur mesure — ⏳ devis demandé 25/11 à Tangsinuo (eBay « tangsinuo » + tsn@tangsinuo.com) pour 2 et 12 pièces ; repli : 17 × 2 mm en stock ≈ 7 $ | 2 | Tangsinuo | ≈ 5–7 € pièce |

Si aucun CM4 2 Go/8 Go n'est revenu vers le 15/12 : prendre la meilleure option disponible (Kubii 16 Go ou BCD Atlantik).

## Filtre 720 nm

- Qualité : Hoya R72 (720 nm) verre coloré Ø 12,5 mm × 2,5 mm, Edmund Optics #66-046 (≈ 37 $).
- Économique : verre noir HWB720 / « 720nm IR pass filter » rond Ø 10–15 mm (AliExpress, eBay).
- **Choix (25/11) : HWB720 Tangsinuo, budget < 15 € pièce** (le Hoya R72 à ≈ 37 $ reste l'option haut de gamme). Logement boîtier v3 : Ø filtre + 0,2 mm, profondeur épaisseur + 0,2 mm, épaulement Ø 11 mm côté objectif — cotes finales à l'arrivée des filtres.
- Contrôle à réception : un vrai 720 nm paraît noir opaque (à peine rouge sombre face à une lampe).
- Le flash 850 nm (LED de la carte flash JLCPCB) passe entièrement à travers le R72 : rien à prévoir en plus.


## ⚠️ Attention au montage

- **Polarité batterie** : aucune norme sur le côté du fil rouge des prises JST-PH ; un avis client de la Meshnology 103665 signale une polarité inversée. **Ne jamais brancher une batterie avant d'avoir vérifié que le fil rouge arrive sur le « + » de J6 (BATT).** Si inversé : retourner les 2 broches dans la fiche (lever les languettes avec une aiguille) ou refaire la prise avec une fiche PH2.0 neuve.
- **Câble carte CM4 (J7) ↔ carte flash (J1)** : câble 1:1, même vérification de polarité (broche 1 = « + »).
- **Décharge** : la batterie doit tenir ≈ 3–3,5 A en pointe pendant le flash ; si l'appareil se coupe au moment du flash, c'est la protection de la batterie qui déclenche (prendre une batterie ≥ 2C).
- **Prises refusées** : JST 1,25 mm (« Micro JST 1.25 ») et XH 2.54 ne rentrent pas dans les connecteurs PH 2.0 des cartes.

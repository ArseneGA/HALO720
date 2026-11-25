# HALO 720 v3 — série de 10 appareils avec flash IR (proposition)

Statut : **proposition à valider** avant le schéma KiCad et le routage. Rien n'est commandé.

## Objectifs

- 10 appareils complets, **≈ 100 € par appareil** (Pi, caméra, filtre, carte, batterie, boîtier).
- **Une seule batterie rechargeable** en USB-C pour le Pi et le flash (fin de l'Intenso et des 6 × AA).
- Une **carte HALO** fabriquée et assemblée chez JLCPCB : plus de modules, de borniers ni de fils Dupont.
- Boîtier v3 redessiné autour de la carte et de la batterie.

## Architecture électrique

```
USB-C ──► IP5306-I2C (charge 2,1 A + boost 5 V 2,4 A + 4 LED de niveau)
              │                 │
        18650 1S (Li-ion)      5 V ──► Pi Zero 2 W (par le connecteur 40 broches, broches 2/4)
              │
              └──► MT3608 en courant constant (≈ 0,87 A) ──► LED IR 850 nm 10 W
                        ▲ EN ◄── GPIO22 (broche 15)
```

| Bloc | Choix | Pourquoi |
|---|---|---|
| Batterie | 1 cellule 18650 Li-ion ≈ 3000 mAh, porte-pile dans le boîtier | courante, ≈ 11 Wh : plusieurs heures de prise de vue (Pi ≈ 2 W) |
| Charge + 5 V | **IP5306-I2C** (LCSC C488349) | charge USB, boost 5 V 2,4 A, protection et jauge à 4 LED en un seul circuit ; quelques dizaines de centimes |
| Flash | **MT3608** monté en régulateur de courant (résistance de mesure sur FB) | tire la LED depuis la batterie, s'active directement par GPIO22 en 3,3 V : plus de MOSFET ni d'IRF520 |
| Protection flash | zener sur FB (LED débranchée), résistance de mesure 2512 | évite la surtension à vide ; 0,5 W dissipés pendant l'éclair |
| Interrupteur général | interrupteur à glissière sur la carte | coupe la sortie de la batterie pour le rangement |
| Interface | bouton déclencheur (GPIO17), LED témoin (GPIO27) soudés sur la carte | même brochage que v1/v2 : le logiciel ne change pas |
| Connecteurs | JST-PH 2 broches : batterie, LED IR ; USB-C ; connecteur femelle 2 × 20 | montage sans outil |

Format envisagé : **carte au format Pi Zero (65 × 30 mm)** qui s'enfiche sur le connecteur du Pi
(+ ≈ 11 mm d'épaisseur), USB-C et jauge sur un bord accessible depuis l'extérieur du boîtier.

## Logiciel

Inchangé : flash sur GPIO22 (actif à l'état haut, broche EN du MT3608), bouton GPIO17, LED GPIO27.
À ajouter : lecture du niveau de batterie par I2C (IP5306-I2C, adresse à confirmer) et désactivation
de la coupure automatique à faible charge.

## Budget par appareil (estimation, à affiner)

| Poste | Estimation |
|---|---|
| Raspberry Pi Zero 2 WH (Kubii ≈ 20 €) | 20–22 € |
| Camera Module 3 NoIR | 25–30 € |
| Filtre 720 nm (à revoir : le Zomei 37 mm vissant est la plus grosse marge d'économie) | 5–15 € |
| Nappe Zero + microSD | 8 € |
| Carte HALO assemblée (lot de 10, port compris) | 8–12 € |
| 18650 + porte-pile | 5 € |
| LED IR 10 W + dissipateur 40 × 40 | 6 € |
| Boîtier PETG + visserie + divers | 8 € |
| **Total** | **≈ 85–110 €** |

## Points à valider avant le schéma

1. Batterie 18650 unique (porte-pile dans le boîtier) plutôt qu'une batterie LiPo plate.
2. On garde la **LED 10 W (≈ 5 V, 0,87 A)** et son dissipateur 40 × 40.
3. Carte **enfichée sur le Pi** (format Pi Zero) plutôt que reliée par une nappe.
4. Le filtre 720 nm : garder le Zomei 37 mm ou chercher moins cher.

## Étapes suivantes

1. Validation de ce document.
2. Schéma KiCad + nomenclature LCSC/JLCPCB (pièces « Basic » en priorité pour limiter les frais).
3. Routage 2 couches, contrôle DRC, fichiers Gerber + BOM + CPL pour JLCPCB.
4. Relecture ensemble, commande d'un premier lot (prototype) avant les 10.
5. Boîtier v3 autour de la carte et de la 18650.

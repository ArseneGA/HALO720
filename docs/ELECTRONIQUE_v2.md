# HALO 720 v2 — câblage et réglage du flash IR 850 nm

> Schéma provisoire : à valider au multimètre, étape par étape. Le GPIO ne fait que **commander** ;
> le courant de la LED vient uniquement du pack 6 × AA.

## 1. Deux circuits séparés

```
CAMÉRA (inchangé)   Intenso 5000 mAh ──micro-USB──► Pi Zero 2 WH ──nappe──► Camera Module 3 NoIR

FLASH               6 × AA (+) ──fusible 2 A──► interrupteur KCD1 ──► MOSFET VIN+
                    6 × AA (−) ─────────────────────────────────────► MOSFET VIN−
                    MOSFET OUT+ / OUT− ──► driver Aihasd IN+ / IN−
                    driver OUT+ / OUT− ──► LED 850 nm (+) / (−)   (LED vissée + pâte sur le dissipateur)

COMMANDE            Pi broche 15 (GPIO22) ──► MOSFET SIG / TRIG
                    Pi broche 14 (GND)    ──► MOSFET GND           ← masses communes obligatoires
                    Pi broche 1  (3,3 V)  ──► MOSFET VCC           (seulement si le module a une broche VCC)
```

Le MOSFET coupe l'**entrée** du driver, pas sa sortie : couper la sortie d'un driver à courant constant
laisse ses condensateurs se charger à vide et envoie un pic de courant dans la LED au rallumage.

## 2. Broches du Pi

Vue de dessus, carte microSD à gauche : broche 1 (pastille carrée) au bout côté microSD, sur la rangée
intérieure. Rangée intérieure = impaires (1, 3 … 39), rangée du bord = paires (2, 4 … 40).

| Broche | Fonction | Vers |
|---|---|---|
| 1 | 3,3 V | VCC du module MOSFET (si présent) |
| 9 | GND | déclencheur (2e patte) |
| 11 | GPIO17 | déclencheur (1re patte) — inchangé |
| 13 | GPIO27 | LED témoin verte via 330 Ω — inchangé |
| 14 | GND | GND du module MOSFET + cathode LED témoin |
| 15 | GPIO22 | SIG du module MOSFET — **nouveau** |

13 et 15 sont côte à côte sur la rangée intérieure, 14 est en face de la 13 côté bord : un petit groupe
facile à repérer. Ne branche jamais rien sur 2 ou 4 (5 V).

## 3. Fils

- Circuit de puissance (piles → interrupteur → MOSFET → driver → LED) : 0,5 mm² (20 AWG), ≈ 1 A.
- Commande (SIG, GND, VCC) : fils Dupont fins ; dans un passage **séparé** des fils de puissance.
- Fusible 2 A (ou polyfusible) en série sur le + du pack, au plus près des piles.
- Respecte la polarité de la LED (+/− gravés sur son support) : une LED inversée ne s'allume pas
  et peut être détruite si le driver monte en tension.

## 4. Réglage du driver — AVANT de brancher la LED

À faire avec le driver relié directement au pack (MOSFET contourné), LED débranchée :

1. **Tension (CV)** : multimètre en voltmètre sur OUT+/OUT−. Tourne le potentiomètre CV jusqu'à
   **6,0 V** (un peu au-dessus de la tension de la LED, 4,5–5,5 V).
2. **Courant (CC)** : multimètre en ampèremètre (calibre 10 A) **directement** entre OUT+ et OUT−.
   Tourne le potentiomètre CC jusqu'à **0,85–0,90 A**.
3. Débranche, remets le MOSFET dans le circuit, puis branche la LED.

La LED doit être vissée sur le dissipateur, avec la pâte thermique, avant tout essai de plus d'une seconde.

## 5. Piles

Le driver abaisse la tension : il lui faut ≈ 1,5–2 V de plus que la LED, soit **≥ 7 V à l'entrée**.

- Alcalines neuves : 9 V, puis le flash faiblit quand le pack passe sous ≈ 7 V (≈ 1,15 V par pile).
- Lithium AA (Energizer Ultimate) : tension plus stable, meilleur choix.
- **NiMH rechargeables : 7,2 V nominal, trop juste** — flash faible ou instable.

## 6. Essais, dans l'ordre

Le service caméra occupe GPIO22 : arrête-le pour les essais manuels.

```bash
sudo systemctl stop halo
pinctrl set 22 op dh    # allume (le voyant du module MOSFET s'allume)
pinctrl set 22 op dl    # éteint
sudo systemctl start halo
```

1. **Sans LED** : `dh` → ≈ 9 V entre OUT+ et OUT− du module MOSFET ; `dl` → 0 V.
   Si c'est l'inverse, mets `FLASH_ACTIVE_HIGH = False` dans `camera/halo_camera.py`.
2. **Avec LED, 1 seconde** : regarde la LED **à travers la caméra frontale d'un téléphone**
   (elle voit souvent le 850 nm en violet). **Ne regarde jamais la LED à l'œil nu, même éteinte en apparence.**
3. **Photo au flash dans l'app** : bouton éclair → « à chaque photo ». Si l'image est sombre ou
   éclairée à moitié (bande), augmente `FLASH_WARMUP_S` (0,08 → 0,15 s).
4. **Thermique** : torche 60 s (le maximum du logiciel), puis mesure le dissipateur. Au-delà de
   ≈ 65 °C, raccourcis `TORCH_MAX_S`.

## 7. Sécurité oculaire

Le 850 nm est invisible : l'œil ne cligne pas. Pas de regard direct sur la LED, pas de lentille de
concentration, un diffuseur pour photographier des personnes de près.

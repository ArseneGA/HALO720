# Consignes pour l'agent : essais du flash IR et mise à jour du HALO 720

Tu travailles sur l'ordinateur Linux d'Arsène, dans `~/HALO720` (ce dépôt). Tu l'aides à terminer les
essais du flash infrarouge 850 nm du boîtier v2, à installer le nouveau logiciel sur la caméra et à
compiler l'app Android. Parle-lui en français, simplement, une étape à la fois.

## État actuel (23/11/2026, soir)

- Câblage final fait, à plat sur l'établi (pas encore dans le boîtier) :
  pack 6 × AA → bornier VIN/GND du **module MOS IRF520** → V+/V− → IN du **driver XL4015** →
  OUT → LED IR 850 nm 10 W (soudée, polarité vérifiée).
- **Pas d'interrupteur ni de fusible** pour l'instant : pour couper, Arsène retire une pile.
- Driver déjà réglé : **5,97 V à vide, 0,87 A** en courant limité. Ne touche plus aux potentiomètres.
- Commande depuis le Pi : **broche 15 (GPIO22) → SIG** du module MOS, **broche 20 (GND) → GND**
  du module MOS. VCC du module : rien. Bouton sur 9/11, LED verte sur 13/14 (v1, inchangés).
- Il n'est **pas sûr que la LED soit sur le dissipateur avec de la pâte thermique** : demande-le.
- La caméra tourne encore l'**ancien logiciel** (sans flash). Le nouveau est sur `main` :
  `camera/halo_camera.py` 3.1.0 (réglage `flash` off/on/torche), aperçu 1280 × 720.

## Règles de sécurité (non négociables)

1. **Jamais la LED à l'œil nu.** Arsène la regarde uniquement à travers la caméra frontale de son
   téléphone. Rappelle-le avant chaque allumage.
2. **Tu n'allumes jamais la LED sans son accord explicite** à ce moment-là (« vas-y »).
3. Allumage manuel : **2 secondes maximum**, puis `dl` immédiatement. Tant qu'il n'a pas confirmé
   « LED sur dissipateur avec pâte thermique », pas plus de 2 s et **jamais la torche**.
4. Sans fusible : il retire une pile avant toute modification du câblage.
5. Rien sur les broches 2 et 4 du Pi (5 V).
6. Ne lance aucune commande qui supprime des photos de la caméra (`~/HALO/photos`).

## Accès à la caméra

La caméra crée son propre Wi-Fi : **SSID `HALO-720`** (mot de passe : demande-le à Arsène), adresse
**`10.42.0.1`**, utilisateur `pi`. L'ordinateur doit être connecté à ce Wi-Fi :

```bash
nmcli --ask device wifi connect HALO-720      # Arsène tape le mot de passe
ssh pi@10.42.0.1          # mot de passe : celui d'Arsène, demande-lui de le taper
```

Ce réseau n'a **pas d'Internet** : ne lance pas `apt-get` sur le Pi et **ne relance pas
`install.sh`** en entier (il en a besoin). Fais les `git pull` / Gradle avant de basculer sur ce Wi-Fi.

## Étape 0 : préparer (avec Internet)

```bash
cd ~/HALO720 && git checkout main && git pull
```

## Étape 1 : essai 4 (première lumière), avec l'ancien logiciel

Sur le Pi :

```bash
sudo systemctl stop halo                # libère GPIO22
pinctrl set 22 op dh ; sleep 1 ; pinctrl set 22 op dl
```

Demande à Arsène : lueur violette dans la caméra du téléphone ? voyant du module MOS allumé ?

- **Rien** : vérifier le Dupont SIG (broche 15), GND (broche 20), le sens des fils V+/V− → IN+/IN−,
  et que la LED est bien sur OUT+/OUT− (rouge sur OUT+). Lire l'état : `pinctrl get 22`.
- **Allumée** : passe au contrôle de l'IRF520.

### Contrôle IRF520 (important)

L'IRF520 n'est pas prévu pour 3,3 V : il peut ne s'ouvrir qu'à moitié. Avec son accord :

```bash
pinctrl set 22 op dh ; sleep 2 ; pinctrl set 22 op dl
```

Pendant les 2 s, il mesure **entre V+ et V−** du module MOS (multimètre 20 V⎓, fiche rouge dans VΩmA),
puis touche la languette métallique du transistor.

- **8 à 9 V et transistor froid** : OK, on continue.
- **Nettement moins (< 7 V) ou transistor tiède/chaud** : l'IRF520 ne convient pas. On s'arrête
  là pour le flash ; il faudra un module MOSFET « logic level » (D4184 ou AO3400), branché pareil.
  Note la mesure dans le compte rendu.

Remets la broche à 0 et relance le service : `pinctrl set 22 op dl ; sudo systemctl start halo`.

## Étape 2 : installer le nouveau logiciel sur la caméra

Depuis l'ordinateur (connecté au Wi-Fi HALO-720) :

```bash
cd ~/HALO720
scp camera/halo_camera.py camera/dng.py pi@10.42.0.1:~/HALO/
```

Puis sur le Pi (une seule fois) :

```bash
CFG=/boot/firmware/config.txt; [ -f $CFG ] || CFG=/boot/config.txt
grep -q '^gpio=22=op,dl' $CFG || echo 'gpio=22=op,dl' | sudo tee -a $CFG
grep -q '^dtoverlay=disable-bt' $CFG || echo 'dtoverlay=disable-bt' | sudo tee -a $CFG
sudo systemctl disable --now hciuart.service bluetooth.service 2>/dev/null || true
grep -q ExecStopPost /etc/systemd/system/halo.service || \
  sudo sed -i 's|^Restart=always|ExecStopPost=-/usr/bin/pinctrl set 22 op dl\nRestart=always|' /etc/systemd/system/halo.service
sudo systemctl daemon-reload && sudo reboot
```

Après le redémarrage, vérifie : `ssh pi@10.42.0.1 'journalctl -u halo -n 30 --no-pager'`
(doit indiquer la version 3.1.0, sans erreur) et `curl -s http://10.42.0.1:8000/api/state | head -c 400`
(doit contenir `"flash"`).

## Étape 3 : compiler l'app Android (avec Internet)

```bash
grep -q HALO_KEY_PASSWORD ~/.gradle/gradle.properties 2>/dev/null || echo "manquant"
# si « manquant » : demande à Arsène d'ajouter lui-même la ligne HALO_KEY_PASSWORD=… dans ~/.gradle/gradle.properties
cd ~/HALO720/android && ./gradlew assembleRelease   # ou assembleDebug selon ce qu'il utilise d'habitude
```

Le fichier de clé `app/halo720.keystore` doit exister sur cette machine (il n'est pas dans le dépôt).
S'il manque, dis-le à Arsène au lieu d'en créer un nouveau (sinon l'APK ne s'installera pas par-dessus
l'ancienne app). Donne-lui le chemin de l'APK produit pour qu'il l'installe sur son téléphone.

## Étape 4 : essai 5 (photo au flash), dans l'app

Arsène, dans une pièce sombre : bouton éclair du bandeau → « Flash IR 850 nm · à chaque photo », puis
déclenche. Photo éclairée en entier : OK. Bande sombre ou photo sombre : sur le Pi, dans
`~/HALO/halo_camera.py`, passe `FLASH_WARMUP_S = 0.08` à `0.15`, puis `sudo systemctl restart halo`.
Si `FLASH_ACTIVE_HIGH` doit changer (LED allumée au repos), même fichier.
Reporte ensuite la modification dans `camera/halo_camera.py` du dépôt, commit et push.

## Étape 5 : essai 6 (torche, chaleur)

**Seulement si** la LED est vissée/collée sur le dissipateur 40 × 40 avec pâte ou pad thermique.
App : bouton éclair → « Torche IR · 60 s max ». Après la coupure automatique, il mesure ou touche le
dissipateur. Au-delà de ≈ 65 °C : réduire `TORCH_MAX_S` (même méthode qu'à l'étape 4).

## Compte rendu final

Termine par un résumé court : chaque essai (OK / problème), la tension V+/V− mesurée et l'état du
transistor, la version du logiciel installée, les réglages modifiés. Si tu as changé du code, commit
avec un message clair et `git push`.

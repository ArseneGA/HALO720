# HALO 720 v3 — fabriquer l'image eMMC pour l'usine

L'usine programme la même image sur les 10 CM4. On la fabrique **une fois**, à partir d'un appareil « maître »
(le premier prototype reçu, ou un CM4 sur une carte CM4 IO officielle).

## 1. Installer l'appareil maître

1. Sur le prototype : ponter **JP1 BOOT**, USB-C vers le PC, batterie (ou alim 3,8 V) sur J6, appui sur **MARCHE**.
2. Sur le PC : `rpiboot` (dépôt `raspberrypi/usbboot`) → l'eMMC apparaît comme une clé USB.
3. Raspberry Pi Imager → *Raspberry Pi OS Lite (64-bit)* → réglages : nom d'hôte `halo`, utilisateur `pi`,
   ton mot de passe, SSH activé, Wi-Fi de ta box. Écrire, puis **retirer le pont JP1** et redémarrer.
4. Copier le logiciel et l'installer :

   ```bash
   scp -r camera pi@halo.local:~/HALO
   ssh pi@halo.local 'bash ~/HALO/v3/install_v3.sh && sudo reboot'
   ```

5. Contrôle : `sudo python3 ~/HALO/v3/factory_test.py` doit afficher **PASS**, l'app HALO 720 doit se connecter
   au Wi-Fi `HALO-720-xxxx` (5 GHz).

## 2. Nettoyer avant la copie

Sur l'appareil maître, juste avant de l'éteindre :

```bash
sudo nmcli connection delete "<Wi-Fi de ta box>"      # le mot de passe de ta box ne part pas en usine
sudo rm -f /etc/ssh/ssh_host_*                        # clés SSH recréées sur chaque appareil (halo-firstboot)
sudo truncate -s 0 /etc/machine-id
sudo sed -i 's/^ssid=HALO-720-.*/ssid=HALO-720/' /etc/NetworkManager/system-connections/HALO-720.nmconnection
sudo rm -rf /var/lib/halo ~/HALO/photos/* ~/HALO/settings.json ~/HALO/power.log ~/HALO/factory_test_*.json
history -c && sudo shutdown -h now
```

## 3. Copier l'eMMC en image

1. Ponter JP1, `rpiboot`, l'eMMC apparaît sur le PC (`/dev/sdX`, vérifier avec `lsblk`).
2. Copier seulement la partie utile et compresser :

   ```bash
   sudo dd if=/dev/sdX of=halo720_v3.img bs=4M status=progress conv=fsync
   sudo pishrink.sh -s halo720_v3.img            # optionnel : réduit l'image (github.com/Drewsif/PiShrink)
   xz -T0 -9 halo720_v3.img && mv halo720_v3.img.xz halo720_v3_$(date +%Y%m%d).img.xz
   ```

3. Retirer le pont JP1. Tester l'image : la programmer sur un 2e CM4, vérifier le **PASS** et le nom Wi-Fi différent.

## 4. Envoyer à l'usine

- Le fichier `halo720_v3_AAAAMMJJ.img.xz` (lien de téléchargement) + `docs/v3/FACTORY_ASSEMBLY_EN.md`.
- Le mot de passe de l'utilisateur `pi` **par message privé à l'usine**, jamais dans le dépôt GitHub.
- Le mot de passe Wi-Fi d'usine est `halo7200` (le même pour tous, modifiable dans l'app).

**Ne jamais publier l'image** : elle contient ton mot de passe `pi` (sous forme chiffrée).

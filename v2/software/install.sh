#!/usr/bin/env bash
# =====================================================================================
# HALO 720 — installation sur une carte microSD fraîchement flashée avec
# Raspberry Pi OS Lite (64-bit, Bookworm ou plus récent) via Raspberry Pi Imager.
#
# Dans Raspberry Pi Imager, avant d'écrire la carte ("Modifier les réglages") :
#   - nom d'hôte : halo      - utilisateur : pi (mot de passe au choix)
#   - Wi-Fi : votre box (uniquement pour cette installation, ensuite la caméra crée
#     son propre point d'accès)       - SSH : activé
# Puis, depuis un ordinateur sur le même réseau :
#   scp -r software pi@halo.local:~/HALO && ssh pi@halo.local 'bash ~/HALO/install.sh'
# =====================================================================================
set -euo pipefail

HALO_DIR="$HOME/HALO"
AP_SSID="HALO-720"
AP_PASS="halo7200"        # 8 caractères minimum ; à changer ici avant l'installation

echo "== 1/6 Paquets"
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
    python3-picamera2 python3-flask python3-gpiozero python3-lgpio python3-pil rpicam-apps

echo "== 2/6 Dossiers"
mkdir -p "$HALO_DIR/photos"
# Les photos sont stockées sur la microSD, dans ~/HALO/photos

echo "== 3/6 Configuration caméra (config.txt)"
CFG=/boot/firmware/config.txt
[ -f "$CFG" ] || CFG=/boot/config.txt
sudo sed -i 's/^camera_auto_detect=0/camera_auto_detect=1/' "$CFG"
grep -q '^camera_auto_detect=1' "$CFG" || echo 'camera_auto_detect=1' | sudo tee -a "$CFG"
# CMA : on NE touche PAS à la valeur par défaut. Sous Trixie, vc4-kms-v3d réserve déjà
# 256 Mo de CMA sur le Zero 2 W ; forcer cma-128 la réduirait de moitié et ferait échouer
# la capture 12 MP + RAW (« Failed to allocate buffers »). Vérifié : capture 12 MP + DNG OK à 256 Mo.
# Pas d'écran : on libère de la mémoire GPU
grep -q '^gpu_mem' "$CFG" || echo 'gpu_mem=32' | sudo tee -a "$CFG"

echo "== 4/6 Extinction sans mot de passe (bouton + interface web)"
echo "$USER ALL=(ALL) NOPASSWD: /sbin/shutdown, /usr/sbin/shutdown" | sudo tee /etc/sudoers.d/halo-shutdown >/dev/null
sudo chmod 440 /etc/sudoers.d/halo-shutdown

echo "== 5/6 Service systemd (démarrage automatique)"
sudo tee /etc/systemd/system/halo.service >/dev/null <<EOF
[Unit]
Description=HALO 720 infrared camera
After=network.target

[Service]
User=$USER
WorkingDirectory=$HALO_DIR
Environment=HALO_PHOTO_DIR=$HALO_DIR/photos
Environment=HALO_SETTINGS=$HALO_DIR/settings.json
ExecStart=/usr/bin/python3 $HALO_DIR/halo_camera.py
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable halo.service

echo "== 6/6 Point d'accès Wi-Fi $AP_SSID (NetworkManager)"
# La caméra crée son propre réseau : le téléphone s'y connecte, puis ouvre http://10.42.0.1:8000
sudo nmcli connection delete "$AP_SSID" >/dev/null 2>&1 || true
sudo nmcli connection add type wifi ifname wlan0 con-name "$AP_SSID" autoconnect yes ssid "$AP_SSID" \
    802-11-wireless.mode ap 802-11-wireless.band bg ipv4.method shared \
    wifi-sec.key-mgmt wpa-psk wifi-sec.proto rsn wifi-sec.pairwise ccmp wifi-sec.group ccmp wifi-sec.psk "$AP_PASS" connection.autoconnect-priority 50
# NB : le point d'accès prend le dessus sur le Wi-Fi de la box à chaque démarrage.
# Pour retrouver la box (mises à jour) : sudo nmcli connection up "<nom de votre box>"

echo
echo "Installation terminée. Redémarrer : sudo reboot"
echo "Après redémarrage : Wi-Fi '$AP_SSID' (mot de passe $AP_PASS) puis http://10.42.0.1:8000"

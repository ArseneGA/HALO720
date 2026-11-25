#!/usr/bin/env bash
# =====================================================================================
# HALO 720 v3 (Compute Module 4 eMMC) - installation du logiciel sur l'appareil « maître ».
# On installe UNE fois sur un CM4, puis on copie son eMMC en image (voir docs/v3/IMAGE_EMMC.md) :
# c'est cette image que l'usine programme sur chaque appareil.
#
# Départ : eMMC programmée avec Raspberry Pi OS Lite 64 bits (Bookworm ou plus récent) via rpiboot +
# Raspberry Pi Imager (nom d'hôte halo, utilisateur pi, SSH activé, Wi-Fi de la box pour l'installation).
#   scp -r camera pi@halo.local:~/HALO && ssh pi@halo.local 'bash ~/HALO/v3/install_v3.sh'
# =====================================================================================
set -euo pipefail

HALO_DIR="$HOME/HALO"
AP_SSID="HALO-720"        # suffixé au 1er démarrage par 4 caractères du numéro de série (HALO-720-1a2b)
AP_PASS="halo7200"        # mot de passe d'usine, modifiable ensuite dans l'app (réglages > Wi-Fi)
AP_CHANNEL=36             # 5 GHz, canal 36 (5180 MHz) : autorisé en intérieur et extérieur en France à faible puissance
COUNTRY=FR

echo "== 1/8 Paquets"
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
    python3-picamera2 python3-flask python3-gpiozero python3-lgpio python3-pil python3-numpy python3-simplejpeg \
    python3-smbus i2c-tools rpicam-apps

echo "== 2/8 Dossiers"
mkdir -p "$HALO_DIR/photos"

echo "== 3/8 config.txt (CM4 : caméra CAM1, I2C, USB, flash éteint)"
CFG=/boot/firmware/config.txt
[ -f "$CFG" ] || CFG=/boot/config.txt
add() { grep -qxF "$1" "$CFG" || echo "$1" | sudo tee -a "$CFG" >/dev/null; }
sudo sed -i 's/^camera_auto_detect=1/camera_auto_detect=0/' "$CFG"
add 'camera_auto_detect=0'
add 'dtoverlay=imx708,cam1'       # Camera Module 3 NoIR sur le port CAM1 du CM4 (connecteur J3)
add 'dtparam=i2c_arm=on'          # I2C1 (GPIO2/3) -> IP5306
add 'gpio=22=op,dl'               # flash éteint dès la mise sous tension
add 'gpio=23=op,dl'               # KEY de l'IP5306 relâché
add 'dtoverlay=disable-bt'        # pas de Bluetooth : radio entièrement au Wi-Fi
add 'dtoverlay=dwc2,dr_mode=peripheral'   # USB-C = USB-Ethernet vers un PC (maintenance, test usine)
add 'gpu_mem=32'
add 'dtparam=watchdog=on'           # chien de garde matériel : un CM4 bloqué redémarre tout seul
sudo systemctl disable --now hciuart.service bluetooth.service >/dev/null 2>&1 || true
CMD=/boot/firmware/cmdline.txt
[ -f "$CMD" ] || CMD=/boot/cmdline.txt
grep -q 'modules-load=dwc2,g_ether' "$CMD" || sudo sed -i 's/$/ modules-load=dwc2,g_ether/' "$CMD"
sudo raspi-config nonint do_wifi_country "$COUNTRY"

sudo mkdir -p /etc/systemd/system.conf.d
printf "[Manager]\nRuntimeWatchdogSec=15\nRebootWatchdogSec=2min\n" | sudo tee /etc/systemd/system.conf.d/halo-watchdog.conf >/dev/null

echo "== 4/8 Extinction sans mot de passe + coupure de l'IP5306 en fin d'arrêt"
echo "$USER ALL=(ALL) NOPASSWD: /sbin/shutdown, /usr/sbin/shutdown, /usr/bin/nmcli" | sudo tee /etc/sudoers.d/halo-shutdown >/dev/null
sudo chmod 440 /etc/sudoers.d/halo-shutdown
sudo install -m 755 "$HALO_DIR/v3/halo-poweroff" /usr/lib/systemd/system-shutdown/halo-poweroff

echo "== 5/8 Services : caméra + alimentation"
sudo tee /etc/systemd/system/halo-power.service >/dev/null <<EOF
[Unit]
Description=HALO 720 v3 - batterie (IP5306)
[Service]
ExecStart=/usr/bin/python3 $HALO_DIR/v3/halo_power.py
Environment=HALO_CHARGE_A=1.0
Environment=PYTHONUNBUFFERED=1
RuntimeDirectory=halo
RuntimeDirectoryMode=0755
RuntimeDirectoryPreserve=yes
Restart=always
RestartSec=5
[Install]
WantedBy=multi-user.target
EOF
sudo tee /etc/systemd/system/halo.service >/dev/null <<EOF
[Unit]
Description=HALO 720 infrared camera
After=network.target halo-power.service

[Service]
User=$USER
WorkingDirectory=$HALO_DIR
Environment=HALO_PHOTO_DIR=$HALO_DIR/photos
Environment=HALO_SETTINGS=$HALO_DIR/settings.json
Environment=HALO_TORCH_MAX_S=30
Environment=MALLOC_MMAP_THRESHOLD_=131072
Environment=MALLOC_TRIM_THRESHOLD_=131072
Environment=MALLOC_ARENA_MAX=2
Environment=PYTHONUNBUFFERED=1
ExecStart=/usr/bin/python3 $HALO_DIR/halo_camera.py
ExecStopPost=-/usr/bin/pinctrl set 22 op dl
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF

echo "== 6/8 Premier démarrage de chaque appareil : nom Wi-Fi unique + nouvelles clés SSH"
sudo install -m 755 "$HALO_DIR/v3/halo-firstboot" /usr/local/sbin/halo-firstboot
sudo tee /etc/systemd/system/halo-firstboot.service >/dev/null <<EOF
[Unit]
Description=HALO 720 v3 - personnalisation au premier démarrage
Before=NetworkManager.service ssh.service
ConditionPathExists=!/var/lib/halo/firstboot-done
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/halo-firstboot
[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable halo.service halo-power.service halo-firstboot.service

echo "== 7/8 Point d'accès Wi-Fi 5 GHz + liaison USB"
sudo nmcli connection delete "$AP_SSID" >/dev/null 2>&1 || true
sudo nmcli connection add type wifi ifname wlan0 con-name "$AP_SSID" autoconnect yes ssid "$AP_SSID" \
    802-11-wireless.mode ap 802-11-wireless.band a 802-11-wireless.channel "$AP_CHANNEL" \
    ipv4.method shared ipv4.addresses 10.42.0.1/24 \
    wifi-sec.key-mgmt wpa-psk wifi-sec.proto rsn wifi-sec.pairwise ccmp wifi-sec.group ccmp wifi-sec.psk "$AP_PASS" \
    connection.autoconnect-priority 50 802-11-wireless.powersave 2
sudo nmcli connection delete halo-usb >/dev/null 2>&1 || true
sudo nmcli connection add type ethernet ifname usb0 con-name halo-usb autoconnect yes \
    ipv4.method shared ipv4.addresses 10.55.0.1/24 ipv6.method disabled

echo "== 8/8 Vérification rapide"
python3 -m py_compile "$HALO_DIR/halo_camera.py" "$HALO_DIR/v3/halo_power.py" "$HALO_DIR/v3/factory_test.py"
echo
echo "Installation terminée. Redémarrer : sudo reboot"
echo "Téléphone : Wi-Fi '$AP_SSID-xxxx' (5 GHz), mot de passe $AP_PASS, puis l'app HALO 720 (http://10.42.0.1:8000)."
echo "PC en USB-C : ssh pi@10.55.0.1"
echo "Pour fabriquer l'image usine : docs/v3/IMAGE_EMMC.md"

#!/usr/bin/env bash
# Procédure de test du capteur, à lancer en SSH AVANT de fermer le boîtier.
# Arrêter d'abord le service si déjà installé : sudo systemctl stop halo
set -e
echo "1) Le capteur est-il détecté ?"
rpicam-hello --list-cameras
#   Attendu : "0 : imx708 [4608x2592 10-bit RGGB] ..."
echo
echo "2) Photo test JPEG + RAW (sans filtre, puis avec le filtre 720 nm)"
rpicam-still --autofocus-on-capture -o test_couleur.jpg -r --width 4608 --height 2592 -t 3000
#   -> test_couleur.jpg et test_couleur.dng dans le dossier courant
echo
echo "3) Test de la LED (GPIO27) et du bouton (GPIO17) : 5 secondes d'écoute"
python3 - <<'EOF'
from gpiozero import LED, Button
from signal import pause
import time
led = LED(27); btn = Button(17, pull_up=True)
btn.when_pressed = lambda: (print("bouton appuyé"), led.on())
btn.when_released = lambda: (print("bouton relâché"), led.off())
led.blink(0.2, 0.2, n=5, background=False)
print("Appuyez sur le déclencheur…"); time.sleep(5)
EOF
echo
echo "4) Vérification infrarouge : pointer une télécommande TV vers l'objectif et appuyer"
echo "   sur une touche : rpicam-hello -t 10000 (si un écran HDMI est branché) ou"
echo "   rpicam-still -o telecommande.jpg -t 2000 — la LED de la télécommande doit apparaître"
echo "   brillante (le capteur NoIR voit l'IR). Avec le filtre 720 nm, une scène en plein"
echo "   soleil doit montrer la végétation très claire et le ciel sombre."

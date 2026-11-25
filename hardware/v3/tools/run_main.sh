#!/bin/bash
# Chaîne complète de la carte principale : placement + routage, nettoyage, plans, DRC.
set -e
cd "$(dirname "$0")"
python3 build_main.py 2>&1 | grep -i "échec\|terminé\|vias\|éventail\|bus"
python3 finish_main.py prune 2>&1 | grep -i "retir"
for i in 1 2 3 4 5 6; do
  n=$(python3 finish_main.py prunetracks 2>&1 | grep -i "retir" | grep -o "[0-9]*$")
  echo "bouts retirés : $n"; [ "$n" = "0" ] && break
done
python3 finish_main.py 2>&1 | grep -v -i "swig\|debug"

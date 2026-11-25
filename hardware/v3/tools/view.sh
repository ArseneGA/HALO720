#!/bin/bash
# usage: view.sh board.kicad_pcb out_prefix
B=$1; O=$2
kicad-cli pcb export svg --mode-single --fit-page-to-board --exclude-drawing-sheet -l F.Cu,F.SilkS,F.CrtYd,F.Fab,Edge.Cuts,User.Drawings -o /tmp/_t.svg "$B" >/dev/null 2>&1 && rsvg-convert -z 12 -b white /tmp/_t.svg -o ${O}_top.png
kicad-cli pcb export svg --mode-single --fit-page-to-board --exclude-drawing-sheet --mirror -l B.Cu,B.SilkS,B.CrtYd,B.Fab,Edge.Cuts -o /tmp/_b.svg "$B" >/dev/null 2>&1 && rsvg-convert -z 12 -b white /tmp/_b.svg -o ${O}_bot.png
ls -la ${O}_top.png ${O}_bot.png | awk '{print $5, $9}'

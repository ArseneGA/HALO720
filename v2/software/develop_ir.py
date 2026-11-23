#!/usr/bin/env python3
"""
Développement des RAW (DNG) infrarouges sur ordinateur : balance des blancs sur la
végétation, échange des canaux rouge/bleu (fausses couleurs classiques "ciel doré /
feuillage bleu" ou "ciel bleu / feuillage blanc"), export TIFF 16 bits et JPEG.

Installation (PC) :  pip install rawpy numpy pillow
Usage :
  python develop_ir.py HALO_20261031_120000_01.dng                 # auto, rendu "neutre"
  python develop_ir.py *.dng --swap --wb-auto                      # fausses couleurs
  python develop_ir.py photo.dng --wb 0.55 1.45 --swap --out sortie/

--wb R B   : gains rouge/bleu (mêmes conventions que les "ColourGains" de la caméra)
--wb-auto  : balance "gray-world" : la scène moyenne devient neutre (bon point de départ
             pour végétation blanche)
--swap     : échange les canaux R et B après balance (rendu "faux bleu" du ciel)
--mono     : noir et blanc (le classique de l'IR : végétation blanche, ciel noir)
"""
import argparse
import os
import numpy as np
import rawpy
from PIL import Image


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--wb", nargs=2, type=float, metavar=("R", "B"))
    ap.add_argument("--wb-auto", action="store_true")
    ap.add_argument("--swap", action="store_true")
    ap.add_argument("--mono", action="store_true")
    ap.add_argument("--gamma", type=float, default=2.2)
    ap.add_argument("--out", default=".")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    for f in a.files:
        with rawpy.imread(f) as raw:
            params = dict(
                output_bps=16, gamma=(1, 1), no_auto_bright=True, output_color=rawpy.ColorSpace.raw,
                demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD, use_camera_wb=False, use_auto_wb=False,
                user_wb=[1, 1, 1, 1],
            )
            img = raw.postprocess(**params).astype(np.float32) / 65535.0  # linéaire, non balancé

        if a.wb_auto:
            m = img.reshape(-1, 3).mean(axis=0)
            gains = m[1] / np.maximum(m, 1e-6)            # ramène R et B sur G
        elif a.wb:
            gains = np.array([a.wb[0], 1.0, a.wb[1]])
        else:
            gains = np.array([0.55, 1.0, 1.45])            # défaut 720 nm (comme la caméra)
        img = np.clip(img * gains, 0, None)

        img = img / np.percentile(img, 99.7)                # exposition : le 99,7e centile → blanc
        img = np.clip(img, 0, 1) ** (1 / a.gamma)

        if a.mono:
            img = np.repeat((0.3 * img[..., 0] + 0.6 * img[..., 1] + 0.1 * img[..., 2])[..., None], 3, axis=2)
        elif a.swap:
            img = img[..., [2, 1, 0]]

        base = os.path.join(a.out, os.path.splitext(os.path.basename(f))[0])
        img8 = (img * 255).round().astype(np.uint8)
        Image.fromarray(img8).save(base + "_dev.jpg", quality=94)
        try:
            import tifffile  # pip install tifffile  (facultatif : TIFF 16 bits pour la retouche)
            tifffile.imwrite(base + "_dev.tiff", (img * 65535).round().astype(np.uint16), photometric="rgb")
        except ImportError:
            Image.fromarray(img8).save(base + "_dev.tiff")
        print("->", base + "_dev.jpg / .tiff  gains R/B =", np.round(gains[[0, 2]], 2))


if __name__ == "__main__":
    main()

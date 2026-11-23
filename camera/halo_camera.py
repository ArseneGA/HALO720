#!/usr/bin/env python3
"""
HALO 720 — serveur de la caméra (Raspberry Pi Zero 2 W + Camera Module 3 NoIR + filtre 720 nm)
================================================================================================

Version 3 : le serveur de l'app Android HALO 720.

Principe : la caméra est toujours prête. Le capteur tourne en continu en pleine définition
(4608 × 2592, ≈ 13 img/s) avec un flux d'aperçu réduit (768 × 432, MJPEG matériel) pour le
téléphone. Déclencher ne change jamais de mode capteur : on garde l'image suivante du flux
(< 100 ms), puis l'encodage JPEG, l'écriture et le RAW se font en arrière-plan, en file.

  * modes : photo, pose longue (durée ou B), rafale, intervallomètre, bracketing ; retardateur
    combinable. Les séries tournent sur la caméra : le téléphone peut se déconnecter.
  * looks IR (Bleu IR, Brut 720, N&B, Rose, Doré) cuits dans le JPEG, le RAW reste brut.
  * zoom 1× à 5× par recadrage du capteur ; JPEG enregistré à sa définition réelle.
  * déclencheur physique GPIO17 (appui = mode actif ; appui long 3 s = extinction),
    LED GPIO27.
  * API HTTP/JSON + flux d'événements (SSE) sur le port 8000.
"""

import io
import hashlib
import json
import math
import os
import queue
import re
import shutil
import signal
import struct
import subprocess
import sys
import tarfile
import threading
import time
from collections import deque
from datetime import datetime
from pathlib import Path

import numpy as np
import simplejpeg

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dng  # noqa: E402
from flask import Flask, Response, abort, jsonify, request, send_from_directory
from werkzeug.serving import WSGIRequestHandler

try:  # matériel : absent sur un PC de test
    from gpiozero import Button, PWMLED
    from picamera2 import MappedArray, Picamera2
    from picamera2.encoders import MJPEGEncoder, Quality
    from picamera2.outputs import FileOutput
    from libcamera import controls, Transform
except ImportError:  # pragma: no cover
    raise

VERSION = "3.0.0"
HALO_DIR = Path(os.environ.get("HALO_DIR", "/home/pi/HALO"))
PHOTO_DIR = Path(os.environ.get("HALO_PHOTO_DIR", str(HALO_DIR / "photos")))
SETTINGS_FILE = Path(os.environ.get("HALO_SETTINGS", str(HALO_DIR / "settings.json")))
POWER_LOG = Path(os.environ.get("HALO_POWER_LOG", str(HALO_DIR / "power.log")))
THUMB_DIR = PHOTO_DIR / ".thumbs"
SPOOL_DIR = PHOTO_DIR / ".spool"
WEB_PORT = int(os.environ.get("HALO_PORT", "8000"))

BUTTON_GPIO = 17
LED_GPIO = 27
HOLD_SECONDS = 3.0
FULL_RES = (4608, 2592)
PREVIEW_RES = (1280, 720)        # aperçu MJPEG matériel 720p (était 768 × 432) ≈ 8–11 Mbit/s en Wi-Fi
FRAME_US = 71429                 # 14 img/s : cadence maximale du mode pleine définition
SD_RESERVE = 300 * 1024 * 1024   # en dessous : microSD considérée pleine
FOCAL_35MM = 28                  # Camera Module 3 standard : 75° en diagonale ≈ 28 mm
SHUTDOWN_CMD = ["sudo", "-n", "/usr/sbin/shutdown", "-h", "now"]
REBOOT_CMD = ["sudo", "-n", "/usr/sbin/shutdown", "-r", "now"]

ISO_STEPS = [100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600]
SHUTTER_STEPS = [125, 156, 200, 250, 312, 400, 500, 625, 800, 1000, 1250, 1563, 2000, 2500, 3125,
                 4000, 5000, 6250, 8000, 10000, 12500, 16667, 20000, 25000, 33333, 40000, 50000,
                 66667, 76923, 100000, 125000, 166667, 200000, 250000, 300000, 400000, 500000,
                 600000, 800000, 1000000, 2000000, 4000000, 8000000, 15000000, 30000000]
POSE_STEPS = [0, 1, 2, 4, 8, 15, 30, 60]      # 0 = pose B (tenir le déclencheur)
LOOK_NAMES = {"bleu": "Bleu IR", "brut": "Brut 720", "nb": "N&B", "rose": "Rose", "dore": "Doré"}
LOOK_ORDER = ["bleu", "brut", "nb", "rose", "dore"]

# Un look = matrice couleur 3×3 appliquée par l'ISP de la caméra (espace RVB linéaire, après la
# matrice de base du capteur), + saturation et contraste de l'ISP. Coût nul pour le processeur ;
# la visée montre exactement le rendu du JPEG ; le RAW n'est jamais touché.
# Matrices à affiner au soleil, une fois la balance des blancs calibrée sur l'herbe.
LOOKS = {
    "brut": {"m": [1, 0, 0, 0, 1, 0, 0, 0, 1], "sat": 1.0, "contrast": 1.0},
    # rouge et bleu échangés : ciel bleu, feuillage blanc-cyan
    "bleu": {"m": [0, 0, 1, 0, 1, 0, 1, 0, 0], "sat": 1.25, "contrast": 1.05},
    # noir et blanc contrasté (luminance du canal IR)
    "nb": {"m": [1, 0, 0, 0, 1, 0, 0, 0, 1], "sat": 0.0, "contrast": 1.35},
    # feuillage rose-magenta
    "rose": {"m": [1.25, 0, 0, 0, 0.78, 0, 0, 0.05, 1.02], "sat": 1.3, "contrast": 1.05},
    # feuillage doré, ciel bleu profond
    "dore": {"m": [0, 0.1, 1.15, 0, 1.0, 0, 0.82, 0, 0], "sat": 1.35, "contrast": 1.1},
}
DEFAULT_BASE_CCM = [1.4086, -0.3480, -0.0606, -0.3225, 1.5717, -0.2492, -0.0117, -0.6308, 1.6425]

DEFAULT_SETTINGS = {
    "iso": 0,                 # 0 = auto
    "shutter_us": 0,          # 0 = auto
    "ev": 0.0,                # correction d'exposition (IL)
    "look": "bleu",
    "look_in_jpeg": True,
    "format": "jpg+raw",      # jpg | jpg+raw | raw
    "jpeg_quality": 92,
    "metering": "matrix",     # matrix | centre | spot
    "iso_max": 1600,
    "slowest_auto_us": 66667,
    "awb": "ir",              # auto | daylight | ir (gains calibrés sur l'herbe)
    "awb_gains": None,        # [rouge, bleu] calibrés, ou None
    "af": "continuous",       # continuous | single | manual
    "lens_position": 1.0,     # dioptries (0 = infini)
    "af_zone": "point",       # point | large
    "nr": "light",            # off | light | strong
    "sharpness": 0,           # -2..+2
    "led": True,
    "timer_s": 0,             # 0 | 2 | 10
    "mode": "photo",          # photo | pose | rafale | interv | bracket
    "pose_s": 4,              # 0 = B
    "rafale_fps": 0,          # 0 = max, 5, 2
    "rafale_limit": 100,
    "rafale_raw": False,
    "interv_s": 10,
    "interv_count": 300,      # 0 = sans limite
    "interv_delay_s": 0,
    "bracket_n": 3,
    "bracket_step": 1.0,
    "bracket_order": "0-+",   # 0-+ | -0+
    "zoom": 1.0,
    "rotation": 0,            # 0 | 180 (caméra montée tête en bas)
    "name": "HALO 720",
}

SCHEMA = {
    "iso": ("choice", [0] + ISO_STEPS),
    "shutter_us": ("choice", [0] + SHUTTER_STEPS),
    "ev": ("float", -3.0, 3.0),
    "look": ("choice", LOOK_ORDER),
    "look_in_jpeg": ("bool",),
    "format": ("choice", ["jpg", "jpg+raw", "raw"]),
    "jpeg_quality": ("int", 60, 100),
    "metering": ("choice", ["matrix", "centre", "spot"]),
    "iso_max": ("choice", ISO_STEPS),
    "slowest_auto_us": ("choice", [s for s in SHUTTER_STEPS if 4000 <= s <= 1000000]),
    "awb": ("choice", ["auto", "daylight", "ir"]),
    "awb_gains": ("gains", 0.1, 8.0),
    "af": ("choice", ["continuous", "single", "manual"]),
    "lens_position": ("float", 0.0, 15.0),
    "af_zone": ("choice", ["point", "large"]),
    "nr": ("choice", ["off", "light", "strong"]),
    "sharpness": ("int", -2, 2),
    "led": ("bool",),
    "timer_s": ("choice", [0, 2, 10]),
    "mode": ("choice", ["photo", "pose", "rafale", "interv", "bracket"]),
    "pose_s": ("choice", POSE_STEPS),
    "rafale_fps": ("choice", [0, 5, 2]),
    "rafale_limit": ("int", 5, 500),
    "rafale_raw": ("bool",),
    "interv_s": ("int", 1, 3600),
    "interv_count": ("int", 0, 100000),
    "interv_delay_s": ("int", 0, 86400),
    "bracket_n": ("choice", [3, 5]),
    "bracket_step": ("choice", [1.0, 2.0]),
    "bracket_order": ("choice", ["0-+", "-0+"]),
    "zoom": ("float", 1.0, 5.0),
    "rotation": ("choice", [0, 180]),
    "name": ("str", 1, 24),
}

NAME_RE = re.compile(r"^HALO_[0-9_]+\.(jpg|dng|json)$")
SEQ_RE = re.compile(r"^HALO_(\d{4,6})_")

# --------------------------------------------------------------------------------------
# Outils
# --------------------------------------------------------------------------------------
app = Flask(__name__)
try:
    app.json.ensure_ascii = False
except AttributeError:  # pragma: no cover
    app.config["JSON_AS_ASCII"] = False


def log(*args):
    print(*args, file=sys.stderr, flush=True)


def fsync_write(path, data):
    """Écrit un fichier de façon atomique et durable (survit à une coupure de batterie)."""
    tmp = path.with_name("." + path.name + ".tmp")
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def fsync_dir(path):
    try:
        fd = os.open(path, os.O_RDONLY)
        os.fsync(fd)
        os.close(fd)
    except OSError:
        pass


def set_thread_nice(n):
    try:
        os.setpriority(os.PRIO_PROCESS, threading.get_native_id(), n)
    except (OSError, AttributeError):
        pass


# --------------------------------------------------------------------------------------
# Réglages
# --------------------------------------------------------------------------------------
settings = dict(DEFAULT_SETTINGS)
settings_lock = threading.RLock()


def clean_settings(data):
    out = {}
    for key, value in data.items():
        if key not in SCHEMA:
            continue
        kind = SCHEMA[key][0]
        try:
            if kind == "choice":
                choices = SCHEMA[key][1]
                if isinstance(choices[0], (int, float)) and not isinstance(choices[0], bool):
                    value = type(choices[0])(float(value))
                    if isinstance(choices[0], int):
                        value = int(value)
                if value not in choices:
                    raise ValueError
            elif kind == "float":
                lo, hi = SCHEMA[key][1:]
                value = round(min(hi, max(lo, float(value))), 2)
            elif kind == "int":
                lo, hi = SCHEMA[key][1:]
                value = int(min(hi, max(lo, int(float(value)))))
            elif kind == "bool":
                if not isinstance(value, bool):
                    raise ValueError
            elif kind == "gains":
                if value is not None:
                    lo, hi = SCHEMA[key][1:]
                    r, b = value
                    value = [round(min(hi, max(lo, float(r))), 3), round(min(hi, max(lo, float(b))), 3)]
            elif kind == "str":
                lo, hi = SCHEMA[key][1:]
                value = str(value).strip()
                if not (lo <= len(value) <= hi) or any(ord(c) < 32 for c in value):
                    raise ValueError
        except (TypeError, ValueError):
            raise ValueError(f"valeur invalide pour {key} : {value!r}")
        out[key] = value
    return out


def load_settings():
    try:
        raw = json.loads(SETTINGS_FILE.read_text())
    except FileNotFoundError:
        return
    except Exception as exc:  # noqa: BLE001
        log("settings.json illisible, réglages par défaut :", exc)
        return
    if "look" not in raw:            # fichier de la v2 : ses gains n'étaient pas une calibration
        raw.pop("awb_gains", None)
        raw.pop("awb", None)
    for key, value in raw.items():
        try:
            settings.update(clean_settings({key: value}))
        except ValueError as exc:
            log("Réglage ignoré :", exc)


def save_settings():
    SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
    with settings_lock:
        data = json.dumps(settings, indent=1, ensure_ascii=False).encode()
    fsync_write(SETTINGS_FILE, data)


# --------------------------------------------------------------------------------------
# Événements (flux SSE vers le téléphone)
# --------------------------------------------------------------------------------------
class Bus:
    def __init__(self):
        self.cond = threading.Condition()
        self.version = 0
        self.events = deque(maxlen=200)
        self.next_id = 1

    def changed(self):
        with self.cond:
            self.version += 1
            self.cond.notify_all()

    def emit(self, kind, data):
        with self.cond:
            self.events.append((self.next_id, kind, data))
            self.next_id += 1
            self.version += 1
            self.cond.notify_all()


bus = Bus()

# --------------------------------------------------------------------------------------
# État
# --------------------------------------------------------------------------------------
state = {
    "phase": "idle",          # idle | timer | exposing | burst | interval | bracket | stacking
    "timer_end": None,
    "exposure_start": None, "exposure_end": None,
    "series": None,           # {id, kind, done, total, next_at, end_at, started}
    "error": None, "error_at": None,
    "last": None,
    "focus": {"x": 0.5, "y": 0.5, "state": "idle", "locked": False, "custom": False},
    "loupe": False,
    "shots": 0,
}
metadata = {}
system = {"throttled": None, "temp_c": None, "disk_free": None, "disk_total": None,
          "clients": 0, "viewers": 0, "started": time.time()}
picam2 = None
cam_lock = threading.RLock()
tap_ev = {"v": 0.0}
iso_cap = {"locked": False}

# --------------------------------------------------------------------------------------
# LED
# --------------------------------------------------------------------------------------
led = PWMLED(LED_GPIO)


class LedManager:
    """Signaux de la LED : démarrage (clignote), prête (fixe, faible), photo (éclair),
    retardateur (clignotement rapide), série (clignotement lent), erreur (4 éclairs longs)."""

    def __init__(self):
        self.mode = "boot"
        self.lock = threading.Lock()

    def set(self, mode):
        with self.lock:
            if mode == self.mode and mode not in ("shot", "error"):
                return
            self.mode = mode
            enabled = settings.get("led", True)
            try:
                if mode == "boot":
                    led.blink(on_time=0.2, off_time=0.2)
                elif mode == "error":
                    led.blink(on_time=0.5, off_time=0.2, n=4, background=True)
                    threading.Timer(3.0, lambda: self.set("ready")).start()
                elif not enabled:
                    led.off()
                elif mode == "ready":
                    led.value = 0.25
                elif mode == "timer":
                    led.blink(on_time=0.08, off_time=0.25)
                elif mode == "series":
                    led.blink(on_time=0.1, off_time=0.9)
                elif mode == "busy":
                    led.value = 1.0
                elif mode == "shot":
                    led.blink(on_time=0.06, off_time=0.06, n=1, background=True)
                    threading.Timer(0.15, self.restore).start()
                elif mode == "off":
                    led.off()
            except Exception:  # noqa: BLE001
                pass

    def restore(self):
        ph = state["phase"]
        if ph == "timer":
            self.set("timer")
        elif state["series"]:
            self.set("series")
        elif ph != "idle":
            self.set("busy")
        else:
            self.set("ready")


leds = LedManager()

# --------------------------------------------------------------------------------------
# Caméra
# --------------------------------------------------------------------------------------
def current_transform():
    flip = 1 if settings["rotation"] == 180 else 0
    return Transform(hflip=flip, vflip=flip)


def scaler_crop_max():
    return picam2.camera_properties.get("ScalerCropMaximum", (0, 0) + FULL_RES)


def crop_for_zoom(z, center=(0.5, 0.5)):
    x0, y0, w, h = scaler_crop_max()
    z = max(1.0, min(5.0, float(z)))
    cw, ch = int(w / z) & ~1, int(h / z) & ~1
    cx = x0 + int(center[0] * w) - cw // 2
    cy = y0 + int(center[1] * h) - ch // 2
    cx = max(x0, min(x0 + w - cw, cx))
    cy = max(y0, min(y0 + h - ch, cy))
    return (cx, cy, cw, ch)


def current_crop():
    if state["loupe"]:
        return crop_for_zoom(max(4.0, settings["zoom"] * 2), (state["focus"]["x"], state["focus"]["y"]))
    return crop_for_zoom(settings["zoom"])


wb = {"gains": None, "base_ccm": list(DEFAULT_BASE_CCM), "daylight": None}


def effective_look():
    return settings["look"] if settings["look_in_jpeg"] else "brut"


def current_gains():
    if settings["awb"] == "ir" and settings["awb_gains"]:
        return tuple(settings["awb_gains"])
    if settings["awb"] == "daylight" and wb["daylight"]:
        return tuple(wb["daylight"])
    return tuple(wb["gains"] or (1.0, 1.0))


def look_ccm(look):
    L = np.array(LOOKS[look]["m"], dtype=float).reshape(3, 3)
    B = np.array(wb["base_ccm"], dtype=float).reshape(3, 3)
    return tuple(float(x) for x in (L @ B).flatten())


def awb_controls():
    """Balance des blancs + look. Les gains sont toujours fixés par nous (calibration IR,
    lumière du jour mesurée, ou « gris moyen » recalculé toutes les 2 s en auto) :
    c'est ce qui permet d'imposer la matrice couleur du look à l'ISP."""
    look = effective_look()
    p = LOOKS[look]
    return {"AwbEnable": False, "ColourGains": current_gains(), "ColourCorrectionMatrix": look_ccm(look),
            "Saturation": float(p["sat"]), "Contrast": float(p["contrast"])}


def measure_wb_reference():
    """Au démarrage : l'AWB de libcamera donne des gains de départ et la matrice de base du capteur."""
    try:
        with cam_lock:
            picam2.set_controls({"AwbEnable": True, "AwbMode": controls.AwbModeEnum.Auto})
        time.sleep(1.2)
        md = picam2.capture_metadata()
        wb["gains"] = list(md.get("ColourGains", (1.0, 1.0)))
        if md.get("ColourCorrectionMatrix"):
            wb["base_ccm"] = list(md["ColourCorrectionMatrix"])
        with cam_lock:
            picam2.set_controls({"AwbMode": controls.AwbModeEnum.Daylight})
        time.sleep(1.2)
        wb["daylight"] = list(picam2.capture_metadata().get("ColourGains", wb["gains"]))
        log("Balance des blancs de départ :", wb)
    except Exception as exc:  # noqa: BLE001
        log("Mesure balance des blancs :", exc)


def lores_rgb_means(arr, box=None):
    w, h = PREVIEW_RES
    Y = arr[:h, :w].astype(np.float32)
    U = arr[h:h + h // 4].reshape(h // 2, w // 2).astype(np.float32) - 128
    V = arr[h + h // 4:h + h // 2].reshape(h // 2, w // 2).astype(np.float32) - 128
    if box:
        x0, y0, x1, y1 = box
        Y = Y[y0:y1, x0:x1]
        U = U[y0 // 2:y1 // 2, x0 // 2:x1 // 2]
        V = V[y0 // 2:y1 // 2, x0 // 2:x1 // 2]
    yy, uu, vv = float(Y.mean()), float(U.mean()), float(V.mean())
    return yy + 1.402 * vv, yy - 0.344136 * uu - 0.714136 * vv, yy + 1.772 * uu


def auto_wb_step():
    """Balance auto « gris moyen » (avec la matrice du look neutralisée par le calcul)."""
    if settings["awb"] == "daylight" or (settings["awb"] == "ir" and settings["awb_gains"]):
        return
    if state["phase"] != "idle" or state["focus"]["locked"]:
        return
    with cam_lock:
        arr = picam2.capture_array("lores")
    pw, ph = PREVIEW_RES
    R, G, B = lores_rgb_means(arr, (pw // 8, ph // 8, pw * 7 // 8, ph * 7 // 8))
    look = effective_look()
    if look != "brut":   # on revient à l'espace « brut » pour mesurer
        M = np.array(LOOKS[look]["m"], dtype=float).reshape(3, 3)
        try:
            R, G, B = np.linalg.solve(M, np.array([R, G, B]))
        except np.linalg.LinAlgError:
            return
    if min(R, G, B) < 4 or max(R, G, B) > 250:
        return
    g = list(current_gains())
    nr = g[0] * (G / R) ** 0.5
    nb = g[1] * (G / B) ** 0.5
    nr, nb = min(8.0, max(0.2, nr)), min(8.0, max(0.2, nb))
    if abs(nr - g[0]) / g[0] > 0.02 or abs(nb - g[1]) / g[1] > 0.02:
        wb["gains"] = [nr, nb]
        with cam_lock:
            picam2.set_controls({"ColourGains": (nr, nb)})


def exposure_controls():
    """Exposition pour la visée et la photo (mode courant)."""
    c = {}
    s = settings
    shutter = s["shutter_us"]
    if s["mode"] == "pose" and s["pose_s"] > 0:
        shutter = 0  # la pose longue n'est appliquée qu'au moment de la prise
    if state["focus"]["locked"] and state["focus"].get("ae"):
        exp, gain = state["focus"]["ae"]
        return {"AeEnable": False, "ExposureTime": int(exp), "AnalogueGain": float(gain),
                "FrameDurationLimits": (FRAME_US, max(FRAME_US, int(exp) + 1000))}
    if shutter > 0 and s["iso"] > 0:
        c.update(AeEnable=False, ExposureTime=int(shutter), AnalogueGain=s["iso"] / 100.0)
    else:
        c["AeEnable"] = True
        c["ExposureValue"] = float(s["ev"]) + tap_ev["v"]
        c["ExposureTime"] = int(shutter)                 # 0 = auto
        gain = s["iso"] / 100.0 if s["iso"] else 0.0
        if not s["iso"] and iso_cap["locked"]:
            gain = s["iso_max"] / 100.0
        c["AnalogueGain"] = gain                          # 0 = auto
    if shutter > 0:
        c["FrameDurationLimits"] = (max(FRAME_US, shutter + 500), max(FRAME_US, shutter + 500))
    else:
        c["FrameDurationLimits"] = (FRAME_US, max(FRAME_US, int(s["slowest_auto_us"]) + 500))
    c["AeMeteringMode"] = {"centre": controls.AeMeteringModeEnum.CentreWeighted,
                           "spot": controls.AeMeteringModeEnum.Spot,
                           "matrix": controls.AeMeteringModeEnum.Matrix}[s["metering"]]
    return c


def focus_controls():
    s = settings
    f = state["focus"]
    if f["locked"] and f.get("lens") is not None:
        return {"AfMode": controls.AfModeEnum.Manual, "LensPosition": float(f["lens"])}
    if s["af"] == "manual":
        return {"AfMode": controls.AfModeEnum.Manual, "LensPosition": float(s["lens_position"])}
    c = {"AfRange": controls.AfRangeEnum.Normal, "AfSpeed": controls.AfSpeedEnum.Fast}
    if f["custom"]:
        cx, cy, cw, ch = current_crop()
        size = 1 / 6 if s["af_zone"] == "point" else 1 / 3
        ww, wh = int(cw * size), int(cw * size)
        wx = int(cx + f["x"] * cw - ww / 2)
        wy = int(cy + f["y"] * ch - wh / 2)
        c["AfMetering"] = controls.AfMeteringEnum.Windows
        c["AfWindows"] = [(max(0, wx), max(0, wy), ww, wh)]
    else:
        c["AfMetering"] = controls.AfMeteringEnum.Auto
    c["AfMode"] = (controls.AfModeEnum.Continuous if s["af"] == "continuous" and not f.get("scanning")
                   else controls.AfModeEnum.Auto)
    return c


def image_controls():
    s = settings
    nr = {"off": controls.draft.NoiseReductionModeEnum.Off,
          "light": controls.draft.NoiseReductionModeEnum.Fast,
          "strong": controls.draft.NoiseReductionModeEnum.HighQuality}[s["nr"]]
    return {"Sharpness": 1.0 * (1.6 ** s["sharpness"]), "NoiseReductionMode": nr}


def all_controls():
    c = {}
    c.update(exposure_controls())
    c.update(awb_controls())
    c.update(focus_controls())
    c.update(image_controls())
    c["ScalerCrop"] = current_crop()
    return c


def apply_controls(extra=None):
    c = all_controls()
    if extra:
        c.update(extra)
    with cam_lock:
        picam2.set_controls(c)


def _on_request(req):
    try:
        md = req.get_metadata()
    except Exception:  # noqa: BLE001
        return
    metadata.update({k: md[k] for k in ("ExposureTime", "AnalogueGain", "DigitalGain", "LensPosition",
                                         "Lux", "ColourTemperature", "AfState", "FrameDuration",
                                         "ColourGains", "ScalerCrop", "FocusFoM") if k in md})
    metadata["t"] = time.time()


def camera_configuration():
    return picam2.create_video_configuration(
        main={"size": FULL_RES, "format": "YUV420"},
        lores={"size": PREVIEW_RES, "format": "YUV420"},
        raw={"size": FULL_RES},
        transform=current_transform(),
        buffer_count=4,
        controls={"FrameDurationLimits": (FRAME_US, FRAME_US)},
    )


def halo_tuning():
    """Réglage du capteur pour la HALO 720 (filtre 720 nm) : le fichier d'origine coupe le PDAF dès
    qu'il voit de l'infrarouge (« check_for_ir »), ce qui laisse une recherche par contraste très lente.
    Ici : PDAF autorisé en IR, puis affinage fin par contraste (décalage de mise au point de l'IR)."""
    try:
        model = Picamera2.global_camera_info()[0].get("Model", "imx708_noir")
        tuning = Picamera2.load_tuning_file(f"{model}.json")
        af = next(a["rpi.af"] for a in tuning["algorithms"] if "rpi.af" in a)
        af["check_for_ir"] = False
        af["conf_thresh"] = 12     # PDAF utilisé un peu plus tôt quand la lumière baisse (16 à l origine)
        af["conf_epsilon"] = 6
        for sp in af["speeds"].values():
            sp["step_fine"] = 0.25
            sp["step_frames"] = 3
            sp["dropout_frames"] = 3
        af["speeds"]["fast"]["step_coarse"] = 1.5
        return tuning
    except Exception as exc:  # noqa: BLE001
        log("Réglage AF HALO non appliqué :", exc)
        return None


def init_camera():
    global picam2
    picam2 = Picamera2(tuning=halo_tuning())
    picam2.post_callback = _on_request
    picam2.configure(camera_configuration())
    picam2.start()
    measure_wb_reference()
    apply_controls()


def restart_camera():
    with cam_lock:
        streaming = stream.encoder is not None
        stream.stop()
        picam2.stop()
        picam2.configure(camera_configuration())
        picam2.start()
        apply_controls()
        if streaming:
            stream.start()


# --------------------------------------------------------------------------------------
# Flux d'aperçu MJPEG (encodeur matériel, image neutre : le téléphone applique le look)
# --------------------------------------------------------------------------------------
class Stream(io.BufferedIOBase):
    def __init__(self):
        self.frame = None
        self.seq = 0
        self.cond = threading.Condition()
        self.encoder = None
        self.viewers = 0
        self.skip = 0

    def write(self, buf):
        if system["temp_c"] and system["temp_c"] > 80:   # Pi trop chaud : aperçu réduit
            self.skip ^= 1
            if self.skip:
                return len(buf)
        with self.cond:
            self.frame = bytes(buf)
            self.seq += 1
            self.cond.notify_all()
        return len(buf)

    def start(self):
        with cam_lock:
            if self.encoder is None:
                self.encoder = MJPEGEncoder()
                picam2.start_encoder(self.encoder, FileOutput(self), name="lores", quality=Quality.HIGH)

    def stop(self):
        with cam_lock:
            if self.encoder is not None:
                try:
                    picam2.stop_encoder(self.encoder)
                except Exception:  # noqa: BLE001
                    pass
                self.encoder = None


stream = Stream()

# --------------------------------------------------------------------------------------
# Fichiers, index des photos
# --------------------------------------------------------------------------------------
index = {}            # stem -> info
index_lock = threading.RLock()
seq_counter = {"n": 0}


def sidecar_path(stem):
    return PHOTO_DIR / f"{stem}.json"


def load_index():
    PHOTO_DIR.mkdir(parents=True, exist_ok=True)
    THUMB_DIR.mkdir(exist_ok=True)
    SPOOL_DIR.mkdir(exist_ok=True)
    for f in PHOTO_DIR.glob(".*.tmp"):
        f.unlink(missing_ok=True)
    for f in THUMB_DIR.glob(".*.tmp"):
        f.unlink(missing_ok=True)
    stems = {p.stem for p in PHOTO_DIR.glob("HALO_*.jpg")} | {p.stem for p in PHOTO_DIR.glob("HALO_*.dng")}
    legacy = 0
    for stem in stems:
        info = None
        sc = sidecar_path(stem)
        if sc.exists():
            try:
                d = json.loads(sc.read_text())
                if isinstance(d, dict) and d.get("v") == 3:
                    info = d
            except Exception:  # noqa: BLE001
                info = None
        if info is None:
            info = legacy_info(stem)
            legacy += 1
        refresh_files(info)
        index[stem] = info
        m = SEQ_RE.match(stem)
        if m:
            seq_counter["n"] = max(seq_counter["n"], int(m.group(1)))
    log(f"Index : {len(index)} photos ({legacy} anciennes)")


def legacy_info(stem):
    """Photo d'une version précédente : on reconstitue ce qu'on peut."""
    jpg = PHOTO_DIR / f"{stem}.jpg"
    t = (jpg if jpg.exists() else PHOTO_DIR / f"{stem}.dng").stat().st_mtime
    m = SEQ_RE.match(stem)
    info = {"v": 3, "stem": stem, "seq": int(m.group(1)) if m else 0, "time": t,
            "look": "brut", "orientation": 0, "zoom": 1.0, "series": None, "mode": "photo",
            "legacy": True, "w": FULL_RES[0], "h": FULL_RES[1]}
    try:
        old = json.loads((PHOTO_DIR / f"{stem}.json").read_text())
        info["exposure_us"] = float(old.get("ExposureTime", 0))
        info["iso"] = round(float(old.get("AnalogueGain", 1)) * 100)
        info["lens"] = float(old.get("LensPosition", 0))
        info["camera"] = {k: old[k] for k in ("ColourGains", "ColourCorrectionMatrix", "SensorBlackLevels") if k in old}
    except Exception:  # noqa: BLE001
        pass
    return info


def refresh_files(info):
    stem = info["stem"]
    jpg = PHOTO_DIR / f"{stem}.jpg"
    dng = PHOTO_DIR / f"{stem}.dng"
    if jpg.exists():
        j = info.get("jpg") or {}
        st = jpg.stat()
        if j.get("size") != st.st_size:
            j = {"size": st.st_size, "sha256": None}
        info["jpg"] = j
    else:
        info["jpg"] = None
    if dng.exists():
        d = info.get("dng") or {}
        st = dng.stat()
        if not isinstance(d, dict) or d.get("size") != st.st_size:
            d = {"size": st.st_size, "sha256": None}
        info["dng"] = d
    elif info.get("dng") != "pending":
        info["dng"] = None


def save_sidecar(info):
    fsync_write(sidecar_path(info["stem"]), json.dumps(info, ensure_ascii=False, indent=1).encode())


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_hashes(info):
    changed = False
    for key, ext in (("jpg", "jpg"), ("dng", "dng")):
        d = info.get(key)
        if isinstance(d, dict) and not d.get("sha256"):
            p = PHOTO_DIR / f"{info['stem']}.{ext}"
            if p.exists():
                d["sha256"] = sha256_file(p)
                changed = True
    if changed:
        try:
            save_sidecar(info)
        except OSError:
            pass


def new_stem():
    with index_lock:
        seq_counter["n"] += 1
        n = seq_counter["n"]
    return n, f"HALO_{n:04d}_{datetime.now():%Y%m%d_%H%M%S}"


def public_info(info):
    out = {k: info.get(k) for k in ("stem", "seq", "time", "look", "orientation", "zoom", "series",
                                     "mode", "exposure_us", "iso", "lens", "w", "h", "ev", "legacy",
                                     "preview_only", "camera")}
    out["jpg"] = info.get("jpg")
    out["dng"] = info.get("dng")
    out["thumb"] = (THUMB_DIR / f"{info['stem']}.jpg").exists()
    return out


def check_name(name):
    if not NAME_RE.match(name):
        abort(404)
    return name


# --------------------------------------------------------------------------------------
# EXIF minimal (orientation, exposition, look)
# --------------------------------------------------------------------------------------
def _rat(x, den=1000000):
    x = max(0.0, float(x))
    if x == 0:
        return (0, 1)
    if x < 1:
        return (1, max(1, round(1 / x))) if abs(1 / x - round(1 / x)) < 0.05 else (round(x * den), den)
    return (round(x * 1000), 1000)


def build_exif(info, w, h):
    """APP1 EXIF (TIFF little-endian) construit à la main : rapide et sans dépendance."""
    orient = {0: 1, 90: 6, 180: 3, 270: 8}.get(info.get("orientation", 0), 1)
    dt = datetime.fromtimestamp(info["time"]).strftime("%Y:%m:%d %H:%M:%S").encode() + b"\0"
    desc = json.dumps({"look": info.get("look"), "zoom": info.get("zoom"), "series": info.get("series"),
                       "halo": VERSION}, ensure_ascii=True).encode() + b"\0"
    make, model, soft = b"HALO 720\0", b"Camera Module 3 NoIR 720nm\0", f"HALO 720 {VERSION}\0".encode()
    exp_s = (info.get("exposure_us") or 0) / 1e6
    iso = int(info.get("iso") or 100)
    ifd0 = [(0x010E, 2, desc), (0x010F, 2, make), (0x0110, 2, model), (0x0112, 3, orient),
            (0x0131, 2, soft), (0x0132, 2, dt), (0x8769, 4, None)]
    exif = [(0x829A, 5, _rat(exp_s)), (0x8827, 3, min(65535, iso)), (0x9003, 2, dt),
            (0x920A, 5, (474, 100)), (0xA002, 4, w), (0xA003, 4, h), (0xA405, 3, round(FOCAL_35MM * info.get("zoom", 1)))]
    TYPES = {2: 1, 3: 2, 4: 4, 5: 8}

    def pack_ifd(entries, base, sub_off=None):
        n = len(entries)
        head = struct.pack("<H", n)
        data_off = base + 2 + n * 12 + 4
        body, extra = b"", b""
        for tag, typ, val in entries:
            if tag == 0x8769:
                body += struct.pack("<HHII", tag, 4, 1, sub_off)
                continue
            if typ == 2:
                raw, cnt = val, len(val)
            elif typ == 3:
                raw, cnt = struct.pack("<H", val), 1
            elif typ == 4:
                raw, cnt = struct.pack("<I", val), 1
            else:
                raw, cnt = struct.pack("<II", *val), 1
            if len(raw) <= 4:
                body += struct.pack("<HHI", tag, typ, cnt) + raw.ljust(4, b"\0")
            else:
                body += struct.pack("<HHII", tag, typ, cnt, data_off + len(extra))
                extra += raw + (b"\0" if len(raw) % 2 else b"")
        return head + body + struct.pack("<I", 0) + extra

    tiff_head = b"II*\0" + struct.pack("<I", 8)
    ifd0_size = len(pack_ifd(ifd0, 8, 0))
    sub_off = 8 + ifd0_size
    blob = tiff_head + pack_ifd(ifd0, 8, sub_off) + pack_ifd(exif, sub_off)
    payload = b"Exif\0\0" + blob
    return b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload


def with_exif(jpeg, exif_seg):
    # simplejpeg écrit SOI + APP0 (JFIF) : on insère l'APP1 juste après l'APP0
    if jpeg[2:4] == b"\xff\xe0":
        l = struct.unpack(">H", jpeg[4:6])[0]
        cut = 4 + l
        return jpeg[:cut] + exif_seg + jpeg[cut:]
    return jpeg[:2] + exif_seg + jpeg[2:]


# --------------------------------------------------------------------------------------
# Chaîne de traitement : capture -> JPEG (2 ouvriers) -> RAW (1 ouvrier, basse priorité)
# --------------------------------------------------------------------------------------
jpeg_q = queue.Queue(maxsize=1)
raw_q = queue.Queue()
raw_ram = {"n": 0}
RAW_RAM_MAX = 2
pending = {"jpg": 0, "raw": 0}
pending_lock = threading.Lock()


def queue_status():
    with pending_lock:
        return dict(pending)


held_reqs = {"n": 0}
held_lock = threading.Lock()


def grab(raw_wanted, check=None, timeout=5.0, keep=False):
    """Récupère l'image suivante du flux capteur. check(md) -> bool pour attendre une image donnée.
    keep=True : on garde la requête (pas de copie de 18 Mo) ; l'ouvrier JPEG la libérera."""
    t0 = time.time()
    while True:
        with cam_lock:
            req = picam2.capture_request()
        release = True
        try:
            md = req.get_metadata()
            if check is not None and not check(md) and time.time() - t0 < timeout:
                continue
            raw = req.make_buffer("raw") if raw_wanted else None
            if keep:
                release = False
                with held_lock:
                    held_reqs["n"] += 1
                return req, raw, md
            return req.make_array("main"), raw, md
        finally:
            if release:
                req.release()


def take_shot(series=None, raw=None, orientation=0, extra=None):
    """Prend une image (déjà exposée selon les contrôles en cours) et la met en file."""
    free = system["disk_free"]
    if free is not None and free < SD_RESERVE:
        set_error("microSD pleine")
        return None
    fmt = settings["format"]
    raw_wanted = (fmt != "jpg") if raw is None else raw
    t_press = time.time()
    keep = held_reqs["n"] < 1 and series is None      # photo isolée : zéro copie
    main, rawbuf, md = grab(raw_wanted, check=(extra or {}).get("check"),
                            timeout=(extra or {}).get("timeout", 5.0), keep=keep)
    n, stem = new_stem()
    look = settings["look"]
    crop = md.get("ScalerCrop", current_crop())
    x0, y0, w0, h0 = scaler_crop_max()
    zoom = round(w0 / crop[2], 2) if crop[2] else 1.0
    info = {"v": 3, "stem": stem, "seq": n, "time": t_press, "look": look,
            "look_in_jpeg": settings["look_in_jpeg"], "orientation": orientation, "zoom": zoom,
            "crop": list(crop), "series": series, "mode": settings["mode"],
            "exposure_us": md.get("ExposureTime"), "iso": round(md.get("AnalogueGain", 1) * md.get("DigitalGain", 1) * 100),
            "lens": md.get("LensPosition"), "lux": md.get("Lux"), "ev": settings["ev"],
            "camera": dict({k: md.get(k) for k in ("ColourGains", "ColourCorrectionMatrix", "SensorBlackLevels",
                                                    "ColourTemperature") if k in md},
                           base_ccm=list(wb["base_ccm"]), look_matrix=LOOKS[effective_look()]),
            "jpg": "pending" if fmt != "raw" else None, "dng": "pending" if rawbuf is not None else None,
            "preview_only": fmt == "raw"}
    if extra and extra.get("bracket_ev") is not None:
        info["bracket_ev"] = extra["bracket_ev"]
    with index_lock:
        index[stem] = info
    state["shots"] += 1
    state["last"] = stem
    with pending_lock:
        pending["jpg"] += 1
        if rawbuf is not None:
            pending["raw"] += 1
    bus.emit("shot", {"stem": stem, "seq": n, "series": series, "t": t_press})
    leds.set("shot")
    jpeg_q.put((info, main))          # bloque si la file est pleine (rafale) : pas de saturation mémoire
    if rawbuf is not None:
        if raw_ram["n"] < RAW_RAM_MAX:
            raw_ram["n"] += 1
            raw_q.put((info, rawbuf, md, None))
        else:   # trop de RAW en mémoire : on les met sur la carte en attendant
            sp = SPOOL_DIR / f"{stem}.raw"
            rawbuf.tofile(sp)
            raw_q.put((info, None, md, sp))
    return stem


def jpeg_worker():
    set_thread_nice(2)
    while True:
        info, main = jpeg_q.get()
        try:
            if isinstance(main, np.ndarray):
                process_jpeg(info, main)
            else:   # requête caméra gardée : on encode directement depuis son tampon
                try:
                    with MappedArray(main, "main") as m:
                        process_jpeg(info, m.array)
                finally:
                    main.release()
                    with held_lock:
                        held_reqs["n"] -= 1
        except Exception as exc:  # noqa: BLE001
            log("Erreur JPEG :", info["stem"], repr(exc))
            set_error(f"enregistrement : {exc}")
        finally:
            del main
            with pending_lock:
                pending["jpg"] -= 1
            bus.changed()


def resize_plane(p, w, h):
    from PIL import Image
    return np.asarray(Image.fromarray(p).resize((w, h), Image.BILINEAR))


def process_jpeg(info, main):
    T = [("début", time.time())]
    W, H = FULL_RES
    main = main.reshape(-1)[:W * H * 3 // 2].reshape(H * 3 // 2, W) if main.shape[1] != W else main
    Y = main[:H]
    U = main[H:H + H // 4].reshape(H // 2, W // 2)
    V = main[H + H // 4:H + H // 2].reshape(H // 2, W // 2)
    crop = info["crop"]
    # Zoom : l'ISP agrandit le recadrage à 4608 × 2592 ; on revient à la définition réelle.
    if info["zoom"] > 1.01:
        cw, ch = crop[2] & ~15, crop[3] & ~15
        Y = resize_plane(Y, cw, ch)
        U = resize_plane(U, cw // 2, ch // 2)
        V = resize_plane(V, cw // 2, ch // 2)
    T.append(("zoom", time.time()))
    h, w = Y.shape
    info["w"], info["h"] = w, h
    exif = build_exif(info, w, h)
    stem = info["stem"]
    # vignette (≈ 30 Ko) en premier : le téléphone l'affiche tout de suite
    f = 8 if w >= 2304 else 4
    tw, th = (w // f) & ~15, (h // f) & ~15
    from PIL import Image
    ty = np.ascontiguousarray(np.asarray(Image.fromarray(np.ascontiguousarray(Y)).reduce(f))[:th, :tw])
    tu = np.ascontiguousarray(np.asarray(Image.fromarray(np.ascontiguousarray(U)).reduce(f))[:th // 2, :tw // 2])
    tv = np.ascontiguousarray(np.asarray(Image.fromarray(np.ascontiguousarray(V)).reduce(f))[:th // 2, :tw // 2])
    thumb = simplejpeg.encode_jpeg_yuv_planes(ty, tu, tv, quality=78)
    fsync_write(THUMB_DIR / f"{stem}.jpg", with_exif(thumb, build_exif(info, tw, th)))
    T.append(("vignette", time.time()))
    bus.emit("thumb", {"stem": stem})
    if info.get("preview_only"):
        Y2, U2, V2 = Y[::2, ::2], U[::2, ::2], V[::2, ::2]
        Y, U, V = (np.ascontiguousarray(a) for a in (Y2, U2, V2))
        h, w = Y.shape
        exif = build_exif(info, w, h)
    jpg = simplejpeg.encode_jpeg_yuv_planes(np.ascontiguousarray(Y), np.ascontiguousarray(U),
                                            np.ascontiguousarray(V), quality=int(settings["jpeg_quality"]))
    T.append(("encodage", time.time()))
    data = with_exif(jpg, exif)
    fsync_write(PHOTO_DIR / f"{stem}.jpg", data)
    T.append(("écriture", time.time()))
    info["jpg"] = {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    with index_lock:
        save_sidecar(info)
    fsync_dir(PHOTO_DIR)
    steps = " ".join(f"{n}={1000 * (t - T[i][1]):.0f}" for i, (n, t) in enumerate(T[1:]))
    log(f"Photo {stem} : {len(data) / 1e6:.1f} Mo en {time.time() - info['time']:.2f} s "
        f"(file {1000 * (T[0][1] - info['time']):.0f} ms ; {steps} ms)")
    bus.emit("photo", {"stem": stem, "info": public_info(info)})


def raw_worker():
    set_thread_nice(10)
    while True:
        info, rawbuf, md, spool = raw_q.get()
        t_wait = time.time()
        while (pending["jpg"] > 0 or state["phase"] == "burst") and time.time() - t_wait < 20:
            time.sleep(0.05)    # le JPEG passe d'abord (carte SD libre pour lui)
        try:
            if spool is not None:
                rawbuf = np.fromfile(spool, dtype=np.uint8)
            out = PHOTO_DIR / f".{info['stem']}.dng.tmp"
            final = PHOTO_DIR / f"{info['stem']}.dng"
            dng.write_dng(str(out), rawbuf, picam2.camera_config["raw"], md, info)
            os.replace(out, final)
            fsync_dir(PHOTO_DIR)
            info["dng"] = {"size": final.stat().st_size, "sha256": sha256_file(final)}
            with index_lock:
                if info["stem"] in index:
                    save_sidecar(info)
            bus.emit("raw", {"stem": info["stem"], "dng": info["dng"]})
        except Exception as exc:  # noqa: BLE001
            log("Erreur RAW :", info["stem"], repr(exc))
            info["dng"] = None
        finally:
            if spool is not None:
                spool.unlink(missing_ok=True)
            else:
                raw_ram["n"] -= 1
            del rawbuf
            with pending_lock:
                pending["raw"] -= 1
            bus.changed()


def set_error(msg):
    state["error"] = msg
    state["error_at"] = time.time()
    log("Erreur :", msg)
    leds.set("error")
    bus.emit("error", {"message": msg})


# --------------------------------------------------------------------------------------
# Séquenceur : modes de prise de vue (tourne sur la caméra, indépendant du téléphone)
# --------------------------------------------------------------------------------------
cmd_q = queue.Queue()
series_counter = {"n": 0}
held = {"v": False}


def next_series(kind, total):
    series_counter["n"] += 1
    sid = f"S{int(time.time()) % 100000:05d}{series_counter['n']:02d}"
    state["series"] = {"id": sid, "kind": kind, "done": 0, "total": total, "started": time.time(),
                       "next_at": None, "end_at": None}
    bus.changed()
    return sid


def end_series():
    state["series"] = None
    bus.changed()


def interruptible_sleep(seconds, stop_on_press=True):
    """Attend ; renvoie False si un appui (arrêt) est arrivé."""
    end = time.time() + seconds
    while True:
        left = end - time.time()
        if left <= 0:
            return True
        try:
            cmd = cmd_q.get(timeout=min(left, 0.2))
        except queue.Empty:
            continue
        if cmd[0] in ("press", "stop") and stop_on_press:
            return False
        if cmd[0] == "release":
            held["v"] = False


def run_timer():
    t = settings["timer_s"]
    if t <= 0:
        return True
    state["phase"] = "timer"
    state["timer_end"] = time.time() + t
    leds.set("timer")
    bus.changed()
    ok = interruptible_sleep(t)
    state["timer_end"] = None
    return ok


def wait_exposure(target_us, tol=0.04):
    return lambda md: abs(md.get("ExposureTime", 0) - target_us) <= max(200, target_us * tol)


def restore_exposure(before, flush=False):
    """Retour rapide à l'exposition de visée après une pose forcée.
    flush=True (pose ≥ 0,3 s) : le capteur a encore des images longues en file ; on redémarre
    le flux (≈ 0,4 s) au lieu d'attendre qu'elles sortent (plusieurs fois la durée de pose)."""
    exp, gain = before
    fixed = {"AeEnable": False, "ExposureTime": int(exp), "AnalogueGain": float(gain),
             "FrameDurationLimits": (FRAME_US, max(FRAME_US, int(exp) + 500))}
    with cam_lock:
        if flush:
            streaming = stream.encoder is not None
            stream.stop()
            picam2.stop()
            c = all_controls()
            c.update(fixed)
            picam2.set_controls(c)
            picam2.start()
            if streaming:
                stream.start()
        else:
            picam2.set_controls(fixed)
    time.sleep(0.15)
    apply_controls()


def mode_photo(orientation):
    state["phase"] = "capture"
    bus.changed()
    take_shot(orientation=orientation)


def mode_pose(orientation):
    dur = settings["pose_s"]
    before = (metadata.get("ExposureTime", 20000), metadata.get("AnalogueGain", 1.0))
    if dur == 0:
        return mode_bulb(orientation, before)
    us = int(dur * 1_000_000)
    gain = settings["iso"] / 100.0 if settings["iso"] else 1.0
    state["phase"] = "exposing"
    state["exposure_start"] = time.time()
    state["exposure_end"] = time.time() + 2 * dur + 0.5   # l'image en cours doit d'abord finir
    leds.set("busy")
    bus.changed()
    with cam_lock:
        picam2.set_controls({"AeEnable": False, "ExposureTime": us, "AnalogueGain": gain,
                             "FrameDurationLimits": (us + 1000, us + 1000)})
    try:
        take_shot(orientation=orientation, extra={"check": wait_exposure(us), "timeout": 3 * dur + 5})
    finally:
        state["exposure_start"] = state["exposure_end"] = None
        restore_exposure(before, flush=us >= 300000)


def mode_bulb(orientation, before):
    """Pose B : tant qu'on tient le déclencheur, on cumule des poses de 1 s (moyenne, peu de bruit)."""
    us = max(settings["shutter_us"], 1_000_000) if settings["shutter_us"] >= 500000 else 1_000_000
    gain = settings["iso"] / 100.0 if settings["iso"] else 1.0
    with cam_lock:
        picam2.set_controls({"AeEnable": False, "ExposureTime": us, "AnalogueGain": gain,
                             "FrameDurationLimits": (us + 1000, us + 1000)})
    state["phase"] = "stacking"
    state["exposure_start"] = time.time()
    state["exposure_end"] = None
    leds.set("busy")
    bus.changed()
    acc = None
    n = 0
    md0 = None
    try:
        held["v"] = True
        while held["v"] and n < 600:
            main, _, md = grab(False, check=wait_exposure(us), timeout=3 * us / 1e6 + 3)
            acc = main.astype(np.uint32) if acc is None else acc + main
            md0 = md
            n += 1
            state["series"] = None
            bus.emit("stack", {"frames": n})
            while True:
                try:
                    cmd = cmd_q.get_nowait()
                except queue.Empty:
                    break
                if cmd[0] in ("release", "press", "stop"):
                    held["v"] = False
        if acc is None:
            return
        main = (acc // n).astype(np.uint8)
        del acc
        n_, stem = new_stem()
        crop = md0.get("ScalerCrop", current_crop())
        info = {"v": 3, "stem": stem, "seq": n_, "time": state["exposure_start"], "look": settings["look"],
                "look_in_jpeg": settings["look_in_jpeg"], "orientation": orientation,
                "zoom": round(scaler_crop_max()[2] / crop[2], 2), "crop": list(crop), "series": None,
                "mode": "pose", "exposure_us": us * n, "iso": round(gain * 100), "lens": md0.get("LensPosition"),
                "bulb_frames": n, "jpg": "pending", "dng": None, "camera": {}}
        with index_lock:
            index[stem] = info
        with pending_lock:
            pending["jpg"] += 1
        state["last"] = stem
        bus.emit("shot", {"stem": stem, "seq": n_, "series": None, "t": info["time"]})
        jpeg_q.put((info, main))
    finally:
        state["exposure_start"] = state["exposure_end"] = None
        held["v"] = False
        restore_exposure(before, flush=True)


def mode_rafale(orientation):
    fps = settings["rafale_fps"]
    limit = settings["rafale_limit"]
    sid = next_series("rafale", limit)
    state["phase"] = "burst"
    leds.set("series")
    held["v"] = True
    period = 1.0 / fps if fps else 0
    n = 0
    try:
        while held["v"] and n < limit:
            t = time.time()
            take_shot(series=sid, raw=settings["rafale_raw"], orientation=orientation)
            n += 1
            state["series"]["done"] = n
            bus.changed()
            wait = period - (time.time() - t)
            while True:
                try:
                    cmd = cmd_q.get(timeout=max(0.0, wait)) if wait > 0 else cmd_q.get_nowait()
                except queue.Empty:
                    break
                if cmd[0] in ("release", "press", "stop"):
                    held["v"] = False
                    break
                wait = period - (time.time() - t)
    finally:
        held["v"] = False
        end_series()


def mode_interv(orientation):
    s = settings
    every = s["interv_s"]
    total = s["interv_count"]
    sid = next_series("interv", total)
    leds.set("series")
    try:
        if s["interv_delay_s"] > 0:
            state["phase"] = "interval"
            state["series"]["next_at"] = time.time() + s["interv_delay_s"]
            bus.changed()
            if not interruptible_sleep(s["interv_delay_s"]):
                return
        t0 = time.time()
        n = 0
        while total == 0 or n < total:
            state["phase"] = "interval"
            take_shot(series=sid, orientation=orientation)
            n += 1
            sr = state["series"]
            sr["done"] = n
            nxt = t0 + n * every
            sr["next_at"] = nxt
            sr["end_at"] = (t0 + (total - 1) * every) if total else None
            bus.changed()
            if total and n >= total:
                break
            if not interruptible_sleep(max(0.0, nxt - time.time())):
                break
    finally:
        end_series()


def mode_bracket(orientation):
    s = settings
    n = s["bracket_n"]
    step = s["bracket_step"]
    half = (n - 1) // 2
    offs = [i * step for i in range(-half, half + 1)]
    if s["bracket_order"] == "0-+":
        offs = [0.0] + [o for o in offs if o < 0] + [o for o in offs if o > 0]
    before = (metadata.get("ExposureTime", 20000), metadata.get("AnalogueGain", 1.0))
    sid = next_series("bracket", n)
    state["phase"] = "bracket"
    leds.set("series")
    exp0, gain0 = before
    try:
        for i, ev in enumerate(offs):
            want = exp0 * (2 ** ev)
            gain = gain0
            if want < 60:                               # trop court : on baisse le gain
                gain = max(1.0, gain0 * want / 60)
                want = 60
            us = int(want)
            with cam_lock:
                picam2.set_controls({"AeEnable": False, "ExposureTime": us, "AnalogueGain": gain,
                                     "FrameDurationLimits": (max(FRAME_US, us + 1000), max(FRAME_US, us + 1000))})
            take_shot(series=sid, orientation=orientation,
                      extra={"check": wait_exposure(us), "timeout": 2 + 3 * us / 1e6, "bracket_ev": ev})
            state["series"]["done"] = i + 1
            bus.changed()
    finally:
        end_series()
        restore_exposure(before, flush=max(exp0 * 2 ** max(offs), exp0) >= 300000)


MODES = {"photo": mode_photo, "pose": mode_pose, "rafale": mode_rafale, "interv": mode_interv,
         "bracket": mode_bracket}


def sequencer():
    while True:
        cmd = cmd_q.get()
        if cmd[0] != "press":
            if cmd[0] == "release":
                held["v"] = False
            continue
        orientation = cmd[1] if len(cmd) > 1 else 0
        mode = settings["mode"]
        state["error"] = None
        try:
            if run_timer():
                MODES[mode](orientation)
        except Exception as exc:  # noqa: BLE001
            log("Erreur prise de vue :", repr(exc))
            set_error(str(exc))
            try:
                apply_controls()
            except Exception:  # noqa: BLE001
                pass
        finally:
            state["phase"] = "idle"
            state["timer_end"] = None
            held["v"] = False
            leds.restore()
            bus.changed()


def busy():
    return state["phase"] != "idle" or state["series"] is not None


def press(orientation=0, source="app"):
    """Appui : déclenche le mode actif, ou arrête la série en cours."""
    if busy():
        cmd_q.put(("stop",))
        if state["phase"] in ("burst", "stacking"):
            held["v"] = False
        return "stop"
    cmd_q.put(("press", orientation))
    return "start"


def release():
    held["v"] = False
    cmd_q.put(("release",))


# --------------------------------------------------------------------------------------
# Déclencheur physique
# --------------------------------------------------------------------------------------
_btn = {"held": False, "toggle": False}


def on_button_pressed(btn):
    _btn["held"] = False
    mode = settings["mode"]
    toggling = mode == "rafale" or (mode == "pose" and settings["pose_s"] == 0)
    if toggling:
        # rafale / pose B au bouton : un appui démarre, un second arrête
        if busy():
            release()
            cmd_q.put(("stop",))
        else:
            press(last_orientation["v"], "bouton")
        _btn["toggle"] = True
        return
    _btn["toggle"] = False
    press(last_orientation["v"], "bouton")


def on_button_held(btn):
    _btn["held"] = True
    if state["series"] is None and state["phase"] in ("idle", "capture"):
        power_off("appui long")


last_orientation = {"v": 0}

button = Button(BUTTON_GPIO, pull_up=True, bounce_time=0.03, hold_time=HOLD_SECONDS)
button.when_pressed = on_button_pressed
button.when_held = on_button_held

# --------------------------------------------------------------------------------------
# Système
# --------------------------------------------------------------------------------------
THROTTLE_BITS = {0: "sous-tension", 1: "fréquence limitée", 2: "ralenti", 3: "limite de température"}


def read_throttled():
    try:
        out = subprocess.run(["vcgencmd", "get_throttled"], capture_output=True, text=True, timeout=3).stdout
        return int(out.strip().split("=")[1], 16)
    except Exception:  # noqa: BLE001
        return None


def describe_throttled(value):
    if not value:
        return []
    now = [t for b, t in THROTTLE_BITS.items() if value & (1 << b)]
    past = [t for b, t in THROTTLE_BITS.items() if value & (1 << (b + 16)) and t not in now]
    return [t + " (en cours)" for t in now] + [t + " (depuis le démarrage)" for t in past]


def power_log(line):
    try:
        if POWER_LOG.exists() and POWER_LOG.stat().st_size > 200_000:
            POWER_LOG.replace(POWER_LOG.with_suffix(".log.1"))
        with open(POWER_LOG, "a") as f:
            f.write(f"{datetime.now():%Y-%m-%d %H:%M:%S} up={time.monotonic():.0f}s {line}\n")
            f.flush()
            os.fsync(f.fileno())
    except Exception:  # noqa: BLE001
        pass


def power_off(reason, reboot=False):
    log("Extinction :", reason)
    power_log(("redémarrage" if reboot else "extinction") + f" demandé ({reason})")
    leds.set("busy")
    bus.emit("shutdown", {"reboot": reboot})
    os.sync()
    subprocess.Popen(REBOOT_CMD if reboot else SHUTDOWN_CMD)


def system_monitor():
    last_thr = -1
    last_alive = 0.0
    power_log(f"démarrage v{VERSION}")
    while True:
        thr = read_throttled()
        system["throttled"] = thr
        try:
            system["temp_c"] = round(int(Path("/sys/class/thermal/thermal_zone0/temp").read_text()) / 1000, 1)
        except Exception:  # noqa: BLE001
            system["temp_c"] = None
        try:
            du = shutil.disk_usage(PHOTO_DIR)
            system["disk_free"], system["disk_total"] = du.free, du.total
        except Exception:  # noqa: BLE001
            pass
        if thr is not None and thr != last_thr:
            power_log(f"throttled=0x{thr:x} {', '.join(describe_throttled(thr)) or 'OK'}")
            last_thr = thr
        if time.monotonic() - last_alive >= 60:
            power_log(f"vivant temp={system['temp_c']} clients={system['clients']}")
            last_alive = time.monotonic()
        # ISO auto maximum : on plafonne le gain quand l'AE le dépasse
        try:
            s = settings
            if not s["iso"] and s["iso_max"] < 1600 and not state["focus"]["locked"] and state["phase"] == "idle":
                g = metadata.get("AnalogueGain", 1.0)
                e = metadata.get("ExposureTime", 0)
                if not iso_cap["locked"] and g > s["iso_max"] / 100.0 + 0.05:
                    iso_cap["locked"] = True
                    apply_controls()
                elif iso_cap["locked"] and e < s["slowest_auto_us"] * 0.3:
                    iso_cap["locked"] = False
                    apply_controls()
            elif iso_cap["locked"]:
                iso_cap["locked"] = False
        except Exception:  # noqa: BLE001
            pass
        try:
            auto_wb_step()
        except Exception as exc:  # noqa: BLE001
            log("AWB :", exc)
        bus.changed()
        time.sleep(2)


def photos_left():
    free = system["disk_free"]
    if free is None:
        return None
    fmt = settings["format"]
    per = {"jpg": 3.0e6, "jpg+raw": 18.0e6, "raw": 16.0e6}[fmt]
    return max(0, int((free - SD_RESERVE) / per))


def af_state_name():
    st = metadata.get("AfState")
    try:
        return {0: "idle", 1: "scanning", 2: "focused", 3: "failed"}[int(st)]
    except (TypeError, ValueError, KeyError):
        return "idle"


def status_payload():
    thr = system["throttled"]
    now = time.time()
    q = queue_status()
    exp = metadata.get("ExposureTime")
    s = settings
    return {
        "v": bus.version, "now": now,
        "phase": state["phase"],
        "timer_left": round(max(0.0, state["timer_end"] - now), 1) if state["timer_end"] else None,
        "exposure_start": state["exposure_start"], "exposure_end": state["exposure_end"],
        "series": state["series"], "error": state["error"], "error_at": state["error_at"],
        "last": state["last"], "shots": state["shots"],
        "exposure_us": exp, "iso_now": round((metadata.get("AnalogueGain") or 1) * 100),
        "lens": metadata.get("LensPosition"), "lux": metadata.get("Lux"),
        "colour_temp": metadata.get("ColourTemperature"),
        "fps": round(1e6 / metadata["FrameDuration"], 1) if metadata.get("FrameDuration") else None,
        "af": af_state_name(), "focus": {k: v for k, v in state["focus"].items() if k in ("x", "y", "locked", "custom")},
        "temp_c": system["temp_c"], "hot": bool(system["temp_c"] and system["temp_c"] > 80),
        "disk_free": system["disk_free"], "disk_total": system["disk_total"], "photos_left": photos_left(),
        "sd_full": bool(system["disk_free"] is not None and system["disk_free"] < SD_RESERVE),
        "throttled": thr, "undervoltage_now": bool(thr and thr & 1),
        "undervoltage_seen": bool(thr and thr & (1 << 16)),
        "power_warnings": describe_throttled(thr), "battery": None,
        "queue": q, "uptime": round(now - system["started"]),
        "iso_capped": iso_cap["locked"],
    }


# --------------------------------------------------------------------------------------
# Mise au point au toucher
# --------------------------------------------------------------------------------------
def tap_metering(x, y):
    """Mesure sur le point : on compare la luminance du point à celle de l'image (aperçu)."""
    try:
        with cam_lock:
            arr = picam2.capture_array("lores")
        h = PREVIEW_RES[1]
        Y = arr[:h, :PREVIEW_RES[0]]
        r = 24
        px, py = int(x * PREVIEW_RES[0]), int(y * h)
        zone = Y[max(0, py - r):py + r, max(0, px - r):px + r]
        zm = float(zone.mean()) + 1
        fm = float(Y.mean()) + 1
        return max(-2.0, min(2.0, math.log2(fm / zm) * 0.8))
    except Exception:  # noqa: BLE001
        return 0.0


def focus_at(x, y, lock=False):
    x = min(1.0, max(0.0, float(x)))
    y = min(1.0, max(0.0, float(y)))
    f = state["focus"]
    f.update(x=x, y=y, custom=True)
    if lock:
        f["locked"] = True
        f["lens"] = metadata.get("LensPosition")
        f["ae"] = (metadata.get("ExposureTime", 20000), metadata.get("AnalogueGain", 1.0))
        apply_controls()
    else:
        f["locked"] = False
        f.pop("lens", None)
        f.pop("ae", None)
        tap_ev["v"] = tap_metering(x, y) if not (settings["shutter_us"] and settings["iso"]) else 0.0
        # Toucher = recherche complète et rapide sur le point (AF « auto » déclenché),
        # puis, en mode continu, suivi continu sur ce même point.
        f["scanning"] = True
        f["tap_id"] = f.get("tap_id", 0) + 1
        apply_controls({"AfMode": controls.AfModeEnum.Auto, "AfTrigger": controls.AfTriggerEnum.Start})
        threading.Thread(target=_after_tap, args=(f["tap_id"],), daemon=True).start()
    bus.changed()


def _after_tap(tap_id):
    f = state["focus"]
    t0 = time.time()
    time.sleep(0.15)
    while time.time() - t0 < 4.0 and f.get("tap_id") == tap_id:
        if af_state_name() in ("focused", "failed"):
            break
        time.sleep(0.05)
    if f.get("tap_id") != tap_id or f.get("locked"):
        return
    log(f"AF au toucher : {af_state_name()} en {time.time() - t0:.2f} s, lentille {metadata.get('LensPosition')}")
    f["scanning"] = False
    if settings["af"] == "continuous":
        apply_controls()      # suivi continu sur le point touché
    bus.changed()


def focus_reset():
    f = state["focus"]
    f.update(x=0.5, y=0.5, custom=False, locked=False)
    f.pop("lens", None)
    f.pop("ae", None)
    tap_ev["v"] = 0.0
    apply_controls()
    bus.changed()


# --------------------------------------------------------------------------------------
# Balance des blancs IR : calibration sur l'herbe au soleil
# --------------------------------------------------------------------------------------
def calibrate_wb():
    """Rend neutre le centre de l'image (herbe au soleil). 3 itérations sur l'aperçu."""
    gains = list(current_gains())
    w, h = PREVIEW_RES
    for _ in range(4):
        with cam_lock:   # mesure dans l'espace « brut » (matrice de base, sans look)
            picam2.set_controls({"AwbEnable": False, "ColourGains": tuple(gains),
                                 "ColourCorrectionMatrix": look_ccm("brut"), "Saturation": 1.0})
        time.sleep(0.6)
        with cam_lock:
            arr = picam2.capture_array("lores")
        R, G, B = lores_rgb_means(arr, (w // 2 - 60, h // 2 - 40, w // 2 + 60, h // 2 + 40))
        if min(R, G, B) <= 1:
            break
        gains[0] = min(8.0, max(0.1, gains[0] * (G / R) ** 0.9))
        gains[1] = min(8.0, max(0.1, gains[1] * (G / B) ** 0.9))
    with settings_lock:
        settings["awb"] = "ir"
        settings["awb_gains"] = [round(gains[0], 3), round(gains[1], 3)]
    save_settings()
    apply_controls()
    return settings["awb_gains"]


# --------------------------------------------------------------------------------------
# API HTTP
# --------------------------------------------------------------------------------------
@app.after_request
def no_cache(resp):
    if request.path.startswith("/api/"):
        resp.headers["Cache-Control"] = "no-store"
    return resp


@app.route("/api/info")
def api_info():
    return jsonify(version=VERSION, name=settings["name"], sensor="IMX708 NoIR", full_res=FULL_RES,
                   preview_res=PREVIEW_RES, focal_35mm=FOCAL_35MM, looks=LOOKS, look_order=LOOK_ORDER,
                   base_ccm=wb["base_ccm"],
                   look_names=LOOK_NAMES, iso_steps=ISO_STEPS, shutter_steps=SHUTTER_STEPS,
                   pose_steps=POSE_STEPS, max_zoom=5.0, battery=False)


@app.route("/api/state")
def api_state():
    with settings_lock:
        s = dict(settings)
    return jsonify(settings=s, status=status_payload(), total=len(index), version=VERSION)


@app.route("/api/events")
def api_events():
    """Canal temps réel : état (à chaque changement, au plus 5 fois/s) + événements."""
    last_id = int(request.args.get("last", 0) or 0)

    def gen():
        nonlocal last_id
        system["clients"] += 1
        try:
            last_v = -1
            last_send = 0.0
            yield "retry: 1000\n\n"
            while True:
                with bus.cond:
                    if bus.version == last_v:
                        bus.cond.wait(timeout=1.0)
                    evs = [e for e in list(bus.events) if e[0] > last_id]
                    v = bus.version
                for eid, kind, data in evs:
                    last_id = eid
                    yield f"id: {eid}\nevent: {kind}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
                now = time.time()
                if v != last_v or now - last_send > 2:
                    if now - last_send < 0.2 and v != last_v and not evs:
                        time.sleep(0.2 - (now - last_send))
                    last_v = bus.version
                    with settings_lock:
                        s = dict(settings)
                    yield f"event: state\ndata: {json.dumps({'status': status_payload(), 'settings': s}, ensure_ascii=False)}\n\n"
                    last_send = time.time()
        finally:
            system["clients"] -= 1

    resp = Response(gen(), mimetype="text/event-stream")
    resp.headers["Cache-Control"] = "no-store"
    resp.headers["X-Accel-Buffering"] = "no"
    return resp


@app.route("/stream.mjpg")
def api_stream():
    def gen():
        stream.viewers += 1
        system["viewers"] = stream.viewers
        stream.start()
        try:
            last = 0
            while True:
                with stream.cond:
                    if stream.seq == last:
                        stream.cond.wait(timeout=2)
                    frame, seq = stream.frame, stream.seq
                if frame is None or seq == last:
                    continue
                last = seq
                yield (b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + str(len(frame)).encode() +
                       b"\r\nX-Seq: " + str(seq).encode() + b"\r\n\r\n" + frame + b"\r\n")
        finally:
            stream.viewers -= 1
            system["viewers"] = stream.viewers
            if stream.viewers <= 0 and not busy():
                stream.stop()

    resp = Response(gen(), mimetype="multipart/x-mixed-replace; boundary=frame")
    resp.headers["Cache-Control"] = "no-store"
    return resp


@app.route("/api/frame.jpg")
def api_frame():
    """Une seule image d'aperçu (galerie hors visée, tests)."""
    stream.start()
    with stream.cond:
        stream.cond.wait(timeout=2)
        frame = stream.frame
    if stream.viewers <= 0:
        threading.Timer(3, lambda: stream.viewers <= 0 and not busy() and stream.stop()).start()
    if frame is None:
        abort(503)
    return Response(frame, mimetype="image/jpeg")


def body():
    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        abort(400)
    return data


@app.route("/api/shoot", methods=["POST"])
def api_shoot():
    d = request.get_json(silent=True) or {}
    orientation = int(d.get("orientation", 0)) % 360
    if orientation not in (0, 90, 180, 270):
        orientation = 0
    last_orientation["v"] = orientation
    action = d.get("action", "press")
    if action == "release":
        release()
        return jsonify(ok=True, action="release")
    if status_payload()["sd_full"] and not busy():
        return jsonify(ok=False, error="microSD pleine"), 507
    return jsonify(ok=True, action=press(orientation))


@app.route("/api/stop", methods=["POST"])
def api_stop():
    held["v"] = False
    cmd_q.put(("stop",))
    return jsonify(ok=True)


@app.route("/api/settings", methods=["GET", "POST"])
def api_settings():
    if request.method == "GET":
        return jsonify(settings)
    try:
        new = clean_settings(body())
    except ValueError as exc:
        return jsonify(ok=False, error=str(exc)), 400
    with settings_lock:
        rotation_changed = "rotation" in new and new["rotation"] != settings["rotation"]
        led_changed = "led" in new and new["led"] != settings["led"]
        if "ev" in new or "shutter_us" in new or "iso" in new:
            tap_ev["v"] = 0.0
        settings.update(new)
    save_settings()
    try:
        if rotation_changed:
            restart_camera()
        elif state["phase"] in ("idle", "capture", "timer") or state["series"] and state["series"]["kind"] == "interv":
            apply_controls()
        if led_changed:
            leds.mode = None
            leds.restore()
    except Exception as exc:  # noqa: BLE001
        log("Réglages caméra :", exc)
        return jsonify(ok=False, error=f"caméra : {exc}", settings=settings), 500
    bus.changed()
    return jsonify(ok=True, settings=settings)


@app.route("/api/focus", methods=["POST"])
def api_focus():
    d = body()
    if d.get("reset"):
        focus_reset()
    else:
        focus_at(d.get("x", 0.5), d.get("y", 0.5), lock=bool(d.get("lock")))
    return jsonify(ok=True, focus=status_payload()["focus"])


@app.route("/api/loupe", methods=["POST"])
def api_loupe():
    state["loupe"] = bool(body().get("on"))
    apply_controls()
    bus.changed()
    return jsonify(ok=True)


@app.route("/api/wb/calibrate", methods=["POST"])
def api_wb_calibrate():
    try:
        return jsonify(ok=True, gains=calibrate_wb())
    except Exception as exc:  # noqa: BLE001
        return jsonify(ok=False, error=str(exc)), 500


@app.route("/api/photos")
def api_photos():
    """Liste des photos, la plus récente d'abord. ?since=<seq> pour ne recevoir que les nouvelles."""
    since = int(request.args.get("since", -1))
    with index_lock:
        items = sorted(index.values(), key=lambda i: (i.get("seq", 0), i["time"]), reverse=True)
    if since >= 0:
        items = [i for i in items if i.get("seq", 0) > since]
    return jsonify(total=len(index), items=[public_info(i) for i in items])


@app.route("/api/photos/<stem>/hash")
def api_hash(stem):
    with index_lock:
        info = index.get(stem)
    if not info:
        abort(404)
    ensure_hashes(info)
    return jsonify(jpg=info.get("jpg"), dng=info.get("dng"))


@app.route("/api/photos/<stem>", methods=["DELETE"])
def api_delete(stem):
    if not re.match(r"^HALO_[0-9_]+$", stem):
        abort(404)
    removed = delete_stem(stem)
    if not removed:
        abort(404)
    return jsonify(ok=True, removed=removed)


def delete_stem(stem):
    removed = []
    for path in (PHOTO_DIR / f"{stem}.jpg", PHOTO_DIR / f"{stem}.dng", sidecar_path(stem), THUMB_DIR / f"{stem}.jpg"):
        try:
            path.unlink()
            removed.append(path.name)
        except FileNotFoundError:
            pass
    with index_lock:
        index.pop(stem, None)
    if state["last"] == stem:
        state["last"] = None
    bus.emit("deleted", {"stem": stem})
    return removed


@app.route("/api/photos/delete", methods=["POST"])
def api_delete_many():
    stems = [s for s in body().get("stems", []) if isinstance(s, str) and re.match(r"^HALO_[0-9_]+$", s)]
    for s in stems:
        delete_stem(s)
    os.sync()
    return jsonify(ok=True, n=len(stems))


@app.route("/photos/<name>")
def photo(name):
    check_name(name)
    return send_from_directory(PHOTO_DIR, name, max_age=3600, conditional=True)


@app.route("/thumb/<name>")
def thumb(name):
    check_name(name)
    p = THUMB_DIR / name
    if not p.exists():
        stem = name[:-4]
        src = PHOTO_DIR / name
        if not src.exists():
            abort(404)
        make_legacy_thumb(src, p)
    return send_from_directory(THUMB_DIR, name, max_age=86400)


thumb_lock = threading.Lock()


def make_legacy_thumb(src, dst):
    from PIL import Image
    with thumb_lock:
        if dst.exists():
            return
        with Image.open(src) as im:
            im.draft("RGB", (640, 640))
            im = im.convert("RGB")
            im.thumbnail((576, 576))
            buf = io.BytesIO()
            im.save(buf, "JPEG", quality=78)
        fsync_write(dst, buf.getvalue())


@app.route("/api/system/<action>", methods=["POST"])
def api_system(action):
    if action == "shutdown":
        power_off("application")
    elif action == "reboot":
        power_off("application", reboot=True)
    elif action == "erase_all":
        d = request.get_json(silent=True) or {}
        if d.get("confirm") != "EFFACER":
            return jsonify(ok=False, error="confirmation manquante"), 400
        with index_lock:
            stems = list(index.keys())
        for s in stems:
            delete_stem(s)
        os.sync()
        return jsonify(ok=True, n=len(stems))
    else:
        abort(404)
    return jsonify(ok=True)


@app.route("/api/wifi", methods=["GET", "POST"])
def api_wifi():
    con = "HALO-720"
    if request.method == "GET":
        try:
            out = subprocess.run(["nmcli", "-t", "-g", "802-11-wireless.ssid,802-11-wireless.band",
                                  "connection", "show", con], capture_output=True, text=True, timeout=5).stdout
            ssid, *rest = out.strip().split("\n") + [""]
            return jsonify(ssid=ssid, band=rest[0] if rest else "")
        except Exception as exc:  # noqa: BLE001
            return jsonify(ssid=None, error=str(exc))
    d = body()
    ssid = str(d.get("ssid", "")).strip()
    pw = str(d.get("password", ""))
    if not (1 <= len(ssid.encode()) <= 32) or not (8 <= len(pw) <= 63) or not pw.isascii():
        return jsonify(ok=False, error="nom 1–32 caractères, mot de passe 8–63 caractères"), 400
    r = subprocess.run(["sudo", "-n", "nmcli", "connection", "modify", con, "802-11-wireless.ssid", ssid,
                        "wifi-sec.psk", pw], capture_output=True, text=True, timeout=15)
    if r.returncode != 0:
        return jsonify(ok=False, error=r.stderr.strip() or "nmcli a échoué"), 500
    power_log(f"Wi-Fi modifié : {ssid}")
    return jsonify(ok=True, applied="au prochain démarrage")


@app.route("/api/update", methods=["POST"])
def api_update():
    """Mise à jour du logiciel de la caméra, envoyée par l'app (archive .tar.gz)."""
    data = request.get_data()
    if len(data) > 5_000_000:
        return jsonify(ok=False, error="archive trop grosse"), 400
    tmp = HALO_DIR / "update_tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir()
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tf:
            for m in tf.getmembers():
                if m.name.startswith("/") or ".." in m.name or not (m.isfile() or m.isdir()):
                    raise ValueError(f"chemin refusé : {m.name}")
            tf.extractall(tmp)
        new = tmp / "halo_camera.py"
        r = subprocess.run([sys.executable, "-m", "py_compile", str(new)], capture_output=True, text=True)
        if r.returncode != 0:
            raise ValueError("code invalide : " + r.stderr[-300:])
        m = re.search(r'^VERSION = "([^"]+)"', new.read_text(), re.M)
        if not m:
            raise ValueError("version introuvable")
        backup = HALO_DIR / f"backup_{VERSION}"
        backup.mkdir(exist_ok=True)
        shutil.copy2(HALO_DIR / "halo_camera.py", backup / "halo_camera.py")
        for f in tmp.iterdir():
            if f.is_file() and f.suffix in (".py", ".sh", ".txt"):
                shutil.copy2(f, HALO_DIR / f.name)
        os.sync()
    except Exception as exc:  # noqa: BLE001
        return jsonify(ok=False, error=str(exc)), 400
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    power_log(f"mise à jour {VERSION} -> {m.group(1)}")
    threading.Timer(1.0, lambda: os._exit(75)).start()   # systemd relance le service
    return jsonify(ok=True, version=m.group(1))


@app.route("/")
def root():
    return Response(f"HALO 720 v{VERSION} — ouvre l'app HALO 720 sur le téléphone.\n", mimetype="text/plain")


# --------------------------------------------------------------------------------------
# Lancement
# --------------------------------------------------------------------------------------
def main():
    load_settings()
    leds.set("boot")
    load_index()
    init_camera()
    for _ in range(2):
        threading.Thread(target=jpeg_worker, daemon=True).start()
    threading.Thread(target=raw_worker, daemon=True).start()
    threading.Thread(target=sequencer, daemon=True).start()
    threading.Thread(target=system_monitor, daemon=True).start()
    # RAW restés en attente sur la carte (coupure) : on les reprend
    for sp in sorted(SPOOL_DIR.glob("*.raw")):
        log("RAW orphelin ignoré (métadonnées perdues) :", sp.name)
        sp.unlink(missing_ok=True)
    leds.set("ready")
    log(f"HALO 720 v{VERSION} prêt — http://10.42.0.1:{WEB_PORT}")

    def stop(*_):
        try:
            power_log("arrêt du service")
            leds.set("off")
            stream.stop()
            picam2.stop()
        finally:
            os._exit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    WSGIRequestHandler.protocol_version = "HTTP/1.1"
    app.run(host="0.0.0.0", port=WEB_PORT, threaded=True, debug=False, use_reloader=False)


if __name__ == "__main__":
    main()

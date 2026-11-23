#!/usr/bin/env python3
"""
HALO 720 — appareil photo infrarouge DIY (Raspberry Pi Zero 2 W + Camera Module 3 NoIR)
=========================================================================================

Programme principal :
  * déclencheur physique sur GPIO17 (appui court = photo, appui long 3 s = extinction propre)
  * LED témoin sur GPIO27 (respiration lente = prêt, clignotement = retardateur,
    fixe = capture, 3 éclairs = photo enregistrée, 4 clignotements lents = erreur)
  * capture JPEG pleine résolution (4608 x 2592) + RAW DNG facultatif, sur la microSD
  * interface web (Flask) sur http://10.42.0.1:8000 (Wi-Fi "HALO-720") ou
    http://10.55.0.1:8000 (câble USB) : visée en direct, réglages, galerie, téléchargement.

Version 2 (vérifiée sur le matériel) :
  * appui long : drapeau interne (Button.was_held n'existe pas dans gpiozero 2.0)
  * une seule capture à la fois (verrou), réglages validés côté serveur
  * miniatures en cache sur disque, décodage réduit (draft JPEG) : pas de saturation mémoire
  * visée coupée quand personne ne regarde (économie de batterie)
  * noms de fichiers numérotés (HALO_0001_…) : ordre correct même si l'horloge recule
    (le Pi n'a pas de pile d'horloge)
  * journal d'alimentation ~/HALO/power.log (sous-tension, dernier signe de vie)
  * API JSON pour l'interface web
"""

import io
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

from flask import Flask, Response, abort, jsonify, render_template, request, send_from_directory
from gpiozero import Button, PWMLED
from picamera2 import Picamera2
from picamera2.encoders import MJPEGEncoder
from picamera2.outputs import FileOutput
from libcamera import controls, Transform

# --------------------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------------------
PHOTO_DIR = Path(os.environ.get("HALO_PHOTO_DIR", "/home/pi/HALO/photos"))
SETTINGS_FILE = Path(os.environ.get("HALO_SETTINGS", "/home/pi/HALO/settings.json"))
POWER_LOG = Path(os.environ.get("HALO_POWER_LOG", str(SETTINGS_FILE.parent / "power.log")))
THUMB_DIR = PHOTO_DIR / ".thumbs"
BUTTON_GPIO = 17          # déclencheur (vers GND, pull-up interne)
LED_GPIO = 27             # LED témoin (via 330 Ω vers GND)
HOLD_SECONDS = 3.0        # appui long = extinction
FULL_RES = (4608, 2592)   # IMX708 pleine résolution
PREVIEW_RES = (768, 432)  # visée web (16:9), encodée en MJPEG par le GPU
WEB_PORT = int(os.environ.get("HALO_PORT", "8000"))
SHUTDOWN_CMD = ["sudo", "-n", "/usr/sbin/shutdown", "-h", "now"]

ISO_CHOICES = [0, 100, 200, 400, 800, 1600]
SHUTTER_CHOICES = [0, 100, 250, 500, 1000, 2000, 4000, 8000, 16667, 33333, 66667,
                   125000, 250000, 500000, 1000000, 2000000, 5000000]

DEFAULT_SETTINGS = {
    "iso": 0,              # 0 = auto, sinon 100..1600 (gain analogique = iso/100)
    "shutter_us": 0,       # 0 = auto, sinon temps de pose en microsecondes
    "ev": 0.0,             # correction d'exposition en IL (-3..+3) quand l'exposition est auto
    "awb": "ir",           # "ir" (gains fixes 720 nm), "auto", "daylight", "manual"
    "awb_gains": [1.0, 1.0],   # gains (rouge, bleu) si awb == "manual"
    "af": "auto",          # "auto" (cycle AF avant chaque photo), "continuous", "manual"
    "lens_position": 2.0,  # dioptries si af == "manual" (0 = infini, 2 = 50 cm, 10 = 10 cm)
    "format": "jpg+dng",   # "jpg" ou "jpg+dng"
    "jpeg_quality": 95,
    "timer_s": 0,          # retardateur en secondes (0, 2, 10)
    "rotation": 0,         # 0 ou 180 (caméra montée tête en bas)
}

# Règles de validation : (type, paramètres)
SCHEMA = {
    "iso": ("choice", ISO_CHOICES),
    "shutter_us": ("choice", SHUTTER_CHOICES),
    "ev": ("float", -3.0, 3.0),
    "awb": ("choice", ["ir", "auto", "daylight", "manual"]),
    "awb_gains": ("gains", 0.2, 4.0),
    "af": ("choice", ["auto", "continuous", "manual"]),
    "lens_position": ("float", 0.0, 10.0),
    "format": ("choice", ["jpg", "jpg+dng"]),
    "jpeg_quality": ("int", 50, 100),
    "timer_s": ("choice", [0, 2, 10]),
    "rotation": ("choice", [0, 180]),
}

# Gains de balance des blancs fixes pour un filtre 720 nm sur IMX708 (point de départ,
# à affiner sur le terrain : le but est d'obtenir la végétation neutre/blanche).
IR_AWB_GAINS = (0.55, 1.45)

NAME_RE = re.compile(r"^HALO_[0-9_]+\.(jpg|dng|json)$")
SEQ_RE = re.compile(r"^HALO_(\d{4,6})_")   # nouveaux noms : HALO_0001_20261115_183813

# --------------------------------------------------------------------------------------
# État global
# --------------------------------------------------------------------------------------
app = Flask(__name__, template_folder="templates", static_folder="static")
try:
    app.json.ensure_ascii = False          # Flask >= 2.2
except AttributeError:
    app.config["JSON_AS_ASCII"] = False
picam2 = None
settings = dict(DEFAULT_SETTINGS)
cam_lock = threading.RLock()        # une seule opération caméra à la fois
capture_lock = threading.Lock()     # une seule capture à la fois (bouton + web)
thumb_lock = threading.Lock()       # un seul décodage de miniature à la fois (RAM)
led = PWMLED(LED_GPIO)
state = {
    "busy": False, "phase": "idle", "timer_end": None, "last_photo": None,
    "session_count": 0, "error": None, "error_at": None,
}
metadata = {}                       # dernières métadonnées caméra (mises à jour en continu)
system = {"throttled": None, "temp_c": None, "disk_free": None, "disk_total": None}
viewers = 0                         # navigateurs qui regardent la visée


class StreamOutput(io.BufferedIOBase):
    """Reçoit les images MJPEG du GPU et les sert au navigateur (visée en direct)."""

    def __init__(self):
        self.frame = None
        self.condition = threading.Condition()

    def write(self, buf):
        with self.condition:
            self.frame = buf
            self.condition.notify_all()


stream_output = StreamOutput()
stream_encoder = None


def log(*args):
    print(*args, file=sys.stderr, flush=True)


# --------------------------------------------------------------------------------------
# Réglages
# --------------------------------------------------------------------------------------
def clean_settings(data, partial=True):
    """Valide des réglages venant du navigateur ou du fichier. Lève ValueError si invalide."""
    out = {}
    for key, value in data.items():
        if key not in SCHEMA:
            continue
        kind = SCHEMA[key][0]
        try:
            if kind == "choice":
                choices = SCHEMA[key][1]
                if isinstance(choices[0], int):
                    value = int(float(value))
                if value not in choices:
                    raise ValueError
            elif kind == "float":
                lo, hi = SCHEMA[key][1:]
                value = round(min(hi, max(lo, float(value))), 2)
            elif kind == "int":
                lo, hi = SCHEMA[key][1:]
                value = int(min(hi, max(lo, int(float(value)))))
            elif kind == "gains":
                lo, hi = SCHEMA[key][1:]
                r, b = value
                value = [round(min(hi, max(lo, float(r))), 2), round(min(hi, max(lo, float(b))), 2)]
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
    for key, value in raw.items():   # clé par clé : une valeur abîmée n'efface pas les autres
        try:
            settings.update(clean_settings({key: value}))
        except ValueError as exc:
            log("Réglage ignoré :", exc)


def save_settings():
    SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = SETTINGS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(settings, indent=2))
    os.replace(tmp, SETTINGS_FILE)   # écriture atomique (coupure de batterie)


def camera_controls():
    """Traduit les réglages utilisateur en contrôles libcamera."""
    c = {}
    if settings["shutter_us"] > 0:
        c["AeEnable"] = False
        c["ExposureTime"] = int(settings["shutter_us"])
        c["AnalogueGain"] = max(1.0, settings["iso"] / 100.0) if settings["iso"] > 0 else 1.0
    else:
        c["AeEnable"] = True
        c["ExposureValue"] = float(settings["ev"])
        if settings["iso"] > 0:
            # ISO fixé, vitesse auto : l'AE ne joue plus que sur le temps de pose
            c["AnalogueGain"] = max(1.0, settings["iso"] / 100.0)
    awb = settings["awb"]
    if awb == "auto":
        c["AwbEnable"] = True
        c["AwbMode"] = controls.AwbModeEnum.Auto
    elif awb == "daylight":
        c["AwbEnable"] = True
        c["AwbMode"] = controls.AwbModeEnum.Daylight
    elif awb == "manual":
        c["AwbEnable"] = False
        c["ColourGains"] = tuple(float(g) for g in settings["awb_gains"])
    else:  # "ir"
        c["AwbEnable"] = False
        c["ColourGains"] = IR_AWB_GAINS
    if settings["af"] == "manual":
        c["AfMode"] = controls.AfModeEnum.Manual
        c["LensPosition"] = float(settings["lens_position"])
    elif settings["af"] == "continuous":
        c["AfMode"] = controls.AfModeEnum.Continuous
    else:
        c["AfMode"] = controls.AfModeEnum.Auto
    return c


def current_transform():
    flip = 1 if settings["rotation"] == 180 else 0
    return Transform(hflip=flip, vflip=flip)


# --------------------------------------------------------------------------------------
# Caméra
# --------------------------------------------------------------------------------------
def _on_request(req):
    """Appelé pour chaque image du capteur : garde les métadonnées pour /api/status."""
    try:
        md = req.get_metadata()
        for k in ("ExposureTime", "AnalogueGain", "LensPosition", "Lux", "ColourTemperature"):
            if k in md:
                metadata[k] = md[k]
    except Exception:  # noqa: BLE001
        pass


def preview_configuration():
    # Mode visée : capteur en 2x2 binning, flux principal YUV420 encodé en MJPEG par le GPU.
    return picam2.create_video_configuration(
        main={"size": PREVIEW_RES, "format": "YUV420"},
        transform=current_transform(),
        buffer_count=3,
        controls={"FrameRate": 15},
    )


def init_camera():
    global picam2
    picam2 = Picamera2()
    picam2.post_callback = _on_request
    picam2.configure(preview_configuration())
    picam2.set_controls(camera_controls())
    picam2.start()
    time.sleep(0.5)


def restart_camera():
    """Reconfigure la visée (nécessaire quand la rotation change)."""
    with cam_lock:
        streaming = stream_encoder is not None
        stop_stream()
        picam2.stop()
        picam2.configure(preview_configuration())
        picam2.set_controls(camera_controls())
        picam2.start()
        if streaming:
            start_stream()


def still_configuration():
    """Configuration de capture pleine résolution (JPEG + RAW)."""
    return picam2.create_still_configuration(
        main={"size": FULL_RES, "format": "RGB888"}, raw={"size": FULL_RES},
        transform=current_transform(), buffer_count=1, controls=camera_controls(),
    )


def start_stream():
    global stream_encoder
    if stream_encoder is None:
        stream_encoder = MJPEGEncoder()
        picam2.start_encoder(stream_encoder, FileOutput(stream_output), name="main")


def stop_stream():
    global stream_encoder
    if stream_encoder is not None:
        try:
            picam2.stop_encoder(stream_encoder)
        except Exception:  # noqa: BLE001
            pass
        stream_encoder = None


# --------------------------------------------------------------------------------------
# Fichiers
# --------------------------------------------------------------------------------------
def photo_sort_key(path):
    """Les photos numérotées d'abord par numéro ; les anciennes (sans numéro) par nom."""
    m = SEQ_RE.match(path.name)
    if m:
        return (1, int(m.group(1)), path.name)
    return (0, 0, path.name)


def list_photos():
    if not PHOTO_DIR.exists():
        return []
    return sorted(PHOTO_DIR.glob("HALO_*.jpg"), key=photo_sort_key, reverse=True)


def new_basename():
    """HALO_<numéro>_<date>_<heure> : le numéro garantit l'ordre même si l'horloge est fausse."""
    PHOTO_DIR.mkdir(parents=True, exist_ok=True)
    seq = 0
    for p in PHOTO_DIR.glob("HALO_*.jpg"):
        m = SEQ_RE.match(p.name)
        if m:
            seq = max(seq, int(m.group(1)))
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    while True:
        seq += 1
        if not list(PHOTO_DIR.glob(f"HALO_{seq:04d}_*")):
            return PHOTO_DIR / f"HALO_{seq:04d}_{stamp}"


def check_name(name):
    if not NAME_RE.match(name):
        abort(404)
    return name


def make_thumb(name):
    """Miniature en cache (décodage JPEG réduit : rapide et peu de mémoire sur le Zero 2 W)."""
    from PIL import Image  # PIL est une dépendance de Picamera2
    src = PHOTO_DIR / name
    dst = THUMB_DIR / name
    if not src.exists():
        return None
    if dst.exists() and dst.stat().st_mtime >= src.stat().st_mtime:
        return dst
    with thumb_lock:
        if dst.exists() and dst.stat().st_mtime >= src.stat().st_mtime:
            return dst
        THUMB_DIR.mkdir(parents=True, exist_ok=True)
        with Image.open(src) as im:
            im.draft("RGB", (640, 640))
            im = im.convert("RGB")
            im.thumbnail((480, 480))
            tmp = dst.with_suffix(".tmp")
            im.save(tmp, "JPEG", quality=75)
        os.replace(tmp, dst)
    return dst


def photo_info(path):
    dng = path.with_suffix(".dng")
    st = path.stat()
    return {
        "name": path.name,
        "size": st.st_size,
        "dng": dng.name if dng.exists() else None,
        "dng_size": dng.stat().st_size if dng.exists() else 0,
        "time": datetime.fromtimestamp(st.st_mtime).strftime("%d/%m/%Y %H:%M"),
    }


# --------------------------------------------------------------------------------------
# Capture
# --------------------------------------------------------------------------------------
def set_phase(phase):
    state["phase"] = phase


def capture_photo():
    """Séquence complète : retardateur, AF, bascule en pleine résolution, sauvegarde."""
    if not capture_lock.acquire(blocking=False):
        return None   # une capture est déjà en cours
    state["busy"] = True
    state["error"] = None
    base = None
    try:
        if settings["timer_s"] > 0:
            set_phase("timer")
            state["timer_end"] = time.time() + settings["timer_s"]
            led.blink(on_time=0.1, off_time=0.4)
            time.sleep(settings["timer_s"])
            state["timer_end"] = None
        led.on()  # occupé
        with cam_lock:
            stop_stream()
            picam2.set_controls(camera_controls())
            lens_pos = None
            if settings["af"] == "auto":
                # Cycle AF (PDAF + contraste). En IR l'optique a un léger décalage de mise
                # au point : le cycle se fait sur l'image IR réelle, il en tient donc compte.
                set_phase("focus")
                ok = picam2.autofocus_cycle()
                lens_pos = picam2.capture_metadata().get("LensPosition")
                if not ok:
                    log("AF : cycle non confirmé, lens =", lens_pos)
            still_cfg = still_configuration()
            if lens_pos is not None:
                # On fige la position trouvée pour la capture (évite un re-AF pendant la bascule)
                still_cfg["controls"]["AfMode"] = controls.AfModeEnum.Manual
                still_cfg["controls"]["LensPosition"] = float(lens_pos)
            base = new_basename()
            set_phase("capture")
            req = picam2.switch_mode_and_capture_request(still_cfg)
            try:
                set_phase("saving")
                picam2.options["quality"] = int(settings["jpeg_quality"])
                req.save("main", str(base) + ".jpg")
                if settings["format"] == "jpg+dng":
                    req.save_dng(str(base) + ".dng")
                meta = req.get_metadata()
            finally:
                req.release()
            base.with_suffix(".json").write_text(
                json.dumps({k: str(v) for k, v in meta.items()}, indent=1)
            )
            if viewers > 0:
                start_stream()
        os.sync()   # la photo est sur la carte même si la batterie coupe juste après
        state["last_photo"] = base.name + ".jpg"
        state["session_count"] += 1
        log("Photo :", base.name)
        led.blink(on_time=0.08, off_time=0.08, n=3, background=False)  # confirmation
        try:
            make_thumb(base.name + ".jpg")
        except Exception as exc:  # noqa: BLE001
            log("Miniature :", exc)
        return base
    except Exception as exc:  # noqa: BLE001
        state["error"] = str(exc)
        state["error_at"] = time.time()
        log("Erreur capture :", exc)
        with cam_lock:
            try:
                if viewers > 0:
                    start_stream()
            except Exception:  # noqa: BLE001
                pass
        led.blink(on_time=0.5, off_time=0.2, n=4, background=False)
        return None
    finally:
        state["busy"] = False
        state["timer_end"] = None
        set_phase("idle")
        capture_lock.release()
        led.pulse(fade_in_time=1.5, fade_out_time=1.5)  # prêt


def trigger_capture():
    """Lance une capture en arrière-plan ; False si une capture est déjà en cours."""
    if capture_lock.locked():
        return False
    threading.Thread(target=capture_photo, daemon=True).start()
    return True


def power_off(reason):
    log("Extinction :", reason)
    power_log(f"extinction demandée ({reason})")
    led.on()
    subprocess.Popen(SHUTDOWN_CMD)


# --------------------------------------------------------------------------------------
# Déclencheur physique
# --------------------------------------------------------------------------------------
_held = {"v": False}


def on_button_held(btn):
    _held["v"] = True
    power_off("appui long")


def on_button_released(btn):
    # appui court -> photo ; un appui long a déjà déclenché l'extinction
    was_held = _held["v"]
    _held["v"] = False
    if not was_held:
        trigger_capture()


button = Button(BUTTON_GPIO, pull_up=True, bounce_time=0.03, hold_time=HOLD_SECONDS)
button.when_released = on_button_released
button.when_held = on_button_held


# --------------------------------------------------------------------------------------
# Surveillance du système (alimentation, température, carte SD)
# --------------------------------------------------------------------------------------
THROTTLE_BITS = {
    0: "sous-tension", 1: "fréquence limitée", 2: "ralenti", 3: "limite de température",
}


def read_throttled():
    try:
        out = subprocess.run(["vcgencmd", "get_throttled"], capture_output=True, text=True, timeout=3).stdout
        return int(out.strip().split("=")[1], 16)
    except Exception:  # noqa: BLE001
        return None


def describe_throttled(value):
    if not value:
        return []
    now = [txt for bit, txt in THROTTLE_BITS.items() if value & (1 << bit)]
    past = [txt for bit, txt in THROTTLE_BITS.items() if value & (1 << (bit + 16)) and txt not in now]
    return [t + " (en cours)" for t in now] + [t + " (depuis le démarrage)" for t in past]


def power_log(line):
    """Journal d'alimentation sur la carte, écrit immédiatement (survit à une coupure)."""
    try:
        if POWER_LOG.exists() and POWER_LOG.stat().st_size > 200_000:
            POWER_LOG.replace(POWER_LOG.with_suffix(".log.1"))
        with open(POWER_LOG, "a") as f:
            f.write(f"{datetime.now():%Y-%m-%d %H:%M:%S} up={time.monotonic():.0f}s {line}\n")
            f.flush()
            os.fsync(f.fileno())
    except Exception:  # noqa: BLE001
        pass


def system_monitor():
    last_throttled = -1
    last_alive = 0.0
    power_log("démarrage")
    while True:
        thr = read_throttled()
        system["throttled"] = thr
        try:
            system["temp_c"] = int(Path("/sys/class/thermal/thermal_zone0/temp").read_text()) / 1000
        except Exception:  # noqa: BLE001
            system["temp_c"] = None
        try:
            du = shutil.disk_usage(PHOTO_DIR if PHOTO_DIR.exists() else "/")
            system["disk_free"], system["disk_total"] = du.free, du.total
        except Exception:  # noqa: BLE001
            pass
        if thr is not None and thr != last_throttled:
            if thr:
                log(f"Alimentation : throttled=0x{thr:x} ->", ", ".join(describe_throttled(thr)))
            power_log(f"throttled=0x{thr:x} {', '.join(describe_throttled(thr)) or 'OK'}")
            last_throttled = thr
        if time.monotonic() - last_alive >= 60:   # signe de vie : dernière minute connue avant une coupure
            power_log(f"vivant temp={system['temp_c']} visée={'oui' if stream_encoder else 'non'}")
            last_alive = time.monotonic()
        time.sleep(5)


# --------------------------------------------------------------------------------------
# Interface web
# --------------------------------------------------------------------------------------
def mjpeg_generator():
    global viewers
    with cam_lock:
        viewers += 1
        start_stream()
    try:
        last = None
        while True:
            with stream_output.condition:
                stream_output.condition.wait(timeout=2)
                frame = stream_output.frame
            if frame is None or frame is last:
                continue
            last = frame
            yield b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + \
                str(len(frame)).encode() + b"\r\n\r\n" + frame + b"\r\n"
    finally:
        with cam_lock:
            viewers = max(0, viewers - 1)
            if viewers == 0 and not state["busy"]:
                stop_stream()   # plus personne ne regarde : on coupe l'encodeur (batterie)


def status_payload():
    thr = system["throttled"]
    timer_left = None
    if state["timer_end"]:
        timer_left = max(0, round(state["timer_end"] - time.time(), 1))
    return {
        "busy": state["busy"], "phase": state["phase"], "timer_left": timer_left,
        "last": state["last_photo"], "session_count": state["session_count"],
        "error": state["error"],
        "exposure_us": metadata.get("ExposureTime"), "gain": metadata.get("AnalogueGain"),
        "lens": metadata.get("LensPosition"), "lux": metadata.get("Lux"),
        "colour_temp": metadata.get("ColourTemperature"),
        "temp_c": system["temp_c"], "disk_free": system["disk_free"], "disk_total": system["disk_total"],
        "throttled": thr, "undervoltage_now": bool(thr and thr & 1),
        "undervoltage_seen": bool(thr and thr & (1 << 16)),
        "power_warnings": describe_throttled(thr),
        "viewers": viewers,
    }


@app.route("/")
def index():
    photos = list_photos()
    boot = {"settings": settings, "status": status_payload(), "total": len(photos),
            "latest": photos[0].name if photos else None,
            "choices": {"iso": ISO_CHOICES, "shutter_us": SHUTTER_CHOICES}}
    return render_template("index.html", boot=boot)


@app.route("/stream.mjpg")
def stream():
    resp = Response(mjpeg_generator(), mimetype="multipart/x-mixed-replace; boundary=frame")
    resp.headers["Cache-Control"] = "no-store"
    return resp


@app.route("/api/status")
@app.route("/status")
def api_status():
    return jsonify(status_payload())


@app.route("/api/shoot", methods=["POST"])
@app.route("/shoot", methods=["POST"])
def api_shoot():
    started = trigger_capture()
    return jsonify(ok=started, busy=not started), (200 if started else 409)


@app.route("/api/settings", methods=["GET", "POST"])
def api_settings():
    if request.method == "GET":
        return jsonify(settings)
    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        return jsonify(ok=False, error="JSON attendu"), 400
    try:
        new = clean_settings(data)
    except ValueError as exc:
        return jsonify(ok=False, error=str(exc)), 400
    rotation_changed = "rotation" in new and new["rotation"] != settings["rotation"]
    settings.update(new)
    save_settings()
    try:
        if rotation_changed:
            restart_camera()
        else:
            with cam_lock:
                picam2.set_controls(camera_controls())
    except Exception as exc:  # noqa: BLE001
        log("Réglages caméra :", exc)
        return jsonify(ok=False, error=f"caméra : {exc}", settings=settings), 500
    return jsonify(ok=True, settings=settings)


@app.route("/api/photos")
def api_photos():
    try:
        offset = max(0, int(request.args.get("offset", 0)))
        limit = min(200, max(1, int(request.args.get("limit", 60))))
    except ValueError:
        abort(400)
    photos = list_photos()
    items = []
    for p in photos[offset:offset + limit]:
        try:
            items.append(photo_info(p))
        except FileNotFoundError:
            pass
    return jsonify(total=len(photos), offset=offset, items=items)


@app.route("/api/photos/<name>", methods=["DELETE"])
def api_delete(name):
    check_name(name)
    if not name.endswith(".jpg"):
        abort(400)
    stem = name[:-4]
    removed = []
    for path in (PHOTO_DIR / name, PHOTO_DIR / f"{stem}.dng", PHOTO_DIR / f"{stem}.json", THUMB_DIR / name):
        try:
            path.unlink()
            removed.append(path.name)
        except FileNotFoundError:
            pass
    if not removed:
        abort(404)
    if state["last_photo"] == name:
        state["last_photo"] = None
    os.sync()
    log("Supprimé :", ", ".join(removed))
    return jsonify(ok=True, removed=removed)


@app.route("/photos/<name>")
def photo(name):
    check_name(name)
    return send_from_directory(PHOTO_DIR, name, as_attachment=request.args.get("dl") == "1"
                               or name.endswith(".dng"), max_age=3600)


@app.route("/thumb/<name>")
def thumb(name):
    check_name(name)
    if not name.endswith(".jpg"):
        abort(404)
    try:
        path = make_thumb(name)
    except Exception as exc:  # noqa: BLE001
        log("Miniature :", name, exc)
        abort(500)
    if path is None:
        abort(404)
    return send_from_directory(THUMB_DIR, name, max_age=86400)


@app.route("/api/shutdown", methods=["POST"])
@app.route("/shutdown", methods=["POST"])
def api_shutdown():
    power_off("interface web")
    return jsonify(ok=True)


# --------------------------------------------------------------------------------------
# Lancement
# --------------------------------------------------------------------------------------
def main():
    load_settings()
    led.blink(on_time=0.2, off_time=0.2)  # démarrage
    init_camera()
    threading.Thread(target=system_monitor, daemon=True).start()
    led.pulse(fade_in_time=1.5, fade_out_time=1.5)
    log(f"HALO prêt. {PHOTO_DIR} — web : http://10.42.0.1:{WEB_PORT} ou http://10.55.0.1:{WEB_PORT}")

    def stop(*_):
        led.off()
        try:
            power_log("arrêt du service")
            stop_stream()
            picam2.stop()
        finally:
            os._exit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    app.run(host="0.0.0.0", port=WEB_PORT, threaded=True, debug=False, use_reloader=False)


if __name__ == "__main__":
    main()

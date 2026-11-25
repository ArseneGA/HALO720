#!/usr/bin/env python3
"""HALO 720 v3 - gestion de l'alimentation (IP5306-I2C, adresse 0x75, bus I2C1 = GPIO2/3).

Au démarrage : règle le chargeur et l'arrêt par double appui. Ensuite, toutes les 10 s : écrit l'état de la
batterie dans /run/halo/power.json (lu par halo_camera.py) et éteint proprement l'appareil si la batterie
est presque vide (niveau < 3 %, hors charge).

Registres d'après la note « IP5306-I2C register » d'Injoinic (reprise par la bibliothèque M5Stack).
Toute écriture est relue : en cas d'écart, le service le note dans le journal et continue (l'IP5306 garde
ses réglages par défaut, l'appareil fonctionne quand même).

  python3 halo_power.py          service (systemd : halo-power.service)
  python3 halo_power.py --once   règle, affiche l'état et sort (test usine)
"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

try:
    from smbus2 import SMBus
except ImportError:  # paquet Debian python3-smbus
    from smbus import SMBus

BUS = 1
ADDR = 0x75
STATE = Path(os.environ.get("HALO_POWER_STATE", "/run/halo/power.json"))
CHARGE_A = float(os.environ.get("HALO_CHARGE_A", "1.0"))   # courant de charge ; LiPo 2000 mAh -> 0,5 C

SYS_CTL0, SYS_CTL1, SYS_CTL2 = 0x00, 0x01, 0x02
CHG_DIG_CTL0 = 0x24          # bits 4:0 : I = 0,05 A + n x 0,1 A
READ0, READ1, LEVEL = 0x70, 0x71, 0x78

# SYS_CTL0 : bit5 boost actif, bit4 chargeur actif, bit2 démarrage auto à la connexion d'une charge,
#            bit1 boost toujours actif (0 : arrêt « charge légère » autorisé),
#            bit0 = arrêt par le bouton MARCHE. On le met à 0 pendant le fonctionnement : un double appui
#            accidentel sur MARCHE ne coupe plus le courant. halo-poweroff le remet à 1 en toute fin
#            d'arrêt, juste avant d'envoyer lui-même le double appui qui coupe l'alimentation.
# SYS_CTL1 : bit7 = 1 -> le boost s'arrête sur DEUX appuis courts (pas sur un appui long),
#            bit2 = 1 -> le boost reste actif quand on débranche l'USB-C, bit0 = 1 -> arrêt sous 3,0 V.
# SYS_CTL2 : bit4 = 0 -> appui long = 2 s ; bits 3:2 = 00 -> arrêt charge légère après 8 s.
WANTED = {
    SYS_CTL0: (0b0011_0111, 0b0011_0100),   # (masque, valeur) : boost + chargeur + auto ; bit1 = 0 ; bit0 = 0
    SYS_CTL1: (0b1000_0101, 0b1000_0101),
    SYS_CTL2: (0b0001_1100, 0b0000_0000),
}


def charge_code(amps):
    return max(0, min(31, round((amps - 0.05) / 0.1)))


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def configure(bus):
    ok = True
    regs = dict(WANTED)
    regs[CHG_DIG_CTL0] = (0b0001_1111, charge_code(CHARGE_A))
    for reg, (mask, val) in regs.items():
        old = bus.read_byte_data(ADDR, reg)
        new = (old & ~mask) | (val & mask)
        if new != old:
            bus.write_byte_data(ADDR, reg, new)
        back = bus.read_byte_data(ADDR, reg)
        if back & mask != val & mask:
            ok = False
            log(f"IP5306 reg 0x{reg:02x} : écrit 0x{new:02x}, relu 0x{back:02x}")
    return ok


def read_state(bus):
    r0 = bus.read_byte_data(ADDR, READ0)
    r1 = bus.read_byte_data(ADDR, READ1)
    lv = bus.read_byte_data(ADDR, LEVEL) & 0xF0
    level = {0x00: 100, 0x80: 75, 0xC0: 50, 0xE0: 25}.get(lv, 0)
    return {"t": time.time(), "level": level, "charging": bool(r0 & 0x08), "full": bool(r1 & 0x08),
            "usb": bool(r0 & 0x08) or bool(r1 & 0x08), "raw": [r0, r1, lv]}


def write_state(d):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(d))
    tmp.replace(STATE)


def main():
    once = "--once" in sys.argv
    configured = False
    low_since = None
    while True:
        try:
            with SMBus(BUS) as bus:
                if not configured:
                    configured = configure(bus)
                    log("IP5306 réglé" if configured else "IP5306 : réglage incomplet (voir ci-dessus)",
                        f"- charge {0.05 + charge_code(CHARGE_A) * 0.1:.2f} A")
                d = read_state(bus)
            write_state(d)
            if once:
                print(json.dumps(d))
                return 0 if configured else 1
            # batterie vide : 0 % (code 0xF0) pendant 1 min, sans USB -> extinction propre
            if d["level"] == 0 and not d["usb"]:
                low_since = low_since or time.time()
                if time.time() - low_since > 60:
                    log("batterie vide : extinction")
                    subprocess.run(["/usr/sbin/shutdown", "-h", "now"])
            else:
                low_since = None
        except OSError as exc:  # IP5306 muet (I2C pas encore prêt, ou pas d'IP5306 : carte v2)
            log("I2C :", exc)
            if once:
                return 2
        time.sleep(10)


if __name__ == "__main__":
    sys.exit(main())

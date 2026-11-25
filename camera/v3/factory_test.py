#!/usr/bin/env python3
"""HALO 720 v3 - FACTORY TEST / TEST USINE (run on the device as root: sudo python3 factory_test.py)

Bilingual output (English for the factory operator, French in comments).
Setup: board fully assembled (CM4 + camera + flash board), 3.7-4.2 V LiPo or bench supply on J6 (BATT),
USB-C connected to the test PC (ssh pi@10.55.0.1). Camera looking at a white card ~20 cm away,
in a dark box (or lens covered with the white card) for the flash test.

Exit code 0 = PASS. A JSON report is written to /home/pi/HALO/factory_test_<serial>.json.
"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

HALO = Path("/home/pi/HALO")
RESULTS = []
AUTO = "--auto" in sys.argv          # no operator questions (buttons/LED skipped -> reported as SKIP)


def sh(cmd, timeout=30):
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    return r.returncode, (r.stdout + r.stderr).strip()


def check(name, ok, detail=""):
    RESULTS.append({"test": name, "result": "PASS" if ok is True else ("SKIP" if ok is None else "FAIL"),
                    "detail": detail})
    print(f"[{RESULTS[-1]['result']:4}] {name}  {detail}", flush=True)
    return ok


def ask(q):
    if AUTO:
        return None
    return input(f"  >>> {q} [y/n] ").strip().lower().startswith("y")


def serial():
    try:
        return Path("/proc/device-tree/serial-number").read_text().strip("\0\n")
    except OSError:
        return "unknown"


def t_module():
    model = Path("/proc/device-tree/model").read_text().strip("\0")
    check("CM4 detected / CM4 détecté", "Compute Module 4" in model, model)
    _, out = sh("lsblk -bdno SIZE /dev/mmcblk0")
    gb = int(out or 0) / 1e9
    check("eMMC 16 GB", 14 < gb < 17, f"{gb:.1f} GB")
    _, thr = sh("vcgencmd get_throttled")
    check("No under-voltage / pas de sous-tension", thr.strip().endswith("=0x0"), thr)


def t_power():
    code, out = sh("i2cdetect -y 1 0x75 0x75")
    check("IP5306 on I2C 0x75", code == 0 and " 75" in out)
    code, out = sh(f"python3 {HALO}/v3/halo_power.py --once")
    try:
        d = json.loads(out.splitlines()[-1])
    except (ValueError, IndexError):
        d = {}
    check("IP5306 configured / réglé", code == 0, out.splitlines()[-1] if out else "")
    check("Battery level read / niveau batterie lu", d.get("level") is not None, f"{d.get('level')} %")


def t_wifi():
    _, out = sh("iw dev wlan0 info")
    freq = 0
    for line in out.splitlines():
        if "channel" in line and "MHz" in line:
            freq = int(line.split("(")[1].split()[0])
    _, ssid = sh("iw dev wlan0 info | sed -n 's/.*ssid //p'")
    check("Wi-Fi access point 5 GHz", freq > 5000 and ssid.startswith("HALO-720"), f"{ssid} {freq} MHz")


def mean_frame(cam):
    import numpy as np
    a = cam.capture_array("main")
    return float(np.mean(a[..., :3]))


def t_camera_flash():
    sh("systemctl stop halo.service")
    time.sleep(1)
    _, out = sh("rpicam-hello --list-cameras", timeout=20)
    if not check("Camera IMX708 detected / caméra détectée", "imx708" in out, out.splitlines()[-1] if out else ""):
        return
    try:
        from gpiozero import DigitalOutputDevice
        from picamera2 import Picamera2
        cam = Picamera2()
        cam.configure(cam.create_still_configuration(main={"size": (1152, 648), "format": "RGB888"}))
        cam.set_controls({"AeEnable": False, "AwbEnable": False, "ExposureTime": 20000, "AnalogueGain": 2.0})
        cam.start()
        time.sleep(1.0)
        dark = mean_frame(cam)
        flash = DigitalOutputDevice(22, initial_value=False)
        flash.on()
        time.sleep(0.4)
        lit = mean_frame(cam)
        flash.off()
        flash.close()
        cam.stop()
        cam.close()
        check("Camera image / image caméra", 0 <= dark < 250, f"mean {dark:.1f}")
        check("IR flash lights the scene / l'éclair éclaire", lit > dark * 1.5 + 5,
              f"off {dark:.1f} -> on {lit:.1f}")
    except Exception as exc:  # noqa: BLE001
        check("Camera / flash test", False, repr(exc))
    finally:
        sh("pinctrl set 22 op dl")


def t_buttons_leds():
    if AUTO:
        check("Shutter button / déclencheur", None, "skipped (--auto)")
        check("LEDs", None, "skipped (--auto)")
        return
    from gpiozero import Button, LED
    btn = Button(17, pull_up=True, bounce_time=0.05)
    print("  >>> Press the SHUTTER button (DECL.) within 15 s / appuyer sur le déclencheur")
    check("Shutter button / déclencheur", btn.wait_for_press(timeout=15) is True)
    btn.close()
    led = LED(27)
    led.blink(0.2, 0.2)
    check("Green LED (ETAT) blinks / LED verte clignote", ask("Is the green LED (ETAT) blinking?") is True)
    led.close()
    sh("echo heartbeat > /sys/class/leds/ACT/trigger")
    check("Red LED (ACT) blinks / LED rouge clignote", ask("Is the red LED (ACT) blinking (heartbeat)?") is True)
    sh("echo mmc0 > /sys/class/leds/ACT/trigger")


def main():
    if os.geteuid() != 0:
        sys.exit("run with sudo / lancer avec sudo")
    sn = serial()
    print(f"HALO 720 v3 factory test - CM4 serial {sn}\n")
    for t in (t_module, t_power, t_wifi, t_camera_flash, t_buttons_leds):
        try:
            t()
        except Exception as exc:  # noqa: BLE001
            check(t.__name__, False, repr(exc))
    sh("systemctl start halo.service")
    failed = [r for r in RESULTS if r["result"] == "FAIL"]
    verdict = "PASS" if not failed else "FAIL"
    rep = {"serial": sn, "date": time.strftime("%Y-%m-%d %H:%M:%S"), "verdict": verdict, "tests": RESULTS}
    out = HALO / f"factory_test_{sn}.json"
    out.write_text(json.dumps(rep, indent=1))
    print(f"\n==== {verdict} ====  ({len(RESULTS) - len(failed)}/{len(RESULTS)})  report: {out}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())

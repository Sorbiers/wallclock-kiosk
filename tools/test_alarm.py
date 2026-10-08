"""Pretend a camera alarm on the tablet:  python tools/test_alarm.py [camera] [tablet address]

camera: 1 (default) or 2, as numbered in the app's settings. Only the trigger
is pretended; the pictures and the live view are real.

The tablet's address and the remote-control PIN are remembered in
tablet.local.txt (not in git): the address is asked from the tablet over USB
the first time, the PIN from you. After that USB is not needed.
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REMEMBERED = ROOT / "tablet.local.txt"   # line 1: address, line 2: PIN
ADB = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or Path.home() / "AppData/Local/Android/Sdk") / "platform-tools" / "adb.exe"
PORT = 8080


def remembered() -> list:
    return REMEMBERED.read_text().split() if REMEMBERED.exists() else []


def tablet_address() -> str:
    if len(sys.argv) > 2:
        return sys.argv[2]
    if remembered():
        return remembered()[0]
    found = subprocess.run([str(ADB), "shell", "ip", "-f", "inet", "addr", "show", "wlan0"],
                           capture_output=True, text=True).stdout
    for word in found.split():
        if word.count(".") == 3 and "/" in word:
            return word.split("/")[0]
    sys.exit("Tablet address not known. Plug the tablet in once, or give it: test-alarm.bat 1 192.168.x.x")


def main() -> None:
    camera = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 1
    address = tablet_address()
    pin = (remembered() + ["", ""])[1]
    command = json.dumps({"test": True, "cam": camera - 1}).encode()
    for asked in (False, True):
        request = urllib.request.Request(f"http://{address}:{PORT}/api/command", method="POST",
                                         headers={"X-Pin": pin}, data=command)
        try:
            urllib.request.urlopen(request, timeout=8).read()
            break
        except urllib.error.HTTPError as refused:
            if refused.code == 403:
                sys.exit("The remote control is off: set a PIN in the tablet's settings (gear, General).")
            if refused.code != 401 or asked:
                sys.exit(f"The tablet refused the PIN (HTTP {refused.code}).")
            pin = input("Remote-control PIN: ").strip()
        except OSError as failed:
            sys.exit(f"Could not reach the tablet at {address}: {failed}\n"
                     f"If its address changed, delete {REMEMBERED.name} and plug the tablet in.")
    REMEMBERED.write_text(address + "\n" + pin + "\n")
    print(f"Test alarm sent for camera {camera} to the tablet at {address}.")


if __name__ == "__main__":
    main()

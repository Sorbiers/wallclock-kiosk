"""Pretend a camera alarm on the tablet:  python tools/test_alarm.py [camera] [tablet address]

camera: 1 (default) or 2, as numbered in the app's settings. Only the trigger
is pretended; the pictures and the live view are real.

The tablet's address is remembered in tablet.local.txt (not in git). The first
time it is asked from the tablet over USB; after that USB is not needed.
"""

import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REMEMBERED = ROOT / "tablet.local.txt"
ADB = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or Path.home() / "AppData/Local/Android/Sdk") / "platform-tools" / "adb.exe"
PORT = 8080


def tablet_address() -> str:
    if len(sys.argv) > 2:
        return sys.argv[2]
    if REMEMBERED.exists() and REMEMBERED.read_text().strip():
        return REMEMBERED.read_text().strip()
    found = subprocess.run([str(ADB), "shell", "ip", "-f", "inet", "addr", "show", "wlan0"],
                           capture_output=True, text=True).stdout
    for word in found.split():
        if word.count(".") == 3 and "/" in word:
            address = word.split("/")[0]
            REMEMBERED.write_text(address + "\n")
            return address
    sys.exit("Tablet address not known. Plug the tablet in once, or give it: test-alarm.bat 1 192.168.x.x")


def main() -> None:
    camera = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 1
    address = tablet_address()
    request = urllib.request.Request(f"http://{address}:{PORT}/api/command", method="POST",
                                     data=json.dumps({"test": True, "cam": camera - 1}).encode())
    try:
        urllib.request.urlopen(request, timeout=8).read()
    except OSError as failed:
        sys.exit(f"Could not reach the tablet at {address}: {failed}\n"
                 f"If its address changed, delete {REMEMBERED.name} and plug the tablet in.")
    print(f"Test alarm sent for camera {camera} to the tablet at {address}.")


if __name__ == "__main__":
    main()

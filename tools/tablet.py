"""Reaching the tablet with adb: by USB cable when it is plugged in, else over Wi-Fi.

Run on its own with the cable in (wifi-on.bat), it switches the tablet to
updates over Wi-Fi: deploy.bat and sync-slides.bat then work without the cable.
The tablet forgets that when it restarts; plug it in and run this again then.
While it is on, the tablet's debug port is open on the home network, to the
computers the tablet has already allowed (this PC).

The tablet's address is remembered in tablet.local.txt (not in git).
"""

import os
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REMEMBERED = ROOT / "tablet.local.txt"   # line 1: the tablet's address, line 2: the remote-control PIN, line 3: the user's name
ADB = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or Path.home() / "AppData/Local/Android/Sdk") / "platform-tools" / "adb.exe"
ADB_PORT = 5555


def remembered() -> list:
    return REMEMBERED.read_text(encoding="utf-8").split() if REMEMBERED.exists() else []


def remember(address: str = None, pin: str = None, user: str = None) -> None:
    """Keep the tablet's address and/or the remote-control name and PIN for the next run."""
    known = (remembered() + ["", "", ""])[:3]
    kept = [address or known[0], known[1] if pin is None else pin, known[2] if user is None else user]
    REMEMBERED.write_text("\n".join(kept) + "\n", encoding="utf-8")


def run(*args, timeout=None) -> subprocess.CompletedProcess:
    if not ADB.exists():
        sys.exit(f"adb not found at {ADB}: set ANDROID_SDK_ROOT (see local.example.bat).")
    try:
        return subprocess.run([str(ADB), *args], capture_output=True, encoding="utf-8", errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(args, 1, "", "no answer")


def adb(name: str, *args) -> str:
    """Run an adb command on the tablet called `name`; stops the script if it fails."""
    done = run("-s", name, *args)
    if done.returncode:
        sys.exit(f"adb {args[0]} failed: {(done.stderr or done.stdout).strip() or 'no answer from the tablet'}")
    return done.stdout


def cabled() -> str:
    """The adb name of a tablet on the cable, or "" when none is plugged in."""
    for line in run("devices", timeout=20).stdout.splitlines():
        name, _, state = line.partition("\t")
        if state.strip() == "device" and ":" not in name and "._adb" not in name:
            return name
    return ""


def over_wifi() -> str:
    """The adb name of the tablet over Wi-Fi, or "" when it does not answer there."""
    known = remembered()
    if not known:
        return ""
    name = f"{known[0]}:{ADB_PORT}"
    try:
        socket.create_connection((known[0], ADB_PORT), timeout=3).close()
    except OSError:
        return ""
    for _ in range(2):   # a connection left from before the tablet restarted is dropped and made again
        run("connect", name, timeout=20)
        if run("-s", name, "get-state", timeout=20).stdout.strip() == "device":
            return name
        run("disconnect", name, timeout=20)
    return ""


def find() -> str:
    """The adb name of the tablet: the cable first, else Wi-Fi. Stops the script when there is neither."""
    name = cabled() or over_wifi()
    if not name:
        sys.exit("Tablet not found. Plug it in (USB debugging on), or, to go over Wi-Fi,\n"
                 "plug it in once and run wifi-on.bat.")
    return name


def wifi_address(name: str) -> str:
    """The tablet's address on the home network, or "" when it is not on Wi-Fi."""
    found = run("-s", name, "shell", "ip", "-f", "inet", "addr", "show", "wlan0", timeout=20).stdout
    for word in found.split():
        if word.count(".") == 3 and "/" in word:
            return word.split("/")[0]
    return ""


def main() -> None:
    name = cabled()
    if not name:
        sys.exit('Plug the tablet in first (USB debugging on, "Allow USB debugging" accepted on its screen).')
    address = wifi_address(name)
    if not address:
        sys.exit("The tablet is not on Wi-Fi.")
    remember(address)
    adb(name, "tcpip", str(ADB_PORT))
    for _ in range(10):   # the tablet's debug service restarts; give it a moment
        time.sleep(1)
        if over_wifi():
            print(f"The tablet answers over Wi-Fi at {address}. Unplug the cable when you like:\n"
                  "deploy.bat and sync-slides.bat now reach it without.\n"
                  "The tablet forgets this when it restarts; plug it in and run wifi-on.bat again then.")
            return
    sys.exit(f"The tablet did not answer at {address}:{ADB_PORT}. Are the PC and the tablet on the same network?")


if __name__ == "__main__":
    main()

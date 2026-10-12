"""Pretend a camera alarm on the tablet:  python tools/test_alarm.py [camera] [tablet address[:port]]

camera: 1 (default) or 2, as numbered in the app's settings. Only the trigger
is pretended; the pictures and the live view are real. The port is the remote
control's: needed once, when the tablet's settings name another than 8080.

The tablet's address and your name and PIN for its remote control are
remembered in tablet.local.txt (not in git): the address is asked from the
tablet over USB the first time, the name and PIN from you. After that USB is
not needed.
"""

import json
import sys
import urllib.error
import urllib.parse
import urllib.request

import tablet

PORT = 8080


def tablet_address() -> str:
    if len(sys.argv) > 2:
        return sys.argv[2]
    if tablet.remembered():
        return tablet.remembered()[0]
    cabled = tablet.cabled()
    address = tablet.wifi_address(cabled) if cabled else ""
    if address:
        return address
    sys.exit("Tablet address not known. Plug the tablet in once, or give it: test-alarm.bat 1 192.168.x.x")


def main() -> None:
    camera = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 1
    address, _, port = tablet_address().partition(":")
    known = tablet.remembered() + ["", "", "", ""]
    pin, user = known[1], known[2] or "home"   # "home": the name the single PIN of earlier versions goes by
    port = port or known[3] or str(PORT)
    command = json.dumps({"test": True, "cam": camera - 1}).encode()
    for asked in (False, True):
        request = urllib.request.Request(f"http://{address}:{port}/api/command", method="POST", data=command,
                                         headers={"X-User": urllib.parse.quote(user), "X-Pin": pin})
        try:
            urllib.request.urlopen(request, timeout=8).read()
            break
        except urllib.error.HTTPError as refused:
            if refused.code == 403:
                sys.exit("The remote control is off, or not open to this network: see the tablet's settings (gear, Remote).")
            if refused.code != 401 or asked:
                sys.exit(f"The tablet refused the name and PIN (HTTP {refused.code}).")
            user = input(f"Remote-control user name [{user}]: ").strip() or user
            pin = input("PIN: ").strip()
        except OSError as failed:
            sys.exit(f"Could not reach the tablet at {address}:{port}: {failed}\n"
                     f"If its address changed, delete {tablet.REMEMBERED.name} and plug the tablet in.\n"
                     f"If the port was changed in its settings, give it once: test-alarm.bat {camera} {address}:<port>")
    tablet.remember(address, pin, user, port)
    print(f"Test alarm sent for camera {camera} to the tablet at {address}.")


if __name__ == "__main__":
    main()

"""Forget the tablet's master PIN, and the lock on its settings, when it is forgotten:  python tools/reset_master.py

Everything else in the settings stays. It goes over the USB cable (or Wi-Fi,
after wifi-on.bat): only a PC the tablet allows to debug it can do this.
"""

import tablet


def main() -> None:
    device = tablet.find()
    tablet.adb(device, "shell", "am", "start", "-n", "home.wallclock/.MainActivity", "--ez", "reset_master", "true")
    print("The master PIN is forgotten. Set a new one on the tablet: gear, Remote.")


if __name__ == "__main__":
    main()

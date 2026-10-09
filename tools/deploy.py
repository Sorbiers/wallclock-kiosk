"""Install dist/wallclock.apk on the tablet and start it:  python tools/deploy.py

By USB cable when the tablet is plugged in, else over Wi-Fi (see tablet.py).
The settings on the tablet are kept.
"""

import sys

import tablet

APK = tablet.ROOT / "dist" / "wallclock.apk"


def main() -> None:
    if not APK.exists():
        sys.exit("dist\\wallclock.apk not found - run build.bat first.")
    name = tablet.find()
    print("Installing on the tablet", "over Wi-Fi..." if ":" in name else "by cable...")
    print(tablet.adb(name, "install", "-r", str(APK)).strip())
    tablet.adb(name, "shell", "am", "start", "-n", "home.wallclock/.MainActivity")


if __name__ == "__main__":
    main()

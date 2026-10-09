"""Copy the slideshow pictures to the tablet:  python tools/sync_slides.py [folder]

The folder (default: slides/) is mirrored to the Wall Clock app on the tablet:
new and changed pictures are copied, pictures no longer in the folder are
removed. Pictures are first shrunk to tablet size into dist/slides (the
originals are never touched), so a phone photo costs the tablet ~300 KB, not 5 MB.
Needs the tablet plugged in with USB debugging on.
"""

import os
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "slides"
SHRUNK = ROOT / "dist" / "slides"
ADB = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or Path.home() / "AppData/Local/Android/Sdk") / "platform-tools" / "adb.exe"
ON_TABLET = "/sdcard/Android/data/home.wallclock/files"   # the app's own folder; "slides" goes inside
PICTURES = (".jpg", ".jpeg", ".png", ".webp")
FIT = (1920, 1200)   # plenty for the 1340x800 screen, with room for the slow zoom


def adb(*args) -> str:
    done = subprocess.run([str(ADB), *args], capture_output=True, text=True)
    if done.returncode:
        sys.exit(f"adb {args[0]} failed: {(done.stderr or done.stdout).strip() or 'is the tablet plugged in?'}")
    return done.stdout


def main() -> None:
    if not SOURCE.is_dir():
        sys.exit(f"Folder not found: {SOURCE}")
    SHRUNK.mkdir(parents=True, exist_ok=True)
    wanted = {}
    for picture in sorted(SOURCE.iterdir()):
        if picture.suffix.lower() in PICTURES:
            wanted[picture.stem + ".jpg"] = picture
    for name, picture in wanted.items():
        small = SHRUNK / name
        if small.exists() and small.stat().st_mtime >= picture.stat().st_mtime:
            continue
        with Image.open(picture) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")   # phone photos: apply the rotation
            image.thumbnail(FIT, Image.LANCZOS)
            image.save(small, "JPEG", quality=88)
        print("prepared", name)
    for stale in SHRUNK.iterdir():
        if stale.name not in wanted:
            stale.unlink()

    adb("shell", "mkdir", "-p", f"{ON_TABLET}/slides")
    for name in adb("shell", "ls", f"{ON_TABLET}/slides").split("\n"):
        name = name.strip()
        if name and name not in wanted:
            adb("shell", "rm", f"'{ON_TABLET}/slides/{name}'")
            print("removed from tablet:", name)
    summary = adb("push", "--sync", str(SHRUNK), ON_TABLET).strip().splitlines()
    if summary:
        print(summary[-1])
    print(f"{len(wanted)} picture(s) on the tablet. Tap Slides there to load them, also if the slideshow is already running.")


if __name__ == "__main__":
    main()

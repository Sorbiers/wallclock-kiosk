"""Copy the slideshow pictures to the tablet:  python tools/sync_slides.py [folder]

The folder (default: slides/) is mirrored to the Wall Clock app on the tablet,
with the folders inside it (one level): those can be chosen in the app's
settings, one for the day and one for the night. New and changed pictures are
copied; pictures and folders no longer there are removed. Folders whose names
begin with _ or . are left alone: they are not copied. Pictures are first
shrunk to tablet size into dist/slides (the originals are never touched), so a
phone photo costs the tablet ~300 KB, not 5 MB.
Goes by USB cable when the tablet is plugged in, else over Wi-Fi (see tablet.py).
"""

import shutil
import sys
from pathlib import Path

from PIL import Image, ImageOps

import tablet

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "slides"
SHRUNK = ROOT / "dist" / "slides"
ON_TABLET = "/sdcard/Android/data/home.wallclock/files"   # the app's own folder; "slides" goes inside
PICTURES = (".jpg", ".jpeg", ".png", ".webp")
FIT = (1920, 1200)   # plenty for the 1340x800 screen, with room for the slow zoom


def pictures_in(folder: Path) -> list:
    return [p for p in sorted(folder.iterdir()) if p.is_file() and p.suffix.lower() in PICTURES]


def main() -> None:
    if not SOURCE.is_dir():
        sys.exit(f"Folder not found: {SOURCE}")
    SHRUNK.mkdir(parents=True, exist_ok=True)
    wanted = {picture.stem + ".jpg": picture for picture in pictures_in(SOURCE)}   # as named on the tablet -> the original
    for folder in sorted(p for p in SOURCE.iterdir() if p.is_dir() and not p.name.startswith((".", "_"))):
        wanted.update({f"{folder.name}/{picture.stem}.jpg": picture for picture in pictures_in(folder)})
    folders = sorted({name.split("/")[0] for name in wanted if "/" in name})   # the ones with pictures in them
    for name, picture in wanted.items():
        small = SHRUNK / name
        if small.exists() and small.stat().st_mtime >= picture.stat().st_mtime:
            continue
        small.parent.mkdir(exist_ok=True)
        with Image.open(picture) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")   # phone photos: apply the rotation
            image.thumbnail(FIT, Image.LANCZOS)
            image.save(small, "JPEG", quality=88)
        print("prepared", name)
    for stale in SHRUNK.iterdir():
        if stale.is_dir() and stale.name not in folders:
            shutil.rmtree(stale)
    for stale in SHRUNK.rglob("*"):
        if stale.is_file() and stale.relative_to(SHRUNK).as_posix() not in wanted:
            stale.unlink()

    device = tablet.find()

    def adb(*args) -> str:
        return tablet.adb(device, *args)

    def there(kind: str) -> list:   # what the tablet has: "d" folders, "f" files, as named from its slides folder
        found = adb("shell", f"cd '{ON_TABLET}/slides' && find . -mindepth 1 -maxdepth 2 -type {kind}")
        return [line.strip()[2:] for line in found.split("\n") if line.strip().startswith("./")]

    print("Copying to the tablet", "over Wi-Fi..." if ":" in device else "by cable...")
    adb("shell", "mkdir", "-p", f"{ON_TABLET}/slides")
    for name in there("d"):
        if name not in folders:
            adb("shell", "rm", "-r", f"'{ON_TABLET}/slides/{name}'")
            print("removed from tablet: folder", name)
    for name in there("f"):
        if name not in wanted:
            adb("shell", "rm", f"'{ON_TABLET}/slides/{name}'")
            print("removed from tablet:", name)
    # The folders are made here and opened to the app: adb's own copying fails
    # at making one inside an app's storage, and a folder made over adb is at
    # first closed to the app itself.
    for name in folders:
        adb("shell", f"mkdir -p '{ON_TABLET}/slides/{name}' && chmod o+rx '{ON_TABLET}/slides/{name}'")
    summary = adb("push", "--sync", str(SHRUNK), ON_TABLET).strip().splitlines()
    if summary:
        print(summary[-1])
    print(f"{len(wanted)} picture(s) on the tablet"
          + (f", with the folder(s) {', '.join(folders)}: choose them in the settings (gear, Slideshow)." if folders else ".")
          + " Tap Slides there to load them, also if the slideshow is already running.")


if __name__ == "__main__":
    main()

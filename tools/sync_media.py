"""Copy what the alarm clock can play to the tablet:  python tools/sync_media.py [folder]

The folder (default: media/) is mirrored to the Wall Clock app on the tablet:
music (mp3, m4a, ogg, wav, flac), video (mp4, webm) and pictures. New and
changed files are copied as they are, files no longer in the folder are removed.
Goes by USB cable when the tablet is plugged in, else over Wi-Fi (see tablet.py).
"""

import sys
from pathlib import Path

import tablet

SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else tablet.ROOT / "media"
ON_TABLET = "/sdcard/Android/data/home.wallclock/files/media"
PLAYABLE = (".mp3", ".m4a", ".aac", ".ogg", ".wav", ".flac", ".mp4", ".m4v", ".webm", ".jpg", ".jpeg", ".png", ".webp", ".gif")


def main() -> None:
    if not SOURCE.is_dir():
        sys.exit(f"Folder not found: {SOURCE}")
    wanted = {file.name: file for file in sorted(SOURCE.iterdir()) if file.suffix.lower() in PLAYABLE}
    device = tablet.find()
    print("Copying to the tablet", "over Wi-Fi..." if ":" in device else "by cable...")
    tablet.adb(device, "shell", "mkdir", "-p", ON_TABLET)
    for name in tablet.adb(device, "shell", "ls", ON_TABLET).split("\n"):
        name = name.strip()
        if name and name not in wanted:
            tablet.adb(device, "shell", "rm", f"'{ON_TABLET}/{name}'")
            print("removed from tablet:", name)
    for name, file in wanted.items():
        tablet.adb(device, "push", "--sync", str(file), f"{ON_TABLET}/{name}")
    print(f"{len(wanted)} file(s) on the tablet. Choose them in the settings there, on the Alarm clock tab.")


if __name__ == "__main__":
    main()

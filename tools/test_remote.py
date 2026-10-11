"""Check the remote control's access rules on this PC:  python tools/test_remote.py

Compiles the server the tablet runs (android/src/.../Remote.java needs nothing
of Android) together with android/test/RemoteTest.java, and runs the checks:
which networks are let in, who gets past the user list, what is handed out.
Needs a JDK (javac, java) on the PATH.
"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "android" / "src" / "home" / "wallclock"
OUT = ROOT / "android" / "build" / "test"


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    files = [SOURCES / "Remote.java", SOURCES / "Mime.java", ROOT / "android" / "test" / "RemoteTest.java"]
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(OUT), *map(str, files)], check=True)
    sys.exit(subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", str(OUT), "home.wallclock.RemoteTest"]).returncode)


if __name__ == "__main__":
    main()

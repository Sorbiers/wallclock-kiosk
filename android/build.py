"""Build the Wall Clock app: python android/build.py  ->  dist/wallclock.apk

Uses Google's SDK tools directly (no Gradle): aapt2 packs the manifest, the
icon and the page (web/ becomes the app's assets), javac and d8 compile the
Java code, zipalign and apksigner finish the APK. Needs a JDK on PATH and the
Android SDK (set ANDROID_SDK_ROOT if it is not in F:/AndroidSdk).
"""

import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "android"
BUILD = APP / "build"
OUT = ROOT / "dist" / "wallclock.apk"

SDK = Path(os.environ.get("ANDROID_SDK_ROOT", "F:/AndroidSdk"))
TOOLS = sorted((SDK / "build-tools").iterdir())[-1]
PLATFORM = sorted((SDK / "platforms").iterdir(), key=lambda p: int(p.name.split("-")[1]))[-1] / "android.jar"

MIN_SDK, TARGET_SDK = "30", "34"
VERSION_CODE, VERSION_NAME = "1", "1.0"

# Updates must be signed with the same key, or Android refuses to install over
# the old app. It only proves "same builder", so a fixed password is fine here.
KEYSTORE = APP / "wallclock.keystore"
KEY_PASSWORD = "wallclock"


def run(*command) -> None:
    command = [str(part) for part in command]
    print(">", Path(command[0]).name, *command[1:4], "..." if len(command) > 4 else "")
    # .bat tools (d8, apksigner) need the shell on Windows
    subprocess.run(command, check=True, shell=command[0].endswith(".bat"))


def main() -> None:
    shutil.rmtree(BUILD, ignore_errors=True)
    (BUILD / "classes").mkdir(parents=True)
    OUT.parent.mkdir(exist_ok=True)

    # Resources, manifest and the page -> base APK (+ R.java)
    run(TOOLS / "aapt2", "compile", "--dir", APP / "res", "-o", BUILD / "res.zip")
    run(TOOLS / "aapt2", "link", "-o", BUILD / "base.apk", "-I", PLATFORM,
        "--manifest", APP / "AndroidManifest.xml", "-A", ROOT / "web", "--java", BUILD / "gen",
        "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
        "--version-code", VERSION_CODE, "--version-name", VERSION_NAME, BUILD / "res.zip")

    # Java -> classes -> dex
    sources = [*(APP / "src").rglob("*.java"), *(BUILD / "gen").rglob("*.java")]
    # core-lambda-stubs: the platform jar leaves out what javac needs to compile lambdas
    run("javac", "-source", "8", "-target", "8", "-Xlint:-options",
        "-bootclasspath", f"{PLATFORM}{os.pathsep}{TOOLS / 'core-lambda-stubs.jar'}",
        "-d", BUILD / "classes", *sources)
    run("jar", "cf", BUILD / "classes.jar", "-C", BUILD / "classes", ".")
    run(TOOLS / "d8.bat", "--release", "--lib", PLATFORM, "--min-api", MIN_SDK,
        "--output", BUILD, BUILD / "classes.jar")

    # APK = base + code, aligned and signed
    shutil.copy(BUILD / "base.apk", BUILD / "unsigned.apk")
    with zipfile.ZipFile(BUILD / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as apk:
        apk.write(BUILD / "classes.dex", "classes.dex")
    run(TOOLS / "zipalign", "-f", "-p", "4", BUILD / "unsigned.apk", BUILD / "aligned.apk")
    if not KEYSTORE.exists():
        run("keytool", "-genkeypair", "-keystore", KEYSTORE, "-storepass", KEY_PASSWORD, "-keypass", KEY_PASSWORD,
            "-alias", "wallclock", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=Wall Clock")
    run(TOOLS / "apksigner.bat", "sign", "--ks", KEYSTORE, "--ks-pass", "pass:" + KEY_PASSWORD,
        "--out", OUT, BUILD / "aligned.apk")
    print(f"\nBuilt {OUT} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failed:
        sys.exit(f"Build failed at: {Path(failed.cmd[0]).name}")

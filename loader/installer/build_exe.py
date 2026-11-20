"""Dev-only, Windows: builds Alloy-Installer-VERSION.exe, one file that runs the installer on a PC with no Java.

usage: python -I build_exe.py --jar alloy-installer.jar --version 0.5.4 [--out DIR] [--jdk JDK25_HOME] [--work DIR]

Steps: jdeps lists the modules the installer jar needs; jlink makes a trimmed Java 25 runtime with those plus what the
child JVMs need (the official Forge installer and Alloy's own tools run on this same Java, so the whole java.se set, the
crypto providers, zipfs and the locale data stay in); jpackage makes an app image with two launchers, "Alloy Installer"
(the window, no console) and alloy-cli (console, for --help and every other option). The image is zipped into the exe
of exe-src/Boot.cs (compiled with the csc of .NET Framework 4, which every Windows 10 and 11 has). On the first start the
exe unpacks the image into %LOCALAPPDATA%\\Alloy\\installer-VERSION-STAMP and runs it from there.

No WiX and no other installed tool is needed. The exe is not code-signed (SmartScreen warns once).
An icon is used when res/icon.ico exists.
"""
import argparse, glob, hashlib, os, shutil, stat, subprocess, sys, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent

# modules for the child JVMs (Forge installer and its processors, PrepFml, PrepGame, the name translator) on top of jdeps
EXTRA = ["java.se", "jdk.crypto.ec", "jdk.crypto.mscapi", "jdk.zipfs", "jdk.charsets", "jdk.management", "jdk.unsupported",
         "jdk.net", "jdk.naming.dns", "jdk.localedata"]
LOCALES = "en,ar,de,fr,es,pt,ru,zh,ja,ko,tr,pl,it,nl"


def find_jdk():
    for pat in [r"C:\Program Files\Eclipse Adoptium\jdk-25*", r"C:\Program Files\Java\jdk-25*", r"C:\Program Files\Microsoft\jdk-25*"]:
        hits = sorted(glob.glob(pat))
        if hits:
            return Path(hits[-1])
    sys.exit("no JDK 25 found; pass --jdk")


def run(cmd, **kw):
    print(">", " ".join(str(c) for c in cmd))
    return subprocess.run([str(c) for c in cmd], check=True, **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--out", default=".")
    ap.add_argument("--jdk")
    ap.add_argument("--work", default=str(HERE / "build" / "exe"))
    a = ap.parse_args()
    jar = Path(a.jar).resolve()
    jdk = Path(a.jdk) if a.jdk else find_jdk()
    work = Path(a.work).resolve()
    out = Path(a.out).resolve()
    if work.exists():
        shutil.rmtree(work, onerror=lambda f, p, e: (os.chmod(p, stat.S_IWRITE), f(p)))   # jpackage makes read-only files
    work.mkdir(parents=True)
    out.mkdir(parents=True, exist_ok=True)
    bin_ = jdk / "bin"

    deps = run([bin_ / "jdeps.exe", "--ignore-missing-deps", "--multi-release", "17", "--print-module-deps", jar],
               capture_output=True, text=True).stdout.strip().splitlines()[-1]
    mods = sorted(set(deps.split(",")) | set(EXTRA))
    print("jdeps:", deps)

    runtime = work / "runtime"
    run([bin_ / "jlink.exe", "--add-modules", ",".join(mods), "--include-locales=" + LOCALES, "--strip-debug", "--no-man-pages",
         "--no-header-files", "--compress=zip-9", "--output", runtime])

    inp = work / "input"
    inp.mkdir()
    shutil.copy2(jar, inp / "alloy-installer.jar")
    cli = work / "cli.properties"
    cli.write_text("win-console=true\ndescription=Alloy Installer (console)\n", encoding="ascii")
    cmd = [bin_ / "jpackage.exe", "--type", "app-image", "--name", "Alloy Installer", "--app-version", a.version,
           "--vendor", "Alloy", "--description", "Alloy Installer", "--input", inp, "--main-jar", "alloy-installer.jar",
           "--runtime-image", runtime, "--add-launcher", "alloy-cli=" + str(cli), "--dest", work / "image"]
    icon = HERE / "res" / "icon.ico"
    if icon.is_file():
        cmd += ["--icon", icon]
    run(cmd)
    image = work / "image" / "Alloy Installer"

    payload = work / "payload.zip"
    with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in sorted(image.rglob("*")):
            if p.is_file():
                z.write(p, p.relative_to(image).as_posix())
    stamp = hashlib.sha256(payload.read_bytes()).hexdigest()[:12]
    (work / "version.txt").write_text(a.version, encoding="ascii")
    (work / "stamp.txt").write_text(stamp, encoding="ascii")
    num = ".".join((a.version.split("-")[0].split(".") + ["0"] * 4)[:4])
    (work / "AssemblyInfo.cs").write_text(
        "using System.Reflection;\n"
        '[assembly: AssemblyTitle("Alloy Installer")]\n'
        '[assembly: AssemblyProduct("Alloy Installer")]\n'
        '[assembly: AssemblyDescription("Alloy Installer")]\n'
        f'[assembly: AssemblyVersion("{num}")]\n'
        f'[assembly: AssemblyFileVersion("{num}")]\n'
        f'[assembly: AssemblyInformationalVersion("{a.version}")]\n', encoding="ascii")

    csc = Path(os.environ.get("WINDIR", r"C:\Windows")) / "Microsoft.NET" / "Framework64" / "v4.0.30319" / "csc.exe"
    exe = out / f"Alloy-Installer-{a.version}.exe"
    cmd = [csc, "/nologo", "/target:winexe", "/platform:x64", "/optimize+", "/out:" + str(exe),
           "/r:System.Windows.Forms.dll", "/r:System.Drawing.dll", "/r:System.IO.Compression.dll",
           "/r:System.IO.Compression.FileSystem.dll",
           f"/resource:{payload},payload.zip", f"/resource:{work / 'version.txt'},version.txt",
           f"/resource:{work / 'stamp.txt'},stamp.txt"]
    if icon.is_file():
        cmd.append("/win32icon:" + str(icon))
    run(cmd + [HERE / "exe-src" / "Boot.cs", work / "AssemblyInfo.cs"])
    print(f"{exe} ({exe.stat().st_size / 1e6:.1f} MB, stamp {stamp})")


if __name__ == "__main__":
    main()

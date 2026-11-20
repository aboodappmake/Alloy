"""Dev-only: builds alloy-installer.jar (plain javac + jar). Users never need Python.

usage: python -I build_installer.py [--cache DIR] [--game-jar hybrid_game.jar] [--out alloy-installer.jar] [--versions 26.1.2,26.2]
                                  [--work120 DIR] [--sodium120 alloy-sodium120.jar] [--work1204 DIR] [--sodium1204 JAR] [--build120]

Minecraft versions: every versions/<id>.json whose fitted Fabric API (fabric.apiPrefit) is in res/prefit/ (make_prefit.py),
default version (ALLOY_MC, else 26.1.2) first. Each gets /versions/<id>.properties in the jar (the installer's --mc and
GUI picker read them); versions other than the default also get their own bridge, compiled against that version's run/
layout (ALLOY_MC=<id> build_bridge.compile_bridge), at /bridges/<id>/alloy-bridge.jar.

Minecraft 1.20.1 is different (Forge 47, SRG names): its bridge is the one launch120.py builds (bridge step, with the
compat120 and net120 sources), read from <work120>/bridge/alloy-bridge.jar, and Alloy's Sodium compat is the small mod
build_sodium120.py makes (--sodium120). Both are Alloy code only. The name translator (tools/remap) is bundled, so the
player's PC makes the Mojang-named game; nothing from Mojang or Forge goes into the jar.

Minecraft 1.20.4 (Forge 49) works the same way with its own build folder (--work1204, made by build120.py --mc 1.20.4):
its bridge carries the net49 and client49 fixes instead of net120, its fit lists (compat_replaces.1.20.4.txt +
prefit_strip.1.20.4.txt) go to /compat120/1.20.4/, and its Sodium compat is built against Sodium 0.5.8.
Every other 1.x version (1.20.2, 1.21.1 to 1.21.11) works the same way from its build120.py --mc <mc> folder
(--work-for MC=DIR, default run/build<mc without dots>), without a Sodium compat. Forge 50 and up get forgeNames=mojang
in their properties (the installer then skips the SRG steps, see V120.NATIVE).

The installer, its tools and the forgemods shim are compiled for Java 17 (Minecraft 1.20.1 runs on 17); 26.x installs
still check for Java 25 at run time.

Needs a populated Alloy cache (default: ../../run/install_test/.alloy-cache, made by install.py or the installer)
only to compile against: the bridge (build_bridge.compile_bridge, prebuilt into the jar), the language adapter
(fabric-loader) and the tools (ASM, also bundled). Shared files (bridge sources, tools/*.java, *.py) are only read.
"""
import argparse, json, os, shutil, subprocess, sys, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
FG = HERE.parent
sys.path.insert(0, str(FG))
import launch, build_bridge  # noqa: E402  (read-only use)
import alloy_version as V  # noqa: E402

JDK = launch.JAVA.parent
ASM = V.ASM
LOADER = V.LOADER
FABRIC_API = V.FABRIC_API
MIXINEXTRAS = V.MIXINEXTRAS
FEATURE_OFF_MARKER = "#alloy: feature-off"   # Fit.FEATURE_OFF: compat_replaces lines below it are hand strips, not compat mixins
RELEASE = "17"   # installer + tools + shim: Minecraft 1.20.1 starts them on Java 17 (Prism's PreLaunchCommand uses the game's Java)
WORK120 = FG.parent.parent / "run" / "installer120" / "l120"
SODIUM120 = FG.parent.parent / "run" / "installer120" / "sodium" / "out" / "alloy-sodium120.jar"
WORK1204 = FG.parent.parent / "run" / "build1204"   # build120.py --mc 1.20.4 default


def is120(vid):
    """a launch120.py version (Minecraft 1.x: Forge 47 to 61): its bridge comes from a build120.py work folder"""
    return vid.startswith("1.")


def default_work(vid):
    """build120.py's default work folder for a version (run/build120, run/build1204, run/build1211, ...)"""
    return FG.parent.parent / "run" / ("build120" if vid == "1.20.1" else "build" + vid.replace(".", ""))


def javac(out, cp, srcs, release=RELEASE):
    r = subprocess.run([str(JDK / "javac.exe"), "-J-Duser.language=en", "-nowarn", "-proc:none", "--release", release,
                        "-d", str(out), "-cp", ";".join(map(str, cp)), *map(str, srcs)], capture_output=True, text=True)
    if r.returncode:
        sys.exit("javac failed:\n" + r.stdout + r.stderr)


def installer_versions(res):
    """Version ids to bundle: profiles whose prefit Fabric API is in res/prefit, the default version first."""
    have = set()
    for f in sorted((FG / "versions").glob("*.json")):
        prof = json.loads(f.read_text(encoding="utf-8"))
        if (res / "prefit" / str(prof.get("fabric", {}).get("apiPrefit", ""))).is_file():
            have.add(prof["id"])
    if V.ID not in have:
        sys.exit(f"default version {V.ID}: no fitted Fabric API in {res / 'prefit'}")
    # numeric order (1.21.4 before 1.21.11), the default version first
    return [V.ID] + sorted(have - {V.ID}, key=lambda v: [int(x) if x.isdigit() else 0 for x in v.split(".")])


def mixin_expand_frames(forge_libs):
    """True when Forge's own Mixin is 0.8.5 (Forge 47, 48, 49: Minecraft 1.20.1 to 1.20.4). Its ModLauncher service reads
    ClassInfo with ClassReader.EXPAND_FRAMES; Knot reads compressed frames unless mixin.tunable.classReaderExpandFrames is
    set, and Mixin's ClassInfo frame sizes are wrong for compressed CHOP frames (MMC4 Sawmill @Local). Forge 52 and up (Mixin 0.8.7) read compressed frames too, so they get nothing."""
    return forge_libs.get("mixin", "").startswith("0.8.5")


def version_properties(vid):
    """The values Installer.select reads for one version (from versions/<id>.json)."""
    prof = json.loads((FG / "versions" / f"{vid}.json").read_text(encoding="utf-8"))
    lib = {c.split(":")[1]: c.split(":")[2] for c in prof["forge"]["libraries"] if c.count(":") >= 2}
    vals = {"minecraft": prof["minecraft"], "forge": prof["forge"]["full"], "loader": prof["fabric"]["loader"],
            "mixin": prof["fabric"]["mixin"], "asm": prof["java"]["asm"], "fabricApi": prof["fabric"]["api"],
            "apiPrefit": prof["fabric"]["apiPrefit"], "modlauncher": lib["modlauncher"]}
    for k in ("securemodules", "unsafe"):   # Forge 64 only (Forge 47 has securejarhandler, no unsafe)
        if k in lib:
            vals[k] = lib[k]
    args = prof["forge"].get("launchArgs", [])
    if prof.get("mappings", {}).get("forge") == "mojang" and prof["minecraft"].startswith("1."):
        vals["forgeNames"] = "mojang"   # Forge 50+: the installer's game is Mojang-named (V120.NATIVE)
    if "--fml.mcpVersion" in args:   # Forge 47: the MCPConfig version names Forge's SRG jars
        vals["mcp"] = args[args.index("--fml.mcpVersion") + 1]
    elif prof["forge"].get("mcp"):    # Forge 49: no launch args, the profile names it
        vals["mcp"] = prof["forge"]["mcp"]
    if prof["forge"].get("fmlFamily"):   # PrepFml rule set (V120.FAMILY), fml49 for Forge 49
        vals["fmlFamily"] = prof["forge"]["fmlFamily"]
    if mixin_expand_frames(lib):   # Forge 47 to 49 (V120.EXPAND_FRAMES)
        vals["mixinExpandFrames"] = "true"
    return "# generated by build_installer.py from versions/%s.json\n" % vid + "".join(f"{k}={v}\n" for k, v in vals.items())


def other_bridge(vid, out):
    """Compile the bridge for another Minecraft version (its run/ layout, see alloy_version.py) into out/alloy-bridge.jar."""
    code = ("import sys; sys.path.insert(0, sys.argv[1]); import build_bridge; "
            "print(build_bridge.compile_bridge(out=sys.argv[2]))")
    subprocess.check_call([sys.executable, "-I", "-c", code, str(FG), str(out)], env={**os.environ, "ALLOY_MC": vid}, cwd=str(FG))
    return Path(out) / "alloy-bridge.jar"


def same_classes(a, b):
    """True when two jars hold the same files with the same contents (timestamps ignored)."""
    with zipfile.ZipFile(a) as za, zipfile.ZipFile(b) as zb:
        ca = {i.filename: i.CRC for i in za.infolist() if not i.is_dir()}
        cb = {i.filename: i.CRC for i in zb.infolist() if not i.is_dir()}
    return ca == cb


def build_agent(build, out):
    """alloy-agent.jar: Premain-Class alloy.agent.Premain, Java 17 like the installer."""
    acls = build / "agent-classes"
    shutil.rmtree(acls, ignore_errors=True)
    acls.mkdir(parents=True)
    javac(acls, [], list((HERE / "agent-src").rglob("*.java")) + [HERE / "shim-src" / "alloy" / "fabinst" / "ArgFile.java"])
    amf = build / "agent-manifest.txt"
    amf.write_text("Premain-Class: alloy.agent.Premain" + chr(10), encoding="ascii")
    out.unlink(missing_ok=True)
    subprocess.check_call([str(JDK / "jar.exe"), "--create", "--file", str(out), "--manifest", str(amf), "-C", str(acls), "."])
    print(f"built {out} ({out.stat().st_size / 1e3:.0f} KB)")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default=str(FG.parent.parent / "run" / "install_test" / ".alloy-cache"))
    ap.add_argument("--game-jar", default=str(FG.parent.parent / "run" / "install_test" / ".alloy" / "hybrid_game.jar"))
    ap.add_argument("--out", default=str(HERE / "alloy-installer.jar"))
    ap.add_argument("--reuse-bridge", action="store_true", help="skip recompiling the bridge if build/bridge/alloy-bridge.jar exists")
    ap.add_argument("--versions", help="comma-separated Minecraft versions to bundle (default: every version with a prefit Fabric API)")
    ap.add_argument("--work120", default=str(WORK120), help="launch120.py work folder whose bridge step made the 1.20.1 bridge")
    ap.add_argument("--sodium120", default=None, help="alloy-sodium120.jar from build_sodium120.py (default <work120>/sodium/out/)")
    ap.add_argument("--work1204", default=str(WORK1204), help="build120.py --mc 1.20.4 work folder (the 1.20.4 bridge and Sodium compat)")
    ap.add_argument("--sodium1204", default=None, help="1.20.4 alloy-sodium120.jar (default <work1204>/sodium/out/)")
    ap.add_argument("--work-for", action="append", default=[], metavar="MC=DIR",
                    help="build120.py work folder of another 1.x version (default run/build<mc without dots>), e.g. 1.21.1=run/b05/build1211")
    ap.add_argument("--build120", action="store_true", help="first build the bridges, prefits and Sodium compats of every 1.x version (build120.py)")
    a = ap.parse_args()
    vids = a.versions.split(",") if a.versions else None
    works = {"1.20.4": a.work1204}
    for x in a.work_for:
        k, _, d = x.partition("=")
        works[k] = d
    if a.build120:
        a.work120 = a.work120 if a.work120 != str(WORK120) else str(FG.parent.parent / "run" / "build120")
        subprocess.check_call([sys.executable, "-I", str(HERE / "build120.py"), "--work", a.work120])
        for vid in (vids or installer_versions(HERE / "res")):
            if is120(vid) and vid != "1.20.1":
                subprocess.check_call([sys.executable, "-I", str(HERE / "build120.py"), "--mc", vid, "--work", str(works.get(vid, default_work(vid)))])
    if a.sodium120 is None:
        a.sodium120 = str(Path(a.work120) / "sodium" / "out" / "alloy-sodium120.jar")
        if not Path(a.sodium120).exists() and Path(a.work120) == WORK120:
            a.sodium120 = str(SODIUM120)
    if a.sodium1204 is None:
        a.sodium1204 = str(Path(a.work1204) / "sodium" / "out" / "alloy-sodium120.jar")
    vids = vids or installer_versions(HERE / "res")
    if subprocess.run([sys.executable, "-I", str(HERE / "pins.py"), "check"]).returncode:   # fail early; the jar is checked again below
        sys.exit("res/pins.txt is missing SHA-256 pins (see above)")
    if vids[0] != V.ID:
        sys.exit(f"the first version must be the default one ({V.ID}); set ALLOY_MC to build with another default")
    cache = Path(a.cache)
    fl = cache / "fabric-libs"
    build = HERE / "build"
    classes = build / "classes"
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(parents=True)
    asm = [fl / f"org/ow2/asm/{x}/{ASM}/{x}-{ASM}.jar" for x in ("asm", "asm-tree", "asm-commons", "asm-util", "asm-analysis")]
    loader = fl / f"net/fabricmc/fabric-loader/{LOADER}/fabric-loader-{LOADER}.jar"
    mixin = next((fl / "net/fabricmc/sponge-mixin").rglob("*.jar"))

    # 1. bridge (same compile as install.py step_bridge, server flavour)
    bridge_dir = build / "bridge"
    bridge_jar = bridge_dir / "alloy-bridge.jar"
    if not (a.reuse_bridge and bridge_jar.exists()):
        client_jar = cache / "mojang" / f"client-{V.MC}.jar"
        runtime = launch.classpath_base(forge=cache / "forge" / "server", fabric_libs=fl, fml_patched=cache / "fml-server")
        fa = build_bridge.fabric_api_compile_jars(cache / "fabric" / f"fabric-api-{FABRIC_API}.jar",
                                                  cache / "fabric" / f"mixinextras-fabric-{MIXINEXTRAS}.jar", cache / "fabric-api-libs")
        bridge_jar = build_bridge.compile_bridge(runtime + [a.game_jar, str(client_jar)] + fa, out=bridge_dir, jdk=JDK)

    # 2. installer + PrepGame/PrepFml (unmodified copies of ../tools) + language adapter
    srcs = list((HERE / "src").rglob("*.java")) + [FG / "tools" / "PrepGame.java", FG / "tools" / "PrepFml.java", FG / "tools" / "RedirectToWrap.java", FG / "tools" / "RelocatePackage.java",
            FG / "bridge" / "src" / "alloy" / "report" / "LogDiagnosis.java",   # crash-text rules, shared with the bridge (Mod Doctor: last crash)
            FG / "bridge" / "src" / "alloy" / "presence" / "PresenceTable.java"]   # presence aliases, shared with the bridge (Mod Doctor: data pack conditions)
    # offline world check (--check-world): the plain NBT and world code of the bridge, no Minecraft classes
    srcs += [FG / "bridge" / "src" / "alloy" / "world" / f"{n}.java" for n in ("Nbt", "WorldBackup", "WorldCheck", "WorldMods", "Keeper")]
    srcs += list((FG / "tools" / "remap" / "src").rglob("*.java"))   # the name translator (Minecraft 1.20.1: SRG/Intermediary -> Mojang on the player's PC)
    javac(classes, asm, srcs)
    javac(classes, [loader, mixin] + asm, list((HERE / "shim-src").rglob("*.java")))

    # 3. bundle ASM + the prebuilt bridge
    for j in asm:
        with zipfile.ZipFile(j) as z:
            for n in z.namelist():
                if n.endswith("/") or n.startswith("META-INF/") or n == "module-info.class":
                    continue
                (classes / n).parent.mkdir(parents=True, exist_ok=True)
                (classes / n).write_bytes(z.read(n))
    shutil.copy(bridge_jar, classes / "alloy-bridge.jar")
    # 3a. Minecraft versions: /versions/index.txt (default first), /versions/<id>.properties, other versions' bridges
    (classes / "versions").mkdir()
    (classes / "versions" / "index.txt").write_text("".join(v + "\n" for v in vids), encoding="utf-8")
    for vid in vids:
        (classes / "versions" / f"{vid}.properties").write_text(version_properties(vid), encoding="utf-8")
        if is120(vid):   # Forge 47 to 61 bridge: launch120.py's build, plus the Sodium compat mod where there is one
            w12, s12 = (a.work120, a.sodium120) if vid == "1.20.1" else (a.work1204, a.sodium1204) if vid == "1.20.4" else (works.get(vid, default_work(vid)), None)
            if vid.startswith("1.21"):   # the compat for Sodium 0.6+ (build_sodium121.py), when the version's build made it
                s121 = Path(w12) / "sodium" / "out" / "alloy-sodium121.jar"
                s12 = str(s121) if s121.is_file() else None
            elif s12 is None:   # 1.20.2: the Sodium 0.5 compat (build_sodium120.py), when the version's build made it
                s120 = Path(w12) / "sodium" / "out" / "alloy-sodium120.jar"
                s12 = str(s120) if s120.is_file() else None
            bj, sj = Path(w12) / "bridge" / "alloy-bridge.jar", Path(s12) if s12 else None
            for f in (bj, sj) if sj else (bj,):
                if not f.is_file():
                    sys.exit(f"Minecraft {vid}: missing {f} (build120.py --mc {vid}, or launch120.py --mojang mods + bridge and build_sodium120.py build)")
            with zipfile.ZipFile(bj) as z:
                fmj = json.loads(z.read("fabric.mod.json"))
                c120cfg = json.loads(z.read("alloy.compat120.mixins.json")) if "alloy.compat120.mixins.json" in z.namelist() else {}
            if "alloy.compat120.mixins.json" not in fmj.get("mixins", []):
                sys.exit(f"{bj} has no compat120 mixins: build it with launch120.py --mojang after the mods step (Fabric API)")
            if vid == "1.20.1" and "alloy.compat120.net.mixins.json" not in fmj.get("mixins", []):
                sys.exit(f"{bj} has no net120 mixins (Forge 47 login fixes): build it with launch120.py --mojang after the mods step")
            if vid in ("1.20.2", "1.20.4") and not any(m.startswith("net49.") for m in c120cfg.get("mixins", [])):
                sys.exit(f"{bj} has no net49 mixins (Forge 48/49 payload fix): build it with ALLOY_MC={vid} launch120.py --mojang bridge")
            if "alloy.compat120.sodium.mixins.json" in fmj.get("mixins", []):
                sys.exit(f"{bj} carries the Sodium compat (Sodium was in the mods step); the installer ships that as its own jar, rebuild without Sodium")
            (classes / "bridges" / vid).mkdir(parents=True)
            shutil.copy(bj, classes / "bridges" / vid / "alloy-bridge.jar")
            if sj:
                shutil.copy(sj, classes / "bridges" / vid / sj.name)   # alloy-sodium120.jar, or alloy-sodium121.jar on 1.21.1
            c120 = FG / "bridge" / "src" / "alloy" / "compat120"
            # one file for the fitter: the compat entries, then (after the marker line) the hand strips. Those are not
            # re-implemented by Alloy, their feature is off, and the fit report must say so (Fit.applyCompat reads the marker).
            # 1.20.1 keeps its old place (/compat120/compat_replaces.txt), other versions get /compat120/<id>/ (V120.compatText)
            strips = [f"prefit_strip.{vid}.txt"] + ([f"prefit_strip.portinglib.{vid}.txt"] if (c120 / f"prefit_strip.portinglib.{vid}.txt").exists() else [])
            cdir = classes / "compat120" if vid == "1.20.1" else classes / "compat120" / vid
            cdir.mkdir(parents=True, exist_ok=True)
            (cdir / "compat_replaces.txt").write_text(
                (c120 / f"compat_replaces.{vid}.txt").read_text(encoding="utf-8") + "\n" + FEATURE_OFF_MARKER + "\n"
                + "".join((c120 / f).read_text(encoding="utf-8") + "\n" for f in strips), encoding="utf-8")
            # the AW drop lines launch120.py applies to every Fabric mod; the fitter applies them to players' mods (V120.awEditsText)
            if (c120 / f"aw_edits.{vid}.txt").exists():
                shutil.copy(c120 / f"aw_edits.{vid}.txt", classes / "compat120" / f"aw_edits.{vid}.txt")
            print(f"bridge for {vid}: {bj} ({len(fmj.get('mixins', []))} mixin configs), Sodium compat {sj.name if sj else 'none'}")
            continue
        if vid != V.ID:
            bj = build / f"bridge-{vid}" / "alloy-bridge.jar"
            if not (a.reuse_bridge and bj.exists()):
                bj = other_bridge(vid, build / f"bridge-{vid}")
            if same_classes(bj, bridge_jar):   # the bridge source is shared; compiled the same, the default copy serves both
                print(f"bridge for {vid}: same classes as the default one, not bundled twice")
                continue
            (classes / "bridges" / vid).mkdir(parents=True)
            shutil.copy(bj, classes / "bridges" / vid / "alloy-bridge.jar")
    print("Minecraft versions: " + ", ".join(vids) + f" (default {vids[0]})")
    (classes / "META-INF").mkdir(exist_ok=True)
    for f in ("LICENSE", "NOTICE"):   # Apache-2.0 text, plus the bundled ASM (BSD) and fitted Fabric API notices
        src = FG.parent / f
        if not src.exists() and (FG.parent / (f + ".md")).exists():   # this repository keeps the notice as NOTICE.md
            src = FG.parent / (f + ".md")
        shutil.copy(src, classes / "META-INF" / f)
    # 3b. fit resources: compat_replaces.txt (copy of ../compat_replaces.txt, refreshed here) + hand-fitted Fabric API
    res = HERE / "res"
    if (FG / "compat_replaces.txt").exists():
        shutil.copy(FG / "compat_replaces.txt", res / "compat_replaces.txt")
    if res.exists():
        for f in res.rglob("*"):
            if f.is_file() and not f.name.endswith(".inject.txt"):
                dst = classes / f.relative_to(res)
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(f, dst)
    shutil.copytree(FG / "bridge" / "res-common", classes, dirs_exist_ok=True)   # crash signatures for LogDiagnosis (Mod Doctor, --report)
    # 3c. Fabric instance mode agent (agent-src + the shared alloy.fabinst.ArgFile): alloy-agent.jar next to the installer and
    # inside it (/alloy-agent.jar, which --prep copies to .alloy/ of a game dir with .alloy/fabric-instance.properties)
    agent = build_agent(build, Path(a.out).with_name("alloy-agent.jar"))
    shutil.copy(agent, classes / "alloy-agent.jar")
    mf = build / "manifest.txt"
    # launcher stub: Java 8 bytecode so an old Java can show the friendly message (it is the jar's Main-Class)
    subprocess.run([str(JDK / "javac.exe"), "-J-Duser.language=en", "-nowarn", "-proc:none", "--release", "8", "-d", str(classes),
                    str(HERE / "stub-src" / "alloy" / "Launch.java")], check=True)
    mf.write_text("Main-Class: alloy.Launch\n")
    out = Path(a.out)
    out.unlink(missing_ok=True)
    subprocess.check_call([str(JDK / "jar.exe"), "--create", "--file", str(out), "--manifest", str(mf), "-C", str(classes), "."])
    print(f"built {out} ({out.stat().st_size / 1e6:.1f} MB)")
    # every non-Mojang download of every bundled version must have a SHA-256 pin (res/pins.txt, pins.py), checked by the
    # new jar's own code so a download added in Java without a pin fails here, not on a player's PC
    r = subprocess.run([str(JDK / "java.exe"), "-Duser.language=en", "-jar", str(out), "--check-pins"], capture_output=True, text=True)
    print(r.stdout.strip())
    if r.returncode:
        out.unlink(missing_ok=True)
        sys.exit("pin check failed (jar removed):\n" + r.stdout + r.stderr)

    # 4. launch wrapper for the launcher format (docs/launchers.md): tiny, no dependencies; --launcher-profile adds the profile
    lclasses = build / "launch-classes"
    shutil.rmtree(lclasses, ignore_errors=True)
    lclasses.mkdir(parents=True)
    javac(lclasses, [], list((HERE / "launch-src").rglob("*.java")), release=V.JAVA_RELEASE)
    # the entry class again for Java 8, so an old Java prints a plain "needs Java 25" line instead of a class version error
    subprocess.run([str(JDK / "javac.exe"), "-J-Duser.language=en", "-nowarn", "-proc:none", "--release", "8", "-d", str(lclasses),
                    str(HERE / "launch-src" / "alloy" / "launch" / "AlloyLaunch.java")], check=True)
    lout = out.with_name("alloy-launch.jar")
    lout.unlink(missing_ok=True)
    subprocess.check_call([str(JDK / "jar.exe"), "--create", "--file", str(lout), "-C", str(lclasses), "."])
    print(f"built {lout} ({lout.stat().st_size / 1e3:.0f} KB)")

    # 5. the full agent for a fresh Fabric instance: the agent classes plus this installer as
    # /alloy-installer.jar, which the agent unpacks into .alloy/ and runs with --fabric-instance on the first start.
    # Players (or packs) put it into the instance as .alloy/alloy-agent.jar; --prep later swaps in the small agent.
    full = build / "agent-full"
    shutil.rmtree(full, ignore_errors=True)
    shutil.copytree(build / "agent-classes", full)
    shutil.copy(out, full / "alloy-installer.jar")
    fout = out.with_name("alloy-agent-full.jar")
    fout.unlink(missing_ok=True)
    subprocess.check_call([str(JDK / "jar.exe"), "--create", "--file", str(fout), "--manifest", str(build / "agent-manifest.txt"), "-C", str(full), "."])
    print(f"built {fout} ({fout.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()

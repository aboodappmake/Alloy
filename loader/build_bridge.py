"""Build the Alloy bridge mod and set up a game dir (default run/hybrid) for a set of Forge mods.

  <game>/mods/        Fabric mods: alloy-bridge.jar, the generated alloy-modmixins.jar, plus any --fabric jars
  <game>/forgemods/   Forge mods (read only by FML, see ModsDirMixin)
  <game>/forgemods/.nested/   jar-in-jar libraries extracted from the Forge mods (put on Knot's classpath by launch.py,
                      so their classes and mixin plugins exist from startup; FML still reads their metadata itself)

usage: python -I build_bridge.py [--game DIR] [--fabric fabric-mod.jar ...] [--] [forge-mod.jar ...]
"""
import argparse, io, json, os, shutil, subprocess, sys, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import launch  # noqa: E402
import alloy_version as V  # noqa: E402
import plat  # noqa: E402

JDK = launch.JAVA.parent
B = HERE / "bridge"
OUT = B / "build"


def copy_forge_jar(src, dst):
    """Copy a Forge jar into forgemods/. Drops jar-in-jar metadata entries for MixinExtras whose nested jar is missing
    (Forge supplies MixinExtras itself; Alloy gets it from Fabric, so FML's JarSelector would fail). Other missing
    entries are kept: real Forge fails on those too. Writes a patched copy; the source jar is never modified."""
    with zipfile.ZipFile(src) as zi:
        names = set(zi.namelist())
        if "META-INF/jarjar/metadata.json" not in names:
            return shutil.copy(src, dst)
        d = json.loads(zi.read("META-INF/jarjar/metadata.json"))
        jars = d.get("jars", [])
        keep = [x for x in jars if x.get("path") in names or "mixinextras" not in x.get("identifier", {}).get("artifact", "").lower()]
        if len(keep) == len(jars):
            return shutil.copy(src, dst)
        d["jars"] = keep
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zo:
            for i in zi.infolist():
                zo.writestr(i, json.dumps(d, indent=2) if i.filename == "META-INF/jarjar/metadata.json" else zi.read(i.filename))


def fabric_api_compile_jars(src=None, mx=None, out=None):
    """Fabric API module jars, compile-time only (compat mixins call Fabric API; at runtime they apply only if it is installed).
    Optional src (Fabric API jar), mx (MixinExtras jar) and out (extraction dir) default to the run/ layout."""
    src = Path(src) if src else V.path("fabricMods") / f"fabric-api-{V.FABRIC_API}.jar"
    out = Path(out) if out else OUT.parent / ("fabric-api-libs" if V.ID == "26.1.2" else f"fabric-api-libs-{V.ID}")
    if not src.exists():
        return []
    if not out.exists():
        out.mkdir(parents=True)
        with zipfile.ZipFile(src) as z:
            for n in z.namelist():
                if n.startswith("META-INF/jars/") and n.endswith(".jar"):
                    (out / Path(n).name).write_bytes(z.read(n))
    mx = Path(mx) if mx else V.path("mixinExtrasJar")   # MixinExtras API for compat mixins
    return [str(p) for p in sorted(out.glob("*.jar"))] + ([str(mx)] if mx.exists() else [])


def compile_bridge(cp=None, out=None, jdk=None):
    """Compile the bridge into <out>/alloy-bridge.jar. cp = compile class path (default: run/ layout)."""
    global OUT
    out = Path(out) if out else OUT
    jdk = Path(jdk) if jdk else JDK
    shutil.rmtree(out, ignore_errors=True)
    (out / "classes").mkdir(parents=True)
    if cp is None:
        cp = launch.classpath_base() + [str(launch.GAME_JAR), str(V.path("clientGameJar"))] + fabric_api_compile_jars()
    # compat/<area>/ folders are only built once they are reviewed and switched on: marker file ENABLED
    srcs = [str(p) for p in (B / "src").rglob("*.java")
            if "compat" not in p.relative_to(B / "src").parts or (p.parent / "ENABLED").exists()]
    api_src = HERE / "api" / "src"   # Alloy API (alloy/api/, api/docs/API.md), bundled in the bridge
    if api_src.is_dir():
        srcs += [str(p) for p in api_src.rglob("*.java")]
    r = subprocess.run([str(plat.tool(jdk, "javac")), "-J-Duser.language=en", "-nowarn", "-proc:none", "--release", V.JAVA_RELEASE,
                        "-d", str(out / "classes"), "-cp", os.pathsep.join(cp), *srcs], capture_output=True, text=True)
    if r.returncode:
        print(r.stdout + r.stderr)
        sys.exit(1)
    for f in (B / "res").iterdir():
        shutil.copy(f, out / "classes" / f.name)
    shutil.copytree(B / "res-common", out / "classes", dirs_exist_ok=True)   # shared with every bridge and the installer (crash signatures)
    (out / "classes" / "META-INF").mkdir(exist_ok=True)
    shutil.copy(HERE.parent / "LICENSE", out / "classes" / "META-INF" / "LICENSE")   # Apache-2.0
    jar = out / "alloy-bridge.jar"
    subprocess.check_call([str(plat.tool(jdk, "jar")), "cf", str(jar), "-C", str(out / "classes"), "."])
    return jar


def manifest_mixins(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        if "META-INF/MANIFEST.MF" not in z.namelist():
            return []
        mf = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    mf = mf.replace("\r\n ", "").replace("\n ", "")
    for line in mf.splitlines():
        if line.startswith("MixinConfigs:"):
            return [c.strip() for c in line.split(":", 1)[1].split(",") if c.strip()]
    return []


def nested_jars(data, found):
    """Recursively collect jar-in-jar libraries as (file name, bytes): every jars[].path in META-INF/jarjar/metadata.json
    (any path is legal, e.g. Modern UI keeps them in META-INF/jars/), plus META-INF/jarjar/*.jar as a fallback."""
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = set(z.namelist())
        paths = []
        if "META-INF/jarjar/metadata.json" in names:
            try:
                paths = [j["path"] for j in json.loads(z.read("META-INF/jarjar/metadata.json")).get("jars", []) if j.get("path")]
            except Exception:
                pass
        paths += sorted(n for n in names if n.startswith("META-INF/jarjar/") and n.endswith(".jar"))
        for n in dict.fromkeys(paths):
            if n in names:
                b = z.read(n)
                found.append((Path(n).name, b))
                nested_jars(b, found)


def resolve_redirect_collisions(game, jdk=None, tools_dir=None, asm_libs=None):
    """A Forge mod and a Fabric mod that @Redirect the same call site make Mixin fail ("@Redirect conflict", then a
    critical injection failure at startup). tools/RedirectToWrap rewrites the FORGE mod's colliding handlers (in the
    copies under <game>/forgemods and forgemods/.nested) to MixinExtras @WrapOperation, which chains with the Fabric
    @Redirect. Mods without a collision are not touched. Writes <game>/alloy-collisions.txt (read by the bridge's
    AlloyReport). Returns the log lines."""
    game = Path(game)
    mods, fmods = game / "mods", game / "forgemods"
    log = game / "alloy-collisions.txt"
    forge = sorted(fmods.glob("*.jar")) + sorted((fmods / ".nested").glob("*.jar"))
    fabric = [p for p in sorted(mods.glob("*.jar")) if not p.name.startswith("alloy-")]
    log.unlink(missing_ok=True)
    if not forge or not fabric:
        return []
    jdk = Path(jdk) if jdk else JDK
    tools = Path(tools_dir) if tools_dir else HERE / "build" / "tools"
    asm = Path(asm_libs or launch.FABRIC_LIBS) / "org/ow2/asm"
    cp = [str(asm / f"{a}/{V.ASM}/{a}-{V.ASM}.jar") for a in ("asm", "asm-tree")]
    if not (tools / "RedirectToWrap.class").exists():
        tools.mkdir(parents=True, exist_ok=True)
        r = subprocess.run([str(plat.tool(jdk, "javac")), "-J-Duser.language=en", "-nowarn", "-proc:none", "--release", "25", "-d", str(tools),
                            "-cp", os.pathsep.join(cp), str(HERE / "tools" / "RedirectToWrap.java")], capture_output=True, text=True)
        if r.returncode:
            print("RedirectToWrap compile failed (collisions are not resolved):", (r.stdout + r.stderr)[-500:])
            return []
    tmp = game / ".collisions.tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    r = subprocess.run([str(plat.tool(jdk, "java")), "-Duser.language=en", "-cp", os.pathsep.join([str(tools)] + cp), "RedirectToWrap", str(tmp),
                        ";".join(map(str, fabric)), *map(str, forge)], capture_output=True, text=True)
    if r.returncode:
        print("RedirectToWrap failed (collisions are not resolved):", (r.stdout + r.stderr)[-500:])
        shutil.rmtree(tmp, ignore_errors=True)
        return []
    lines = [l for l in r.stdout.splitlines() if l.startswith(("COLLISION", "CONVERT", "WARN"))]
    if tmp.exists():
        for f in tmp.glob("*.jar"):
            for d in (fmods, fmods / ".nested"):
                if (d / f.name).exists():
                    shutil.copy(f, d / f.name)
        shutil.rmtree(tmp, ignore_errors=True)
    if lines:
        log.write_text(chr(10).join(lines) + chr(10), encoding="utf-8")
        print(chr(10).join("  " + l for l in lines))
    return lines


def fabric_mixin_configs(mods):
    """mixin config names the Fabric mods in <game>/mods declare (fabric.mod.json "mixins", nested jars too) -> jar name"""
    out = {}
    def scan(data, name):
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            names = set(z.namelist())
            if "fabric.mod.json" in names:
                try:
                    d = json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"), strict=False)
                    for m in d.get("mixins", []):
                        c = m if isinstance(m, str) else m.get("config")
                        if c:
                            out.setdefault(c, (name, z.read(c) if c in names else b""))
                except Exception:
                    pass
            for n in names:
                if n.startswith("META-INF/jars/") and n.endswith(".jar"):
                    scan(z.read(n), name)
    for j in sorted(Path(mods).glob("*.jar")):
        if j.name != "alloy-modmixins.jar":
            scan(j.read_bytes(), j.name)
    return out


def drop_twin_configs(cfgs, mods, game):
    """A library installed for both loaders (GeckoLib Forge in forgemods/, GeckoLib Fabric in mods/) ships the same mixin
    config name twice. Both jars are on Knot's class path, so the name resolves to one file anyway, and declaring it a
    second time stops Fabric Loader ("Non-unique Mixin config name"). Keep the Fabric declaration, drop the Forge one.
    A config whose content differs between the two copies is reported (the Forge copy's version is not applied)."""
    fab = fabric_mixin_configs(mods)
    keep, lines = [], []
    fdir = Path(game) / "forgemods"
    for c in dict.fromkeys(cfgs):
        if c not in fab:
            keep.append(c)
            continue
        same = None
        for j in sorted(fdir.glob("*.jar")) + sorted((fdir / ".nested").glob("*.jar")):
            with zipfile.ZipFile(j) as z:
                if c in z.namelist():
                    same = z.read(c) == fab[c][1]
                    break
        lines.append(f"TWIN {c}: declared by Fabric {fab[c][0]}; Forge copy not declared again" + ("" if same else " (WARN: its content differs)"))
    if lines:
        print(chr(10).join("  " + l for l in lines))
        (Path(game) / "alloy-twins.txt").write_text(chr(10).join(lines) + chr(10), encoding="utf-8")
    else:
        (Path(game) / "alloy-twins.txt").unlink(missing_ok=True)
    return keep


# Mod ids that Fabric mods depend on but that may exist only as a Forge mod. When one of
# these is installed on the Forge side only, alloy-modmixins.jar carries a tiny jar-in-jar Fabric mod with that id and the
# Forge mod's version, so Fabric Loader resolves e.g. Biomes O' Plenty Fabric's "terrablender" dependency. No classes.
FORGE_STUB_IDS = ("jei", "roughlyenoughitems", "emi", "terrablender")


def forge_mod_versions(data):
    """{modId: version} of the [[mods]] tables in a Forge jar's mods.toml (${file.jarVersion} -> manifest version)."""
    import re
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = set(z.namelist())
        if "META-INF/mods.toml" not in names:
            return {}
        toml = z.read("META-INF/mods.toml").decode("utf-8", "replace")
        mf = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace") if "META-INF/MANIFEST.MF" in names else ""
    jar_ver = next((l.split(":", 1)[1].strip() for l in mf.splitlines() if l.startswith("Implementation-Version:")), "0.0.0")
    out, table, mid = {}, False, None
    for raw in toml.splitlines():
        l = raw.split("#", 1)[0].strip()
        if l.startswith("["):
            table, mid = l.replace(" ", "").startswith("[[mods]]"), None
            continue
        m = re.match(r"(modId|version)\s*=\s*[\"']([^\"']*)[\"']", l)
        if table and m:
            if m.group(1) == "modId":
                mid = m.group(2)
                out.setdefault(mid, jar_ver)
            elif mid:
                out[mid] = jar_ver if "${" in m.group(2) else m.group(2)
    return out


def fabric_mod_id(path):
    try:
        with zipfile.ZipFile(path) as z:
            return json.loads(z.read("fabric.mod.json").decode("utf-8", "replace")).get("id")
    except Exception:
        return None


def forge_stubs(forge, fabric):
    """[(id, version, forge file name)] for FORGE_STUB_IDS present only as Forge mods."""
    have = {fabric_mod_id(f) for f in fabric}
    out = []
    for m in forge:
        for mid, ver in forge_mod_versions(Path(m).read_bytes()).items():
            if mid in FORGE_STUB_IDS and mid not in have and mid not in {o[0] for o in out}:
                out.append((mid, ver, Path(m).name))
    return out


def stub_jar(mid, ver, src):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("fabric.mod.json", json.dumps({
            "schemaVersion": 1, "id": mid, "version": ver, "name": f"{mid} (Forge mod, Alloy stub)", "environment": "*",
            "description": f"Generated by Alloy. Stands in for the Forge mod {src} so Fabric mods that depend on '{mid}' resolve.",
            "custom": {"alloy:forge-stub": src, "modmenu": {"badges": ["library"]}}}, indent=1))   # Mod Menu hides libraries
    return b.getvalue()


def install_mods(game, jar, fabric=(), forge=(), work=None, jdk=None, tools_dir=None, asm_libs=None):
    """Put the bridge jar, Fabric mods and Forge mods into <game>/mods and <game>/forgemods (rewrites both folders'
    jars), extract jar-in-jar libraries to forgemods/.nested and write the generated alloy-modmixins.jar.
    Forge-vs-Fabric @Redirect collisions on the same call are resolved on the Forge copies (resolve_redirect_collisions)."""
    work = Path(work) if work else OUT
    jdk = Path(jdk) if jdk else JDK
    game = Path(game)
    mods, fmods = game / "mods", game / "forgemods"
    nested = fmods / ".nested"
    for d in (mods, fmods, nested):
        d.mkdir(parents=True, exist_ok=True)
        for old in d.glob("*.jar"):
            old.unlink()
    shutil.copy(jar, mods / Path(jar).name)
    inject = []
    for f in fabric:
        shutil.copy(f, mods / Path(f).name)
        it = Path(str(f) + ".inject.txt")
        if it.exists():
            inject += [l for l in it.read_text(encoding="utf-8").splitlines() if l.strip()]
    (game / "alloy-inject.txt").write_text(chr(10).join(sorted(set(inject))) + (chr(10) if inject else ""), encoding="utf-8")
    cfgs, found = [], []
    for m in forge:
        copy_forge_jar(m, fmods / Path(m).name)
        data = Path(m).read_bytes()
        cfgs += manifest_mixins(data)
        nested_jars(data, found)
    seen = set()
    for name, b in found:
        # Forge mods bundle MixinExtras for Forge; Fabric Loader already provides it (same config names would clash)
        if name.lower().startswith("mixinextras"):
            continue
        if name not in seen:
            seen.add(name)
            (nested / name).write_bytes(b)
            cfgs += manifest_mixins(b)
    resolve_redirect_collisions(game, jdk, tools_dir, asm_libs)
    cfgs = drop_twin_configs(cfgs, mods, game)
    shim = work / "shim"
    shim.mkdir(parents=True, exist_ok=True)
    # first config: starts FML during Mixin startup (alloy.EarlyInit), before any Forge mod's mixin plugin runs
    (shim / "alloy_early.mixins.json").write_text(json.dumps({
        "required": True, "minVersion": "0.8", "package": "alloy.early", "plugin": "alloy.EarlyInit",
        "compatibilityLevel": "JAVA_21", "mixins": []}, indent=1))
    stubs = forge_stubs(forge, fabric)
    shutil.rmtree(shim / "META-INF", ignore_errors=True)
    for mid, ver, src in stubs:
        (shim / "META-INF" / "jars").mkdir(parents=True, exist_ok=True)
        (shim / "META-INF" / "jars" / f"alloy-stub-{mid}.jar").write_bytes(stub_jar(mid, ver, src))
    if stubs:
        print("forge stubs:", stubs)
    (shim / "fabric.mod.json").write_text(json.dumps({
        "schemaVersion": 1, "id": "alloy_modmixins", "version": "0.0.1",
        "name": "Alloy: Forge mod mixin configs", "environment": "*",
        "description": "Generated. Declares the mixin configs of the installed Forge mods so Fabric's Mixin loads them at startup.",
        "mixins": ["alloy_early.mixins.json"] + sorted(set(cfgs)), "depends": {"alloyloader": "*", "fabricloader": ">=0.12.0"},
        **({"jars": [{"file": f"META-INF/jars/alloy-stub-{mid}.jar"} for mid, _, _ in stubs]} if stubs else {})}, indent=1))
    subprocess.check_call([str(plat.tool(jdk, "jar")), "cf", str(mods / "alloy-modmixins.jar"), "-C", str(shim), "."])
    print("mixin configs declared:", sorted(set(cfgs)))
    print("fabric mods:", sorted(p.name for p in mods.glob("*.jar")))
    print("forge mods:", sorted(p.name for p in fmods.glob("*.jar")), "nested:", sorted(seen))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--game", default=str(launch.SERVER_DIR))
    ap.add_argument("--fabric", nargs="*", default=[])
    ap.add_argument("forge", nargs="*")
    ap.add_argument("--work", default=None, help="build dir (default: bridge/build/<game dir name>, so lanes for different game dirs never share one)")
    a = ap.parse_args()
    work = Path(a.work) if a.work else OUT / Path(a.game).resolve().name
    jar = compile_bridge(out=work)
    install_mods(a.game, jar, a.fabric, a.forge, work=work)


if __name__ == "__main__":
    main()

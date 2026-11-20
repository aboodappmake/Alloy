"""Build the Alloy API (no Gradle: plain javac + jar).

  build/alloy-api.jar               the API mods compile against (Java 21 bytecode; loads on plain Fabric/Forge too)
  build/samples/ffapi-sample-fabric.jar   Fabric sample mod   (put in <game>/mods)
  build/samples/ffapi-sample-forge.jar    Forge sample mod    (put in <game>/forgemods)

usage:
  python -I build_api.py                    API jar + samples
  python -I build_api.py --check-bridge DIR also copy bridge/ to DIR, add the API sources and the impl
                                            (bridge/src/alloy/api/impl/*.java.pending), and compile the whole bridge there
"""
import argparse, shutil, subprocess, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent          # alloy/api
FF = HERE.parent                                # alloy
sys.path.insert(0, str(FF))
import launch  # noqa: E402

API_VERSION = "1.0.0"
JDK = launch.JAVA.parent
OUT = HERE / "build"


def javac(release, out, cp, srcs):
    out.mkdir(parents=True, exist_ok=True)
    cmd = [str(JDK / "javac.exe"), "-J-Duser.language=en", "-Xlint:all", "-Werror", "-proc:none", "--release", str(release),
           "-encoding", "UTF-8", "-d", str(out)]
    if cp:
        cmd += ["-cp", ";".join(map(str, cp))]
    r = subprocess.run(cmd + [str(s) for s in srcs], capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"javac failed ({out.name}):\n" + r.stdout + r.stderr)


def jar(dest, classes, manifest=None):
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        dest.unlink()
    cmd = [str(JDK / "jar.exe"), "--create", "--file", str(dest)]
    if manifest:
        cmd += ["--manifest", str(manifest)]
    subprocess.check_call(cmd + ["-C", str(classes), "."])
    return dest


def build_api():
    classes = OUT / "api-classes"
    shutil.rmtree(classes, ignore_errors=True)
    javac(21, classes, [], sorted((HERE / "src").rglob("*.java")))
    fmj = (HERE / "res" / "fabric.mod.json").read_text(encoding="utf-8").replace("${version}", API_VERSION)
    (classes / "fabric.mod.json").write_text(fmj, encoding="utf-8")
    (classes / "META-INF").mkdir(exist_ok=True)
    shutil.copy(HERE.parent.parent / "LICENSE", classes / "META-INF" / "LICENSE")   # Apache-2.0
    mf = OUT / "MANIFEST.MF"
    # Automatic-Module-Name: stable module name under Forge's module layers; FMLModType GAMELIBRARY: Forge loads a
    # jar-in-jar copy as a plain library in the game layer (no mods.toml, so Alloy's isolation keeps it SHARED)
    mf.write_text("Manifest-Version: 1.0\nAutomatic-Module-Name: alloy.api\nFMLModType: GAMELIBRARY\n"
                  f"Implementation-Title: Alloy API\nImplementation-Version: {API_VERSION}\n", encoding="utf-8")
    return jar(OUT / "alloy-api.jar", classes, mf)


def pick(cp, *needles):
    out = []
    for n in needles:
        hit = [p for p in cp if n in Path(p).name]
        if not hit:
            sys.exit(f"class path entry not found: {n}")
        out.append(hit[0])
    return out


def build_samples(api_jar):
    base = launch.classpath_base()
    built = []
    for name, cp in (("fabric", pick(base, "fabric-loader-", "slf4j-api-")),
                     ("forge", pick(base, f"forge-{launch.FV}-universal", "fmlcore-", "javafmllanguage-", "eventbus-", "log4j-api-"))):
        d = HERE / "samples" / name
        classes = OUT / "samples" / f"{name}-classes"
        shutil.rmtree(classes, ignore_errors=True)
        javac(25, classes, [api_jar] + cp, sorted((d / "src").rglob("*.java")))
        shutil.copytree(d / "res", classes, dirs_exist_ok=True)
        built.append(jar(OUT / "samples" / f"ffapi-sample-{name}.jar", classes))
    return built


def check_bridge(scratch):
    """Compile the bridge + API + impl in a scratch copy, the same way build_bridge.py bundles the API into the bridge."""
    scratch = Path(scratch).resolve()
    sb = scratch / "bridge"
    shutil.rmtree(sb, ignore_errors=True)
    shutil.copytree(FF / "bridge" / "src", sb / "src")
    shutil.copytree(FF / "bridge" / "res", sb / "res")
    shutil.copytree(HERE / "src" / "alloy" / "api", sb / "src" / "alloy" / "api", dirs_exist_ok=True)
    for p in (sb / "src" / "alloy" / "api" / "impl").glob("*.java.pending"):
        p.rename(p.with_name(p.name[:-len(".pending")]))
    import build_bridge as b  # noqa: E402
    b.B = sb
    return b.compile_bridge(out=scratch / "out")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check-bridge", metavar="SCRATCH_DIR")
    ap.add_argument("--no-samples", action="store_true")
    a = ap.parse_args()
    api = build_api()
    print("built", api)
    if not a.no_samples:
        for j in build_samples(api):
            print("built", j)
    if a.check_bridge:
        print("bridge compiled:", check_bridge(a.check_bridge))


if __name__ == "__main__":
    main()

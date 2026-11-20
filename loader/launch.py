"""Class path helpers the build scripts share: where the Forge and Fabric jars of the selected Minecraft version are
(alloy_version.py layout) and the Forge runtime jars to put next to Fabric Loader.

The Forge-patched jar is produced locally by the official Forge installer (`--installServer`), never redistributed.
"""
import os, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import alloy_version as V  # noqa: E402  (ALLOY_MC picks the version, default 26.1.2)
import plat  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent.parent / "run"
FABRIC_LIBS = V.path("fabricLibs")                    # Fabric Loader + MC server libs (from the bundler); 26.1.2: srv26/libraries
FORGE = V.path("forgeServer")                         # Forge server install; 26.1.2: forge26/srv
FORGE_LIBS = FORGE / "libraries"
FV = V.FV                              # 26.1.2-64.1.6
FORGE_PATCHED = FORGE_LIBS / f"net/minecraftforge/forge/{FV}/forge-{FV}-server.jar"   # Forge-patched Minecraft (installer output)
GAME_JAR = V.path("serverGameJar")   # FORGE_PATCHED after tools/PrepGame (MethodParameters restored, ATs applied)
SERVER_DIR = V.path("serverDir")
FML_PATCHED = V.path("fmlPatched")     # Forge loader jars with Alloy's changes baked in (tools/PrepFml)
JAVA = plat.JAVA   # env ALLOY_JAVA, else JAVA_HOME, else a JDK 25 found on this machine

# Forge runtime jars we need beyond what Fabric already has. Forge's own Mixin + MixinExtras are left out:
# Fabric's Mixin (0.17.5+mixin.0.8.7) and its bundled MixinExtras serve Forge mods too.
SKIP = ("org/spongepowered/mixin/", "io/github/llamalad7/mixinextras-forge/", f"forge-{FV}-server.jar",
        "net/minecraftforge/bootstrap/", "net/minecraftforge/bootstrap-api/")


def forge_runtime(forge=None, fabric_libs=None, fml_patched=None):
    """Forge runtime jars to add on top of Fabric's libraries. Optional paths default to this module's constants
    (install.py passes its own)."""
    import zipfile
    forge = Path(forge) if forge else FORGE
    fabric_libs = Path(fabric_libs) if fabric_libs else FABRIC_LIBS
    fml_patched = Path(fml_patched) if fml_patched else FML_PATCHED
    forge_libs = forge / "libraries"
    lst = zipfile.ZipFile(forge / f"forge-{FV}-shim.jar").read("bootstrap-shim.list").decode().split()
    jars = [l for l in lst if l.endswith(".jar")]
    have = {p.relative_to(fabric_libs).as_posix().split("/")[-1] for p in fabric_libs.rglob("*.jar")}
    out = []
    for j in jars:
        if any(s in j for s in SKIP) or j.split("/")[-1] in have:
            continue
        p = fml_patched / j if (fml_patched / j).exists() else forge_libs / j
        if p.exists():
            out.append(p)
    return out


def forge_mod_jars(game=None):
    """Forge mods in <game>/forgemods (+ their extracted jar-in-jar libraries in forgemods/.nested). They must be on
    Knot's classpath from the start so Fabric's Mixin can load their mixin configs and plugins at startup."""
    g = Path(game) if game else SERVER_DIR
    return sorted((g / "forgemods").glob("*.jar")) + sorted((g / "forgemods" / ".nested").glob("*.jar"))


def classpath_base(forge=None, fabric_libs=None, fml_patched=None):
    fl = Path(fabric_libs) if fabric_libs else FABRIC_LIBS
    return [str(p) for p in sorted(fl.rglob("*.jar")) + forge_runtime(forge, fl, fml_patched)]


def classpath(game=None, **paths):
    return classpath_base(**paths) + [str(p) for p in forge_mod_jars(game)]

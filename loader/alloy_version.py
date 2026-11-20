"""The one place the Python tools read the Minecraft version from.

The version is picked by the environment variable ALLOY_MC (default 26.1.2) and described by versions/<id>.json.
Child processes inherit the variable, so a whole run (prep_game, build_bridge, client_launch, ...) uses one version.

  MC, FORGE, FV, FABRIC_API, LOADER, MIXIN, ASM, MIXINEXTRAS, JAVA_RELEASE   version values from the profile
  path(key)   a run/ folder or jar for this version, from the profile's "layout" (paths relative to the run/ folder next to the repo)

set ALLOY_LAYOUT to a json file of layout entries to give a side lane its own folders (see fabric_matrix.py --layout).

usage: set ALLOY_MC=26.2 before running any tool, or import alloy_version and read the values.
"""
import json, os
from pathlib import Path

HERE = Path(__file__).resolve().parent
RUN = HERE.parent.parent / "run"
ID = os.environ.get("ALLOY_MC", "26.1.2")
_p = HERE / "versions" / f"{ID}.json"
if not _p.exists():
    raise SystemExit(f"ALLOY_MC={ID}: no profile {_p}")
PROFILE = json.loads(_p.read_text(encoding="utf-8"))

MC = PROFILE["minecraft"]
FORGE = PROFILE["forge"]["version"]
FV = PROFILE["forge"]["full"]                      # 26.1.2-64.1.6
FABRIC_API = PROFILE["fabric"]["api"]
LOADER = PROFILE["fabric"]["loader"]
MIXIN = PROFILE["fabric"]["mixin"]
MIXINEXTRAS = PROFILE["fabric"]["mixinExtras"]
ASM = PROFILE["java"]["asm"]
JAVA_RELEASE = str(PROFILE["java"]["release"])
LAYOUT = PROFILE.get("layout", {})
# ALLOY_LAYOUT=<file.json>: a side lane (its own game dir, game jar, matrix folder, ...) overrides layout entries.
# Values are relative to run/ or absolute. Child processes inherit the variable, so every tool of the run agrees.
if os.environ.get("ALLOY_LAYOUT"):
    LAYOUT = {**LAYOUT, **json.loads(Path(os.environ["ALLOY_LAYOUT"]).read_text(encoding="utf-8"))}
LIB = {c.split(":")[1]: c.split(":")[2] for c in PROFILE["forge"]["libraries"] if c.count(":") >= 2}


def path(key):
    """run/ path for this version (layout entries are relative to the run/ folder next to the repo)."""
    v = LAYOUT.get(key)
    if v is None:
        raise KeyError(f"versions/{ID}.json has no layout.{key}")
    return RUN / v


def paths(key):
    """A list-valued layout entry (e.g. selftestForgeMods) as run/ paths."""
    return [RUN / v for v in LAYOUT.get(key, [])]


def lib(artifact):
    """Version of a Forge library (modlauncher, securemodules, unsafe, ...) from forge.libraries."""
    return LIB[artifact]

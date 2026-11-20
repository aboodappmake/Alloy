"""SHA-256 pins for every non-Mojang file the installer downloads (res/pins.txt).

Mojang files are checked with the SHA-1 from Mojang's own manifest; everything else (Forge installer, Fabric Loader,
Fabric's Mixin, ASM, Fabric API, Intermediary, MCPConfig) must have a pin here, or the installer refuses to use it.

  python -I pins.py check            every download of every bundled version has a pin (the build runs this; offline)
  python -I pins.py fetch --tmp DIR  download each missing file from its official maven into DIR, hash it, cross-check
                                     the maven's own .sha256/.sha1 file, and add the pin (--all: redo every pin)

The list of files per version is the same one Installer/V120/Modpack use (Pins.required in Util.java); the built
installer checks that again with --check-pins, so a download added in Java without a pin fails the build.
"""
import argparse, hashlib, json, sys, urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
FG = HERE.parent
PINS = HERE / "res" / "pins.txt"
FORGE = "https://maven.minecraftforge.net"
FABRIC = "https://maven.fabricmc.net"
CENTRAL = "https://repo1.maven.org/maven2"
UA = {"User-Agent": "alloy-installer-build"}


def bundled_versions():
    """the versions build_installer.py bundles: profiles whose fitted Fabric API is in res/prefit"""
    out = []
    for f in sorted((FG / "versions").glob("*.json")):
        prof = json.loads(f.read_text(encoding="utf-8"))
        if (HERE / "res" / "prefit" / str(prof.get("fabric", {}).get("apiPrefit", ""))).is_file():
            out.append(prof["id"])
    return out


def required(vid):
    """[(repos, maven path)] the installer downloads for one Minecraft version (keep in step with Pins.required)"""
    prof = json.loads((FG / "versions" / f"{vid}.json").read_text(encoding="utf-8"))
    mc, fv = prof["minecraft"], prof["forge"]["full"]
    loader, mixin, asm, api = prof["fabric"]["loader"], prof["fabric"]["mixin"], prof["java"]["asm"], prof["fabric"]["api"]
    out = [([FORGE], f"net/minecraftforge/forge/{fv}/forge-{fv}-installer.jar"),
           ([FABRIC, CENTRAL], f"net/fabricmc/fabric-loader/{loader}/fabric-loader-{loader}.jar"),
           ([FABRIC, CENTRAL], f"net/fabricmc/sponge-mixin/{mixin}/sponge-mixin-{mixin}.jar")]
    for a in ("asm", "asm-tree", "asm-commons", "asm-util", "asm-analysis"):
        out.append(([FABRIC, CENTRAL], f"org/ow2/asm/{a}/{asm}/{a}-{asm}.jar"))
    out.append(([FABRIC], f"net/fabricmc/fabric-api/fabric-api/{api}/fabric-api-{api}.jar"))
    if mc.startswith("1."):   # V120.on(): Intermediary for the name translator
        out.append(([FABRIC], f"net/fabricmc/intermediary/{mc}/intermediary-{mc}-v2.jar"))
        if prof.get("mappings", {}).get("forge") != "mojang":   # Forge 47 to 49 (not V120.NATIVE): MCPConfig too
            args = prof["forge"].get("launchArgs", [])
            mcp = args[args.index("--fml.mcpVersion") + 1] if "--fml.mcpVersion" in args else prof["forge"]["mcp"]
            out.append(([FORGE], f"de/oceanlabs/mcp/mcp_config/{mc}-{mcp}/mcp_config-{mc}-{mcp}.zip"))
    return out


def load():
    pins = {}
    if PINS.exists():
        for line in PINS.read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.startswith("#"):
                h, p = line.split(None, 1)
                pins[p.strip()] = h.lower()
    return pins


def save(pins):
    head = ("# SHA-256 of every non-Mojang file the Alloy installer downloads, by maven path (Util.Pins).\n"
            "# Written by loader/installer/pins.py fetch from the official mavens; checked by the build. Do not edit by hand.\n")
    PINS.write_text(head + "".join(f"{pins[p]}  {p}\n" for p in sorted(pins)), encoding="utf-8", newline="\n")


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url.replace("+", "%2B"), headers=UA), timeout=120) as r:
        return r.read()


def check():
    pins, missing = load(), []
    for vid in bundled_versions():
        for _, rel in required(vid):
            if rel not in pins:
                missing.append(f"{vid}: {rel}")
    if missing:
        print("files the installer downloads with no SHA-256 pin in res/pins.txt (run: python -I pins.py fetch --tmp DIR):")
        for m in missing:
            print("  " + m)
        return 1
    print(f"pins: every download of {', '.join(bundled_versions())} is pinned")
    return 0


def fetch(tmp, redo):
    pins = load()
    tmp.mkdir(parents=True, exist_ok=True)
    for vid in bundled_versions():
        for repos, rel in required(vid):
            if rel in pins and not redo:
                continue
            seen = {}
            for repo in repos:
                url = f"{repo}/{rel}"
                try:
                    b = get(url)
                except Exception as e:
                    print(f"  {url}: {e}")
                    continue
                h = hashlib.sha256(b).hexdigest()
                side = None
                for ext, algo in ((".sha256", "sha256"), (".sha1", "sha1")):
                    try:
                        side = (algo, get(url + ext).decode().split()[0].lower())
                        break
                    except Exception:
                        pass
                if side and hashlib.new(side[0], b).hexdigest() != side[1]:
                    sys.exit(f"{url}: does not match the maven's own {side[0]} file, not pinned")
                (tmp / Path(rel).name).write_bytes(b)
                seen[repo] = h
                print(f"  {h}  {url}  ({len(b)} bytes, maven {side[0] if side else 'no'} file matches)")
            if not seen:
                sys.exit(f"{rel}: no maven served it")
            if len(set(seen.values())) != 1:
                sys.exit(f"{rel}: mirrors disagree {seen}")
            pins[rel] = next(iter(seen.values()))
    save(pins)
    return check()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["check", "fetch"])
    ap.add_argument("--tmp", help="fetch: folder for the downloaded files")
    ap.add_argument("--all", action="store_true", help="fetch: download and re-pin every file, not only missing ones")
    a = ap.parse_args()
    if a.cmd == "check":
        sys.exit(check())
    if not a.tmp:
        sys.exit("fetch needs --tmp DIR")
    sys.exit(fetch(Path(a.tmp), a.all))


if __name__ == "__main__":
    main()

# Building Alloy

This is how to build the installer jar, the API jar and the Windows exe from the source in `loader/`.

## What you need

- A JDK 25 (Eclipse Temurin works). Set `ALLOY_JAVA` to its `java` (or `JAVA_HOME` to the JDK folder) if it is not in a usual place.
- Python 3.10 or newer. No packages are needed.
- For the exe: Windows 10 or 11. The exe step uses the C# compiler of .NET Framework 4, which comes with Windows.

## What is not in this repository

- Minecraft, Forge, Fabric Loader, Fabric API and ASM. The build compiles against them, so you need a local copy. The easiest way is to let a released installer make one: run `java -jar alloy-installer.jar` once into an empty test folder. It downloads the official files into `.alloy-cache` and builds the game jar in `.alloy/`.
- The fitted Fabric API jars (`loader/installer/res/prefit/`). They are binary files, so they are not kept here. Copy the `prefit/` folder out of a released `alloy-installer.jar` (it is a zip) into `loader/installer/res/prefit/`.
- The Minecraft 1.x bridges (1.20.x and 1.21.x). They are built against Forge's own game jars by a separate developer tool that is not part of this repository. A build from this source covers the 26.x versions; to bundle 1.x versions too, pass their prebuilt bridge folders with `--work120`, `--work1204` and `--work-for MC=DIR`.

## Build the installer

From `loader/installer`:

```
python -I build_installer.py --cache TEST/.alloy-cache --game-jar TEST/.alloy/hybrid_game.jar --versions 26.1.2,26.2,26.3
```

`TEST` is the test folder from above. This makes, next to `build_installer.py`:

- `alloy-installer.jar`: the installer, with the bridge, the name translator and the fitted Fabric API inside
- `alloy-agent.jar` and `alloy-agent-full.jar`: the Java agent for a plain Fabric instance
- `alloy-launch.jar`: the small launch wrapper for the launcher format

At the end the build runs the new jar with `--check-pins`. Every download the installer makes must have a SHA-256 pin in `res/pins.txt`; `python -I pins.py check` shows what is missing.

## Build the API jar

From `loader/api`:

```
python -I build_api.py --no-samples
```

This makes `build/alloy-api.jar`, the jar mods compile against.

## Build the exe

From `loader/installer`, on Windows:

```
python -I build_exe.py --jar alloy-installer.jar --version 0.5.4 --out dist
```

It trims a Java 25 runtime with jlink, packs it with the installer using jpackage, and wraps it all in one exe (`exe-src/Boot.cs`). Pass `--jdk` if your JDK 25 is not found. The exe is not code-signed.

## Other Minecraft versions

The version profiles are in `loader/versions/`. Set `ALLOY_MC` (for example `ALLOY_MC=26.2`) to make the build scripts use another version as the default one.

> **Public beta.** Alloy is new and still being tested. Bugs are expected. If something breaks, please [open an issue](https://github.com/aboodappmake/Alloy/issues/new/choose).

# Alloy

Alloy is a Minecraft mod loader that runs Forge mods and Fabric mods together in one game.

Fabric mods go in `mods/`. Forge mods go in `forgemods/` (or in `mods/`, Alloy finds them there too). Both kinds of mods share one world, one set of blocks and items, and one multiplayer connection.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT. Alloy is also not made by or affiliated with the Forge project or FabricMC.

## What Alloy is

- A loader that starts the real Fabric Loader and, inside the same game, the real Forge mod loader (FML). Forge mods run on Forge's own code. Fabric mods run on Fabric's own code.
- An installer (`alloy-installer.jar`) that downloads Minecraft and Forge from their official servers and builds the game on your PC.
- A pre-launch check (Mod Doctor) that reads your mods and tells you in plain words what will probably break.
- Crash recovery: when a start crashed, the next start names the mod and offers to start without it.
- A small API (`alloy-api.jar`) for mod authors who want a Forge mod and a Fabric mod to talk to each other.

## What Alloy is not

- It is not a translator. It does not turn Forge mods into Fabric mods or the other way round.
- It does not run NeoForge mods. Many big mods (Create, Mekanism, AE2, Farmer's Delight, Quark) only have NeoForge builds for 26.1.2, so you can't use them with Alloy.
- It does not make every mod pair work. If two mods crash together on plain Forge, they crash together on Alloy too.
- It is not a launcher. You start the game with Prism Launcher (or MultiMC) or the official Minecraft Launcher. Only dedicated servers get a start script.
- Its isolation between Forge and Fabric mods is not a security feature. All mods in one game trust each other, like on plain Forge or Fabric.

## Supported versions

Every version uses Fabric Loader 0.20.1.

| Minecraft | Forge | Java for the game | Status |
|---|---|---|---|
| 1.20.1 | 47.4.29 | 17 or newer | Main version. Client and dedicated server. Most of our testing happens here. |
| 26.1.2 | 64.1.6 | 25 | Stable. Client and dedicated server. The installer's default. |
| 26.2 | 65.1.6 | 25 | Stable. Client and dedicated server. Tested less than 26.1.2; the server only started without a world. |
| 1.20.4 | 49.2.13 | 17 or newer | Experimental. Client and dedicated server (server started up to the EULA prompt only, new in 0.5.1). |
| 26.3 | 66.1.8 | 25 | Experimental. Client and dedicated server (server only started without a world). |
| 1.20.2 | 48.1.0 | 17 or newer | Experimental. Client and dedicated server (server started up to the EULA prompt only). Sodium and Iris work since 0.5.1; Indium, Sodium Extra and Reese's Sodium Options checked in 0.5.2. |
| 1.21.1 | 52.1.21 | 21 | Experimental. Client and dedicated server (server started up to the EULA prompt only). Fabric content mods, Sodium and Iris work. |
| 1.21.4 | 54.1.22 | 21 | Experimental. Client and dedicated server (server started up to the EULA prompt only). Fabric content mods, Sodium and Iris work since 0.5. |
| 1.21.8 | 58.1.26 | 21 | Experimental. Client and dedicated server (server started up to the EULA prompt only). Fabric content mods, Sodium and Iris work since 0.5. |
| 1.21.11 | 61.2.5 | 21 | Experimental. Client and dedicated server (server started up to the EULA prompt only). Fabric content mods, Sodium and Iris work since 0.5. |

Pick the version in the installer window, or use `--mc` on the command line (for example `--mc 1.20.1`). Experimental means the version reaches a world with Fabric API and Forge mods in our tests, but only a few mods were tried. Please report what breaks.

What works where (Sodium, Iris, Embeddium, LAN, servers, content mods), and how sure we are of each, is in the tables in `COMPATIBILITY.md`.

## How it works, short version

Alloy starts Fabric Loader first. A small bridge then starts Forge's mod loader inside the same game.

Forge changes a lot of Minecraft's code, and some Fabric mods expect the original code. So when you add a Fabric mod, Alloy "fits" it on the next start: it checks every place the mod hooks into Minecraft and adjusts what it can. If a hook can't work next to Forge, Alloy turns that one hook off so the game still starts. Fabric API is fitted the same way. You still download Fabric API yourself and put it in `mods/`. If it is the version listed for your Minecraft in `CHANGELOG.md` (for example 0.92.12 on 1.20.1), Alloy swaps in a fitted copy that ships with the installer. Other versions are fitted on your PC.

By default each side only sees its own mods, so a Forge mod can't grab a Fabric library by accident, and the other way round. We call this isolation.

## Getting started

1. Install Java 25 (for example from adoptium.net). The installer runs on Java 25, and so do Minecraft 26.x. Minecraft 1.21.x needs Java 21 and 1.20.x needs Java 17 or newer (we test 1.20.x on 21), so install that too if you play those.
2. Download `Alloy-Installer-0.5.4.exe` (or `alloy-installer.jar`) from the [Releases page](https://github.com/aboodappmake/Alloy/releases) and check it against `SHA256SUMS.txt`.
3. Double-click it. On the Client tab, pick the Minecraft version and a folder, then press "Export Prism / MultiMC instance". (Or on the command line: `java -jar alloy-installer.jar --side client --mc 1.20.1 --dir Alloy120`.)
4. In Prism Launcher, choose Add Instance > Import and pick the zip in the `prism/` folder there (for example `prism/Alloy 1.20.1.zip`).
5. Put Fabric mods and Fabric API in the instance's `minecraft/mods/`. Put Forge mods in `minecraft/forgemods/`.
6. Start the instance. The first start takes a few minutes.

The full guide, with the official launcher, servers and the command line, is in `INSTALL.md`.

### Alloy in a plain Fabric instance (experimental, new in 0.5.1)

If your launcher makes Fabric instances (Modrinth App, ATLauncher, CurseForge app), you can use Alloy there without the Prism zip: make a Fabric Loader 0.20.1 instance for the Minecraft version, put Forge and Fabric mods into its `mods/` folder, put `alloy-agent-full.jar` into the instance as `.alloy/alloy-agent.jar`, and add the Java argument `-javaagent:.alloy/alloy-agent.jar` in the instance settings. The first start installs Alloy into the instance (a few minutes, needs internet). This is new and was not tried in a real launcher yet. Steps and limits are in `INSTALL.md` ("Alloy in a plain Fabric instance").

If the game does not start, start it again. When the crash points to one mod, Alloy shows a window that offers to start without it (the mod is moved aside, not deleted). You can also open `alloy-report.txt` in the game folder: it says in plain words which mod caused the problem and what to try. If you need help, press "Make crash report" in the installer window: it makes one zip with your logs and mod list, with your login and personal data removed.

Before Alloy opens a world for the first time, or after your mods changed, it makes a backup of that world in `alloy-backups/` in your game folder. If a world needs a mod you removed, Alloy tells you before it loads the world.

## What we tested

Every mod that failed in our tests was also started on plain Forge (or plain Fabric), so we know whose fault it was. The full per-mod list is in `COMPATIBILITY.md`.

For beta 0.5.4:

- 1.20.1 installed with the Windows exe on a PC path with no Java: the game got Java 21 from the PC, not the exe's own Java 25, and reached a world.
- 1.21.1, 26.1.2 and the 1.20.1 server installed fresh again: the 1.21.1 content mod test passed, Alloy's self-test passed in a 26.1.2 world, and the server reached the EULA prompt.

For beta 0.5.3:

- All 10 versions were installed fresh from the release installer again and reached a world (or passed Alloy's self-test in a world) with Forge and Fabric mods, plus a LAN pair on 1.20.4, both servers up to the EULA prompt and a plain Fabric instance on 26.1.2.
- The official launcher profile on 1.20.1 from a test Minecraft folder, started with the launcher's own command line and Java 17: world reached, and a Forge mod added afterwards was picked up on the next start with no manual step.
- Fabulously Optimized 5.4.1 imports and reaches the title screen (after switching off Controlify, which crashes next to Zoomify).

For beta 0.5.2:

- All 10 versions were installed fresh from the release installer again and reached a world (or passed Alloy's self-test in a world) with Forge and Fabric mods, plus a LAN pair on 1.20.4, both servers up to the EULA prompt and a plain Fabric instance with `alloy-agent-full.jar` on 26.1.2.
- Fabric API's use entity callback, attack callback, `AFTER_TRANSLUCENT` and the screen key, render and tick events with a test mod in a world on 1.20.1, 1.20.2, 1.20.4 and 1.21.1.
- A set of 42 high-risk Fabric mods on 1.20.1 with Axiom, FrozenLib and Wilder Wild: world reached, no Forge coremod errors.
- On 1.20.2 Fabric Sodium with Iris, Indium, Sodium Extra and Reese's Sodium Options: every Video Settings page opened and drawn.
- The 0.5 regression set for 1.21.4 without Balm (22 Fabric and 11 Forge mods) reaches a world.

For beta 0.5.1:

- All 10 versions were installed fresh from the release installer again and reached a world (or passed Alloy's self-test in a world) with Forge and Fabric mods, including Axiom on 1.20.1 and Sodium with Iris on 1.20.2.
- Fabric Sodium 0.5.5 and Iris 1.6.14 with the Complementary Reimagined shader pack on 1.20.2, compared view by view with the same world without Sodium.
- The 1.20.4 dedicated server up to the EULA prompt, and Fabric's entity callbacks on 1.20.1, 1.20.2 and 1.20.4 in a world.
- The 0.5 regression sets that had not reached a world: 1.20.4, 26.2 and 26.3 reach a world now, 1.20.1 with Axiom too. 1.21.4 stops at a question vanilla asks because of Balm, the same on plain Forge.
- A LAN pair on 1.20.4 again (20 of 20 checks).
- Alloy inside a fresh plain Fabric instance with `alloy-agent-full.jar` on 26.1.2 and 1.20.1, started the way third party launchers start an instance (not in a real launcher).

For beta 0.5:

- All 10 versions were installed fresh from the release installer and reached a world (or passed Alloy's self-test in a world) with Forge and Fabric mods together. The 0.5 regression run with popular mods on every version is in `KNOWN-ISSUES.md`.
- LAN multiplayer passed on 1.20.1, 1.20.2, 1.20.4, 1.21.1, 1.21.4, 1.21.8 and 1.21.11: two clients, Forge and Fabric blocks placed both ways with the same ids, a Fabric network message, chat, leave and join again.
- Fabric Sodium and Iris with the Complementary Reimagined shader pack on 1.21.1, 1.21.4, 1.21.8 and 1.21.11, in a world with Forge blocks that use connected textures, Forge plants and fluids, and block entities, compared view by view with the same world without Sodium.
- Fabric content mods (Macaw's Bridges and Macaw's Fences) on 1.21.4, 1.21.8 and 1.21.11: items in their tabs, blocks placed, same ids after the world was reopened, 44 to 45 Fabric API checks.
- Crash recovery on a 1.20.1 client (switch off, start, restore; and a swap to the other loader's build from a pack) and on a 1.20.1 dedicated server up to the EULA prompt.
- Config screens on 1.20.1, clicked through by a test driver: Forge and Fabric config screens, screens that crash on purpose (also while they build themselves, from Forge's own list), and a mod with only a config file. On 26.1.2, 26.2 and 26.3 the new config editor for Forge mods: values edited, saved, and the same after a restart.
- LuckPerms with Fabric permission checks on 1.20.1, 1.20.4 and 1.21.1, in singleplayer worlds with a test copy of LuckPerms changed to start there.
- On a Linux ARM test machine: the installer and dedicated server starts without a world (mods loaded, no world opened) for every version that has a server, plus the offline mixin check.

The 0.5 regression run and the final check of the release build are in `KNOWN-ISSUES.md` and `COMPATIBILITY.md`.

Minecraft 26.1.2, full test (2026-11-12):

| Group | Tested | Works | Also fails on plain Forge, or our test can't handle it | Fails only on Alloy |
|---|---|---|---|---|
| Forge mods, dedicated server | 355 | 338 | 17 | 0 |
| Forge mods, client | 230 | 221 | 9 | 0 |
| Fabric mods next to Forge mods | 149 | 147 | 2 | 0 |

The 2 Fabric mods that did not pass and what we know about them are in `KNOWN-ISSUES.md`.

On the server, "works" means the mod starts and Alloy loads exactly the mods a real Forge server loads. On the client our test joins a world and plays a short script: break a block, place a chest, use an item, take damage, die. That is a smoke test. It does not try every feature of every mod.

We also ran a big pack with 162 Forge mods and 37 Fabric mods (Sodium, Iris, Lithium, JEI, REI and others) for 20 minutes. Nothing crashed, and memory stayed flat at about 2.1 GB. A bigger pack with 157 Forge mods and 34 Fabric mods reaches a world too.

Minecraft 26.2: 28 of 30 popular Forge mods passed the client test (the other 2 fail on plain Forge too), and 12 of 12 Fabric mods passed.

Minecraft 1.20.1: 63 of 63 Fabric mods reach a world next to Forge mods. 27 of 29 Forge client test sets reach a world and play the test script; the 2 that did not were Embeddium and Oculus, which work now. Two Connector packs from Modrinth (Zombie Invade 100 Days and Forgeulously Optimized) import with the installer and run 5 minutes in a world without a crash. Embeddium and Oculus with a shader pack were checked with screenshots against plain Forge, and Sodium and Iris with shaders reach a world too. Since 0.5, Fabric Sodium Extra runs on Embeddium with its own settings tab.

## What we have not tested

- A real launch from the official Minecraft Launcher.
- A person clicking through the installer window.
- The game client on Linux or macOS. The installer and servers ran on a Linux test machine, but nobody has played Alloy on Linux or a Mac.
- Long survival play. Our longest session was 20 minutes. Alloy backs up a world before it first opens it, but keep your own backups too.
- Alloy inside a plain Fabric instance in a real launcher (Modrinth App, ATLauncher, CurseForge app). We only tested it with our own tool that starts an instance the way those launchers do.
- A 1.20.x or 1.21.x dedicated server with players on it. Those servers were started up to the EULA prompt only. Multiplayer on those versions is tested over LAN.

## The other files

| File | What's in it |
|---|---|
| `INSTALL.md` | Prism, MultiMC, the official launcher, servers, the command line, crash recovery, updating |
| `FAQ.md` | Questions players ask a lot, crashes, config screens, permissions, Sodium or Embeddium, bug reports, performance |
| `PACK-MAKERS.md` | Pack layout, `mods/` and `forgemods/`, lock files, Mod Doctor in CI, the Alloy API, the launcher format |
| `KNOWN-ISSUES.md` | What is broken or untested right now, from the latest test runs |
| `COMPATIBILITY.md` | What works on each version, and every tested mod with its result |
| `LICENSE`, `NOTICE.md` | Apache-2.0 license and credits |
| `loader/`, `BUILDING.md` | Source code of the installer, the loader bridge and the API, and how to build them |
| `alloy-agent-full.jar` | Experimental: Alloy for a plain Fabric instance of another launcher (see `INSTALL.md`) |

## What Alloy downloads

Alloy doesn't ship any Minecraft, Forge or Fabric Loader code. The installer downloads the official files from Mojang, the Forge maven, the Fabric maven and Maven Central, and builds the game on your PC. It includes one changed library: a fitted copy of Fabric API (Apache-2.0, see `NOTICE.md`).

Alloy doesn't send anything about you anywhere. Other network use: an optional lock file check for pack makers that looks up your mod files' hashes on Modrinth, only when you run it, and a pack import that downloads from Modrinth: the pack's own files, Mod Menu when the pack needs it, and on 1.20.x the Forge build of a few mods that have both builds (see "Modpacks" in `INSTALL.md`).

## Credits

Alloy runs on the work of the Forge project and FabricMC. If Alloy is useful to you, please support them too.

Source code is in this repository (`loader/`, see `BUILDING.md`).

Alloy is licensed under Apache-2.0. See `LICENSE` and `NOTICE.md`.

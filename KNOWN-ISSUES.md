# Known issues

What we know is broken, unfinished or untested. Updated for beta 0.5.4.

If you hit something that is not on this list, please report it (see "How do I report a bug?" in `FAQ.md`).

## Final check of the 0.5.4 release build

A shorter check than for 0.5.3, since only the installer's Java choice and the crash advice changed. Fresh installs from the 0.5.4 release build: 1.20.1 with Forge and Fabric mods (Axiom, JEI, Waystones, Balm) installed with `Alloy-Installer-0.5.4.exe` on a PC path with no Java on it. The installer found Java 21 for the game (not its own Java 25), wrote it into the start script, and the game reached a world on it with Alloy's report OK. Also fresh installs of 1.21.1 (the 1.21.x content mod test passed, fails 0), 26.1.2 (Alloy's self-test passed in a world, report OK) and the 1.20.1 server (up to the EULA prompt). With no Java anywhere the exe looks, it says in one line to install Java 21. The Controlify crash from the Fabulously Optimized run of 0.5.3 is now read as a mod to switch off; we checked that with the real crash report, not with a new run of the pack.

## Final check of the 0.5.3 release build

The 0.5.3 release installer was used for a fresh install of every version, one at a time, with Forge and Fabric mods, the same sets as in 0.5.2: 1.20.1 (creative tabs and block and item colors of both loaders, and a new world with Axiom, JEI, Waystones and Balm), 1.20.2 with Fabric Sodium 0.5.5, Iris 1.6.14, Indium 1.0.28 and Lithium, 1.20.4 in a world, the 1.21.x content mod test on 1.21.1, 1.21.4, 1.21.8 and 1.21.11, Alloy's self-test on 26.1.2, 26.2 and 26.3, a LAN pair on 1.20.4, the 1.20.1 and 1.20.4 servers up to the EULA prompt, and one fresh plain Fabric instance on 26.1.2 started with `alloy-agent-full.jar`. New for 0.5.3: the official launcher profile on 1.20.1, installed with `--mc-dir` into a test Minecraft folder and started with the launcher's own command line on Mojang's Java 17 (no real launcher, no account): it reached a world, and after a Forge mod (Macaw's Roofs) was added to `forgemods/` the next start prepared the game by itself and reached the world again with the new mod. And the Fabulously Optimized 5.4.1 pack: it imports, crashes on the first start on Zoomify with Controlify (a known pair), and after `--disable-mod . controlify` reaches the title screen. Every other check passed with Alloy's report OK, and the 1.20.1 report shows the new shorter list of switched off parts and Alloy 0.5.3-beta in the mod list.

## Follow-up runs for 0.5.2

For 0.5.2 we ran the sets that 0.5.1 had left open, and closed the Fabric API gaps that were still listed for 1.20.x:

| Minecraft | Mod set | Test level | Result | Alloy bugs found / fixed |
|---|---|---|---|---|
| 1.20.1 | 42 Fabric (high-risk, with Axiom, FrozenLib and Wilder Wild, without Zoomify and Controlify) | in game | world reached, no Forge coremod error after the access widener fix | 1 / 1 |
| 1.20.2 | 21 Fabric (with Sodium 0.5.5, Iris 1.6.14, Indium 1.0.28, Sodium Extra 0.5.3, Reese's Sodium Options 1.7.0) + 12 Forge | in game | world reached, every Video Settings page opens and draws | 0 / 0 |
| 1.21.4 | 22 Fabric + 11 Forge (the 0.5 set without Balm) | in game | world reached | 0 / 0 |
| 1.20.1, 1.20.2, 1.20.4, 1.21.1 | Fabric API test mod | in game | use entity callback, attack callback, AFTER_TRANSLUCENT, screen key, render and tick events all fire the expected number of times | 2 / 2 (client use entity callback on 1.20.x, render and screen events on 1.20.2 and 1.20.4) |

### Final check of the 0.5.2 release build

The 0.5.2 release installer was used for a fresh install of every version, one at a time, with Forge and Fabric mods: 1.20.1 (creative tabs and block and item colors of both loaders, and a new world with Axiom, JEI, Waystones and Balm), 1.20.2 with Fabric Sodium 0.5.5, Iris 1.6.14, Indium 1.0.28 and Lithium, 1.20.4 with Forge Config API Port (Fabric) next to Forge content mods, the 1.21.x content mod test on 1.21.1, 1.21.4, 1.21.8 and 1.21.11, Alloy's self-test on 26.1.2, 26.2 and 26.3, a LAN pair on 1.20.4, the 1.20.1 and 1.20.4 servers up to the EULA prompt, and one fresh plain Fabric instance on 26.1.2 started with `alloy-agent-full.jar`. Every one passed: worlds reached with Alloy's report OK, Axiom's welcome screen in the world with its stair access widener line dropped and no Forge coremod error, Sodium's compat loaded with Indium and Iris, 45 Fabric API checks on 1.21.1, 1.21.4 and 1.21.8 (44 on 1.21.11), the same self-test results as 0.5.1, 20 of 20 LAN checks with the same block ids on both sides, both servers stopped at the EULA prompt with `eula=false`, and the Fabric instance installed Alloy by itself in about a minute and passed the same self-test as a normal install.

## Follow-up runs for 0.5.1

For 0.5.1 we ran the parts of the 0.5 regression run that had not reached a world, on the 0.5.1 code:

| Minecraft | Mod set | Test level | Result | Alloy bugs found / fixed |
|---|---|---|---|---|
| 1.20.1 | 43 Fabric (high-risk, with Axiom, without Zoomify and Controlify) | in game | world reached after the Axiom fix | 1 / 1 |
| 1.20.2 | Fabric Sodium 0.5.5 and Iris 1.6.14 with Forge mods | in game | world reached, test scene matched the world without Sodium | 2 / 2 (no Sodium compat, Quick Play) |
| 1.20.4 | 40 Fabric + 14 Forge | in game | world reached after the Forge Config API Port fix | 2 / 2 (login message, server use entity callback) |
| 1.21.4 | 20 Fabric + 10 Forge | in game | stops at vanilla's "Experimental Settings" question; caused by Balm alone, same on plain Forge | 0 / 0 |
| 26.2 | 20 Fabric + 10 Forge | in game | world reached | 0 / 0 |
| 26.3 | 20 Fabric + 10 Forge | in game | world reached | 0 / 0 |

### Final check of the 0.5.1 release build

The 0.5.1 release installer was used for a fresh install of every version, one at a time, with Forge and Fabric mods: 1.20.1 (creative tabs and block and item colors of both loaders, and a new world with Axiom, JEI, Waystones and Balm), 1.20.2 with Fabric Sodium 0.5.5, Iris 1.6.14 and Lithium, 1.20.4 with Forge Config API Port (Fabric) next to Forge content mods, the 1.21.x content mod test on 1.21.1, 1.21.4, 1.21.8 and 1.21.11, Alloy's self-test on 26.1.2, 26.2 and 26.3, a LAN pair on 1.20.4, the 1.20.1 and 1.20.4 servers up to the EULA prompt, and one fresh plain Fabric instance on 26.1.2 started with `alloy-agent-full.jar` (it installed Alloy by itself in about a minute and passed the same self-test as a normal install). Every one passed: worlds reached with Alloy's report OK, Axiom's welcome screen in the world, Sodium's compat loaded with Iris, 45 Fabric API checks on 1.21.1, 1.21.4 and 1.21.8 (44 on 1.21.11), the same self-test results as 0.5, 20 of 20 LAN checks with the same block ids on both sides, and both servers stopped at the EULA prompt with `eula=false`.

## Regression run for 0.5

For 0.5 we ran a sample of popular Fabric and Forge mods on every version. Test levels: **offline** (Alloy's offline mixin check applies every Fabric mod's hooks to the Forge game code), **dry boot** (the game starts and loads every mod, no world) and **in game** (a client reached a world with the whole set). The offline count is the Fabric mods with hooks; Fabric API and Alloy's own jars are not counted. "Skipped" means Alloy turns one hook of the mod off at start and the rest of the mod runs. Every Alloy bug the run found is fixed in 0.5, and the one on 1.20.2 (Sodium) in 0.5.1.

| Minecraft | Mod set | Test level | Passed / total | Alloy bugs found / fixed |
|---|---|---|---|---|
| 1.20.1 | 53 Fabric + 12 Forge (most downloaded) | offline, dry boot, in game | offline 58 / 58, dry boot passed, world reached | 8 / 8 |
| 1.20.1 | 44 Fabric (high-risk mods first) | offline, dry boot, in game | offline 44 / 44, dry boot passed; in game Zoomify with Controlify crashes (mod bug, below) | 1 / 1 |
| 1.20.2 | 20 Fabric + 10 Forge | offline, dry boot | offline 19 / 20 (Lithium: one hook skipped, that option is now off), dry boot passed | 5 / 4 |
| 1.20.4 | 40 Fabric + 14 Forge | offline, dry boot | offline 40 / 44 (4 hooks skipped), dry boot passed | 5 / 5 |
| 1.21.1 | 40 Fabric + 15 Forge | offline, dry boot, in game | offline 45 / 45, dry boot passed, world reached | 4 / 4 |
| 1.21.4 | 20 Fabric + 10 Forge | offline, dry boot | offline 22 / 22, dry boot passed | 1 / 1 |
| 1.21.8 | 20 Fabric + 10 Forge | offline, dry boot, in game | offline 22 / 22, dry boot passed, world reached | 1 / 1 |
| 1.21.11 | 20 Fabric + 10 Forge | offline, dry boot, in game | offline 21 / 21, dry boot passed, world reached | 0 / 0 |
| 26.1.2 | 149 Fabric (the big list) | offline, dry boot | offline 148 / 149 (1 is a limit of the offline check), dry boot 149 / 149 | 0 / 0 |
| 26.1.2 | 283 Forge, server | offline, dry boot | 283 / 283 both | 0 / 0 |
| 26.1.2 | 30 Forge, client | offline | 30 / 30 | 0 / 0 |
| 26.2 | 20 Fabric + 10 Forge | offline, dry boot | offline 21 / 21, dry boot passed | 0 / 0 |
| 26.3 | 20 Fabric + 10 Forge | offline, dry boot | offline 21 / 21, dry boot passed | 1 / 1 |

On 1.20.2, 1.20.4, 1.21.4, 26.2 and 26.3 the whole set did not get its in-game run: on 1.20.2 Sodium crashed (below), on 1.20.4 a file locked by our own test tools stopped the reinstall, and on the other three vanilla's own questions in our test world (experimental settings, backup before upgrade) stopped the unattended run. The fixes for those versions were then checked on the final 0.5 build with smaller sets that reached a world: Lithium on 1.20.2 (its two hopper and inventory options written off), CreativeCore on 1.20.4 (loads, world reached), Architectury on 26.3 (Alloy's self-test passed).

Every mod that did not pass:

| Mod | Minecraft | Result | What we know |
|---|---|---|---|
| Fabric Sodium 0.5.5 | 1.20.2 | Alloy gap, fixed in 0.5.1 | Crashed while building chunks ("ModelQuadFacing facing is null") because Alloy had no Sodium compat for 1.20.2. 0.5.1 has it. |
| Zoomify with Controlify | 1.20.1 | Mod bug | The two crash together. Mod Doctor warns about this pair. Each one alone works. |
| Lithium 0.12.0 | 1.20.2 | Skipped hook | Its hopper and inventory change tracking do not fit Forge 48; Alloy now turns those two options off in Lithium's config on every 1.20.x. The rest of Lithium runs. |
| Balm, No Chat Reports, Fabric API networking | 1.20.4 | Skipped hook | One hook each that the offline check skips. Balm's key binding hook gets what it needs at start (no skip in the dry boot). The other two hook a small class whose fields differ on Forge 49. The mods load. |
| ModernFix (Forge) | 1.20.2 | Skipped hook | One hook skipped. Not looked at yet. |
| Moonlight 2.9.9 "forge" file | 1.20.4 | Wrong file | That file is a NeoForge mod (NeoForge mods.toml). |
| Inventory Profiles Next 2.2.5 | 1.21.1 | Mod file | Pins a libIPN version that the libIPN file for 1.21.1 does not match. |
| libIPN (26.2 file) | 26.2 | Wrong file | The file listed for 26.2 is the 26.3 build. |

No mod in this run fails on Alloy while it works on plain Forge or plain Fabric, except Sodium on 1.20.2 (fixed in 0.5.1).

### Final check of the 0.5 release build

The release installer was used for a fresh install of every version, one at a time, with Forge and Fabric mods: 1.20.1 (creative tabs of Forge and Fabric mods on Forge's pages, block and item colors of both loaders, key bindings, the pause menu Mods button and config screens), 1.20.2, 1.20.4, the 1.21.x content mod test on 1.21.1, 1.21.4, 1.21.8 and 1.21.11, a Sodium world on 1.21.1, Alloy's self-test on 26.1.2, 26.2 and 26.3, a LAN pair on 1.20.1, and the 1.20.1 and 1.21.1 servers up to the EULA prompt. Every one passed: worlds reached with Alloy's report OK (the config screen test reports its three crashes made on purpose), 45 Fabric API checks on 1.21.1, 1.21.4 and 1.21.8 (44 on 1.21.11, which has one event less), the same self-test results as before, the LAN checks with the same block ids on both sides, and both servers stopped at the EULA prompt with `eula=false`.

## Earlier test runs (0.4.1)

These are the regression runs from 0.4.1. They test a smaller, fixed list of mods. The full per-mod list from the big test is in `COMPATIBILITY.md`.

| Minecraft | What | Tested | Passed | Not passed |
|---|---|---|---|---|
| 26.1.2 | Forge mods, dedicated server (same mod list as plain Forge) | 283 | 283 | 0 |
| 26.1.2 | Forge mods, client | 30 | 29 | 1 |
| 26.1.2 | Fabric mods next to Forge mods, newest run | 149 | 147 | 2 |
| 26.2 | Popular Forge mods, client | 30 | 28 | 2 |
| 26.2 | Fabric mods next to Forge mods | 12 | 12 | 0 |

Every mod that did not pass:

| Mod | Minecraft | Result | What we know |
|---|---|---|---|
| Cherished Worlds 16.0.1 | 26.1.2 | Fails on plain Forge too | Forge's event bus refuses a class with a single listener. Plain Forge crashes the same way. |
| Inventory Profiles Next (26.2) | 26.2 | Fails on plain Forge too | Needs libIPN 6.9.0, which only runs on a newer Forge. Fails on plain Forge too. |
| Let Me Despawn | 26.1.2 | Missing dependency | Needs Dungeon Config, which was not in our test. Install it with the mod. |
| libIPN 6.9.0 (26.2) | 26.2 | Fails on plain Forge too | This build is for 26.3 and needs a newer Forge. Fails on plain Forge too. |
| RRLS | 26.1.2 | Limit of our test | Replaces the loading screen, so our unattended test never sees the title screen. Works when a person plays. |

So far no mod in these runs fails on Alloy while it works on plain Forge or plain Fabric.

## New in 0.5.4, with limits

- **The game's Java from the Windows exe.** The exe no longer gives its own Java to the game. When the PC has no matching Java, the install finishes and says which Java to install; the start scripts then use `java` from `PATH` until you install it or set `ALLOY_JAVA`. Prism Launcher and the official Minecraft Launcher pick the game's Java themselves.
- **Controlify** (with Zoomify, in Fabulously Optimized 5.4.1 on 1.20.1) still does not start on Alloy. The crash recovery window now offers to switch it off; the rest of the pack keeps working without controller support.

## New in 0.5.3, with limits

- **The official launcher profile prepares the game by itself** (`.alloy/alloy-agent.jar`). We checked it on 1.20.1 and 1.21.1 with the launcher's exact command line and files from a test Minecraft folder, not in the real launcher (that needs an account). Profiles made by 0.5.2 or older don't have it: install again, or keep running `java -jar .alloy/alloy-installer.jar --prep .` after mod changes.
- **Fabulously Optimized 5.4.1** (1.20.1) imports again, but its first start crashes on Zoomify with Controlify. In 0.5.3 the crash recovery window did not offer to switch Controlify off for this crash (0.5.4 does). Run `java -jar .alloy/alloy-installer.jar --disable-mod . controlify` in the game folder, as the crash report says, and start again. We only checked that it reaches the title screen.

## New in 0.5.2, with limits

- **Dropped access widener lines.** To let Forge's coremod run, Alloy drops a few access widener lines from Fabric mods (on 1.20.1: Axiom 6.1.5 StairBlock base, FrozenLib 1.9.3 and Wilder Wild 3.0.7 Structure settings; each one is listed in the fit report). Axiom's Tinker tool and its data generation read that stair field directly and were not tried; they may stop with an IllegalAccessError. FrozenLib and Wilder Wild do not read the field. On the other 1.x versions the lists only hold a biome field that no mod we ran widens, so that part is covered by a unit test only.
- **Client use entity callback and screen events on 1.20.x** were checked with our own test mod (one click on a pig, one screen opened), not with a real mod that uses them. Screen mouse events and `ScreenEvents.AFTER_INIT` were not counted on 1.20.2 and 1.20.4.

## New in 0.5.1, with limits

- **Alloy in a plain Fabric instance** (`alloy-agent-full.jar`, see `INSTALL.md`) is experimental. It was tested with our own tool that starts an instance the way the Modrinth App, ATLauncher and the CurseForge app do, on 26.1.2 and 1.20.1, on Windows, with Java 25 and 21. Not tested: any real launcher (where it starts the game, how it splits the Java argument, which Java it picks, what its mod list shows after Alloy fitted the mods), 26.2, 26.3, 1.20.2, 1.20.4 and the 1.21.x versions, servers, and a first start without internet (it fails, there is nothing to download from). Only Fabric Loader 0.20.1 works in this mode.
- **Fabric Sodium on 1.20.2** was checked with Sodium 0.5.5 and Iris 1.6.14, the last builds for 1.20.2. Since 0.5.2 also with Indium 1.0.28+mc1.20.4 (it lists 1.20.2), Sodium Extra 0.5.3 and Reese's Sodium Options 1.7.0: their pages open and draw, but we did not check what each Sodium Extra option does.
- **1.20.4 dedicated server** was started up to the EULA prompt and with a dry start. No world, no players.

## Under investigation

- **FPS in big packs.** In Zombie Invade 100 Days, Alloy ran about 6 percent below Forge with Connector with shaders off and about 15 percent below with shaders on, on the same PC. Worlds load faster on Alloy. We think it is CPU work on the render thread and are looking for it.

## New in 0.5, with limits

- **Crash recovery** only offers something for crashes during startup or while a world loads (up to about 2 minutes after joining). A crash later in play gets no offer, by design. On the start right after you switched a mod off, Mod Doctor still prints its "Last start failed" line. The window was tested on Windows, with Prism's pre-launch command run by hand, not in Prism itself. The launcher profile path (26.x) and the official launcher were not tried. On macOS inside the game you get a log line instead of the window.
- **Config screens** were clicked through on 1.20.1 only. On 1.20.4 and 1.21.1 the new pause menu was checked in a world. 1.20.2, 1.21.4, 1.21.8 and 1.21.11 have the code but were not booted with it. On 26.x the pause menu route and the Alloy list are off; Forge's own list has the Config buttons there, and the new config editor was clicked through on 26.1.2, 26.2 and 26.3. The editor shows lists read only, shows only the value name for nested sections (the tooltip has the full path), offers server configs only in single player, and is not on 1.20.1 to 1.21.11. In the pause menu, Forge's hidden Mods button leaves a gap above "Save and Quit". A Forge config screen opened from Forge's own list shows one blank frame before it opens.
- **Permissions bridge** was tested on 1.20.1, 1.20.4 and 1.21.1 in singleplayer worlds, with a copy of LuckPerms Forge changed to start there (the normal jar only starts on a server). It was not tested on a dedicated server, and it is not built for 26.x. On 1.21.1, a Fabric permission check that runs while a player joins, before LuckPerms is ready for that player, gets the mod's default answer (one warning in the log).
- **Key bindings.** With Amecs installed, when two mods register a key with the same name, only one of the two keys fires. That is how Amecs handles keys. Without Amecs both fire.
- **Embeddium with Fabric Sodium add-ons (1.20.1)** works for Sodium Extra and Reese's Sodium Options only. Others are refused by Mod Doctor. The tab header of a Fabric add-on shows a fallback icon instead of the mod's logo. We checked that Sodium Extra's settings are saved, not what each of its features does with Embeddium. Embeddium's "tainted" note in the log names only Oculus. Without Embeddium installed, the log has two harmless "Error loading class: org/embeddedt/..." warnings.
- **Leftover old names in 1.21.x Forge mods** are renamed for LuckPerms 5.4.140, the only mod we found with them. Names inside strings, refmaps and access transformers are not touched.
- **Lithium hoppers on 26.2 and 26.3.** Alloy's hopper fix hooks in (the log says so), but we have not played a hopper setup with it.

## Not finished

- **Dedicated servers on 1.20.x and 1.21.x have not had players on them.** 1.20.1, 1.20.2, 1.20.4 and the 1.21.x servers install and start with Forge and Fabric mods up to the EULA prompt, and on our Linux test machine they load all mods without opening a world. We never accept the EULA in tests, so none of them has opened a world. Multiplayer on those versions is tested over LAN, which uses the same network code. Only the 26.1.2 server went through our full server test.
- **26.2 is tested less than 26.1.2.** Our 26.2 runs are 30 Forge mods and 12 Fabric mods on the client. The 26.2 and 26.3 servers only started without a world.
- **The experimental versions are tested with few mods.** 1.20.4, 26.3, 1.20.2, 1.21.1, 1.21.4, 1.21.8 and 1.21.11 each reach a world from a fresh install with Forge and Fabric mods, and pass the LAN test (not 26.3). The 1.21.x versions were also played with Fabric content mods and with Sodium and Iris. No long mod lists were run on them in a game.
- **Fabric rendering API model mods on 1.21.x.** Continuity loads and fits on 1.21.1, but its connected glass does nothing, with or without Sodium. Fabric API's model modifier hook is off on 1.21.x, and Continuity's built-in resource pack does not show up in the pack list. Probably the same on 1.21.4, 1.21.8 and 1.21.11.
- **Sodium on 1.21.x, smaller gaps.** On 1.21.8 and 1.21.11, blocks drawn outside chunks (the block breaking overlay, and single blocks that some mods draw) use the vanilla path instead of Sodium's. On 1.21.1 and 1.21.4 two of Sodium's model speed-ups are off because Forge has different methods there; that costs a little speed, nothing visible. A few small Iris hooks are off (translucent after deferred, block outline, a weather shader on 1.21.4); nothing was missing in our shots. Forge 1.21.1 has a particle render method that calls itself; Alloy fixes it only when Iris is installed, because Iris is the only caller we know.
- **Some small Fabric API features turned off on 1.20.1 have side effects.** A few of the hooks Alloy turns off in Fabric API on 1.20.1 also did other work that nothing else does now. Creative tabs and block and item colors were fixed in 0.4; biome weather, `AFTER_TRANSLUCENT`, screen events and the sleep direction check in 0.4.1; the client use entity callback on 1.20.1, 1.20.2 and 1.20.4, and `AFTER_TRANSLUCENT` and screen events on 1.20.2 and 1.20.4, in 0.5.2. Tell us if you find another one.
- **Fabric API is fitted with more parts off on the new versions.** Between 50 and 63 small pieces per version, many of them data generation and registry sync details. They are listed in `.alloy/features-off.txt` and `alloy-report.txt`. We have not checked each one in a game.
- **Not ported to 1.21.x yet:** Fabric's sound stream hook, Fabric model modifiers, and part of Fabric's multiplayer code (custom recipe ingredients over the network, registry list merge, item component sync). The LAN test with blocks passed, but a Fabric mod with custom recipe ingredients on a 1.21.x server or LAN world is the part we worry about most.
- **1.21.x content mods are new.** The registry, particle and block crashes we found are fixed and checked with Bountiful Fares (1.21.1) and two Macaw's mods (all 1.21.x). Other content mods may still find gaps.
- **1.21.1 with Balm or Waystones shows an "experimental features" screen** when you create a world, because those mods' data packs carry an old format number. Press Proceed. We have not compared this with plain Forge.
- **CurseForge packs need a manual step.** The installer imports `.mrpack` and CurseForge pack zips, but it can't download CurseForge files. It lists them, you download them by hand and run again with `--pack-mods`. No real CurseForge pack has been tested yet.
- **Not in any launcher's loader list.** The launcher format is written and checked offline, but nothing is hosted and no launcher has been asked yet. Use the Prism zip.
- **Recipe viewers only one way.** Forge JEI and REI plugins show up in Fabric JEI and REI. EMI has no 26.x build. Energy works between Forge and Fabric blocks, not for items yet.
- **Tech Reborn** starts and joins a world in our test, but we have not used its machines or played it on a dedicated server.
- **No server-to-client mod sync.** Players need the same mods as the server, and you keep them in sync by hand (see `PACK-MAKERS.md`).

## Not tested yet

- **The official Minecraft Launcher.** The window's "Install to the official Minecraft Launcher" (`--mc-dir`) adds an Alloy profile for every version, 1.20.1 included. We started 1.20.1 and 1.21.1 into a world with the launcher's own command line rules from a test launcher folder, not from the real launcher (no account). The profile runs Alloy's prepare step itself before each start (`.alloy/alloy-agent.jar`), so mod changes need no manual `--prep`; profiles made by 0.5.2 or older still do, until you install again. `--launcher-profile` (the hosted file a launcher's loader list would use) is still 26.x only; testers don't need it.
- **The installer window.** The command line is well tested. The window has not been clicked through by a person yet.
- **Prism with the current zip.** Prism was tested with a real account before the project was renamed to Alloy. Since then we check the zip with a tool that imports and starts it the way Prism does (also for 26.3 in 0.5), but not in Prism itself.
- **MultiMC** and other MultiMC-style launchers. A client install also writes `NAME (MultiMC).zip` with the Java arguments where MultiMC reads them, but we have only tried Prism.
- **The game client on Linux and macOS.** The installer and dedicated servers run on our Linux ARM test machine (mods load, no world, no EULA). Nobody has played the client on a Linux PC or a Mac yet. The fixes for those systems (Apple Silicon and ARM natives, the default Minecraft folder on each system, the Flatpak launcher on Linux, opening the window by double click, finding Java in the usual places) are checked by tests that run on Windows.
- **Long survival play.** Our longest session was 20 minutes in a big pack. Saved worlds reopen fine, also after changing mods. Alloy backs up a world before it first opens it and after your mods change (`alloy-backups/`, the newest 3 per world), but keep your own backups too.
- **Installing a new Alloy version over an old one.** Install into a new folder and move your mods, configs and worlds.

## Mods that don't get along (also on plain Forge)

- **MMC4 (1.20.1) does not reach a world: a pack bug, not Alloy.** Creating a world fails with `Unknown condition type: apotheosis:module`. The pack is missing Apotheosis (and Placebo and Apothic Attributes) but still ships Paxi data packs (`mmc_apotheosis.zip`, `mmc_spacing_tweaks.zip`) and add-ons (ApothicCombat, manaunification) that need it. Plain Forge 47.3.12 with Connector fails the same way. Fix: add Apotheosis 7.4.8 with Placebo and Apothic Attributes, or remove those data packs and add-ons. Mod Doctor reports this before launch since 0.5.

- **LuckPerms 5.4.140 (1.21.1) crashes when a player joins** on plain Forge, because it calls Minecraft by an old name. Alloy renames that call, so it works on Alloy.

- **1.21.8: some chunks are not saved when you quit.** Chunks that are still being generated and hold a trial chamber vault fail with `Failed to save chunk` and `this.level is null`. Plain Forge 58.1.26 does the same on the same world, so this is a Forge bug. The chunks are generated again the next time.

- **The Dwellers pack (1.20.1) hangs at "Preparing spawn area 98%".** The Midnight Lurker asks for the chunk that is being loaded. Plain Forge with Connector hangs in exactly the same place, so this is a bug in the pack.

- **Paladin's Furniture 1.5.1 and Shoulder Surfing Reloaded 5.1.1** can crash together at start, depending on load timing (bigger packs make it more likely). Plain Forge crashes the same way. Remove one of the two. Mod Doctor warns about this pair.

- **Balm 21.4.41 (1.21.4) and Balm 21.8.10 (1.21.8): "Worlds using Experimental Settings are not supported".** Vanilla asks this when you open a world with Balm, because Forge's mod data packs carry no known pack info and Balm ships a biome modifier. Plain Forge 54.1.22 with Balm stops at the same point. Press Proceed, or leave Balm out.

## Limits of this version

- **NeoForge mods don't work.** Alloy runs Forge and Fabric. Create, Mekanism, AE2, Farmer's Delight, Quark and many others only have NeoForge builds for 26.1.2. Embeddium for 1.20.4 and later is a NeoForge mod too.
- **No OptiFine.** Mod Doctor stops the start when it finds OptiFine or OptiFabric.
- **Fitting can turn off small Fabric features.** When a Fabric mod hooks a piece of Minecraft that Forge replaced completely, Alloy turns that one hook off so the game starts. `.alloy/fit-report.txt` lists what was turned off. Known examples: Mod Menu's mod count on the title screen, a few Lithium optimizations (on 1.20.1 its chunk ticket expiry change, on 26.3 an allocation tweak), Sound Physics' sound hook, a few hooks in Collective, Balm and Puzzles Lib, and two Architectury events (client right-click on air, block break) that may not fire.
- **Fabric API is fitted too.** A few of its parts are replaced by Alloy's own compat code, a few are only for data generation, and one is something Forge already does.
- **If a Forge mod and a Fabric mod hook the exact same Forge call,** the Forge mod wins and the Fabric hook is turned off.
- **Clients without Forge can't join servers with Forge content.** A vanilla or plain Fabric client gets Forge's own "requires Forge" message when the server has Forge mods that add blocks or items.
- **Error messages for Forge jars in `mods/`** name the copy (`forgemods/frommods_X.jar`), not the file you put in `mods/`. Crash recovery and its log lines name your own file.
- **Isolation is not a security feature.** It stops mods from using the wrong side's code by accident. All mods in one game still run with the same rights.
- **Exit code 0xC0000409 on 1.20.2 and newer after a crash.** When the game crashes, it writes the crash report first and then may end with this code: the OpenAL sound library that vanilla ships for these versions fails when the process ends with the sound device still open. Plain Minecraft does the same. A normal quit exits cleanly.
- **Pause on lost focus.** If "Pause on lost focus" is on, single player stops when the window loses focus. That is normal Minecraft, but it can look like a freeze.

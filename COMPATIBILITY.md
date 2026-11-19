# Alloy compatibility list

Updated for beta 0.5.4. The first part says what works on each Minecraft version and how sure we are. The second part is the per-mod list from our big 26.1.2 test.

## How sure we are

Every row below has an evidence level:

- **In game**: a real client reached a world with these mods and the check ran (for LAN: two clients in one world). This is the strongest level we have. It is still a test scene or a short script, not hours of play.
- **Dry boot**: the game or server started and loaded every mod, but no world was opened. For dedicated servers this means up to the EULA prompt (we never accept the EULA in tests), or a dry start on our Linux test machine.
- **Offline**: only Alloy's offline mixin check ran. It applies every mod's hooks to the game code without starting the game. It finds hooks that can't apply, not crashes in play.
- **Not tested**: we have no result. That does not mean it is broken.
- **Not available**: there is no build of that mod for this version and loader, or Alloy has no support for it yet.

"Content mods" means Fabric and Forge mods that add blocks and items, in one world.

## Per version

### 1.20.1 (Forge 47.4.29)

| Area | Result | Evidence | What we know |
|---|---|---|---|
| Client | Works | In game | Our main version. 63 of 63 Fabric mods and 27 of 29 Forge client sets reached a world (the other 2, Embeddium and Oculus, work now). Booted again for 0.5 with Sodium, Iris and Indium, and with Embeddium and Oculus. |
| Dedicated server | Starts | Dry boot | Installed and started with Forge and Fabric mods up to the EULA prompt; dry start on Linux; 147 of 147 Forge server sets passed the offline check. No players on it yet. |
| Fabric Sodium | Works | In game | Sodium 0.5.13 (also with Indium 1.0.36), with Alloy's compat jar. Test scene matched the world without Sodium. |
| Iris / Oculus | Works | In game | Iris 1.7.6 with Sodium. Forge Oculus 1.8.0 with Embeddium, checked with a shader pack against plain Forge in 0.4. |
| Embeddium | Works | In game | Forge Embeddium 0.3.31. Since 0.5, Fabric Sodium Extra 0.5.9 has its own tab in Embeddium's settings and its settings are saved. Reese's Sodium Options loads; Embeddium's screen is kept. Other Fabric Sodium add-ons are refused by Mod Doctor. |
| LAN multiplayer | Works | In game | Two-client LAN test passed (Forge and Fabric blocks both ways, same ids, Fabric network message, chat, rejoin). |
| Content mods | Works | In game | Bountiful Fares, Macaw's Bridges (Fabric) with Waystones, Balm, Macaw's Roofs (Forge) and many more. Config screens and LuckPerms permission checks also tested in game. Axiom 6.1.5 reaches a world since 0.5.1. Since 0.5.2 a set of 42 Fabric mods with Axiom, FrozenLib 1.9.3 and Wilder Wild 3.0.7 starts with no Forge coremod error (Alloy drops their conflicting access widener lines). |

### 1.20.2 (Forge 48.1.0)

| Area | Result | Evidence | What we know |
|---|---|---|---|
| Client | Works | In game | Fresh install reached a world with Fabric API, Mod Menu, Lithium, Sodium, Iris and Forge JEI (0.5.1). The 0.5 regression set (20 Fabric + 10 Forge mods) passed a dry boot. Quick Play into an existing world keeps the mods' reload listeners since 0.5.1. |
| Dedicated server | Starts | Dry boot | Started up to the EULA prompt with Forge and Fabric mods; dry start on Linux. No players on it yet. |
| Fabric Sodium | Works | In game | New in 0.5.1: Sodium 0.5.5 with Alloy's compat jar. 0.5.2: also with Indium 1.0.28+mc1.20.4, Sodium Extra 0.5.3 and Reese's Sodium Options 1.7.0 (all Video Settings pages open). Biomes O' Plenty plants, leaves and its Forge fluid, Rechiseled connected textures and block entities matched the world without Sodium. (In 0.5 it crashed while building chunks.) Indium not checked. |
| Iris / Oculus | Iris works | In game | New in 0.5.1: Iris 1.6.14 with Sodium and the Complementary Reimagined shader pack: shaded world, shadows, particles, connected textures. Oculus is not available (Forge 1.20.1 only). |
| Embeddium | Not tested | Not tested | |
| LAN multiplayer | Works | In game | Passed after two 0.5 fixes (the join hung at forge:handshake, and Fabric's disconnect event never fired). |
| Content mods | Works, few mods | In game | Macaw's Fences (Forge) and Macaw's Bridges (Fabric) placed and synced in the LAN test. |

### 1.20.4 (Forge 49.2.13)

| Area | Result | Evidence | What we know |
|---|---|---|---|
| Client | Works | In game | Booted again for 0.5 and 0.5.1 with Forge and Fabric content mods. The 0.5 regression set (40 Fabric + 14 Forge) reached a world in 0.5.1, after the Forge Config API Port login fix. |
| Dedicated server | Starts | Dry boot | New in 0.5.1. Installed and started with Waystones, Balm, Fabric API and FerriteCore up to the EULA prompt, plus a dry start. Fabric's server use entity callback fires since 0.5.1 (checked in a world). No players on it yet. |
| Fabric Sodium | Works | In game | Sodium 0.5.8 world with Forge AppleSkin and JEI, with Alloy's compat jar. |
| Iris / Oculus | Iris works | In game | Iris was played with Sodium in 0.4. Oculus is not available (Forge 1.20.1 only). |
| Embeddium | Not available | | Embeddium for 1.20.4 is a NeoForge mod. |
| LAN multiplayer | Works | In game | Two-client LAN test passed. |
| Content mods | Works | In game | Balm, Waystones, Macaw's Roofs (Forge) with Fabric API and Fabric test mods; Macaw's Fences (Forge) and Bridges (Fabric) in the LAN test. LuckPerms permission checks tested in game. |

### 1.21.1 (Forge 52.1.21)

| Area | Result | Evidence | What we know |
|---|---|---|---|
| Client | Works | In game | Booted again for 0.5. |
| Dedicated server | Starts | Dry boot | Started up to the EULA prompt with Waystones, Balm, Fabric API and Mod Menu; dry start on Linux. No players on it yet. |
| Fabric Sodium | Works | In game | Sodium 0.6.13 and 0.8.13 with Alloy's compat jar (new in 0.5): connected textures through Forge model data, Biomes O' Plenty plants and fluid, block entities. Matched the world without Sodium view by view. |
| Iris / Oculus | Iris works | In game | Iris 1.8.8 with Complementary Reimagined: shaded world, shadows, connected walls. Oculus is not available. |
| Embeddium | Not available | | No Forge build for 1.21. |
| LAN multiplayer | Works | In game | Two-client LAN test passed. |
| Content mods | Works | In game | Bountiful Fares (241 blocks, 320 items), Macaw's Bridges and Fences next to Forge mods: tabs, placing, same ids after reopening, 45 Fabric API checks. Continuity (Fabric rendering API models) loads but does nothing. LuckPerms permission checks tested in game. |

### 1.21.4 (Forge 54.1.22)

| Area | Result | Evidence | What we know |
|---|---|---|---|
| Client | Works | In game | Booted again for 0.5, 0.5.1 and 0.5.2. The 0.5 regression set without Balm (22 Fabric + 11 Forge) reaches a world (0.5.2). Balm 21.4.41 makes vanilla ask about experimental settings when a world opens; plain Forge does the same (`KNOWN-ISSUES.md`). |
| Dedicated server | Starts | Dry boot | Started up to the EULA prompt; dry start with Fabric API and Macaw's mods; dry start on Linux. No players on it yet. |
| Fabric Sodium | Works | In game | Sodium 0.6.13 with Alloy's compat jar (new in 0.5). Same test scene as 1.21.1, matched the world without Sodium. |
| Iris / Oculus | Iris works | In game | Iris 1.8.8 with Complementary Reimagined (new in 0.5). Oculus is not available. |
| Embeddium | Not available | | No Forge build for 1.21. |
| LAN multiplayer | Works | In game | Two-client LAN test passed. |
| Content mods | Works | In game | New in 0.5 (they crashed at start in 0.4.1). Macaw's Bridges and Fences: every item in its tab, same ids after reopening, 45 Fabric API checks. |

### 1.21.8 (Forge 58.1.26)

| Area | Result | Evidence | What we know |
|---|---|---|---|
| Client | Works | In game | Booted again for 0.5. |
| Dedicated server | Starts | Dry boot | Started up to the EULA prompt; dry start with Fabric API and Macaw's mods; dry start on Linux. No players on it yet. |
| Fabric Sodium | Works | In game | Sodium 0.7.3 with Alloy's compat jar (new in 0.5). Blocks drawn outside chunks use the vanilla path. |
| Iris / Oculus | Iris works | In game | Iris 1.9.6 with Complementary Reimagined (new in 0.5). Oculus is not available. |
| Embeddium | Not available | | No Forge build for 1.21. |
| LAN multiplayer | Works | In game | Two-client LAN test passed. |
| Content mods | Works | In game | New in 0.5. Macaw's Bridges and Fences: tabs, same ids after reopening, 45 Fabric API checks. Forge's own trial chamber chunk save bug is in `KNOWN-ISSUES.md`. |

### 1.21.11 (Forge 61.2.5)

| Area | Result | Evidence | What we know |
|---|---|---|---|
| Client | Works | In game | Booted again for 0.5. |
| Dedicated server | Starts | Dry boot | Started up to the EULA prompt; dry start with Fabric API and Macaw's mods; dry start on Linux. No players on it yet. |
| Fabric Sodium | Works | In game | Sodium 0.8.14 with Alloy's compat jar (new in 0.5), chests and signs visible. Sodium Extra 0.9.5's block break particle switch works. Blocks drawn outside chunks use the vanilla path. |
| Iris / Oculus | Iris works | In game | Iris 1.10.8 with Complementary Reimagined (new in 0.5). Oculus is not available. |
| Embeddium | Not available | | No Forge build for 1.21. |
| LAN multiplayer | Works | In game | Two-client LAN test passed. |
| Content mods | Works | In game | New in 0.5. Macaw's Bridges and Fences: tabs, same ids after reopening, 44 Fabric API checks (this Fabric API has one event less). |

### 26.1.2 (Forge 64.1.6)

| Area | Result | Evidence | What we know |
|---|---|---|---|
| Client | Works | In game | Our big test: 221 of 230 Forge client mods and 147 of 149 Fabric mods pass, none fails only on Alloy. Per-mod list below. |
| Dedicated server | Works | In game | Full server test: 338 of 355 Forge mods, the other 17 fail on plain Forge too. Alloy and plain Forge clients joined an Alloy server in earlier betas. Dry start on Linux for 0.5. |
| Fabric Sodium | Works | In game | Sodium is in the 149 Fabric mod list. In 0.4.1 the new mod id aliases made Sodium switch itself off; fixed in 0.5 and checked with Sodium 0.9.2 in a self-test world. |
| Iris / Oculus | Iris works | In game | Iris is in the 149 Fabric mod list. Oculus is not available. |
| Embeddium | Not available | | We know of no Forge build. |
| LAN multiplayer | Not tested | | Not in the 0.5 LAN test (that covered the 1.2x versions). |
| Content mods | Works | In game | Many Forge content mods in the client and server tests, Fabric content mods in the Fabric list. |

### 26.2 (Forge 65.1.6)

| Area | Result | Evidence | What we know |
|---|---|---|---|
| Client | Works | In game | 28 of 30 popular Forge mods (the other 2 fail on plain Forge too) and 12 of 12 Fabric mods. Self-test passed again for 0.5 and 0.5.1. The 0.5 regression set (20 Fabric + 10 Forge) reached a world in 0.5.1. |
| Dedicated server | Starts | Dry boot | Dry start on Linux; offline check of the Forge server list passed. No players on it yet. |
| Fabric Sodium | Not tested | | Not tested on 26.2 itself. The 0.5 fix for Sodium switching itself off applies here too. |
| Iris / Oculus | Not tested | | |
| Embeddium | Not available | | We know of no Forge build. |
| LAN multiplayer | Not tested | | |
| Content mods | Works, few mods | In game | Tombstone and Fusion (Forge) with Fabric mods in the self-test world; the 30 Forge mod client test. |

### 26.3 (Forge 66.1.8)

| Area | Result | Evidence | What we know |
|---|---|---|---|
| Client | Works | In game | Self-test passed on fresh installs. The 0.5 regression set (20 Fabric + 10 Forge) reached a world in 0.5.1. A native crash in about one start in four (Prism zip and Alloy's own launch arguments) is fixed in 0.5; 0 crashes in 20 starts after the fix. |
| Dedicated server | Starts | Dry boot | Dry start on Linux; offline check of the server side passed with Fabric API, Lithium and Sodium. No players on it yet. |
| Fabric Sodium | Works | In game | Sodium 0.9.3-beta.5 with Lithium 0.26.3 in a self-test world (new in 0.5). |
| Iris / Oculus | Not tested | | |
| Embeddium | Not available | | We know of no Forge build. |
| LAN multiplayer | Not tested | | |
| Content mods | Works, few mods | In game | Macaw's Windows and Fences (Forge) with Fabric API in the self-test world. No Fabric content mod tested yet. |

## Regression run for 0.5

A sample of popular Fabric and Forge mods on every version, on the 0.5 code. Offline, dry boot and in game as above; the offline count is the Fabric mods with hooks (Fabric API and Alloy's own jars not counted). "Skipped" means Alloy turns one hook of the mod off at start and the rest of the mod runs. The mods that did not pass are listed in `KNOWN-ISSUES.md`.

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

On the final build every version was installed fresh and reached a world (or passed Alloy's self-test in a world) with Forge and Fabric mods; see `KNOWN-ISSUES.md`. The follow-up runs for 0.5.1 (the sets that had not reached a world here) are in `KNOWN-ISSUES.md` too.

## Mod list, Minecraft 26.1.2

Generated 2026-11-15 by `testkit/make_compat_list.py` from the test result files. Minecraft 26.1.2, Forge 64.1.6. These lists are from 0.4.1 and were not run again for 0.5.
Mod versions are the Modrinth versions we tested. Only mods that were actually tested are listed.

### Totals

| Group | Tested | PASS | Fails on real Forge too | FAIL on Alloy only |
|---|---|---|---|---|
| Forge mods, dedicated server | 355 | 338 | 17 | 0 |
| Forge mods, client | 230 | 221 | 9 | 0 |
| Fabric mods | 149 | 147 | 2 | 0 |

What the words mean:
- **PASS (server)**: the mod boots on Alloy and Alloy lists every mod that a real Forge server lists for the same mod set.
- **PASS (client)**: the client boots, joins a fresh world, and a scripted test (break block, use item, damage, death) runs without a crash.
- **Fails on real Forge too**: the mod also fails on plain Forge in the same setup, so it is not an Alloy problem (missing dependency, mod bug, test-folder permission).
- **FAIL**: works on real Forge but not on Alloy (an Alloy bug).

### Forge server mods

| Mod (Modrinth slug) | Version | Result | Note |
|---|---|---|---|
| asteorbar | 1.5.3 | Fails on real Forge too |  |
| automodpack | 5.0.0-rc.7 | Fails on real Forge too | Crashes on real Forge too: 5.0.0-rc.7 needs a ModLauncher field Forge 64 does not have (NoSuchFieldException ... |
| biolith | 3.6.0 | Fails on real Forge too | Caused by: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException: Invalid descriptor on ... |
| biomes-o-plenty | 26.1.2.0.22 | Fails on real Forge too |  |
| crashexploitfixer | forge-2.0.0+26.1.2 | Fails on real Forge too | Caused by: org.spongepowered.asm.launch.MixinInitialisationError: Mixin config crashexploitfixer.mixins.json ... |
| e4all | 2.3.0-forge | Fails on real Forge too | Caused by 0: org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: An unexpected critical... |
| fantasy_armor | 1.3-26.1.2 | Fails on real Forge too |  |
| floral-enchantment | 1.7.0 | Fails on real Forge too | Caused by: org.spongepowered.asm.launch.MixinInitialisationError: Mixin config floralench.mixins.json specifi... |
| macaws-biomes-o-plenty | 26.1.2-1.6 | Fails on real Forge too |  |
| modonomicon | 26.1.2-2.20.1 | Fails on real Forge too |  |
| no-chat-reports | Forge-26.1-v2.19.0 | Fails on real Forge too | Caused by: org.spongepowered.asm.launch.MixinInitialisationError: Mixin config mixins/common/nochatreports.mi... |
| open-parties-and-claims | forge-26.1.2-0.33.0 | Fails on real Forge too | Caused by: java.nio.file.AccessDeniedException: .\world\serverconfig\openpartiesandclaims-server.toml.new.tmp... |
| serene-seasons | 26.1.2.0.7 | Fails on real Forge too | Crashes on real Forge too: StackOverflowError, its Biome.hasPrecipitation mixin calls itself (26.1.2.0.7). |
| stellarity | 6.0.1 | Fails on real Forge too | Caused by: java.lang.IllegalStateException: Failed to load registries due to errors |
| terralith-restoned | 1.3.1 | Fails on real Forge too |  |
| tslatentitystatus | 1.10 | Fails on real Forge too | Crashes on real Forge too: its dependency Forge Config API Port 26.1.5 fails to save the config on Windows (A... |
| vanilla-refresh | 1.4.31 | Fails on real Forge too | Caused by: net.minecraftforge.forgespi.locating.ModFileLoadingException: Mod candidate vanilla-refresh-1.4.31... |
| 2032-world-height | 1.3+mod | PASS |  |
| 3d-placeable-food | 3.1.0 | PASS |  |
| additional-enchanted-miner | 26.12.160 | PASS |  |
| additional-lanterns | 1.1.2b-forge-mc26.1 | PASS |  |
| addonslib | 26.1.2-1.1 | PASS |  |
| advanced-shulkerboxes | forge-2.0.5+26.1.2 | PASS |  |
| alexs-mobs-continued | 2.3.0+26.1.2-forge | PASS |  |
| alexs-mobs-extra-music | 1.1.1 | PASS |  |
| always-a-wither-skull | 26.1.2-3.7-fabric+forge+neo | PASS |  |
| amber | 11.7.0+26.1.2 | PASS |  |
| amplified-nether | 1.2.16 | PASS |  |
| animal-garden-owl | 1.2.8-forge-26.1.2-64.0.0 | PASS |  |
| antiportals | 26.1.2-2.2.1 | PASS |  |
| anvil-restoration | 26.1.2-2.6-fabric+forge+neo | PASS |  |
| armor-stand-arms | v2.7.1+mod | PASS |  |
| armor-stands | 3.0.4+26.1-forge | PASS |  |
| ati-structures-fabricforge | 1.4.7 | PASS |  |
| ati-structures-vanilla-edition | 1.4.7 | PASS |  |
| auto-aim | 1.4.3 | PASS |  |
| badpackets | forge-0.12.2 | PASS |  |
| balm | 26.1.2.15+forge-26.1.2 | PASS |  |
| battle-towers | 1.3.0+mod | PASS |  |
| better-beacon-placement | 26.1.2-3.7-fabric+forge+neo | PASS |  |
| better-conduit-placement | 26.1.2-3.6-fabric+forge+neo | PASS |  |
| better-respawn | forge-2.0.6+26.1.2 | PASS |  |
| better-spawner-control | 26.1.2-4.9-fabric+forge+neo | PASS |  |
| better-trees | 1.9.3+mod | PASS |  |
| better-waypoints | v2.6.2+mod | PASS |  |
| bigger-sponge-absorption-radius | 26.1.2-3.8-fabric+forge+neo | PASS |  |
| biome-spawn-point | 26.1.2-4.1-fabric+forge+neo | PASS |  |
| blooming-biosphere | 1.1.14 | PASS |  |
| bluemap | 5.29-forge | PASS |  |
| bottle-your-xp | 26.1.2-3.6-fabric+forge+neo | PASS |  |
| bottled-air | 26.1.2-2.6-fabric+forge+neo | PASS |  |
| bouncier-beds | 26.1.2-2.6-fabric+forge+neo | PASS |  |
| calcmod | 1.5.1+forge.26.1 | PASS |  |
| call-your-horse | v2.1.3+mod | PASS |  |
| campfire-spawn-and-tweaks | 26.1.2-5.0-fabric+forge+neo | PASS |  |
| carry-on | 2.10.0 | PASS |  |
| cave-spider-spawn | 26.1.2-1.5-fabric+forge+neo | PASS |  |
| cb-compressed-blocks | 3.1.0-26.3 | PASS |  |
| chimes | 2.1.2 | PASS |  |
| chunk-loaders | 1.2.9-forge-mc26.1 | PASS |  |
| chunky | 1.5.66 | PASS |  |
| chunkyborder | 1.2.67 | PASS |  |
| codxlib | 1.6.1+forge-26.1.2 | PASS |  |
| collective | 26.1.2-8.86-fabric+forge+neo | PASS |  |
| common-network | 1.0.23-26.1.2 | PASS |  |
| compost | Forge-26.1.2-3.0.3 | PASS |  |
| configurable-extra-mob-drops | 26.1.2-4.1-fabric+forge+neo | PASS |  |
| connected-glass | 1.1.14-forge-mc26.1 | PASS |  |
| continents | 1.1.14 | PASS |  |
| corail-tombstone | forge-26.1.2-9.8.4 | PASS |  |
| craft-slabs-back-into-blocks | forge-26.1.2-3 | PASS |  |
| crate-delight | 26.07.01-26.1-forge | PASS |  |
| craterlib | 26.1.2-3.1.3 | PASS |  |
| creeper-no-break-blocks | 1.0.0+forge | PASS |  |
| crying-ghasts | 26.1.2-3.7-fabric+forge+neo | PASS |  |
| crying-portals | 26.1.2-3.1-fabric+forge+neo | PASS |  |
| custom-player-models | 26.1v0.6.27b-forge | PASS |  |
| customguns | v2.3.0+mod | PASS |  |
| customizable-elytra | forge-2.2.8+26.1 | PASS |  |
| cycle-paintings | 26.1.2-5.0-fabric+forge+neo | PASS |  |
| cyclops-core | 26.1.2-1.31.4 | PASS |  |
| darksmelting | 1.2.0 | PASS |  |
| daycount | 1.6.1-Forge-mc26.1 | PASS |  |
| daycounter | 4+mod | PASS |  |
| decubed-dungeons | 1.11.1+mod | PASS |  |
| deeper_dark | 4.4.1+mod | PASS |  |
| deimos | 26.1.2-forge-2.8 | PASS |  |
| despawning-eggs-hatch | 26.1.2-4.6-fabric+forge+neo | PASS |  |
| difficulty-lock | 26.1.2-5.2-fabric+forge+neo | PASS |  |
| dismount-entity | 26.1.2-3.8-fabric+forge+neo | PASS |  |
| distanthorizonsapi | 7.2.0 | PASS |  |
| double-doors | 26.1.2-7.5-fabric+forge+neo | PASS |  |
| dragon-drops-elytra | 26.1.2-3.7-fabric+forge+neo | PASS |  |
| drop-head | 2.2 | PASS |  |
| dungeons-and-taverns | 5.2.0+mod | PASS |  |
| dungeons-and-taverns-ancient-city-overhaul | 3.3+mod | PASS |  |
| dungeons-and-taverns-desert-temple-overhaul | v2.1+mod | PASS |  |
| dungeons-and-taverns-jungle-temple-overhaul | v2.1+mod | PASS |  |
| dungeons-and-taverns-nether-fortress-overhaul | v3.1+mod | PASS |  |
| dungeons-and-taverns-ocean-monument-overhaul | v2.2+mod | PASS |  |
| dungeons-and-taverns-pillager-outpost-overhaul | v3.3+mod | PASS |  |
| dungeons-and-taverns-stronghold-overhaul | v2.4.0+mod | PASS |  |
| dungeons-and-taverns-swamp-hut-overhaul | v2.3+mod | PASS |  |
| dungeons-and-taverns-woodland-mansion-overhaul | 2.1+mod | PASS |  |
| dynamic-torches | 5.4+mod | PASS |  |
| easy-elytra-takeoff | 26.1.2-5.0-fabric+forge+neo | PASS |  |
| easy-mob-farm | 11.1.0 | PASS |  |
| easy-npc | 7.18.0 | PASS |  |
| easy-npc-config-ui | 7.18.0 | PASS |  |
| easy-npc-core | 7.18.0 | PASS |  |
| edf-remastered | 5.0.2+mod | PASS |  |
| enchanted-golden-apple-addition | 2.4 | PASS |  |
| enchanting-commands | 26.1.2-3.8-fabric+forge+neo | PASS |  |
| endercon | 3.3+mod | PASS |  |
| entangled | 1.3.21a-forge-mc26.1 | PASS |  |
| epic-structures-dungeons | 1.2.5+mod | PASS |  |
| epic-structures-villages | 2.0.0+mod | PASS |  |
| epic-structures-witch-huts | 1.3.1 | PASS |  |
| explorations | 26.1.2-1.6.5-Forge | PASS |  |
| explorify | v1.6.6 | PASS |  |
| extended-bone-meal | 26.1.2-3.8-fabric+forge+neo | PASS |  |
| extract-poison | 26.1.2-3.7-fabric+forge+neo | PASS |  |
| factory-api | 26.1.2-2.2.9-forge | PASS |  |
| fallingtree | 26.1.2-26.1.2.5 | PASS |  |
| fantasy_weapons | 1.0-26.1.2 | PASS |  |
| fire-arrows-ignite-fire | forge-26.1.2-13.5 | PASS |  |
| first-join-message | 26.1.2-3.9-fabric+forge+neo | PASS |  |
| fish-on-the-line | 26.1.2-3.6-fabric+forge+neo | PASS |  |
| fixed-anvil-repair-cost | 26.1.2-4.0-fabric+forge+neo | PASS |  |
| flower-mimics | 26.1.2-2.5-fabric+forge+neo | PASS |  |
| flower-tweaks | 4+mod | PASS |  |
| fokusapi | 4.9 | PASS |  |
| followers-teleport-too | 26.1.2-3.2-fabric+forge+neo | PASS |  |
| forge-config-api-port | 26.1.5 | PASS |  |
| formations | 1.0.4-forge-mc26.1 | PASS |  |
| formations-nether | 1.0.5b-mc1.21+ | PASS |  |
| formations-overworld | 1.0.5c-mc1.21+ | PASS |  |
| fortunate-ancient-debris | 4.3.1+mod | PASS |  |
| from-the-fog | 1.11.3 | PASS |  |
| fullbright-multiplayer | 1.4+mod | PASS |  |
| furnace-recycle | 26.1.2-2.8-fabric+forge+neo | PASS |  |
| gamingbarns-guns | V1.28.1-mod | PASS |  |
| gamingbarns-morphs | V1.15.1-mod | PASS |  |
| gardeners-dream | 2.6+mod | PASS |  |
| geckolib | 5.5.2 | PASS |  |
| geophilic | 3.7 | PASS |  |
| giant-spawn | 26.1.2-5.6-fabric+forge+neo | PASS |  |
| glitchcore | 26.1.2.0.2 | PASS |  |
| grabby-mobs | 26.1.2-2.0-fabric+forge+neo | PASS |  |
| grass-seeds | 26.1.2-3.5-fabric+forge+neo | PASS |  |
| grim-kingdoms-lost-structures-ruins | 2.0.3+mod | PASS |  |
| grim-kingdoms-lost-structures-ruins-vanilla-edition | 2.0.0+mod | PASS |  |
| grindstone-sharper-tools | 26.1.2-4.3-fabric+forge+neo | PASS |  |
| growableores | 3.8.0-26.1.2-Forge | PASS |  |
| gun-core | V1.0.14-mod | PASS |  |
| healing-campfire | 26.1.2-7.1-fabric+forge+neo | PASS |  |
| health-indicator | 4+mod | PASS |  |
| hobbit-hill-village | 0.0.5 | PASS |  |
| hoe-tweaks | 26.1.2-3.7-fabric+forge+neo | PASS |  |
| hopo-better-mineshaft | 1.3.6 | PASS |  |
| hopo-better-ruined-portals | 1.5.0 | PASS |  |
| hopo-better-underwater-ruins | 1.2.7 | PASS |  |
| husk-spawn | 26.1.2-3.9-fabric+forge+neo | PASS |  |
| iceberg | 1.4.1.2 | PASS |  |
| immersivemc | 1.6.0-beta1 | PASS |  |
| improved-pillager-outpost | 7 | PASS |  |
| incendium | 5.4.12 | PASS |  |
| infinite-trading | 26.1.2-6.0-fabric+forge+neo | PASS |  |
| inventory-management | 2.0.2+26.1-forge | PASS |  |
| inventory-totem | 26.1.2-3.6-fabric+forge+neo | PASS |  |
| invisible-frames-mod | 2.0.1+26.1-forge | PASS |  |
| item-collectors | 1.1.12a-forge-mc26.1 | PASS |  |
| iwtb | 1.3+mod | PASS |  |
| journeymap | 26.1.2-6.0.10+forge | PASS |  |
| journeymap-teams | 26.1.2-1.3.1+forge | PASS |  |
| just-mob-heads | 26.1.2-9.7-fabric+forge+neo | PASS |  |
| just-player-heads | 26.1.2-4.5-fabric+forge+neo | PASS |  |
| kafs-valentine-special | 5.3.1+26.1.2 | PASS |  |
| keep-my-soil-tilled | 26.1.2-2.7-fabric+forge+neo | PASS |  |
| ksyxis | 1.4.6 | PASS |  |
| legacy4j | 26.1.2-1.9.1-forge | PASS |  |
| lib-5555ff | v1.1.5+mod | PASS |  |
| lifesteal-mian | forge-10.0.7-26.1 | PASS |  |
| lios-overhauled-villages | 0.1.2 | PASS |  |
| liteminer | 3.4.2+26.1.2 | PASS |  |
| living-things | 26.1.2-2.3.2-Forge | PASS |  |
| luckperms | v5.5.85-forge | PASS |  |
| macaws-bridges | 3.1.2 | PASS |  |
| macaws-doors | 1.1.5 | PASS |  |
| macaws-fences-and-walls | 1.2.1 | PASS |  |
| macaws-furniture | 3.4.2 | PASS |  |
| macaws-lights-and-lamps | 1.1.5 | PASS |  |
| macaws-paintings | 1.1.0 | PASS |  |
| macaws-paths-and-pavings | 1.1.1 | PASS |  |
| macaws-roofs | 2.3.2 | PASS |  |
| macaws-stairs | 1.0.2 | PASS |  |
| macaws-trapdoors | 1.1.5 | PASS |  |
| macaws-windows | 2.4.2 | PASS |  |
| magic-vibe-decorations | 2.3.4 | PASS |  |
| manhunt_pack | 18+mod | PASS |  |
| mapfrontiers | 26.1.2-3.0.0-beta.13-forge | PASS |  |
| mastercutter | 1.6.2-mc26.1+mod | PASS |  |
| mclogs | 3.3.4 | PASS |  |
| mcwifipnp | 2.0.8 | PASS |  |
| medieval-buildings | 1.2.0 | PASS |  |
| medieval-buildings-end-edition | 1.2.1 | PASS |  |
| medieval-buildings-nether-edition | 1.2.1 | PASS |  |
| mes-moogs-end-structures | 2.1.1 | PASS |  |
| mineral-chance | 26.1.2-5.0-fabric+forge+neo | PASS |  |
| minertrack | 2.1.2.0+forge | PASS |  |
| mmv-moogs-missing-villages | 2.2.0 | PASS |  |
| mns-moogs-nether-structures | 3.1.1 | PASS |  |
| mob-heads | v4.7.2+mod | PASS |  |
| modern-guns | V1.9.2-mod | PASS |  |
| moogs-glow-up | 2.3.0-forge-26.1.2 | PASS |  |
| moogs-structure-lib | 3.4.2-forge-26.1.2 | PASS |  |
| moogs-voyager-structures | 5.1.3 | PASS |  |
| mooshroom-spawn | 26.1.2-3.9-fabric+forge+neo | PASS |  |
| mooshroom-tweaks | 26.1.2-3.7-fabric+forge+neo | PASS |  |
| more-music-discs | 26.1.2-forge-35.2 | PASS |  |
| more-zombie-villagers | 26.1.2-3.9-fabric+forge+neo | PASS |  |
| moreenchantments | 1.5+mod | PASS |  |
| move-boats | 26.1.2-3.8-fabric+forge+neo | PASS |  |
| move-minecarts | 26.1.2-4.0-fabric+forge+neo | PASS |  |
| moving-elevators | 1.4.12a-forge-mc26.1 | PASS |  |
| mss-moogs-soaring-structures | 2.2.0 | PASS |  |
| mtr-moogs-temples-reimagined | 2.0.4 | PASS |  |
| name-tag-tweaks | 26.1.2-4.2-fabric+forge+neo | PASS |  |
| naturally-charged-creepers | 26.1.2-3.7-fabric+forge+neo | PASS |  |
| nether-portal-spread | 26.1.2-8.8-fabric+forge+neo | PASS |  |
| no-animal-tempt-delay | 26.1.2-1.3-fabric+forge+neo | PASS |  |
| no-enderman-grief | v3.1.1+mod | PASS |  |
| no-feather-trample | 26.1.2-1.4-fabric+forge+neo | PASS |  |
| no-hostiles-around-campfire | 26.1.2-8.1-fabric+forge+neo | PASS |  |
| norollback | 2.2.0 | PASS |  |
| nullscape | 1.2.20 | PASS |  |
| oh-the-trees-youll-grow | 26.1.2-10.1.5-Forge | PASS |  |
| old-combat-mod | 1.2+forge.build.3 | PASS |  |
| orbital-strike-cannon-datapack | v14.5+mod | PASS |  |
| ore-harvester | 26.1.2-2.2-fabric+forge+neo | PASS |  |
| ore-vein-miner | 26.x+mod | PASS |  |
| osc-dp | 8.1+mod | PASS |  |
| overworld-piglins | 26.1.2-1.7-fabric+forge+neo | PASS |  |
| packed-up-backpacks | 1.1.2-forge-mc26.1 | PASS |  |
| paladins-furniture | 26.1.2-v1.5.1-minecraftforge | PASS |  |
| passive-shield | 26.1.2-4.0-fabric+forge+neo | PASS |  |
| pet-names | 26.1.2-3.6-fabric+forge+neo | PASS |  |
| pickup-notifications | 3.0.1+26.1-forge | PASS |  |
| ping-wheel | 1.12.1 | PASS |  |
| quick-pack | forge-1.5.1+26.1.2 | PASS |  |
| quick-right-click | 26.1.2-3.0-fabric+forge+neo | PASS |  |
| random-bone-meal-flowers | 26.1.2-5.0-fabric+forge+neo | PASS |  |
| random-shulker-colours | 26.1.2-3.5-fabric+forge+neo | PASS |  |
| random-village-names | 26.1.2-5.0-fabric+forge+neo | PASS |  |
| randomisland | 1.9.1+mod | PASS |  |
| realistic-bees | 26.1.2-5.1-fabric+forge+neo | PASS |  |
| recast | 26.1.2-3.8-fabric+forge+neo | PASS |  |
| rechiseled | 1.2.6-forge-mc26.1 | PASS |  |
| reds-more-structures | 1.1.2+mod | PASS |  |
| remove-terralith-intro-message | 1.2.2 | PASS |  |
| replanting-crops | 26.1.2-5.7-fabric+forge+neo | PASS |  |
| respawning-shulkers | 26.1.2-4.3-fabric+forge+neo | PASS |  |
| rhino | 2101.2.7-build.85+Rhino-1.21 | PASS |  |
| scaffolding-drops-nearby | 26.1.2-3.6-fabric+forge+neo | PASS |  |
| scalable-cats-force | 6.1.1-mc26.1.2-3.9.0-forge | PASS |  |
| server-side-horror | 26.1.2-forge-5.1 | PASS |  |
| serversleep | 3+mod | PASS |  |
| shulker-drops-two | 26.1.2-3.7-fabric+forge+neo | PASS |  |
| simple-datapacks | 2.9.1-forge+26.1 | PASS |  |
| simple-magnets | 1.1.12-forge-mc26.1 | PASS |  |
| simple-snowy-fix-forge-fabric | 2.2.1 | PASS |  |
| simple-voice-chat | forge-2.6.22+26.1.2 | PASS |  |
| sit-anywhere | 3.1.0+mc1.19.4-26.3 | PASS |  |
| skeleton-horse-spawn | 26.1.2-4.5-fabric+forge+neo | PASS |  |
| skinrestorer | 2.11.1+26.1-forge | PASS |  |
| skippy-pearls | v2.0+universal+Mod | PASS |  |
| skyblock-infinite | 1.1.18+mod | PASS |  |
| sleep-sooner | 26.1.2-5.2-fabric+forge+neo | PASS |  |
| smaller-nether-portals | 26.1.2-4.2-fabric+forge+neo | PASS |  |
| smartbrainlib | 2.0.3 | PASS |  |
| snowballs-freeze-mobs | 26.1.2-4.1-fabric+forge+neo | PASS |  |
| softer-hay-bales | 26.1.2-3.6-fabric+forge+neo | PASS |  |
| some-more-blocks | 26.1.0.0 | PASS |  |
| sound-physics-remastered | forge-1.5.2+26.1.2 | PASS |  |
| spark | 1.10.187-forge | PASS |  |
| spiders-produce-webs | 26.1.2-3.8-fabric+forge+neo | PASS |  |
| spiral-tower-villages | 0.0.4 | PASS |  |
| stack-refill | 26.1.2-6.2-fabric+forge+neo | PASS |  |
| stacked-blocks | 26.03.26+26.1-forge | PASS |  |
| stairsit | 2.0 | PASS |  |
| starter-kit | 26.1.2-9.0-fabric+forge+neo | PASS |  |
| starter-structure | 26.1.2-6.0-fabric+forge+neo | PASS |  |
| startinv | 1.2.0 | PASS |  |
| stellarity-lite | 3.0.5.12+mod | PASS |  |
| storage-delight | 26.07.01-26.1-forge | PASS |  |
| storagedrawers | 26.1.0.2+forge | PASS |  |
| stronger-skill-tree-for-pufferfishs-skills | 2.0.0+mod | PASS |  |
| structory | 1.3.17 | PASS |  |
| structory-towers | 1.0.17 | PASS |  |
| superflat-world-no-slimes | 26.1.2-3.7-fabric+forge+neo | PASS |  |
| supermartijn642s-config-lib | 1.1.8a-forge-mc26.1 | PASS |  |
| supermartijn642s-core-lib | 1.1.26c-forge-mc26.1 | PASS |  |
| surface-mushrooms | 26.1.2-3.8-fabric+forge+neo | PASS |  |
| swinging-lanterns | 26.1-26.1.2-2.1.0 | PASS |  |
| terrablender | 26.1.2.0.3 | PASS |  |
| theoneblock | 5.2.0+26.1.2-forge | PASS |  |
| tidal-towns | 2.0+mod | PASS |  |
| timber-strike | 4.2+mod | PASS |  |
| time-in-a-bottle-universal | 8.0.0 | PASS |  |
| too-many-paintings | 26.08.16-26.1-forge | PASS |  |
| toolbar-sounds | 1.1+mod | PASS |  |
| tough-as-nails | 21.11.0.6 | PASS |  |
| trade-cycling | forge-1.0.23+26.1.2 | PASS |  |
| transcending-trident | 26.1.2-6.0-fabric+forge+neo | PASS |  |
| transmog | forge-1.8.0+26.1 | PASS |  |
| trash-cans | 1.1.2a-forge-mc26.1 | PASS |  |
| tree-harvester | 26.1.2-11.1-fabric+forge+neo | PASS |  |
| tree-vein-miner | 26.x+mod | PASS |  |
| treetimber-treecapitator | 1.0.2 | PASS |  |
| trek | B0.6.2+mod | PASS |  |
| typewriter-daycounter | 1.2 | PASS |  |
| undead-nights | 2.2.5-Forge-mc26.1 | PASS |  |
| underground-worlds | 3.2.1-26.1 | PASS |  |
| unlimited-trading | 3.3+mod | PASS |  |
| upgrade-items | 1.3.0 | PASS |  |
| vanilla-plus-data-pack | 25.0.mod | PASS |  |
| vault-patcher | 1.5.4 | PASS |  |
| vein_miner | 26.x+mod | PASS |  |
| village-spawn-point | 26.1.2-6.1-fabric+forge+neo | PASS |  |
| villager-converting | 2.0.2+26.1-forge | PASS |  |
| villager-death-messages | 26.1.2-4.0-fabric+forge+neo | PASS |  |
| villager-in-a-bucket | 26.1.2-1.0.0 | PASS |  |
| villager-names-serilum | 26.1.2-9.0-fabric+forge+neo | PASS |  |
| vivecraft | 26.1.2-1.3.15-forge | PASS |  |
| wall-jump-txf | 26.1.2-1.3.8-forge | PASS |  |
| watermedia-binaries | 3.0.1 | PASS |  |
| weaker-spiderwebs | 26.1.2-4.0-fabric+forge+neo | PASS |  |
| weaponmaster | forge-26.1-4.4.4 | PASS |  |
| weaponsexpanded | 1.9.3 | PASS |  |
| welcome-message | 26.1.2-2.9-fabric+forge+neo | PASS |  |
| what-durability | 26.1.2-2.1-fabric+forge+neo | PASS |  |
| wider-ender-chests | 1.2.0 | PASS |  |
| wooden-shears | 26.1-4.0.1.1 | PASS |  |
| wool-tweaks | 26.1.2-4.1-fabric+forge+neo | PASS |  |
| world-editor | v2.6.1+mod | PASS |  |
| wthit | forge-19.0.1 | PASS |  |
| x-to-xray | 2026.29 | PASS |  |
| x_ray | 1.1.1 | PASS |  |
| xaeros-map-server-utils | 1.1.0+mod | PASS |  |
| xaeros-minimap | forge-26.1.2-26.6.2 | PASS |  |
| xaeros-world-map | forge-26.1.2-1.47.2 | PASS |  |
| your-items-are-safe | 26.1.2-5.1-fabric+forge+neo | PASS |  |
| zombie-horse-spawn | 26.1.2-5.6-fabric+forge+neo | PASS |  |
| zombie-proof-doors | 26.1.2-3.6-fabric+forge+neo | PASS |  |
| zombie-villagers-from-spawner | 26.1.2-4.2-fabric+forge+neo | PASS |  |

### Forge client mods

| Mod (Modrinth slug) | Version | Result | Note |
|---|---|---|---|
| automodpack | 5.0.0-rc.7 | Fails on real Forge too | Crashes on real Forge too: 5.0.0-rc.7 needs a ModLauncher field Forge 64 does not have (NoSuchFieldException ... |
| cherished-worlds | 16.0.1+26.1.2 | Fails on real Forge too | Crashes on real Forge too: EventBus 7 rejects its single-listener class (mod bug). |
| compact-storage | 26.1.16+forge-26.1.2 | Fails on real Forge too | Fails on real Forge too: 26.1.16 has an invalid version range "[[26.1-,26.2),)" in its mods.toml; Forge stops... |
| legacy4j | 26.1.2-1.9.1-forge | Fails on real Forge too | Harness artifact: Legacy4J opens its own first-launch screen, which the unattended test cannot click through;... |
| npc-studio | 1.3.9 | Fails on real Forge too | Harness artifact: the mod opens its own GameruleWarningScreen on world join, which the unattended test cannot... |
| punchy-fpa | 3.2a | Fails on real Forge too | Fails on real Forge too: pinned to exactly Forge 64.0.1. |
| serene-seasons | 26.1.2.0.7 | Fails on real Forge too | Crashes on real Forge too: StackOverflowError, its Biome.hasPrecipitation mixin calls itself (26.1.2.0.7). |
| startinv | 1.2.0 | Fails on real Forge too | Harness artifact: the mod makes new players invulnerable for their first seconds (cancels hurtServer at HEAD)... |
| watermedia-yt-plugin | 3.0.0-beta.7 | Fails on real Forge too | Fails on real Forge too: its dependency WATERMeDIA Platform Extension 3.0.0-beta.7 needs org.watermedia.api.p... |
| 3d-placeable-food | 3.1.0 | PASS |  |
| additional-enchanted-miner | 26.12.160 | PASS |  |
| additional-lanterns | 1.1.2b-forge-mc26.1 | PASS |  |
| addonslib | 26.1.2-1.1 | PASS |  |
| advancement-plaques | 1.7.1 | PASS |  |
| advancement-screenshot | 26.1.2-5.3-fabric+forge+neo | PASS |  |
| alexs-mobs-continued | 2.3.0+26.1.2-forge | PASS |  |
| all-in-one-veinminer | 1.1.8.1 | PASS |  |
| animal-garden-owl | 1.2.8-forge-26.1.2-64.0.0 | PASS |  |
| animal-garden-porcupine | 1.0.5-forge-26.1.1-63.0.0 | PASS |  |
| animal-garden-red-panda | 1.1.3-forge-26.1.2-64.0.0 | PASS |  |
| animal-garden-sea-otter | 1.1.1-forge-26.2-65.0.0 | PASS |  |
| antiblocksrechiseled | 26.1.1.1 | PASS |  |
| antiportals | 26.1.2-2.2.1 | PASS |  |
| aquarius-libs | 1.2.0-forge-26.2-65.0.0 | PASS |  |
| areas | 26.1.2-7.0-fabric+forge+neo | PASS |  |
| armor-durability-hud | 8.1.1.7 | PASS |  |
| asteorbar | 1.5.3 | PASS |  |
| attack-through-grass | forge-2.1.1-26.1 | PASS |  |
| axolotl-buckets | 2.0.0+26.1-forge | PASS |  |
| bareback-horse-riding | 26.1.2-2.5-fabric+forge+neo | PASS |  |
| beautified-chat-client | 26.1.2-4.0-fabric+forge+neo | PASS |  |
| better-advancements | 0.6.0.76 | PASS |  |
| better-library | 2.1.3 | PASS |  |
| biomes-o-plenty | 26.1.2.0.22 | PASS |  |
| bisect-mod | 2.5.3 | PASS |  |
| boat-item-view | 26.1-0.0.9-forge | PASS |  |
| boatview360 | v1.0.6-mc26.1.2-forge | PASS |  |
| build-guide | 0.4.9-26.1.2 | PASS |  |
| carry-on | 2.10.0 | PASS |  |
| cb-compressed-blocks | 3.1.0-26.3 | PASS |  |
| chimes | 2.1.2 | PASS |  |
| chunk-loaders | 1.2.9-forge-mc26.1 | PASS |  |
| classic-minecraft-icon | v1.1.10-mc26.1.2 | PASS |  |
| classic-pipes | 1.1.7 | PASS |  |
| colorize | 26.1-1.13.1 | PASS |  |
| common-network | 1.0.23-26.1.2 | PASS |  |
| compost | Forge-26.1.2-3.0.3 | PASS |  |
| connected-glass | 1.1.14-forge-mc26.1 | PASS |  |
| constant-music | 1.1.4-forge+26.1.2 | PASS |  |
| corail-tombstone | forge-26.1.2-9.8.4 | PASS |  |
| cpmsvcc | 1.14.4-1.3.2 | PASS |  |
| craftpresence | 2.7.1+26.1.2-forge | PASS |  |
| crash-assistant | 1.11.12 | PASS |  |
| crate-delight | 26.07.01-26.1-forge | PASS |  |
| critter-armory | 26.1.2-0.6.5-forge | PASS |  |
| cubes-without-borders | 5.0.0+26.1 | PASS |  |
| custom-crosshair-mod | v1.6.6-forge-mc26.1.2 | PASS |  |
| custom-fov-illusive | 13.0.0+26.1 | PASS |  |
| customizable-elytra | forge-2.2.8+26.1 | PASS |  |
| customskinloader | 15.1-Universal | PASS |  |
| cyclops-core | 26.1.2-1.31.4 | PASS |  |
| darksmelting | 1.2.0 | PASS |  |
| daycount | 1.6.1-Forge-mc26.1 | PASS |  |
| disable-fog | 2.2.0 | PASS |  |
| dungeons-and-taverns | 5.2.0+mod | PASS |  |
| durability-tooltip | 1.2.0a-forge-mc26.1 | PASS |  |
| easy-mob-farm | 11.1.0 | PASS |  |
| easy-npc | 7.18.0 | PASS |  |
| easy-npc-config-ui | 7.18.0 | PASS |  |
| easy-npc-core | 7.18.0 | PASS |  |
| enchantment-level-cap-indicator | 26.1.2-1.3-fabric+forge+neo | PASS |  |
| enchlevel-langpatch | 3.10.3 | PASS |  |
| entangled | 1.3.21a-forge-mc26.1 | PASS |  |
| essential | 1.5.2 | PASS |  |
| euphoria-patches | 1.10.5-r5.9.3-forge | PASS |  |
| everlasting-abilities | 26.1.2-2.5.11 | PASS |  |
| exp-counter | 26.1.2-forge-2.6 | PASS |  |
| explorations | 26.1.2-1.6.5-Forge | PASS |  |
| factory-api | 26.1.2-2.2.9-forge | PASS |  |
| fantasy_armor | 1.3-26.1.2 | PASS |  |
| fantasy_weapons | 1.0-26.1.2 | PASS |  |
| fast-ip-ping | v1.0.12-mc26.1.2 | PASS |  |
| floral-enchantment | 1.7.0 | PASS |  |
| forgeshot | 26.1.2-2.0 | PASS |  |
| fps-reducer | 26.1-2.16 | PASS |  |
| freecammc | 26.1Fo | PASS |  |
| freelook-og | 1.6.1 | PASS |  |
| from-the-fog | 1.11.3 | PASS |  |
| full-brightness-toggle | 26.1.2-4.9-fabric+forge+neo | PASS |  |
| fusion-connected-textures | 1.3.16-forge-mc26.1 | PASS |  |
| geckolib | 5.5.2 | PASS |  |
| glitchcore | 26.1.2.0.2 | PASS |  |
| glowing-emissive-ores-definitive-edition | 1.0.4-rp1.117.0 | PASS |  |
| growableores | 3.8.0-26.1.2-Forge | PASS |  |
| gui-clock | 26.1.2-5.1-fabric+forge+neo | PASS |  |
| gui-compass | 26.1.2-5.2-fabric+forge+neo | PASS |  |
| gui-effect-timer | 26.1.2-1.4-fabric+forge+neo | PASS |  |
| health-indicator-txf | 26.1.2-1.3.13-forge | PASS |  |
| hidden-recipe-book | 26.1.2-5.3-fabric+forge+neo | PASS |  |
| hide-experimental-warning | 26.1.2-1.3-fabric+forge+neo | PASS |  |
| hide-hands | 26.1.2-4.8-fabric+forge+neo | PASS |  |
| hide-item-frame | 1.5.2-forge+26.1 | PASS |  |
| iceberg | 1.4.1.2 | PASS |  |
| immersivemc | 1.6.0-beta1 | PASS |  |
| in-game-account-switcher | 9.0.8+26.1.2-forge | PASS |  |
| indestructible-blocks | forge-1.0.5-26.1.2 | PASS |  |
| inventory-management | 2.0.2+26.1-forge | PASS |  |
| inventory-profiles-next | forge-26.1-2.3.8 | PASS |  |
| inventoryhudplus | 3.4.31 | PASS |  |
| invtweaks-emu-for-ipn | forge-26.1-1.4.2 | PASS |  |
| item-borders | 1.3.1 | PASS |  |
| item-collectors | 1.1.12a-forge-mc26.1 | PASS |  |
| item-highlighter | 1.2.1 | PASS |  |
| journey-pac | forge-26.1.2-1.1.10 | PASS |  |
| journeymap-web-map | 26.1.2-1.0.12-forge | PASS |  |
| kafs-valentine-special | 5.3.1+26.1.2 | PASS |  |
| kogtyv-tav | 1.7.1 | PASS |  |
| laser-bridges-and-doors | 26.1.2-forge-6.3 | PASS |  |
| legendary-tooltips | 1.6.1 | PASS |  |
| libipn | forge-26.1-6.9.0 | PASS |  |
| lifesteal-mian | forge-10.0.7-26.1 | PASS |  |
| liteminer | 3.4.2+26.1.2 | PASS |  |
| living-things | 26.1.2-2.3.2-Forge | PASS |  |
| macaws-biomes-o-plenty | 26.1.2-1.6 | PASS |  |
| macaws-bridges | 3.1.2 | PASS |  |
| macaws-doors | 1.1.5 | PASS |  |
| macaws-fences-and-walls | 1.2.1 | PASS |  |
| macaws-furniture | 3.4.2 | PASS |  |
| macaws-lights-and-lamps | 1.1.5 | PASS |  |
| macaws-paintings | 1.1.0 | PASS |  |
| macaws-paths-and-pavings | 1.1.1 | PASS |  |
| macaws-roofs | 2.3.2 | PASS |  |
| macaws-stairs | 1.0.2 | PASS |  |
| macaws-trapdoors | 1.1.5 | PASS |  |
| macaws-windows | 2.4.2 | PASS |  |
| magic-vibe-decorations | 2.3.4 | PASS |  |
| many-more-ores-and-crafts | 2.0.1 | PASS |  |
| mcwifipnp | 2.0.8 | PASS |  |
| medieval-buildings | 1.2.0 | PASS |  |
| medieval-buildings-end-edition | 1.2.1 | PASS |  |
| medieval-buildings-nether-edition | 1.2.1 | PASS |  |
| minecraftcapes | forge-26.1-1.1.0 | PASS |  |
| mob-control | 2.0.1-beta-2 | PASS |  |
| modern-ui | 3.13.0.5 | PASS |  |
| modonomicon | 26.1.2-2.20.1 | PASS |  |
| mods-optimizer | 4.3.0 | PASS |  |
| moogs-glow-up | 2.3.0-forge-26.1.2 | PASS |  |
| more-banner-features | 2.4+26.1.2-forge | PASS |  |
| more-music-discs | 26.1.2-forge-35.2 | PASS |  |
| mouse-tweaks | 26.1-2.31-forge | PASS |  |
| move-subtitles | 1.3+26.1-forge | PASS |  |
| moving-elevators | 1.4.12a-forge-mc26.1 | PASS |  |
| nicer-portals | 2.0.3+26.1-forge | PASS |  |
| no-chat-restrictions | Forge-MC26.1-v1.1.3 | PASS |  |
| no-darkness-effect | 0.0.2-26.1-26.2 | PASS |  |
| no-enderman-grief | v3.1.1+mod | PASS |  |
| no-telemetry | 1.10.0 | PASS |  |
| nrb | 1.7.1 | PASS |  |
| opac-ui | 1.1.0 | PASS |  |
| ore-growth | 1.2.0-forge-mc26.1 | PASS |  |
| packed-up-backpacks | 1.1.2-forge-mc26.1 | PASS |  |
| paladins-furniture | 26.1.2-v1.5.1-minecraftforge | PASS |  |
| persistent-inventory-search | 26.1.2-2.0-fabric+forge+neo | PASS |  |
| perspectivenametag | forge-1.2.0+26.1 | PASS |  |
| physicsmod | 3.1.43 | PASS |  |
| pickup-notifications | 3.0.1+26.1-forge | PASS |  |
| ping-view | 1.6 | PASS |  |
| ping-wheel | 1.12.1 | PASS |  |
| potion-cauldron | 1.0.2+mc26.1.2 | PASS |  |
| prism-lib | 1.1.1 | PASS |  |
| raised | Forge-26.1.2-6.0.2 | PASS |  |
| random-village-names | 26.1.2-5.0-fabric+forge+neo | PASS |  |
| realistic-bees | 26.1.2-5.1-fabric+forge+neo | PASS |  |
| rechiseled | 1.2.6-forge-mc26.1 | PASS |  |
| resource-backpacks | 26.1.2-2.2.0 | PASS |  |
| resource-config-api | 26.1.2-4.1.1 | PASS |  |
| resource-gamma-utils | 26.1.2-1.6.0 | PASS |  |
| resource-library | 26.1.2-4.0.3 | PASS |  |
| scalable-cats-force | 6.1.1-mc26.1.2-3.9.0-forge | PASS |  |
| screenshot-compression | 26.1.2-1.4-fabric+forge+neo | PASS |  |
| shoulder-surfing-reloaded | 26.1.2-5.1.1+forge | PASS |  |
| simple-discord-rpc | 4.1.4 | PASS |  |
| simple-magnets | 1.1.12-forge-mc26.1 | PASS |  |
| simple-menu | 26.1.2-2.4-fabric+forge+neo | PASS |  |
| skniros-furniture | 1.6.0-26.1.2-Forge | PASS |  |
| some-more-blocks | 26.1.0.0 | PASS |  |
| sound-culling | 2.1.0 | PASS |  |
| sound-physics-remastered | forge-1.5.2+26.1.2 | PASS |  |
| sprint-indicator | 2.1.0+26.1-forge | PASS |  |
| stack-refill | 26.1.2-6.2-fabric+forge+neo | PASS |  |
| stacked-blocks | 26.03.26+26.1-forge | PASS |  |
| storage-delight | 26.07.01-26.1-forge | PASS |  |
| storagedrawers | 26.1.0.2+forge | PASS |  |
| structures-mod | 4.5 | PASS |  |
| supermartijn642s-core-lib | 1.1.26c-forge-mc26.1 | PASS |  |
| swinging-lanterns | 26.1-26.1.2-2.1.0 | PASS |  |
| terrablender | 26.1.2.0.3 | PASS |  |
| tesseract | 1.0.40-forge-mc26.1 | PASS |  |
| time-in-a-bottle-universal | 8.0.0 | PASS |  |
| tinted-campfires | 1.0.1 | PASS |  |
| too-many-paintings | 26.08.16-26.1-forge | PASS |  |
| tough-as-nails | 21.11.0.6 | PASS |  |
| trade-cycling | forge-1.0.23+26.1.2 | PASS |  |
| transmog | forge-1.8.0+26.1 | PASS |  |
| trash-cans | 1.1.2a-forge-mc26.1 | PASS |  |
| tslatentitystatus | 1.10 | PASS |  |
| undead-nights | 2.2.5-Forge-mc26.1 | PASS |  |
| underground-worlds | 3.2.1-26.1 | PASS |  |
| unilib | 1.2.1+26.1.2-forge | PASS |  |
| upgrade-items | 1.3.0 | PASS |  |
| vanilla-zoom | 26.1.2-3.3-fabric+forge+neo | PASS |  |
| vault-patcher | 1.5.4 | PASS |  |
| villager-in-a-bucket | 26.1.2-1.0.0 | PASS |  |
| vivecraft | 26.1.2-1.3.15-forge | PASS |  |
| voxelmap-updated | 26.1-1.16.6 | PASS |  |
| wall-jump-txf | 26.1.2-1.3.8-forge | PASS |  |
| watermedia | 3.0.1 | PASS |  |
| watermedia-binaries | 3.0.1 | PASS |  |
| weaponmaster | forge-26.1-4.4.4 | PASS |  |
| weaponsexpanded | 1.9.3 | PASS |  |
| weather-changer | 1.4.0-beta1 | PASS |  |
| wider-ender-chests | 1.2.0 | PASS |  |
| wireless-chargers | 1.0.10-forge-mc26.1 | PASS |  |
| wooden-shears | 26.1-4.0.1.1 | PASS |  |
| wormhole | 1.1.17-forge-mc26.1 | PASS |  |
| wwaypoints | 0.8.1 | PASS |  |
| x-to-xray | 2026.29 | PASS |  |
| xaeros-minimap | forge-26.1.2-26.6.2 | PASS |  |
| xaeros-world-map | forge-26.1.2-1.47.2 | PASS |  |
| ydms-mobhealthbar | forge-26.1.2-2.3.3 | PASS |  |

### Fabric mods


| Mod (Modrinth slug) | Version | Result | Note |
|---|---|---|---|
| letmedespawn |  | Fails on real Forge too | Not an Alloy result: it needs Dungeon Config, which was not in our test set (missing dependency). |
| rrls |  | Fails on real Forge too | Harness artifact: RRLS replaces the loading overlay, so the unattended test never sees the title screen. Work... |
| almanac |  | PASS |  |
| ambientsounds |  | PASS |  |
| amendments |  | PASS |  |
| appleskin |  | PASS |  |
| architectury |  | PASS |  |
| athena |  | PASS |  |
| attributefix |  | PASS |  |
| badoptimizations |  | PASS |  |
| badpackets | forge-0.12.2 | PASS |  |
| balm | 26.1.2.15+forge-26.1.2 | PASS |  |
| betteradvancements |  | PASS |  |
| betterdungeons |  | PASS |  |
| betterendisland |  | PASS |  |
| betterf3 |  | PASS |  |
| betterfortresses |  | PASS |  |
| betterjungletemples |  | PASS |  |
| bettermineshafts |  | PASS |  |
| bettermounthud |  | PASS |  |
| betteroceanmonuments |  | PASS |  |
| betterstats |  | PASS |  |
| biomesoplenty |  | PASS |  |
| bobby |  | PASS |  |
| bookshelf |  | PASS |  |
| c2me |  | PASS |  |
| capes |  | PASS |  |
| carryon |  | PASS |  |
| chat_heads |  | PASS |  |
| chatanimation |  | PASS |  |
| cherishedworlds |  | PASS |  |
| chloride |  | PASS |  |
| chunky | 1.5.66 | PASS |  |
| cloth-config |  | PASS |  |
| clumps |  | PASS |  |
| collective | 26.1.2-8.86-fabric+forge+neo | PASS |  |
| comforts |  | PASS |  |
| continuity |  | PASS |  |
| controlify |  | PASS |  |
| controlling |  | PASS |  |
| crash_assistant |  | PASS |  |
| creativecore |  | PASS |  |
| customskinloader-bootstrap |  | PASS |  |
| cwb |  | PASS |  |
| debugify |  | PASS |  |
| distanthorizons |  | PASS |  |
| drippyloadingscreen |  | PASS |  |
| dynamic_fps |  | PASS |  |
| dynamiccrosshair |  | PASS |  |
| e4mc |  | PASS |  |
| enchdesc |  | PASS |  |
| enhancedvisuals |  | PASS |  |
| entity_model_features |  | PASS |  |
| entity_texture_features |  | PASS |  |
| entityculling |  | PASS |  |
| essential-container |  | PASS |  |
| euphoria_patcher |  | PASS |  |
| fabric-api |  | PASS |  |
| fabric-language-kotlin |  | PASS |  |
| fabrishot |  | PASS |  |
| fallingleaves |  | PASS |  |
| fancymenu |  | PASS |  |
| fastipping |  | PASS |  |
| fastquit |  | PASS |  |
| ferritecore |  | PASS |  |
| forgeconfigapiport |  | PASS |  |
| freecam |  | PASS |  |
| fzzy_config |  | PASS |  |
| geckolib | 5.5.2 | PASS |  |
| glitchcore | 26.1.2.0.2 | PASS |  |
| iceberg | 1.4.1.2 | PASS |  |
| immediatelyfast |  | PASS |  |
| inventoryprofilesnext |  | PASS |  |
| iris |  | PASS |  |
| jade |  | PASS |  |
| jei |  | PASS |  |
| kiwi |  | PASS |  |
| konkrete |  | PASS |  |
| krypton |  | PASS |  |
| lambdynlights |  | PASS |  |
| languagereload |  | PASS |  |
| libipn | forge-26.1-6.9.0 | PASS |  |
| litematica |  | PASS |  |
| lithium |  | PASS |  |
| lithostitched |  | PASS |  |
| lootr |  | PASS |  |
| malilib |  | PASS |  |
| melody |  | PASS |  |
| midnightlib |  | PASS |  |
| modernui |  | PASS |  |
| modmenu |  | PASS |  |
| moonlight |  | PASS |  |
| morechathistory |  | PASS |  |
| moreculling |  | PASS |  |
| mousetweaks |  | PASS |  |
| mr_dungeons_andtaverns |  | PASS |  |
| mru |  | PASS |  |
| naturescompass |  | PASS |  |
| netherportalfix |  | PASS |  |
| nochatreports |  | PASS |  |
| notenoughanimations |  | PASS |  |
| openpartiesandclaims |  | PASS |  |
| optigui |  | PASS |  |
| owo |  | PASS |  |
| packetfixer |  | PASS |  |
| particlerain |  | PASS |  |
| patchouli |  | PASS |  |
| physicsmod | 3.1.43 | PASS |  |
| placeholder-api |  | PASS |  |
| polytone |  | PASS |  |
| presencefootsteps |  | PASS |  |
| prickle |  | PASS |  |
| puzzle |  | PASS |  |
| puzzleslib |  | PASS |  |
| reeses-sodium-options |  | PASS |  |
| replaymod |  | PASS |  |
| resourcefulconfig |  | PASS |  |
| resourcefullib |  | PASS |  |
| rhino | 2101.2.7-build.85+Rhino-1.21 | PASS |  |
| roughlyenoughitems |  | PASS |  |
| searchables |  | PASS |  |
| shogi |  | PASS |  |
| shulkerboxtooltip |  | PASS |  |
| skinlayers3d |  | PASS |  |
| sodium |  | PASS |  |
| sodium-extra |  | PASS |  |
| sound_physics_remastered |  | PASS |  |
| sounds |  | PASS |  |
| spark | 1.10.187-forge | PASS |  |
| sspb |  | PASS |  |
| supermartijn642configlib |  | PASS |  |
| tcdcommons |  | PASS |  |
| terrablender | 26.1.2.0.3 | PASS |  |
| terralith |  | PASS |  |
| toms_storage |  | PASS |  |
| travelersbackpack |  | PASS |  |
| veinminer |  | PASS |  |
| veinminer_client |  | PASS |  |
| visuality |  | PASS |  |
| visualworkbench |  | PASS |  |
| voicechat |  | PASS |  |
| waystones |  | PASS |  |
| wthit | forge-19.0.1 | PASS |  |
| xaerominimap |  | PASS |  |
| xaeroworldmap |  | PASS |  |
| yet_another_config_lib_v3 |  | PASS |  |
| yosbr |  | PASS |  |
| yungsapi |  | PASS |  |
| zoomify |  | PASS |  |

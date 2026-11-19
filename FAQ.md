# Alloy FAQ

Short answers to the questions we get most. When we say "untested", we mean we have no test result for it yet.

## Mods and folders

### Where do mods go?
Fabric mods (and Fabric API) go in `mods/`. Forge mods go in `forgemods/`.

You can also leave Forge mods in `mods/`. See the next question.

### I put a Forge mod in `mods/`. What happens to it?
Launchers and modpack files put every mod in `mods/`, so Alloy looks for Forge mods there on every start.

- A jar that is only a Forge mod (it has `META-INF/mods.toml` and no `fabric.mod.json`) is copied to `forgemods/frommods_<name>.jar` and loads on the Forge side. The original stays in `mods/`, so your launcher can still update or remove it. When you remove it from `mods/`, Alloy removes the copy too.
- A jar built for both Forge and Fabric stays in `mods/` and loads as a Fabric mod. That is how it worked before, so nothing changes for it.
- Alloy only ever deletes files whose name starts with `frommods_`.

You will see lines like `alloy-mods: X is a Forge mod in mods/ -> forgemods/frommods_X` in the prep output.

If you want a multi-loader jar on the Forge side instead, add a line to `config/alloy-sides.properties`:

```
# file name (wildcards allowed) or mod id = forge, fabric or none
collective = forge
```

Then put the jar into `mods/` again. Alloy moves it to `forgemods/` and removes its fitted Fabric copy. `none` keeps a Forge jar in `mods/` off the Forge side.

If the same mod is in both folders, the one in `forgemods/` wins and you get a warning.

One thing to know: error messages name the copy (`forgemods/frommods_X.jar`), not the file in `mods/`.

### How do I know if a mod is Forge or Fabric?
The download page tells you. Modrinth and CurseForge show "Forge" or "Fabric". Inside the jar, a Forge mod has `META-INF/mods.toml` and a Fabric mod has `fabric.mod.json`.

You can also ask the installer:

```
java -jar alloy-installer.jar --classify path/to/mod.jar
```

### Can I use NeoForge mods?
No. Alloy runs Forge and Fabric mods only. Mod Doctor reports a NeoForge-only jar as a blocker.

Many big mods (Create, Mekanism, AE2, Farmer's Delight, Quark) only have NeoForge builds for 26.1.2. Look for a Forge or Fabric build instead. For example, the Fabric build of JEI passed our tests.

### Do JEI and REI show recipes from Forge mods?
Yes. On 26.1.2 there is only a Fabric JEI and a Fabric REI. Put the Fabric one in `mods/`. Alloy hands the JEI and REI plugins of your Forge mods to it, so their recipe categories show up. Forge mods that check whether JEI is installed see it too. The log has lines starting with `Alloy viewers:` that list what was added. A few Forge plugins register nothing useful on REI 26 (Paladin's Furniture's categories stay empty), which is a problem in that mod.

### Can I use Sodium and Iris?
Yes, the Fabric builds, on 1.20.1, 1.20.2, 1.20.4, every 1.21.x version and 26.x. Put them in `mods/`. On 1.20.x and 1.21.x Alloy adds a small compat jar next to Sodium by itself, so Forge blocks are drawn the Forge way (Forge render types, connected textures through Forge model data, Forge fluids, block entities).

We tried Iris with the Complementary Reimagined shaders on 1.20.1, 1.20.2, 1.21.1, 1.21.4, 1.21.8 and 1.21.11: Forge blocks, connected textures, water, light and block entities looked right, and the same world without Sodium looked the same. That is a set of test scenes with one shader pack, not every mod.

These are the Sodium and Iris builds we checked on 1.20.2 and 1.21.x:

| Minecraft | Sodium | Iris |
|---|---|---|
| 1.20.2 | 0.5.5 | 1.6.14 |
| 1.21.1 | 0.6.13, 0.8.13 | 1.8.8 |
| 1.21.4 | 0.6.13 | 1.8.8 |
| 1.21.8 | 0.7.3 | 1.9.6 |
| 1.21.11 | 0.8.14 | 1.10.8 |

Other builds of the same line probably work; Mod Doctor tells you when yours is not the one we checked. Sodium 0.6 and newer have the Fabric rendering API built in, so you don't need Indium there. On 1.20.2 the compat is new in 0.5.1, and Indium 1.0.28+mc1.20.4 works there too (it lists 1.20.2 and 1.20.3), checked in 0.5.2 with Sodium Extra and Reese's Sodium Options.

Sodium replaces Minecraft's renderer, so some Forge render hooks may not run. If something looks wrong, try without Sodium and tell us.

### Should I use Fabric Sodium or Embeddium?
Use one of them, never both. Mod Doctor warns when it sees both and tells you which one to remove, based on your other mods.

| Minecraft | What you can use |
|---|---|
| 1.20.1 | Forge Embeddium (with Oculus for shaders), or Fabric Sodium (with Iris for shaders, and Indium if a Fabric mod needs it) |
| 1.20.4 | Fabric Sodium and Iris. Embeddium for 1.20.4 is a NeoForge mod, so it does not run here. |
| 1.21.x | Fabric Sodium and Iris. There is no Forge build of Embeddium or Oculus for 1.21. |
| 26.x | Fabric Sodium and Iris |

On 1.20.1, a rough rule: if most of your mods are Forge mods, Embeddium and Oculus are the safer pick, because they are made for Forge. If you have Fabric mods that need Sodium itself, pick Fabric Sodium.

With Embeddium on 1.20.1 you can still use two Fabric Sodium add-ons: Sodium Extra and Reese's Sodium Options. Alloy lets Embeddium stand in for Sodium for those two. Sodium Extra's pages show up under their own tab in Embeddium's video settings, and their settings are saved. With Reese's, Alloy keeps Embeddium's settings screen (it has Reese's search and more), and Mod Doctor suggests removing Reese's because its buttons draw over Embeddium's tab titles. Other Fabric Sodium add-ons next to Embeddium are refused by Mod Doctor with a message, because they hook Sodium code that Embeddium changed. Don't use Sodium Extra and Embeddium Extra together: they are the same mod for two loaders.

### Why is OptiFine blocked?
Alloy does not support OptiFine or OptiFabric, so Mod Doctor stops the start when it finds either one, instead of letting the game crash later. It finds them by what is inside the jar, so a renamed jar is caught too. Use Embeddium and Oculus on Forge 1.20.1, or Fabric Sodium and Iris on any version (see the questions above). Shader packs made for OptiFine usually work with Iris and Oculus.

### Why did my Fabric mod get "-alloy" in its name?
That is fitting. When you add a Fabric jar to `mods/`, Alloy checks it on the next start and writes a fitted copy called `<name>-alloy.jar`. It removes or repairs the few hooks that can't work next to Forge.

- Your original jar is moved to `.alloy/originals/`.
- `.alloy/fit-report.txt` and the files in `.alloy/fit/` list what was changed.
- A mod can work fine with a small feature off. For example, Mod Menu loses its mod count line on the title screen.
- Don't copy a `-alloy.jar` into a normal Fabric install.

### Does Mod Menu show Forge mods?
There is one Mods button, on the title screen and (on 1.20.1 to 1.21.11) in the pause menu. Forge's button and Mod Menu's button are merged into it. It opens a chooser: "Forge mods" (Forge's list), "Fabric mods (Mod Menu)" and "Mod configs (Alloy)". See the next question.

### Where are the config screens of my mods?
Press Mods (title screen or pause menu), then "Mod configs (Alloy)". That list has every Forge and Fabric mod with a config screen or a config file, Forge mods first. You can also go through Forge's list or Mod Menu's list as before.

- A mod with a config screen opens it.
- A mod with only a config file gets a small screen with "Open" buttons for its files and "Open config folder". Your system opens the file in its usual editor. Restart the game after editing, unless the mod says it reloads on its own.
- If a mod's config screen crashes when it opens, you get a short message instead of a crash, and the game goes on. The mod shows `[broken]` in the list for the rest of the session, and `alloy-report.txt` gets a warning that names it. Please report that to the mod's author.

Fabric config screens need Mod Menu. When you import a pack whose Fabric mods have Mod Menu screens, the installer adds Mod Menu for you.

In the pause menu, Forge's own Mods button is hidden so there is only one, which leaves a small gap above "Save and Quit". `-Dalloy.modlist=chooser` keeps the old pause menu, `-Dalloy.configscreens=off` turns all of this off. On 26.x the pause menu route and the Alloy list are off for now (`-Dalloy.configscreens=on` turns them on, untested).

A Forge config screen that crashes when it opens is caught in Forge's own list too, also when it crashes while it builds itself. You see the same short message.

On 26.x, a Forge mod that has a config file but no screen of its own gets a simple editor from the Config button in Forge's mod list: on/off and choice buttons, text boxes for numbers and text (red while a value is out of range), lists read only. Done saves the file, Cancel or Esc change nothing. `-Dalloy.configscreens.spec=false` turns the editor off. On 1.20.1 to 1.21.11 those mods get the screen with "Open file" instead.

## When something goes wrong

### The game crashed or did not start. What do I do?
Start it again first. If the crash points to one mod, Alloy offers to start without it (see the next question). Then:

1. Open `alloy-report.txt` in the game folder (next to `mods/`). It starts with `STATUS: OK`, `WARNINGS` or `ERRORS`. When something failed, the next line starts with `ALLOY:` and says in one sentence what happened and what to do. The same line is at the top of the crash report. After that the report says in plain words what is wrong, which mod, and how to fix it. On a client you also get an Alloy error screen with "Open report", "Open mods folder" and "Quit" buttons. On a server the same text goes to the console.
2. Run Mod Doctor: `java -jar .alloy/alloy-installer.jar --doctor .` in the game folder. It finds the usual mistakes: a missing dependency, two copies of one mod, a NeoForge jar, a jar for another Minecraft version.
3. Check `KNOWN-ISSUES.md`. Your mod may already be on it.
4. Try the same mods on plain Forge (for Forge mods) or plain Fabric (for Fabric mods). If they crash there too, it is a problem in the mod, not in Alloy. In our tests, 17 of 355 Forge mods failed on plain Forge too.
5. If it still looks like Alloy's fault, report it (next question).

Common fixes:

| You see | Fix |
|---|---|
| "needs X (not installed)" | Install X. A Forge library goes in `forgemods/`, a Fabric library in `mods/`. |
| "NeoForge-only mod" | Get the Forge or Fabric build, or remove the mod. |
| "two copies of mod X" | Delete one of them. |
| `UnsupportedClassVersionError` or "Java 25 needed" | Install the Java your Minecraft needs: 25 for 26.x, 21 for 1.21.x, 17 or newer for 1.20.x (see "Java" in `INSTALL.md`). The installer itself needs 25. |
| "file in use" while preparing | Stop the server or the game, then start again. |
| `NoClassDefFoundError: alloy/hidden/...` | A Fabric mod tried to use a Forge mod's code, or the other way round. See "What is isolation?" below. |
| A Forge mod does not load (official launcher) | Install again once if the profile was made by Alloy 0.5.2 or older. Or run `java -jar .alloy/alloy-installer.jar --prep .` in the game folder. |

### The last start crashed. What is this window?
That is crash recovery. When a start crashed while the game was starting or a world was loading, and the crash points to one mod (or two), the next start shows "Alloy: the last start crashed" before the game opens. It names the mod and offers:

- **Start without MOD.** The jar moves to `.alloy/disabled/` and the game starts without it. Mods that need it go with it. Nothing is deleted.
- **Try the Forge (or Fabric) build of MOD.** Only when your pack had both builds and the installer kept one. It swaps them.
- **Start normally anyway.**
- **Open crash report.** The window stays open.

To get switched-off mods back: `java -jar .alloy/alloy-installer.jar --restore-disabled .` in the game folder (add a mod name to restore just that one). While a mod is off, every start prints a reminder. If you reopen a world that had the mod's blocks, Alloy backs it up and shows the missing mods first, as always, and you can cancel.

Why no window this time? Alloy only offers this when it is fairly sure which mod it was. There is no offer when the crash came later in play, when the cause is Java, memory, a graphics driver, a broken install or Alloy itself, when the culprit would be Fabric API, Forge or Minecraft, when the crash is more than 7 days old, or when you changed your mods after it. Closing the window starts normally and you get asked again next time. Once you picked a button, that crash is not asked about again.

On a server there is no window. The console gets one `ALLOY:` line with the mod and the flag to start without it (`-Dalloy.recover=disable:MODID`). Nothing is switched off unless you add that flag. `--recover-check .` prints what Alloy would offer without changing anything, and `-Dalloy.recover=off` turns the whole thing off.

Switching a mod off only gets you playing again. The crash is still there, so please report it to the mod's author (or to us, if the same mods work on plain Forge or plain Fabric).

### How do I report a bug?
Open an issue on the Alloy GitHub page and use the "Bug report" form. The easiest way to give us what we need is a crash report zip: press "Make crash report" in the installer window, or run `java -jar .alloy/alloy-installer.jar --report` in the game folder. It holds `alloy-report.txt`, the logs, the newest crash report, your mod list and the Mod Doctor result, with your login token, account ids, player and user names, e-mail addresses and IP addresses removed. Please also tell us what you did, and whether the same mods work on plain Forge or plain Fabric.

If you would rather send the files yourself, add:

1. `alloy-report.txt` from the game folder,
2. the output of `--doctor`,
3. your lock file: run `java -jar .alloy/alloy-installer.jar --lock .` and attach the `alloy.lock.json` it writes,
4. `logs/latest.log` and the crash report, if there is one.

Read the files before you post them. They can contain your username, folder paths and server addresses. Remove those. (The crash report zip does this for you, but a look never hurts.)

Please report Alloy crashes to us, not to the Forge project or FabricMC, even when the crash names Fabric API. The Fabric API inside Alloy is our fitted copy, and they can't help with it.

If you found a security problem, don't post it in public. Use "Report a vulnerability" on the Security tab instead.

### What does the Mod Doctor score mean?
Mod Doctor reads your jars without starting the game. It prints what it found and a score.

- BLOCKER: the game will most likely not start. Examples: a NeoForge-only jar, a missing dependency, two copies of one mod, the wrong Minecraft version, a Fabric mod that needs a Forge-only mod, or a broken jar.
- WARN: it will probably start, but something is off. A mod pair we know crashes together is a WARN too, so read the warnings.
- INFO: just a note.

The score is the average confidence over your mods, minus 5 points for each warning. Any blocker makes it 0. A mod we tested on Alloy counts 100%, an untested Forge mod 85%, an untested Fabric mod 75%, a mod that fails on plain Forge too 20%. These are rough weights we picked, not measured chances.

Exit codes: 0 means no findings, 1 warnings, 2 blockers. When your mods changed, a start through Prism or the scripts runs Mod Doctor first, and blockers stop the start with a plain message. It works offline and sends nothing.

### What is isolation?
Isolation keeps Fabric mods and Forge mods from using each other's code by accident. It is on by default.

- A Fabric mod can't see Forge mods or Forge's loader. A Forge mod can't see Fabric mods.
- Both still share Minecraft, the registries and the world.
- If a mod reaches across, it fails as if the other mod was not installed.
- Some mods need isolation. WTHIT (Fabric) refuses to run when it can see Forge.
- To test without it, start Java with `-Dalloy.isolation=warn` (only writes cross-side uses to `alloy-isolation-report.txt`) or `-Dalloy.isolation=off`. Please tell us which mods needed it.

Isolation is not a security wall. All mods in one game still trust each other.

## Mods that don't work

### Which mods are known not to work, and why?

| Mod | Why | What to do |
|---|---|---|
| Any NeoForge-only mod (Create, Mekanism, AE2, Farmer's Delight, Quark on 26.1.2) | Alloy runs Forge, not NeoForge | Use a Forge or Fabric build if there is one |
| Paladin's Furniture 1.5.1 together with Shoulder Surfing Reloaded 5.1.1 | They can crash together at start, depending on load timing, on plain Forge too | Remove one of them. Mod Doctor warns about this pair. |
| Cherished Worlds 16.0.1 | Forge's event bus refuses one of its classes. Plain Forge crashes the same way. | Wait for a fixed version of the mod |
| Serene Seasons 26.1.2.0.7 | Its own mixin calls itself forever (StackOverflowError), on plain Forge too | Wait for a fixed version |
| No Chat Reports, Forge build | Fails on plain Forge in our test too | Use the Fabric build, which passed |
| Punchy 3.2a | Only accepts exactly Forge 64.0.1 | Wait for an update |
| Compact Storage 26.1.16 | Broken version range in its `mods.toml`, Forge stops before loading mods | Wait for an update |
| Let Me Despawn (Fabric) | Needs Dungeon Config, which our test did not have | Install its dependency |
| OptiFine, OptiFabric | Not supported by Alloy; Mod Doctor stops the start | Embeddium and Oculus (Forge 1.20.1) or Sodium and Iris (Fabric) |
| Balm 21.4.41 (1.21.4), Balm 21.8.10 (1.21.8) | Vanilla asks "Worlds using Experimental Settings are not supported" when a world opens. Plain Forge stops at the same point. | Press Proceed, or leave Balm out |
| Zoomify together with Controlify (1.20.1) | They crash together. Each one alone works. | Remove one of them. Mod Doctor warns about this pair. |
| Continuity and other Fabric rendering API model mods, on 1.21.x | They load, but their models do nothing there, with or without Sodium (Fabric's model hook is off on 1.21.x) | Nothing yet for those mods. For connected textures, the Forge mod Fusion worked in our tests. |

Some mods only failed because our unattended test can't click through their own first-start screen (Legacy4J, NPC Studio) or because they make new players invulnerable for a few seconds (StartInv). RRLS hides the loading screen from our test. These work when a person plays.

The full list, with the error text for each mod, is in `COMPATIBILITY.md`. A mod that is not on that list is untested. That doesn't mean it is broken.

### Are Fabric API features missing?
A few parts of the fitted Fabric API are off on purpose: some are replaced by Alloy's own compat code, some only matter for data generation, and one is something Forge already does. In our tests players did not lose a visible Fabric API feature.

## Multiplayer

### Can friends without Alloy join my server?

| Friend's game | Result |
|---|---|
| Alloy with the same mods | Works. |
| Plain Forge | Worked in our test. |
| Plain Fabric or vanilla | Works if the server has no Forge mods that add blocks or items. |

If the server has such Forge mods, vanilla and plain Fabric players get Forge's own message: "This server has mods that require Forge to be installed on the client". That is expected.

Not checked yet: a plain Forge player joining a server whose Fabric mods add blocks or items, and long play sessions. The safe way is that everyone uses Alloy with the same mods.

### Does LAN multiplayer work?
Yes, between Alloy clients with the same mods. "Open to LAN" passed our two-client test on 1.20.1, 1.20.2, 1.20.4, 1.21.1, 1.21.4, 1.21.8 and 1.21.11: join, Forge and Fabric blocks placed by both players with the same ids on both sides, a Fabric network message there and back, chat both ways, leave and join again. Dedicated servers on the 1.20.x and 1.21.x versions (1.20.4 since 0.5.1) start, but we have not played on one with players yet.

### Do permissions work with LuckPerms?
For Forge mods, yes, the normal Forge way. For Fabric mods that check permissions through fabric-permissions-api (most Fabric mods with permission nodes do), Alloy passes their checks to the Forge side, so LuckPerms Forge answers them:

- A node you set true or false in LuckPerms is used. A node you did not set falls back to what the mod does by itself, usually "op level 2" or "everyone".
- That also works for a player who is offline or never joined (by UUID), when a mod asks about them.
- Only players are sent to LuckPerms. The console and command blocks keep the mod's own rule.
- If a Fabric permission provider (for example LuckPerms Fabric) is installed too, it answers first.

We tested this with LuckPerms Forge on 1.20.1, 1.20.4 and 1.21.1, with a test mod that checks nodes and a command that needs one. LuckPerms Forge does not start in singleplayer, so the test used a copy of it changed to start there, in singleplayer worlds. Not tested yet: a real dedicated server with the normal LuckPerms jar, and 26.x, where the bridge is not built in.

Two LuckPerms fixes came with this: on 1.20.1 the `/lp` command now registers, and LuckPerms 5.4.140 on 1.21.1 no longer crashes when a player joins (that one is a LuckPerms bug that also hits plain Forge; Alloy renames the one old name it calls).

`-Dalloy.permsBridge=false` turns the bridge off.

## Performance

### Is Alloy slower than plain Forge or Fabric?
A little, at startup. On our test PC with 7 mods, Alloy reached the title screen about 3 to 4 seconds later than plain Forge or plain Fabric (16.6 s against 13.2 s and 12.7 s). Joining a world took about the same time. At the title screen Alloy used 50 to 100 MB more memory. Isolation cost under 50 ms.

These were 3 runs per setup on a busy PC, so treat them as rough numbers.

### How much memory should I give it?
For packs with 100 or more mods, give the game 4 GB. Our 162 Forge + 37 Fabric pack stayed flat at about 2.1 GB of heap over 20 minutes. The installer already gives 4 GB on PCs with 12 GB of RAM or more, else 3 GB.

### Why is the first start slow?
On the first start Alloy downloads Minecraft, builds the game jar and fits your Fabric mods. In Prism it also runs the Forge installer. That takes a few minutes. After that, each start only checks that nothing changed, which takes about a second. Adding or changing a Forge mod builds the game jar again, which takes a bit longer.

## Other questions

### Does Alloy phone home?
No. The installer downloads from Mojang, the Forge maven, the Fabric maven and Maven Central. Nothing about you is sent. Mod Doctor and the lock file work offline. The only exception is a lock file check for pack makers that asks Modrinth about your mod files' hashes, and only when you run it.

### Is my world safe?
Alloy backs up a world before it opens it for the first time and every time your mod list changed since the last save. The zips are in `alloy-backups/` in your game folder, the newest 3 per world. If a world needs a mod you removed, Alloy tells you before it loads it, and you can cancel. You can also check a world without starting the game: `java -jar alloy-installer.jar --check-world "saves/My World"`. See "Your worlds" in `INSTALL.md`.

We reopened saved worlds after adding and removing mods, and they had no registry errors. We have not tested long survival play, so keep your own backups too.

### Can I use Alloy in a Fabric instance of the Modrinth App, ATLauncher or the CurseForge app?
Maybe, and we would like to hear how it goes. Since 0.5.1 there is `alloy-agent-full.jar`: you make a Fabric Loader 0.20.1 instance in your launcher, put your Forge and Fabric mods into its `mods/`, put the file into the instance as `.alloy/alloy-agent.jar` and add the Java argument `-javaagent:.alloy/alloy-agent.jar`. The first start installs Alloy into the instance. We tested this only with our own tool that starts an instance the way those launchers do, not in the launchers themselves. Steps and limits: "Alloy in a plain Fabric instance" in `INSTALL.md`.

### Can I use the official Minecraft Launcher?
The installer can make a profile for it, but we have not tested a real launch from it yet. Prism Launcher is the one we tested. See `INSTALL.md`.

### Where is run-client.cmd?
A normal install does not make it any more. It was a test helper that started the game without signing in. Developers can get it back with `--dev-scripts`. To play, use Prism or the official launcher.


## How do I report a bug?

Open an issue here: https://github.com/aboodappmake/Alloy/issues/new/choose. Run `java -jar alloy-installer.jar --report` (or `Alloy-Installer-0.5.4.exe --report`) and attach the zip it makes. It removes your account tokens and personal paths.

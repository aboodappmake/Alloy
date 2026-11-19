# Installing Alloy

Alloy comes as one file: `alloy-installer.jar`. You don't need Python or any other tools.

The installer downloads the official Minecraft and Forge files and builds the game jar on your PC, so the first run needs internet. We don't ship any Minecraft code, not in the installer and not in the Prism zip. The Prism zip has no Forge files either: on the first start it gets them from the Forge maven and patches Forge's loader jars on your PC.

This installer can make 10 Minecraft versions, all with Fabric Loader 0.20.1: 1.20.1 (Forge 47.4.29), 26.1.2 (Forge 64.1.6, the default), 26.2 (Forge 65.1.6), and, as experimental versions, 1.20.4, 26.3, 1.20.2, 1.21.1, 1.21.4, 1.21.8 and 1.21.11. Pick one in the window, or add `--mc VERSION` on the command line, for example `--mc 1.20.1`. 1.20.1 and 26.1.2 are the versions we test the most. Every version can also make a dedicated server (1.20.4 since 0.5.1).

Which way should you pick?

| You use | Read |
|---|---|
| Prism Launcher | "Prism Launcher" below. This is the way we tested the most. |
| MultiMC or another MultiMC-style launcher | "MultiMC and other launchers" |
| The official Minecraft Launcher | "Official Minecraft Launcher" |
| A Fabric instance in the Modrinth App, ATLauncher or the CurseForge app | "Alloy in a plain Fabric instance" (experimental, new in 0.5.1) |
| A dedicated server | "Dedicated server" |

## Java

The installer needs Java 25 or newer. The game needs:

| Minecraft | Java |
|---|---|
| 26.1.2, 26.2, 26.3 | 25 or newer |
| 1.20.1, 1.20.2, 1.20.4 | 17 or newer (we test on 21) |
| 1.21.1, 1.21.4, 1.21.8, 1.21.11 | 21 or newer |

If you start the installer with an older Java (even Java 8), it looks for a Java 25 on your PC and starts itself again with it. It looks in `JAVA_HOME`, in the official Minecraft Launcher's own Java folders (they appear after you have played Minecraft 26.1.2 there once), in Prism's Java folder, in the usual JDK folders (Eclipse Adoptium, Java, Microsoft, Zulu, Amazon Corretto, BellSoft, Semeru) and on `PATH`. If it finds nothing new enough, it says which Javas it found and what is missing, with a link to the Adoptium download. For the 1.x versions it looks for the Java in the table above in the same places.

The Windows exe (`Alloy-Installer-0.5.4.exe`) brings its own Java 25, for the installer only. The game never gets that one. The installer looks for the game's Java in the same places (for 1.20.x Java 21 first, then 17; for 1.21.x Java 21; for 26.x Java 25) and writes it into the start scripts. Prism Launcher and the official Minecraft Launcher pick the game's Java themselves. If no matching Java is on the PC, the install still finishes and one line says which Java to install.

To see what it finds: `java -jar alloy-installer.jar --find-java` (add `--mc 1.20.1` for 1.20.1). To see which Java the game would get: `--find-game-java --mc 1.20.1`.

To pick a Java yourself, add `--java <JAVA_HOME>` on the command line. To change the Java the launch scripts use, set `ALLOY_JAVA` to the full path of `java.exe` (or `java` on Linux and macOS).

If you see `UnsupportedClassVersionError`, your Java is too old for that Minecraft version. Install the Java from the table above.

## The installer window

Double-click `alloy-installer.jar`, or run `java -jar alloy-installer.jar` with no arguments.

Above the tabs you pick the Minecraft version.

The window has a Client tab and a Server tab. On each tab you can set:
- the install folder (default: `Alloy-client` or `Alloy-server` in your home folder),
- a folder of Forge mods and a folder of Fabric mods to copy in,
- a modpack file to import (optional, see "Modpacks" below),
- on the Client tab: whether to download the game assets (about 450 to 700 MB; 654 MB for 1.20.1), and your Minecraft folder for the official launcher,
- on the Server tab: whether you accept the Minecraft EULA.

The buttons:
- Client tab: "Install to the official Minecraft Launcher" and "Export Prism / MultiMC instance". Both do a full client install. The first one also adds a profile to the official launcher.
- Server tab: "Install server".
- Both tabs: "Run Mod Doctor", which checks your mods, and "Make crash report" (see "When the game crashes" below).

Honest note: nobody has clicked through the window by hand yet. If it acts strange, use the command line below and tell us.

## Prism Launcher

Every client install writes a Prism instance into the `prism/` folder of the install folder: a folder called `Alloy <version>` and a zip of it, for example `prism/Alloy 26.1.2.zip` or `prism/Alloy 1.20.1.zip`.

1. Install a client, with the window or with `java -jar alloy-installer.jar --side client --dir Alloy26`.
2. In Prism, choose Add Instance > Import and pick `prism/Alloy 26.1.2.zip`.
3. Open the instance settings and make sure it uses the right Java (25 for 26.x, 21 for 1.21.x, 17 or newer for 1.20.x; 21 works for 1.20.x too).
4. Start the instance. The first start is slow: it downloads Minecraft and runs the Forge installer. That takes a few minutes, once. Later starts only check that nothing changed, which takes about a second.
5. Put Fabric mods in the instance's `minecraft/mods/` and Forge mods in `minecraft/forgemods/`. Forge mods that Prism's own mod downloader puts in `mods/` work too (see `FAQ.md`). Alloy picks up changes at the next start.

The instance has a pre-launch command that runs `alloy-installer.jar --prep` for you. Leave it in place. It is also what shows the crash recovery window after a crashed start (see "When the game crashes" below).

On 26.x the instance passes the same Java options that vanilla Minecraft sets for that version (on 26.3 that includes `-XX:StackShadowPages=32`, without which some starts crashed inside Java). If you made a 26.3 instance with 0.4.1 or older, make a new one with this installer.

Good to know:
- Use a real Microsoft account. With an offline account Prism only offers Demo mode.
- The default name has a space in it. That is fine in the Prism window. Only Prism's command line launch (`--launch`) breaks on the space. If you start Prism from a script, rename the instance, for example to `Alloy26`.
- We tested this with Prism Launcher 11.1 and a real account. That test was done before the project was renamed to Alloy. A new test with the current zip is still to do.

## MultiMC and other launchers

The Prism zip is a normal MultiMC-style instance (`instance.cfg`, `mmc-pack.json`, `patches/`). MultiMC and other launchers that read this format should be able to import it the same way. We have only tested Prism.

Alloy is not in any launcher's "Install loader" list yet. The format for that is ready (see `PACK-MAKERS.md`), but we have not asked any launcher to add it.

## Alloy in a plain Fabric instance (experimental)

New in 0.5.1. If you use a launcher that makes Fabric instances (Modrinth App, ATLauncher, CurseForge app), Alloy can run inside such an instance. You need `alloy-agent-full.jar` from the release (it carries the installer).

Honest note: we tested this with our own tool that lays out and starts an instance the way those launchers do, on 26.1.2 and 1.20.1, on Windows. Nobody has tried it in a real launcher yet. If you do, please tell us how it goes.

1. In your launcher, make a new instance for one of Alloy's Minecraft versions with **Fabric Loader 0.20.1**. Other Fabric Loader versions are not supported in this mode. Give it the Java from the table under "Java" above.
2. Put your mods into the instance's `mods/` folder, Forge and Fabric jars together, the way the launcher's own mod browser does. Fabric API goes there too.
3. In the instance folder (the folder that has `mods/` in it), make a folder called `.alloy`. Copy `alloy-agent-full.jar` into it and rename the copy to `alloy-agent.jar`.
4. In the instance settings, add this Java argument:
   ```
   -javaagent:.alloy/alloy-agent.jar
   ```
   - Modrinth App: Instance settings > Java and memory > Java arguments. The app starts the game in the instance folder, so this short path works.
   - ATLauncher and the CurseForge app: the Java arguments of the instance (CurseForge: profile options). If the game does not start in the instance folder there, use the full path, for example `-javaagent:C:/Games/MyInstance/.alloy/alloy-agent.jar`. The path must not have spaces.
5. Start the instance. On the first start `alloy-agent.jar` installs Alloy into the instance before the game starts: it downloads Minecraft, Forge and the libraries from their official servers with the same checks as the normal installer. That took 1 to 2 minutes in our tests, and it needs internet. Game assets are left to the launcher.
6. Later starts take a second or two before the game starts. When you add or remove Forge mods, the next start picks them up by itself.

Good to know:
- Without the Java argument the game stops at start with `ClassNotFoundException: cpw.mods.modlauncher...`. Add the argument.
- Alloy fits your Fabric mods as usual: the fitted copies (with `-alloy` in the name) are in `mods/`, your original files are kept in `.alloy/originals/`. Forge jars are copied to `forgemods/`. The launcher's mod list may show these names.
- A modpack file (`.mrpack`, CurseForge zip) can't carry a Java argument. Add it by hand after you import the pack.
- To move to a newer Alloy, make a new instance, as with a normal install. A newer `alloy-agent-full.jar` dropped into an old instance does not install again.
- Clients only. For a server, use "Dedicated server" below.
- If you already have such an instance and want to install Alloy into it yourself, the installer can do it: `java -jar alloy-installer.jar --side client --mc VERSION --dir INSTANCE --fabric-instance`. Then add the same Java argument.
## Official Minecraft Launcher

In the window, pick the Minecraft version, set your Minecraft folder and press "Install to the official Minecraft Launcher". On the command line, add `--mc-dir`:

```
java -jar alloy-installer.jar --side client --dir Alloy26 --mc-dir "%APPDATA%\.minecraft"
java -jar alloy-installer.jar --mc 1.20.1 --side client --dir Alloy120 --mc-dir "%APPDATA%\.minecraft"
```

This works the same way for every version in the installer, 1.20.1 included. The installer then writes into your Minecraft folder:
- `versions/Alloy-<version>/Alloy-<version>.json`, which builds on vanilla, for example `Alloy-1.20.1` on vanilla 1.20.1,
- Alloy's extra libraries in `libraries/`,
- a profile called `Alloy <version>` in `launcher_profiles.json`. The old file is saved first as `launcher_profiles.json.alloy-backup-<time>`.

The launcher downloads vanilla Minecraft and its Java itself when you first press Play: Java 17 for 1.20.1 to 1.20.4, Java 21 for 1.21.x, Java 25 for 26.x. You don't have to set a Java.

The profile uses your Alloy install folder (`Alloy120` above) as its game folder, so your mods go in `Alloy120/mods/` and `Alloy120/forgemods/`. Don't move or delete that folder: the profile points at it.

This launcher can't run a command before the game starts, so the Alloy profile starts a small helper (`.alloy/alloy-agent.jar`) inside the game's Java. It runs Alloy's prepare step (`--prep`) first, the same step Prism runs, and shows the crash recovery window after a crashed start. After you add, remove or change mods, just press Play again. The first start after a change takes a few seconds longer. If you installed with Alloy 0.5.2 or older, install again once to get the helper; until then run `java -jar .alloy/alloy-installer.jar --prep .` in the Alloy folder after a mod change.

With `--mc-dir`, the installer ignores `--lean` and prepares the game during the install.

How we tested it: we did not use the real launcher or an account. We installed into a test Minecraft folder and started 1.20.1 and 1.21.1 with the exact command line the launcher builds from these files (its Java, libraries, natives and arguments), with Forge and Fabric mods, into a world. A start from the real launcher has not been reported yet. Please tell us how it goes.

## The run-client script (developers only)

A normal install does not write a start script. Start the game from Prism or the official launcher.

If you work on Alloy itself, `--dev-scripts` also writes `run-client.cmd` (and `run-client.sh`). It runs `--prep` and then starts the game directly as a player called `AlloyDev`, without signing in. You can't join online-mode servers with it.

## Modpacks

The installer can import a Modrinth `.mrpack` or a CurseForge pack zip. It puts Forge jars in `forgemods/` and Fabric jars in `mods/`, copies the pack's configs and other files, and names the Prism instance after the pack.

```
java -jar alloy-installer.jar --modpack MyPack.mrpack --dir MyPack
java -jar alloy-installer.jar --modpack forge-pack.mrpack --modpack fabric-pack.mrpack --dir MyPack
java -jar alloy-installer.jar --modpack MyPack.mrpack --dir MyPack --check
```

- `--check` only checks the pack and lists where each jar would go. It installs nothing.
- You can give a Forge pack and a Fabric pack together. If both have the same mod, the Forge build is kept unless a Fabric mod needs the Fabric one.
- The pack must be for a Minecraft version this installer has. The installer switches to the pack's version by itself. NeoForge packs are refused. Quilt packs are imported as Fabric packs, without the Quilt libraries.
- CurseForge packs: the installer can't download their files (that needs a CurseForge key). It lists the files and stops. Download them by hand into one folder and run again with `--pack-mods FOLDER`.
- Import a newer version of a pack into a new folder. An import over an old one does not remove mods the new version dropped.
- When a client pack has Fabric API and a Fabric mod with a Mod Menu config screen but no Mod Menu, the installer adds the Fabric Mod Menu from Modrinth (hash checked), so those screens can be opened. It says so in its output. Turn it off with `-Dalloy.modpack.modmenu=false`.
- On Minecraft 1.20.x the installer swaps the Fabric build of a few mods that have the same code for both loaders (FerriteCore, ModernFix, ImmediatelyFast, Entity Culling, Clumps, Dynamic FPS, BadOptimizations, Noisium) for their Forge build from Modrinth (hash checked), unless a Fabric mod needs the Fabric build. The Forge build needs no fitting. It says so in its output. Turn it off with `-Dalloy.modpack.forgefirst=false`.
- When a pack has the same mod for both loaders and the installer keeps one build, the other one is saved in `.alloy/alternates/`. Crash recovery can offer it if the kept build crashes.

We tested Modrinth packs. No real CurseForge pack has been tested yet.

## Sodium, Iris and Embeddium

You don't need to set anything up for these. Put the mods in their folders and Alloy adds what it needs.

- Fabric Sodium on 1.20.1, 1.20.2, 1.20.4 and every 1.21.x version: when Prep sees a Sodium jar in `mods/`, it adds Alloy's small compat jar for that Minecraft version next to it (`alloy-sodium120.jar` or `alloy-sodium121.jar`), so Forge blocks draw the Forge way. It removes the compat jar again when you remove Sodium. Don't copy these jars between versions.
- Iris goes in `mods/` next to Sodium. Shader packs go in `shaderpacks/` as usual.
- Forge Embeddium and Oculus (1.20.1 only) go in `forgemods/`. Fabric Sodium Extra and Reese's Sodium Options can go in `mods/` next to them.
- Use either Fabric Sodium or Forge Embeddium, not both. Mod Doctor tells you which one to keep. See "Should I use Fabric Sodium or Embeddium?" in `FAQ.md`.
- No OptiFine. Mod Doctor stops the start when it finds OptiFine or OptiFabric.

## Dedicated server

```
java -jar alloy-installer.jar --side server --dir MyServer
```

- Read the Minecraft EULA at https://aka.ms/MinecraftEULA. The installer writes `eula.txt` with `eula=false`. Change it to `eula=true` yourself if you accept it, or install with `--eula` (in the window: the "I accept the Minecraft EULA" box).
- Put Forge mods in `MyServer/forgemods/` and Fabric mods in `MyServer/mods/`.
- Start it with `run-server.cmd` on Windows or `run-server.sh` elsewhere. The script runs `server.jar` with `java` from `PATH` (or `ALLOY_JAVA`, or `JAVA`). That runs `--prep` first, then starts the server with 2 GB of memory (`-Xmx2G`) and no window (`nogui`).
- On a host or a server panel, upload the whole server folder and pick `server.jar` (in the folder root) as the server jar or "custom jar". The host's usual start line works, for example `java -Xmx4G -jar server.jar nogui`; no Alloy arguments are needed. It does the same as the script. The console and the `stop` command work as usual. If the host wants another name (some want `custom.jar`), rename `server.jar`; it only points at `.alloy/alloy-installer.jar`, so keep it in the folder root. The long form `java -Xmx4G -jar .alloy/alloy-installer.jar --server-start nogui` still works.
- The server folder can be moved or uploaded to another PC or a host: every path in it is relative to the folder. A folder made on Windows should also start on Linux (the class path separator is switched at start), but that is not tested on a Linux host yet. (Not true if you installed with `--cache` pointing outside the folder.)
- Stop the server before you add Forge mods or prepare again. Windows locks the game jar while the server runs.
- A server doesn't need game assets.
- Minecraft 1.20.1 servers: add `--mc 1.20.1`. The installer runs the official Forge 47 installer (`--installServer`) and translates the names on your PC, about two minutes the first time. Java 17 or newer (Alloy tests it on 21). The start script, `--server-start` and moving the folder work the same way. A 1.20.1 modpack (`--modpack PACK --side server`) picks 1.20.1 by itself.
- Minecraft 1.20.2 and 1.21.x servers (new in 0.4.1, experimental): add `--mc 1.20.2`, `--mc 1.21.1`, `--mc 1.21.4`, `--mc 1.21.8` or `--mc 1.21.11`. 1.21.x needs Java 21. We have started them with a few mods up to the EULA prompt, but not played on them yet.
- Minecraft 1.20.4 servers (new in 0.5.1, experimental): add `--mc 1.20.4`. Java 17 or newer (we test on 21). The installer runs the official Forge 49 installer and translates the names on your PC. Started with Waystones, Balm, Fabric API and FerriteCore up to the EULA prompt, not played on yet.
- Every server install also writes `unix_args.txt` in the folder root (it runs `-jar server.jar`), for hosts that start servers with `java @unix_args.txt`.
- A server import of a modpack leaves out the files the pack marks as client only, and jars whose own metadata says client only (Fabric `"environment": "client"`, Forge `clientSideOnly`).
- `--doctor` on a server folder checks it as a server.
- If the last start crashed because of one mod, the console shows one line starting with `ALLOY:` that names the mod and the flag to start without it, for example `-Dalloy.recover=disable:crashme`. Nothing is switched off unless you add that flag. See "When the game crashes" below.
- With LuckPerms on the Forge side, Fabric mods that check permissions get LuckPerms' answer (1.20.1, 1.20.4, 1.21.1; see `FAQ.md`). We have tested that in singleplayer worlds only, not on a dedicated server yet.
- Who can join your server: see "Can friends without Alloy join my server?" in `FAQ.md`.

## Command line options

```
java -jar alloy-installer.jar --side client --dir Alloy26
java -jar alloy-installer.jar --side server --dir MyServer --eula
```

| Option | What it does |
|---|---|
| `--side client` or `--side server` | what to install |
| `--mc VERSION` | install this Minecraft version instead of 26.1.2, for example `--mc 1.20.1` |
| `--modpack PACK` | import a `.mrpack` or CurseForge zip (see "Modpacks"). Add `--check` to only check it. |
| `--dir DIR` | where to install |
| `--forge-mods DIR` | copy the Forge mods from DIR into `forgemods/` |
| `--fabric-mods DIR` | copy the Fabric mods from DIR into `mods/` |
| `--cache DIR` | download cache. Reuse it and later installs are much faster. Default: `.alloy-cache` in the install folder. |
| `--java HOME` | the Java the game uses (a Java home folder or a `java` file) |
| `--eula` | server: write `eula=true` |
| `--no-assets` | client: skip the game assets |
| `--mc-dir DIR` | client: also add a profile to the official Minecraft Launcher in DIR |
| `--lean` | leave no Minecraft code in the install folder. The first `--prep` downloads it. |
| `--prep GAMEDIR` | prepare the game again after mods changed. Add `--force` to start from scratch. |
| `--doctor GAMEDIR` | check your mods. Add `--side client` or `--side server`, and `--json FILE` for a machine-readable report. |
| `--lock GAMEDIR` | write a lock file (`GAMEDIR/alloy.lock.json`, or the file you give with `--out FILE`) |
| `--lock GAMEDIR --verify FILE` | compare the folder with a lock file |
| `--classify JAR_OR_DIR` | print which side (Forge, Fabric or none) each jar would load on |
| `--check-world WORLD` | read a world without starting the game and list what of it belongs to mods your game folder does not have (see "Your worlds") |
| `--report` | make a crash report zip of the game folder you are in (`--game DIR` for another one) |
| `--recover-check GAMEDIR` | print what crash recovery would offer after the last crash. Changes nothing. |
| `--disable-mod GAMEDIR MOD` | switch one mod off: its jar moves to `.alloy/disabled/` |
| `--restore-disabled GAMEDIR [MOD]` | put back every mod crash recovery switched off, or only MOD |

Run `java -jar alloy-installer.jar --help` to see the full list, including the options for launcher makers.

Mod Doctor and the lock file are explained in `FAQ.md` and `PACK-MAKERS.md`.

## Your worlds

Alloy protects worlds you bring from another setup:

- **Backup.** Before Alloy opens a world for the first time, or when your mod list changed since the world was last saved, it zips the world folder to `alloy-backups/<world>-<date>-<time>.zip` in your game folder. It reads the zip back and checks every file before it keeps it. The newest 3 backups per world are kept. If there is not enough free space, you get a screen that says so, and you choose whether to go on without a backup.
- **Missing mods.** Alloy writes your Fabric mod list into `level.dat`, next to Forge's list. When you open a world that has blocks, items or other data of a mod you no longer have, you see a list of those mods first, with "Load anyway" and "Cancel". Cancel changes nothing. Load anyway removes that mod's things from the world when it saves, like plain Forge or Fabric would, and the backup is still there.
- **Player data of missing mods.** Data that a missing mod kept on a player (Curios or Trinkets slots, for example) is kept and put back when the mod returns.
- **Check a world offline.** `java -jar alloy-installer.jar --check-world "saves/My World"` reads the world and lists every mod it needs that your game folder does not have, and where it found it. It changes nothing. Exit code 2 means something would be lost.

To turn the backup off, start the game with `-Dalloy.backup=false`. To keep more or fewer backups, use `-Dalloy.backup.keep=N`.

## When the game crashes

Start the game again first. If the crash happened while the game was starting or while a world was loading, and it points to one mod, Alloy shows a window before the game starts: "Alloy: the last start crashed". It names the mod and gives you these choices:

- "Start without MOD": the mod's jar moves to `.alloy/disabled/` and the game starts. Mods that need that mod are moved with it. Nothing is deleted.
- "Try the Forge build of MOD" or "Try the Fabric build of MOD": only when a pack import kept the other loader's build (see "Modpacks").
- "Start normally anyway".
- "Open crash report": opens it, and the window stays.

Closing the window starts the game normally, and the window comes back next time. Once you have answered, Alloy does not ask again about that crash. While a mod is switched off, every start prints a reminder.

To put switched-off mods back, run this in the game folder (or add a mod name at the end to restore only that one):

```
java -jar .alloy/alloy-installer.jar --restore-disabled .
```

For the Controlify crash in 1.20.1 packs with Zoomify (Fabulously Optimized), the window offers to switch Controlify off. The rest of the pack keeps working, only controller support is missing until you put it back.

To switch one mod off yourself, for example when the crash report says to start without it but no window came, run this in the game folder with the mod id from the mod list in `alloy-report.txt` (here Controlify):

```
java -jar .alloy/alloy-installer.jar --disable-mod . controlify
```

If a world had blocks or items of the switched-off mod, Alloy's world safety works as usual: a backup first, then the list of missing mods with "Load anyway" and "Cancel".

There is no offer when the crash came later in play, when the cause is Java, memory, a graphics driver or Alloy itself, when the crash is older than 7 days, or when you changed your mods since. On a dedicated server, or with `-Dalloy.recover.noWindow=true`, you get one line in the console instead of the window, and nothing is switched off unless you start with `-Dalloy.recover=disable:MODID`. `-Dalloy.recover=off` turns crash recovery off.

The window shows wherever Alloy runs its prepare step before the game: Prism and MultiMC (the pre-launch command), the run scripts, and `--prep` by hand. We tested it on Windows with the Prism pre-launch command line, not in Prism itself. On macOS, when Alloy prepares inside the game (launcher profiles), it writes the line instead of showing the window.

Next, open `alloy-report.txt` in the game folder. Its `ALLOY:` line says in plain words what happened and what to try.

If you want help, make a crash report zip: press "Make crash report" in the installer window, or run `java -jar .alloy/alloy-installer.jar --report` in the game folder. The zip has your logs, the newest crash report, your mod list with checksums, the Mod Doctor result and the diagnosis. Your login token, account ids, player and user names, e-mail addresses and IP addresses are removed. Files that hold accounts, passwords or keys are never read. In the window you see every file before you save the zip.

## Memory

The installer picks the memory for you. A client gets 4 GB on PCs with 12 GB of RAM or more, and 3 GB on smaller PCs. This goes into the Prism instance and the launcher profile (and into `run-client.cmd` when you use `--dev-scripts`). The server script uses 2 GB.

To change it, set the memory in Prism's instance settings, or pass your own `-Xmx` to the script, for example `run-server.cmd -Xmx4G`.

## Updating

| You want to | Do this |
|---|---|
| Add, remove or change mods | Change `mods/` and `forgemods/`, then start the game. Prism, the official launcher profile and the scripts run `--prep` for you. |
| Prepare again from scratch | `java -jar .alloy/alloy-installer.jar --prep . --force` in the game folder |
| Update a Fabric mod | Replace the jar in `mods/`. Alloy fits it again on the next start and drops the old fitted copy. |
| See what changed since it last worked | `java -jar .alloy/alloy-installer.jar --lock . --verify .alloy/last-good.lock.json` |
| Update Alloy itself | Download the new installer and install again into a new folder, with the same `--cache` folder so it is fast. Then move your mods, configs and worlds over. There is no self-update, and we have not tested installing over an old version. |

Nothing updates by itself.

After you move your mods into a 0.5 install, the first start fits every Fabric mod again, because the fitter's rules changed. That start takes a bit longer, once.

## Linux and macOS

The installer writes `.sh` scripts next to the `.cmd` ones, and the Prism zip should work on any system Prism runs on. We play-test on Windows only. On a Linux ARM test machine, the installer and dedicated server starts without a world (mods loaded, no world opened, EULA not accepted) run for every version that has a server. Nobody has played the client on Linux or macOS yet.

What the installer does on these systems:

- It looks for your Minecraft folder in the usual place: `~/Library/Application Support/minecraft` on macOS and `~/.minecraft` on Linux. If you only have the Flatpak launcher from Flathub, it uses that folder, and it asks you to install inside it, because the Flatpak launcher can't see folders outside it.
- It keeps the Apple Silicon (arm64) natives on every Mac and the ARM natives on ARM Linux, so the game starts there.
- A double click on the installer jar opens the window. Started from a terminal it behaves like on a server.
- `--java` accepts a Mac Java folder such as `/Library/Java/JavaVirtualMachines/temurin-25.jdk`. The installer also finds Java through `java_home`, Homebrew and SDKMAN.
- If a script lost its run permission, start it with `sh run-server.sh` or run `chmod +x run-server.sh` once.

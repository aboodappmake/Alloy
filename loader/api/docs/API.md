# Alloy API v1 (1.0.0)

Alloy runs Fabric mods (`mods/`) and Forge mods (`forgemods/`) in one game. The two sides share Minecraft, the
registries and the world, but they cannot see each other's classes: when isolation is on, a Fabric mod that links
to a Forge mod's class gets `NoClassDefFoundError`. The Alloy API is the supported way for a Fabric mod and a
Forge mod to work together anyway. They pass only plain Java values (strings, numbers, lists, maps), so no class
ever crosses from one side to the other.

What the API gives you:

| | |
|---|---|
| `Alloy.isPresent()`, `version()`, `side()` | Is this Alloy? Which version? Is my mod on the Fabric or the Forge side? |
| `Alloy.isModLoaded(id)` / `isModLoaded(id, Side)` / `mod(id)` / `mods()` | Mods on **both** sides |
| `CrossEvents.listen / post` | An event bus both sides share, plus built-in events fired by Alloy |
| `Alloy.channel("modid:name")` | Messages between mods in the same game, and between client and server |
| `alloy` metadata | Tell Alloy's Mod Doctor about your mod (see the end of this page) |

## Getting the jar

`alloy-api.jar` (about 23 KB, Java 21 bytecode, no dependencies). Build it with `python -I build_api.py` in
`alloy/api/` (output: `build/alloy-api.jar`).

- Compile against it.
- At runtime, either bundle it (jar-in-jar) or let players install it. It is safe on plain Fabric and plain Forge,
  where it does nothing (see "Outside Alloy").
  - Fabric: `include(files("libs/alloy-api.jar"))` in Loom, or list it under `"jars"` in fabric.mod.json. Its
    Fabric mod id is `alloy_api`. Use `"suggests"`, not `"depends"`.
  - Forge: jar-in-jar it as a library. Its manifest says `FMLModType: GAMELIBRARY` and
    `Automatic-Module-Name: alloy.api`. It has no mods.toml, so it is not a mod.
- Alloy carries its own copy of the API inside the bridge, so on Alloy the API is always there. The API only
  ever gains methods; existing methods are never changed or removed.

## Basics

```java
if (Alloy.isPresent()) {
    LOG.info("Alloy {} - I am on the {} side", Alloy.version().orElse("?"), Alloy.side());
}
boolean jeiAnywhere = Alloy.isModLoaded("jei");                 // Fabric or Forge
boolean forgeTombstone = Alloy.isModLoaded("tombstone", Side.FORGE);
Alloy.mod("tombstone").ifPresent(m -> LOG.info("{} {} ({})", m.name(), m.version(), m.side()));
```

- `side()` returns the side of **the mod that calls it**. Alloy works it out from the jar your class came from.
  It returns `UNKNOWN` for code that is not a mod's (Minecraft, libraries).
- `mods()` lists every mod on both sides. `minecraft` shows up on both.

## Plain data

Event data and messages are `Map<String, ?>` values that may contain only:

`null`, `String`, `Boolean`, `Byte`, `Short`, `Integer`, `Long`, `Float`, `Double`, `byte[]`, `List`, and
`Map<String, …>`. Nesting can go up to 32 levels deep.

- The API copies your map when you post or send it, so changing it afterwards has no effect.
- What the receivers get is unmodifiable.
- Anything else (a `BlockPos`, an `ItemStack`, one of your own classes) is refused with an `IllegalArgumentException`
  that names the key. Send ids and numbers instead: `"minecraft:stone"`, `x`, `y`, `z`, a UUID as a string.
  `PlainData.isPlain(value)` checks a value before you send it.

## Cross-side events

```java
// any side: listen
Subscription sub = CrossEvents.listen("mymod:boss_killed", e ->
        LOG.info("{} killed {} (posted by {} on the {} side)",
                 e.getString("player", "?"), e.getString("boss", "?"), e.fromMod(), e.fromSide()));
// any side: post
int listeners = CrossEvents.post("mymod:boss_killed", Map.of("player", uuid, "boss", "minecraft:wither"));
sub.close();   // stop listening
```

- Event names look like `namespace:path` (lower case). Use your mod id as the namespace.
- Listeners run right away, on the thread that posts, in the order they were added. Every listener is called,
  including the poster's own.
- A listener that throws is logged and skipped. The other listeners still run.

### Built-in events (fired by Alloy, on the server thread)

| Name (`CrossEvents.*`) | Data |
|---|---|
| `PLAYER_JOIN` = `alloy:player_join` | `uuid`, `name` |
| `PLAYER_LEAVE` = `alloy:player_leave` | `uuid`, `name` |
| `SERVER_STARTED` = `alloy:server_started` | `dedicated` (Boolean) |
| `SERVER_STOPPING` = `alloy:server_stopping` | `dedicated` |
| `BLOCK_BROKEN` = `alloy:block_broken` | `x`, `y`, `z`, `dimension` (`"minecraft:overworld"`), `state_id` (Minecraft's block-state id), `block` (`"minecraft:stone"`), `player` (UUID) |

- These come from Forge's event bus, so they fire with or without Fabric API.
- `BLOCK_BROKEN` fires only once a break is final: after all Forge listeners, and only if nobody cancelled it. That
  includes a Fabric `PlayerBlockBreakEvents.BEFORE` cancel, which stops Forge from seeing the break at all.
- `e.isBuiltIn()` is true for these events. The `alloy:` namespace is reserved: mods cannot post to it.

## Messages in the same game

```java
Channel ch = Alloy.channel("mymod:sync");          // get it from your own mod's code
ch.onMessage(m -> { if (!m.remote()) handle(m.getString("what", "")); });
int delivered = ch.send(Map.of("what", "reload"));    // or ch.send(byteArray)
```

- A Fabric mod and a Forge mod that use the same channel name talk to each other.
- `send` calls every listener of that channel right away, except the sending mod's own listeners. A mod never gets
  its own local messages.
- `Message` gives you `fromMod()`, `fromSide()`, `data()` / `getString(...)`, `isBytes()` / `bytes()`.

## Messages over the network

```java
// client -> server (returns false if not connected, or if the server is not Alloy)
ch.sendToServer(Map.of("type", "request", "page", 2));
// server -> one player / all players
ch.sendToPlayer(playerUuid, Map.of("type", "page", "rows", rows));
ch.sendToAllPlayers(Map.of("type", "reset"));
// both ends: the same onMessage listeners receive it, on the main thread
ch.onMessage(m -> {
    if (m.remote() && !m.clientSide()) {     /* on the server, from player m.player() */ }
    if (m.remote() && m.clientSide())  {     /* on the client, from the server */ }
});
```

- Alloy carries every channel over **one** payload channel of its own, `alloyloader:api` (protocol 1).
  - It is optional on both ends: vanilla, pure Fabric and pure Forge players can still join.
  - A message is sent only to a peer that runs Alloy. Otherwise it is dropped, and the call returns false or 0.
- `isNetworkAvailable()` tells whether that channel is registered in this game.
- Size limits: about 32 KB per message from a client, 1 MB from the server.
- Call the server methods from the server thread.
- **Singleplayer:** the client and the integrated server run in one game and share listeners. Use `m.clientSide()`
  to tell which half received the message.
- **Treat data from clients as untrusted.** Check every value, and check `m.player()` before acting on it.

## Example: a Fabric mod and a Forge mod talking

The full, compiling versions are in `alloy/api/samples/`. Build them with `python -I build_api.py`.

Fabric side (`mods/`):

```java
public final class SampleFabric implements ModInitializer {
    @Override public void onInitialize() {
        Channel ch = Alloy.channel("ffapisample:ping");
        ch.onMessage(m -> {
            if (!m.remote() && m.getString("type", "").equals("pong"))
                LOG.info("Forge mod {} answered, n={}", m.fromMod(), m.getInt("n", -1));
        });
        CrossEvents.listen(CrossEvents.SERVER_STARTED, e -> ch.send(Map.of("type", "ping", "n", 1)));
        CrossEvents.listen("ffapisample:forge_ready", e -> LOG.info("Forge side is ready"));
    }
}
```

Forge side (`forgemods/`):

```java
@Mod("ffapi_sample_forge")
public final class SampleForge {
    public SampleForge(FMLJavaModLoadingContext ctx) {
        Channel ch = Alloy.channel("ffapisample:ping");
        ch.onMessage(m -> {
            if (!m.remote() && m.getString("type", "").equals("ping"))
                ch.send(Map.of("type", "pong", "n", m.getInt("n", 0) + 1));
        });
        CrossEvents.listen(CrossEvents.SERVER_STARTED, e -> CrossEvents.post("ffapisample:forge_ready", Map.of()));
        CrossEvents.listen(CrossEvents.PLAYER_JOIN, e -> ch.sendToPlayer(e.getString("uuid", null), Map.of("type", "welcome")));
    }
}
```

Neither mod imports a class from the other, so this keeps working with isolation enforced.

## Outside Alloy

On plain Fabric or plain Forge every call is safe:

| Call | Result |
|---|---|
| `isPresent()` | false |
| `version()` | empty |
| `side()` | the running loader (`FABRIC` or `FORGE`) |
| `isModLoaded` / `mods()` | ask that loader only |
| `listen` | returns `Subscription.NONE` and never fires |
| `post` / `send` | return 0 |
| `sendToServer` / `sendToPlayer` | return false |

Bad names and non-plain data still throw, so you find those mistakes in a normal dev environment.
`-Dalloy.api.disable=true` makes the API act like this even on Alloy.

## `alloy` metadata (for the Mod Doctor)

This metadata is optional. It helps Alloy's Mod Doctor (its pre-launch check) give better advice. Plain Fabric
and plain Forge ignore it.

fabric.mod.json, under `custom`:

```json
"custom": {
  "alloy": { "sides": ["fabric"], "tested_versions": ["0.1"], "requires_isolation": false }
}
```

mods.toml, as a top-level table (place it after the top-level keys like `modLoader` and before `[[mods]]`):

```toml
[alloy]
sides=["forge"]
tested_versions=["0.1"]
requires_isolation=false
```

| Key | Type | Meaning |
|---|---|---|
| `sides` | list of `"fabric"` / `"forge"` | Which Alloy side(s) this jar is made to be loaded on. A multi-loader jar lists both. The Doctor warns when the jar sits in the other side's folder. |
| `tested_versions` | list of Alloy versions | Versions the author tested. A plain version (`"0.1"`) covers all of its patch versions (`"0.1.x"`). Ranges use the Fabric (`">=0.1 <0.3"`) or Maven (`"[0.1,0.3)"`) form. The Doctor shows "tested by the author" for a match and "untested" otherwise. |
| `requires_isolation` | boolean | `true`: the mod needs side isolation to be on (`-Dalloy.isolation=enforce`). Example: a multi-loader jar whose "am I on Fabric?" check gets the wrong answer when it can see the other loader's classes. The Doctor warns when isolation is off. |

Keys the Doctor does not know are ignored, so later versions can add more keys.

## Limits of v1

- Events and local messages are delivered synchronously, with no priorities and no cancelling.
- Built-in events come from the logical server only. There are no client-only built-ins yet.
- Isolation is not a security boundary, and neither is this API: mods in one game trust each other.

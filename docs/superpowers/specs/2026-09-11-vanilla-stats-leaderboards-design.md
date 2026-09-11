# Vanilla statistics as selectable leaderboard stats

Date: 2026-09-11
Status: approved for planning (revised after adversarial review)

## Problem

Leaderstats tracks exactly two numbers of its own — deaths and advancements —
in `statsboard.json`, and both the `/leaderboard` command and the hologram
block hardcode those two. Minecraft already tracks hundreds of per-player
statistics (the vanilla Statistics screen: General, Items, Mobs) and persists
them per player in the world save. Players should be able to build a
leaderboard for any of them.

## Decisions

Settled before design; not open questions:

1. **Scope: the full vanilla registry**, searchable — every custom stat, every
   item/block stat, every mob stat. Not a curated shortlist.
2. **Selection happens in two places**: each hologram block is configured to
   show chosen stats, *and* the full-screen GUI can switch stat freely.
3. **The mod's own deaths counter is dropped** in favour of vanilla
   `minecraft:deaths`.
4. **Offline players are included** — the leaderboard covers everyone who has
   ever played on the world, by reading the per-player save files.
5. **Advancement counts come from `world/advancements/<uuid>.json`**, not from
   the existing mixin counter, so they are correct retroactively.

Consequence of 3 + 5: any deaths and advancements recorded by the old
`statsboard.json` counters are discarded. Vanilla's own `minecraft:deaths` and
the advancement files are the source of truth from now on. This is a
deliberate, accepted one-way change.

## The threading constraint

This is the single most important fact in the design, and the first version of
this spec got it wrong. Read this before anything else.

`StatType<T>` holds its `Stat` instances in a plain, unsynchronized
`java.util.IdentityHashMap`, mutated through `computeIfAbsent`:

```
public net.minecraft.stat.StatType(net.minecraft.registry.Registry<T>);
       5: new  #30   // class java/util/IdentityHashMap
      12: putfield #33  // Field stats:Ljava/util/Map;

public net.minecraft.stat.Stat<T> getOrCreateStat(T, net.minecraft.stat.StatFormatter);
      12: invokeinterface #77  // InterfaceMethod java/util/Map.computeIfAbsent
```

`Stats.MINED`, `Stats.CRAFTED`, … are global singletons, and the server thread
writes to those maps whenever a player mines or crafts something for the first
time. Concurrent `computeIfAbsent` on a `HashMap`/`IdentityHashMap` corrupts
the table. In single-player it is worse: the integrated server thread and the
client render thread share the same statics.

Therefore, two absolute rules:

- **`StatKey.resolve()` — i.e. anything that calls `getOrCreateStat` — is
  server-thread only.** It is never called from the scanner thread and never
  called from the client.
- **The client never resolves a `StatKey` at all.** It gets display names from
  read-only registry lookups and formats values with our own formatter (below).

Similarly, `StatFormatter.DEFAULT` / `DISTANCE` / `DIVIDE_BY_TEN` close over a
process-wide `java.text.DecimalFormat`, which is not thread-safe. Vanilla only
ever formats on the client render thread. We format on two threads, so **we do
not call `Stat.format` anywhere** — see `StatValueFormatter`.

## Verified API surface (Yarn 1.20.1+build.10)

Verified by disassembling the remapped Minecraft jar:

```java
// net.minecraft.stat.StatHandler
public int getStat(Stat<?> stat);

// net.minecraft.stat.ServerStatHandler extends StatHandler
public ServerStatHandler(MinecraftServer server, File file);  // ctor parses the file

// net.minecraft.server.network.ServerPlayerEntity  (NOT PlayerEntity)
public ServerStatHandler getStatHandler();
public PlayerAdvancementTracker getAdvancementTracker();

// net.minecraft.server.MinecraftServer
public ServerAdvancementLoader getAdvancementLoader();  // .get(Identifier), .getAdvancements()
public UserCache getUserCache();                        // Optional<GameProfile> getByUuid(UUID)
public Path getSavePath(WorldSavePath);

// net.minecraft.stat.StatType<T>
public Stat<T> getOrCreateStat(T value);   // SERVER THREAD ONLY - see above
public Registry<T> getRegistry();
public String getTranslationKey();         // "stat_type.minecraft.mined"

// net.minecraft.registry.Registries
public static final Registry<StatType<?>> STAT_TYPE;   // SimpleRegistry
public static final Registry<Identifier> CUSTOM_STAT;  // SimpleRegistry

// net.minecraft.util.WorldSavePath
public static final WorldSavePath STATS;        // "stats"
public static final WorldSavePath ADVANCEMENTS; // "advancements"
```

Three facts that shape the design:

- **`Registries.BLOCK`, `ITEM` and `ENTITY_TYPE` are `DefaultedRegistry`.**
  `SimpleDefaultedRegistry.get(Identifier)` substitutes the default entry
  (`minecraft:air`) rather than returning null, so it **cannot** detect an
  unknown value. Every value lookup in this design uses **`getOrEmpty`**, which
  `SimpleDefaultedRegistry` overrides to bypass the default. Using `get` here
  would make packet validation accept arbitrary client input.
- `Stats.MINED` is `StatType<Block>`, not `StatType<Item>`. The picker
  enumerates `Registries.BLOCK` for `mined` and `Registries.ITEM` for
  `crafted` / `used` / `broken` / `picked_up` / `dropped`.
- `AdvancementProgress$Serializer.serialize` unconditionally writes
  `json.addProperty("done", progress.isDone())`, so `"done": true` really is
  present in `world/advancements/<uuid>.json`. Of the 110 non-recipe
  advancements shipped in 1.20.1 every one has a `"display"` block; of the 1161
  recipe advancements, **zero** do. So `getDisplay() != null` is an exact
  recipe filter, and omitting it would inflate every count by ~1160.

## Architecture

### `com.statsboard.stat.StatKey`

```java
public record StatKey(Identifier typeId, Identifier valueId)
```

The single identity for "which stat is this leaderboard about".

- `Optional<Stat<?>> resolve()` — **server thread only.**
  `Registries.STAT_TYPE.getOrEmpty(typeId)`, then
  `type.getRegistry().getOrEmpty(valueId)`, then `type.getOrCreateStat(value)`.
  Empty for an unknown type or value; every caller handles empty rather than
  throws.
- `boolean isValid()` — **safe on any thread.** The two `getOrEmpty` lookups
  *without* `getOrCreateStat`. This is what packet and command validation use,
  so validation never mutates a `StatType` map.
- `Text displayName()` — **safe on any thread**, read-only registry lookups.
  For `minecraft:custom`, `Text.translatable("stat." + valueId.toString().replace(':', '.'))`.
  For `ADVANCEMENTS`, a mod lang key. Otherwise
  `Text.translatable(statType.getTranslationKey())` followed by the value's own
  name, dispatched explicitly because `StatType<T>` exposes no generic name
  accessor. Note this is Java 17 — `build.gradle` pins `options.release = 17`,
  so pattern-matching `switch` and `case null` (Java 21) are unavailable;
  `instanceof` patterns are fine:

  ```java
  Object v = type.getRegistry().getOrEmpty(valueId).orElse(null);
  Text name;
  if (v instanceof Block b) name = b.getName();
  else if (v instanceof Item i) name = i.getName();
  else if (v instanceof EntityType<?> e) name = e.getName();
  else name = Text.literal(valueId.toString());
  ```

  Deliberately does **not** use `StatType.getName()`, which lazily writes a
  non-volatile `Text name` field and so is not safe to call from the client.

- Wire codec: `buf.writeIdentifier(typeId)` then `buf.writeIdentifier(valueId)`;
  read in the same order.
- NBT codec: a compound with string keys `"Type"` and `"Value"`. `fromNbt`
  returns `Optional<StatKey>`, empty if either key is absent or is not a
  well-formed identifier (`Identifier.tryParse` returns null), so a
  hand-edited or corrupted block never throws during chunk load.

Constants: `StatKey.DEATHS` (`minecraft:custom` / `minecraft:deaths`) and
`StatKey.ADVANCEMENTS` (`statsboard:special` / `statsboard:advancements`).

`ADVANCEMENTS` is a **pseudo-stat**: it does not resolve to a vanilla `Stat`.
It is the one special case, handled in exactly three places — `StatQuery`'s
value source, `displayName()`, and `isValid()`. Everywhere else it flows
through the same path as a real stat.

### `com.statsboard.stat.StatValueFormatter`

Pure, static, thread-safe. Replaces `Stat.format(int)` entirely, because that
goes through a shared `DecimalFormat`.

```java
public static String format(StatKey key, int value)
```

Classifies by stat id rather than by reading vanilla's private formatter field.
This is safe because the mapping is a fixed table in `Stats`'s static
initializer, which was disassembled to confirm the rules below are exact — no
false positives and no missed entries:

- custom stat id ending `_one_cm` → **distance**. 15 such stats, and no
  `_one_cm` stat uses any other formatter.
- `play_time`, `total_world_time`, `time_since_death`, `time_since_rest`,
  `sneak_time` → **time**. Exactly these five.
- custom stat id starting `damage_` → **divide by ten**. 7 such stats, and no
  other id starts with `damage_`.
- everything else, and every non-custom stat type → **plain count**. This
  includes `minecraft:jump` and `minecraft:drop`, which are ordinary counts.

**Output must match vanilla exactly**, so a value reads the same here as on the
player's own Statistics screen. Vanilla's shapes, from
`StatFormatter`'s bytecode:

- shared decimal format is `new DecimalFormat("########0.00")` — always two
  decimal places
- **distance** (input cm): `>= 1000 m` → `"<km> km"`; `>= 0.5 m` → `"<m> m"`;
  otherwise the raw centimetres as an integer plus `" cm"`
- **time** (input ticks): a **single largest unit**, not a composite. Convert
  ticks → s → m → h → d → y, and emit the largest unit whose value exceeds
  0.5, formatted to two decimals: `"12.34 h"`, `"1.50 d"`. Below 0.5 minutes,
  emit whole seconds plus `" s"`.
- **divide by ten**: `value * 0.1` through the same two-decimal format
- **plain count**: grouped integer, `NumberFormat.getIntegerInstance(Locale.US)`

Every formatter instance is held in a `ThreadLocal`, so none is ever shared
across threads — which is the whole reason this class exists.

### `com.statsboard.stat.StatSnapshot`

Immutable, published through a `volatile` field, **initialised to an empty
snapshot and never null** (block entities tick from the first server tick,
before the first scan finishes).

```java
Map<UUID, Object2IntMap<StatKey>> values;  // from world/stats/*.json
Map<UUID, Integer> advancementCounts;      // from world/advancements/*.json
Set<UUID> knownPlayers;                    // union of both directories
```

Note `values` holds **plain ints keyed by `StatKey`**, not `ServerStatHandler`
objects and not `Stat` objects. That is what keeps the scanner off the
`StatType` maps, and it is also far smaller in memory — it is exactly the
file's contents as primitives.

`knownPlayers` is the **union** of the two directories' filenames, not just the
stats directory: an imported or hand-edited world can have an advancements file
with no stats file, and such a player must still appear on the advancements
board. Every read of `values` and `advancementCounts` goes through
`getOrDefault` with an empty default, so a UUID present in one map and absent
from the other yields zero rather than an NPE.

Nothing evicts, which is a deliberate trade-off: a 10k-player world holds 10k
small int maps. Acceptable; revisit only if it is ever measured to be a
problem.

### `com.statsboard.stat.StatScanner`

Owns a single-thread `ScheduledExecutorService`, rescanning every 60 seconds
and publishing a new `StatSnapshot`.

**It parses the stat JSON itself** — `{"stats": {"<typeId>": {"<valueId>": n}}}`
— straight into `Object2IntMap<StatKey>`. It does **not** construct
`ServerStatHandler` and does **not** call `getOrCreateStat`, per the threading
constraint. The keys are raw identifier strings; no registry lookup is needed
to read a value, only to display one.

The cost of hand-parsing is that we lose vanilla's DataFixer pass, so a stats
file last written by a pre-1.13 client would use legacy keys and read as zero.
Accepted: this mod targets 1.20.1 worlds, and any such file is repaired by
vanilla the moment that player next logs in.

Rescans are **mtime-gated** — an unchanged file is not re-read and its parsed
map is carried into the new snapshot. Files belonging to **currently online**
players are skipped entirely: vanilla rewrites them on every autosave so their
mtime churns, and `StatQuery` overrides them with live values anyway.

Advancement counting, per file: parse as a GSON `JsonObject`, skip the
top-level `"DataVersion"` integer, and count entries whose value is a
`JsonObject` with `"done": true` and whose key is in the **countable id set**.

That countable set is computed **on the server thread**, not the scanner
thread — `ServerAdvancementLoader` swaps its internal `manager` field during
`/reload` with no synchronization, so reading it off-thread is a race.

It is cached as a **pair**, because the two consumers need different forms: the
scanner matches JSON keys and needs identifiers, while `StatQuery`'s online path
calls `getProgress(Advancement)` and needs the objects:

```java
List<Advancement> countableAdvancements;  // display != null, ~110 entries
Set<Identifier> countableIds;             // their ids
```

Both are rebuilt together and published as immutables, once at
`SERVER_STARTED` and again on `ServerLifecycleEvents.END_DATA_PACK_RELOAD`
(signature `(MinecraftServer, LifecycledResourceManager, boolean success)` in
fabric-lifecycle-events-v1, which ships in this project's fabric-api 0.92.2).
The identifier set is handed to each scan job.

Lifecycle: started on `SERVER_STARTED`, shut down on `SERVER_STOPPING` with a
bounded `awaitTermination` so a hung scan cannot hold the server open.

Failure policy: a malformed or unreadable file logs once at WARN and is skipped
for that scan; it never aborts the pass.

### `com.statsboard.stat.StatQuery`

```java
public static List<LeaderboardEntry> topFor(MinecraftServer server, StatKey key, int limit)
```

Server thread only. Reads the snapshot, then **overlays online players with
live values** — vanilla flushes to disk only every few minutes, so a snapshot
value for an online player is stale by construction:

- real stat, online: `serverPlayer.getStatHandler().getStat(resolved)`
- real stat, offline: `snapshot.values.getOrDefault(uuid, empty).getInt(key)`
- advancements, online: count `countableAdvancements` (~110) via
  `player.getAdvancementTracker().getProgress(adv).isDone()`
- advancements, offline: `snapshot.advancementCounts.getOrDefault(uuid, 0)`

The "countable list only" restriction on the online path matters beyond
performance: `PlayerAdvancementTracker.getProgress` is not a pure read — it
inserts a fresh `AdvancementProgress` for any advancement not already present.
Iterating all 1271 every 5 seconds per online player would permanently balloon
every player's progress map. Iterating 110 keeps it bounded to advancements
that are displayable anyway. (Those inserted entries never reach disk: `save()`
only serialises entries where `isAnyObtained()`.)

Then: drop values <= 0, sort descending, **truncate to `limit`, and only then
resolve names and skins.** That ordering is load-bearing, not tidiness:
`UserCache.getByUuid` mutates the entry's `lastAccessed` on every call, and
vanilla's `UserCache.save()` persists only the 1000 most-recently-accessed
entries. Resolving names for every historical player on every query would
reorder the server's own usercache so that it keeps "whoever the leaderboard
touched" instead of recent players — silently corrupting a vanilla data file.
Truncating first bounds this to at most 50 lookups per query.

Name resolution, in order: `server.getUserCache().getByUuid(uuid)` →
`PlayerProfileCache` → the first 8 characters of the UUID. `getByUuid` does no
expiry filtering and its in-memory map is a superset of `usercache.json`, so
there is nothing to gain by reading that file directly. A player who last
logged in long before the mod existed and has since fallen out of the cache
renders as a UUID stub with a default skin — a real and visible limitation of
"everyone who has ever played", accepted rather than solved.

**Per-cycle memoisation.** A single call is O(players), but it is invoked once
per *column* per *block*, so five boards of three columns is fifteen full
sorts every 5 seconds. `StatQuery` therefore memoises results by `StatKey` for
the duration of one server tick, so boards showing the same stat — and the two
columns of the default layout across every board on the server — cost one pass
between them. The memo is cleared at the end of each tick; nothing is cached
across ticks, so values stay as fresh as the refresh interval implies.

### `PlayerProfileCache` (was `StatsManager`)

Shrinks to a name + skin-texture cache, still persisted as `statsboard.json` in
the world root, still populated from `ServerPlayConnectionEvents.JOIN`. The
`deaths` and `advancements` fields are removed from the serialized form; `load`
is `GSON.fromJson(reader, StatsData.class)` into a private POJO and GSON
ignores JSON members with no matching field, so **old worlds load without a
migration step** (verified against the existing code). `PlayerStats` reduces to
the two skin strings.

The 5-minute autosave tick in `StatsboardMod` is **deleted**: once only
`recordPlayerProfile` can set the dirty flag, and `JOIN` already saves
immediately, it has nothing left to do.

### Deletions

- `mixin/PlayerAdvancementTrackerMixin`
- `mixin/PlayerAdvancementTrackerAccessor`
- their two entries in `statsboard.mixins.json`
- the `ServerLivingEntityEvents.AFTER_DEATH` handler and the autosave tick in
  `StatsboardMod`
- `StatsManager.recordDeath`, `recordAdvancement`, `topDeaths`, `topAdvancements`
- `StatsboardNetworking.LEADERBOARD_CHANNEL` and its two-list wire format

`PlayerEntityAccessor` and its mixin entry stay — used by the renderers, not by
stat tracking.

### Hologram block

`LeaderboardBlockEntity` gains `List<StatKey> columns`, sized 1 to 3,
defaulting to `[StatKey.DEATHS, StatKey.ADVANCEMENTS]` — so a freshly placed
block looks exactly as it does today. Its per-column cached top entry (name,
value, uuid, skin value, skin signature) replaces the hardcoded `topDeath*` /
`topAdv*` field groups, and is **initialised explicitly to `"-"` / 0** rather
than relying on `nbt.getString` defaulting to `""`, so a block renders sanely
in the up-to-5s window before its first refresh.

`Columns` is an NBT list of compounds, each the `StatKey` NBT form
(`{"Type": "...", "Value": "..."}`). `readNbt` detects an old-version block via
`nbt.contains("Columns", NbtElement.LIST_TYPE)` and falls back to the default
pair; an element that fails `StatKey.fromNbt` is dropped, and if that empties
the list the default pair is used.

**Refreshes are staggered.** `serverTick` currently starts every block at
`ticksUntilRefresh = 0`, so after a chunk-load burst every board on the server
refreshes on the same tick. Seed it instead from
`Math.floorMod(pos.hashCode(), REFRESH_INTERVAL_TICKS)`.

Note the sync cost: the block entity NBT is sent in full on every
`markDirty()`, and each column carries a base64 skin value (~1KB) plus
signature (~700B). Three columns is ~5KB — and that is **per tracking player**,
since `markDirty` routes through `world.updateListeners` to everyone tracking
the chunk, and `toInitialChunkDataNbt` sends the same payload again to every
player on chunk load. Refreshes are 5s apart and only fire when a value
actually changed. This is the reason columns are capped at 3.

Nothing in vanilla calls `markDirty` on this block entity — it has no
inventory, no comparator output, and no `onStateReplaced` path — so the 5s
refresh is the only trigger.

**Opening the picker.** Right-clicking the block works, but is not sufficient
on its own: the block's hitbox is a 4×1×4 nub at its base
(`Block.createCuboidShape(6, 0, 6, 10, 1, 10)`) while the hologram figures
stand metres away, so a player aiming at the hologram hits nothing. Two entry
points:

1. `LeaderboardBlock.onUse` on the nub itself.
2. **Right-clicking with the Leaderboard Wand near an existing leaderboard
   opens the picker for the nearest one** instead of placing a new one. This is
   the discoverable path — you placed it with the wand, you edit it with the
   wand.

Entry point 2 must be implemented in **both** `LeaderboardStickItem.use` and
`useOnBlock`, and `use` is the one that matters. Aiming at a hologram figure
standing over open ground hits no block at all, so the raycast misses and the
client sends `PlayerInteractItemC2SPacket`, which routes to `Item.use` — never
to `useOnBlock`. Implementing only `useOnBlock` would leave the exact gesture
this feature exists to support doing nothing. `useOnBlock` still needs the same
interception so it opens the picker rather than placing a second block.

The proximity rule, stated precisely to avoid a placement trap:

- the radius is **3 blocks**, measured from the **would-be placement position**
  (not from the player), searching the nearest `LeaderboardBlockEntity`
- **sneak-right-click always places**, never opens the picker

Without that sneak escape hatch the rule would be a trap: a three-column board
spans 9 blocks and player reach is under 5, so a builder laying out a row of
boards could never place the second one where they wanted it.

`onUse` fires on **both** logical sides. The client call returns
`ActionResult.SUCCESS` for the arm swing and does nothing else; all packet work
sits behind `!world.isClient`.

Server-side it sends S2C `open_picker` with the block pos and current columns —
but only when `ServerPlayNetworking.canSend(player, OPEN_PICKER)` is true. A
vanilla client without the mod silently drops unknown channels and would see
nothing happen, so that branch instead sends an action-bar message (not chat,
so repeated pokes do not spam the log) reading roughly *"Install Leaderstats to
change this board, or use /leaderboard"*. Sent at most once per player per 5
seconds.

Choosing a stat sends C2S `set_block_stat {BlockPos, int columnIndex, StatKey}`.
**Server-side validation is mandatory** — it is a client-controlled packet:

- the block at `pos` must be a `LeaderboardBlockEntity`, else ignore
- the player must be within 8 blocks of `pos`, else ignore
- `columnIndex` must be within `[0, columns.size())`, else ignore
- `key.isValid()` must hold, or the key must be `ADVANCEMENTS`, else ignore —
  using `getOrEmpty`, so a removed-mod stat id is actually rejected instead of
  silently becoming `minecraft:air`

No operator permission gate: any player can already place and break these
blocks, so gating retargeting would be inconsistent.

`LeaderboardBlockEntityRenderer` loops over the columns instead of the
hardcoded `DEATH_X = -1.0` / `ADV_X = 2.0`. Column *i* of *n* sits at
`x = (i - (n - 1) / 2.0) * 3.0 + 0.5` — one column centred on the block, two at
±1.5, three at -3 / 0 / +3. Labels use `StatKey.displayName()` and
`StatValueFormatter.format`.

### GUI

`LeaderboardScreen` renders **one** stat at a time — the existing list panel on
the left and podium on the right, unchanged in look. The
`LeaderboardScreen(List, List)` constructor and the `Tab.DEATHS` /
`Tab.ADVANCEMENTS` enum are replaced by a single entry list plus the active
`StatKey`. It gains a "Change stat…" button opening `StatPickerScreen`, plus
Deaths and Advancements quick buttons for the common cases.

Selecting a stat sends C2S `request_board`; the screen re-renders when
`board_data` arrives. The empty case — a stat nobody has a nonzero value for,
now much easier to reach — uses the existing "No data yet." treatment.

### `com.statsboard.gui.StatPickerScreen`

Three category tabs mirroring vanilla, plus a search box:

- **General** — `Registries.CUSTOM_STAT`, 75 entries
- **Items** — `Registries.BLOCK` × `{mined}` (~1060) and `Registries.ITEM` ×
  `{crafted, used, broken, picked_up, dropped}` (~6600), ~7700 rows
- **Mobs** — `Registries.ENTITY_TYPE` × `{killed, killed_by}`, ~250 rows

Rows are built **directly from the registries as
`(StatKey, Text displayName, String lowercasedSearchText)`** — the picker never
calls `resolve()`, both because it runs on the client (threading constraint)
and because resolving 8000 keys would instantiate 8000 `Stat` objects that
vanilla creates lazily for a handful.

The row list is built once into a static cache on first open. The lowercased
search string is precomputed at build time, so a keystroke is 8000 substring
tests on cached strings rather than 8000 `Text.getString().toLowerCase()`
calls.

Opened from two contexts — the GUI's "Change stat…" button and a block or wand
right-click — so it takes a `Consumer<StatKey>` callback and a parent `Screen`
to return to.

### Networking

The single existing S2C channel is **replaced**, not extended:

| Direction | Channel | Payload |
|---|---|---|
| C2S | `statsboard:request_board` | `StatKey`, `int limit` |
| S2C | `statsboard:board_data` | `StatKey`, `boolean openScreen`, `List<LeaderboardEntry>` |
| C2S | `statsboard:set_block_stat` | `BlockPos`, `int columnIndex`, `StatKey` |
| S2C | `statsboard:open_picker` | `BlockPos`, `List<StatKey>` |

`openScreen` disambiguates the two ways `board_data` arrives: true for the
`/showleaderboard` case (open a fresh `LeaderboardScreen`), false for the
`request_board` case. For the `false` case the client applies the packet only
if **both**: `MinecraftClient.currentScreen` is a `LeaderboardScreen`, **and**
the packet's `StatKey` equals that screen's pending key. The second condition
discards stale replies — there is no request id, so a player who changes stat
twice in quick succession could otherwise have the first reply land after the
second.

`request_board` clamps `limit` server-side to `[1, 50]` rather than trusting the
client, and validates the `StatKey` with `isValid()`. `LeaderboardEntry` is
unchanged — the client formats the raw int through `StatValueFormatter` using
the `StatKey` it received alongside the entries.

### Commands

`/leaderboard` keeps its existing literal subcommands so command blocks,
macros and muscle memory do not break, and gains the general form:

- `/leaderboard deaths [count]` — unchanged, maps to `StatKey.DEATHS`
- `/leaderboard advancements [count]` — unchanged, maps to `StatKey.ADVANCEMENTS`
- `/leaderboard stat <stat_type> <stat> [count]` — the general form
- `/showleaderboard [stat_type] [stat]` — opens the GUI, defaulting to deaths

Both identifier arguments are `IdentifierArgumentType` with suggestion
providers: `stat_type` suggests `Registries.STAT_TYPE` ids plus
`statsboard:special`; `stat` suggests the ids of the chosen type's registry,
or the single id `statsboard:advancements` when the type is
`statsboard:special`. The executor treats `statsboard:special` /
`statsboard:advancements` as `StatKey.ADVANCEMENTS` and rejects any other value
under that type. An identifier that fails `isValid()` produces a clear command
error rather than failing silently or resolving to `minecraft:air`.

Plain-text output formats through `StatValueFormatter`, never `Stat.format`.
The raw, unformatted value is what crosses the wire in `LeaderboardEntry.count`
— ticks for time stats, centimetres for distance stats — and formatting happens
only at the point of display.

### Resources

- `assets/statsboard/lang/en_us.json` gains keys for: the picker screen title,
  its three category tabs, the search box placeholder, the "Change stat…"
  button, the Deaths/Advancements quick buttons, the `statsboard:advancements`
  pseudo-stat name, and the vanilla-client action-bar hint.
- `fabric.mod.json`'s description ("tracks and displays the top players for
  deaths and advancements") is rewritten.

## Testing

The repository has no test harness and Loom does not make unit-testing
registry-dependent code cheap, so verification is a live playtest: one
dedicated server and two clients, requiring new `runs {}` entries in
`build.gradle` with separate run directories and distinct offline usernames,
plus `online-mode=false` and an accepted EULA in the server run directory.

What the playtest must confirm:

1. A fresh leaderboard block still shows deaths and advancements, as before.
2. Right-clicking the block's base nub opens the picker, **and** right-clicking
   the wand while aiming at a hologram figure over open ground opens it too —
   that second gesture is the one the whole entry-point design exists for, and
   it is the one that fails if only `useOnBlock` is implemented. Choosing a
   stat retargets that column and the hologram updates within one refresh
   interval.
3. Sneak-right-clicking the wand next to an existing board places a second
   board rather than opening the picker.
4. Picker search finds an item stat (e.g. "diamond ore" under mined) and a mob
   stat, and choosing it yields a correct board.
5. Formatting matches the player's own vanilla Statistics screen **exactly**,
   checked side by side: a distance stat, play time, a damage stat, and a plain
   count. Two decimal places, single largest time unit.
6. Two players produce correctly *ordered* boards, and an offline player's
   values persist and still appear after they disconnect.
7. **The advancement count is correct** — matches the player's real total and
   excludes recipe advancements.
8. A world with a pre-existing `statsboard.json` from the old version loads
   without error.
9. No `ConcurrentModificationException` or corrupted-stat symptoms after a
   sustained session with both clients actively mining and crafting while the
   picker is open — the threading constraint holding in practice.

## Out of scope

- Backfilling or migrating the old `deaths` / `advancements` counters.
- Per-block podium depth (top 3 per column); each column shows its top player,
  as today.
- Evicting long-inactive players from the snapshot.
- Any stat source other than vanilla's.

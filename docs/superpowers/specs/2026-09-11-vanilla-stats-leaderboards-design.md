# Vanilla statistics as selectable leaderboard stats

Date: 2026-09-11
Status: approved for planning

## Problem

Leaderstats tracks exactly two numbers of its own — deaths and advancements —
in `statsboard.json`, and both the `/leaderboard` command and the hologram
block hardcode those two. Minecraft already tracks hundreds of per-player
statistics (the vanilla Statistics screen: General, Items, Mobs) and persists
them per player in the world save. Players should be able to build a
leaderboard for any of them.

## Decisions

These were settled before design and are not open questions:

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

## Verified API surface (Yarn 1.20.1+build.10)

Confirmed by disassembling the remapped Minecraft jar. The design depends on
these signatures:

```java
// net.minecraft.stat.StatHandler
public int getStat(Stat<?> stat);
public <T> int getStat(StatType<T> type, T value);

// net.minecraft.stat.ServerStatHandler extends StatHandler
public ServerStatHandler(MinecraftServer server, File file);  // parses the file in the ctor

// net.minecraft.stat.StatType<T>
public Stat<T> getOrCreateStat(T value);
public Registry<T> getRegistry();
public String getTranslationKey();
public Text getName();

// net.minecraft.stat.Stat<T> extends ScoreboardCriterion
public StatType<T> getType();
public T getValue();
public String format(int value);

// net.minecraft.registry.Registries
public static final Registry<StatType<?>> STAT_TYPE;
public static final Registry<Identifier> CUSTOM_STAT;

// net.minecraft.util.WorldSavePath
public static final WorldSavePath STATS;        // "stats"
public static final WorldSavePath ADVANCEMENTS; // "advancements"
```

Two facts that shape the design:

- `ServerStatHandler`'s constructor loads and datafixes the file, and `getStat`
  is public. An offline player's stats can therefore be read by constructing
  one `ServerStatHandler` per save file. **No hand-rolled JSON parsing or
  datafixer handling is required.**
- `Stats.MINED` is `StatType<Block>`, not `StatType<Item>`. The picker must
  enumerate `Registries.BLOCK` for `mined` and `Registries.ITEM` for
  `crafted` / `used` / `broken` / `picked_up` / `dropped`.

## Architecture

### `com.statsboard.stat.StatKey`

```java
public record StatKey(Identifier typeId, Identifier valueId)
```

The single identity for "which stat is this leaderboard about". Responsible
for:

- `Optional<Stat<?>> resolve()` — `Registries.STAT_TYPE.get(typeId)`, then
  `type.getRegistry().get(valueId)`, then `type.getOrCreateStat(value)`.
  Returns empty for an unknown type or value (e.g. a stat from a mod that has
  since been removed); every caller must handle empty rather than throw.
- `Text displayName()` — matches what the vanilla Statistics screen shows. For
  `minecraft:custom`, `Text.translatable("stat." + valueId.toString().replace(':', '.'))`.
  For every other type, `Text.translatable(statType.getTranslationKey())`
  followed by the value's own name.
- `String format(int value)` — delegates to `Stat.format(int)`, so distances
  render as `"1.2 km"` and play time as `"4h 12m"`. Falls back to
  `Integer.toString` when `resolve()` is empty.
- NBT codec: `writeNbt(NbtCompound)` / `fromNbt(NbtCompound)`.
- Packet codec: `write(PacketByteBuf)` / `read(PacketByteBuf)` — two
  identifiers.

Constants: `StatKey.DEATHS` (`minecraft:custom` / `minecraft:deaths`) and
`StatKey.ADVANCEMENTS` (`statsboard:special` / `statsboard:advancements`).

`ADVANCEMENTS` is a **pseudo-stat**: it does not resolve to a vanilla `Stat`.
It is the one special case in the system, and it is handled in exactly two
places — the value source (`StatQuery`) and `displayName()` / `format()`.
Everywhere else it flows through the same code path as a real stat.

### `com.statsboard.stat.StatSnapshot`

Immutable value object published by the scanner and read by queries:

```java
Map<UUID, ServerStatHandler> handlers;   // parsed from world/stats/*.json
Map<UUID, Integer> advancementCounts;    // parsed from world/advancements/*.json
```

Its key set is "every player who has ever played on this world", derived from
the stats directory filenames.

### `com.statsboard.stat.StatScanner`

Owns a single-thread `ScheduledExecutorService`. Every 60 seconds it rescans
the two save directories and publishes a new `StatSnapshot` to a `volatile`
field. Never touches the server thread; never blocks it.

Rescans are **mtime-gated**: a file whose last-modified time is unchanged since
the previous scan is not re-read, and its previously parsed `ServerStatHandler`
or count is carried into the new snapshot. This matters — a busy player's
advancements file is a few hundred KB.

Advancement counting, per file: parse as a GSON `JsonObject`; for each entry
whose value is a `JsonObject` with `"done": true`, resolve the key as an
`Identifier` and keep it only if `server.getAdvancementLoader().get(id)` is
non-null **and** `getDisplay() != null`. That display filter is essential:
recipe advancements are stored in the same file and would otherwise inflate
every count by roughly 1100. Skip the top-level `"DataVersion"` integer.

Lifecycle: started on `SERVER_STARTED`, shut down on `SERVER_STOPPING` with a
bounded `awaitTermination` so a hung scan cannot hold the server open.

Failure policy: a malformed or unreadable file logs once at WARN and is skipped
for that scan; it never aborts the whole pass.

### `com.statsboard.stat.StatQuery`

```java
public static List<LeaderboardEntry> topFor(MinecraftServer server, StatKey key, int limit)
```

Runs on the server thread. Reads the current snapshot, then **overlays online
players with live values**, because vanilla only flushes stats and advancements
to disk every few minutes, so a snapshot value for an online player is stale by
construction:

- real stat, online: `player.getStatHandler().getStat(stat)`
- real stat, offline: `snapshot.handlers.get(uuid).getStat(stat)`
- advancements, online: count `server.getAdvancementLoader().getAdvancements()`
  entries with a non-null display where
  `player.getAdvancementTracker().getProgress(adv).isDone()`
- advancements, offline: `snapshot.advancementCounts.get(uuid)`

Then: drop zero and negative values, sort descending, truncate to `limit`.
Names come from `server.getUserCache().getByUuid(uuid)`, falling back to
`PlayerProfileCache`, falling back to the first 8 characters of the UUID. Skins
come from `PlayerProfileCache`; a player who has never joined since the mod was
installed has no cached skin and renders with the default skin.

Cost is O(players) per call, and it is called once per GUI open and once per
block refresh interval (5s), so no caching layer is warranted.

### `PlayerProfileCache` (was `StatsManager`)

Shrinks to a name + skin-texture cache, still persisted as `statsboard.json` in
the world root, still populated from `ServerPlayConnectionEvents.JOIN`. The
`deaths` and `advancements` fields are removed from the serialized form; GSON
silently ignores them when reading an existing file, so **old worlds load
without a migration step or a crash**. `PlayerStats` reduces to the two skin
strings.

### Deletions

- `mixin/PlayerAdvancementTrackerMixin`
- `mixin/PlayerAdvancementTrackerAccessor`
- their two entries in `statsboard.mixins.json`
- the `ServerLivingEntityEvents.AFTER_DEATH` handler in `StatsboardMod`
- `StatsManager.recordDeath`, `recordAdvancement`, `topDeaths`, `topAdvancements`

`PlayerEntityAccessor` and its mixin entry stay — they are used by the
renderers, not by stat tracking.

### Hologram block

`LeaderboardBlockEntity` gains `List<StatKey> columns`, sized 1 to 3,
defaulting to `[StatKey.DEATHS, StatKey.ADVANCEMENTS]` — so a freshly placed
block looks exactly as it does today. Its per-column cached top entry (name,
value, uuid, skin value, skin signature) replaces the current pair of hardcoded
`topDeath*` / `topAdv*` field groups. NBT read/write handles the list;
`readNbt` tolerates a block saved by the old version (no `Columns` tag) by
falling back to the default pair.

`LeaderboardBlock.onUse` sends S2C `open_picker` with the block pos and current
columns. The client opens `StatPickerScreen`; choosing a stat sends C2S
`set_block_stat {BlockPos, int columnIndex, StatKey}`.

**Server-side validation of `set_block_stat` is mandatory** — it is a
client-controlled packet:

- the block at `pos` must be a `LeaderboardBlockEntity`, else ignore
- the player must be within 8 blocks of `pos`, else ignore
- `columnIndex` must be within `[0, columns.size())`, else ignore
- the `StatKey` must either `resolve()` or be `ADVANCEMENTS`, else ignore

No operator permission gate: any player can already place and break these
blocks, so gating retargeting would be inconsistent.

`onUse` fires server-side only, for a `ServerPlayerEntity`, and returns
`ActionResult.SUCCESS`. A vanilla client without the mod silently drops the
`open_picker` packet and sees nothing happen, so that path must also send the
player a chat hint naming `/leaderboard` as the client-free alternative. Use
`ServerPlayNetworking.canSend(player, OPEN_PICKER)` to decide which of the two
to do.

`LeaderboardBlockEntityRenderer` loops over the columns, spacing them evenly
about the block, instead of the two hardcoded `DEATH_X` / `ADV_X` offsets. Each
column's label uses `StatKey.displayName()` and `StatKey.format(value)`.

### GUI

`LeaderboardScreen` renders **one** stat at a time — the existing list panel on
the left and podium on the right, unchanged in look. It gains a "Change stat…"
button that opens `StatPickerScreen`, plus Deaths and Advancements quick tabs
for the common cases. Selecting a stat sends C2S `request_board` and the screen
re-renders when `board_data` arrives.

The screen must handle the empty case it can now reach much more easily — a
stat nobody has a nonzero value for — with the existing "No data yet."
treatment.

### `com.statsboard.gui.StatPickerScreen`

Three category tabs mirroring vanilla, plus a search box filtering on display
name:

- **General** — `Registries.CUSTOM_STAT`, ~75 entries
- **Items** — `Registries.BLOCK` × `{mined}` and `Registries.ITEM` ×
  `{crafted, used, broken, picked_up, dropped}`, ~6000 rows
- **Mobs** — `Registries.ENTITY_TYPE` × `{killed, killed_by}`, ~300 rows

The full row list is built once into a static cache on first open, since
building it walks several registries. Rows are `(StatKey, Text displayName)`;
search is a case-insensitive substring match on the resolved display string.

Opened from two contexts — the GUI's "Change stat…" button and a block
right-click — so it takes a `Consumer<StatKey>` callback and a parent `Screen`
to return to.

### Networking

`StatsboardNetworking` grows from one channel to four:

| Direction | Channel | Payload |
|---|---|---|
| C2S | `statsboard:request_board` | `StatKey`, `int limit` |
| S2C | `statsboard:board_data` | `StatKey`, `List<LeaderboardEntry>` |
| C2S | `statsboard:set_block_stat` | `BlockPos`, `int columnIndex`, `StatKey` |
| S2C | `statsboard:open_picker` | `BlockPos`, `List<StatKey>` |

`request_board` must clamp `limit` server-side to `[1, 50]` rather than trust
the client. `LeaderboardEntry` is unchanged — the client formats the raw int
through the `StatKey` it received alongside the entries.

### Commands

- `/leaderboard <stat_type> <stat> [count]` — plain-text output, no client mod
  required. Both arguments are `IdentifierArgumentType` with suggestion
  providers: `stat_type` suggests `Registries.STAT_TYPE` ids plus
  `statsboard:special`; `stat` suggests the ids of the chosen type's registry.
- `/showleaderboard [stat_type] [stat]` — opens the GUI, defaulting to deaths.

Both report a clear error for a stat identifier that does not resolve, rather
than failing silently.

## Testing

The repository has no test harness and Loom does not make unit-testing
registry-dependent code cheap, so verification is a live playtest: one
dedicated server and two clients, which requires new `runs {}` entries in
`build.gradle` with separate run directories and distinct offline usernames,
plus `online-mode=false` and an accepted EULA in the server run directory.

What the playtest must confirm:

1. A fresh leaderboard block still shows deaths and advancements, as before.
2. Right-clicking a block opens the picker; choosing a stat retargets that
   column and the hologram updates within one refresh interval.
3. Picker search finds an item stat (e.g. "diamond ore" under mined) and a mob
   stat, and choosing it yields a correct board.
4. Formatting is right: a distance stat reads in km/m, play time reads as a
   duration, plain counts read as integers.
5. Two players produce correctly *ordered* boards, and an offline player's
   values persist and still appear after they disconnect.
6. **The advancement scan is correct** — the one assumption in this design that
   is not verified from source. The count must match the player's real
   advancement total and must not include recipe advancements.
7. A world with a pre-existing `statsboard.json` from the old version loads
   without error.

## Out of scope

- Backfilling or migrating the old `deaths` / `advancements` counters.
- Per-block podium depth (top 3 per column); each column shows its top player,
  as today.
- Any stat source other than vanilla's.

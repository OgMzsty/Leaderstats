# Statsboard — Fabric mod for Minecraft 1.20.1

Tracks every player's **deaths** and **advancements** on the server and lets
anyone view a leaderboard with a command.

## Commands

| Command | Description |
|---|---|
| `/leaderboard deaths [count]` | Text leaderboard for deaths in chat (default top 10, max 50) |
| `/leaderboard advancements [count]` | Text leaderboard for advancements in chat |
| `/showleaderboard` | Opens a GUI with **Deaths** / **Advancements** tabs — a scrollable stat list on the left, and a podium showing the top 3 players' skins on the right |

No permission node is set, so by default any player can run these (op-only
games can restrict them in server config if you want them staff-only).

Data is stored in `<world save>/statsboard.json` and survives restarts.

### About the GUI

`/showleaderboard` works over a small custom network packet: the server
gathers the current top 50 for both stats and sends them to the requesting
player, and the client mod opens the screen. This means **the mod must be
installed on the client as well as the server** (the plain `/leaderboard`
text command still works with a server-only install, since it's just a chat
message — only the GUI needs the client-side piece).

The podium shows each top-3 player's real skin if they're currently online
(fetched from the client's tab-list cache); if they're offline it falls back
to the default Steve/Alex skin, since Minecraft doesn't let a client look up
an arbitrary offline player's skin without them being in the tab list.

## How it works

- A mixin on `LivingEntity#onDeath` increments a player's death counter
  whenever a real server player dies (any cause).
- A mixin on `PlayerAdvancementTracker#grantCriterion` checks, after each
  criterion grant, whether the advancement is now fully complete — if so it
  increments that player's advancement counter. Hidden/root advancements
  (the invisible tracking nodes every unlock tree starts from) are excluded
  since they have no display info.
- Stats are cached in memory, autosaved every 5 minutes, and always flushed
  on server shutdown.

## Building the mod

This is a standard Fabric Loom Gradle project targeting Minecraft 1.20.1,
Fabric Loader 0.15.11, Yarn mappings `1.20.1+build.10`, and Fabric API
`0.92.2+1.20.1`. I can't run Gradle in this sandbox (no network access to
Fabric/Mojang's Maven repos), so you'll need to build it on your own machine:

1. Install **JDK 17** (Fabric 1.20.1 requires exactly Java 17).
2. Unzip this project.
3. From the project folder, run:
   - Windows: `gradlew.bat build`
   - macOS/Linux: `./gradlew build`

   (If you don't have a Gradle wrapper jar, run `gradle wrapper` once with any
   local Gradle install, or open the folder in IntelliJ IDEA with the Fabric
   plugin support and let it sync — that will fetch the wrapper for you.)
4. The compiled mod will be at `build/libs/statsboard-1.0.0.jar`.

## Installing

Install on **both** the server and every client that wants to use
`/showleaderboard`:

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.20.1.
2. Download **Fabric API** for 1.20.1 (matching version `0.92.2+1.20.1` or
   newer) from Modrinth/CurseForge and drop the jar into `mods/`.
3. Drop `statsboard-1.0.0.jar` into the same `mods/` folder.
4. Start the server/client.

If a player only has `/leaderboard` (not `/showleaderboard`) available, or
the GUI never opens, they're missing the mod on their client.

## Customizing

- **Autosave interval**: change `AUTOSAVE_INTERVAL_TICKS` in
  `StatsboardMod.java`.
- **Default leaderboard length**: change the `10` literals in
  `LeaderboardCommand.java`, or `GUI_LIST_SIZE` in `ShowLeaderboardCommand.java`.
- **GUI colors/layout/row height**: `LeaderboardScreen.java` — panel bounds,
  podium pedestal heights/colors, and `ROW_HEIGHT` are all top-level
  constants or easy to find near the top of the render methods.
- **Track more stats** (playtime, mob kills, etc.): add a field to
  `PlayerStats.java`, a `recordX(...)` method to `StatsManager.java`, a mixin
  hook for the relevant event, a new tab in `LeaderboardScreen.java`, and a
  branch in `LeaderboardCommand.java` / `ShowLeaderboardCommand.java`.

## A note on the GUI code

I wrote and reasoned through `LeaderboardScreen.java` carefully against what
I know of the 1.20.1 client rendering API (`DrawContext`, skin texture
lookups via `PlayerListEntry#getSkinTextures()`, etc.), but I don't have
network access in this environment to actually compile it against the real
Yarn mappings. Client GUI/rendering code is the part of Fabric modding most
likely to have small signature differences between mapping builds. If
`./gradlew build` fails, it's most likely to point at one of:

- `entry.getSkinTextures().texture()` in `getSkinTexture(...)`
- `context.drawTexture(...)` calls in `drawPlayerHead(...)`
- `context.drawCenteredTextWithShadow(...)` / `drawTextWithShadow(...)` overloads

These are usually one-line fixes (a renamed method or a swapped argument) —
if you hit one, paste me the error and I'll correct it.

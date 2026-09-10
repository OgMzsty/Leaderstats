<div align="center">

<img src="img/logo.png" width="240" alt="logo">

### Death and advancement leaderboards, with the top two stood in your world.

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-62B47A?style=for-the-badge)
![Client and server](https://img.shields.io/badge/Client%20%2B%20server-2D6FE0?style=for-the-badge)
![Licence](https://img.shields.io/badge/licence-CC0--1.0-A42E2B?style=for-the-badge)

[<img alt="fabric" height="52" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/fabric_vector.svg">](https://fabricmc.net/)

[![Requires Fabric API](https://img.shields.io/badge/requires-Fabric%20API-1976D2?style=flat-square)](https://modrinth.com/mod/fabric-api)
[![Java 17](https://img.shields.io/badge/Java-17-E76F00?style=flat-square)](https://adoptium.net/)

</div>

## What does this mod do?

Counts every death and every completed advancement on the server.

Read the totals in chat with `/leaderboard`, or in the GUI that `/showleaderboard` and a
bound key both open. Put a board down in the world and the top two stand on it wearing
their own skins.

The counting is server side and survives restarts. The GUI and the world models are drawn
client side, so anyone who wants those needs the mod as well.

## Showcase

<div align="center">
<a href="https://www.youtube.com/watch?v=s5rUb3rtTJM"><img src="https://img.youtube.com/vi/s5rUb3rtTJM/hqdefault.jpg" width="480" alt="Leaderstats showcase video"></a>
</div>

## Commands

| Command | Does |
| --- | --- |
| `/leaderboard deaths [count]` | Deaths, in chat. Top 10 by default, 50 at most |
| `/leaderboard advancements [count]` | Advancements, same limits |
| `/showleaderboard` | Opens the GUI |

Nobody needs to be op. Set a permission node in your server config if you want that.

## The GUI

`/showleaderboard` pulls the current top 50 of both stats off the server and opens a screen
with them. Deaths and advancements are separate tabs, with the full list scrolling down the
left and the top three on a podium to the right, skins and all.

There is an **Open Leaderboard** keybind in Options, Controls too. It is unbound out of the
box so it cannot steal a key you already use. Bind it and it runs the same command.

## The board itself

Craft a **Leaderboard Wand** out of four iron ingots and right-click a face with it.

Two players appear where you pointed, full height, wearing their real skins:

- The one on the left has died more than anyone else on the server
- The one on the right has more advancements than anyone else

Their name and their count float above their head and turn to face you as you walk round.
Skins come from the profile stored with the stats rather than the tab list, so somebody who
has not logged in for a month still shows up as themselves.

Every five seconds the block checks the standings again, so the pair swap over on their own
while you watch. Die enough times and you will see yourself walk into first place.

What you actually planted is an invisible marker block. It has a small hitbox at the base,
so break that to take the board down.

## Install

Goes on the **server** and on every **client** that wants the GUI or the board. `/leaderboard`
on its own works fine with a server-only install, it is only chat text.

1. [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.20.1
2. [Fabric API](https://modrinth.com/mod/fabric-api) `0.92.2+1.20.1` or newer in `mods/`
3. This jar, same folder

If `/showleaderboard` does nothing for someone, they have not got the mod client side.

## Where the data lives

`<world save>/statsboard.json`. Held in memory, written every five minutes, and written
again on shutdown.

Deaths come off Fabric's `AFTER_DEATH` event and only count real server players. Any cause
counts. Advancements are counted by a mixin that looks, after each criterion is granted,
at whether the advancement has just been finished off. The invisible root nodes every tree
hangs from are skipped, they have no display info and nobody thinks of them as an
advancement.

## Building

Ordinary Fabric Loom project. Needs **JDK 17**, not 21.

```bash
./gradlew build
```

Jar comes out in `build/libs/`.

| | |
| --- | --- |
| Minecraft | `1.20.1` |
| Yarn mappings | `1.20.1+build.10` |
| Fabric Loader | `0.15.11` |
| Fabric API | `0.92.2+1.20.1` |

## Tweaking

| What | Where |
| --- | --- |
| Autosave interval | `AUTOSAVE_INTERVAL_TICKS`, `StatsboardMod.java` |
| How often the board refreshes | `REFRESH_INTERVAL_TICKS`, `LeaderboardBlockEntity.java` |
| How far apart the two models stand | `DEATH_X`, `ADV_X`, `HEAD_TEXT_Y`, `LeaderboardBlockEntityRenderer.java` |
| Default chat leaderboard length | `LeaderboardCommand.java` |
| How many rows the GUI asks for | `GUI_LIST_SIZE`, `ShowLeaderboardCommand.java` |
| GUI colours, layout, row height | `LeaderboardScreen.java` |

To track something else: a field on `PlayerStats`, a `recordX(...)` on `StatsManager`, a
hook for whatever event fires it, a tab in `LeaderboardScreen`, and a branch in each
command.

## Links

- [Showcase video](https://www.youtube.com/watch?v=s5rUb3rtTJM)
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Bug reports](https://github.com/OgMzsty/Leaderstats/issues)

---

<div align="center">
<sub>

NOT AN OFFICIAL MINECRAFT SERVICE. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.

</sub>
</div>

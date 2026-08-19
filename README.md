# DupeXvCore

The core plugin for [DupeXv](https://dupexv.org), built for Folia 26.2.

This is the glue that holds the server together. It started as a `/tpa` and `/spawn` pair, then homes got added, and over time everything that didn't fit anywhere else ended up here: the tab list, staff tools, account linking, and a couple of background services that mostly only make sense if you run the DupeXv network.

## Install

Drop `DupeXvCore.jar` into `plugins` and restart. Config and language files are written on first boot, so there's no setup step.

Optional soft-dependencies:

- **LuckPerms** — name tag prefixes/suffixes in the tab list come from here.
- **packetevents** — only needed if you want the chat report popup stripped from the packet stream.

## Commands

Player commands:

| Command | Aliases | What it does |
| --- | --- | --- |
| `/tpa <player>` | `tpask`, `call` | Ask to teleport to a player |
| `/tpa-here <player>` | `tpahere`, `tphere`, `tpah` | Ask a player to teleport to you |
| `/tpa-cancel [player]` | `tpacancel`, `tpcancel`, `tpac` | Cancel an outgoing request |
| `/tpa-accept [player]` | `tpaccept`, `tpyes`, `tpayes` | Accept a request |
| `/tpa-deny [player]` | `tpadeny`, `tpdeny`, `tpno`, `tpano` | Deny a request |
| `/spawn` | — | Teleport back to spawn |
| `/home [name]` | `homes` | Teleport to a home, or open the homes menu |
| `/sethome <name>` | — | Set a home at your current spot |
| `/delhome <name>` | — | Delete a home |
| `/link` | — | Link your Minecraft account to dupexv.org |

Staff commands (op by default):

| Command | Aliases | What it does |
| --- | --- | --- |
| `/invsee <player>` | `openinv`, `ise` | Browse a player's inventory |
| `/endersee <player>` | `enderchest`, `echest` | Browse a player's ender chest |
| `/invclear <player>` | `clearinv` | Wipe a player's inventory |
| `/enderclear <player>` | `clearender` | Wipe a player's ender chest |

## Tab list

Custom tab list that replaces [TAB](https://www.spigotmc.org/resources/tab-1-5-1-20-x.57806/) on our server. It shows live TPS, online count and ping in the footer by default (MSPT, max players, health, coordinates and such are available as placeholders too), handles name tags through LuckPerms, and can force player collision off for everyone.

If the TAB plugin is still loaded, DupeXvCore will warn about it in the console and you should remove it — they both fight over the same scoreboard.

## Chat reports

Hides the "chat report" popup Vanilla shows players and stops the auto-kick when chat reporting gets triggered. Needs packetevents installed, otherwise it quietly does nothing and you'll just see a warning at startup.

## Region debug

A background service that samples what's actually loaded in each region (entities, tiles, that sort of thing) and posts it to the DupeXv API on a fixed interval. It's how we spot the chunk that's eating all the MSPT without teleporting around guessing. It's only useful if you're us, but it's harmless if you leave it on — set `regions.enabled: false` to turn it off.

## Permissions

- `dupexvcore.tpa`, `dupexvcore.spawn`, `dupexvcore.home`, `dupexvcore.link` — default everyone
- `dupexvcore.invsee`, `dupexvcore.endersee`, `dupexvcore.invclear`, `dupexvcore.enderclear` — default op
- `dupexvcore.home.max.2` — default everyone; `.max.3` through `.max.10` raise the cap

Warmup and cooldown values come from config unless the player has a numbered permission that overrides them. For example `dupexvcore.home.warmup.5` means a 5 second warmup, and `dupexvcore.tpa.cooldown.0` means no cooldown at all.

## Config

```yml
language: en

tpa:
  warmup: 5
  cooldown: 5
  timeout: 60

spawn:
  radius: 250
  world: world
  warmup: 30
  cooldown: 30

home:
  warmup: 10
  cooldown: 60
```

The rest of the options (tab, regions, website, chat-reports) are appended to the config automatically on first boot, so just open the generated file and you'll see everything the plugin understands. Messages live in `lang/en.yml`; missing keys get added on startup and keys you've already edited are left alone.

## Build

```
mvn package
```

Output lands in `target/DupeXvCore.jar`. You'll need Java 25 and Maven.

Old Skript homes can be migrated over with `scripts/import-homes.py`.

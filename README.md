# DupeXvCore

Folia 26.2 plugin for [DupeXv](https://dupexv.org).

Drop `DupeXvCore.jar` in `plugins` and restart.

## Commands

`/tpa <player>` (`/tpask`, `/call`)
`/tpa-here <player>` (`/tpahere`, `/tphere`, `/tpah`)
`/tpa-cancel [player]` (`/tpacancel`, `/tpcancel`, `/tpac`)
`/tpa-accept [player]` (`/tpaccept`, `/tpyes`, `/tpayes`)
`/tpa-deny [player]` (`/tpadeny`, `/tpdeny`, `/tpno`, `/tpano`)
`/spawn`
`/home [name]` (`/homes`)
`/sethome <name>`
`/delhome <name>`

`dupexvcore.tpa`, `dupexvcore.spawn`, and `dupexvcore.home` are given to everyone.
`dupexvcore.home.max.2` is the default cap. Higher caps are `dupexvcore.home.max.3` through `.max.10`.

Warmup and cooldown come from config unless the player has a numbered perm, e.g. `dupexvcore.home.warmup.5` or `dupexvcore.tpa.cooldown.0`.

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

Messages are in `lang/en.yml`. On startup, missing keys are added. Keys you already changed are not overwritten.

## Build

```
mvn package
```

Output: `target/DupeXvCore.jar`

Homes from the old Skript `variables.csv` can be imported with `scripts/import-homes.py`.

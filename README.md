# DupeXvCore

Folia 26.2 core plugin for [DupeXv](https://dupexv.org).

Put `DupeXvCore.jar` in `plugins` and restart the server.

## Commands

- `/tpa <player>` — `/tpask`, `/call`
- `/tpa-here <player>` — `/tpahere`, `/tphere`, `/tpah`
- `/tpa-cancel [player]` — `/tpacancel`, `/tpcancel`, `/tpac`
- `/tpa-accept [player]` — `/tpaccept`, `/tpyes`, `/tpayes`
- `/tpa-deny [player]` — `/tpadeny`, `/tpdeny`, `/tpno`, `/tpano`
- `/spawn`

Both `dupexvcore.tpa` and `dupexvcore.spawn` are given to everyone by default.

`/spawn` dumps you on a safe block within `spawn.radius` of `0,0`. `/tpa` has a warmup and a cooldown (5 seconds each unless you change them). Moving during the warmup cancels the teleport.

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
```

Chat text lives in `lang/en.yml`. Copy it to something like `lang/es.yml` and set `language: es` if you want another file.

## Build

```
mvn package
```

Jar ends up at `target/DupeXvCore.jar`.

# OraNotifier

Vote rewards for Minecraft servers listed on [Mcora](https://mcora.net). When someone votes for your server on Mcora, OraNotifier picks up the vote and runs the reward commands you set.

It works on:

| Platform | Jar |
| --- | --- |
| Paper (1.20.4+) | `OraNotifier-paper-<version>.jar` |
| Velocity | `OraNotifier-velocity-<version>.jar` |
| BungeeCord / Waterfall | `OraNotifier-bungee-<version>.jar` |
| Fabric | `OraNotifier-fabric-<version>.jar` |
| NeoForge | `OraNotifier-neoforge-<version>.jar` |

## Install

1. Download the jar for your platform from [Releases](../../releases/latest) and put it in `plugins/` (or `mods/` for Fabric and NeoForge).
2. Start the server once. It writes `config.properties` in the plugin's folder.
3. On mcora.net, open your server's edit page → 連結與通知 → OraNotifier → 產生 Token, and paste the token into `token=`.
4. Set your reward commands, then restart the server.

On a network, install it on each server with the same token and give each one its own name in the config. The full guide (in Traditional Chinese) is in the Mcora docs.

## Building

The in-game messages are translated on the Guildora translation site and live in the Guildora monorepo, so the jars are built from there, where this repository is the `plugins/oranotifier` submodule:

```bash
./gradlew build   # jars in */build/libs/
```

To build this repository on its own, point it at a copy of the translations: `./gradlew build -Pi18nDir=/path/to/packages/i18n`.

Needs Java 21 (server plugins, built for Java 17 servers) and Java 25 (Fabric and NeoForge); Gradle downloads them if missing.

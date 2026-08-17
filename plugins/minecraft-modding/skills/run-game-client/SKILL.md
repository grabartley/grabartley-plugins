---
name: run-game-client
description: Runs the Minecraft game client locally with the mod for manual testing. Use when asked to launch, run, or start the game client for testing in a Fabric mod repo.
---

# Run Game Client

Runs the Minecraft client with the mod loaded for manual testing.

## Config

Read per the dev-workflow `config` skill:
- `javaVersion`, default 21

## Quick Start

Ensure the configured Java version is active (`jenv local 21` or `sdk use java 21-amzn`), then:

```bash
./gradlew runClient
```

If the repo keeps recipe viewers (JEI/EMI) out of the dev client by default so their overlays stay out of screenshots, launch with them loaded only when testing that integration:

```bash
./gradlew runClient -Precipe_viewers=true
```

## Testing Commands

Resolve `<modid>` from `src/main/resources/fabric.mod.json`.

- `/gamemode creative` - access spawn eggs
- `/summon <modid>:<entity_id>` - spawn a mod entity directly (see the mod's entity registration class for IDs)
- `/locate biome minecraft:<biome>` - find biomes for spawn testing

## Post-Test Protocol

After running the game client for manual testing, always ask for human input before continuing with next steps.

## Hot Reload

Press **F3+T** to reload textures/models without restarting.

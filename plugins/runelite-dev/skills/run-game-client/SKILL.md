---
name: run-game-client
description: Runs the RuneLite dev client locally with the plugin for manual testing, including Jagex account login. Use when asked to launch, run, or start the game client for testing in a RuneLite plugin repo.
---

# Run Game Client

Runs the RuneLite dev client with the plugin loaded for manual testing.

## Config

Read per the dev-workflow `config` skill:
- `javaVersion`, default 17
- `repos.<slug>.runelite.runnerJarGlob`: shadow jar path pattern, e.g. `build/libs/myPlugin-*-all.jar`
- `repos.<slug>.runelite.clientLog`: log file path, e.g. `/tmp/runelite-client.log`

## Quick Start

Ensure Java 17 is active (`jenv local 17` or `sdk use java 17-amzn`), build the plugin, then launch via the plugin's runner entry point (the shadow jar's `Main-Class`), passing `--developer-mode` so the dev client logs in and exposes developer tooling:

```bash
./gradlew shadowJar
java -ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED -jar <runnerJarGlob> --developer-mode --debug 2>&1 | tee <clientLog>
```

The runner's `main` forwards these program arguments to `RuneLite.main`. `--developer-mode` belongs only with this launcher and is required for login to work; omit it and login fails. `--debug` is optional and turns on RuneLite debug-level logging, which pairs well with any debug toggle the plugin exposes.

**Always write the client output to the configured log file** (the `tee` above, or `> <clientLog> 2>&1 &` when launching in the background) so the logs can be read and grepped during and after testing.

The standard runner pattern calls `ExternalPluginManager.loadBuiltin(<Plugin>.class)` and starts RuneLite. You can also run that `main` directly from the IDE with the same VM options and the `--developer-mode` (and optional `--debug`) program arguments.

## Logging In (Jagex accounts)

Accounts migrated to a Jagex account cannot log in to a source-built client directly: the login attempt returns `401 Unauthorized` and drops back to the login screen. The source client instead reads launcher credentials from `~/.runelite/credentials.properties` (the `JX_*` session tokens). When those tokens expire you get the same `401`, and they must be refreshed.

To write or refresh the credentials (requires RuneLite launcher 2.6.3+):

1. macOS: `/Applications/RuneLite.app/Contents/MacOS/RuneLite --configure`, then add `--insecure-write-credentials` to the `Client arguments` box and save.
2. Launch RuneLite once through the Jagex launcher so it writes fresh `JX_*` tokens into `~/.runelite/credentials.properties`.
3. Re-run the dev client; it picks up the saved credentials and logs in without a password.

Keep `credentials.properties` private, and delete it (or use "End sessions" on the account site) to return the client to normal.

## External Providers

If the plugin depends on a cloud provider (TTS, external APIs), it does nothing until that provider's API key is set in the plugin config panel inside the running client. Check the key's billing and rate limits before test sessions; free tiers are often too small to test with.

## Testing Flow

- Log into the dev client with a test account.
- Exercise the plugin's main trigger (dialogue, overlay, event) and confirm the expected behavior.
- Toggle the plugin's config options and confirm behavior changes accordingly.
- Grep the client log for the plugin's debug traces when diagnosing.

## Post-Test Protocol

After running the game client for manual testing, always ask for human input before continuing with next steps.

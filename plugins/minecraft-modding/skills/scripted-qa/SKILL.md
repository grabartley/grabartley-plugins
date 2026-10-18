---
name: scripted-qa
description: Prove known changes in the real game with predetermined scenarios: a temporary in-process driver runs either a fixed script or a live session of many staged checks against one long-lived client, captures framebuffer screenshots of the feature under test, verifies them, and attaches the evidence to the PR. Runs either singleplayer against an integrated server, or multiplayer against a local dedicated server with one or more connected clients. Use for any change with a visible, interactive, or multiplayer surface BEFORE handing off to manual QA, and to work through a manual QA checklist yourself instead of handing it to a human. For open-ended "does this feel right, what will a player hit" exploration, use `playtest` instead.
---

# Scripted QA

Drive the real game programmatically, capture framebuffer screenshots of the feature under test,
verify them, and attach the evidence to the PR. Use this after implementing any change with a
visible or interactive surface (screens, HUD, rendering, in-world interactions), or with a
multiplayer surface that only exists once real clients are connected, BEFORE handing off to manual
QA. Manual QA then confirms feel and edge cases instead of discovering basics.

Why in-process instead of OS automation: macOS input automation (System Events, cliclick) needs
accessibility permissions the agent shell usually lacks, and OS-level clicks are brittle against
window focus and Retina scaling. A temporary in-process driver has full deterministic control over
the client, needs no OS permissions, and captures pixel-exact framebuffer screenshots.

## Choosing The Mode

Decide this before writing any driver, because it changes the harness, not just the scenario.

| | Singleplayer | Multiplayer |
|---|---|---|
| What runs | one client with its integrated server | a local dedicated server plus N clients |
| Reaching the server | `client.getServer().execute(...)` | a second driver in the main source set; `client.getServer()` is null |
| Use it for | screens, HUD, rendering, item and block interaction, anything one player can demonstrate | S2C sync, join and rejoin behaviour, broadcasts with different audiences, per-player state, anything whose contract is "every connected player sees this" |

Pick multiplayer when the behaviour under test **cannot be demonstrated by one player**, or when a
gametest could not reach it. The common case for that second reason: a gametest's mock player never
declares a mod's networking channels, so `canSend` is false and the real send path is never
exercised. A real client on a real connection does declare them, which is exactly the gap this mode
closes.

Do not reach for multiplayer merely because the mod is multiplayer-first. One client is faster,
quieter, and enough for most visible surfaces.

Separately from the mode, decide **how many scenarios** the run has to cover. One known change
with one or two shots fits the fixed state machine in `templates/QaDriver.java`. A QA checklist
with many items, or any visual work where framing will need a few tries, belongs in a **live
session**, described next. It works in both modes.

## Live Session: Many Checks, One Launch

The fixed state machine costs a recompile and a relaunch for every scenario and for every framing
fix: compile, client launch, title screen, world load, then the scenario, roughly 25 to 40 seconds
before the first useful frame, plus rewriting Java each time. A live session pays that once. The
client stays up and runs commands the agent appends to a file, so each further check costs only
its own seconds.

It has two temporary pieces and four helper scripts:

| File | Where it goes | What it does |
|---|---|---|
| `templates/LiveQaDriver.java` | `src/client/java/<pkg>/qa/`, registered from `onInitializeClient()` | reads `<runDir>/qa-in.txt`, runs one command per step, logs to `<runDir>/qa-out.txt` |
| `templates/QaCommands.java` | `src/main/java/<pkg>/qa/`, registered from `onInitialize()` | a temporary `/qa` command tree that stages and guards scenes: neutral spawn by tag, scene check, scene dump, reset, quiet game rules, AI on or off, sit, hurt, probe state, list hostile mobs and their targets, list players |
| `scripts/qa.sh` | run from the shell | appends commands, waits until they have all run, prints what the driver logged |
| `scripts/orbit.sh` | run from the shell | prints front, three-quarter, left and right shots of one entity |
| `scripts/sheet.py`, `scripts/contrast.py` | run from the shell | contact sheets and centre zooms for reading many shots at once (Pillow, or ffmpeg when Pillow is missing); contrast ratio between two pixels |

Because staging goes through real brigadier commands, the same tree serves singleplayer (the
driver runs the command on the integrated server at op level 4) and multiplayer (an op player
sends it as a chat command). Add mod-specific subcommands to `QaCommands` (equip an item, put a pet
to bed, set a variant) rather than writing Java into the driver. Vanilla commands (`time set`,
`effect`, `summon`, `fill`, `damage`, `gamemode`) cover the rest through `cmd`. In multiplayer,
make the clients op through the server's `ops.json`; offline-mode UUIDs are the name-based UUID of
`OfflinePlayer:<name>`.

### Driver commands

- Session: `until title|world|overlay`, `world <save>`, `connect <host:port>`, `leave`, `wait <ticks>`,
  `mark <text>`, `quit`, `abort`.
- Staging: `cmd <command>` runs any server command as this player.
- Camera: `cam <tag> <angle> <dist> <up> [focusUp|-] [focusFwd]` looks the tag up at that moment
  and views through a client-side camera entity aimed at a point on the subject. The player never
  moves. `angle` is measured from the subject's body yaw, so `0` is the front, `90` its left side,
  `-90` its right. `camoff` returns the view to the player. `fov <degrees>` (30 to 110),
  `look <yaw> <pitch>`, `hud on|off`, `gui <scale>`.
- Evidence: `sshot <name> [settleTicks]` settles, runs `/qa check`, and saves
  `qa_<username>_<name>.png` only if every staged subject is still where it was staged.
- Screens: `inv`, `hover <slotId>` (forced mouse, so tooltips render unfocused), `useblock x y z`,
  `slotclick <slotId> [button] [action]`, `slots`, `widgets`, `clickw <index>`, `clicka x y`,
  `type <text>`, `close`, `hotbar <n>`, `lang <code>`.
- Reading back: `state`, and `recipes <namespace>`, which lists each recipe and whether this
  player's recipe book has it unlocked.

`/qa` replies of the form `qa: var <tag>=<entityId>` set `$<tag>` in the driver, but prefer tags
over ids: `cam` resolves its tag itself, so shots keep working after a subject is re-staged. A
staging command and the shots that follow can be written in one batch:

```bash
bash scripts/qa.sh run <<'EOF'
cmd gamemode spectator
cmd qa quiet
cmd qa spawn pet1 <modid>:<entity> 20.5 65 0.5 0
cmd qa scene 16
hud off
EOF
bash scripts/orbit.sh pet1 look_pet1 1.6 0.1 - 0.3 | bash scripts/qa.sh run
```

The driver is strict on purpose. An `ERROR` (an exception, a command the server rejected, a
`qa: FAIL` reply, an unresolved `$name`, an `until` that timed out) drops the rest of the queue,
and `qa.sh` returns as soon as it sees one. A batch never carries on staging the wrong scene.

**Never leave the game running.** The client is a heavy process on the developer's machine, and
one left behind has to be killed by hand. Stop it with `bash scripts/qa.sh <runDir> stop` before
ending every turn, before relaunching, and before handing back to the user, and confirm it prints
`client stopped` or `client killed`. `stop` only touches the client launched from that run
directory, so other sessions' games and servers are safe; never use a broad `pkill`. As a backstop,
the driver quits the client on its own after three minutes with nothing queued.

### Scene guards

The harness assumes the mod under test will react to anything it can. So the defaults give it
nothing to react to, and every shot checks that the scene is still the one that was staged.

- **Neutral subjects.** `qa spawn <tag> <type> x y z yaw [flags]` makes the subject persistent,
  untamed, AI-off, silent, invulnerable and gravity-free. Flags opt into behaviour one at a time:
  `tame` (owned by the staging player), `sit`, `ai`, `gravity`, `sound`, `vulnerable`. Owned or
  active mobs set off follow, teleport-to-owner, pack and despawn logic you did not ask for.
- **One tag, one entity.** `spawn` refuses a tag already in use, and every lookup fails unless the
  tag matches exactly one loaded entity. Use fresh tags for each staging.
- **Every shot is checked.** `spawn` records the staged position and body yaw. `/qa check` fails if
  any staged subject is gone, duplicated, moved more than 0.05 blocks or turned more than 3
  degrees, and `sshot` runs it after the settle. A scene that is meant to move (a recall, a
  knockback) calls `qa restage <tag>` once it has settled, or `qa unstage <tag>` to stop guarding
  that subject.
- **The camera does not touch the game.** `cam` never teleports the player, so mods that react to
  player movement leave the scene alone. Park the player near the stage, out of frame and within
  view distance, because the server only loads chunks around the player.
- **Quiet world.** Run `qa quiet` once per session. It turns off mob, patrol, trader, warden and
  phantom spawning, the day and weather cycles, fire spread and mob griefing, and pins noon.
- **Look before blaming the feature.** `qa scene [radius]` lists every entity near the player with
  its tags, position, yaw, AI, persistence and owner. Run it straight after staging, and again
  whenever a check fails. `qa reset <radius>` discards everything nearby without killing it, so no
  drops, death animations or grave blocks land in later shots, and it clears the staged list.

**Stop rule.** Two staging errors or failed checks in a row mean the harness or the scene is wrong,
not the framing. Run `qa scene`, find what moved or appeared and why, and fix that cause before
sending another batch. Do not retry with new tags, positions or teleports.

### Before staging: what will the mod do?

Spend a minute reading the mod for anything that acts on staged entities before writing the stage.
Search the source for hooks on player teleport and respawn (pets brought along), entity spawn
(packs, groups, variant swaps, replacement entities), despawn rules, owner follow and sit logic,
and anything that ticks on nearby entities. Note each one, then either avoid it (a neutral spawn,
a separate stage area) or clean up after it (`qa scene`, then tp or `qa reset` the extras). A spawn
that brings friends shows up in `qa scene` as untagged entities of the same type.

### Measuring positions

Close shots exaggerate perspective: an object that reaches towards the camera looks further back
or forward than it is. When the question is where something sits (an attachment point, a gap, an
overlap), shoot axis-aligned views (side `90`, front `0`, top with a large `up`) from further away
with a narrow field of view, for example `fov 30` at three to four times the usual distance. Keep
close and three-quarter shots for judging how it looks.

### Running a checklist

1. Launch once, wait for `[QA] READY` in the client log, then send `until title` and `world <save>`
   (or `connect`). Keep the game up until the whole checklist is done.
2. Per item: stage with `cmd`, confirm the stage with `/qa scene`, assert with `/qa probe`,
   `/qa mobs` or `/qa players`, photograph with `cam` and `sshot`. Prefer asserting state over reading pixels for behaviour; keep pixels for how
   things look.
3. Pair every behaviour check with a **control**: the same scene without the item, effect or upgrade
   under test, side by side or straight after. "The warded mob ignored the player" means nothing
   until the unwarded mob, in the same place, attacks.
4. Read shots in contact sheets: `sheet.py out.png 0.5 4 shots...` puts several angles of one
   subject in one image, and a crop of `0.2` to `0.3` zooms on the focus point. Then read every
   sheet in the two passes from **Reading The Screenshots**. For a question of contrast, measure it
   with `contrast.py` rather than eyeballing it.
5. Time each phase (compile, launch, world load, each batch, reading) from the log and `qa.sh`'s
   `[took Ns]` lines, so slow steps are visible.
6. Stop the client with `qa.sh <runDir> stop`, then revert both registration lines and delete the
   `qa/` packages when done.

### What the live session taught

- **Owned entities may be pulled to their owner.** A mod that brings pets along on a teleport moves
  every owned subject the moment the player teleports. The neutral spawn and the camera entity
  avoid both halves of that. If a check needs `tame`, add `sit` too and teleport nothing.
- **AI-disabled mobs never run goals or `move()`.** Poses driven by a goal (sitting, begging) never
  apply, and block collisions never happen, so a mob stands in fire unharmed whether or not the
  feature works. Turn AI back on for those checks, with the entity sitting or anchored so it stays
  in frame.
- **Tag lookups only see loaded entities.** After a rejoin or a long move, `/qa find` fails for
  anything in an unloaded chunk. Move a player near it first, or stage within view distance of
  where the player is parked.
- **The hitbox is not the model.** Renderers often scale per variant, so one camera distance and
  focus height frames every variant differently. Tune `cam` per variant and confirm with a
  wide shot before the close-ups.
- **A language reload outlives its future.** The reload future completes while the loading overlay
  is still fading in, so a shot taken then is a red loading screen. `lang` waits for the overlay
  to clear; use `until overlay` after anything else that reloads resources.
- **Night shots are unreadable.** Give the camera player night vision before photographing
  anything staged at night.
- **Staging scripts are bash.** zsh does not word-split `set -- $var`, so a loop over
  `"name value"` pairs silently passes the wrong arguments. Run helper loops with `bash`.

## Config

Read per the dev-workflow `config` skill:
- `repos.<slug>.minecraft.imagesBranch`, default `images`: orphan branch holding PR screenshot evidence
- `repos.<slug>.minecraft.devWorld`, default `New World`: the dev save the driver loads in
  singleplayer mode. Multiplayer mode generates its own world in the server's run directory.

Resolve `<modid>` from `src/main/resources/fabric.mod.json` and the mod's base package (call it
`<pkg>` below) from the client entrypoint in the same file.

## The Temp Driver Pattern

All driver code is TEMPORARY and must never be committed. It exists only in the worktree during QA.

1. Create `src/client/java/<pkg-path>/<Feature>QaDriver.java` from
   `templates/QaDriver.java` (singleplayer) or `templates/MultiplayerQaDriver.java`
   (multiplayer) in this skill directory. It is a tick-driven state machine registered on
   `ClientTickEvents.END_CLIENT_TICK`. For a live session, use `templates/LiveQaDriver.java` and
   `templates/QaCommands.java` instead, per **Live Session: Many Checks, One Launch**.
2. Register it with one line at the end of the mod's client initializer `onInitializeClient()`:
   `<Feature>QaDriver.register();`
3. Multiplayer only: also create `src/main/java/<pkg-path>/<Feature>ServerQaDriver.java` from
   `templates/ServerQaDriver.java`, and register it with one line in the mod initializer's
   `onInitialize()`. A dedicated server is a separate process, so this is the only way a scenario
   can drive server state.
4. After QA passes, revert every one of them:
   `git checkout -- src/client/java/<pkg-path>/<ClientInitializer>.java`
   `rm src/client/java/<pkg-path>/<Feature>QaDriver.java`
   and, for multiplayer, the main-source initializer and driver too.

## Capabilities Toolbox

- **World loading (singleplayer)**: from the title screen call
  `client.createIntegratedServerLoader().start("<devWorld>", () -> {})` once
  `client.currentScreen instanceof TitleScreen` and ~60 ticks have passed (resources settled).
  Do NOT bother with loom `programArgs "--quickPlaySingleplayer", ...` — it is not picked up.
  The dev `run/saves/<devWorld>` world exists in every worktree when the `worktree` skill copies
  `run/` (set `copyRunDir` in config).
- **Server-side setup (singleplayer)**: `client.getServer().execute(() -> ...)` reaches the
  integrated server. In multiplayer this returns null; see **Reaching The Server** below.
  Spawn entities, set NBT/DataTracker state, tame to
  `server.getPlayerManager().getPlayerList().get(0)`, and trigger S2C packets exactly as
  production code would (e.g. calling the same networking send helpers used by gameplay). This
  tests the real network round trip, not a mock.
- **Clicks and keys**: call `client.currentScreen.mouseClicked(sx, sy, 0)` / `keyPressed(...)`
  directly with SCALED screen coordinates. No cursor movement needed; this drives the same code
  path as a real click and real C2S packets flow.
- **Hover states**: hover rendering reads the real OS cursor, so move it with
  `GLFW.glfwSetCursorPos` plus the iterative settle loop in the template. Never trust a single
  set call: GLFW cursor space vs framebuffer size differs per display (Retina), so converge with
  the multiplicative feedback loop until `client.mouse` derives to the target scaled position.
- **Screenshots**: `ScreenshotRecorder.saveScreenshot(client.runDirectory, name + ".png",
  client.getFramebuffer(), text -> {})` writes to `run/screenshots/`. Log the derived mouse
  position alongside each shot so a failed hover is diagnosable from the log.
- **GUI scale sweeps**: `client.setScreen(null); client.options.getGuiScale().setValue(n);
  client.onResolutionChanged();` then re-trigger the screen. Capture at least scales 1, 2, and 4
  for anything with custom rendering.
- **Lifecycle**: print `[QA]`-prefixed markers for every step, a final `[QA] DONE`, catch every
  exception into `[QA] ERROR`, and end with `client.scheduleStop()` so the run terminates itself.

## Multiplayer Mode

A local dedicated server plus one or more real clients, each in its own JVM. Everything in **The
Temp Driver Pattern** still applies; the additions are below, and all of it is temporary.

### Run Configurations

One loom run config per participant. Add them to `build.gradle`, and revert afterwards unless the
repo wants them permanently as dev infrastructure:

```groovy
runs {
	qaServer {
		server()
		name "QA Server"
		runDir "run/qa-server"
		programArgs "--nogui"
	}
	qaClientAlice {
		client()
		name "QA Client Alice"
		runDir "run/qa-alice"
		programArgs "--username", "Alice"
	}
	qaClientBob {
		client()
		name "QA Client Bob"
		runDir "run/qa-bob"
		programArgs "--username", "Bob"
	}
}
```

This yields `runQaServer`, `runQaClientAlice`, `runQaClientBob`. **Every client needs its own
`runDir`.** Two clients sharing one race on `options.txt` and overwrite each other's screenshots,
and the evidence silently becomes one client's shots twice.

### Server Preparation

In the server's run directory, before the first launch:

- `eula.txt` containing `eula=true`.
- `server.properties` with, at minimum, `online-mode=false` so offline usernames can join. Prefer
  `level-type=minecraft\:flat` (note the escaped colon) for deterministic framing, plus
  `gamemode=creative`, `force-gamemode=true`, `difficulty=peaceful`, `spawn-monsters=false`,
  `spawn-protection=0`. A flat world means every screenshot composes the same way on any seed.
- **Seed whatever per-world state the feature reads**, into the world directory, before boot.

That last point is the one that decides whether the run proves anything. **Seeded values must
differ from what a fresh client holds on its own.** If the server is left on defaults and the
client also starts on defaults, a join-time screenshot showing defaults is indistinguishable from a
client that was never told anything, and the test is worthless while looking green. Choose seed
values that differ in every field, and choose the later runtime change to differ from the seed
in every field too.

### Client Preparation

Seed each client's `options.txt` from the developer's own `run/options.txt` when it exists, so
their settings carry into the run, then force the QA-critical keys on top:

```python
# python3 seed.py run/options.txt run/qa-alice/options.txt run/qa-bob/options.txt
import os, shutil, sys
source, targets = sys.argv[1], sys.argv[2:]
forced = {"pauseOnLostFocus": "false", "guiScale": "2", "skipMultiplayerWarning": "true",
          "onboardAccessibility": "false", "tutorialStep": "none"}
for target in targets:
    os.makedirs(os.path.dirname(target), exist_ok=True)
    if os.path.exists(source):
        shutil.copyfile(source, target)
    lines = open(target).read().splitlines() if os.path.exists(target) else []
    seen = set()
    out = []
    for line in lines:
        key = line.split(":", 1)[0]
        out.append(f"{key}:{forced[key]}" if key in forced else line)
        if key in forced:
            seen.add(key)
    out += [f"{k}:{v}" for k, v in forced.items() if k not in seen]
    open(target, "w").write("\n".join(out) + "\n")
```

### Connecting

Loom's quickPlay `programArgs` are not picked up, so connect from the driver once the title screen
has settled:

```java
ConnectScreen.connect(
    new TitleScreen(),
    client,
    ServerAddress.parse(address),
    new ServerInfo("qa", address, ServerInfo.ServerType.OTHER),
    false,
    null);
```

`ConnectScreen` lives in `net.minecraft.client.gui.screen.multiplayer`. The client is in world once
`client.world != null && client.player != null`. To leave, `client.world.disconnect()` then
`client.disconnect()`; reconnect by calling `connect` again, which is how join, disconnect, and
rejoin behaviour all get covered in one run.

### Reaching The Server

`client.getServer()` is null on a dedicated server, so a client driver cannot drive server state.
Put a **second temporary driver in the main source set**, registered from the mod initializer, on
`ServerTickEvents.END_SERVER_TICK`. Have it wait until the expected number of players is connected,
then perform the change under test. The clients simply observe the effect.

This needs no command tree, no console access, and no stdin plumbing, which matters because the
command surface often does not exist yet at the point the behaviour is worth testing.

Use the same driver to pose the players so each one is visible in the other's shot. Yaw runs
`0` south (+Z), `90` west (-X), `180` north (-Z), `270` east (+X); get it backwards and the players
stand back to back, which reads as "the other client never connected".

```java
players.get(0).networkHandler.requestTeleport(x - 3.0, y, z, 270.0f, 0.0f);  // faces +X
players.get(1).networkHandler.requestTeleport(x + 3.0, y, z, 90.0f, 0.0f);   // faces -X
```

### Photographing State That Has No Pixels

Sync, persistence, and per-player state have no visible surface, so a screenshot of the world
proves nothing on its own. Register a `HudRenderCallback` in the client driver that draws **the
state that client currently holds**, labelled with its username and the phase of the scenario. The
screenshot then shows what that specific client believes, which is the actual claim under test.

Draw the panel **fully opaque**. A translucent panel lets clouds and terrain through, and the
result looks like a rendering defect in the evidence rather than a deliberate overlay.

### Orchestration

Server first, then clients, staggered so their connect attempts do not collide:

1. Reset the world and re-seed the per-world state. **The server driver writes its change into the
   world**, so a second run without a reset starts from the first run's end state and the join-time
   phase silently tests the wrong values.
2. Launch `runQaServer` in the background; wait for `Done (` in its log.
3. Launch each client in the background, a few seconds apart, each with its own log file.
4. Wait for `[QA] DONE` or `[QA] ERROR` in **every** client log, never on elapsed time.
5. Stop the server.

## Environment Prep (once per worktree)

- `options.txt`: set `pauseOnLostFocus:false` (MANDATORY — the client runs unfocused in the
  background and singleplayer would otherwise sit on the pause screen, which also blocks any
  screen-open packet handler that checks `currentScreen == null`). Pin `guiScale` to a known value
  so the first screenshots are deterministic. Preserve the developer's own `run/options.txt` by
  copying it as the base and forcing only these keys on top, rather than writing a fresh file that
  discards their settings. In multiplayer, do this per client run directory.
- `pauseOnLostFocus:false` is necessary but **not sufficient**: a second client launching steals
  focus and the first can still land on the pause screen. Call `client.setScreen(null)` in the
  driver before every capture.
- These are `run/` files, untracked; no cleanup needed beyond not committing them.
- Keep recipe viewers (JEI/EMI) out of the run. They draw full-height sidebars and a search bar
  over every screen, which lands in the captured framebuffer and makes the evidence unreadable.
  Only load them when the recipe viewer integration is itself under test.

## Run Protocol

1. **Absolute paths only.** Every command runs with an explicit
   `cd /path/to/.claude/worktrees/<prefix>-<branch> && ...`. The classic failure mode is the
   shell cwd silently resetting to the main checkout, which launches the OLD mod without the
   driver and "does nothing". If a run produces zero `[QA]` log lines, check cwd first. The tell is
   in the log itself: the Gradle `problems-report` path at the end names the checkout that actually
   built, so if it points at the main checkout rather than the worktree, that run proved nothing.
2. Compile and PROVE the driver is in the build before launching:
   `./gradlew compileClientJava` then confirm
   `ls build/classes/java/client/.../<Feature>QaDriver.class` exists and
   `javap -c -classpath build/classes/java/client <pkg>.<ClientInitializer> | grep -c QaDriver`
   returns non-zero. Do not launch until both check out.
3. Launch in the background with output to a log file.
   - Singleplayer: `./gradlew runClient > /tmp/qa-run.log 2>&1`.
   - Multiplayer: follow **Orchestration** above, one log file per participant.
4. Wait on the sentinels, but **always bound the wait**. A driver whose scenario can never
   complete produces no sentinel and no error, so an unbounded wait hangs until someone notices,
   which is usually the user rather than you. Cap every run at roughly five minutes:
   ```bash
   deadline=$((SECONDS + 300))
   until grep -qaE '\[QA\] (DONE|ERROR)|BUILD FAILED' /tmp/qa-run.log; do
     [ $SECONDS -ge $deadline ] && { echo "[QA] TIMEOUT"; break; }
     sleep 3
   done
   ```
   In multiplayer, every client log must satisfy this before the run is over, and the deadline
   covers the whole set rather than each client separately. Note that macOS has no `timeout`
   binary unless coreutils is installed, so `timeout 300 ./gradlew ...` exits 127 and silently
   skips the run; use the shell deadline above, or `gtimeout` if it exists.
   On timeout, kill the launched processes, read the log, and fix the driver. Never relaunch an
   identical run hoping it behaves differently.
5. Read the `[QA]` log lines, then Read every captured PNG and judge it per **Reading The
   Screenshots** below. A green log with wrong pixels is a failed QA.

## Gotchas That Cost A Run Each

- **An item only stays in use while `options.useKey` is held.** `MinecraftClient` calls
  `interactionManager.stopUsingItem(player)` on every tick the use key is not pressed, so anything
  that needs a bow drawn, a shield raised, food being eaten, or a crossbow charging must set
  `client.options.useKey.setPressed(true)` and keep it pressed for the whole window. Two corollaries
  cost a run each:
  - Calling `client.interactionManager.interactItem(player, hand)` on its own is not enough. It
    starts the use and the very next tick cancels it. Pressing the key alone usually works but
    depends on window focus and cursor lock, so it fails intermittently when the client runs in the
    background. Do both: press the key so nothing cancels, and call `interactItem` so the use starts
    even unfocused.
  - The same cancel defeats a **server-driven** use in multiplayer. `player.setCurrentHand(hand)` on
    a `ServerPlayerEntity` sets the flag, then that player's own client sends `RELEASE_USE_ITEM`
    because its use key is not held, and the server clears it. A player being posed mid-use must
    drive the use from their own client driver; the server driver can only stage inventory and
    position.

  Assert the state rather than assuming it: log `player.isUsingItem()` and `getItemUseTimeLeft()` a
  couple of ticks after starting. Nothing being used reads as `getItemUseTimeLeft() == 0`, which
  turns any derived progress into an absurd value (`maxUseTime / 20`, so 3600 for a bow) rather than
  into an error.
- **Releasing a drawn item fires it.** `stopUsingItem` runs the real `onStoppedUsing`, so a
  scenario that draws and releases a bow shoots a live arrow. With incendiary ammunition that sets
  the world alight and the fire lands in every later screenshot. Prefer holding the draw for the
  whole run, or expect the consequences in the frame.
- **`client.options` is null during `onInitializeClient()`.** GameOptions does not exist yet, so
  any option override belongs on the first client tick, not in `register()`. Setting it in
  `register()` throws and kills the entrypoint before the driver ever runs.
- **The framebuffer holds the frame rendered before this tick.** Change an overlay label and
  capture in the same tick and the screenshot shows the previous label. Route every capture
  through a small "settle" step: set the state, clear the frame, wait a handful of ticks, then
  save. Screenshots taken the naive way look correct until someone reads the caption.
- **Toasts land in captures.** Advancement, tutorial, and unverified-chat toasts animate in over
  several frames and appear in the top corner of the evidence. Clear the toast manager every tick
  rather than only before a capture.
- **A semi-transparent overlay is not evidence-safe.** Sky and terrain read through it and the
  result looks like a rendering bug. Draw evidence panels fully opaque.
- **Screenshot names must carry the participant.** With several clients writing shots, a name
  without the username produces sets that cannot be told apart once collected.

## Reading The Screenshots

The trap is confirming the change instead of judging the picture. You know what you altered, so
your eye goes straight to that one property, finds it applied, and calls the shot good, while a
defect sitting in the same frame goes unread because you were not looking for it. Whoever opens
the PR sees it in two seconds, and it costs another whole round.

Judge every shot in two passes:

1. **The change.** Did what you altered take effect, in the direction you intended? "It moved" is
   not "it moved the right way": check the sign, the axis, the side, the amount.
2. **The frame.** Now forget what you changed and read the picture as a player would. Anything
   floating, sunk, clipping or gapped from what it should be touching. Anything tilted, mirrored
   or facing the wrong way. Anything the wrong size against its neighbours. Labels, outlines and
   effects in the wrong place. Contact points are the richest source of bugs: where two things
   meet, they should meet.

Write down what you actually see in each shot before ruling on it. "The totem is on the left now"
restates your diff; "the totem is on the left, tipped away from the stone with a gap between them"
reads the image, and only the second one catches anything. If a shot cannot settle a question
because of its angle, distance or occlusion, that is a reason to re-shoot, not to assume.

## The Dev World Is Mutable State, And A Dead Player Poisons It

This section is about the **singleplayer** dev save. Multiplayer runs generate their own world in
the server's run directory and should reset it per run, which sidesteps all of it.

`run/saves/<devWorld>` persists between runs. A driver that kills the player writes that death into
the save, and **every later run in that worktree inherits it**. This is the single most expensive
failure mode in this skill, because it does not look like a harness problem.

**Symptom.** The world renders normally, screenshots look fine, and inventory edits show up in the
hotbar, so the setup appears to have worked. But server-side, `getPlayerList().get(0)` returns a
player with `isRemoved() == true` and `world.getPlayers()` is empty. Anything resolving the owner
through `world.getPlayerByUuid(...)` (for example `TameableEntity.getOwner()`) returns null, every
`isOwner(player)` check returns false, and interactions return `PASS` while consuming nothing. That
is indistinguishable from a genuine bug in the feature under test.

**Cause.** The saved player is at zero health. The driver's setup calls
`player.changeGameMode(GameMode.SURVIVAL)`, the player dies again on the very next tick, and the
entity is removed from the world while `PlayerManager` keeps handing out the dead reference.

**Diagnose it first, before touching feature code.** Log the player state at the moment of the
interaction, not just the outcome:

```java
System.out.println("[QA] isOwner=" + pet.isOwner(player)
    + " playerRemoved=" + player.isRemoved()
    + " worldPlayers=" + player.getServerWorld().getPlayers().size());
```

`playerRemoved=true` or `worldPlayers=0` means the harness is broken, not the feature.

**Fix.** Restore a clean save into the worktree, then re-run:

```bash
rm -rf <worktree>/run/saves/"<devWorld>"
cp -a <main-checkout>/run/saves/"<devWorld>" <worktree>/run/saves/
```

**Avoid causing it.** Two rules:

- A heightmap query against an ungenerated chunk answers with the world bottom. Any teleport that
  derives its Y from `world.getTopY(...)` must force generation first, or it drops the player into
  the void and corrupts the save:
  ```java
  world.getChunk(x >> 4, z >> 4);              // generate before querying
  final int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
  ```
- Restore the player in setup regardless: `setHealth(getMaxHealth())`, food to 20, `setAir`,
  `clearStatusEffects()`, `setFireTicks(0)`. It is three lines and it makes the run idempotent.

Prefer `player.requestTeleport(x, y, z)` for moving the player within one world. And do not trust
terrain for framing: flatten a small stage of grass with air above it so the shot composes the same
way on any seed, rather than discovering the camera is buried in a dirt cliff.

**The dev world is also populated, and its residents will do your test for you.** If it already
holds tamed or registered entities, any driver that resolves "the player's pets" (or similar) picks
one of those rather than the one it just spawned. Worse, a freshly spawned tamed entity may run
follow goals that teleport it to its owner: a recall test that waits a couple of seconds before
acting watches the entity arrive on its own and calls that a pass. Give any entity you spawn for a
test `setAiDisabled(true)`, and point the feature at it explicitly by id instead of letting it
resolve.

**A posed entity faces south no matter what yaw you gave it.** The renderer draws living entities
off `bodyYaw`, which `refreshPositionAndAngles` never touches, and with AI disabled nothing ever
updates it, so every screenshot quietly captures the entity's back while its rotation yaw claims it
faces the camera. When posing an entity for a shot, set all three:

```java
entity.refreshPositionAndAngles(x, y, z, yaw, 0.0f);
entity.setBodyYaw(yaw);
entity.setHeadYaw(yaw);
```

If a mod's renderer applies per-variant yaw offsets to normalize differently-authored rigs, do NOT
fold those into the pose: the offset exists precisely so that yaw means the same thing for every
variant, so one plain yaw works uniformly.

## Publishing Evidence to the PR

GitHub's `user-attachments` uploads are not available via `gh`, so the evidence lives on the
dedicated orphan `<imagesBranch>` branch, stored by PR number. NEVER commit screenshots to the PR
branch or main; binary evidence must not enter main's history.

NEVER force-push or delete the `<imagesBranch>` branch: every PR description across the repo
hot-links its evidence from this branch, so rewriting its history breaks images on every past PR at
once. Always add on top with normal commits; to replace a PR's evidence, overwrite the
files in its `pr-<number>/` directory in a new commit, which only ever affects that one PR.

1. Downscale the keeper screenshots: `sips -Z 1000 run/screenshots/qa_*.png --out <staging-dir>/`
2. Check out the `<imagesBranch>` branch in a temporary worktree
   (`git worktree add <tmp-path> <imagesBranch>`; if the branch does not exist yet, create it
   orphan with `git worktree add --orphan -b <imagesBranch> <tmp-path>`), copy the screenshots
   into `pr-<number>/`, commit with `--no-verify` (any gradle-based pre-commit hook cannot run on
   the codeless orphan branch), push `origin <imagesBranch>`, then `git worktree remove` the temp
   path.
3. Embed them in the PR body next to the paragraph each illustrates, then
   `gh pr edit <num> --body-file ...`. **Which URL form depends on whether the repo is public**,
   and these mods are private until they go open source for release:

   | Repo | Embed as |
   |---|---|
   | Public | `https://raw.githubusercontent.com/<slug>/<imagesBranch>/pr-<number>/<name>.png` |
   | Private | `https://github.com/<slug>/blob/<imagesBranch>/pr-<number>/<name>.png?raw=true` |

   GitHub renders both hosts' URLs verbatim rather than through its Camo proxy, so the viewer's
   own browser credentials decide whether the image loads. `raw.githubusercontent.com` never
   receives a github.com session cookie, so on a private repo it 404s and every image in the body
   is broken. The `blob/...?raw=true` form is on github.com, so the session authenticates and
   GitHub redirects to the signed asset. That form keeps working unchanged once the repo goes
   public, so prefer it whenever there is any doubt.

   **`?raw=true` is not optional and is the single most common way this breaks.** Strip it and
   the URL is a GitHub *page* rather than image bytes, so the body renders a broken image on a
   public repo and a private one alike. The suffix goes on every `src`, including inside `<img>`
   tags and table cells.

   Note that a token-authenticated `curl` is NOT a valid test of either form, because the two
   hosts accept different credentials: `raw.githubusercontent.com` accepts a bearer token but not
   a cookie, and the `blob` endpoint accepts a cookie but not a token, so each will appear broken
   when probed the wrong way. Both also 404 unauthenticated on a private repo, so a red `curl`
   proves nothing either way.
4. **Verify the body that shipped, never a sample URL.** Checking one hand-written URL through
   `gh api /markdown` proves only that the form is sound; it says nothing about what the generated
   body actually contains, and a body built from a base string that omits the suffix passes that
   check while every image is broken. Read the live PR back and assert on it:

   ```bash
   # every src must carry ?raw=true, so this must print 0
   gh pr view <number> --repo <slug> --json body --jq .body \
     | grep -oE 'src="[^"]+"' | grep -vc 'raw=true'

   # every referenced file must exist on the images branch, so this must print nothing
   gh pr view <number> --repo <slug> --json body --jq .body \
     | grep -oE 'pr-<number>/[^"?]+\.png' | sort -u > /tmp/referenced.txt
   git ls-tree -r origin/<imagesBranch> --name-only | grep '^pr-<number>/' | sort > /tmp/present.txt
   comm -23 /tmp/referenced.txt /tmp/present.txt
   ```

   Those two commands are the only proof that matters: the first catches a dropped suffix, the
   second catches a typo or a file that never pushed. Run them after every `gh pr edit`, including
   edits that only touch prose.

   The embed syntax is never the culprit, so do not go looking there. `![](url)`, a bare
   `<img src>`, and an `<img>` inside a table cell all emit an identical `src` through GitHub's
   renderer; confirm with `gh api -X POST /markdown -f mode=gfm -f context=<slug> -f text='...'`
   if in doubt.
5. If a later run replaces the screenshots, overwrite the same file names in `pr-<number>/` and
   push again; the URLs track the images branch head, so the body only needs editing when the
   prose changes.
6. Use `<img src="...?raw=true" width="420">` rather than markdown image syntax when placing
   several shots side by side in a table, which is how a multi-client run is best read: one column
   per participant, one row per phase. Generating those tags from a loop or a format string is
   exactly where the `?raw=true` suffix gets dropped, so put it in the template itself rather than
   appending it per call site.

## Checklist Before Handoff to Manual QA

- [ ] Driver ran to `[QA] DONE` with zero `[QA] ERROR`, inside the run deadline rather than being
      cut off by it
- [ ] Server-side interactions actually took effect; a `PASS` result with nothing consumed means
      checking `player.isRemoved()` before suspecting the feature
- [ ] Every screenshot visually verified by reading the PNG, at multiple GUI scales for rendering
- [ ] Server-side state assertions logged and correct (e.g. command/DataTracker values after a click)
- [ ] Multiplayer only: the seeded server state differed from client defaults in every field, so
      the join-time evidence distinguishes "synced" from "never contacted"
- [ ] Multiplayer only: each client's shots show the other participant, proving more than one
      client was really connected
- [ ] Multiplayer only: run configs, server run directory, and per-client run directories reverted
      or left untracked; nothing QA-only committed
- [ ] Live session only: every behaviour check had a control, and every batch ended on its marker
      rather than on an `ERROR` or a timeout
- [ ] No game client left running: `qa.sh <runDir> stop` printed `client stopped` or `client killed`
- [ ] Temp driver + initializer hook reverted; `git status` shows only intended files
- [ ] Evidence pushed to the `<imagesBranch>` branch under `pr-<number>/` and embedded in the PR
      body; nothing image-related committed to the PR branch
- [ ] The live PR body verified, not a sample URL: every `src` carries `?raw=true` and every
      referenced file exists on the images branch

## Related Skills

- dev-workflow `build` — requires this skill before manual QA handoff
- `playtest` — the exploratory counterpart: the agent plays the game live and reasons between inputs, instead of running a fixed script
- `run-game-client` — plain manual launch, used when a human is driving
- dev-workflow `worktree` — provides the isolated `run/` directory this skill relies on
- `item-sprite`, authoring a flat item sprite; this skill verifies the result in the client

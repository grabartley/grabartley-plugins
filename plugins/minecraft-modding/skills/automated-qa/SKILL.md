---
name: automated-qa
description: Programmatically drive the game with a temporary in-process QA driver, capture framebuffer screenshots of the feature under test, verify them, and attach the evidence to the PR. Runs either singleplayer against an integrated server, or multiplayer against a local dedicated server with one or more connected clients. Use for any change with a visible, interactive, or multiplayer surface BEFORE handing off to manual QA.
---

# Automated QA

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
   `ClientTickEvents.END_CLIENT_TICK`.
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
4. Wait on the sentinels, never on time:
   `until grep -qE '\[QA\] (DONE|ERROR)|BUILD FAILED' /tmp/qa-run.log; do sleep 3; done`
   In multiplayer, every client log must satisfy this before the run is over.
5. Read the `[QA]` log lines, then Read every captured PNG and judge it per **Reading The
   Screenshots** below. A green log with wrong pixels is a failed QA.

## Gotchas That Cost A Run Each

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
hot-links its evidence from this branch by raw URL, so rewriting its history breaks images on every
past PR at once. Always add on top with normal commits; to replace a PR's evidence, overwrite the
files in its `pr-<number>/` directory in a new commit, which only ever affects that one PR.

1. Downscale the keeper screenshots: `sips -Z 1000 run/screenshots/qa_*.png --out <staging-dir>/`
2. Check out the `<imagesBranch>` branch in a temporary worktree
   (`git worktree add <tmp-path> <imagesBranch>`; if the branch does not exist yet, create it
   orphan with `git worktree add --orphan -b <imagesBranch> <tmp-path>`), copy the screenshots
   into `pr-<number>/`, commit with `--no-verify` (any gradle-based pre-commit hook cannot run on
   the codeless orphan branch), push `origin <imagesBranch>`, then `git worktree remove` the temp
   path.
3. Embed them in the PR body via raw URLs:
   `https://raw.githubusercontent.com/<slug>/<imagesBranch>/pr-<number>/<name>.png`
   placed next to the paragraph each illustrates, then `gh pr edit <num> --body-file ...`.
4. If a later run replaces the screenshots, overwrite the same file names in `pr-<number>/` and
   push again; the raw URLs track the images branch head, so the body only needs editing when the
   prose changes.

## Checklist Before Handoff to Manual QA

- [ ] Driver ran to `[QA] DONE` with zero `[QA] ERROR`
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
- [ ] Temp driver + initializer hook reverted; `git status` shows only intended files
- [ ] Evidence pushed to the `<imagesBranch>` branch under `pr-<number>/` and embedded in the PR
      body; nothing image-related committed to the PR branch

## Related Skills

- dev-workflow `build` — requires this skill before manual QA handoff
- `run-game-client` — plain manual launch, used when a human is driving
- dev-workflow `worktree` — provides the isolated `run/` directory this skill relies on
- `item-sprite`, authoring a flat item sprite; this skill verifies the result in the client

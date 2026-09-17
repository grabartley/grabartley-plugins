---
name: playtest
description: Play the game as a player would, live, and report what a player would hit. A temporary in-process driver turns the agent's decisions into real inputs, movement keys, mouse look, attack and use, and screen clicks, while the agent reads the world back between steps and decides what to do next. Use before a release, when judging feel rather than a single change, when a feature can only be reached by progressing through the game, or when asked to explore, play, or find what is broken rather than to prove a known fix. For proving one known change with reproducible evidence for a PR, use `scripted-qa` instead.
---

# Playtest

`scripted-qa` answers "does this change work". This skill answers "what happens when somebody
plays". The difference is not the harness, it is who decides the next step: a scripted run knows
its whole scenario before it launches, and a playtest decides each step from what the last one
returned.

That changes the shape of the driver. Instead of a state machine holding one pre-written scenario,
the driver is a **command interpreter reading a file that the agent appends to while the game
runs**. The agent writes a few commands, reads the log and the screenshots, thinks, and writes the
next few. The game stays up for the whole session.

Use it when:

- a release is coming and somebody needs to know how the mod feels end to end
- the feature is gated behind progression (the recipe needs iron, the block needs redstone) and
  the only honest test is to go and get those things
- the request is "play it", "explore it", "find bugs", "would a player notice"
- a scripted run passed but nobody has ever used the feature in context

Do not use it to prove a one-line rendering fix. That is `scripted-qa`, and it is faster.

## What "as a player would" means

Every input goes through the same path a keyboard and mouse would drive. This is not pedantry: it
is the only way the test can find input-level bugs, and it is what makes the findings credible.

| Action | Drive it as | Never |
|---|---|---|
| Walk, jump, sneak, sprint | hold the `KeyBinding` (`options.forwardKey.setPressed` + `KeyBinding.setKeyPressed`) | teleport commands |
| Look | `player.changeLookDirection(dx, dy)` toward a computed yaw and pitch | snapping yaw with no travel |
| Mine | hold `attackKey` with the block under the crosshair | `world.breakBlock` |
| Place | tap `useKey` against a real support face | `setblock` |
| Open a container | tap `useKey` at the block | opening the handler directly |
| Screen clicks | `screen.mouseClicked(x, y, button)` at real widget coordinates | calling the mod's own send helpers |
| Hotbar, inventory, drop | tap the bound `KeyBinding` | writing `selectedSlot` |
| Change a game setting | drive the real options screen | a `/gamerule` command |

Cheat commands are the tell that a playtest has stopped being one. A player without cheats cannot
`/give` themselves the mod's block, and neither can this run. When a world genuinely needs settings
a player can set (difficulty, world type, game mode), set them **through the world creation screen
or the in-game options screen**, which is a real player action, and say so in the report.

## The interactive driver

Everything in `scripted-qa`'s **The Temp Driver Pattern** applies: the driver lives in
`src/client/java/<pkg>/qa/`, is registered with one line in the client initializer, and is reverted
before the run is over. The difference is what it does once it is up.

Copy the templates in this skill's `templates/` directory:

| File | What it is |
|---|---|
| `QaDriver.java` | the tick loop: reads the inbox, runs one command per step, owns the running task |
| `QaTask.java`, `QaTasks.java` | multi-tick behaviours: look, mine, place, use, walk, attack, pillar, eat |
| `QaNavigate.java` | the parts that need a plan: step into a cell, dig to a position, gather N of a block, build a row |
| `QaInput.java` | keybinding held/tapped state, yaw and pitch maths, nearest entity |
| `QaReport.java` | everything the agent reads back: screen, slots, inventory, world, entities, threats, block scans |
| `QaModifiers.java` + `QaModifiersMixin.java` | held shift, control and alt, injected where the game actually reads them |
| `QaLog.java` | the outbox |

Register `QaDriver.register()` from `onInitializeClient()`, add `QaModifiersMixin` to the client
mixin config, and revert both when the session ends.

### The loop

- The driver reads `run/qa-in.txt` every couple of ticks and appends everything new to a queue.
- One command runs per step, with a short settle wait after it.
- A command that cannot finish in a tick becomes a **task**; while a task runs, the queue waits.
- Everything the driver sees goes to `run/qa-out.txt` and to stdout with a `[QA]` prefix.
- Chat and action bar messages are captured by registering `ClientReceiveMessageEvents.GAME`. This
  is the single highest-value line in the driver: most mod feedback is an action bar message, and
  reading it beats reading pixels.

The agent drives it with nothing more exotic than `cat >> run/qa-in.txt` and `tail run/qa-out.txt`.

### Commands worth having

Movement and world: `goto x z`, `walk ticks`, `press <binding> on|off`, `tap <binding>`,
`lookat yaw pitch`, `face x y z`, `facee <entity>`, `hit <entity> <swings>`, `mine x y z`,
`place x y z`, `useblock x y z`, `digto x y z`, `gather <block> <count>`, `buildrow x1 y1 z1 x2 y2 z2`,
`pillar n`, `eat ticks`, `hotbar n`.

Screens: `click wx wy [button]` (window-relative to the open `HandledScreen`), `clicka x y [button]`
(absolute), `slotclick <slotId> [button]`, `scroll`, `key <name>`, `type <text>`, `mod shift|ctrl|alt on|off`,
`put <from> <to> <count>`, `takeout <count>`, `close`, `pause`.

Reading back: `world`, `inv`, `slots`, `screen`, `entities`, `threats`, `block x y z`, `scan x y z`,
`find <block> <radius>`, `look`, plus whatever mirrors the mod keeps client-side.

Session: `wait ticks`, `sshot <name>`, `guiscale n`, `mark <text>`, `abort`, `quit`.

`mark` is how the agent finds its place in a long log: write one before each check and grep for it
afterwards.

## Rules the game will teach you the hard way

Every one of these cost a run before it was written down.

**Modifiers are read below `Screen.hasShiftDown`.** Vanilla's `HandledScreen.mouseClicked` inlines
`InputUtil.isKeyPressed(handle, 340)`; it never calls `Screen.hasShiftDown()`. Mixin
`InputUtil.isKeyPressed` instead, so held modifiers are true everywhere the game asks, vanilla and
mod alike.

**On macOS, control is Command.** `Screen.hasControlDown()` checks `GLFW_KEY_LEFT_SUPER` on a Mac.
A modifier map that only answers for 341 and 345 will silently fail every ctrl-click gesture. Map
343 and 347 as control too.

**Closing a screen must tell the server.** `client.setScreen(null)` runs the client-side removal but
leaves the server on the old handler, after which every slot click is accepted locally and dropped
server-side: items appear to move, crafting results never appear, and nothing errors. Call
`client.player.closeHandledScreen()` for any `HandledScreen`.

**A tap must release.** Leaving the bound key pressed re-triggers the action next tick, so the
inventory key opens and immediately closes. Set pressed, call `KeyBinding.onKeyPressed`, then clear
pressed in the same call: `wasPressed()` reads a counter, so the release does not cancel the press.

**A crafting result needs a tick.** Fill the grid, wait a handful of ticks for the server to send
the result slot, then take it. Taking it in the same step consumes the ingredients and yields
nothing.

**Mine through whatever is in the way.** A miner that aborts when the crosshair is not exactly on
its target cannot cut a tree with leaves in front of it. Hold attack against whatever the crosshair
finds until the target is air, with a budget so it cannot eat the world.

**Never mine next to a fluid.** Check a 3x3x3 around the target and refuse. One breach into water
drowns the player and the whole inventory despawns five minutes later.

**Stepping up needs headroom above the player**, not just at the destination. Stepping down needs
the cell at the player's own level cleared as well, or the player walks into a wall. A gap in the
path is bridged by placing a block, and a straight climb is a pillar jump. A navigator missing any
of these loops forever while the log fills with "cleared" lines.

**Align to the block centre before walking into a one-wide gap.** A player standing at z=26.7 has a
bounding box that touches z=27.0, and the move is refused. Aim for the centre of the destination
cell, always.

**Death must be handled by the harness.** A dead player still ticks, so every queued command keeps
running against a corpse and each one burns its timeout. Detect `player.isDead()`, drop the queue,
release the keys, log the position, and respawn automatically. Then go and collect the drops: they
despawn in five minutes and a survival run cannot afford to lose its tools twice.

**Keep an abort.** Read the inbox even while a task is running and act on a bare `abort` line.
Without it, a gather that wanders off across the map can only be stopped by killing the client.

**Hover states cannot be driven by moving the OS cursor.** `glfwSetCursorPos` does nothing for a
background window, so tooltips only ever appear wherever the real cursor happens to sit. Do what
`scripted-qa` does instead: wrap the screen under test in an anonymous subclass that overrides
`render(...)` and passes forced mouse coordinates through to `super.render`. Identical render path,
no OS involvement, and the tooltip appears where you asked for it.

**Mobs will end the run.** Three deaths to a zombie is a morning gone. Set Peaceful through the
options screen for the building and gathering phases, and switch back only for the part of the
feature that genuinely needs hostile mobs. Say in the report which phases ran at which difficulty.

## Getting to the feature

A mod's best surface is often behind a recipe, and the recipe is behind a mining trip. Two honest
routes, in order of preference:

1. **Play the progression.** `gather <ore> <n>` plus a furnace and a crafting table gets there, and
   the trip itself is the test: it is how you learn the recipe's cost is wrong, or that the block
   is unobtainable before the Nether.
2. **A second world in creative.** When progression has been exercised and the remaining questions
   are all about the feature's own surface, make a flat creative world through the world creation
   screen and take the block from the creative inventory. This is still play, it is how a pack
   author evaluates a mod, and it verifies the creative tab placement too. Report both worlds.

Never let route 2 quietly replace route 1. The progression run is where the "mid-game goal" claims
in a design document either hold or do not.

## Reading the game back

Screenshots are the evidence, but they are the slowest thing to read. Prefer, in order:

1. **Captured messages.** The action bar says "Sorted", "That was already sorted", "Took 1 of 64,
   the rest would not fit". Grep the outbox.
2. **State dumps.** Slot contents, the mod's own client mirrors, block states, the container list.
   A dump proves a count; a screenshot suggests one.
3. **Screenshots**, for layout, art, colour, truncation, and anything about how it reads.

Judge a screenshot in two passes, exactly as `scripted-qa` says: first whether the thing under test
happened, then reading the whole frame as a player would. Text that is trimmed without an ellipsis,
labels that read as broken mid-phrase, a highlight that covers half of a double chest: these are
found by looking at the picture, never by the log.

## Reporting

A playtest's output is a findings list, not a pass mark. For each finding give:

- what a player does to hit it
- what happened, with the captured message or dump as evidence
- what the documentation or design says should happen, quoted, when there is a mismatch
- the root cause when the code confirms it, and where

Separate **blocking** (a documented feature that cannot work), **behavioural** (works, reports the
wrong thing, or is unreachable in normal use), and **polish** (truncation, pluralisation, missing
icon). List what was verified as working too: a release decision needs the green as much as the red.

Confirm each finding in the code before reporting it. "Middle-click does nothing" is an
observation; "vanilla only sends a CLONE slot action in creative, so the non-creative gesture the
mod listens for can never arrive" is a finding, and the second one is what gets fixed.

## Checklist

- [ ] Driver, navigation and modifier mixin in place, compiled, and proven present in the build
- [ ] Every input went through a keybinding, a real click, or a real interaction, no cheat commands
- [ ] Difficulty and world-type changes made through the real screens and disclosed in the report
- [ ] Deaths handled by the harness, drops recovered or their loss noted
- [ ] Messages captured for every operation exercised
- [ ] Screenshots read in both passes, at more than one GUI scale for anything custom-drawn
- [ ] Findings confirmed against the code, split into blocking, behavioural and polish
- [ ] What worked listed alongside what did not
- [ ] Driver, mixin entry and initializer hook reverted; `git status` clean of QA code

## Related Skills

- `scripted-qa` — the same client, driven by a fixed scenario, for proving one known change with
  evidence attached to a PR
- `run-game-client` — plain manual launch when a human is driving
- dev-workflow `build` — the flow both of these plug into

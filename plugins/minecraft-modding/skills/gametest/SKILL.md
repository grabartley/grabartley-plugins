---
name: gametest
description: Author or modify Fabric GameTest classes for a Fabric mod. Use this skill BEFORE writing or editing any file under src/main/java/.../gametest/, before changing fabric-gametest entrypoints in fabric.mod.json, or before authoring .snbt structure templates. Also use when diagnosing flaky gametests, ClassCastException on createMockPlayer, sleep-test timing failures, or "test still in EMPTY_STRUCTURE but placing blocks at (2,1,0)" smells. This skill encodes hard-won patterns from a full audit of a real suite; rules here override defaults.
---

# Writing Fabric GameTests

GameTests run inside a real Minecraft server. They are NOT JUnit. Every method below is verified against Yarn 1.21.1 + Fabric Gametest API v1 (2.0.5+) decompiled sources. If you skip this skill the result will compile, run, and silently spawn entities at world origin where they contaminate other tests.

Examples below use a generic tameable `PetEntity` registered as `ModEntities.PET`; substitute the mod's own entities and blocks.

## Config

Read per the dev-workflow `config` skill:
- `repos.<slug>.minecraft.gametestTemplates`: list of `.snbt` structure template ids the repo ships (e.g. `<modid>:arena`). Resolve `<modid>` from `src/main/resources/fabric.mod.json`.

## Mental Model

A gametest runs inside a structure spawned at a fixed offset from world origin. The TestContext gives you relative-coordinate APIs that resolve into absolute world coordinates inside your structure's footprint. Tests in the same batch run sequentially in the same `ServerWorld`; different batches run in parallel in separate world regions but **share the same world clock and the same JVM-global static state**. There is no test isolation by default. You have to ask for it.

Each test ends in one of two ways:
1. You call `context.complete()` imperatively (good for short flag-transition checks).
2. You queue end-of-test invariants via `context.addInstantFinalTask(...)` or `context.expectBlockAtEnd(...)` etc.; the framework completes the test for you at the tick limit.

Failing to do either means the test runs until `tickLimit` and is marked timeout.

## Rule 1: NEVER touch `world.spawnEntity` or `world.setBlockState` in a gametest

These accept absolute `BlockPos`. Tests using them with `new BlockPos(0, 1, 0)` spawn entities at world origin, NOT at the structure. Every test then competes for the same patch of world, blocks/entities leak between tests, and failures look like flakes.

Use the relative-coord APIs:

```java
// Spawn an entity at structure-relative (0, 1, 0)
final PetEntity pet = context.spawnEntity(ModEntities.PET, new BlockPos(0, 1, 0));

// Place a block at structure-relative (0, 1, 0)
context.setBlockState(new BlockPos(0, 1, 0), ModBlocks.PET_BED.getDefaultState());

// If a downstream API needs an absolute pos, convert once:
final BlockPos absBedPos = context.getAbsolutePos(new BlockPos(0, 1, 0));
pet.setAssignedBedPos(absBedPos);
```

Consider a CI grep gate for raw `world\.\(spawnEntity\|setBlockState\)` inside the gametest tree so they cannot be reintroduced.

## Rule 2: `EMPTY_STRUCTURE` is 1x1x1. Anything outside it falls into the void.

`FabricGameTest.EMPTY_STRUCTURE` is a single air block at relative (0, 0, 0) with no floor. Entities spawned at (0, 1, 0) collide with whatever block you placed there. Entities spawned at (1, 1, 0), (2, 1, 0), etc. escape the structure. Without a floor below the air block, non-flying entities free-fall into the void within seconds, get marked `removed`, and any subsequent assertion on their state silently lies.

For anything that needs more than one block, ship an `.snbt` template:

```
src/main/resources/data/<modid>/gametest/structure/<name>.snbt
```

Build templates with a bedrock floor and enough room for the behavior under test. A useful pair, proven in practice:
- a small template (about 5x3x5) for placed-block interactions
- an arena template (about 7x4x7) for AI-driven multi-tick tests

Reference them via `@GameTest(templateName = "<modid>:<name>", ...)` and list the shipped ids in the repo's `gametestTemplates` config. Anytime a test relies on AI ticking for >20 ticks, use the arena, not `EMPTY_STRUCTURE`. The number of mysteriously-failing tests this prevents is hard to overstate.

A note on template coordinates: relative y1 is the template floor, entities stand at y2; placing an entity at y1 embeds it in the floor.

## Rule 3: `useNightTime()` and `setTime(int)` are one-shot, not pins

`useNightTime()` is just `setTime(13000)`. `setTime` is just `world.setTimeOfDay(int)`. The natural daylight cycle keeps running. By tick 100, time-of-day has advanced 100 from wherever you set it, and any goal reading `world.getTimeOfDay() % 24000` sees the drifted value.

Two ways to fix this:

### (Preferred) Disable the daylight cycle for the batch

The `TestServer` disables `DO_MOB_SPAWNING`, `DO_WEATHER_CYCLE`, `DO_FIRE_TICK`, and sets `RANDOM_TICK_SPEED = 0`. It does NOT disable `DO_DAYLIGHT_CYCLE`. Disable it yourself in a `@BeforeBatch`:

```java
@BeforeBatch(batchId = "sleep-time")
public void freezeDaylightForSleepTests(ServerWorld world) {
  world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
}

@AfterBatch(batchId = "sleep-time")
public void restoreDaylightCycle(ServerWorld world) {
  world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(true, world.getServer());
}
```

With `DO_DAYLIGHT_CYCLE` off, `world.setTimeOfDay(13000)` actually pins time at 13000 until you change it.

### Tests in the same batch run in PARALLEL and share world time

Each `@GameTest` in a batch runs in its own structure region, but they all share ONE world: same gamerules, same `worldProperties.timeOfDay`. If test A pins night and test B pins day, whichever pin happened most recently wins for ALL tests, and the loser sees the wrong time on its next `world.getTimeOfDay()` read. Goal selectors evaluating mid-test then mutate state on stale assumptions. The race surfaces as ~25% flake on a test that LOOKS standalone.

Symptom: a test that asserts "suppression / sleep state holds for the rest of night" passes locally in isolation, fails ~25% of the time in the full suite, and diagnostic logging shows `world.getTimeOfDay() % 24000 < 13000` mid-window even though the test pinned night.

Fix: any test that pins time to a value DIFFERENT from its sibling tests goes in its own `batchId`. One time-pinning test per batch is the cheapest reliable answer. Split along lines like `sleep-stay-asleep`, `sleep-wake-at-sunrise`, `sleep-suppress`, plus a separate batch for the time-agnostic tests.

### (Fallback) Re-pin time immediately before each assertion

If you can't or don't want a batch-wide setup, re-set time right before every read via small `pinNight` / `pinDay` helpers. It's noisier than the batch approach and only works when you control every assertion site. NOTE: this fallback does NOT fix the parallel-batch race above; goal selectors evaluating between your re-pin calls still see the conflicting value.

## Rule 4: `createMockPlayer` is NOT a `ServerPlayerEntity`

```java
// CRASHES: ClassCastException at runtime
// "class net.minecraft.test.TestContext$1 cannot be cast to class
//  net.minecraft.server.network.ServerPlayerEntity"
ServerPlayerEntity player = (ServerPlayerEntity) context.createMockPlayer(GameMode.SURVIVAL);
```

`createMockPlayer(GameMode)` returns an anonymous `TestContext$1` extending `PlayerEntity`. It has no server connection, no AdvancementTracker, no real inventory routing. Any code reaching for `getAdvancementTracker()`, `Criteria.*.trigger(player, ...)`, server-side inventory updates, or `ServerPlayNetworking.send(player, ...)` will crash or silently no-op.

For that, use:

```java
ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
```

This returns a real `ServerPlayerEntity` whose advancement tracker and server connection are wired enough for most criteria / inventory / packet tests.

If you only need a `PlayerEntity` (for `interactMob`, holding an item, being the closest player to a mob), `createMockPlayer(GameMode)` is fine. Cast carefully.

## Rule 5: Tests sharing static state must reset it per batch

Mods often carry JVM-global static maps mutated by gameplay (session registries, pending-interaction maps, and similar). A test that triggers one leaves state behind for the next test in the batch. Production may clear such state on server stop; tests must reset proactively. In a `@BeforeBatch` for any batch that touches the state:

```java
@BeforeBatch(batchId = "play-mode")
public void clearSessionState(ServerWorld world) {
  ModSessions.clearActiveSessions();
  ModBlocks.clearPendingAssignments();
}
```

If clear-helpers don't exist yet, add them (preferred), use `setAccessible(true)` reflection on the static field, or scope the test so the state never gets set.

## Rule 6: When the test is about flag transitions, disable the AI

Many entity tests are testing DataTracker contracts: "set X, observe X via getter; call mutator, observe mutated value." The entity's AI ticks in parallel and can mutate the same flags: anger goals set targets, revenge goals react to damage, an auto-sleep goal re-sleeps an entity right after `damage()` woke it. All of this races your assertion.

If the test isn't about AI behavior, kill the AI:

```java
final PetEntity pet = context.spawnEntity(ModEntities.PET, new BlockPos(0, 1, 0));
pet.setTamed(true, true);
pet.setAiDisabled(true);   // <-- no goals run; DataTracker mutation still works
```

If the test IS about AI behavior (e.g. "auto-sleep at night"), keep AI on but pin the entity near its target before the relevant assertion so navigation has a chance to fire:

```java
context.runAtTick(130, () -> {
  pet.refreshPositionAndAngles(
      absBedPos.getX() + 0.5, absBedPos.getY(), absBedPos.getZ() + 0.5, 0f, 0f);
  pet.setVelocity(0, 0, 0);
});
```

### And a tamed entity NEEDS AN OWNER

Vanilla `SitGoal.canStart` on `TameableEntity` returns true UNCONDITIONALLY for any tamed entity whose `getOwner()` returns null (after the early `!isTamed`, in-water, not-on-ground filters). `SitGoal` is typically registered at a higher priority (lower number) than custom sleep or behavior goals, and `PrioritizedGoal.canBeReplacedBy` returns `canStop() && other.priority < this.priority`, so SitGoal cannot be preempted by a lower-priority goal. The entity "sits" forever and `isInSittingPose()` short-circuits the custom goals' `canStart`. In production every tamed entity has an owner so this never fires; in gametests wire one up explicitly:

```java
final ServerPlayerEntity owner = context.createMockCreativeServerPlayerInWorld();
pet.setOwnerUuid(owner.getUuid());
```

Note: `setSitting(false)` only resets the transient `sitting` field on the Java object, it does NOT clear the DataTracker `InSittingPose` flag. Only `SitGoal.stop()` (or a direct `setInSittingPose(false)` call) does that. Fixing this with `setSitting(false)` alone is a trap; give the entity an owner so SitGoal never starts in the first place.

## Rule 7: Prefer declarative end-of-test assertions over `runAtTick(tickLimit - 1, ...)`

Fabric's canonical example uses:

```java
context.addInstantFinalTask(() ->
    context.checkBlock(new BlockPos(0, 2, 0), b -> b == Blocks.DIAMOND_BLOCK, "Expect diamond"));
```

The framework runs `addInstantFinalTask` at the end of the test and completes for you. No `complete()` call, no tick math, no race with goal selectors.

Other useful end-of-test helpers from `TestContext`:
- `expectBlockAtEnd(Block, BlockPos)` — final block-id check, declarative
- `expectEntityAtEnd(EntityType, BlockPos)` — final entity-near-pos check
- `expectEntityWithDataEnd(BlockPos, EntityType, Function<E,T>, T)` — final DataTracker check via Function

Use these when you're verifying steady-state. Use `runAtTick` + imperative `assertTrue` when you're verifying a transition mid-test.

## Rule 8: Use `@GameTest(maxAttempts = N, requiredSuccesses = 1)` for inherent races

For tests that depend on goal selector timing, AI navigation, or world tick ordering, the framework supports retries:

```java
@GameTest(
    templateName = "<modid>:arena",
    batchId = "sleep-time",
    tickLimit = 200,
    maxAttempts = 3,
    requiredSuccesses = 1)
public void manualNightWakePetAutoSleepsNextNight(TestContext context) { ... }
```

This runs the test up to 3 times, passes if any single run succeeds. CI sees one passing test in the report. Do NOT use this to paper over real bugs; reserve it for genuine race-y AI scenarios where the contract is "this *can* happen within N ticks" not "this *must* happen at tick N". Document why you're using it in the test's javadoc.

## Rule 9: One assertion theme per test, named after what fails

Bad:

```java
@GameTest
public void wakeUpFromBedClearsSleepingFlag(TestContext context) {
  // ... sets up sleep
  // ... asserts entity is sleeping at tick 50
  // ... wakes entity
  // ... asserts entity is not sleeping
  // ... asserts COMMANDED_TO_SLEEP is cleared
  // ... assumes the world clock is night for 50 ticks straight
}
```

If this fails at "should still be sleeping at tick 50" you don't know whether the sleep flag was wrong or a goal woke the entity. Split it:

```java
@GameTest void startSleepingInBedSetsSleepingFlag(...)           { ... }
@GameTest void startSleepingInBedClearsCommandedToSleepFlag(...) { ... }
@GameTest void wakeUpClearsSleepingFlag(...)                     { ... }
@GameTest void wakeUpKeepsCommandedClearedAfterCommand(...)      { ... }
```

Smaller tests are easier to keep deterministic.

## Rule 10: If JUnit can do it, use JUnit

Gametest spins up an integrated server and a real `ServerWorld` per batch. That's expensive. Anything that can be tested without ticking the world should be a JUnit test:

- Constant comparisons (`COOLDOWN_TICKS == 6 * 20`)
- `instanceof` checks against base classes the compiler already enforces
- Pure utility classes (resolvers, calculators, mappers)

These go under `src/test/java/...`. Prefer `@ParameterizedTest` data providers over copy-pasted per-variant test methods.

### Bootstrapping registries for unit tests

For tests that read mod content from the canonical vanilla registries (sound registration presence, block properties, `Registries.BLOCK.containsId(...)` checks) or the mod's own JVM-global static maps, a JUnit 5 extension that touches just enough of Minecraft's static init works well: populate `Registries.SOUND_EVENT` and `Registries.BLOCK` and trigger every registration class's static `Registry.register(...)` calls, then apply it once per test class with `@ExtendWith(...)`. Note the unit-test classpath may need an access widener entry for `RegistryEntry$Reference#setRegistryKey` so `Bootstrap.initialize()` runs in a plain JVM.

### What still has to live in gametest

The JUnit unit-test classpath does NOT have Loom's production-runtime access widening, and Yarn-mapped vanilla code emits invokevirtuals that the verifier rejects mid-bootstrap. Even with a bootstrap extension catching the `VerifyError`, everything below the first failing class init is left in a `Could not initialize class` state. In practice this means tests still need gametest when they touch:

- `Items.*` (anywhere — `Items.<clinit>` fails verification through `LightBlock` → `EntityType` → `MobEntity.isInAttackRange`)
- `EntityType.<clinit>` (same chain), which transitively means anything that touches the mod's entity-type field reads
- `BlockTags.*` membership (`state.isIn(BlockTags.AXE_MINEABLE)` needs server-side tag bindings)
- Entity construction (`ModEntities.PET.create(world)` triggers `MobEntity` class init, which hits the same verifier path)
- NBT round-trips via a real entity (same construction barrier)
- Goal selector behavior, navigation, multi-tick state, anything that needs a real `ServerWorld`

## Annotation Reference

The full `@GameTest` annotation fields, decompiled from Yarn 1.21.1:

| Field | Default | Meaning |
|---|---|---|
| `templateName` | (required) | Structure id, e.g. `"<modid>:arena"` or `FabricGameTest.EMPTY_STRUCTURE` |
| `tickLimit` | 100 | Max ticks before timeout |
| `batchId` | `"defaultBatch"` | Batch name. Tests with same batchId run sequentially. Use the same name in `@BeforeBatch(batchId = ...)`. |
| `skyAccess` | false | If true, opens the top of the structure (sunlight, sky-spawning rules) |
| `rotation` | 0 | Rotate structure 0/90/180/270° |
| `required` | true | If false, failure does not fail the suite. Use this VERY sparingly |
| `manualOnly` | false | If true, skipped in `runGametest` and only runs via in-game `/test run` |
| `duration` | 0L | Extra setup ticks before user code runs |
| `maxAttempts` | 1 | Retry budget; see Rule 8 |
| `requiredSuccesses` | 1 | Required passes within `maxAttempts` |

Related annotations:
- `@BeforeBatch(batchId = "X")` on an instance method `(ServerWorld) -> void` — runs once before batch X's tests
- `@AfterBatch(batchId = "X")` on `(ServerWorld) -> void` — runs once after batch X's tests
- `@CustomTestProvider` on a method returning `Collection<TestFunction>` — dynamically generate tests (this is what Mojang docs call `@GameTestGenerator`; Yarn renames it)

## TestContext Method Reference

Stable, useful, decompiled from Yarn 1.21.1. Method signatures with `(BlockPos)` accept structure-relative positions unless noted; signatures with `(int, int, int)` are also relative.

### Coordinates
- `getWorld() -> ServerWorld`
- `getAbsolutePos(BlockPos) -> BlockPos`
- `getRelativePos(BlockPos) -> BlockPos`
- `getAbsolute(Vec3d) -> Vec3d`
- `getRelative(Vec3d) -> Vec3d`
- `getTestBox() -> Box` — bounding box of structure
- `getRotation() -> BlockRotation`
- `forEachRelativePos(Consumer<BlockPos>)`

### Spawning
- `spawnEntity(EntityType<E>, BlockPos) -> E`
- `spawnEntity(EntityType<E>, Vec3d) -> E`
- `spawnEntity(EntityType<E>, int, int, int) -> E`
- `spawnEntity(EntityType<E>, float, float, float) -> E`
- `spawnMob(EntityType<M>, BlockPos) -> M`
- `spawnItem(Item, BlockPos) -> ItemEntity`
- `spawnItem(Item, float, float, float) -> ItemEntity`

### Players
- `createMockPlayer(GameMode) -> PlayerEntity` — NOT a ServerPlayerEntity
- `createMockCreativeServerPlayerInWorld() -> ServerPlayerEntity` — REAL server player, use for advancements/inventory

### Blocks
- `setBlockState(BlockPos, BlockState)` / `setBlockState(BlockPos, Block)`
- `setBlockState(int, int, int, BlockState)` / `setBlockState(int, int, int, Block)`
- `getBlockState(BlockPos) -> BlockState`
- `getBlockEntity(BlockPos) -> T`
- `removeBlock(BlockPos)`

### Time
- `useNightTime()` — one-shot `setTime(13000)`
- `setTime(int)` — one-shot `world.setTimeOfDay(int)`

### Scheduling
- `runAtTick(long absoluteTick, Runnable)` — schedule code at this tick
- `waitAndRun(long relativeTicks, Runnable)` — wait then run
- `runAtEveryTick(Runnable)` — every tick
- `addTask(Runnable)` — queue for next tick
- `getTick() -> long` — current tick

### End-of-test (preferred)
- `addFinalTask(Runnable)` — runs at end of test
- `addInstantFinalTask(Runnable)` — runs immediately at test's natural end
- `addFinalTaskWithDuration(int, Runnable)` — runs at end + ticks
- `expectBlockAtEnd(Block, BlockPos)`
- `expectEntityAtEnd(EntityType, BlockPos)`
- `dontExpectEntityAtEnd(EntityType, BlockPos)`
- `expectEntityWithDataEnd(BlockPos, EntityType<E>, Function<E,T>, T)`

### Imperative assertions
- `assertTrue(boolean, String)` / `assertFalse(boolean, String)` / `assertEquals(N, N, String)`
- `checkBlock(BlockPos, Predicate<Block>, String)`
- `checkBlockState(BlockPos, Predicate<BlockState>, Supplier<String>)`
- `checkBlockEntity(BlockPos, Predicate<T>, Supplier<String>)`
- `expectBlock(Block, BlockPos)` / `dontExpectBlock(Block, BlockPos)`
- `expectEntity(EntityType)` / `dontExpectEntity(EntityType)`
- `expectEntityAt(EntityType, BlockPos)` / `dontExpectEntityAt(EntityType, BlockPos)`
- `expectEntityAround(EntityType, BlockPos, double radius)`
- `expectEntityWithData(BlockPos, EntityType<E>, Function<E,T>, T)`
- `expectEntityHasEffect(LivingEntity, RegistryEntry<StatusEffect>, int amplifier)`

### Lifecycle
- `complete()` — mark test complete (required unless using a `*Final*` or `*AtEnd` pattern)
- `getTick() -> long`

### Interactions
- `useBlock(BlockPos)` / `useBlock(BlockPos, PlayerEntity)` / `useBlock(BlockPos, PlayerEntity, BlockHitResult)`
- `useStackOnBlock(PlayerEntity, ItemStack, BlockPos, Direction)`
- `pushButton(BlockPos)` / `toggleLever(BlockPos)`
- `putAndRemoveRedstoneBlock(BlockPos, long ticksAlive)`
- `startMovingTowards(MobEntity, BlockPos, float speed) -> TimedTaskRunner`
- `setEntityPos(MobEntity, float, float, float)`
- `setBiome(RegistryKey<Biome>)`

### Cleanup
- `killAllEntities()` / `killAllEntities(Class)`

## Proven Patterns

### Pattern: pure DataTracker contract test (no AI needed)

```java
@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
public void collarColorPersistsAcrossWakeUpCycle(TestContext context) {
  final PetEntity pet = context.spawnEntity(ModEntities.PET, new BlockPos(0, 1, 0));
  pet.setAiDisabled(true);
  pet.setTamed(true, true);
  pet.setCollarColor(DyeColor.LIME);

  pet.startSleepingInBed(context.getAbsolutePos(new BlockPos(0, 1, 0)));
  pet.wakeUp();

  context.assertTrue(pet.getCollarColor() == DyeColor.LIME, "Collar color must survive wake cycle");
  context.complete();
}
```

### Pattern: multi-tick AI test on a real arena

```java
@GameTest(
    templateName = "<modid>:arena",
    batchId = "sleep-time",
    tickLimit = 200)
public void commandedSleepPetStaysInPositionAcrossMultipleTicks(TestContext context) {
  final BlockPos relBedPos = new BlockPos(0, 1, 0);
  final BlockPos absBedPos = context.getAbsolutePos(relBedPos);
  context.setBlockState(relBedPos, ModBlocks.PET_BED.getDefaultState());

  final PetEntity pet = context.spawnEntity(ModEntities.PET, relBedPos);
  pet.setTamed(true, true);

  context.runAtTick(10, () -> {
    pet.setAssignedBedPos(absBedPos);
    pet.commandToSleep(absBedPos);
    pet.startSleepingInBed(absBedPos);
  });

  context.runAtTick(100, () -> {
    final double dxz = Math.hypot(
        pet.getX() - (absBedPos.getX() + 0.5),
        pet.getZ() - (absBedPos.getZ() + 0.5));
    context.assertTrue(dxz < 0.05, "Sleeping pet should stay on bed X/Z center, dxz=" + dxz);
    context.complete();
  });
}
```

### Pattern: time-dependent test with batch-wide daylight freeze

```java
public final class SleepBehaviorGameTest implements FabricGameTest {
  @BeforeBatch(batchId = "sleep-time")
  public void freezeDaylight(ServerWorld world) {
    world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, world.getServer());
  }

  @GameTest(
      templateName = "<modid>:arena",
      batchId = "sleep-time",
      tickLimit = 100)
  public void commandedSleepAutoWakesAtSunrise(TestContext context) {
    // setup ...
    context.runAtTick(10, () -> context.setTime(13000));   // pinned by daylight freeze
    context.runAtTick(50, () -> context.setTime(1000));    // pinned
    context.runAtTick(55, () -> {
      context.assertTrue(!pet.isSleepingInBed(), "Sleeping pet should wake at sunrise");
      context.complete();
    });
  }
}
```

### Pattern: advancement-criterion test needing a real server player

```java
@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
public void tamingUnlocksBestFriend(TestContext context) {
  final ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
  final PetEntity pet = context.spawnEntity(ModEntities.PET, new BlockPos(0, 1, 0));

  Criteria.TAME_ANIMAL.trigger(player, pet);

  final AdvancementEntry bestFriend = context.getWorld().getServer()
      .getAdvancementLoader()
      .get(Identifier.of(MyMod.MOD_ID, "best_friend"));
  context.assertTrue(
      player.getAdvancementTracker().getProgress(bestFriend).isDone(),
      "Taming should unlock best_friend");
  context.complete();
}
```

### Pattern: collapsing per-variant duplication with `@CustomTestProvider`

```java
@CustomTestProvider
public Collection<TestFunction> spawnsCorrectlyPerVariant() {
  return VariantTestData.getAllVariants().stream()
      .map(data -> new TestFunction(
          "defaultBatch",
          "entitycoretest.spawnscorrectly." + data.variant().serializedId(),
          FabricGameTest.EMPTY_STRUCTURE,
          /* tickLimit */ 20,
          /* setupTicks */ 0L,
          /* required */ true,
          ctx -> testEntitySpawnsCorrectly(ctx, data)))
      .toList();
}
```

The annotation is `net.minecraft.test.CustomTestProvider` in Yarn 1.21.1. Most Mojang-mapped / Forge tutorials call it `@GameTestGenerator` — same concept, different name.

When several behaviors fan out across the same variant list, factor the `Stream → map → TestFunction → toList` boilerplate into one `generatePerVariant(behavior, tickLimit, body)` helper per class and have each `@CustomTestProvider` delegate to it. Use a class-private `@FunctionalInterface` (`PerVariantBody(TestContext, VariantTestData)`) so generator call sites can pass method references without explicit casts. Keep the test-data type non-generic and construct it from the mod's variant presets.

When the contract differs per variant (e.g. one variant has no sound so the assertion flips to "must NOT play"), branch inside the generator body on the data (`data.expectedSound() == null`) rather than on the variant identity. The null-check stays correct for any future variant and keeps the test-data type as the single source of truth.

## Registration

After authoring any new gametest class, register it in `src/main/resources/fabric.mod.json`:

```json
"fabric-gametest": [
  "com.example.mymod.gametest.ExistingGameTest",
  "com.example.mymod.gametest.YourNewTestClass"
]
```

Unregistered test classes silently do nothing. The `runGametest` task loads only entries listed here.

## Running

```bash
./gradlew runGametest --no-daemon
```

Reports land at `build/gametest-results.xml` (JUnit-style XML). To debug a single class:

```bash
./gradlew runGametest --no-daemon 2>&1 | grep -E "GAME TESTS|required tests|<failure"
```

Run the same task in CI on every PR so the suite gates merges.

## Common Failures and Their Causes

| Failure | Likely cause | Fix |
|---|---|---|
| `TestContext$1 cannot be cast to class ...ServerPlayerEntity` | Used `(ServerPlayerEntity) createMockPlayer(...)` | Use `createMockCreativeServerPlayerInWorld()` |
| "still sleeping" / "auto-sleep" assertion fails sometimes | World clock drifted across the assertion window | Freeze `DO_DAYLIGHT_CYCLE` in `@BeforeBatch` |
| Entity not at expected position after >20 ticks | Entity wandered (AI on) or fell into the void (EMPTY_STRUCTURE has no floor) | Use an arena template; disable AI for pure-state tests |
| Sleep flag asserted true after `damage()` call but it's false | An auto-sleep goal re-slept the entity the very next tick | Assert in the same tick as the mutation, not 10 ticks later |
| A commanded flag asserted true after the action completes | Production intentionally clears it once the action lands | Test the transition (was true, now false) |
| Test passes sometimes, fails sometimes, no obvious cause | Cross-test contamination on world clock or static maps | Move tests into a named `batchId`; clear static state in `@BeforeBatch` |
| A block you "removed" is still there, but only for fluids | `removeBlock` sets the pos to its own fluid state, so removing water re-places water | `setBlockState(pos, Blocks.AIR)` |
| An entity is never rained on no matter how you set the weather | The framework roofs every structure with barriers unless the test opts out | `@GameTest(skyAccess = true)`, and remember weather is world state so pin it per batch |

## Skills you should use alongside this one

- `run-tests` — running the gametest suite, looking at reports
- dev-workflow `worktree` + `pr` — when shipping changes to gametests

## Don't

- Don't use `world.spawnEntity`/`world.setBlockState` inside a gametest class.
- Don't cast `createMockPlayer(...)` to `ServerPlayerEntity`.
- Don't rely on `useNightTime()`/`setTime(...)` as persistent pins.
- Don't put behavioral logic in `tickLimit = 20` if the goal selector needs 50+ ticks to fire.
- Don't paper over flakiness with `maxAttempts = 100`. Use it for genuine races and document why.
- Don't write tests that JUnit can do; reach for `src/test/java` first.
- Don't ship a new gametest class without adding it to `fabric.mod.json`.

---
name: run-tests
description: Run and write tests for a Fabric Minecraft mod (unit tests, game tests, manual testing). Use when asked to test, run tests, write tests, or verify changes in a Fabric mod repo.
---

# Run Tests

Run unit tests, game tests, and manual tests for the mod.

## Config

Read per the dev-workflow `config` skill:
- `commands.format` / `commands.test` / `commands.build`, defaults below
- `javaVersion`, default 21

## Quick Commands

```bash
# Apply code formatting
./gradlew spotlessApply

# Run unit tests
./gradlew test

# Run game tests (headless)
./gradlew runGametestServer

# Full build with all checks
./gradlew clean build

# Manual testing
./gradlew runClient
```

## Test Types

| Test Type | Location | Run Command |
|-----------|----------|-------------|
| Unit Tests (JUnit 5) | `src/test/java/` | `./gradlew test` |
| Game Tests | `src/main/java/.../gametest/` | `./gradlew runGametestServer` |
| Manual Tests | In-game client | `./gradlew runClient` |

## Java Toolchain

Ensure the configured Java version is active:
- **jenv**: `jenv local <version>`
- **SDKMAN**: `sdk use java <version>-amzn`

## Writing Tests

**Unit Test Example:**
```java
@Test
@DisplayName("Custom entity health should be higher than the vanilla baseline")
void testEntityHealth() {
	double entityHealth = 25.0;
	assertTrue(entityHealth > 20.0);
}
```

**Game Test Example:**
```java
@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
public void entitySpawns(final TestContext context) {
	// Spawn and test entity behavior
	context.complete();
}
```

> **Before writing any gametest, invoke the `gametest` skill.** The framework
> has many sharp edges (relative vs world coordinates, time-of-day drift,
> `createMockPlayer` not returning a `ServerPlayerEntity`, `EMPTY_STRUCTURE`
> having no floor) that have cost real PRs. The `gametest`
> skill encodes the patterns that actually work for Yarn 1.21.1 + Fabric
> Gametest API v1 (2.0.5+) and is the authoritative reference.

## Test Workflow

Before pushing changes:
1. `./gradlew spotlessApply` - Format code
2. `./gradlew test` - Run unit tests
3. `./gradlew runGametestServer` - Run game tests
4. `./gradlew runClient` - Manual test if functionality changed

## Related Skills

- `gametest`, required before authoring or editing any gametest
- `automated-qa`, for verifying visible surfaces in the live client

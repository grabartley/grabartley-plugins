---
name: run-tests
description: Run and write tests for a RuneLite plugin (unit tests, manual testing). Use when asked to test, run tests, write tests, or verify changes in a RuneLite plugin repo.
---

# Run Tests

Run unit tests and manual tests for the RuneLite plugin.

## Config

Read per the dev-workflow `config` skill:
- `commands.*`, `javaVersion` (default 17 for RuneLite)
- `repos.<slug>.runelite.runnerJarGlob`: the shadow jar path pattern
- `repos.<slug>.runelite.mainSourceRelease`, default 11

## Quick Commands

```bash
# Apply code formatting
./gradlew spotlessApply

# Run unit tests
./gradlew test

# Full build with all checks (runs spotlessCheck)
./gradlew clean build

# Manual testing in the RuneLite dev client
./gradlew shadowJar
java -ea --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED -jar <runnerJarGlob> --developer-mode --debug
```

If the plugin depends on an external provider (cloud TTS, external APIs), manual testing needs that provider's key set in the plugin config; the plugin does nothing until it is supplied.

## Test Types

| Test Type | Location | Run Command |
|-----------|----------|-------------|
| Unit Tests (JUnit 4) | `src/test/java/` | `./gradlew test` |
| Manual Tests | RuneLite dev client | `./gradlew shadowJar` then run the shadow jar |

## Java toolchain and language level

Build with a Java 17 toolchain (tests compile at release 17). Ensure it is active:
- **jenv**: `jenv local 17`
- **SDKMAN**: `sdk use java 17-amzn`

The plugin's **main sources compile at release 11** (`compileJava` sets `options.release=11`), because the Plugin Hub's `build=standard` compiles them at Java 11 with its own injected `build.gradle`. Do not use Java 12+ syntax or APIs in `src/main` (no records, no pattern-matching `instanceof`, no `Stream.toList()`, etc.); keep `compileJava` pinned to release 11 so `./gradlew build` fails locally on a violation, mirroring the Hub. Tests may use Java 17 freely.

## Writing Tests

All behavioral code changes require unit tests. Test classes mirror the production class name under test with a `Test` suffix and live in the same package structure under `src/test/java`.

**Unit Test Example (JUnit 4):**
```java
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VoiceManagerTest {
	@Test
	public void unknownNpcFallsBackToDefaultProfile() {
		// arrange the manager, act, then assert the selected profile
		assertEquals("default", selected);
	}
}
```

## Test Workflow

Before pushing changes:
1. `./gradlew spotlessApply` - Format code
2. `./gradlew test` - Run unit tests
3. `./gradlew clean build` - Full build with `spotlessCheck`
4. Run the shadow jar - Manual test if user-facing behavior changed

## Related Skills

- `run-game-client`, for the full dev client launch and login flow

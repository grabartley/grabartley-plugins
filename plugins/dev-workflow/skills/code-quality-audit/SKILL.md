---
name: code-quality-audit
description: Audit a whole repository against its code standards and docs, fix every finding with no change in behaviour, and ship the result as one fully tested pull request through the `build` skill. Covers DRY, KISS, single responsibility, duplication, single source of truth, class size, dead code, fake tests, flaky tests, package structure, and docs that drifted from the code. Use when asked for a code quality sweep, cleanup pass, tech debt sweep, standards audit, or docs sync across a repo, especially after a burst of feature work. Runs autonomously to a ready PR. Do NOT use to review a single PR (use `pr-local-review`) or to add features.
---

# Code Quality Audit

Sweep a repository for quality problems, fix them all, and finish with one clean pull request that is ready and fully tested. Behaviour does not change: the audit only restructures, deletes, deduplicates, fixes tests and syncs docs.

## Ground Rules

- **Run autonomously.** Never stop to ask. Make each call yourself, state it in the PR, and keep going until the PR is ready and CI is green. Assume the user is away until it is done.
- **No functional changes.** No behaviour, balance, config default, output or API changes. If a fix would change behaviour, leave it out and list it in the PR as a follow-up.
- **One PR.** Every fix ships in a single PR made through the `build` skill.
- **Be token efficient.** Scan with scripts, read only what the scans point at, and never dump whole files or logs into context. Save logs to the scratchpad and grep them.
- **No long-running agents or processes.** Run at most one subagent at a time. Give each one a single narrow, read-only task that finishes in 15 minutes or less. Run long test suites in the background and wait for the notification instead of polling.
- **The repo's own standards win.** Read `CLAUDE.md`, `AGENTS.md`, any `docs/standards*` or contributing guide, and the user's global instructions first. Where they set a rule, such as a class size limit or a no-comments rule, use theirs over the defaults below.

## What to Audit

| Area | Finding |
|---|---|
| Size | Any class or file over the repo's limit, or 700 lines when none is set. Split along responsibility seams into small named helpers, each with its own test. |
| SRP | A class that holds more than one concern. Extract a collaborator rather than growing it. |
| DRY and duplication | Repeated blocks of code, copy-pasted helpers, and test setup repeated across files. |
| Single source of truth | The same constant, list, rule or text defined in more than one place. Give it one home. |
| KISS | Machinery heavier than the job needs, such as a long run of near-identical methods that a small helper would replace. |
| Dead code | Unreferenced classes, methods, constants and locals, and production code that only tests use. Delete it, or move test-only helpers into the test source set. |
| Fake tests | Tests that can't fail: no assertion, assertions on constants or on the test's own setup, swallowed exceptions, source-scanning tests, reflection on structure rather than behaviour, and tests that compare a result with the same code path that produced it. |
| Test mapping | Unit tests that don't map one to one onto the class they test, by name and package. |
| Flaky tests | Tests that failed on the main branch without a code change. |
| Packages | Flat packages with clear feature seams, and code in the wrong source set. |
| Docs | README, design docs and guides that disagree with the code: settings, commands, defaults, names, counts, paths and broken links. |

## Process

### 1. Set up

Run the `build` skill from the start. Its workflow creates the worktree, the tracking issue and the board moves, and the audit happens inside that worktree. Let `build` create the issue with a scope of "audit and fix, no functional changes".

### 2. Scan mechanically

Write throwaway scripts in the scratchpad. Each prints a short list, never file contents:

- **File sizes:** the largest files by line count.
- **Duplication:** normalise source lines (trim them, skip blanks, imports and lone braces), hash every window of 8 lines, and report repeated windows grouped into clusters, biggest first. Rerun it after fixing to measure the drop.
- **Reachability:** start from the real entry points (main classes, framework entrypoint files, mixin or plugin configs, test runners), follow references by name, and list production files nothing reaches.
- **Unreferenced members:** non-private methods and constants named only at their declaration, and production members named only from tests. Expect false positives from overrides and framework callbacks, and confirm each one before deleting it.
- **Test mapping:** test files with no matching class, and tests in a different package from their class.
- **Assertion-less tests:** test bodies with no assertion or verification call.
- **Markers:** disabled tests, TODO and FIXME, and deprecated code.

### 3. Find the flaky tests from CI history

List recent failed runs on the main branch with `gh run list --branch <main> --status failure`. Save each failed log to the scratchpad and grep it for the failing test names, or read the test report annotations. For each flaky test, find the root cause in the test and the code it drives before changing anything. Then make it deterministic and simpler:

- Never assert on what a simulated agent decides, such as NPC or mob AI, schedulers or random timers. Assert the condition the agent acts on, and drive any movement yourself.
- End a test when the work it waits for reports that it is done, not on a guessed tick or time limit. A test server may run faster than real time, while background work runs on the clock.
- Keep tests apart. Tests that run side by side must not be able to reach each other's state.
- Never use retries as a flake guard. A test that passes on a retry is still flaky.

### 4. Delegate the reading that scripts can't do

Run one subagent at a time, read-only, on a cheaper model, each with a narrow brief that names the files to read and the sources of truth to check against:

- **Docs against code:** every concrete claim in the README and docs, checked against the code, each mismatch with file and line evidence on both sides.
- **Fake and flaky tests:** a ranked list of confirmed hits, each with a one-line fix.

Treat what comes back as leads. Confirm each claim in the code before acting on it.

### 5. Fix

Work in small batches, and compile after each one:

1. Flaky tests.
2. Dead code.
3. Code in the wrong source set.
4. Oversized classes.
5. Duplication and single source of truth.
6. Packages.
7. Docs.

Make bulk edits with scripts that assert they matched what they expected. When a move or rename touches many files, update the build and registration files and the docs paths in the same change. Give each new helper a unit test that maps to it. Don't write a test that only repeats the code under test: compare against values built independently.

### 6. Verify

- Format, lint, compile every source set, and run the unit tests.
- Run every integration or game test the change touched, using the repo's way of running a subset. Leave the full suite to CI.
- Commit only after the checks pass, and chain the push so it never runs after a failed check.
- Measure the audit again: rerun the size and duplication scans and compare.

### 7. Ship through `build`

Continue the `build` skill from the point where it opens the PR. It runs the review gate, the comment strip and the ready-for-review step. Fix every blocker the review returns, and take each suggestion that is cheap and clearly right.

The PR description says what the branch does, in the `pr` skill's style. Cover:
- each flaky test, with its cause and what it checks now (a table works well);
- the structural changes;
- the docs that were corrected;
- what was left alone, and why.

Watch CI until it is green. A failure from a head you have already replaced needs nothing.

## Report

End with a short summary for the user: the PR link, CI state, what was fixed by area, what was left and why, and anything skipped or unverified. Say plainly if any step went wrong, such as a commit pushed before its checks passed.

## Related Skills

- `build`, the end-to-end flow this skill runs inside, from the issue to a ready PR
- `pr-local-review`, the review gate `build` runs on the draft PR
- `strip-comments`, used by `build` so the PR introduces no comments
- `worktree` and `pr`, used through `build`

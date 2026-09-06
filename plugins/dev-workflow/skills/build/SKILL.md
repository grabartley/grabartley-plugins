---
name: build
description: Build or implement a feature end to end, optionally from a GitHub issue. Use when asked to build, implement, or ship scoped work and keep project board status updated.
---

# Build

## Config

Read per the `config` skill. `<slug>` is the current repo:
- `repos.<slug>.board.statusOptions` for the status names used below. No board config means skip board moves and say so.
- `repos.<slug>.issueFlow.assignOnStart`: when true, assign the issue to the developer running the build before moving it to `In progress`. Detect the current GitHub user with `gh api user --jq .login`, do not guess.
- `repos.<slug>.issueFlow.ciGreenBeforeQa`: when true, move to `QA testing` only after CI has completed green; when false, after CI is running.
- `commands.*` and `javaVersion` for validation runs.

## Critical Rules

1. Always tie build work to a GitHub issue.
2. Run the `worktree` skill first before any issue moves, coding, or validation.
3. Keep issue project status in sync during execution.
4. Any new behavioral code change must include extensive unit tests in the same PR. Do not ship untested code. Docs-only or config-only changes (for example `README.md`, `.gitignore`, or workflow and skill files) are exempt.
5. Unit tests MUST map to a single specific class. Test class name MUST match the class under test plus a `Test` suffix (e.g. `CoatRolls.java` -> `CoatRollsTest.java`), in the same package structure under `src/test/java`. A test that exercises `Foo` must be named `FooTest`, never `BarRelatedThingTest`.
6. Check for domain plugins before coding. If the repo has the `minecraft-modding` plugin enabled and the change touches gametest code or entrypoints, invoke its `gametest` skill BEFORE writing code. If the change has a visible or interactive surface, its `automated-qa` skill is a hard requirement before manual QA handoff, run at the point the review gate specifies. If the repo has the `runelite-dev` plugin enabled, respect its Java 11 main-source constraint (see its `run-tests` skill).
7. Run the `pr` skill as part of build after validation passes, opening the PR as a draft (`gh pr create --draft ...`).
8. The review gate below is mandatory. A draft PR never becomes ready for review while it has open blockers from that gate.
9. Automated QA runs only after the review gate is clear, so evidence is captured once against final code.
10. Move the issue to `QA testing` only after the PR is marked ready for review and the `ciGreenBeforeQa` condition is met. If CI fails, keep the issue in `In progress`, fix the failures on the same branch, and re-validate before transitioning.
11. After the PR is ready and the `QA testing` transition lands, always provide a detailed manual QA checklist to the developer. The checklist covers what automated validation could not; items already verified automatically are listed as pre-verified with a pointer to the evidence.
12. If PR code changes after the PR is opened, check whether the PR description still matches the current branch state, and update it if needed so it reflects the final state only.
13. Stop at `QA testing`, a human performs final verification and moves to `Done`.
14. Every code change must also update any docs it invalidates. Audit `README.md`, in-repo docs, and the linked issue body before committing; ship doc edits in the same PR as the code change.
15. If QA finds issues after handoff, re-enter the build flow for the same issue: move it back to `In progress`, continue on the existing branch and PR, and pass back through the review gate before marking it ready again. Do not open a new issue for the same scope.

## Workflow

1. Run the `worktree` skill to create a fresh isolated branch worktree, then perform all implementation and validation work inside that worktree.
2. Capture scope from the request.
3. If an issue number or URL is provided, read it first with gh:
	- `gh issue view <number> --repo <slug>`
	- Extract acceptance criteria, constraints, and references.
4. If no issue is provided, run the `create-issue` skill to create one before coding. Use the created issue as the tracking artifact for all subsequent status moves.
5. With `assignOnStart` on, assign the issue to the developer who called build.
6. Move the issue to `In progress`.
7. Implement the feature.
8. Run relevant automated tests and a local validation pass for changed behavior.
9. Invoke the `pr` skill for final checks, commit, push, and draft PR creation.
10. Run the review gate below: review, fix, then automated QA, then mark the PR ready for review.
11. Wait on CI per the `ciGreenBeforeQa` setting and report status.
12. Move the issue to `QA testing` when CI is satisfied and the PR is ready for human verification.
13. Provide a detailed manual QA checklist that the developer can run step by step.

## Review Gate

Runs between opening the draft PR and marking it ready for review. Every step, in order.

1. **Review.** Spawn a subagent on the latest Opus model whose only task is to invoke the `pr-local-review` skill against this PR number and return its punch list. Keep it in a subagent so the diff is read cold rather than by the agent that wrote it. That review is read-only: it never changes the branch the build worktree sits on.
2. **Triage.** Fix every blocker. Also take every suggestion that is cheap and clearly right; note which suggestions were skipped and why in the handoff report.
3. **Fix on the same branch and worktree**, then re-run `commands.format`, `commands.test`, and `commands.build`, and update the PR body if the final state moved. Run these against the worktree path explicitly, since a `cd` elsewhere earlier in the flow leaves the shell's working directory outside it.
4. **Confirm every fix reached the PR.** After pushing, check that the PR's head commit matches the worktree's, so a commit made on the wrong branch cannot pass as shipped.
5. **Re-run the gate** when the fixes changed design or behaviour. Stop once a review returns no blockers.
6. **Then run automated QA**, for example `automated-qa` from the `minecraft-modding` plugin for a visible surface. Running it earlier means capturing evidence for code that is about to change.
7. **Mark the PR ready**: `gh pr ready <number> --repo <slug>`.

## Board Status Policy

- Use these exact status values from the configured project board:
	- `Backlog`: issue created, not started
	- `Ready`: scoped and ready to start
	- `In progress`: active implementation
	- `QA testing`: implementation complete, awaiting human validation
	- `Done`: human-only final move after QA signoff

- Required transitions for build flow:
	- Start work: set to `In progress`
	- After the PR is ready for review and QA handoff: set to `QA testing`
	- Do not move to `Done` inside this skill

## Related Skills

- `worktree`, required first step for isolated branch setup
- `create-issue`, used when build work starts without an existing GitHub issue
- `pr`, required for commit, push, and draft PR creation during build flow
- `pr-local-review`, the mandatory review gate, run in an Opus subagent against the draft PR
- `config`, for how repo settings are resolved
- Domain plugins layer on top: `minecraft-modding` adds `gametest`, `automated-qa`, `run-game-client`; `runelite-dev` adds its client runner and release flow

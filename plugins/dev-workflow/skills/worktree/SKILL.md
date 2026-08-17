---
name: worktree
description: Set up a fresh git worktree from latest main for isolated implementation work. Use before build or PR workflows to prevent agents clashing in the same checkout.
---

# Worktree

Use this skill first when starting implementation work.

## Config

Read per the `config` skill:
- `repos.<slug>.worktreePrefix`, default: the repo name
- `repos.<slug>.copyRunDir`, default `false`. Fabric mod repos set this so the dev client and server can launch inside the worktree.

## Workflow

1. `git fetch origin main`.
2. Pick a short kebab-case branch name, for example `add-voice-cache`.
3. Create worktree: `git worktree add -b <branch-name> ./.claude/worktrees/<prefix>-<branch-name> origin/main`.
4. If `copyRunDir` is true, copy the dev `run/` directory into the worktree: `cp -a run/ ./.claude/worktrees/<prefix>-<branch-name>/run/`.
5. Perform all coding, validation, commit, and PR steps from inside `./.claude/worktrees/<prefix>-<branch-name>`.

## Conventions

- Always branch from latest `main`.
- Use a fresh worktree per branch, do not reuse active worktrees.
- Worktree path format: `./.claude/worktrees/<prefix>-<branch-name>`.
- Keep branch names concise and kebab-case.

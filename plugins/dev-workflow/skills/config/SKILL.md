---
name: config
description: How every grabartley-plugins skill reads its per-developer, per-repo configuration. Use when a skill needs a config value, when setting up the config file for the first time, or when diagnosing a skill that is missing board ids, commands, or repo settings.
---

# Plugin Config

All skills in this marketplace read one config file so the same skills work for any developer and any repo without editing skill text.

## Location

Default: `~/.grabartley-plugins/config.json`
Override: set the `GRABARTLEY_PLUGINS_CONFIG` environment variable to an absolute path.

## Reading values

Resolve the current repo slug first, then read keys scoped to it:

```bash
CONFIG="${GRABARTLEY_PLUGINS_CONFIG:-$HOME/.grabartley-plugins/config.json}"
REPO=$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null \
	|| git remote get-url origin | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')

jq -r --arg r "$REPO" '.repos[$r].board.projectId // empty' "$CONFIG"
```

Fallback order for any key: `.repos[$r].<key>`, then `.defaults.<key>`, then the skill's documented default. Example:

```bash
FORMAT_CMD=$(jq -r --arg r "$REPO" '.repos[$r].commands.format // .defaults.commands.format // "./gradlew spotlessApply"' "$CONFIG")
```

## Behavior when config is missing

- No config file at all: skills still work. Use documented defaults, skip config-only steps (like project board moves), and tell the user which steps were skipped and how to enable them.
- Repo not listed under `.repos`: same as above, plus offer to add an entry for the repo.
- Never fail a whole workflow because a config value is absent. Degrade gracefully and say so.

## First-time setup

1. Copy the example from the marketplace repo:
	```bash
	mkdir -p ~/.grabartley-plugins
	cp "${CLAUDE_PLUGIN_ROOT}/../../config.example.json" ~/.grabartley-plugins/config.json
	```
2. Fill in `github.owner` and one entry under `.repos` per repo you work in.
3. Board ids come from `gh project field-list <number> --owner <owner> --format json`.
4. If any board call later fails with "field not found" or "could not resolve", the board was likely recreated: refresh the ids with that same command and update the config.

## Key reference

| Key | Used by | Meaning |
|---|---|---|
| `github.owner` | create-issue, build | Default owner for project boards |
| `github.devDir` | pr-local-review | Where local clones live, default `~/dev` |
| `defaults.commands.{format,test,build}` | pr, build, run-tests | Shell commands for format, test, and full build |
| `defaults.javaVersion` | pr, build | JDK to activate before gradle runs |
| `repos.<slug>.worktreePrefix` | worktree | Worktree dir prefix, defaults to the repo name |
| `repos.<slug>.copyRunDir` | worktree | Copy `run/` into new worktrees (Fabric dev env) |
| `repos.<slug>.board.*` | create-issue, build | Project board ids and status option ids |
| `repos.<slug>.issueFlow.epics` | create-issue | Attach issues to `[Epic]` parents as sub-issues |
| `repos.<slug>.issueFlow.artLabels` | create-issue | Apply the `art` / `requires art` label system |
| `repos.<slug>.issueFlow.milestonesAutomated` | create-issue | Release pipeline owns milestones, never set by hand |
| `repos.<slug>.issueFlow.assignOnStart` | build | Assign the issue to the running developer before `In progress` |
| `repos.<slug>.issueFlow.ciGreenBeforeQa` | build | Require CI green, not just running, before `QA testing` |
| `repos.<slug>.issueFlow.prTemplate` | pr | Follow `.github/PULL_REQUEST_TEMPLATE.md` headings |
| `repos.<slug>.minecraft.*` | minecraft-modding plugin | Images branch, dev world name, gametest templates |
| `repos.<slug>.runelite.*` | runelite-dev plugin | Runner jar glob, client log path, Plugin Hub coordinates |

---
name: update-skill
description: Update one or more skills in the grabartley-plugins marketplace repo on a worktree, open a PR, squash merge it, and refresh the locally installed plugins. Use when asked to update, improve, fix, or add to a plugin skill, or to promote a learning from a project into the shared skills.
---

# Update Skill

End-to-end flow for changing marketplace skills: edit on an isolated worktree, ship via PR, squash
merge, and pull the change into the local plugin installation so it takes effect immediately.

Unlike the `build` flow, this skill merges its own PR: the marketplace repo has no QA stage, and
the change is not live locally until it lands on `main` and the marketplace refreshes.

## Config

Read per the `config` skill:
- `github.devDir`, default `~/dev`: the marketplace repo is expected at `<devDir>/grabartley-plugins`.
- The marketplace name is `grabartley-plugins` (matches the repo and the `~/.grabartley-plugins` config dir).

## Workflow

1. **Locate the marketplace repo** at `<devDir>/grabartley-plugins`. If it is not cloned, clone it
	from the `sources` URL in its marketplace listing before continuing.
2. **Capture scope.** Which skill or skills, in which plugin(s), and what should change. If the
	request is a learning from another project ("promote this gotcha into the gametest skill"),
	restate it generically first: marketplace skills must stay repo-agnostic, with repo-specific
	values living in `~/.grabartley-plugins/config.json` per the `config` skill.
3. **Worktree.** From the marketplace repo, run the `worktree` skill: fresh branch from latest
	`main`, kebab-case name describing the change (e.g. `gametest-weather-pinning`).
4. **Edit the skill(s)** inside the worktree, under `plugins/<plugin>/skills/<skill>/`. Rules:
	- Keep frontmatter `name` and `description` accurate; the description is the trigger, so update
	  it when the skill's scope changes.
	- No repo-specific ids, owners, paths, or names in skill text; route them through config keys
	  and document any new key in both the `config` skill's key reference and
	  `config.example.json`.
	- New skills need a `SKILL.md` in a new directory plus a mention in the repo `README.md` skill
	  table. Do not add a command that merely wraps a skill: skills are invocable directly, so
	  commands exist only for flows with no skill counterpart (like `setup-config`).
	- Bump the affected plugin's `version` in its `.claude-plugin/plugin.json`.
5. **Validate.** `jq` every touched `.json` file to prove it parses, and re-read each changed
	`SKILL.md` top to bottom for internal consistency (references to sections, templates, and
	config keys that actually exist).
6. **PR.** Run the `pr` skill from the worktree: commit, push, open the PR. Skip the gradle
	format/test/build steps, this repo has no build; the validation in step 5 is the pre-commit
	gate. No AI attribution anywhere.
7. **Squash merge** once the PR is open (and CI, if any, is green):
	```bash
	gh pr merge <number> --repo <owner>/grabartley-plugins --squash --delete-branch
	```
8. **Clean up the worktree**: `git worktree remove ./.claude/worktrees/<prefix>-<branch>` and
	`git -C <devDir>/grabartley-plugins pull` so the local checkout is on the merged `main`.
9. **Refresh the local plugin installation** so the merged change is live:
	```bash
	claude plugin marketplace update grabartley-plugins
	```
	If the `claude plugin` CLI is unavailable in this environment, tell the user to run
	`/plugin marketplace update grabartley-plugins` in their session instead, and note that
	marketplaces with `autoUpdate` enabled pick the change up on their own.
10. **Report**: what changed per skill, the merged PR URL, and whether the local refresh happened
	or is on the user.

## Conventions

- One PR per coherent change; several skills may move together when the change spans them (for
	example a new config key read by three skills).
- Commit and PR style follow the `pr` skill: `<type>: <lowercase description>`, prose-led
	present-tense body, no journey language, no file-change enumeration.
- Never merge someone else's open PR in the marketplace repo as a side effect; this skill merges
	only the PR it created.

## Related Skills

- `worktree`, isolated branch setup in the marketplace repo
- `pr`, commit, push, and PR conventions
- `config`, the schema every skill reads and where new keys must be documented

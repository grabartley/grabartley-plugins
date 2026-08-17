---
name: publish-version
description: Publish a new RuneLite plugin version end to end. Dispatches the repo's release workflow, waits for it, resolves the released tag's commit SHA, then opens the follow-up PR in runelite/plugin-hub that repoints the Hub at the new release commit. Use when asked to release, publish, ship, or cut a new version of the plugin.
---

# Publish Version

Use this skill to release a new plugin version. It runs two steps as one guided flow:

1. Dispatch the repo's release workflow, which computes the next semver, pins it into `runelite-plugin.properties` on a detached release commit, pushes the `v<version>` tag, and publishes a GitHub Release.
2. Open a PR against `runelite/plugin-hub` that updates only the `commit=` line in the plugin's Hub manifest to the new release tag's SHA, so the Hub builds the new version.

This skill only opens the Hub PR. Merging it is done by the Plugin Hub maintainers.

## Config

Read per the dev-workflow `config` skill. `<slug>` is the plugin repo; all Hub coordinates come from `repos.<slug>.runelite.hub`:
- `hub.releaseWorkflow`, e.g. `release.yml`
- `hub.upstream`, e.g. `runelite/plugin-hub` (base branch `master`)
- `hub.fork`, the developer's fork of plugin-hub
- `hub.manifest`, e.g. `plugins/my-plugin` (the `commit=` line is the only field this skill changes)
- Derived: Hub PR branch `<plugin>-v<version>`, Hub PR title `update <plugin>`, where `<plugin>` is the manifest basename

## Critical Rules

1. Never guess `bump` or `release_type`. Both are required release inputs; ask the user and refuse to proceed until you have both.
2. Do not edit the release workflow file. The skill dispatches it unchanged.
3. The Hub must be pinned to the tag's commit, not a branch HEAD. The release commit is detached and reachable only through the `v<version>` tag; resolving `main` gives the wrong SHA.
4. The Hub PR diff must touch only the `commit=` line of the manifest. Leave `repository=`, `authors=`, and `warning=` untouched.
5. If the release run fails mid-way, a dangling `v<version>` tag may already exist. Delete it before re-dispatching (see Recovery), or the re-run's tag push collides.
6. Everything you write here is public (release notes, Hub PR title and body). No local paths, private notes, or machine-specific details.

## Workflow

### 1. Collect release metadata

Ask the user for, and do not proceed without:

- `bump`: one of `patch`, `minor`, `major`.
- `release_type`: one of `alpha`, `beta`, `stable` (anything other than `stable` publishes as a prerelease).

Also ask what, if anything, they want highlighted in the Hub PR body beyond the auto-generated release notes (a headline feature, a time-sensitive reason to merge, etc.).

Optionally preview the version the workflow will compute so the user can sanity-check it. It bumps the highest existing `v*` tag by semver:

```bash
gh api repos/<slug>/tags --jq '.[].name' | grep '^v' | sort -V | tail -n1
```

The workflow, not this skill, is the source of truth for the final version.

### 2. Dispatch the release and wait

```bash
gh workflow run <releaseWorkflow> \
	--repo <slug> \
	-f bump=<bump> -f release_type=<release_type>
```

The dispatch returns no run id, so resolve the run just started, then watch it:

```bash
# give GitHub a moment to register the run, then grab the newest release-workflow run id
RUN_ID=$(gh run list --repo <slug> \
	--workflow <releaseWorkflow> --limit 1 --json databaseId --jq '.[0].databaseId')

gh run watch "$RUN_ID" --repo <slug> --exit-status
```

`--exit-status` makes the command fail if the run fails. On failure, surface the failing step:

```bash
gh run view "$RUN_ID" --repo <slug> --log-failed
```

Report the failing step, follow Recovery if a tag was pushed, and stop. Do not open a Hub PR for a failed release.

### 3. Resolve the released SHA

On success, determine the new version from the run (or the highest tag now present), then resolve the commit the tag points at, not `main`:

```bash
VERSION=$(gh api repos/<slug>/tags --jq '.[].name' \
	| grep '^v' | sort -V | tail -n1 | sed 's/^v//')

# The tag SHA: the detached release commit carrying the pinned runelite-plugin.properties.
RELEASE_SHA=$(gh api repos/<slug>/git/refs/tags/v$VERSION --jq '.object.sha')

# Annotated tags resolve one level deeper; if the above is a tag object, dereference it:
OBJ_TYPE=$(gh api repos/<slug>/git/refs/tags/v$VERSION --jq '.object.type')
if [ "$OBJ_TYPE" = "tag" ]; then
	RELEASE_SHA=$(gh api repos/<slug>/git/tags/$RELEASE_SHA --jq '.object.sha')
fi
```

Verify the GitHub Release exists for the tag before continuing:

```bash
gh release view "v$VERSION" --repo <slug> --json tagName,isPrerelease,url
```

Sanity-check that `RELEASE_SHA` is the release commit and not `main`'s HEAD (they differ whenever the version bump produced a commit):

```bash
gh api repos/<slug>/commits/$RELEASE_SHA --jq '.commit.message'   # expect "chore: release v<version>"
```

### 4. Open the plugin-hub PR

Sync the fork's `master` with upstream so the branch is cut from current upstream:

```bash
gh repo sync <hub.fork> --source <hub.upstream> --branch master
```

Work in a throwaway clone of the fork (use the session scratchpad, never the plugin repo's worktree):

```bash
git clone https://github.com/<hub.fork>.git "$SCRATCH/plugin-hub"
cd "$SCRATCH/plugin-hub"
git checkout -b "<plugin>-v$VERSION" origin/master
```

Edit only the `commit=` line, then confirm the diff is exactly that one line:

```bash
sed -i -E "s/^commit=.*/commit=$RELEASE_SHA/" <hub.manifest>
git diff <hub.manifest>    # must show only the commit= line changing
```

If `git diff` shows anything other than the single `commit=` line, stop and fix it. `repository=`, `authors=`, and `warning=` must be byte-for-byte unchanged.

Commit, push, and open the PR against upstream `master`:

```bash
git commit -am "update <plugin>"
git push origin "<plugin>-v$VERSION"

gh pr create \
	--repo <hub.upstream> \
	--base master \
	--head "<fork-owner>:<plugin>-v$VERSION" \
	--title "update <plugin>" \
	--body-file "$SCRATCH/hub-pr-body.md"
```

### 5. Hub PR body

Write the body to a file first (real newlines, public-facing). Model it on the repo's prior update PRs against the Hub:

- Opening line linking the release tag and stating the pin, e.g.:
	`Updates <plugin> to [v<version>](https://github.com/<slug>/releases/tag/v<version>). The pinned commit is the ` `` `v<version>` `` ` release tag commit.`
- If the user flagged a time-sensitive reason to merge, a short bold `**Time-sensitive:**` paragraph.
- A `Highlights since v<previous>:` list of a few player-facing bullets, derived from the generated release notes plus anything the user supplied. Keep them user-focused, not a changelog dump. Pull the notes with:

```bash
gh release view "v$VERSION" --repo <slug> --json body --jq '.body'
```

Keep the body concise and in the same voice as the prior PRs. Do not enumerate raw commit subjects; summarize into player-visible highlights.

### 6. Report back

Return to the user:

- New version and release type (prerelease or stable)
- Release URL
- Pinned `RELEASE_SHA`
- Hub PR URL

End here. The maintainers merge the Hub PR.

## Recovery

If the release run failed after the tag was pushed (any failure at or after the "Push release tag" step), the `v<version>` tag exists but the release may be incomplete. Delete it before re-dispatching:

```bash
gh api -X DELETE repos/<slug>/git/refs/tags/v<version>
```

Then re-run from step 2. If the failure was before the tag push (formatting, build, or test gate), no tag exists; fix the underlying cause on `main` first, then re-dispatch.

## Related Skills

- dev-workflow `pr`, for the branch, commit, and PR conventions this skill follows.
- dev-workflow `worktree`, for isolated branch setup when the release itself needs code changes first (out of scope here; this skill assumes `main` is release-ready).

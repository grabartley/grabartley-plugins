---
name: pr-local-review
description: Read a GitHub PR's diff from the local clone, changing no checkout, and review it against SOLID, DRY, KISS, correctness, docs, dead code, and antipatterns, then output a short, paste-ready punch list of blockers and suggested fixes. Use when the user gives a GitHub PR URL or PR number and asks to review, audit, sanity-check, or look at the PR before merge, and when the `build` skill runs its mandatory review gate on a draft PR. Do NOT use for general code reviews unrelated to a specific PR, and do NOT use just to fetch PR metadata.
---

# pr-local-review

A focused workflow for reviewing a GitHub PR locally and producing a paste-ready punch list for the agent that authored it.

The review is strictly read-only. It reads the PR's code out of the local clone through git object
reads and never checks anything out, so the branch every clone and worktree sits on is exactly what
it was before the review started.

## Config

Read per the `config` skill:
- `github.devDir`, default `~/dev`: where local clones live.

## When to use

Trigger this skill when the user:
- Gives a GitHub PR URL or PR number and asks to review it locally before merge.
- Asks for "blockers or suggested fixes" they can hand to an agent.
- Wants a sanity check on a PR an agent opened.

The `build` skill also invokes it automatically as its review gate, see "Invocation from build" below.

Do NOT use this skill for:
- General code review of the working tree.
- Posting review comments to GitHub.
- Just fetching PR metadata or summarizing the PR.

## Review criteria

Check the diff against every category below, not just the first thing you find.

- **SOLID**, with single responsibility weighted heaviest: a class or method doing two jobs, a leaky abstraction, a type switch where polymorphism belongs, a fat interface forcing empty implementations, a hardcoded dependency that should be passed in.
- **DRY**: logic duplicated inside the diff, or duplicating something the repo already has. Grep for an existing helper before accepting a new one.
- **KISS**: indirection, configuration, or generality the change does not need yet.
- **OOP practice**: encapsulation, mutable state escaping, inheritance where composition fits, static utility classes hiding stateful behaviour, constructors doing real work.
- **Correctness**: bugs, NPEs, off-by-ones, unhandled edge cases, broken contracts, concurrency and lifecycle mistakes in the changed paths.
- **Antipatterns**: god objects, primitive obsession, magic values, swallowed exceptions, boolean parameters that select behaviour, temporal coupling.
- **Dead code**: unreachable branches, unused fields, methods and imports, scaffolding left behind by the change.
- **Docs and comments**: README, in-repo docs, and doc comments the change invalidated, plus comments that only restate the code.
- **Tests**: new behaviour nothing covers, and test classes not named for the single class under test.
- **Anything else worth improving**, stated plainly rather than forced into a category above.

## Invocation from build

`build` runs this skill in a subagent on the latest Opus model against its own draft PR, before that PR is marked ready for review. In that mode:

- The branch is already checked out in the build worktree, so skip the fetch in step 3 entirely: review that worktree in place and diff its branch against the base. Switching that worktree to another branch strands the commits the authoring agent makes next on a branch the PR never sees, so leave its `HEAD` alone.
- Return the punch list as the subagent's result. Do not fix anything, `build` owns the fixes.
- Review the diff cold, on its own merits. Be exhaustive and let `build` triage.
- Drop the paste-ready framing: return the verdict line and the numbered punch list, no footer and no offer to apply the fixes.

## How to perform the review

1. **Resolve the repo path.** The user typically passes a GitHub URL. Map `github.com/<owner>/<repo>/pull/<num>` to a local clone under `devDir` (e.g. `<devDir>/<repo>`). If no local clone exists, ask the user where to clone or whether to review from `gh` output alone.

2. **Pull PR metadata.** Always use the `gh` CLI for GitHub operations:
	```
	gh pr view <num> --json title,body,headRefName,baseRefName,state,mergeable,files
	```
	Read the `body` carefully, it usually states the intent and scope. Note the `mergeable` status, base branch, and the `files` list so you know what to inspect.

3. **Resolve the PR head to a commit, without checking it out.** Never run `git checkout`, `git switch`, or `git worktree add`, and never create a local branch. Every clone and worktree stays on the branch it was already on.

	First look for a checkout that already holds the PR's head branch, which is the normal case when `build` runs the gate:
	```
	git -C <repo> worktree list
	git -C <repo> rev-parse --verify <headRefName>
	```
	If the branch exists locally, use it by name as the review revision. If a worktree is already sitting on it, read files from that worktree's path directly.

	Otherwise fetch the PR head into the object store only, with no ref and no branch:
	```
	git -C <repo> fetch origin refs/pull/<num>/head
	git -C <repo> rev-parse FETCH_HEAD
	```
	Capture that SHA and use it as the review revision. `FETCH_HEAD` is overwritten by any later fetch, so pin the SHA rather than referring to `FETCH_HEAD` throughout.

4. **Diff the review revision against the PR's base branch** (from step 2's `baseRefName`, often `main`):
	```
	git -C <repo> diff origin/<base>...<rev> --stat
	git -C <repo> diff origin/<base>...<rev> -- <specific files>
	```
	For large PRs, scope diffs by file or directory rather than dumping the whole thing.

5. **Inspect what the diff doesn't show.** For non-trivial findings, read the surrounding file context (class hierarchy, callers, related entities) so the suggestions hold up.

	When a worktree already holds the branch, `Read` and `grep` its files directly. Otherwise read at the revision, which needs no checkout:
	```
	git -C <repo> show <rev>:<path>
	git -C <repo> grep <pattern> <rev> -- <path>
	git -C <repo> ls-tree -r --name-only <rev> <dir>
	```

6. **Optional but encouraged:** if the project has a fast test or lint command, mention to the user whether you ran it. Do not run long-running builds/tests without the user asking.

## Safety rules

- **Never change what is checked out.** No `checkout`, `switch`, `worktree add`, `branch`, `merge`, `pull`, `stash`, or `rebase`, in the main clone or in any worktree, even when the review revision is awkward to reach without one. The only git writes this skill ever performs are fetching PR heads into the object store.
- **Leave no new refs behind.** Fetch with `refs/pull/<num>/head` and pin the resulting SHA. Do not create local branches such as `pr-<num>`, and do not delete refs to tidy up after yourself.
- **Confirm the checkout is untouched before reporting.** Record `git -C <repo> rev-parse --abbrev-ref HEAD` for every clone and worktree you touched at the start, check it again at the end, and say so in the footer. An agent may be committing to one of those worktrees while the review runs.
- **No destructive git ops** without explicit user request: no `reset --hard`, no `push`, no `branch -D`, no `checkout --` of working files, no force-fetch over a branch the user might have local work on.
- **Use `gh` for all GitHub interactions.**
- **Don't post the review to GitHub.** Output is for the user to paste, not for `gh pr comment`.
- **Don't propose to apply the fixes yourself** unless the user asks. The whole point of this skill is that the user wants to hand the punch list to another agent.

## Output format

Keep the response tight. Match the user's tone (their global instructions set the voice; do not invent your own). Structure:

1. **One-line verdict** at the top: "no blockers, ship-ready" or "blockers found, see below" plus any quick vibe-check.

2. **A horizontal rule (`---`) and then a paste-ready block** the user can copy directly to the authoring agent. The block must:
	- Open with a bolded verdict line: `**No blockers, ready to merge. Suggested polish to send to the agent:**` or `**Blockers found, please address before merge:**`.
	- Use a numbered list. Each item names the file (and function/class when useful), describes the issue concretely, and proposes a fix. Reference code with `path:line` where it helps.
	- Separate blockers from non-blocking suggestions if both exist. Blockers first.
	- Avoid speculation; if a concern depends on code not in the diff, flag it as "worth confirming" rather than asserting it.

3. **Closing horizontal rule (`---`) and a one-line footer** noting:
	- Which commands you ran (e.g. `gh pr view` + `git fetch` + diff), and that every checkout is still on the branch it started on.
	- An offer to apply the fixes yourself if the user would rather not hand them off.

## Style rules for the output

- Follow the user's global style instructions.
- No code comments added to suggestions unless the suggestion is literally "add a comment because the invariant is non-obvious".
- Keep each numbered item to one short paragraph. If it needs more, it is probably two suggestions.
- Do not narrate your thought process. State findings.
- Do not add a trailing summary after the footer.

## What counts as a blocker vs a suggestion

**Blocker** (must fix before merge):
- Correctness bug the diff introduces.
- Security regression (auth bypass, injection, missing server-side validation of a client request, etc.).
- Breaks an existing test or contract.
- Crashes, NPEs, or obviously broken edge cases in the changed code paths.
- Public API change that contradicts the PR description.
- Docs the change makes wrong: README, in-repo docs, or doc comments that now contradict behaviour.
- Behavioural code shipping with no test coverage.
- Logic duplicating a helper that already exists in the repo.

**Suggestion** (nice to have, not blocking):
- Style, naming, comment quality, magic numbers.
- Missing i18n if the rest of the project uses it.
- Test naming or coverage gaps where the underlying behavior is correct.
- Defensive copies, immutability hardening, dead code cleanup.
- Refactors that would be cleaner but are not required, including SOLID and KISS cleanups that leave behaviour unchanged.

When unsure, default to "suggestion" and say "worth confirming" rather than escalating to blocker.

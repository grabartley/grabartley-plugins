---
name: pr-local-review
description: Locally check out a GitHub PR and review the diff, then output a short, paste-ready punch list of blockers and suggested fixes the user can hand directly to the agent that wrote the PR. Use when the user gives a GitHub PR URL or PR number and asks to review, audit, sanity-check, or look at the PR before merge. Do NOT use for general code reviews unrelated to a specific PR, and do NOT use just to fetch PR metadata.
---

# pr-local-review

A focused workflow for reviewing a GitHub PR locally and producing a paste-ready punch list for the agent that authored it.

## Config

Read per the `config` skill:
- `github.devDir`, default `~/dev`: where local clones live.

## When to use

Trigger this skill when the user:
- Gives a GitHub PR URL or PR number and asks to review it locally before merge.
- Asks for "blockers or suggested fixes" they can hand to an agent.
- Wants a sanity check on a PR an agent opened.

Do NOT use this skill for:
- General code review of the working tree.
- Posting review comments to GitHub.
- Just fetching PR metadata or summarizing the PR.

## How to perform the review

1. **Resolve the repo path.** The user typically passes a GitHub URL. Map `github.com/<owner>/<repo>/pull/<num>` to a local checkout under `devDir` (e.g. `<devDir>/<repo>`). If no local clone exists, ask the user where to clone or whether to skip the local checkout.

2. **Pull PR metadata.** Always use the `gh` CLI for GitHub operations:
	```
	gh pr view <num> --json title,body,headRefName,baseRefName,state,mergeable,files
	```
	Read the `body` carefully, it usually states the intent and scope. Note the `mergeable` status, base branch, and the `files` list so you know what to inspect.

3. **Fetch and check out the PR branch locally.** Use a stable local ref name:
	```
	git fetch origin pull/<num>/head:pr-<num>
	git checkout pr-<num>
	```
	If the branch already exists, fetch will fail; in that case fetch with `+refs/pull/<num>/head:pr-<num>` to force-update, or `git checkout pr-<num>` then `git pull` only after confirming with the user. Never reset or force-update the user's working branches.

4. **Diff against the PR's base branch** (from step 2's `baseRefName`, often `main`):
	```
	git diff <base>...pr-<num> --stat
	git diff <base>...pr-<num> -- <specific files>
	```
	For large PRs, scope diffs by file or directory rather than dumping the whole thing.

5. **Inspect what the diff doesn't show.** For non-trivial findings, read the surrounding file context (class hierarchy, callers, related entities) so the suggestions hold up. Use `Read` and `grep` for this, not just the diff.

6. **Optional but encouraged:** if the project has a fast test or lint command, mention to the user whether you ran it. Do not run long-running builds/tests without the user asking.

## Safety rules

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
	- That you did no destructive ops, and which commands you did run (e.g. `gh pr view` + `git fetch` + checkout + diff).
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

**Suggestion** (nice to have, not blocking):
- Style, naming, comment quality, magic numbers.
- Missing i18n if the rest of the project uses it.
- Test naming or coverage gaps where the underlying behavior is correct.
- Defensive copies, immutability hardening, dead code cleanup.
- Refactors that would be cleaner but are not required.

When unsure, default to "suggestion" and say "worth confirming" rather than escalating to blocker.

---
name: prd
description: Turn a mod concept, a pile of requirements, or an existing issue backlog into a product requirements document for a Minecraft mod, refined with the user and shipped into the repo or written to the Desktop when there is no repo. Use when asked for a PRD, a product spec, a requirements document, or a single statement of what a mod does and does not do. Also use when a project's requirements exist only as scattered issues and nobody can answer "what does this mod support" without reading the whole board.
---

# prd

A mod PRD answers one question: **what is this mod**. Which use cases it serves, who is allowed to do what, which side enforces each rule, and what it deliberately does not do.

It is not a plan, not a design document, and not a tracker. Those exist elsewhere and this must not duplicate them, because a second source of truth drifts within a week and then neither can be trusted.

## Two Rules That Shape Everything

Both are decided before drafting, because retrofitting either means rewriting most of the document.

**No status, ever.** Nothing in the document says what is built, in progress, or blocked. No "shipped", no "outstanding", no issue links used as progress pointers. Progress lives on the board. A requirement belongs here whether it shipped a year ago or has not been started, because both are equally part of what the mod is.

The test: if merging a pull request would require editing this document, something in it is status. Take it out.

**Client and server, never singleplayer and multiplayer.** A single-player world is an integrated server with one client, running the same code down the same path as a dedicated server with two hundred. A requirement written as "in multiplayer, check permission" invites an implementation that skips the check when it thinks it is alone, and the mod is wrong the moment somebody opens their world to LAN.

Never frame a requirement around player count. Frame it around which side decides, and name that side in every use case.

## Config

Read per the dev-workflow `config` skill:
- `repos.<slug>` to confirm the current repo is configured, which decides how the document ships.

No new keys. The document is written from the repo's own contents and from the user.

## Inputs

Three starting points, and the work differs mostly at the front.

| Input | What to do first |
|---|---|
| **A concept** ("a mod that does X") | Everything is a question. Go straight to **Refining With The User**; there is nothing to research yet |
| **A set of requirements** | Sort them into use cases and find the gaps: permissions, concurrency, persistence, and failure are the ones loose requirements always miss |
| **An issue board or backlog** | Research first, per below. The requirements already exist; the work is gathering, deduplicating, and finding what the board never wrote down |

Most real requests are the third, or a mix. A repo with a board also has a codebase, and the codebase is the more reliable source. See **Accuracy**.

## Workflow

1. **Gather.** For a board, read every issue and epic body, not just titles. Read the architecture decision records if the repo keeps them, the README, and any standards document. For a concept, skip to step 2.
2. **Settle scope before drafting.** Which release is being specified, and what is deliberately out. These are the cheapest decisions to change now and the most expensive later, so ask before writing. See **Refining With The User**.
3. **Draft** against **Document Structure**.
4. **Verify every claim** against the source, per **Accuracy**. This is the step that decides whether the document is worth having.
5. **File what the verification finds.** A place where the code and the intended rule disagree is a bug, not a documentation problem. See **Contradictions Are Findings**.
6. **Review with the user.** Give them the parts only they can judge, per **Refining With The User**.
7. **Ship** per **Shipping The Document**.

## Document Structure

Ten numbered sections and an unnumbered preface. Four of them exist because this is a Minecraft mod rather than generic software: permissions, client and server, persistence, and the deferred-scope boundary.

Every requirement carries a stable identifier, prefixed by area and numbered from 1, so it can be cited from an issue, a commit, or a review. Identifiers are contiguous within a prefix.

### Preface: How To Read This Document

States the contract in three short paragraphs: this says what the product is, it holds no status because progress lives on the board, and it changes only when requirements change or turn out to be unclear. Point at the decision records for reasoning.

This is what stops the next contributor adding a status column back.

### 1. Product Summary

- **The problem**, in the player's terms, not the implementer's
- **What the mod is**, in a paragraph somebody could repeat back
- **What makes it different**, as a table of claim and why it holds
- **Non-goals**, the things it will never be. A storage mod is not a logistics mod; say so

### 2. Release Scope

Names the release under specification and states what that release accepts and refuses, as a two-column table. An alpha tolerates unpolished; it never tolerates unsafe.

A **Deferred** table records what is out and why, so the boundary is deliberate rather than accidental. Harvest these from the "out of scope" sections the issues already carry.

### 3. Actors

Who uses the mod and what they need. For a mod this is rarely just "player":

- The player
- The owner of a placed thing, where ownership exists
- The server operator
- The pack author, who tunes performance caps and ships a configuration
- **The vanilla client**, when any behaviour is server-side. A player with no client mod is an actor, not an edge case, and naming them forces the question of what still works for them

### 4. Domain Model

The vocabulary, as a table of term and definition, so use cases can be terse. Define anything the requirements lean on: the block, the network, the container, the role, the tag, the lock.

Define geometric and numeric terms precisely. "Radius" must say whether it is a sphere radius or a cuboid half-extent, because it decides which of a player's blocks are included.

Add a subsection for any eligibility or membership rule, as a table of what is in, what is out, and why not.

### 5. Use Cases

The bulk of the document. One per thing a player sets out to do, not one per epic: epics are engineering groupings and use cases are player goals.

Each carries:

- **Actor**
- A paragraph of what happens, in order
- A table of interactions where the use case is a gesture
- A table of numbered requirements
- **Not supported**, an explicit list. This is the half most documents omit and the half that stops scope creep
- **Enforcement**, naming the side. Every use case, no exceptions

### 6. Permissions

Its own section, because access control is the difference between a storage mod and a griefing tool, and because it must be readable in one glance rather than scattered.

- The access modes, if the mod has them, and which is the default
- A **capability matrix**: rows of capability, columns of actor, cells of yes, no, or the condition
- Enforcement requirements

State the adversary explicitly: **a modified client is assumed**. Hiding a control is never the enforcement of a rule. Where an entity can exist without an owner, say who administers it, because the tempting inference is usually the dangerous one.

### 7. Client and Server

Open by stating there is no singleplayer mode in the architecture, and why the document is framed this way.

- **The boundary**, as a table of what the server owns and what the client does
- **Concurrency**, which exists whatever the player count: a hopper and a hand-opened container race a single player
- **Per-player state**, what is scoped to one player and syncs only to them
- **Side separation**, what must not load where

### 8. Non-Functional Requirements

- **Safety.** For anything that moves items or grants rewards, conservation is the property that decides whether the mod is safe in a pack. State it as requirements, not as a hope
- **Performance**, as budgets and triggers rather than adjectives. "Costs nothing while idle" is a requirement; "fast" is not
- **Persistence**, as a grid. Rows of what, columns of the ways a world can interrupt it: power loss, chunk unload, world reload, death. Four axes that only exist in this domain
- **Accessibility**, including the rule that no state distinction relies on colour alone
- **Compatibility**, covering vanilla parity, optional integrations, and every declared dependency being genuinely used

### 9. Release Criteria

Conditions that must hold for a release, whatever the schedule. Written as properties, never as a checklist of issues.

### 10. References

Point at the board for progress, the issues for acceptance criteria, the decision records for reasoning, and the standards document. State plainly that this document holds none of those.

## Accuracy

A PRD that misstates behaviour is worse than none, because it will be cited in review to reject correct code.

**The codebase outranks the issues.** Issues are what was intended when they were written. Decision records supersede issues, and code supersedes both. When a repo has decision records, read them before drafting: a record that revises an earlier decision means the issue that specified it is now wrong, and a requirement copied from that issue inherits the error.

**Read values, never recall them.** Defaults, bounds, sizes, and cooldowns are read from the constants that define them, not from an issue table and not from memory. Grep the whole constant block rather than the first few lines.

**Verify a representative sample of every behavioural claim**, and all of any claim about permission. Permission rows are the ones that will be cited, and the ones an implementer will trust rather than re-derive.

Claims worth checking every time:

- Every default and every bound, against the configuration type
- Every permission gate, against the code that gates it, not against the issue that specified it
- Every routing, ordering, or matching rule, against the implementation
- Every "only" and every "never", which are the words most likely to be too strong
- Whether a command path and an interface path really share one rule, wherever the document claims they do

## Contradictions Are Findings

When verification shows the code does not do what the intended rule says, that is a bug, and finding it is the document paying for itself. Do not smooth it over by writing down what the code happens to do.

- State the intended rule in the document
- File the gap as its own issue, with the intended behaviour, the current behaviour, and the file
- Reference the issues from the pull request body so nobody reads the document as a description of what ships today

Do not fix the code in the same change. A documentation pull request that also changes behaviour is two reviews wearing one hat.

**A flagged inconsistency is not automatically a mandate to change code.** Two things can disagree because they describe different concerns. Establish which is authoritative before editing either, and prefer changing nothing outside the document's own scope.

## Refining With The User

The user decides intent. Research decides fact. Never spend their attention on something the repo can answer.

**Ask before drafting**, because these are cheap now and expensive later:

- Which release is being specified, and what that release means for quality
- What is deliberately out of scope
- Anything the inputs leave genuinely ambiguous, where different readings produce materially different documents

**Ask after drafting**, giving them the parts only they can judge:

- The use cases, read as the product owner rather than the implementer. Verification proves requirements match the code; only the user can say they match the intent
- Anything unbuilt, where there is no code to have checked against and their intent is the only source
- The deferred table, since inferring a boundary from scattered "out of scope" notes is guesswork
- The capability matrix, which is the section most likely to be cited later

**Do not ask** what a grep would answer, and do not ask them to confirm findings that verification already settled.

Expect the structure itself to draw feedback, especially on the two hard rules. Restate why they exist rather than quietly conceding: a status column and a singleplayer section are exactly the things that rot.

## Shipping The Document

**In a repo:** the document goes to `docs/prd.md` and is linked from the README alongside the other documentation. Ship it through the dev-workflow `build` skill, which creates the tracking issue, the worktree, the pull request, and runs the review gate.

Audit what the new document invalidates and fix it in the same change. A repo with decision records usually has a line somewhere saying the issues are where to find what the mod does; that line is now wrong.

The review gate matters more here than for code, because a documentation change has no test suite. Ask the reviewer to check factual accuracy against the source and the issue set, not just prose quality, and to say plainly whether the document is accurate enough to become the project's reference.

**With no repo:** write it to `~/Desktop/<mod-name>-prd.md` and tell the user where it is. A concept with no repository yet is the common case for this path, and the document is what the repository gets built from.

## Gotchas

- **Writing from the issues alone produces a confidently wrong document.** The issues are a snapshot of intent at the time each was written. Read the decision records and the code.
- **A "not supported" list is not padding.** It is what a future reader cites when somebody proposes the thing the mod deliberately does not do.
- **Requirements that restate each other under two identifiers will drift.** Pick one home and cross-reference.
- **Engineering standards are not product requirements.** "Every mutating path adds a test" belongs in the standards document; cite it rather than restating it.
- **An absolute is usually too strong.** "Never read from", "only on these triggers", "every setting is reachable through". Check each one; most need a scope or an exception.
- **Renumber in document order after inserting a requirement.** Renaming identifiers one at a time collides with the one just inserted.
- **The deferred table is the most valuable table in the document** and the easiest to leave empty. Harvest it from the "out of scope" sections the issues already have.

## Related Skills

- dev-workflow `build`, which ships the document into a repo through the full issue, worktree, and review flow
- dev-workflow `create-issue`, for the gaps verification finds
- dev-workflow `config`, for confirming the repo is configured
- `gametest` and `automated-qa`, which prove the requirements this document states

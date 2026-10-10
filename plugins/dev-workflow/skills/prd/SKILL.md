---
name: prd
description: Turn a product concept, a pile of requirements, or an existing issue backlog into a product requirements document for any general software product (web app, CLI, library, service, mobile or desktop app), refined with the user and shipped into the repo or written to the Desktop when there is no repo. Use when asked for a PRD, a product spec, a requirements document, or a single statement of what a product does and does not do. Also use when a project's requirements exist only as scattered issues and nobody can answer "what does this product support" without reading the whole board. For a Minecraft mod, use the minecraft-modding plugin's `prd` skill instead.
---

# prd

A PRD answers one question: **what is this product**. Which use cases it serves, who is allowed to do what, where each rule runs and who decides it, and what it deliberately does not do.

It is not a plan, not a design document, and not a tracker. Those exist elsewhere and this must not duplicate them, because a second source of truth drifts within a week and then neither can be trusted.

## Two Rules That Shape Everything

Both are decided before drafting, because retrofitting either means rewriting most of the document.

**No status, ever.** Nothing in the document says what is built, in progress, or blocked. No "shipped", no "outstanding", no issue links used as progress pointers. Progress lives on the board. A requirement belongs here whether it shipped a year ago or has not been started, because both are equally part of what the product is.

The test: if merging a pull request would require editing this document, something in it is status. Take it out.

**Where it runs and who decides, never the deployment shape.** Frame every requirement around the place it runs and the party that decides it: the user's device, your server, a third-party service. Do not frame it around a shape that hides that boundary, such as "in the hosted version", "when self-hosted", "for single users", or "in offline mode". Those shapes run the same code down the same path, and a requirement written as "in the team version, check permission" invites an implementation that skips the check when it thinks nobody else is there. The product is then wrong the first time the shape changes.

Never frame a requirement around how the product happens to be deployed or how many people use it. Frame it around where it runs and who decides, and name that place in every use case.

## Config

Read per the `config` skill:
- `repos.<slug>` to confirm the current repo is configured, which decides how the document ships.

No new keys. The document is written from the repo's own contents and from the user.

## Inputs

Three starting points, and the work differs mostly at the front.

| Input | What to do first |
|---|---|
| **A concept** ("an app that does X") | Everything is a question. Go straight to **Refining With The User**; there is nothing to research yet |
| **A set of requirements** | Sort them into use cases and find the gaps: access, concurrency, persistence, privacy, and failure are the ones loose requirements always miss |
| **An issue board or backlog** | Research first, per below. The requirements already exist; the work is gathering, deduplicating, and finding what the board never wrote down |

Most real requests are the third, or a mix. A built product also has a codebase, and the codebase is the more reliable source. See **Accuracy**.

## Workflow

1. **Gather.** For a board, read every issue and epic body, not just titles. Read the architecture decision records if the repo keeps them, the README, and any standards document. For a concept, skip to step 2.
2. **Settle scope before drafting.** Which release is being specified, and what is deliberately out. These are the cheapest decisions to change now and the most expensive later, so ask before writing. See **Refining With The User**.
3. **Draft** against **Document Structure**.
4. **Verify every claim** against the source, per **Accuracy**. This is the step that decides whether the document is worth having.
5. **File what the verification finds.** A place where the code and the intended rule disagree is a bug, not a documentation problem. See **Contradictions Are Findings**.
6. **Review with the user.** Give them the parts only they can judge, per **Refining With The User**.
7. **Ship** per **Shipping The Document**.

## Document Structure

Ten numbered sections and an unnumbered preface. Leave a section out only when it truly does not apply, and say so in one line rather than deleting the heading, so the reader knows it was considered.

Every requirement carries a stable identifier, prefixed by area and numbered from 1, so it can be cited from an issue, a commit, or a review. Pick short prefixes that name the area (`AUTH-3`, `SYNC-2`, `PERF-1`). Identifiers are contiguous within a prefix.

### Preface: How To Read This Document

States the contract in three short paragraphs: this says what the product is, it holds no status because progress lives on the board, and it changes only when requirements change or turn out to be unclear. Point at the decision records for reasoning and at wherever the engineering standards live.

This is what stops the next contributor adding a status column back.

### 1. Product Summary

- **The problem**, in the user's terms, not the implementer's
- **What the product is**, in a paragraph somebody could repeat back
- **The experience it aims for**, how it should feel to use, as a short list of concrete traits. This is what lets a reviewer reject a change that meets every requirement and still makes the product worse
- **What makes it different**, as a table of claim and why it holds
- **Non-goals**, the things it will never be. A note-taking app is not a project manager; say so

### 2. Release Scope

Names the release under specification and states what that release accepts and refuses, as a two-column table. An early release tolerates unpolished; it never tolerates unsafe.

A **Deferred** table records what is out and why, so the boundary is deliberate rather than accidental. Harvest these from the "out of scope" sections the issues already carry. The reason column must come from the sources too; see **Accuracy**.

### 3. Actors

Who or what the product deals with and what each needs. This is rarely just "user":

- Each kind of person, split by what they need rather than by job title: a first-time user, a returning user, an admin, the person who receives something shared
- **External systems**, such as the services the product calls and the services that call it. They are not people, but they rate limit, go down, change their responses, and return untrusted content. Naming them forces every use case to say what happens when one misbehaves
- **Degraded environments**, such as a browser with storage blocked, a device offline, an old client talking to a new server, or a screen reader. Each is an actor, not an edge case, and naming it forces the question of what still works there

### 4. Domain Model

The vocabulary, as a table of term and definition, so use cases can be terse. Define anything the requirements lean on: the record, the account, the workspace, the role, the job, the link.

Define numeric and boundary terms precisely. "Recent" must say how recent, and "limit" must say per what, because it decides which cases are in.

Add a subsection for any eligibility or membership rule, as a table of what is in, what is out, and why not.

### 5. Use Cases

The bulk of the document. One per thing a user sets out to do, not one per epic: epics are engineering groupings and use cases are user goals.

Each carries:

- **Actor**
- A paragraph of what happens, in order
- A table of interactions where the use case is a gesture, a command, or a set of inputs
- A table of numbered requirements
- **Not supported**, an explicit list. This is the half most documents omit and the half that stops scope creep
- **Where it runs**, naming the place and who decides. Every use case, no exceptions

### 6. Access and Privacy

Its own section, because who may do what and where data goes are the parts people cite when something goes wrong, and because they must be readable in one glance rather than scattered across use cases.

- **A capability matrix**: rows of capability, columns of actor, cells of yes, no, or the condition. Include the default access level if there is one
- **A data-flow matrix**: rows of each kind of data the product handles, columns of every place it could go (your server, each outside service, local storage, logs, analytics, shared links), cells of yes, no, or the condition
- Enforcement requirements

State the adversary explicitly: **a hostile client and hostile inputs are assumed**. Hiding a control is never the enforcement of a rule, and anything from outside the product's own code, whether user input, a third-party response, or a shared link, is untrusted until checked. Where something can exist without an owner, say who administers it, because the tempting inference is usually the dangerous one.

If the product has only one kind of user, the capability matrix may be short, but the data-flow matrix still applies.

### 7. Where It Runs

Open by naming every place the product's code runs and why the document is framed around those places rather than around a deployment shape.

- **The boundary**, as a table of what the product owns and what outside services provide
- **Limits of outside services**, as a table of each service, its limit, and the product's budget against it, where the product depends on one
- **Concurrency**, which exists whatever the user count: two tabs, a retry, and a background job race a single user
- **Per-user state**, what is scoped to one user and never leaks to another
- **Separation**, what must not run or load where, such as secrets in client code

### 8. Non-Functional Requirements

Use the subsections that apply. Each is a table of numbered requirements, except persistence.

- **Simplicity**, what the product refuses to make the user do: setup, accounts, prompts, configuration. Only where simplicity is a stated goal
- **Cost**, what the product may and may not depend on paying for, where cost is a constraint
- **Performance**, as budgets and triggers rather than adjectives. "Shows a result within 2 seconds" is a requirement; "fast" is not
- **Persistence**, as a grid. Rows of what is stored, columns of the ways the product can be interrupted. Pick the axes that fit: page reload, crash, restart, deploy, network loss, storage blocked, data cleared, new device, upgrade
- **Accessibility**, including the rule that no state relies on colour alone
- **Compatibility**, covering the platforms, browsers, runtimes, and versions supported, optional integrations, and every declared dependency being genuinely used
- **Security**, covering anything not already in **Access and Privacy**: secrets handling, input validation, dependency trust

### 9. Release Criteria

Conditions that must hold for a release, whatever the schedule. Written as properties, never as a checklist of issues.

### 10. References

Point at the board for progress, the issues for acceptance criteria, the decision records for reasoning, and the standards document. State plainly that this document holds none of those.

## Accuracy

A PRD that misstates behaviour is worse than none, because it will be cited in review to reject correct code.

**Know which source outranks which.** For a product that is not built yet, the issues and the owner's decisions are the source of intent, and the owner's decisions win when they disagree with an issue. For a built product, issues are what was intended when they were written. Decision records supersede issues, and code supersedes both. When a repo has decision records, read them before drafting: a record that revises an earlier decision means the issue that specified it is now wrong, and a requirement copied from that issue inherits the error.

**Every requirement is owned by an issue, or it comes out.** If no issue or owner decision backs a requirement, either file the issue or drop the requirement. A requirement nobody is tracking is one nobody will build or test.

**Claim nothing the sources do not support.** This includes reasons. A "why" in the Deferred table or a "why it holds" in the summary must come from an issue, a decision record, the code, or the user. A plausible reason invented to fill a cell is still invented.

**Say each thing in one place.** A rule stated in a use case is not restated in the non-functional section, the privacy section, or the release criteria. Cross-reference it by identifier instead. Repetition is how two versions of one rule start to disagree.

**Read values, never recall them.** Defaults, bounds, sizes, timeouts, and limits are read from the constants that define them, not from an issue table and not from memory. Grep the whole constant block rather than the first few lines.

**Verify a representative sample of every behavioural claim**, and all of any claim about access or data flow. Those rows are the ones that will be cited, and the ones an implementer will trust rather than re-derive.

Claims worth checking every time:

- Every default and every bound, against the configuration type
- Every access check, against the code that enforces it, not against the issue that specified it
- Every data flow, against the code that sends it
- Every routing, ordering, or matching rule, against the implementation
- Every "only" and every "never", which are the words most likely to be too strong
- Whether two paths to one action, such as an API and a UI, really share one rule, wherever the document claims they do

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
- The experience the product aims for, if the inputs do not already say
- Anything the inputs leave genuinely ambiguous, where different readings produce materially different documents

**Ask after drafting**, giving them the parts only they can judge:

- The use cases, read as the product owner rather than the implementer. Verification proves requirements match the code; only the user can say they match the intent
- Anything unbuilt, where there is no code to have checked against and their intent is the only source
- The deferred table, since inferring a boundary from scattered "out of scope" notes is guesswork
- The capability and data-flow matrices, which are the sections most likely to be cited later

**Do not ask** what a grep would answer, and do not ask them to confirm findings that verification already settled.

Expect the structure itself to draw feedback, especially on the two hard rules. Restate why they exist rather than quietly conceding: a status column and a section per deployment shape are exactly the things that rot.

## Shipping The Document

**In a repo:** the document goes to `docs/prd.md` and is linked from the README alongside the other documentation. Ship it through the `build` skill, which creates the tracking issue, the worktree, the pull request, and runs the review gate.

Audit what the new document invalidates and fix it in the same change. A repo with decision records usually has a line somewhere saying the issues are where to find what the product does; that line is now wrong.

The review gate matters more here than for code, because a documentation change has no test suite. Ask the reviewer to check factual accuracy against the source and the issue set, not just prose quality, and to say plainly whether the document is accurate enough to become the project's reference.

**With no repo:** write it to `~/Desktop/<product-name>-prd.md` and tell the user where it is. A concept with no repository yet is the common case for this path, and the document is what the repository gets built from.

## Gotchas

- **Writing from the issues alone produces a confidently wrong document** for a built product. The issues are a snapshot of intent at the time each was written. Read the decision records and the code.
- **A "not supported" list is not padding.** It is what a future reader cites when somebody proposes the thing the product deliberately does not do.
- **Requirements that restate each other under two identifiers will drift.** Pick one home and cross-reference.
- **Engineering standards are not product requirements.** "Every change adds a test" belongs in the standards document; cite it rather than restating it.
- **An absolute is usually too strong.** "Never sends", "only on these triggers", "every setting is reachable through". Check each one; most need a scope or an exception.
- **Outside services are actors, not plumbing.** A use case that does not say what happens when its service is slow, down, or rate limited is missing a requirement.
- **Renumber in document order after inserting a requirement.** Renaming identifiers one at a time collides with the one just inserted.
- **The deferred table is the most valuable table in the document** and the easiest to leave empty. Harvest it from the "out of scope" sections the issues already have, and take its reasons from the same place.

## Related Skills

- `build`, which ships the document into a repo through the full issue, worktree, and review flow
- `create-issue`, for the gaps verification finds and for requirements no issue owns yet
- `config`, for confirming the repo is configured
- minecraft-modding `prd`, the same process specialised for Minecraft mods

---
name: strip-comments
description: Remove every comment from a codebase safely, using a literal-aware lexer and a mechanical proof that only comments were removed. Use when asked to strip, remove, or delete comments or javadoc across a repo or a set of source files, or to enforce a no-comments house style. Java is supported out of the box; other languages are added by declaring a profile. Do NOT use to delete documentation files, changelogs, or architecture decision records, which are documentation rather than comments.
---

# strip-comments

Removing comments is easy and proving you removed *only* comments is not. That second half is the
whole skill: a stripper that eats one line of code inside a branch nobody tests still compiles,
still passes the suite, and ships a bug that no reviewer will find in a five thousand line diff.

Two rules carry everything below.

**Never use a regular expression.** `"https://example.com"`, `'"'`, and `"/* not a comment */"`
each defeat naive matching, and the corruption is silent. Comment removal needs a character-level
state machine that knows it is inside a string, a char literal, or a raw block.

**Verify against the diff, not against the build.** A green build is necessary and nowhere near
sufficient. The deliverable of this skill is a mechanical statement about every removed line.

## Config

Read per the `config` skill:
- `repos.<slug>.commands.format` / `.test` / `.build`, used unchanged for the validation pass.
  A repo with no configured commands still needs its formatter run; ask rather than guess.

## Scope This Before Touching Anything

Comments are not documentation. Markdown docs, changelogs, architecture decision records, and
licence files are content, and this skill never touches them. If the request reads as "remove every
comment in the repo", confirm the boundary in a sentence and proceed: source comments yes,
documentation files no.

Decide the file set explicitly. Source files are the core ask. Build scripts, CI workflows, and
property files hold comments too and are usually intended, but they carry different hazards, so
they are handled separately in **Non-Source Files** below and never by the same pass.

**Land this as its own commit, and prefer its own PR.** A repo-wide strip is thousands of deleted
lines. Landing it on top of a behaviour change buries that change in the Files Changed tab and
costs the reviewer the ability to see either one. If the user has asked for it on an existing
branch, do it, and say plainly that splitting it would keep both reviewable.

### Two Scopes

| Scope | File set | When |
|---|---|---|
| Repo-wide | every source file | adopting a no-comments house style, once |
| Branch-scoped | only the files the current branch touched | enforcing that style on a change, every time |

Branch-scoped is what the `build` skill's review gate runs, and it is the common case. It asks a
narrower question, "did this change leave comments behind", so it produces a diff proportional to
the change rather than to the repo:

```bash
git diff --name-only origin/main...HEAD -- '*.java' | xargs python3 scripts/strip_comments.py
```

Everything below applies to both. The survey in Step 1 is cheaper branch-scoped but not optional:
a change can introduce a suppression comment as easily as a prose one.

## Step 1: Survey For Comments That Are Not Comments

Some comments are load-bearing: the toolchain reads them, so deleting them changes behaviour. Find
them before you start, because afterwards they are indistinguishable from the ones you meant to
remove.

```bash
grep -rnE "spotless:(off|on)|@formatter:(off|on)|CHECKSTYLE|NOSONAR|noinspection|\\\$NON-NLS|nolint|go:build|go:generate|ts-ignore|eslint-disable|noqa|type: ignore|pragma" <source-dirs>
```

Also look for:

- **Licence and copyright headers.** Legal text, not commentary. Removing them may breach a
  licence the project is bound by. Raise it rather than deciding.
- **`package-info.java`** and equivalents whose entire content is a doc comment. Stripping leaves a
  file with nothing but a package declaration, which is legal but pointless; delete or keep the
  file deliberately.
- **Generated files** with "do not edit" banners. Strip the generator's template, not its output,
  or the next regeneration reverts you.
- **Suppression comments a linter reads**, which vary per project. If a lint step exists in CI, its
  config names them.

Then check the lexer hazards for the language, so you know whether the default profile is enough:

```bash
grep -rln '"""' <source-dirs>   # Java text blocks, Python docstrings
```

## Step 2: Prove The Stripper Before Aiming It At The Repo

`scripts/strip_comments.py` is the lexer. Do not modify it in place for one repo's quirk; if the
language needs something it lacks, add a profile per **Adding A Language**.

Write a fixture that contains every hazard you found, run the stripper on it, and read the output
before going near real files:

```java
public class Fixture {
  // a line comment
  private static final String URL = "https://example.com/a//b"; // trailing comment
  private static final String STAR = "/* not a comment */";
  private static final char QUOTE = '"';
  private static final char SLASH = '\\';
  private static final String ESCAPED = "a \" // still string";
  private static final String BLOCK = """
      a text block with // and /* inside
      """;

  /* block
     comment */
  public int value() {
    return 42; /* inline */
  }
}
```

Every literal must survive byte for byte, and every comment must be gone. A stripper that fails
this fixture will fail somewhere in the repo where nobody is looking.

## Step 3: Strip, Then Format

```bash
find <source-dirs> -name "*.java" -print0 | xargs -0 python3 scripts/strip_comments.py
```

Then run `commands.format`. The formatter settles the blank lines a removed block left behind, and
it will also make two code changes that are expected and legitimate:

| The formatter does this | Because | Confirm |
|---|---|---|
| Rejoins lines that carried trailing comments | The lines got shorter, so they now fit | The code content survives verbatim |
| Drops an import as unused | It existed only to satisfy a doc-comment link | Nothing outside comments referenced it |

Anything else it changes is a signal, not noise.

## Step 4: Verify Mechanically

This is the step that makes the change trustworthy.

```bash
python3 scripts/verify_only_comments_removed.py --language java <source-dirs>
```

It reads `git diff -U0` and sorts every removed line into three piles:

- **accounted for**, being a comment or a blank line, which is most of them
- **explained**, being a code line that carried a trailing comment whose code content it then
  finds surviving verbatim among the added lines
- **unexplained**, being everything else

It exits non-zero while anything is unexplained. Adjudicate those by hand: the usual answer is a
symbol referenced only from a removed doc comment, which the formatter dropped as unused. Confirm
nothing outside comments referenced it. Anything else is the stripper eating code, and the fix is
to restore the files and correct the lexer rather than to edit the result.

Do not wave a line through because the build is green. Open the file and look.

A run with zero unexplained lines, plus the compile and test pass below, is the proof. On a real
five thousand line strip this reduced the manual check to two imports.

## Step 5: Compile And Run Everything

Comment removal touches every file, so a partial validation proves nothing about the files it
skipped.

- Compile **every source set**, not just the main one. Test sources, client sources, and
  fixture sources all had comments too.
- Run `commands.test` in full.
- Run any integration or in-engine suite the repo has. Unit tests do not exercise the paths where
  a silently eaten line hides.
- Run `commands.build`, which includes the formatter's own check.

## Non-Source Files

Build scripts, CI workflows, and property files get a separate, more conservative pass: **full-line
comments only**, never trailing ones. Their comment markers appear inside ordinary values far more
often than a source language's do.

Prove the marker is safe before removing anything:

```bash
grep -nE "^[^#]*\S\s+#" <workflow-files>      # a '#' after content is probably a string
grep -nE "[^:/]//" <build-script>              # a '//' after content, minus URLs
```

A shell line like `echo "  #${issue} -> ${name}"` inside a workflow is exactly the case a
whole-line rule protects and a general rule destroys. After stripping, prove the files still parse:
load the YAML, and run the build tool's configuration phase.

## Update The Docs This Invalidates

A repo that mandated comments now contradicts itself. Search the docs for the standard being
changed and rewrite it in the same pass:

```bash
grep -rn "comment\|javadoc\|docstring" <docs-dirs> README.md
```

State the new rule positively rather than deleting the old line, so the next contributor learns the
house style instead of inferring it from an absence.

## Adding A Language

`LANGUAGES` in `scripts/strip_comments.py` maps a name to its comment and literal syntax. A new
entry needs the line marker, the block delimiters, the string and char quotes, the escape
character, and any raw or multi-line string forms.

Before declaring a language supported, check it against the traps that make comment stripping
language-specific:

| Trap | Languages | Why the default profile is wrong |
|---|---|---|
| Regex literals | JavaScript, TypeScript, Ruby, Perl | `/pattern//g` opens what looks like a line comment |
| Nested block comments | Kotlin, Swift, Rust, Scala | `/* /* */ */` closes early and the tail is eaten as code |
| Docstrings are expressions | Python | A module or function docstring is a string, not a comment; removing it can change behaviour |
| Significant indentation | Python, YAML | Dropping a comment line is safe, but never collapse the blank lines around it blindly |
| Template and interpolated strings | JavaScript, Kotlin, Scala | `${...}` can contain nested quotes and comment markers |
| Preprocessor directives | C, C++ | `#pragma` and `#if` are directives, and conditional blocks may guard comments |

The provided profiles are `java`, `c`, `go`, and `gradle`. Adding one that hits a trap above means
extending the state machine, not just adding a table row.

## Gotchas That Cost A Run Each

- **The lexer must refuse to write on an unterminated state.** A file ending mid-string means the
  profile is wrong for that language; writing the result corrupts it. The script raises instead.
- **A comment between an annotation and its target** removes cleanly, but leaves a blank line the
  formatter may not close. Harmless, and worth expecting in the diff.
- **Doc comments hold the only reference to an import.** Expect unused-import removal and confirm
  it rather than being surprised by it.
- **Two blank lines where a javadoc block stood** is the formatter's business, not the stripper's.
  Do not hand-tidy; run the formatter and let it decide.
- **`git diff` with default context hides the shape of the change.** Verify with `-U0`, which is
  what the verification script uses, so added and removed lines line up one to one.
- **A file whose every line was a comment** becomes empty. Decide whether it should exist.

## Related Skills

- `config`, for the format, test, and build commands this skill runs unchanged
- `build`, whose review gate runs this branch-scoped before a PR becomes ready for review
- `worktree`, because a repo-wide rewrite belongs on its own branch
- `pr`, for landing it as its own reviewable commit

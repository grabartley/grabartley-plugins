"""Prove a comment strip removed only comments, by reading the diff rather than trusting it.

A green build does not prove a stripper behaved: a line eaten inside a rarely-exercised
branch still compiles and still passes tests. This reads every removed line and sorts it
into one of three piles, so only the genuinely unexplained ones need a human.

    accounted for : the line was a comment, or was blank
    explained     : the line carried a trailing comment and its code survives in the diff
    unexplained   : everything else, which must be adjudicated before committing

Usage:
    python3 verify_only_comments_removed.py [--language java] [PATHSPEC ...]

Exits non-zero while anything is unexplained, so it can gate a commit.
"""

import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from strip_comments import LANGUAGES, strip

COMMENT_STARTS = ("//", "/*", "*", "#", "--", ";")


def diff_lines(pathspecs):
    command = ["git", "diff", "-U0", "--"] + (pathspecs or ["."])
    output = subprocess.run(command, capture_output=True, text=True, check=True).stdout
    removed, added = [], []
    for line in output.split("\n"):
        if line.startswith(("+++", "---")):
            continue
        if line.startswith("-"):
            removed.append(line[1:])
        elif line.startswith("+"):
            added.append(line[1:])
    return removed, added


def is_comment_or_blank(text):
    stripped = text.strip()
    if stripped == "":
        return True
    if stripped.endswith("*/"):
        return True
    return stripped.startswith(COMMENT_STARTS)


def squashed(text):
    return re.sub(r"\s+", "", text)


def code_of(line, lang):
    try:
        return strip(line + "\n", lang).strip()
    except ValueError:
        return line.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--language", default="java", choices=sorted(LANGUAGES))
    parser.add_argument("pathspecs", nargs="*")
    args = parser.parse_args()
    lang = LANGUAGES[args.language]

    removed, added = diff_lines(args.pathspecs)
    added_blob = squashed("".join(added))

    explained, unexplained = [], []
    for line in removed:
        if is_comment_or_blank(line):
            continue
        code = code_of(line, lang)
        if code == "":
            continue
        if squashed(code) and squashed(code) in added_blob:
            explained.append((line, code))
        else:
            unexplained.append(line)

    print(f"removed lines:  {len(removed)}")
    print(f"added lines:    {len(added)}")
    print(f"explained:      {len(explained)}  (trailing comment, code survives verbatim)")
    print(f"unexplained:    {len(unexplained)}")

    for line, code in explained[:10]:
        print(f"  ok {line.strip()!r} -> {code!r}")
    if len(explained) > 10:
        print(f"  ... and {len(explained) - 10} more")

    for line in unexplained:
        print("  ?", repr(line))

    if unexplained:
        print()
        print("Every line above must be explained before committing. The ending that is")
        print("usually legitimate is a symbol referenced only from a removed doc comment,")
        print("which the formatter then dropped as unused: confirm nothing outside comments")
        print("referenced it. Anything else is the stripper eating code, so restore the")
        print("files and fix the lexer rather than editing the result by hand.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

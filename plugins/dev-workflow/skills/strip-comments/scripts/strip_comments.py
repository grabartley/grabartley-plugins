"""Remove every comment from source files, respecting string and char literals.

A character-level state machine rather than a regex, because `"https://x"`, `'"'`, and
`"/* not a comment */"` all defeat naive matching and corrupt the file silently.

Usage:
    python3 strip_comments.py [--language java] FILE [FILE ...]

Lines that held nothing but a comment are dropped. A line with code and a trailing
comment keeps the code. Run the project formatter afterwards to settle blank lines.
"""

import argparse
import sys


class Language:
    def __init__(self, line, block_open, block_close, strings, char, escape, raw=None):
        self.line = line
        self.block_open = block_open
        self.block_close = block_close
        self.strings = strings
        self.char = char
        self.escape = escape
        self.raw = raw or []


LANGUAGES = {
    "java": Language("//", "/*", "*/", ['"'], "'", "\\", raw=['"""']),
    "c": Language("//", "/*", "*/", ['"'], "'", "\\"),
    "go": Language("//", "/*", "*/", ['"'], "'", "\\", raw=["`"]),
    "gradle": Language("//", "/*", "*/", ['"', "'"], None, "\\"),
}

CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR, RAW = range(6)

STATE_NAMES = {
    CODE: "code",
    LINE_COMMENT: "line comment",
    BLOCK_COMMENT: "block comment",
    STRING: "string literal",
    CHAR: "char literal",
    RAW: "raw string",
}


def strip(source, lang):
    state = CODE
    out = []
    closer = ""
    i = 0
    n = len(source)
    while i < n:
        c = source[i]
        rest = source[i:]
        if state == CODE:
            raw_open = next((r for r in lang.raw if rest.startswith(r)), None)
            if raw_open:
                out.append(raw_open)
                closer = raw_open
                state = RAW
                i += len(raw_open)
                continue
            if lang.line and rest.startswith(lang.line):
                state = LINE_COMMENT
                i += len(lang.line)
                continue
            if lang.block_open and rest.startswith(lang.block_open):
                state = BLOCK_COMMENT
                i += len(lang.block_open)
                continue
            if c in lang.strings:
                closer = c
                state = STRING
            elif lang.char and c == lang.char:
                state = CHAR
            out.append(c)
            i += 1
            continue
        if state in (STRING, CHAR, RAW):
            out.append(c)
            if lang.escape and c == lang.escape and i + 1 < n:
                out.append(source[i + 1])
                i += 2
                continue
            if state == RAW:
                if rest.startswith(closer):
                    out.extend(closer[1:])
                    i += len(closer)
                    state = CODE
                    continue
            elif state == STRING and c == closer:
                state = CODE
            elif state == CHAR and c == lang.char:
                state = CODE
            i += 1
            continue
        if state == LINE_COMMENT:
            if c == "\n":
                state = CODE
                out.append(c)
            i += 1
            continue
        if state == BLOCK_COMMENT:
            if rest.startswith(lang.block_close):
                state = CODE
                i += len(lang.block_close)
                continue
            if c == "\n":
                out.append(c)
            i += 1
            continue
    if state != CODE:
        raise ValueError(
            f"unterminated {STATE_NAMES[state]} at end of file, refusing to write"
        )
    return "".join(out)


def tidy(text):
    """Drop the lines a comment vacated and never leave trailing whitespace behind."""
    lines = [line.rstrip() for line in text.split("\n")]
    kept = []
    for index, line in enumerate(lines):
        if line == "":
            previous = kept[-1] if kept else ""
            following = lines[index + 1] if index + 1 < len(lines) else ""
            if previous.strip() == "" and following.strip() == "":
                continue
            if previous.rstrip().endswith(("{", "(")) and following.strip() != "":
                continue
        kept.append(line)
    while kept and kept[-1] == "":
        kept.pop()
    return "\n".join(kept) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--language", default="java", choices=sorted(LANGUAGES))
    parser.add_argument("files", nargs="+")
    args = parser.parse_args()
    lang = LANGUAGES[args.language]

    changed = 0
    for path in args.files:
        with open(path, encoding="utf-8") as handle:
            original = handle.read()
        try:
            updated = tidy(strip(original, lang))
        except ValueError as failure:
            print(f"SKIPPED {path}: {failure}", file=sys.stderr)
            continue
        if updated != original:
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(updated)
            changed += 1
    print(f"rewrote {changed} of {len(args.files)} files")


if __name__ == "__main__":
    main()

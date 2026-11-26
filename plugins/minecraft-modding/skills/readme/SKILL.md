---
name: readme
description: Write or change a Minecraft mod's README.md, then turn the merged README into the Modrinth and CurseForge project descriptions and paste both into the session. Use for every README change in a Minecraft mod repo, however small: a new feature folded into the pitch, a settings or commands table update, new screenshots, credits, or a full rewrite. Also use when asked for the Modrinth or CurseForge description of a mod.
---

# Readme

The README is the mod's storefront, and the same text becomes its Modrinth and CurseForge pages.
It is written for players deciding whether to download the mod, not for developers, and it is not
a changelog.

Every README change goes through this skill, and the change ships through the dev-workflow `build`
skill like any other change. Once the PR is merged, this skill always ends by pasting the Modrinth
and CurseForge versions into the session, built from the README on `main`.

## Layout

Keep this order. A mod that already has a README keeps its own section names; new sections follow
this shape.

1. **Header.** The banner, centred, with alt text that describes the scene. Then the badges:
	Modrinth downloads, GitHub stars, licence and Ko-fi. Then the showcase video if the mod has
	one, as a YouTube thumbnail linked to the video. Then the required-mod buttons, centred, with
	a centred "Also needs" line for any required mod that has no button.
2. **`# <Mod name>`**, a two-sentence intro saying what the mod adds, then the alpha call-out
	asking for feedback through a new issue, while the mod is in alpha.
3. **The pitch.** One `##` section per area of the mod, each with a screenshot and one bullet per
	feature group. A bullet opens with a short bold lead (`**Feed.**`) and follows with one to
	three short sentences. A mod with one main collection (breeds, blocks, devices) may open the
	pitch with a short table of it and its images.
4. **`## Install`.** Three numbered steps: install Fabric Loader and put the mod and its required
	mods in `mods`, load a world, then the first thing to do. Then one short paragraph: singleplayer
	and servers, the Minecraft version, and where the key binds are.
5. **`## Settings`.** One short paragraph on where settings are changed and who may change them.
	Then `<details>` blocks for the long reference: all settings, commands, works well with, and
	requirements (Minecraft and Java version, plus a table of required mods and minimum versions).
6. **`## Languages`.** The languages the mod ships, each with its own name in brackets, then how
	to add or fix a translation.
7. **`## Credits`.** A link to the product requirements if the repo has one, the licence, and one
	line on where the sounds and music come from. Then a `<details>` block per kind of recording,
	one line each: title, author, `[link](url)` and licence. Close with one line asking for a star
	or a coffee, and the Ko-fi button.

## Writing rules

- **Plain and player-facing.** Short sentences in everyday words. No slogans, taglines, hooks,
	cute contrasts or marketing copy. Describe what the player can do.
- **Never mention AI**, or the games, franchises, characters or mods behind a design. Describe
	the thing itself.
- **Not a changelog.** Never add a section per feature. A shipped feature becomes a clause or one
	short sentence in the bullet it belongs to, and the bullet stays about the length it was. Add a
	bullet only for a whole new area, and a `##` section only for a new side of the mod.
- **No mechanics.** Never explain how a feature works step by step: no recipes, tick counts,
	chances, stats, NBT fields, paths or engine ids. Counts of things the player can collect are
	fine.
- **Never mention advancements** in the pitch.
- **Check every claim against the code.** A feature that only some variants have, or that does
	less than the sentence says, gets rewritten or cut.
- **Keep the reference current.** When settings, commands, key binds, required mods or versions
	change, update their tables and lines against the code in the same PR.
- **Every image has alt text** that says what is in the shot. Images use absolute
	`raw.githubusercontent.com` URLs on `main`, so the same tag works on every site.
- **Real recordings are credited.** Every recorded sound and piece of music is in Credits with
	its author, link and licence.
- **Wrapped lines are not indented.** Spotless strips the indent from a wrapped list line, so
	write continuation lines flush left.
- **No em dashes.** Use commas, colons or full stops.

## Site versions

After the README PR is merged, read `README.md` from `main` and build two versions of it. Both
are whole documents, ready to paste over the full description on each site.

### Modrinth

Modrinth renders GitHub-style markdown and the HTML the README uses, so this version is the README
with three changes:

- Drop the `# <Mod name>` heading. The site shows the name above the description.
- Turn every relative link into an absolute GitHub URL on `main`: files use
	`https://github.com/<owner>/<repo>/blob/main/<path>`, images use
	`https://raw.githubusercontent.com/<owner>/<repo>/main/<path>`.
- Keep everything else as it is: the centred HTML blocks, the badges, the required-mod buttons,
	the `<details>` blocks, the line wrapping and the Modrinth links.

### CurseForge

CurseForge pages use plain markdown only, so every HTML block is rewritten:

- **No HTML at all.** No `<p>`, `<a>`, `<img>`, `<details>` or `<summary>`.
- **Drop the `# <Mod name>` heading**, as on Modrinth.
- **Banner and screenshots** become `![alt text](url)`, with the same alt text and no size or
	centring.
- **Badges** go on one line as markdown image links. Drop the Modrinth downloads badge.
- **Drop the required-mod buttons and the "Also needs" line.** CurseForge lists required mods
	from the project's relations.
- **Every link to another mod points at its CurseForge page**
	(`https://www.curseforge.com/minecraft/mc-mods/<slug>`), in Install, Works well with and
	anywhere else. Look up each slug, since it can differ from the Modrinth one (Patchouli for
	Fabric is `patchouli-fabric`), and check every page loads.
- **`<details>` blocks become sections.** A block under Settings becomes its own `##` section
	(`## Commands`, `## Works well with`, `## Requirements`), and a block under Credits becomes a
	`###` section (`### Music`, `### Sounds`). The summary text is the heading.
- **One line per paragraph and per list item.** Join wrapped lines.
- **Relative links become absolute**, as on Modrinth.
- **Escape `(` and `)` inside link URLs** as `\(` and `\)`. A link whose URL had
	percent-encoded characters (`%C3%B6`) lost its URL on the live Nightborn page and showed as
	bare text, so after pasting, open the preview and check that every credit link still works.
- **The Ko-fi button** at the end becomes a markdown image link.

### Hand-off

Paste both versions into the session, Modrinth first, each in its own fenced `markdown` block so
they copy raw. Use a four-backtick fence, because the README itself contains three-backtick code
blocks. Say which site each block is for, and nothing else in between.

## Related Skills

- `build` (dev-workflow), which ships the README change
- `prd`, the document the Credits section links to
- `demo-video`, which makes the showcase video in the header

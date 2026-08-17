---
description: Create or update the shared ~/.grabartley-plugins/config.json used by all grabartley-plugins skills
---

Set up the shared config file for the grabartley-plugins marketplace skills.

1. Read the `config` skill from the dev-workflow plugin for the file location, schema, and fallback rules.
2. If `~/.grabartley-plugins/config.json` (or `$GRABARTLEY_PLUGINS_CONFIG`) does not exist, create it from `${CLAUDE_PLUGIN_ROOT}/../../config.example.json`.
3. Ask the user which repos they want configured, then fill in `github.owner` and a `.repos` entry per repo. Resolve project board ids with `gh project field-list <number> --owner <owner> --format json` instead of guessing.
4. Remove example placeholder entries that do not apply.
5. Show the final config and confirm where it was written.

$ARGUMENTS

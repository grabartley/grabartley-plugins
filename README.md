# grabartley-plugins

A Claude Code plugin marketplace carrying reusable development workflow skills. One repo, multiple plugins, one shared config file so the same skills work for any developer and any repo.

## Install

Add the marketplace once:

```
/plugin marketplace add grabartley/grabartley-plugins
```

Install the plugins you want:

```
/plugin install dev-workflow@grabartley-plugins
/plugin install minecraft-modding@grabartley-plugins
/plugin install runelite-dev@grabartley-plugins
```

To auto-enable plugins for everyone in a specific repo, commit this to that repo's `.claude/settings.json`:

```json
{
	"extraKnownMarketplaces": {
		"grabartley-plugins": {
			"source": { "source": "github", "repo": "grabartley/grabartley-plugins" },
			"autoUpdate": true
		}
	},
	"enabledPlugins": {
		"dev-workflow@grabartley-plugins": true,
		"minecraft-modding@grabartley-plugins": true
	}
}
```

## Configure

Skills read one config file: `~/.grabartley-plugins/config.json` (override the path with the `GRABARTLEY_PLUGINS_CONFIG` environment variable). Bootstrap it from the example:

```bash
mkdir -p ~/.grabartley-plugins
cp config.example.json ~/.grabartley-plugins/config.json
```

Then fill in your GitHub owner and one `repos` entry per repo you work in: project board ids, build commands, worktree settings, and domain settings. The `/dev-workflow:setup-config` command walks through this, and the `config` skill in dev-workflow documents every key and the fallback rules. Skills degrade gracefully when a value is missing: board moves are skipped and reported rather than failing the workflow.

## Plugins

### dev-workflow

General development workflow, usable in any repo.

| Skill | What it does |
|---|---|
| `config` | The shared config file: location, schema, fallback rules |
| `worktree` | Fresh git worktree from latest main for isolated work |
| `pr` | Validation, commit, push, and PR creation with strict description conventions |
| `create-issue` | GitHub issues with project board sync, optional epics and asset labels |
| `build` | End-to-end feature flow: issue, worktree, implement, validate, PR, QA handoff |
| `pr-local-review` | Locally review a PR and produce a paste-ready punch list |
| `update-skill` | Update marketplace skills on a worktree, PR, squash merge, refresh local plugins |

Skills are invoked directly (for example `/build` or `/update-skill` in a session with the plugin enabled). The one command is `/dev-workflow:setup-config`, which has no skill counterpart: it bootstraps the config file.

### minecraft-modding

Fabric mod development on Minecraft 1.21.1 era tooling. Layers on top of dev-workflow.

| Skill | What it does |
|---|---|
| `gametest` | Fabric GameTest authoring patterns: coordinates, batches, time pinning, mock players, flake diagnosis |
| `automated-qa` | Drive the real client with a temp in-process driver, capture and verify screenshots, publish evidence to the PR |
| `geo-prop` | Author GeckoLib props as reviewable text with an offline render loop |
| `item-sprite` | Author flat 16x16 sprites as palette-mapped text with size verification |
| `run-game-client` | Launch the dev client for manual testing |
| `run-tests` | Unit tests, game tests, and the pre-push workflow |
| `mp3-to-ogg` | Convert audio assets for resource packs |

### runelite-dev

RuneLite plugin development. Layers on top of dev-workflow.

| Skill | What it does |
|---|---|
| `run-game-client` | Dev client launch with Jagex account login and provider key setup |
| `run-tests` | Unit tests plus the Plugin Hub Java 11 main-source constraint |
| `publish-version` | Full release: dispatch release workflow, resolve the tag SHA, open the Plugin Hub update PR |

## Contributing changes

Use the `update-skill` skill: it edits skills on a worktree, opens a PR, squash merges, and refreshes the local plugin installation. Keep skill text repo-agnostic; anything repo-specific belongs in the config file, documented in the `config` skill and `config.example.json`.

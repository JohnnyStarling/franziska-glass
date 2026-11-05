# Continuity / portable community source

This is the lexical/sparse-vector retrieval implementation and OpenClaw tool bridge used by the Franziska project, adapted for a fresh user-owned workspace. No personal memory, index chunks or conversations are included.

## Requirements

- Python 3.11 or newer, standard library only.
- Node.js 22 or newer for the plugin tests; use the Node version required by your selected OpenClaw build when loading the plugin.
- A compatible OpenClaw installation only if you want agent integration; the CLI recall tool also runs by itself.

## Create and search a fresh workspace

From the repository root:

```sh
python3 scripts/init-workspace.py --workspace "$HOME/franziska-workspace"
python3 "$HOME/franziska-workspace/continuity/semantic_index.py" build
python3 "$HOME/franziska-workspace/continuity/recall.py" "example project" --json
```

The initializer requires a new or empty destination and never overwrites an existing workspace. It copies generic identity/continuity templates and the four Python source files. Replace the example project and user preferences with your own records.

The Python scripts default to the parent of their installed continuity directory. Set `FRANZISKA_WORKSPACE` to override the corpus root. Rebuild the sparse index after changing source records; it is a generated snapshot, not a self-updating neural memory model.

## Add an OpenClaw agent

After configuring your own model/provider access in OpenClaw:

```sh
openclaw agents add franziska --workspace "$HOME/franziska-workspace" --non-interactive
```

This creates an additional agent rather than replacing an existing one. Follow the selected OpenClaw version's provider-auth and channel-binding documentation. The command does not copy this project's private runtime or account configuration.

### Agent target used by the wearable client

The unmodified community Pixel client uses `agent:main:glass:<conversation UUID>`. The additional `franziska` agent above is a text/CLI integration example; it does not automatically become the wearable target.

For a fresh wearable deployment, configure **main** to use the initialized Franziska workspace during your own Gateway setup. The relevant configuration field is `agents.entries.main.workspace`. Keep your model/provider settings and other agent entries. For an existing personal main agent, integrate the continuity tool/records deliberately rather than overwriting its identity or assuming all session histories merge.

## Install the local recall plugin

From the repository root:

```sh
openclaw plugins install --no-enable --link "$PWD/components/continuity/plugin"
```

Install disabled, then set the plugin's `workspace` to the absolute path of the initialized workspace using the installed OpenClaw version's configuration UI or CLI. Merge the following **fragment** into your configuration; do not replace the whole file:

```json
{
  "plugins": {
    "entries": {
      "continuity-memory": {
        "enabled": true,
        "config": {
          "workspace": "/absolute/path/to/franziska-workspace",
          "maxChars": 4000
        }
      }
    }
  }
}
```

After setting the workspace, enable it with `openclaw plugins enable continuity-memory`. If you maintain a plugin allowlist, retain its existing entries and admit this plugin through the supported installation flow. Inspect `openclaw plugins inspect continuity-memory --runtime --json` to confirm its status. Follow any capability admission reported by your OpenClaw version.

OpenClaw's bundled Active Memory can use `continuity_recall` in its recall-tool allowlist. Automatic triggering is supplied by that upstream plugin; this package implements the corpus search and tool bridge.

## Optional legacy history exports

The default never scans the host's private OpenClaw session directory. Raw history is opt-in: set `FRANZISKA_SESSIONS` for CLI use or plugin `sessionsPath` to a directory of your own exported legacy records. Supported loaders are the documented `sessions.json.telegram-messages.json` line export and `*.jsonl.codex-app-server.json` files. They are not a universal current-session database reader. Use OpenClaw's native session tools for current runtime history.

## What changed for portability

The community copy replaces author-specific filesystem roots and project mappings with configurable roots and project-card discovery. It uses `api.pluginConfig`, returns deep-search/freshness fields and enforces the advertised output size. It does not modify the running private deployment.

## Tests

```sh
python3 -m unittest discover -s tests -p 'test_continuity.py' -v
node --test tests/continuity-plugin.test.mjs
```

Tests create temporary workspaces, verify retrieval and no-overwrite behavior, and exercise the plugin with its own configuration. No provider account, device, private archive or Gateway mutation is involved.

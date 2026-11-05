import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { execFileSync } from "node:child_process";
import plugin from "../components/continuity/plugin/index.js";

const repo = resolve(import.meta.dirname, "..");

async function fixture(t) {
  const base = await mkdtemp(join(tmpdir(), "franziska-plugin-test-"));
  t.after(() => rm(base, { recursive: true, force: true }));
  const workspace = join(base, "workspace");
  execFileSync("python3", [join(repo, "scripts/init-workspace.py"), "--workspace", workspace]);
  return workspace;
}

function toolFor(pluginConfig) {
  let tool;
  plugin.register({ pluginConfig, config: { workspace: "/invalid-global-config-root" }, registerTool(value) { tool = value; } });
  return tool;
}

test("plugin uses its own configuration and portable source corpus", async (t) => {
  const workspace = await fixture(t);
  const tool = toolFor({ workspace, maxChars: 12000 });
  const result = await tool.execute("request", { query: "example project", limit: 2 });
  const pack = JSON.parse(result.content[0].text);
  assert.equal(tool.name, "continuity_recall");
  assert.ok(pack.search_hits.length);
  assert.ok(pack.project_cards.includes("continuity/projects/example-project.md"));
  assert.ok(pack.source_freshness_tokens);
  assert.ok(Array.isArray(pack.session_search_hits));
});

test("missing workspace fails explicitly", async () => {
  await assert.rejects(toolFor({}).execute("request", { query: "example" }), /config.workspace/);
});

test("output stays inside the configured character bound", async (t) => {
  const workspace = await fixture(t);
  const result = await toolFor({ workspace }).execute("request", { query: "example project", maxChars: 500 });
  assert.ok(result.content[0].text.length <= 500);
});

test("empty query is rejected before spawning the search", async (t) => {
  const workspace = await fixture(t);
  await assert.rejects(toolFor({ workspace }).execute("request", { query: "   " }), /query is required/);
});

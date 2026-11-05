import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { resolve } from "node:path";

const execFileAsync = promisify(execFile);



function asString(value, fallback = "") {
  return typeof value === "string" ? value : fallback;
}

function asNumber(value, fallback) {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

async function runContinuityRecall(config, params) {
  const configuredWorkspace = asString(config?.workspace).trim();
  if (!configuredWorkspace) throw new Error("Set continuity-memory config.workspace to an initialized Franziska workspace.");
  const workspace = resolve(configuredWorkspace);
  const maxChars = Math.max(500, Math.min(12000, Math.floor(asNumber(params.maxChars, asNumber(config?.maxChars, 4000)))));
  const query = asString(params.query).trim();
  const limit = Math.max(1, Math.min(10, Math.floor(asNumber(params.limit, 6))));
  const includeSessions = params.includeSessions === true;
  if (!query) {
    throw new Error("query is required");
  }

  const args = ["continuity/recall.py", query, "--limit", String(limit), "--json"];
  if (includeSessions) args.push("--sessions");

  const { stdout } = await execFileAsync("python3", args, {
    cwd: workspace,
    timeout: 15000,
    maxBuffer: 1024 * 1024,
    env: { ...process.env, FRANZISKA_WORKSPACE: workspace, FRANZISKA_SESSIONS: asString(config?.sessionsPath) },
  });
  const parsed = JSON.parse(stdout);
  const compact = {
    query: parsed.query,
    classes: parsed.classes,
    confidence: parsed.confidence,
    recommended_action: parsed.recommended_action,
    required_read_order: parsed.required_read_order,
    project_cards: parsed.project_cards,
    search_hits: (parsed.search_hits || []).slice(0, limit).map((hit) => ({
      score: hit.score,
      source: hit.source,
      path: hit.path,
      excerpt: String(hit.excerpt || "").slice(0, 500),
    })),
    semantic_hits: (parsed.semantic_hits || []).slice(0, Math.min(limit, 4)).map((hit) => ({
      score: hit.score,
      source: hit.source,
      path: hit.path,
      excerpt: String(hit.excerpt || "").slice(0, 500),
    })),
    session_search_hits: (parsed.session_search_hits || []).slice(0, limit),
    source_freshness_tokens: parsed.source_freshness_tokens,
    answer_rules: parsed.answer_rules,
  };
  let text = JSON.stringify(compact, null, 2);
  if (text.length > maxChars) {
    const suffix = "\n...truncated...";
    text = text.slice(0, maxChars - suffix.length) + suffix;
  }
  return text;
}

export default {
  id: "continuity-memory",
  name: "Continuity Memory",
  description: "Exposes Franziska's continuity recall layer as an OpenClaw memory tool.",
  configSchema: {
    type: "object",
    additionalProperties: false,
    required: ["workspace"],
    properties: {
      sessionsPath: { type: "string" },
      workspace: {
        type: "string",

      },
      maxChars: {
        type: "integer",
        minimum: 500,
        maximum: 12000,
        default: 4000,
      },
    },
  },
  register(api) {
    api.registerTool({
      name: "continuity_recall",
      label: "Continuity Recall",
      description:
        "Search Franziska's durable continuity memory for prior decisions, project state, preferences, contradictions, and handoff context. Return NONE if no relevant evidence is found.",
      parameters: {
        type: "object",
        additionalProperties: false,
        required: ["query"],
        properties: {
          query: {
            type: "string",
            description: "Memory question or topic to recall evidence for.",
          },
          limit: {
            type: "number",
            description: "Maximum evidence hits to return, 1-10. Default 6.",
            minimum: 1,
            maximum: 10,
          },
          includeSessions: {
            type: "boolean",
            description: "Include bulky session transcript search. Default false.",
          },
          maxChars: {
            type: "number",
            description: "Maximum returned characters. Default plugin config maxChars.",
            minimum: 500,
            maximum: 12000,
          },
        },
      },
      async execute(_toolCallId, params) {
        const text = await runContinuityRecall(api.pluginConfig ?? api.config, params);
        return { content: [{ type: "text", text }] };
      },
    });
  },
};

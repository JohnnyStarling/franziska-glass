# Franziska / The assistant

[Overview](../README.md) · [System architecture](ARCHITECTURE.md) · [OpenClaw engineering](OPENCLAW.md)

## Purpose

Franziska is Ivan Shpak's personal AI assistant project. The objective is a durable working relationship: an assistant with an established identity, an accumulating record of decisions and the ability to carry work forward with tools.

The same project is reached through Telegram, the OpenClaw Control UI and the Glass interface. These surfaces can have separate session histories: shared identity and retrieved durable knowledge do not automatically merge all transcripts. Talk binds to its chosen agent session; the phone is not intended to host an unrelated assistant with a second project history.

## Identity is an operating layer

Franziska's identity is expressed through instructions, working conventions, preferences and standing decisions. These shape how she reasons about a request, searches for context, uses tools and reports results.

This identity is not tied to one model's transient context window. Existing AI models provide inference; OpenClaw provides the agent runtime. The project adds the personal working system, continuity procedures and device integrations around that foundation.

## Five kinds of context

| Context | Role |
| --- | --- |
| **Conversation** | The sequence of messages, tool calls and results in a particular interaction. |
| **Pinned context** | Stable instructions, preferences and decisions that must be recovered consistently. |
| **Project records** | Current goals, completed work, open work, evidence and next actions for an ongoing project. |
| **Episodic records and handoffs** | Compact accounts of events and the information needed to resume after a boundary. |
| **Retrieval indexes** | Searchable representations that lead back to source records. |

The distinction is practical. A raw transcript can contain several superseded plans. A current project record should resolve them. A handoff should preserve the objective without copying the entire archive into the next prompt.

## Recall as a procedure

Franziska's continuity process starts from the question being asked: a prior decision, a project state, a preference or a contradiction. Retrieval locates the relevant sources; the agent reads those sources and uses them to form the answer or next action.

Records have different authority. Explicit owner corrections take precedence over stale summaries. Project state is kept separate from historical checkpoints. If a result is missing, the system retrieves it or states the gap rather than inventing a smooth continuation.

This is the engineering basis of continuity: durable records, source selection, retrieval and deliberate updates at session and task boundaries. The aim is useful, recoverable context, not a larger pile of unstructured text.

## Retrieval implementation

The local continuity code has two retrieval channels:

- **Weighted lexical search:** tokenization for Latin/Cyrillic text and numbers, a small bilingual/topic alias table, query coverage, logarithmic term-frequency scoring and source weights. It searches the selected continuity corpus and can extend to supported history sources.
- **Local sparse-vector search:** token/alias features plus character 3-grams and 4-grams, stable BLAKE2b hashing into 4,096 dimensions, normalized sparse vectors and cosine-style similarity over stored chunks.

These are inspectable local retrieval methods. The sparse index is not a neural embedding model. It is a generated cache; rebuilding it is a maintenance operation rather than a consequence of every new message.

`recall.py` assembles an evidence pack: lexical and vector hits, source pointers, project-card suggestions, required read order and answer rules. The local `continuity-memory` plugin exposes that path as `continuity_recall`, with bounded process time and output size. The tool locates evidence; it does not itself rewrite memories.

## Automatic recall and its boundary

OpenClaw's bundled Active Memory plugin provides eligible-turn recall and prompt integration. Franziska connects the local continuity tool to that existing facility. It is configured for the main agent's direct conversations and uses available tool authority and audience restrictions.

Retrospective intent can trigger deeper retrieval; an ordinary message may intentionally skip it. The recall helper returns a compact context summary rather than answering the user. The main agent still owns interpretation and the response.

Automatic triggering belongs to upstream Active Memory. The local project contribution is the continuity corpus, operating protocol, lexical/sparse-vector implementation and bridge. This separation is important when describing authorship and the actual structure of the system.

## Records stay recoverable

The code and procedures distinguish immediate event recording, project updates, durable facts and handoffs. Corrections are recorded explicitly; current project state is not obtained merely by selecting the latest matching historical sentence.

Generated indexes and compact summaries are working aids. Source files and runtime conversation access remain important when the question depends on fresh or missing history. The architecture therefore treats retrieval as a route back to evidence, not an assertion that an index contains every conversation or automatically merges every interface's transcript.

## Current coverage and freshness

Lexical recall reads the selected files at query time. Sparse-vector recall uses a saved snapshot and is rebuilt explicitly; source edits do not automatically invalidate the cache. Current conversation history can be read through OpenClaw's native session tools when needed. The optional local legacy history loaders are not a complete index of the current Gateway session store.

Memory writeback and consolidation are explicit agent/protocol operations, not guaranteed automatic capture of every event. The community continuity bridge includes the deep-search and source-freshness fields in its evidence pack; the reviewed earlier deployed bridge used a smaller compact response. Release code and private deployment state are tracked separately.

## The main agent owns the work

The main agent interprets the task, retrieves context, inspects sources, selects tools and integrates their results. Tool availability depends on the configured runtime and connections. The working set includes research, documents, code, local files, connected applications and software on the user's machines.

A task can involve more than a conversational reply. It can require reading a repository, preparing a change, executing an operation, checking a result and preserving the new state for later work. Those stages remain distinguishable in the conversation and project records.

## Voice is an interface to that work

The realtime voice layer is responsible for live spoken interaction. It can handle ordinary conversational turns directly. When a request needs the main agent's context or tools, it consults that agent and receives its result.

The voice session and the agent task have different lifetimes. A provider connection can end while a task is still running; a result can arrive after an interruption; a new spoken turn can begin while earlier work is completing. Coordination at these boundaries is part of the project's runtime engineering, described in [OpenClaw engineering](OPENCLAW.md).

## Glass extends the interface

Glass adds hands-free input, playback, glanceable text and camera-related interaction. Pixel handles the wearable connection and media bridge. The main agent, durable conversation and memory access remain with OpenClaw on the Mac.

The objective is continuity of identity and work across these interfaces. Navigation complements the wearable system through its own phone-to-Glass path; it does not replace the assistant's reasoning, memory or tools.

## Example record and workflow

The following is an illustrative example, not a private transcript:

```text
Project: Companion setup
Goal: build and configure a user-owned wearable assistant
Current state: source checked out; local build is the next step
Decision: preserve separate Glass/Pixel protocol roots
Next action: run the documented build with pinned dependencies
Evidence: source manifest and component build guide
```

When the user asks to continue this project, the agent retrieves the record, checks the cited source, performs the authorized next step and records its actual result. The card carries current state; build output supplies evidence; the conversation remains the history of the interaction. None of those alone substitutes for the others.

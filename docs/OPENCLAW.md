# Franziska / Engineering inside OpenClaw

[Overview](../README.md) · [Architecture](ARCHITECTURE.md) · [Assistant and memory](FRANZISKA.md) · [Glass & Pixel](WEARABLE.md)

## Scope of the work

We maintain a source-level OpenClaw customization for Franziska's wearable realtime workflow. The reviewed code line changes **33 paths** against a pinned official upstream revision. That count includes implementation, regression tests, protocol generation, documentation and test setup.

The work is a maintained local patch line. OpenClaw supplies the agent runtime, normal tools, configured-agent consultation, accepted-run custody, transcript machinery and much of the provider reconnect infrastructure. Our changes operate inside that architecture: they repair specific control, ownership and delivery boundaries exposed by a long-running wearable interaction.

| Source identity | Revision |
| --- | --- |
| Official OpenClaw base | [`1306cc5e4be6a1e4153870759505ea8c26bbacfc`](https://github.com/openclaw/openclaw/commit/1306cc5e4be6a1e4153870759505ea8c26bbacfc) |
| Reviewed local revision | `5c73cf2b31264d3650a67d0419a8a69921d9d5ca` |
| Local delta | 33 changed paths |

The same reviewed customization was carried across several pinned upstream generations. Those generations are forward ports of the same maintained work, not separate features to count repeatedly.

## 1. Make an agent consultation produce one terminal response

A realtime provider can acknowledge a tool call before the actual agent has finished. If an interim “working” result resumes speech, the user can receive an acknowledgement, speculation and then a second answer when the real result arrives.

Our change in `realtime-voice-model-contract.ts` makes the selected regular Google Live family use a blocking consultation declaration and derives continuation behavior from that actual contract. The provider waits for the agent terminal rather than treating an interim result as another response opportunity. Model families with different scheduling requirements retain their distinct contracts.

The companion regression suite works at the SDK boundary: declaration selection, continuation, serialization, send failures, retained responses and duplicate completion. This is a targeted change to the provider's interaction contract, not a replacement agent implementation.

## 2. Keep status and cancellation independent of a blocked consultation

A long consultation can occupy the companion tool dispatcher. Routing “stop that task” through the same occupied path defeats the control.

We added a server-owned adapter, `enqueueTalkRealtimeRelayAgentControl`, and route native agent controls to it. Once-only ledger admission prevents duplicate control queueing. It uses the existing run-control owner and returns the control result through the relay.

The target is captured before FIFO or provider-readiness waits. Status reads do not cancel a run. Cancel addresses the captured run. Steer and follow-up remain contextual input to existing work. This avoids turning a simple status question into a new task or an accidental Stop.

## 3. Cancel work before a backend run has registered

There is a gap between an admitted consultation and its companion/backend registration. Cancelling only registered runs leaves that gap uncovered: the user has cancelled, but late registration can still attach the work and return a result.

The local ledger distinguishes admitted consultation identity from successful `agentRegistered` state. Cancellation snapshots the active registrations and the admitted, not-yet-registered identities, then marks the captured incomplete calls cancelled before awaiting stop work.

Late registration sees that mark and is rejected. A pending consultation can be truthfully cancelled even if no backend run exists yet. A later successor call is not accidentally swept into the previous cancellation set. Normal and forced-consult result delivery consult the same ownership facts.

## 4. Return one cancellation acknowledgement, not two

For the blocking consult family, cancellation has two related terminals: the cancelled consultation and the native cancel-control response. Sending them separately can resume provider speech twice.

The Google bridge retains the related terminals in the existing bounded response buffer and sends them in one SDK `sendToolResponse` batch. Unrelated status and tool calls do not hold that batch. Serialization or send failure does not silently consume the retained successful prefix. Resumable reconnect and native tool cancellation preserve or release the correct members.

This change is about response ordering and provider scheduling; it does not replace the underlying transport's reconnect implementation.

## 5. Give late speech an exact, revocable owner

A valid final agent result may arrive after an earlier voice turn has already ended. Simply rejecting all later audio loses the answer. Letting any late audio open a new response allows stale work to speak over a successor turn.

The local output-ownership extension introduces bounded, generation-specific pending speech references. A legitimate final or control result can register an expectation for speech. Before that expectation is used, the code checks the retained session, closing state, tool-result epoch, current run authority and cancellation state.

A failed send revokes only its own reference, not another current result's expectation. Close, reset, cancellation and failed response paths invalidate the corresponding ownership. The existing turn-bound and exact-response modes remain separate.

This turns “the provider started speaking” into an ownership decision, rather than assuming every audio callback belongs to the latest user request.

## 6. Put text into the active Talk connection

`talk.session.sendText` is a local RPC addition for sending a text message through an already active realtime relay.

The closed schema accepts a session ID and nonblank text, with an 8,192-character raw limit. The method uses `operator.talk`, resolves the backing target through the retained relay and requesting connection, and rechecks current mutation authority before dispatch.

The handler requires the owning connection, a realtime relay, a ready provider, a connected bridge and actual `sendUserMessage` support. It trims and submits once. It does not create a replacement session or silently fall back to TTS or another transport. Its success means submission was accepted; completion is represented by subsequent session events.

The implementation is accompanied by protocol schema, generated validators, method discovery, session policy, handler and boundary tests. That full chain matters more than the existence of a handler alone.

## 7. Apply consistent ASR language hints

The Google provider normalizes configured speech-recognition language hints and applies them to both relay and browser setup. Tests cover the request payload and normalization. The change keeps provider setup consistent across the interfaces that share the contract.

## 8. Preserve native macOS lifecycle admission

The local service-membership fix handles a specific macOS observation: structurally valid `launchctl` PID output can omit coalition fields. In that case the implementation falls back to native resource-coalition inspection instead of treating omitted fields as established absence.

Malformed or ambiguous data remains unknown. Native lifecycle admission remains in force. This is a compatibility fix within the existing process-membership model, not removal of the hosting-Gateway protection.

## Upstream mechanisms we build on

Several important mechanisms are already upstream and should be credited accordingly:

- `openclaw_agent_consult` and the configured-agent execution path.
- Session-bound authority, accepted-run ownership and transcript persistence.
- Exact-speech message construction and retained answer handling.
- Provider session resumption, bounded buffers, reconnect backoff and stale callback checks.
- The standard tools and memory/workspace execution available to an agent.

Our patch connects to and hardens those mechanisms at the points described above. It is not presented as upstream-merged work or as authorship of OpenClaw itself.

## Maintaining the customization across updates

A separate maintenance project preserves the source delta and qualifies new candidates. It pins the exact official source, carries the complete customization range into an independent candidate and checks data-schema compatibility before proceeding.

The pipeline fingerprints source, policy, tools and artifacts; runs isolated gates; retains conflicts for review; and separates source qualification, copied-state rehearsal and actual activation. The deployment guard compares the serving installation and the full committed, dirty and untracked customization set. A release number alone does not establish compatibility.

Activation uses the supported external-owner lifecycle route. The maintenance code orchestrates native update, snapshot and migration owners rather than inventing an alternative database migration engine. This maintenance work is a separate local engineering artifact, outside the 33-path runtime delta.

## Recorded qualification

For the reviewed November source revision, retained qualification records show:

- **384 source/regression tests across 14 files.** This includes local tests and unchanged upstream preservation tests.
- **Seven candidate gates:** dependencies, focused tests, targeted typed Google checks, targeted typed core checks, formatting, protocol freshness and runtime build.
- **An eight-phase copied-state canary:** snapshot, native copied-state migration, lint, configuration, plugins, recovery, isolated Gateway startup and cleanup.
- **External activation and core reconciliation** of the reviewed candidate.

The runtime-build profile covers runtime, plugin assets and Control UI. The count is a source qualification record, not a claim that every test was authored here or that it represents hundreds of physical device trials. The deployed source and the complete 33-path inventory were checked again for this documentation review.

## Complete source inventory

Module paths below refer to the maintained OpenClaw source. They are not a claim that the runtime source is included in this presentation repository.

<details>
<summary>All 33 changed paths and their roles</summary>

| # | Source-relative path | Local role |
| --- | --- | --- |
| 1 | `docs/gateway/protocol/rpc-talk-config-and-agents.md` | Documents `talk.session.sendText`, scope, size, connection ownership, submission-only success, and no fallback. |
| 2 | `extensions/google/realtime-voice-model-contract.ts` | Adds `usesNonBlockingAgentConsult`; derives continuation eligibility from actual nonblocking contract while retaining sequential/Extended Thinking distinctions. |
| 3 | `extensions/google/realtime-voice-provider.test.ts` | Adds ASR-hint request assertions for relay and browser and normalization; updates the selected consult contract assertions. Existing provider recovery/transcript bounds remain. |
| 4 | `extensions/google/realtime-voice-provider.ts` | Normalizes ASR hints; applies them to relay/browser setup; selects declaration behavior; batches paired cancel terminals in the existing bounded response/reconnect buffer. |
| 5 | `packages/gateway-protocol/src/compiled/protocol.generated/predicates.d.ts` | Generated declaration for `isTalkSessionSendTextParams`. |
| 6 | `packages/gateway-protocol/src/compiled/protocol.generated/predicates.js` | Canonical generated predicate implementation; large renumbering/layout diff must not be advertised as authored feature volume. |
| 7 | `packages/gateway-protocol/src/compiled/protocol.generated/validators-integrations.d.ts` | Generated integration export for new predicate. |
| 8 | `packages/gateway-protocol/src/compiled/protocol.generated/validators-integrations.js` | Generated integration export for new predicate. |
| 9 | `packages/gateway-protocol/src/public-schema.ts` | Public export of `TalkSessionSendTextParamsSchema`. |
| 10 | `packages/gateway-protocol/src/schema/channels.ts` | Closed `{sessionId,text}` input schema and type; nonempty session ID, nonblank text, raw maximum 8,192 characters. |
| 11 | `packages/gateway-protocol/src/validators/integrations.ts` | Exposes `validateTalkSessionSendTextParams` using schema and generated predicate. |
| 12 | `src/daemon/service-process-membership.test.ts` | Covers successful launchctl output that omits coalition fields while preserving inside/outside/unknown behavior. |
| 13 | `src/daemon/service-process-membership.ts` | Missing coalition fields in otherwise valid PID-domain output fall back to native coalition inspection. |
| 14 | `src/gateway/methods/core-descriptors.ts` | Appends `talk.session.sendText` with `operator.talk`, preserving existing advertised method order and upstream siblings. |
| 15 | `src/gateway/server-methods-list.test.ts` | Updates append-only advertised-method contract for text RPC. |
| 16 | `src/gateway/session-method-policy.ts` | Adds sendText to existing profile-independent session-target policy. This does not remove write authorization. |
| 17 | `src/gateway/session-sharing-target-input.ts` | Resolves sendText's backing target from retained unified relay plus requesting connection, not caller-supplied session identity. |
| 18 | `src/gateway/talk/handlers/session.ts` | Validated sendText handler; realtime-only, owning connection, current mutation guard/authorization, trimmed dispatch, normal RPC error reporting. |
| 19 | `src/gateway/talk/relay/agent-consult.ts` | Marks successful run registration as `agentRegistered` instead of generic identity admission. |
| 20 | `src/gateway/talk/relay/forced-consults.ts` | Marks forced consult identities; settles explicit admitted cancel set and native aliases, aborts current registrations, respects ledger cancellation, carries speech source ownership. |
| 21 | `src/gateway/talk/relay/index.test.ts` | Integrated relay regressions for pending/active cancellation, native status, owner-bound output, rejected acceptance, late aliases/results, and successor turns. |
| 22 | `src/gateway/talk/relay/operations.ts` | Same-session text dispatch; current authority on controls; captures pending/admitted consult IDs before FIFO wait, cancels before asynchronous stop, reports pending-cancel success, requests final-result speech ownership. |
| 23 | `src/gateway/talk/relay/provider-results.ts` | Adds revocable speech expectations around owned final/control submission, respecting session/epoch/authority/cancellation and rejecting submissions. |
| 24 | `src/gateway/talk/relay/session-create.ts` | Parses explicit control mode; routes native controls server-side; marks native consult admission; tracks provider readiness; classifies real audio/nonblank assistant text as speech. |
| 25 | `src/gateway/talk/relay/state.ts` | Extends `TalkRealtimeRelayOutputOwnership` with bounded generation-specific pending speech references; readiness state and forced terminal speech metadata. |
| 26 | `src/gateway/talk/relay/tool-call-ledger.test.ts` | Tests consult provenance and exactly-once native-control queue admission. |
| 27 | `src/gateway/talk/relay/tool-call-ledger.ts` | Adds consult/registration/control-queued facts and methods for pending identity enumeration. Existing retained identity limits remain. |
| 28 | `test/vitest/vitest.gateway-server-paths.mjs` | Assigns new sendText tests to gateway database-worker test project. |
| 29 | `extensions/google/realtime-voice-consult-serialization.test.ts` | Dedicated SDK-boundary tests for declaration/continuation selection, cancel batching, unrelated status/tools, serialization/send failure, buffer limits, reconnect, and duplicate completion. |
| 30 | `src/gateway/talk/handlers/session-send-text.test.ts` | Text size, same bridge, ownership, session kind/readiness/connectivity/capability and no-retry failure regressions. |
| 31 | `src/gateway/talk/relay/agent-control.ts` | New `enqueueTalkRealtimeRelayAgentControl` adapter: once-only ledger admission to existing server run-control owner, canonical success/error result back through relay. |
| 32 | `src/gateway/talk/relay/output-ownership.test.ts` | Reference-level tests: keep another current expectation, old failure cannot revoke successor generation, thrown authority revokes only stale reference, hard cap. |
| 33 | `vitest.consult-fix.config.ts` | Isolated Google-extension proof configuration with its own cache directory. |

</details>

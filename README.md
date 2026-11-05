<p align="center"><img src="assets/franziska-glass.svg" alt="Franziska — personal AI assistant and wearable systems" width="100%"></p>

# Franziska

**A personal AI assistant with persistent working context, executable tools and a Google Glass interface.**

Built and developed by **Ivan Shpak**. Based on **OpenClaw**, with a maintained local runtime customization, a dedicated continuity system and native applications for **Google Glass XE24** and **Pixel 7**.

[Get started](docs/GETTING_STARTED.md) · [Source release](https://github.com/JohnnyStarling/franziska-glass/releases/tag/v2026.11.06) · [The assistant](docs/FRANZISKA.md) · [Architecture](docs/ARCHITECTURE.md) · [OpenClaw engineering](docs/OPENCLAW.md) · [Glass & Pixel](docs/WEARABLE.md) · [In operation](docs/STATUS.md) · [Credits](docs/CREDITS.md)

---

## Run your own instance

This is a **source-focused community release**. Start with the portable continuity tools, build the native companion applications, or reconstruct the pinned OpenClaw/navigation sources with the included patches.

```sh
git clone https://github.com/JohnnyStarling/franziska-glass.git
cd franziska-glass
python3 scripts/init-workspace.py --workspace "$HOME/franziska-workspace"
python3 "$HOME/franziska-workspace/continuity/recall.py" "example project" --json
```

That first step needs Python 3.11+ and creates only a new user-owned workspace. The full [getting-started guide](docs/GETTING_STARTED.md) covers model setup, application builds, signing, installation and fresh pairing.

| Included source | Entry point |
| --- | --- |
| Continuity search and OpenClaw plugin | [components/continuity](components/continuity/README.md) |
| Separate Glass and Pixel source trees | [assistant/BUILDING.md](assistant/BUILDING.md) |
| Public-only pairing and setup tools | [assistant/SETUP.md](assistant/SETUP.md) |
| Pinned OpenClaw / navigation patches | [docs/UPSTREAM_BUILD.md](docs/UPSTREAM_BUILD.md) |
| Generic identity and memory templates | [templates/workspace](templates/workspace) |
| Upstream source/provenance lock | [source-components.json](source-components.json) |

You supply your own accounts, model access, network route and device identities. Prebuilt navigation APKs are not part of this source release; its upstream components and artwork keep their own licenses. [Release notes](RELEASE_NOTES.md) distinguish source/build verification from user hardware setup.

## 01 / The project

Franziska is an assistant developed around a continuing working relationship with one person. She carries an explicit identity, retrieves previous decisions, works with project records and uses tools to act on the task at hand. Telegram, the desktop Control UI and Glass are interfaces to this wider system.

The engineering problem is continuity: carrying the right information from one conversation to another, coordinating a spoken interaction with an agent task, and returning an actual result through a small wearable device. That requires work in the runtime, the memory layer, the transport and the device applications.

OpenClaw provides the foundation. Our work includes changes to its voice and agent-coordination code, the continuity layer around the agent, native Android applications, media transport and an independent navigation integration. The system uses existing AI models; the project is the personal assistant and the machinery that makes those models useful across interfaces.

## 02 / System structure

<img src="assets/system-architecture.svg" alt="Franziska architecture: interfaces, Pixel bridge, OpenClaw main agent and realtime relay, memory, tools and voice provider" width="720">

| Layer | Responsibility |
| --- | --- |
| **Identity and instructions** | The assistant's working style, standing decisions and rules for handling context and tasks. |
| **Continuity and memory** | Project records, curated long-term context, conversation archives, retrieval and handoffs. |
| **Main agent** | Reasoning, source inspection, tool execution and ownership of durable work. |
| **Realtime voice** | Live spoken interaction and streaming audio, coordinated with main-agent consultations. |
| **Pixel companion** | The bridge between Glass and the Gateway: connectivity, media, service lifecycle and companion UI. |
| **Glass application** | Microphone and playback, conversation display, status, gestures and camera-related interaction. |
| **Navigation** | Phone-side OsmAnd routing with Bluetooth guidance on Glass through OsmGlass. |

The Mac hosts the agent runtime and durable context; its configured model backend supplies inference. The phone is a companion and media bridge; it does not become a second independent assistant. Navigation is a separate phone-to-Glass path and does not require the Mac.

[Open the full-size architecture diagram](assets/system-architecture.svg).

## 03 / From a question to an action

A voice request that needs the main agent follows a concrete chain:

1. **Glass captures the interaction.** The wearable handles input, display and playback.
2. **Pixel bridges the devices.** The assistant link uses Wi-Fi, including the phone's hotspot, while Pixel reaches the Gateway over the internet.
3. **The realtime layer handles speech.** It can answer conversationally or consult the main agent when the request needs working context or tools.
4. **The main agent recovers the relevant context.** Decisions and project state come from the continuity records and conversation, rather than from a new identity on the phone.
5. **Tools do the work.** The agent inspects sources or performs the authorized action and receives the result.
6. **The result returns through the voice session.** Task state, cancellation and delivery have to remain coordinated across the agent, provider and wearable.

This separation matters. A microphone stream, an accepted task, a completed agent result and finished playback are different events. Much of our OpenClaw and Android work concerns those boundaries.

## 04 / What we engineered

| Area | Project work |
| --- | --- |
| **OpenClaw runtime** | Local changes around realtime consultation, task ownership, final-answer delivery, cancellation, reconnect and observability; maintained as a source-level customization. |
| **Continuity** | Structured memory records, source-grounded recall, project handoffs and retrieval integration around the main agent. |
| **Wearable audio** | A Glass–Pixel–Gateway path with device-side buffering, audio lifecycle handling and explicit Stop behavior. |
| **Portable connectivity** | Protected Glass–Pixel communication, hotspot operation, endpoint recovery and Gateway authentication through mobile internet. |
| **Conversation and media** | Native Glass chat/status, the Pixel companion, gallery and media-transfer components. |
| **Navigation** | OsmAnd/OsmGlass integration and targeted repairs to route, position, transport and display lifecycles. Navigation has been tested in use. |
| **Maintenance** | A separate source/patch pipeline that preserves the customization across OpenClaw changes and checks storage compatibility before activation. |

The [OpenClaw engineering chapter](docs/OPENCLAW.md) explains the mechanisms and the boundary between upstream infrastructure and our local work. The [wearable chapter](docs/WEARABLE.md) covers the device path and navigation.

### What the integration changes for the user

| Situation | Mechanism |
| --- | --- |
| Stop is requested during a long task | A server-owned control path addresses the captured task without waiting behind its consultation. |
| An old result arrives after a new turn | Session, request and generation ownership prevent it from becoming the successor's response. |
| A photo is asked about during Talk | Image submission, agent final/history receipt and text return are linked to the same owned interaction. |
| The phone's local address changes | Endpoint discovery finds a candidate; the established peer identity still decides whether it is accepted. |
| An earlier project decision matters | Source-grounded recall retrieves the relevant record and evidence for the main agent. |

## 05 / Memory is part of the architecture

A conversation transcript records what was said. A project record states what matters now. A handoff states what the next run needs. An index makes those sources retrievable. Franziska uses these as distinct layers rather than treating the current model context window as the entire memory system.

The result is a working process for finding earlier decisions, continuing a task after a session change and correcting stale information. Identity, conversation history and retrieval are related, but they are not interchangeable.

[Read the assistant and memory design →](docs/FRANZISKA.md)

## 06 / Glass in use

Glass makes the assistant available through voice and short visible text. Pixel provides the portable link; the Mac remains the agent host. The navigation system calculates routes on the phone and sends guidance directly to Glass over Bluetooth. Navigation is tested and working in use; it is not dependent on the AI conversation being active.

[Operating configuration](docs/STATUS.md) · [Original device captures](docs/WEARABLE.md#recorded-interface-captures)

---

**Project and integration:** Ivan Shpak. **Foundation:** OpenClaw and configured AI models. **Navigation:** OsmAnd / OsmGlass and OpenStreetMap data.

Original first-party code, documentation and SVG artwork are MIT-licensed. OpenClaw and navigation keep their respective component licenses; see [licensing](LICENSE), [credits](docs/CREDITS.md) and [contribution guide](CONTRIBUTING.md). Personal records and production deployment material are not included.

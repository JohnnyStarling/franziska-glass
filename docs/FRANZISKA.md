# Franziska: the assistant behind the glasses

**Franziska is a personal AI assistant project created and developed by Ivan Shpak.** Its central question is how an assistant can become a continuing collaborator: familiar with the person's working context, able to revisit earlier decisions and equipped to help with real tasks over time.

Google Glass is one interface to that project. The assistant also exists outside the glasses, through Telegram and the OpenClaw Control UI.

## A personal system, built on existing models

Franziska is the identity and working system around configured AI models, not a new foundation model or a claim of human consciousness. OpenClaw supplies the agent runtime, conversations and tool infrastructure. The Franziska project develops the personal context, continuity practices and integrations around that foundation, including the Glass and Pixel applications.

The emphasis is on a coherent assistant that can follow ongoing work, rather than treating each new interaction as an isolated exchange. That continuity is a project goal supported by stored information and retrieval, not a guarantee that every channel or session already shares every detail automatically.

## Memory and continuity

The project uses saved preferences, curated notes, project records, handoffs and searchable conversation history to help recover relevant context. Earlier decisions should be grounded in those records rather than reconstructed from a plausible-sounding guess.

This supports questions such as what was decided, where a project stopped and what should happen next. The memory system remains under development: retrieval can miss information, records can become stale and session transitions can still need reconciliation. Perfect or unlimited memory is not claimed.

## Practical assistance

Depending on configured tools and permissions, Franziska can help with research, writing and editing, software development, local files, project organization and actions in connected applications or services. Tool results provide evidence for what actually happened; preparing a plan or starting an operation is not the same as completing it.

These are capabilities of the wider assistant system. Their presence does not mean every workflow has already been accepted end to end through Glass.

## What Glass adds

Glass brings a voice-and-glance interaction to an assistant that already has a desktop-hosted agent, working context and tools. The intended experience is to ask for help, receive a short visible or spoken response and continue with what you are doing without repeatedly opening a phone screen.

In the current assistant architecture:

- **Glass** provides wearable input, output and the conversation display.
- **Pixel** provides connectivity and media handling.
- **The reachable Mac running OpenClaw** hosts the main agent, durable conversation context, memory access and tools.
- **The realtime voice layer** handles live spoken interaction and consults the main agent when its context or tools are needed. Not every spoken reply is evidence that a main-agent tool task ran.

The objective is access to the same broader personal assistant, not a second independent assistant with a separate project history on the phone. AI functions currently depend on the reachable Mac and network connection.

## Navigation is a companion feature

The OsmAnd/OsmGlass integration is a separate Pixel-to-Glass Bluetooth path. Routing and downloaded maps live on the phone, so it does not require the Mac or OpenClaw. It complements the wearable experience but is not Franziska's memory, reasoning system or navigation engine developed from scratch.

## Present stage

The wider assistant is in use through text and tool workflows. The wearable integration is a working research prototype, with recorded main-agent consultations and installed Glass/Pixel interfaces. Long conversations, complete spoken delivery, predictable Stop/reconnect, real outdoor use, prism readability and thermal/battery behavior still need physical acceptance.

See the [evidence-based project status](STATUS.md) for the distinction between implemented, installed and demonstrated behavior. This presentation shares no personal memory records or conversations.

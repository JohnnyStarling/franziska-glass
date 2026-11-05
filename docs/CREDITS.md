# Franziska / Credits and provenance

[Overview](../README.md) · [OpenClaw engineering](OPENCLAW.md) · [Glass & Pixel](WEARABLE.md)

## Project work

**Ivan Shpak** — project creator and developer of Franziska's personal assistant system and wearable integration.

The project includes the local OpenClaw customization, continuity protocols and retrieval bridge, native Glass/Pixel companion, media/audio integration and navigation adaptations described in the technical chapters. The OpenClaw patch is maintained locally; it is not represented as an accepted upstream contribution.

## Foundations

| Project or platform | Role |
| --- | --- |
| [OpenClaw](https://github.com/openclaw/openclaw) | Agent runtime, tools, session and conversation infrastructure, foundational Talk consultation and realtime machinery. |
| Configured AI models and voice providers | Language, reasoning and realtime speech inference. These models are used by Franziska; they are not newly trained Franziska foundation models. |
| [OsmAnd](https://osmand.net/) | Maps, route calculation, position, ETA and navigation on the phone. |
| [OsmGlass](https://github.com/zakwilson/OsmGlass) | Upstream Glass navigation projection, shared protocol and compatible [OsmAnd fork](https://github.com/zakwilson/OsmAnd). |
| [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) | Map data in the navigation captures; [ODbL](https://opendatacommons.org/licenses/odbl/). |
| Android and Google Glass GDK | Device OS, media, services and LiveCard platform. |
| [Bouncy Castle](https://www.bouncycastle.org/) | Established TLS/cryptographic components integrated for the old-Android transport seam. |

Original routing algorithms, cartography, AI models and platform APIs retain their upstream authorship. Our work is the customization, integration and specific repairs documented here.

## Navigation source origins

- OsmGlass base: `13626781089e6f22862395483430a89db98b6ae4`.
- Compatible OsmAnd fork base: `fd9b8e0b8ecbe0167b233dc472bca8976aee66bd`.

The deployed navigation release includes local repairs and selected compatible upstream integrations beyond those base revisions. Base HEAD alone is not the full release manifest.

## Visual material

- **`assets/franziska-glass.svg`** — original monochrome editorial cover.
- **`assets/system-architecture.svg`** — original architecture diagram, showing principal ownership and transport links.
- **`assets/navigation-left.png` / `assets/navigation-right.png`** — unchanged 30 October 2026 framebuffer captures from real Glass during native route simulation. The Russian text belongs to the captured application UI. Navigation UI: OsmAnd/OsmGlass; map: © OpenStreetMap contributors.

The captures are dated engineering material. The diagrams describe the system; they are not photographs of a running interface.

Application source and packaged binaries are maintained separately from this technical presentation. Independent project, not affiliated with Google. Third-party names belong to their respective owners.

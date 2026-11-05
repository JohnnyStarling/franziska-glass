# Franziska / In operation

[Overview](../README.md) · [Architecture](ARCHITECTURE.md) · [OpenClaw engineering](OPENCLAW.md) · [Glass & Pixel](WEARABLE.md)

## Current use

Franziska is a working personal assistant with text, tools and a wearable interface. The system is in use and working well. Navigation has been tested in use and is part of the operating configuration.

| Component | Operating role |
| --- | --- |
| Telegram and Control UI | Text interaction, documents and main-agent work. |
| Main OpenClaw agent on Mac | Reasoning, tool execution, conversation state and continuity access. |
| Realtime voice provider and Gateway relay | Spoken interaction and coordinated main-agent consultations. |
| Google Glass assistant application | Voice, conversation display, status, Stop and camera-related interaction. |
| Pixel companion | Local link, authenticated Gateway connection, media bridge, gallery and service ownership. |
| OsmAnd / OsmGlass navigation | Tested phone-side routing and Bluetooth guidance on Glass, independent of the Mac agent. |

## Reviewed implementation baseline

The documentation review inspected the installed-source derivatives for **Glass56 / Pixel50**, the maintained navigation release and the current OpenClaw customization at **`5c73cf2b3126`**. It also checked the complete 33-path OpenClaw source delta and the actual serving candidate's source identity.

The release-specific engineering record is described in [OpenClaw engineering](OPENCLAW.md). Original device captures remain separately dated in [Glass & Pixel](WEARABLE.md#recorded-interface-captures).

## Portable configuration

The assistant uses Glass → Pixel Wi-Fi/hotspot → internet → the reachable Mac Gateway. The phone carries the connection; the Mac remains the agent host and can stay elsewhere.

Navigation uses Pixel → bonded Bluetooth → Glass. Maps and route calculation remain on the phone. This path does not depend on the assistant's voice session or the Mac.

The Android system hotspot control, explicit Glass Stop and the companion's enabled/disabled service state remain distinct operations. Phone notification forwarding is not part of the configured wearable experience.

## Presentation record

The project documentation was rebuilt on 6 November 2026 from the maintained source, frozen installed application sources, technical reports and current operating feedback. The GitHub work changes documentation and presentation; it does not deploy new applications or alter the running Gateway.

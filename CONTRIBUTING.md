# Contributing

Franziska joins a personal agent, a realtime voice path and native wearable software. Changes should identify the component and lifecycle they affect.

## Useful reports

Open an issue with the source revision, device/OS versions, affected component, steps to reproduce, expected behavior and observed result. For voice or reconnection problems, distinguish the local Glass–Pixel link, Gateway connection, agent task and playback state. Include a minimal redacted trace rather than an entire personal conversation or device export.

## Changes

Keep a pull request focused. Explain the old behavior, the intended change and how it was checked. Include a regression case when changing ownership, cancellation, protocol boundaries or asynchronous callbacks. Documentation-only changes do not need unrelated builds.

Do not commit signing material, pairing identities, access tokens, personal configuration, generated device backups or build output. Use sample configuration and fresh locally generated identities for development.

## Upstream boundaries

OpenClaw, OsmAnd and OsmGlass have their own codebases and licenses. Keep upstream attribution and source pins intact. Clearly distinguish an original local fix from a compatible upstream backport. Contributions to a component follow that component's applicable license.

The project documentation and examples are maintained in English.

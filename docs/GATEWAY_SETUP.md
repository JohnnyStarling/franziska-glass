# Gateway setup / fresh user-owned instance

[Getting started](GETTING_STARTED.md) · [Pinned source build](UPSTREAM_BUILD.md#openclaw-source-build-no-activation) · [Device setup](../assistant/SETUP.md)

The Pixel reference client requires the protocol-4 OpenClaw/Talk contract in [assistant/COMPATIBILITY.md](../assistant/COMPATIBILITY.md). Use the pinned source and complete local patch, not an arbitrary binary chosen by version number.

## Build the pinned source

Follow [UPSTREAM_BUILD](UPSTREAM_BUILD.md) to reconstruct OpenClaw and run its source-supported build commands with the pinned Node/pnpm toolchain. Build operations do not activate an installed service.

For a fresh instance on your own host, enter the reconstructed `openclaw/` directory. Its executable entry point is `openclaw.mjs`; the source runner is `pnpm openclaw`.

```sh
node openclaw.mjs onboard
```

Use the guided wizard to choose your own main reasoning model, provider authentication, workspace and Gateway settings. For source-only baseline setup the pinned upstream also supports `pnpm openclaw setup --baseline`; it is not a substitute for configuring actual model access.

After setup, a foreground Gateway can be run from this source:

```sh
node openclaw.mjs gateway --port 18789 --verbose
```

Use the port and state/config directory chosen for your instance. On a host with an existing Gateway, do not launch a second owner against its state or overwrite its service; follow the pinned upstream's separate-instance/service-owner instructions. These commands are directions for your host, not a deployment action performed by this repository.

## Main agent and workspace

The unmodified Pixel client creates `agent:main:glass:<conversation UUID>`. It therefore uses **main**, even if a separate text agent named `franziska` exists.

For a fresh deployment, select the newly initialized Franziska workspace for `agents.entries.main.workspace`. Preserve the main model/provider settings and other agent entries. The continuity plugin's `workspace` must point at the same intended corpus. Shared identity does not automatically merge all channel transcripts.

## Configure realtime voice separately

The reasoning backend and realtime speech provider have different responsibilities. The reference integration uses a Google Live route; you need your own supported Google/Gemini API access in addition to whichever main-agent model you select.

The pinned Google provider can use its configured account/API key, `models.providers.google.apiKey`, or Gateway-process `GEMINI_API_KEY` / `GOOGLE_API_KEY` fallback. Use the supported credential entry for your instance; no secret is included here. A service launched outside your terminal may not inherit that terminal's environment.

The following is a **configuration fragment** for the reference Talk shape, not a complete OpenClaw config or a credential file:

```json
{
  "plugins": { "entries": { "google": { "enabled": true } } },
  "talk": {
    "agentId": "main",
    "speechLocale": "en-US",
    "realtime": {
      "provider": "google",
      "providers": {
        "google": {
          "model": "gemini-3.8-live",
          "speakerVoice": "Aoede"
        }
      },
      "mode": "realtime",
      "transport": "gateway-relay",
      "brain": "agent-consult"
    }
  }
}
```

Merge through the pinned version's supported configuration UI/CLI. If you use `plugins.allow`, retain its existing entries and admit `google` through the supported flow; keep other providers and settings. The model must be available to your account. The project's regular-Live consultation fix is part of the supplied patch. Batch TTS, an OpenAI-only WebRTC configuration or the Voice Call plugin's separate settings are not interchangeable with this client path.

## Reach the Gateway over WSS

Pixel uses a valid-CA `wss://` URL. Choose your own authenticated HTTPS/WSS route, for example the pinned upstream's Tailscale Serve deployment or your own correctly configured TLS reverse proxy. A reachable plain `ws://` listener, self-signed certificate or the author's private endpoint is not the reference setup.

The local Glass–Pixel RFC1918 address belongs only to the peer link; it is not the Gateway URL. Pairing that local link does not authenticate the Pixel client to OpenClaw.

## Pair the Pixel client

Follow [assistant/SETUP](../assistant/SETUP.md). Approve the exact new device request in your own trusted Gateway interface. The client generates its own Gateway identity and per-install conversation UUID; do not copy another installation's tokens or session identifier.

The server must actually allow the negotiated chat/Talk paths and the chosen session. A route or catalog entry alone is not a substitute for successful application authentication. Test a simple agent request, the Talk relay, Stop and a visual question as separate deliberate user actions.

The source build and host checks do not enroll devices, start microphones or migrate existing personal data.

# Getting started / source release

[Overview](../README.md) · [Architecture](ARCHITECTURE.md) · [Contributing](../CONTRIBUTING.md)

This repository includes reusable source, generic workspace templates and pinned upstream patches. Choose the part you want to run; the memory tools, wearable applications and navigation have different prerequisites.

## 1. Clone the project

```sh
git clone https://github.com/JohnnyStarling/franziska-glass.git
cd franziska-glass
```

## 2. Start with the assistant's continuity tools

Requirements: Python 3.11+; no paid service or device is needed for this step.

```sh
python3 scripts/init-workspace.py --workspace "$HOME/franziska-workspace"
python3 "$HOME/franziska-workspace/continuity/semantic_index.py" build
python3 "$HOME/franziska-workspace/continuity/recall.py" "example project" --json
```

You now have a fresh, inspectable corpus and a working recall CLI. Add your own preferences and project records. The initializer refuses to overwrite existing data.

For integration with your own OpenClaw agent, follow the [continuity component guide](../components/continuity/README.md). You supply your own model/provider authentication and channel bindings. The existing author's accounts and personal records are not part of the release.

## 3. Prepare the compatible Gateway source

The wearable client uses protocol 4 and the Talk relay/agent-consult contract described in this repository. It is not qualified against an arbitrary release selected only by its version number.

The [upstream build guide](UPSTREAM_BUILD.md) gives the pinned public OpenClaw source, maintained patch and source-reconstruction commands. Then use the [fresh Gateway setup guide](GATEWAY_SETUP.md) for onboarding, main-agent binding, realtime voice and your own WSS route. Preparation does not activate a running Gateway or migrate an existing installation. For an existing installation, retain OpenClaw's native storage/version admission and recovery procedures.

The reference Pixel client binds its conversation to **main**. If you used the optional additional `franziska` agent in the continuity guide, that is a separate text-agent example. For the wearable setup, select the initialized Franziska workspace for `agents.entries.main.workspace` in your fresh Gateway configuration; keep provider authentication and all unrelated settings intact. Do not assume changing a display name redirects the app's session key.

## 4. Build the Glass and Pixel applications

Use [assistant/BUILDING.md](../assistant/BUILDING.md) for the portable, pinned-dependency build. The source roots remain separate because their protocol/TLS implementations differ.

This is a developer/community sideload edition. The existing public-only USB bootstrap depends on debuggable applications and on-device generation of private identities. The reference hardware is Glass XE24 and Pixel 7; other models are not silently accepted by the existing role checks.

You sign the built APKs with your own local app-signing key before installing them. Keep that key for subsequent updates. A new signer cannot update an existing differently signed same-package installation; do not uninstall or erase a working installation as a routine workaround.

## 5. Install and create your own pairing

Follow [assistant/SETUP.md](../assistant/SETUP.md) for install, two-device public-identity bootstrap, Gateway authentication, Prepare/Start/Stop and deliberate media opt-in.

The devices generate their own private pairing identities. Only public trust material is exchanged by the bootstrap. Local Glass–Pixel pairing, Pixel–Gateway authentication and AI-provider authentication are separate setup steps.

## 6. Optional navigation

Navigation uses the OsmAnd/OsmGlass source pins and local patch set in [the upstream build guide](UPSTREAM_BUILD.md). Routing runs on the phone and guidance reaches Glass by Bluetooth. The working reference integration has been tested in use.

This source release does not redistribute the existing navigation APK with its bundled third-party artwork and native libraries. Source reconstruction and component licensing are documented separately from binary distribution.

## 7. Verify your own instance

Check each route with a deliberate user interaction:

1. Text agent: a simple request and a source-backed recall question.
2. Local readiness: Glass–Pixel authenticated connection, then separate Gateway authentication.
3. Voice: one question, complete reply, explicit Stop and deliberate restart.
4. Visual question: select/capture an image, agent result, return to the owned Talk session.
5. Media: opt in, copy one original, check the resulting file and gallery entry.
6. Navigation: download your map, start a route on the phone, confirm guidance and understand the separate Glass-display/phone-route Stop controls.

The source release is checked through the recorded build and host tests. Device setup is performed on your own hardware, with your own identities and network configuration.

## Source and licenses

See [source-components.json](../source-components.json), [credits](CREDITS.md) and the component notices. Original first-party material is MIT; upstream and navigation components retain their own licenses. Do not apply the root MIT license to GPL navigation code or third-party resources.

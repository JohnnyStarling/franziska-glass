# Franziska wearable assistant — community source

Developer/sideload community edition for Google Glass XE24 and a Pixel 7 companion. Glass connects to Pixel over exact-certificate-pin mutual TLS; Pixel connects to a separately supplied compatible OpenClaw Gateway. This is independently buildable source, not a hardened consumer distribution or a claim of fresh device acceptance.

## Contents

- `glass-source/`: 83 frozen source/resource inputs.
- `pixel-source/`: 102 separately preserved inputs, with the owner-specific broker removed/disabled.
- `scripts/`: portable frozen build, checksum-locked public dependencies and public-only USB trust bootstrap.
- `tests/`: host-only validation and disabled-broker regressions; no device operations.
- `licenses/`: versioned upstream notices. Original project code is MIT; upstream code retains its own licenses.

Keep the two source roots separate: protocol/TLS implementations have intentional role-specific differences. The coupled Pixel companion/media classes are retained; their inclusion is not a claim that all optional features were qualified.

Start with [BUILDING](BUILDING.md), then [COMPATIBILITY](COMPATIBILITY.md) and [SETUP](SETUP.md). Read [third-party notices](THIRD_PARTY_NOTICES.md) before redistribution. The repository's [pinned Gateway patch/source build recipe](../docs/UPSTREAM_BUILD.md#openclaw-source-build-no-activation) is a separate prerequisite: neither these apps nor a generic stock Gateway provide the full server contract by themselves.

The source build produces **unsigned**, aligned APKs only. Dependency jars, intermediate files, mappings and APKs stay in ignored local directories. No signing keys, identities, saved networks, service endpoints or device exports are supplied.

## Packaging changes

The MyGlass broker is intentionally unconfigured: no token entry/import/enable controls, no request implementation and no enabled preference can activate it. This does not configure a replacement server. Local route confirmation UI, assistant/transport/media logic and blank Gateway defaults are retained.

Bouncy Castle runtime notices match upstream 1.86. Reflective string relocation now agrees with the integration class namespace; an actual BC reflection roundtrip is covered by a host test. Both manifests deliberately retain `debuggable=true`, because this edition's public-trust bootstrap requires `run-as`.

Clean community builds have passed host qualification, not installation, Glass rendering, sensors, Talk delivery, stop/idle or end-to-end Gateway testing. See the compatibility boundary before choosing devices or signing an update.

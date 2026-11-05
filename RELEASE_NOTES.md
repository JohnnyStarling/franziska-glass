# Franziska — community source release

A personal AI assistant with a maintained OpenClaw customization, portable continuity tools and native Google Glass / Pixel integration.

## Included

- Separate clean Glass and Pixel assistant sources, a pinned direct Android build and public-only fresh-identity bootstrap.
- Portable lexical/sparse-vector recall, OpenClaw plugin, generic workspace templates and a no-overwrite initializer.
- Complete local OpenClaw and navigation patches, exact public source pins, component licenses and source-reconstruction tooling.
- Five corresponding curated upstream source archives, with retained-file manifests and checksum verification.
- English architecture, build, setup and contribution documentation; original monochrome artwork.

## Qualification and scope

Both assistant applications were built from clean exported source with freshly fetched checksum-locked dependencies. Host checks: 20 assistant tests plus 5 Python and 4 Node continuity tests. Unsigned reference APKs passed manifest, single-DEX, alignment, notice and relocation checks; they are not distributed as ready-to-install signed apps.

This is a developer/source release. Use your own model accounts, signing key, devices, public pairing identities and Gateway route. Glass XE24 / Pixel 7 are the reference pair; the existing USB bootstrap uses debuggable applications.

Navigation remains a tested working integration. This release distributes its source and patches, not the old navigation APK with unresolved third-party binary/artwork provenance. Source reconstruction and 12,848 preferred-native input hashes were verified; a fresh complete navigation binary build is not claimed.

## Downloads

The repository/tag source contains the assistant and continuity code. The attached archives reconstruct the pinned upstream components; see `docs/UPSTREAM_BUILD.md` and `source-components.json` for layout, licenses, omitted resource/prebuilt payloads and restoration requirements. Verify the attached `SHA256SUMS` before extracting.

Original first-party material is MIT. OpenClaw and navigation retain their component licenses. No personal memory, credentials, pairing exports or signing keys are included.

# Component licenses and notices

The [root license index](LICENSE) separates original first-party material from upstream code, patches and resources.

| Material | Terms and retained notices |
| --- | --- |
| Original assistant source and tooling | MIT; [assistant/LICENSE](assistant/LICENSE). Dependencies are separately listed in [assistant/THIRD_PARTY_NOTICES.md](assistant/THIRD_PARTY_NOTICES.md). |
| Continuity source/plugin, fresh templates and root tools/tests | MIT; [LICENSE-MIT](LICENSE-MIT). No original author's private memory or generated index data is included. |
| Original documentation and SVG artwork | MIT; [LICENSE-MIT](LICENSE-MIT). |
| OpenClaw patch and reconstructed upstream source | OpenClaw MIT plus existing nested notices; [upstream license inventory](LICENSES/README.md). |
| OsmGlass/OsmAnd/native navigation deltas | Respective upstream GPL and file-level terms; full GPL text and component texts in [LICENSES](LICENSES). |
| Navigation resources, fonts, data and libraries | Separate upstream resource/library terms; [LICENSES/index.json](LICENSES/index.json) and [source-components.json](source-components.json). The original navigation APK is not redistributed. |
| Map content in unchanged navigation captures | [© OpenStreetMap contributors](https://www.openstreetmap.org/copyright), [ODbL](https://opendatacommons.org/licenses/odbl/); navigation UI by OsmAnd/OsmGlass. |

The source reconstruction script fetches declared public upstreams and applies the project's patches. Their original licenses, attribution and resource exceptions remain in the reconstructed trees. A patch or a root license file does not remove those exceptions.

The assistant build fetches pinned dependency jars with checksum verification. It does not distribute the author's SDK, credentials, signing material or private pairing identity. The compatibility guide states the developer/sideload setup boundary.

No private production configuration, personal conversation export, regional map download or owner signing key is part of the release.

# Public source modification notice — 2026-11-06

The patches in this directory convey local source changes against the exact public bases in [source-components.json](../source-components.json). They are plain unified binary diffs: no commit messages, private Git metadata, author addresses, bundles, remotes, or owner build receipts.

| Patch | Changed/new files | Scope |
| --- | ---: | --- |
| openclaw-franziska.patch | 33 | MIT OpenClaw customization; official 1306cc5e4be6a1e4153870759505ea8c26bbacfc → content of 5c73cf2b31264d3650a67d0419a8a69921d9d5ca |
| osmglass-franziska.patch | 26 | Glass UI/livecard, transport and shared protocol source/config/tests; 22 tracked edits + 4 allowlisted additions |
| osmand-franziska.patch | 39 | Phone/GlassNav/routing source and build/verification inputs; 33 tracked edits + 6 allowlisted additions |
| core-legacy-franziska.patch | 5 | Native offline routing C++/headers |
| resources-franziska.patch | 5 | Routing test definition + 4 named upstream regression fixtures, including binary/gzip test data; not user maps |
| osmand-public-debug-signing.patch | 1 | Additional public packaging adaptation: Android developer-owned default debug signing, no shipped keystore |

Navigation patches include the **working-tree deltas**, not just the base HEADs. Outer dirty gitlinks are deliberately omitted: nested repositories and GDK are independently pinned. Untracked directories are never blindly exported. The complete per-file change list and content digests are in the machine-readable component map.

One added non-runtime host-test helper (`test/glassnav-host/run.py`) is adapted for portability: private JDK/Android SDK/Kotlin-library installation paths become required `JAVA_HOME`, `ANDROID_HOME`, and `GLASSNAV_HOST_KOTLIN_LIB_DIR` inputs. Its original working-file hash and public hash are distinguished in the manifest. All app/runtime source bytes match the audited working files. The signing patch is separately hashed and applied **after** exact release-source verification.

Licenses follow components: OpenClaw MIT, navigation GPL with upstream exceptions, resources/data under their own terms. The repository's first-party MIT license is not an alternate license for the navigation patches. Preserve upstream attribution and warranty/license notices; see [LICENSES](../LICENSES/README.md).

Source-only snapshots are generated from the **patched working trees**, not `git archive HEAD`. They include no APK, native/OpenGL/Qt prebuilt payload, personal map set, keystore, private state, or metadata. Omitted upstream resource/prebuilt collections and exact retained files are listed in each snapshot file manifest. Source reconstruction is not binary build qualification.

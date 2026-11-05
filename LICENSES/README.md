# Component license index

This is an index, not a blanket license. The top-level first-party license does not relicense navigation, upstream components, artwork, datasets, or dependencies.

| Component | Governing license / retained text |
| --- | --- |
| OpenClaw and Franziska OpenClaw delta | MIT; [upstream LICENSE](openclaw/LICENSE), [third-party notices](openclaw/THIRD_PARTY_NOTICES.md), [protocol license](openclaw/packages/gateway-protocol/LICENSE) |
| OsmGlass Glass app and shared protocol | GPLv3; [complete upstream text](osmglass/LICENSE). Do not infer an “or later” grant for every outer file. |
| OsmAnd Java/Kotlin and in-tree GlassNav | GPLv3 or later for code, with separate resource/library exceptions; [complete upstream overview](osmand/LICENSE), [authors](osmand/AUTHORS.md), [full GPLv3](GPL-3.0.txt) |
| Legacy native core | [upstream license and exceptions](core-legacy/LICENSE), GPL source; protozero/vtzero and included libraries retain their own texts |
| Prepared protobuf, Skia, Expat, FreeType, HarfBuzz, JPEG, PNG, Lua, zlib | Exact retained notices under [native-dependencies](native-dependencies/); inspect each file, not one guessed bundle license |
| Compile-only Glass GDK API class tree | Apache-2.0 at the exact public pin; [license](gdk/LICENSE). No GDK runtime firmware or stub jar is distributed here. |
| OsmAnd resources, fonts, artwork and data | [upstream resource license](resources/LICENSE), font/icon notices and astro provenance preserved under resources/. Not relicensed as GPL or MIT. |

[index.json](index.json) maps the preserved texts to their upstream component paths and SHA256 digests. Complete reconstructed upstream trees retain additional in-tree notices; this extracted index is not an inventory of every transitive Maven/npm/runtime binary.

## Scope of this source release

Local navigation code remains under the applicable upstream GPL terms; there is **no MIT claim over GPL navigation**. Changes are identified, dated 2026-11-06, and individually enumerated in [source-components.json](../source-components.json) and [patch change notice](../patches/CHANGES.md). Existing copyright/credit notices are preserved. These notices describe this public source cutoff, not private Git identities.

The source archives omit navigation media/artwork/font collections, maps other than four explicitly approved routing test inputs, prebuilt libraries, APKs, GDK classes, and all signing keys. Resource code and references remain in the source. Developers may independently obtain required upstream assets subject to their own terms; this is not a grant to redistribute those assets.

The upstream OsmAnd overview assigns UI/artwork to CC-BY-NC-ND 4.0 with exceptions, warns that some artwork is proprietary, and identifies several metro icons as fair use. Preserving that overview does not grant unrestricted reuse. The source-only patches contain no metro artwork. OSM-derived regression fixtures retain OpenStreetMap contributor attribution and applicable ODbL data terms; generated binary fixture format does not relicense the underlying data. See https://www.openstreetmap.org/copyright and https://opendatacommons.org/licenses/odbl/1-0/ .

## Why no navigation APK

The retained tested navigation is not described as broken. APK redistribution is deferred because the exact frozen OpenGL/Qt AAR corresponding-source/build mapping is incomplete; restricted/fair-use asset rights are not cleared; exact patched Trove/ICU/custom-chart provenance and data/font mappings need completion; and the current common dependency set includes Google billing, review and Play Services Location. The legacy JNI source pin does not supply the missing OpenGL/Qt source. A future APK needs its own matching corresponding-source access and dependency/resource notices; this patch release is not that clearance.

No original owner signing key, private build log, device configuration, credential, or private Git metadata is included.

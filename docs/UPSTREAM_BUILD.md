# Pinned upstream source and local build guide

## Scope and verification

This is a **source-only** community release. Five complete patches preserve all 108 audited changed/new files: OpenClaw33, OsmGlass26, OsmAnd39, legacy core5, resources5. Navigation includes working-tree changes and every explicit new-file allowlist entry, not just upstream HEADs. One host-test helper has portable toolchain paths, and a separate public packaging overlay selects developer-owned debug signing.

[source-components.json](../source-components.json) specifies full public commit/tree IDs, patch SHA256, per-changed-file hashes/modes, dependency/resource pins, and limitations. [LICENSES](../LICENSES/README.md) preserves component licenses. OpenClaw is MIT; navigation is GPL with upstream exceptions, **not MIT**. Changes are marked in [CHANGES](../patches/CHANGES.md), dated 2026-11-06.

Success means **source-reconstructed**, not build-verified. This preparation did not build, install, activate, change devices, Gateway, configuration or update state. The retained tested navigation is not characterized as broken. No navigation APK, OpenGL/Qt/native prebuilts, personal maps, GDK jar/classes, keys, private logs/device configuration or private Git metadata are redistributed.

## Release source archives

The [v2026.11.06 release](https://github.com/JohnnyStarling/franziska-glass/releases/tag/v2026.11.06) provides the five curated source archives and `SHA256SUMS`. Their uploaded bytes were checked against GitHub's asset SHA256 digests. Exact sizes, hashes and retained-file manifests are in [source-components.json](../source-components.json).

After downloading the archives and checksum file into the same directory, verify before extracting:

```sh
shasum -a 256 -c SHA256SUMS
```

The source reconstruction tool below is an independent way to obtain the same source. The archives do not include private history or the omitted navigation binary/resource payloads; restore local upstream build inputs under their own terms as described below.

## Public source reconstruction

Requirements: Python3.9+, Git, and POSIX `patch` for prepared native source. Choose a NEW destination outside the release repository and any installed runtime:

```sh
python3 scripts/checkout-source.py ../franziska-public-source --native --snapshots
```

The tool validates approved patch hashes **before** network/destination creation, fetches exact public commits into independent repositories, checks base trees, runs `git apply --check`, applies the actual patches, then verifies every changed/new file and the sorted changed-file aggregate. The separately hashed developer-signing overlay is applied after exact app-source verification.

`--native` fetches exact protobuf/Skia and seven dependencies, applies ordered patches and verifies all12,848 historical native input files. `--snapshots` archives **patched working trees**, never `git archive HEAD`, with deterministic metadata and no `.git`/keys/prebuilts/navigation artwork/fonts/media. Only the four explicit routing regression fixtures pass map-file exclusions. Every archive has a full retained-file hash manifest and an omitted-file list. `RECONSTRUCTION.json` distinguishes complete source reconstruction from build verification. The five prepared archive asset names, digests/sizes, and repository-hosted full file manifests are recorded as `source_snapshot` in the component map; publication status is explicit rather than an invented download URL. The OpenClaw archive is larger than GitHub's single-file Git limit: publish it as a release asset, not a Git blob. The same archive is independently regenerable with `--snapshots`. Existing destinations are refused, including interrupted ones; inspect their incomplete receipt and choose a fresh destination.

Omit `--native` for repository source only; `--component openclaw` selects just OpenClaw. Source hashes are never re-pinned from newly served bytes. Network/checksum/patch failures are failures, not successful builds.

Optional large-source caches:

```sh
python3 scripts/checkout-source.py ../franziska-public-source \
  --object-cache openclaw=/path/to/source-object-cache --snapshots
```

Only the exact public base is imported, never private HEAD/history/remotes/config/working files. Cached commit/tree identity is independently proved through unauthenticated GitHub/Gitiles metadata. Receipts do not store cache paths. IDs: the five component IDs; `skia` and `skia-expat`, `skia-freetype`, `skia-harfbuzz`, `skia-libjpeg-turbo`, `skia-libpng`, `skia-lua`, `skia-zlib`. `--download-cache DIR` accepts source archives named by approved SHA256, not arbitrary binary inputs.

```text
franziska-public-source/
  openclaw/
  navigation/osmglass/
    protocol/
    glass-app/
    OsmAnd/
    resources/
    core-legacy/
      externals/protobuf/upstream.patched/
      externals/skia/upstream.patched/
  source-archives/                 # --snapshots only
  RECONSTRUCTION.json
```

For independently obtained, SHA256-verified release source assets, assemble the same layout without Git metadata (create a NEW `public-source` directory first):

```sh
mkdir -p public-source/openclaw public-source/navigation/osmglass
mkdir -p public-source/navigation/osmglass/OsmAnd
mkdir -p public-source/navigation/osmglass/core-legacy
mkdir -p public-source/navigation/osmglass/resources
tar -xzf openclaw-source.tar.gz -C public-source/openclaw --strip-components=1
tar -xzf osmglass-source.tar.gz -C public-source/navigation/osmglass --strip-components=1
tar -xzf osmand-source.tar.gz -C public-source/navigation/osmglass/OsmAnd --strip-components=1
tar -xzf core-legacy-source.tar.gz -C public-source/navigation/osmglass/core-legacy --strip-components=1
tar -xzf resources-source.tar.gz -C public-source/navigation/osmglass/resources --strip-components=1
```

Verify each retained file against its published file manifest; navigation resource/prebuilt omissions are intentional and require independent local upstream restoration for a later build. Licensed OpenClaw font resources and public upstream source-test fixtures are retained; they are not owner state.

Outer gitlinks have no usable tracked `.gitmodules`; OsmAnd/core-legacy are independent nested repositories. Explicit layout matters: OsmAnd consumes `../protocol`, collection uses `../../resources`, and JNI uses the sibling legacy core. Stock OsmAnd master or recursive cloning is not this source release.

Reconstruction checkouts fetch upstream content under upstream terms. Upstream signing keys are never materialized, but other tracked resources/prebuilt inputs remain in local checkouts; **published snapshots exclude them**. Never publish raw checkouts or their `.git`. Independently restoring licensed upstream resources locally does not clear their redistribution.

## OpenClaw (source build, no activation)

Official base `1306cc5e4be6a1e4153870759505ea8c26bbacfc` at https://github.com/openclaw/openclaw.git plus the33-path MIT patch reproduces content of `5c73cf2b31264d3650a67d0419a8a69921d9d5ca`. Package2026.10.9 is customized, not unmodified stable2026.10.9.

Use public Node26.12.1 (historically qualified) and the **full** `packageManager` integrity pin in reconstructed `package.json`: pnpm12.15.0. Declared Node range is `>=24.16.0 <25 || >=26.1.0`; compatible does not mean historically qualified. Preserve lockfile/workspace/catalogs/dependency patches; their exact hashes are in the manifest.

From the NEW OpenClaw checkout, source-supported commands (not run in this preparation):

```sh
corepack pnpm install --frozen-lockfile
corepack pnpm build:runtime
corepack pnpm plugins:assets:build
corepack pnpm ui:build
node --import ./scripts/tsx.mjs scripts/generate-gateway-validators.mts --check
```

Full orchestration is `corepack pnpm build`, owning runtime/plugin-SDK/native-protocol/build-stamp steps. Do not substitute another installation's `dist` or caches. Runtime output: `dist/`; UI: `dist/control-ui/`. Archive consumers may initialize a fresh local Git repository for build-identity tools, without importing private history.

Focused source tests:

```sh
node scripts/run-vitest.mjs run --config vitest.consult-fix.config.ts \
  extensions/google/realtime-voice-consult-serialization.test.ts \
  extensions/google/realtime-voice-provider.test.ts --maxWorkers=1

node scripts/run-vitest.mjs run \
  src/daemon/service-process-membership.test.ts \
  src/gateway/server-methods-list.test.ts \
  src/gateway/talk/handlers/session-send-text.test.ts \
  src/gateway/talk/relay/index.test.ts \
  src/gateway/talk/relay/output-ownership.test.ts \
  src/gateway/talk/relay/tool-call-ledger.test.ts --maxWorkers=1
```

These directions do not activate/update/migrate a running Gateway. No production config, setup wizard or credentials are required for reconstruction.

## Android toolchain pins

| Input | Glass | Phone |
| --- | --- | --- |
| Gradle | 8.13-bin | 8.9-bin |
| Gradle ZIP SHA256 | `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` | `d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab` |
| AGP | 8.13.0 | 8.7.3 |
| Kotlin plugin | 2.0.21 | 2.1.10 |
| Compile API | 36 | 35 |
| Min/target | 19/19 | 24/35 |
| Build Tools | AGP-selected, not independently pinned/qualified here | 35.0.0 |
| Toolchain | JDK21; Java1.8 bytecode | JDK21; source settings determine bytecode |

The manifest pins a public **Eclipse Temurin21.0.8+9 / macOS aarch64** prerequisite: `OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.8_9.tar.gz`, SHA256 `59422c2292ae4e76b87e00d8808dbe49cffa39af731e08bb0292ddb0af4e0261`. Immutable URL/checksum were read, not installed/build-qualified; this is not the unidentified historical local OpenJDK21 archive.

Android public metadata pins in the manifest give exact URLs, revisions, byte lengths and Google's **SHA1** checksums for API35 revision2, API36 revision2, Build Tools35.0.0/macOS and command-line tools19.0/macOS. They are labeled SHA1, not invented SHA256. Archives were not downloaded, installed or independently rehashed. Other platforms require their own pins. Complete historical toolchain/bit-reproducibility remains unverified.

NDK r27d / **27.3.13750724**, Darwin archive `android-ndk-r27d-darwin.zip`, SHA256 `e69092f9d2bfa5d1199039980a14eb91c03cc971ab5c6968fc08a8e6b84e7bb7`. Check its `source.properties` revision. SDK/JDK/NDK/Gradle binaries are not redistributed here.

Set developer-owned paths:

```sh
export JAVA_HOME="/path/to/your/jdk21/Contents/Home"
export ANDROID_HOME="/path/to/your/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_ROOT="/path/to/android-ndk-r27d"
export PATH="$JAVA_HOME/bin:$PATH"
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
export GRADLE_GLASS_BIN="/path/to/gradle-8.13/bin/gradle"
export GRADLE_PHONE_BIN="/path/to/gradle-8.9/bin/gradle"
```

Source archives omit wrapper jars. Bootstrap with the separately obtained/checksummed exact Gradle-bin distribution and invoke its `bin/gradle` instead of `./gradlew`. Reconstructed upstream checkouts retain their public wrapper jars.

### GDK and Glass build

GDK public pin: https://github.com/glasskit/gdk.git at `e4822d65290bb915ee8f0451c1d40c594b41f918`. It contains32 Apache2.0 **compile-only API classes**, not Java implementation source or runtime firmware. No classes/jar are shipped here; the Apache license is preserved. Obtain separately for local compilation:

```sh
cd navigation/osmglass
git init gdk
git -C gdk remote add origin https://github.com/glasskit/gdk.git
git -C gdk fetch --depth=1 origin e4822d65290bb915ee8f0451c1d40c594b41f918
git -C gdk checkout --detach FETCH_HEAD
"$GRADLE_GLASS_BIN" --no-daemon --max-workers=2 \
  :protocol:test :glass-app:rejarGdk :glass-app:assembleRelease
```

`rejarGdk` is the real task; the older README's `buildGdkStub` name is stale. The jar is `compileOnly`; firmware supplies it at runtime. Historical jar SHA256: `59a5e23df38fc3cee10fb92409db72fc22d89567df77bc685fec505deaec1452`; ZIP metadata may differ when re-jarring identical class bytes.

For physical Bluetooth guidance, build the Glass **release** variant as above: it embeds RFCOMM. Its APK is unsigned; sign it with your own key and v1/v2 support before installing. The default debug variant embeds TCP and is for emulator/debug use, so `assembleDebug` is not the physical Glass recipe. A `transportKind` project property does not override later build-type literals. Output: `glass-app/build/outputs/apk/release/`. No install/ADB/device command is run by this build.

### Six frozen phone downloads

These inputs are **not shipped or automatically fetched** by the source-only tool. Exact URLs/sizes are in `phone_downloads` in the manifest.

| Input | Approved SHA256 |
| --- | --- |
| OsmAndCore_android-master-snapshot.aar | `f1f7d8c85bb5698272cd019c4cc894ee11c7b53574556ae98ae1c82686e529e8` |
| OsmAndCore_androidNativeRelease-master-snapshot.aar | `06e6df8c688340f475ca71507d0f4f0faa40dd4804fb65b97a865f8fc12d17bf` |
| MPAndroidChart-custom-snapshot-debug.aar | `43139fb886b31d7c382cc428ea6336c62a75b88497f490b093a66b8adac631eb` |
| World_basemap_mini_2.obf | `058017aa0e25d1421679a6e8907bf73bdc9f9a0812d4c6e44576e28b65c1038c` |
| regions.ocbf | `fe4151a23cd090110d5e726447b0841f64300817e103e4903efe245c678ed4e3` |
| stars.db.gz | `d4a6e14a9fa904bbd8d4aacc2cd19002e1fefead2433509b26e3f565be071d2a` |

AAR URLs are under `https://builder.osmand.net/ivy/net.osmand/`; basemap/boundaries under `/basemap/`; stars under `/basemap/astro/`. To obtain local inputs with approved hashes, from the release repository (optional developer operation, not run here):

```sh
python3 - <<'PY'
import hashlib, json, pathlib, urllib.request
pins = json.loads(pathlib.Path("source-components.json").read_text())["phone_downloads"]
out = pathlib.Path("local-phone-inputs")
out.mkdir(exist_ok=True)
for name, pin in pins.items():
    target = out / name
    if target.exists():
        with target.open("rb") as stream:
            actual = hashlib.sha256(stream.read()).hexdigest()
        if actual != pin["sha256"]:
            raise SystemExit("Existing input mismatch: " + name)
        continue
    partial = target.with_suffix(target.suffix + ".part")
    h = hashlib.sha256()
    with urllib.request.urlopen(pin["url"], timeout=120) as src, partial.open("wb") as dst:
        for block in iter(lambda: src.read(1024*1024), b""):
            h.update(block)
            dst.write(block)
    if h.hexdigest() != pin["sha256"]:
        partial.unlink()
        raise SystemExit("Upstream bytes changed: " + name + "; pins NOT rewritten")
    partial.replace(target)
PY
```

Keep the approved manifest. A changed mutable snapshot URL is fatal: use a rights-cleared hash-addressed mirror or issue a newly qualified release with deliberate new pins. Do not adopt the old helper's “generate new manifest if absent” behavior. `--verify-build-inputs DIR` verifies an already obtained six-file directory, without downloading binaries.

Published `gradle/verification-metadata.xml` SHA256: `e565b29dc88b8564db7bd98d20dcaaf010ff9cb9bc09fcba3316dd283b3f39c9`. It constrains741 components /1,440 SHA256 entries; metadata checking true, signatures false. It is not a source/license inventory or complete graph lock.

### Legacy-native preferred source preparation

`--native` implements this bounded Android recipe, without executing broad upstream `externals/configure.sh`:

1. Fetch protobuf2.5.0 mirrored source archive; verify SHA256 `13bfc5ae543cf3aa180ac2485c0bc89495e3ae711fc6fab4f8ffe90dfb4bb677`; extract into `core-legacy/externals/protobuf/upstream.patched`.
2. Fetch Skia `2432e5a4c45e724b96f1d32d9ac6973c84cc67d5` into `externals/skia/upstream.patched`, not moving `android/11-release`.
3. Fetch exact dependencies into Skia `third_party/externals`:

| Dependency | Commit |
| --- | --- |
| Expat | `e5aa0a2cb0a5f759ef31c0819dc67d9b14246a4a` |
| FreeType | `0a3d2bb99b45b72e1d45185ab054efa993d97210` |
| HarfBuzz | `b37f03f16b39d397a626f097858e9ae550234ca0` |
| libjpeg-turbo | `574f3a772c96dc9db2c98ef24706feb3f6dbda9a` |
| libpng | `386707c6d19b974ca2e3db7f5c61873813c6fe44` |
| Lua | `e354c6355e7f48e087678ec49e340ca0696725b1` |
| zlib | `47af7c547f8551bd25424e56354a2ae1e9062859` |

4. Apply pinned core `externals/{protobuf,skia}/patches/*.patch` in lexicographic filename order. Use `patch --batch --forward -p1`; Skia additionally `-l`. Fail on any missing/failed patch.
5. Verify every preferred input in [native-source-files.json](../patches/manifests/native-source-files.json). Preserve `native/`, `targets/android/`, both prepared source trees, `externals/jpeg/` configuration headers and notices. Its historical aggregate is an **input** digest, not a compiled-library digest. One upstream Skia example wrapper jar is verified, then omitted from publication snapshots.
6. A subsequent developer build from `navigation/osmglass/OsmAnd/OsmAnd` uses the real JNI target:

```sh
env BUILD_ONLY_OLD_LIB=1 ARM64_ONLY=1 \
  "$ANDROID_NDK_ROOT/ndk-build" -j2 \
  APP_MODULES=osmand APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true
```

Output: `OsmAnd/OsmAnd/libs/arm64-v8a/libosmand.so` relative to outer root. Flexible page-size support must be in the first clean link; cached success is not evidence. This legacy JNI build is **different from** OpenGL/Qt/STL/resource payloads from frozen Core AARs; one cannot stand in for the other's missing source.

### Phone build and developer-owned signing

The separate public overlay removes the development-keystore reference and uses AGP's default debug signing, creating/using the developer's own local debug key. No upstream/owner key is supplied. Upstream release-publishing context is not an enabled or qualified community release variant.

Archive users must independently restore required licensed upstream artwork/fonts/media and exact binary dependencies from their public pins under applicable terms, or replace them and qualify a new build. Reconstructed checkouts fetch upstream tracked inputs directly; those assets remain upstream-licensed, not cleared for APK redistribution here.

After native preparation, six-input verification and independent resource/dependency restoration, from NEW `navigation/osmglass/OsmAnd`:

```sh
"$GRADLE_PHONE_BIN" --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs='-Xmx6g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  -PnavigationPinnedInputsDir="/absolute/path/to/local-phone-inputs" \
  :OsmAnd:assembleGlassnavOpenglArm64Debug
```

Pinned-input logic rejects `opengldebug` and only enables `glassnavOpenglArm64Debug`. The older README's `OpengldebugArmonlyDebug` is not this recipe. Do not rename to an untested release variant: release chart uses a different mutable coordinate. Output: `OsmAnd/build/outputs/apk/`. Developer signing is not byte-identical to the retained APK and need not replace its installed app/data.

The host-only added `test/glassnav-host/run.py` requires `JAVA_HOME`, `ANDROID_HOME`, `GLASSNAV_HOST_KOTLIN_LIB_DIR` with compiler/stdlib/coroutine jars. Its stubs/API compilation are not a full phone build/runtime test.

## Why no navigation APK or full-binary reproduction claim

- Frozen OpenGL/Qt AAR hashes identify bytes, not corresponding source revisions/patches/build recipe or Qt relinking compliance. Legacy core is separate.
- Exact custom-chart and patched Trove/ICU source mapping remains incomplete.
- Chicago/Toronto/Brussels fair-use metro icons, other restricted/proprietary/NC-ND artwork/branding, decorative-vector provenance, exact font/sound/data mappings need resolution for unrestricted redistribution.
- Basemap OSM attribution/ODbL and frozen boundary/star data provenance are independent of app GPL. No personal map set is included.
- Common dependencies include Google billing8.0.0, review2.0.1, Play Services Location21.3.0. The older “no Play Services dependency” description is inaccurate.
- Historical JDK archive identity and fully qualified SDK/Glass-toolchain resolution remain unavailable. Public portable prerequisites are pinned but unqualified.

These are source/provenance/packaging limitations, not a verdict that tested navigation is broken. A future APK needs separately qualified inputs, rights and matching public corresponding-source access beside the binary. Do not promise a clean full-navigation binary rebuild from this source preparation.

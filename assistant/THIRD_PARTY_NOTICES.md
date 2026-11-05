# License and dependency provenance

Original Franziska project code is offered under the MIT license in [LICENSE](LICENSE), under owner authorization. That grant does **not** relicense third-party code, licenses or tools as ours. The community Glass launcher uses original project artwork under MIT, as documented below; that does not extend to inherited third-party assets.

## Packaged runtime

**Bouncy Castle 1.86**, coordinates `org.bouncycastle:{bcprov,bcutil,bctls,bcpkix}-jdk15to18:1.86`, retains the upstream MIT-style Bouncy Castle license and copyright **2000–2026 The Legion of the Bouncy Castle Inc.**

The exact pinned upstream text is stored as [bouncycastle-1.86-LICENSE.html](licenses/bouncycastle-1.86-LICENSE.html), retrieved from `bcgit/bc-java` tag `r1rv86`, `LICENSE.html` (SHA-256 `afaf5bd0b0d869dc86b71711e1b775b204948634c9710a35a9a70d1613a2ab14`). The complete permission/warranty text is included in each app's packaged `assets/THIRD_PARTY_NOTICES.txt`.

Modification: class/type names and dotted class/property strings are relocated into `com.franziska.glass.compat.tls15.shaded`. The BC code is not claimed as original project code. Glass selects the upstream AESFastEngine for legacy performance; Pixel keeps default BcTlsCrypto. JarJar already relocates exact known class-name strings, including the tested XMSS allowlist. The helper correction aligns residual embedded names/messages (including composite-provider class filters) with that namespace; it is not a claim of a demonstrated historical assistant runtime failure. Upstream legacy serialization aliases remain aliases, not missing classes to invent.

## Download-only build dependencies

| Coordinates | License | Local license text |
| --- | --- | --- |
| `com.eed3si9n.jarjar:jarjar:1.18.1` | Apache-2.0, as declared in that coordinate's POM | [Apache-2.0](licenses/jarjar-1.18.1-APACHE-2.0.txt) |
| `org.ow2.asm:asm:9.10.1` | BSD-3-Clause | [ASM license](licenses/asm-9.10.1-BSD-3-Clause.txt) |
| `org.ow2.asm:asm-commons:9.10.1` | BSD-3-Clause | same |
| `org.ow2.asm:asm-tree:9.10.1` | BSD-3-Clause | same |

ASM copyright/conditions/disclaimer are reproduced from the pinned 9.10.1 source header (`org/objectweb/asm/ClassReader.java`, public Maven source archive). JarJar's license identity is for the stated Abrams coordinates, not an assumed older unrelated jar.

All eight binary inputs, including runtime jars, are locked in [deps/SHA256SUMS](deps/SHA256SUMS). They are fetched from public Maven Central; no jars or caches are committed. Build tools are not packaged as app runtime libraries. If redistributing build-tool binaries/source beyond this download recipe, review that version's complete upstream attribution/NOTICE obligations.

## External toolchain

Android SDK/platform/build-tools are separately installed under their applicable upstream terms: <https://developer.android.com/studio/terms>. R8 is separately installed with SDK command-line tools, upstream <https://r8.googlesource.com/r8/> (BSD-style project license plus its upstream dependencies). JDK licensing depends on the user's chosen distribution; OpenJDK source is at <https://openjdk.org/>. No SDK, JDK or R8 distribution is copied into this source release.

No proprietary firmware, private credentials, app-signing material or owner-specific service configuration is included.

## Community launcher mark

The inherited checklist PNG is replaced by the project's original geometric F artwork under MIT; see `artwork/README.md`. No external icon attribution is claimed.

# Compatibility boundary

| Layer | Required contract / qualification |
| --- | --- |
| Reference wearable | Google Glass Explorer Edition XE24, physical role `glass-1`; manifest min API 15, target 36 |
| Reference phone | Pixel 7, physical role `panther`; manifest min API 23, target 36 |
| App IDs / versions | Glass `lab.franziska.glass`, 56 / `0.6.19-route`; Pixel `lab.franziska.pixelbridge`, 50 / `0.6.12-route` |
| Link | Direct same-LAN/hotspot canonical RFC1918 IPv4; TLS 1.2 exact-leaf-pin mutual authentication, `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` |
| Desktop build | JDK 21.0.11, Android compile 36/build-tools 36.0.0, command-line tools 20.0 / R8 9.0.32, eight pinned public Maven jars |
| Desktop onboarding | Python 3 stdlib, host OpenSSL, absolute ADB path, default server port 5037, physical USB and debug/run-as |
| Server | Separately supplied repository Gateway patch recipe/config/provider setup, **protocol 4**, device-auth pairing and the relay contract below |

Manifest min API is not a runtime support matrix. No claim is made that every API15/API23 combination, arbitrary phone or Glass firmware works. Launcher/voice trigger support comes from user-supplied compatible firmware; firmware is not redistributed. No proprietary GDK library is required for compilation.

## Gateway contract

This client advertises **min=max protocol 4**. It requires signed device-auth connect with endpoint-bound per-device tokens, returned role/scope enforcement, `chat.send`, `chat.abort`, `chat.history`, and relevant chat events. Talk requires `talk.session.create`, `talk.session.appendAudio`, `talk.session.close`, `talk.event`, realtime **gateway-relay**, brain **agent-consult**, and per-install `agent:main:glass:<conversation UUID>` sessions.

Photo/text handoff additionally negotiates `talk.session.sendText`, canonical history receipts and chat events. Enabled flows also use marks/tool-result/steering RPCs. The separately supplied server recipe must satisfy all enabled paths and provider/relay requirements, not just advertise a base Talk method. Generic stock OpenClaw, a WSS reverse proxy or a reachable URL alone is insufficient evidence of compatibility.

Server activation/pairing/provider credentials are independent user-owned setup. This app build does not modify any Gateway. Consult the repository's [pinned OpenClaw source/patch recipe](../docs/UPSTREAM_BUILD.md#openclaw-source-build-no-activation) and its qualification boundary before activation.

## Security and edition limitations

This is a **developer/sideload community edition**, not a hardened consumer build. Debuggable manifests deliberately support public-only USB onboarding. Trust is exact leaf pinning, not automatic identity rotation; recover partial identity/trust via an explicitly designed procedure. Pixel listens on all interfaces despite its recorded bind field. Gateway WSS routing is separate from the RFC1918 peer link.

Glass retains the frozen `AESFastEngine` optimization, which uses table-based AES: it improves legacy-device record CPU performance but has timing/cache side-channel tradeoffs. Pixel retains default BC crypto. Neither implementation has been silently substituted for packaging.

The two roots preserve their distinct `AssistWire`, `Json`, `Tls` and `PinnedTlsTransport` variants. Broker credentials/requests/enrollment are disabled, and no private endpoint is replaced by another default.

## Evidence for this copy

Both apps have been compiled locally from the clean exported source using freshly fetched, checksum-verified public dependencies and the locked public toolchain. Host tests cover public identity/port/address/gate validation, interrupted/conflicting trust, mocked preflight/readback, disabled broker behavior and actual BC reflective serialization after relocation. APK checks cover manifest/permissions, unsigned status/alignment, packaged notice, relocation and single DEX.

**Not performed:** signing, installation, device/sensor operations, network pairing, Glass optical output, audio delivery, stop/idle acceptance, end-to-end Gateway/provider or fresh-user hardware verification of this community copy. Source build success must not be described as those results. Cross-host or byte-for-byte reproducibility has not been established.

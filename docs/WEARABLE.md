# Franziska / Glass, Pixel and navigation

[Overview](../README.md) · [Architecture](ARCHITECTURE.md) · [OpenClaw engineering](OPENCLAW.md)

## Two applications, one assistant path

The native assistant pair is `lab.franziska.glass` on Google Glass XE24 and `lab.franziska.pixelbridge` on Pixel 7. The reviewed installed-source baseline is Glass56 / Pixel50.

Glass runs the wearable interface. Pixel owns the companion connection and media bridge. The existing OpenClaw agent remains on the Mac. The installed path is **PCM over a protected local link plus Gateway WSS**, not an on-phone language model or a deployed WebRTC experiment.

Three lanes are kept distinct:

| Lane | Transport and purpose |
| --- | --- |
| Assistant | Glass–Pixel Wi-Fi/IP with mutual TLS; Pixel–Gateway authenticated WSS; live speech and main-agent access. |
| Media | A separate authenticated transfer connection; catalogue and original-file synchronization independent of active Talk. |
| Navigation | Separate OsmAnd/OsmGlass applications over bonded Bluetooth Classic RFCOMM; phone-side routing and Glass guidance. |

The original XE24 Bluetooth companion protocol is another compatibility lane. It is not substituted for the assistant's media transport.

## Native application structure

**On Glass:** `GlassActivity` owns the active connection, request and UI lifetime. `GlassTalkAudio`, `NativeAudioAdmission`, `TalkPlayoutPolicy` and `TalkSendPolicy` own recording, playback and backpressure. Separate capture gates coordinate one-shot images and physical camera-button interaction. `TalkTranscriptLog`, `TalkChatLayout` and `TalkStatusBar` present the conversation and observed device state. `PhotoSyncService` and gallery components handle media separately.

**On Pixel:** `LinkService` is the connected-device foreground owner. `GatewayWebSocket` and `GatewayTalkClient` authenticate the direct Gateway connection, create the owned realtime relay and carry its audio/events. Media receiver, album and original-share classes implement the file path. Route preparation and saved-state restoration support the portable setup.

This division is deliberate. The old wearable handles the smallest useful input/output surface; the phone handles connectivity and coordination; the agent host handles durable reasoning and tools.

## Bridging an old Android TLS stack

The Glass connection uses an app-owned socket with a relocated Bouncy Castle lightweight TLS implementation, avoiding conflicts with the old Android platform provider. The protocol uses pinned mutual TLS identities; network addresses locate the peer but do not establish its identity.

The captured raw socket has an independent close path so cancellation does not have to wait for a TLS writer lock or graceful shutdown. Small-record behavior also matters for PCM, so the transport configures the underlying TCP accordingly. This is integration of established TLS components, not a new cryptographic scheme.

Pixel's upstream WSS uses platform TLS and hostname validation. Local pairing and the Gateway credential are distinct pieces of state.

## Audio as a controlled pipeline

The data path is explicit:

**Glass AudioRecord, 16 kHz mono PCM16 → authenticated local frames → Pixel stateful 16-to-24 kHz conversion → Gateway realtime audio.** Output returns as **24 kHz mono PCM16 → Pixel batching → Glass AudioTrack**.

Several mechanisms make that path usable on limited hardware:

- Reusable frame storage and a synchronous borrowed microphone buffer reduce allocation churn.
- Pixel batches short output chunks with a bounded partial-batch flush.
- Glass uses adaptive playout reserve, increasing for short starvation gaps and reducing after stable writes.
- Cumulative flow credits limit how far Pixel can send ahead of completed Glass native writes.
- Clear changes the output revision, so old queued frames cannot become new playback.
- The speaker owner serializes native writes with pause, flush, reset and release.
- Normal closure flushes the final PCM and uses playback progress with a bounded drain deadline.

A provider's acknowledgement, a network frame, an AudioTrack write and audible playback are different points in this pipeline. The implementation models them separately instead of using one “done” flag for the whole path.

## Stop is an ownership transition

Stop revokes the current work and output generation before asynchronous cleanup. Queued audio is cleared, blocked producers are released and the exact owned connection is closed or cancelled.

Native recorder and speaker release remain with their owning execution path; another thread does not free a handle while a read/write may still be using it. If native cleanup cannot establish a reusable state, the admission layer prevents immediate reuse.

The wider remote Stop is latched. A deliberate subsequent Glass interaction can restore readiness without silently restarting capture. The companion's Prepare action preserves that boundary. Service restoration reuses an explicitly enabled state and existing pairing; it is not a background microphone start.

## Conversation on a 640 × 360 display

The Glass UI uses full-width dark conversation bubbles, 28 px text and a separate status strip. Partial/final utterance updates replace the corresponding entry. A reading gesture freezes the visible snapshot so live partials do not move the page underneath the wearer.

The on-device transcript view is bounded; the canonical durable conversation remains with OpenClaw. The HUD distinguishes microphone/session state, link, battery, volume, queued output and session time. These are separate observations rather than one ambiguous animated activity indicator.

## Images and original media

A one-shot AI visual question and original-media synchronization are separate mechanisms.

A visual question binds the capture, image attachment, agent result and speech continuation to the owned request/session. A late image or result cannot restart a revoked Talk.

For original photos and video, the sync service observes the media catalogue while explicitly enabled. The transfer protocol uses catalogue digests, ordered chunks, declared sizes, SHA-256 verification, commit and acknowledgements. Pixel receives into a temporary file, verifies it, rechecks the current receiver generation and then publishes it into the album. Idempotent naming and receipts make lost-acknowledgement retries safe to repeat without duplicating the visible import.

Ordinary synchronization copies originals; deletion is a separate, explicit protocol. Selected-original handoff uses Android's URI-grant sharing mechanism. The gallery and transfer process do not depend on keeping a voice conversation open.

## Portable connectivity

The companion supports Glass through Pixel's Wi-Fi hotspot while Pixel reaches the Mac Gateway over mobile internet. Endpoint discovery can use the current DHCP gateway, but the unchanged peer certificate pin still decides whether the connection is trusted.

Saved-network recovery selects an already approved network after pinned peer authentication and uses bounded scan/selection work. The phone's listeners follow interface changes. Preparation checks local readiness separately from upstream Gateway authentication. These are application-level checks, not an internet-ping substitute.

The native system hotspot control remains on Android's side. The Mac remains reachable elsewhere; it is not carried with the wearable.

## Navigation: working, tested, independent

Navigation has been tested in use. It uses **OsmAnd on Pixel** for downloaded maps, search, route calculation, position, rerouting, ETA and navigation voice. A GlassNav plugin projects guidance to **OsmGlass on Glass** over bonded Bluetooth RFCOMM. Glass is a GDK LiveCard display, not a second routing engine.

The project retains the upstream engine and adds specific integration work:

1. **Position freshness:** real fix age and queue residence determine whether a marker and maneuver remain current. Keepalive traffic does not refresh an old location.
2. **Route/render identity:** current route, connection and projection revision own each frame. Late decode or render completion cannot publish into a successor route.
3. **Bounded projection:** phone-side lookahead, Glass cache and image dimensions are capped; the whole trip is not pre-rendered into memory.
4. **Long-route values:** distances and ETA avoid the earlier 16-bit truncation, while arrival follows the actual OsmAnd terminal state.
5. **Stop across asynchronous work:** display Stop survives route ticks, reroutes and late service/transport callbacks. Stopping the Glass display and stopping the phone's route are explicitly different operations.
6. **Single navigation voice owner:** phone-side OsmAnd owns navigation speech; Glass display configuration avoids a second competing speaker.
7. **Java/native compatibility:** the local FastRoutingState repair aligns the Java and native state layouts instead of masking the resulting routing failure.

Compatible official OsmAnd improvements were selectively integrated while preserving these repairs. Their upstream algorithmic authorship remains with OsmAnd; selecting, adapting and retaining the Glass integration is our contribution.

## Recorded interface captures

The following original captures are retained as dated interface examples:

- [Navigation frame: left turn](../assets/navigation-left.png)
- [Navigation frame: subsequent right turn](../assets/navigation-right.png)

They are real Glass framebuffer captures from a native route simulation on 30 October 2026. The route changes were delivered to the device over Bluetooth, including while Pixel was locked. That describes these particular images; it does not replace the subsequent real-use acceptance of navigation.

Navigation UI: OsmAnd / OsmGlass. Map: [© OpenStreetMap contributors](https://www.openstreetmap.org/copyright). See [credits](CREDITS.md).

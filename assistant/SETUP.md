# Fresh user-owned setup

This is an owner-run developer/sideload workflow, not a step executed by a source build. No private identity, owner endpoint, token, saved Wi-Fi network or device serial is included.

## Prerequisites and install

Use reference Glass **XE24** (`ro.product.device=glass-1`) and Pixel **7** (`panther`), owner-enabled USB debugging, a trusted desktop with Python 3, OpenSSL and Android platform-tools, and a direct user-owned LAN/Pixel hotspot. Read [COMPATIBILITY](COMPATIBILITY.md) first and supply the separately documented protocol-4 Gateway patch/config/provider requirements from this repository's [pinned server source/patch recipe](../docs/UPSTREAM_BUILD.md#openclaw-source-build-no-activation).

Build and sign as in [BUILDING](BUILDING.md), or independently verify a publisher's APK hash and signing certificate. Install the chosen signed APKs on **explicitly selected USB devices**, using your own absolute ADB path and server port. For example (all variables must be your own):

```sh
"$ADB_BIN" -P "$ADB_PORT" -s "$GLASS_USB_SERIAL" install "$GLASS_SIGNED_APK"
"$ADB_BIN" -P "$ADB_PORT" -s "$PIXEL_USB_SERIAL" install "$PIXEL_SIGNED_APK"
```

Install and signing are separate from bootstrap. Do not point these commands at an existing different-signer installation without an explicit migration plan.

## Generate identities on devices

Open the Glass assistant normally, without invoking a voice/capture action. In Pixel tap **«Подготовить и проверить» (Prepare and check)**. Absent RSA identities are generated privately on each device; this may take time on Glass. Do not repeatedly interrupt generation. Existing or partial identity state is not silently overwritten/rotated. Wait for public-bootstrap-required/identity-ready status.

Connect both devices to your chosen LAN, or connect Glass to your own Pixel hotspot through system/native companion settings. No network/password record is supplied. On older API levels, scan-based recovery may be unavailable; manual Wi-Fi setup remains necessary.

## Public-only exact-pin trust

Choose the Pixel's **current canonical RFC1918 IPv4** on that direct network. Both endpoint and bind values below must equal it. Hostnames, IPv6, VPN 100.64/10 addresses and TCP/IP ADB serials are not accepted for this bootstrap.

Run with your own absolute ADB path and two **distinct physical USB serials**; these uppercase values are intentionally invalid placeholders:

```sh
export GLASSLAB_ENABLE_PUBLIC_BOOTSTRAP=OWNER_VERIFIED_USB_RUN_AS
export GLASSLAB_ADB_BIN=/absolute/path/to/adb
export GLASSLAB_ADB_SERVER_PORT=5037
export GLASSLAB_GLASS_SERIAL=YOUR_GLASS_USB_SERIAL
export GLASSLAB_PIXEL_SERIAL=YOUR_PIXEL7_USB_SERIAL
export GLASSLAB_GLASS_ENDPOINT=YOUR_PIXEL_RFC1918_IPV4
export GLASSLAB_PIXEL_BIND=YOUR_PIXEL_RFC1918_IPV4
export GLASSLAB_LINK_PORT=38443
sh scripts/public_bootstrap.sh
```

The community default ADB server port is **5037**; choose another explicitly only if your desktop requires it. Link port must be canonical 1..65535. The explicit gate must match exactly.

The helper checks device roles, bounded canonical public exports, certificate/pin hashes and X509 DER via host OpenSSL; it preflights both identities and previous trust before writing. Only public exact-pin `trust.json` crosses USB. Private key/password/token bytes are never bootstrap inputs, outputs or command arguments; private identity files are only checked for existence/non-symlink status. No host-created PKCS12/private-stream provisioning is supported.

It refuses differing existing trust. Writes are sequential, **not a cross-device transaction**: after interruption, rerun the **same** values to complete idempotently; do not replace pins or clear identities casually. Rotation/revoke is outside initial setup. Successful output explicitly says `network_pair=NOT_TESTED`; trust readback is not a working network/Talk test.

Both APKs intentionally have `debuggable=true`, required for this USB `run-as` flow. Trusted USB/developer access is therefore within the edition's trust boundary. A non-debuggable consumer edition needs different onboarding and new qualification, not just a manifest toggle.

## Configure the separate Gateway identity

Use the [fresh Gateway setup guide](../docs/GATEWAY_SETUP.md) for the compatible main-agent and realtime-provider configuration.

In Pixel open **«Доступ к OpenClaw» (OpenClaw access)**, save your own valid-CA `wss://` URL (no URL userinfo, query or fragment), and use its masked transient bootstrap field only when your Gateway requires it. Approve the exact displayed per-device request in your trusted Gateway UI, then retry explicit pairing if required.

The reference client targets `agent:main:glass:<conversation UUID>`; configure the Gateway's main agent/workspace accordingly. An additional text agent named `franziska` is not selected automatically.

Pixel generates its independent Ed25519 Gateway identity and per-install conversation UUID; credentials are endpoint-bound and Keystore protected. Basic requested scopes are `operator.read,operator.write`. Optional admin upgrade requires separate explicit approval; it is not a blanket assistant setup requirement. No baked token or WSS endpoint is supplied. MyGlass broker enrollment/request processing remains disabled; it is not needed for Glass → Pixel → Gateway.

## Deliberate acceptance checks

Return to Pixel **«Подготовить и проверить» (Prepare and check)**, reopen Glass assistant and confirm authenticated route status. After deliberately granting only the permissions needed for your chosen action, test a short question, selected photo, Talk and Stop as separate user actions. Verify actual answer/audio completion and idle sensors after Stop. No blanket media/camera/microphone/call/Bluetooth grants are needed for initial public trust bootstrap.

These steps describe user acceptance work, **not tests already performed on this community copy**. Notification forwarding is not part of this release. Optional media sync/companion functions should remain off until the user chooses them. The Pixel listener currently binds all interfaces (`0.0.0.0`), not only the recorded bind field; exact-pin mutual TLS is still required. Glass authenticates discovered route candidates against the unchanged pin.

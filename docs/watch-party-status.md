# Watch Party — implementation status

This file records what is **built, verified, and still unproven** in the
serverless Watch Party feature. It exists so nobody has to guess.

Last updated at build `f0fa6c4`.

## Verified by automated tests

191 unit tests run on the JVM with no device involved. Everything in this list is
pure Kotlin with no Android or WebRTC dependency, which is why it can be tested
this way.

| Area | What is covered |
| --- | --- |
| `PairingPayload` | round-trip encode/decode, magic + version prefix, URL-safe body, foreign QR rejection, corrupt bodies, oversize rejection, 5-minute expiry boundary, clock-skew rejection |
| `SdpTemplate` | rebuilds offer/answer SDP with correct `a=setup:`, exactly one `application` m-line and no media sections ever, round-trips through `parse`, reads stock Chromium SDP, tolerates relay/prflx candidates, IPv6 survives |
| `ChunkedPayload` | splits at capacity, reassembles in any scan order, balanced chunks, refuses to mix two invitations, a chunk can never be parsed as a payload and vice versa |
| `BoundedOutbox` | drop-oldest at capacity, drop counting, stall detection, oversized frame rejection |
| `PairingSession` | full handshake state machine, expiry cannot be extended, expiry governs only unanswered invitations so a slow guest is not cut off mid-ICE, wrong room/role rejected, second answer cannot be applied, distinct session id per attempt |
| `PeerHub` | host fan-out, sender never receives its own frame, three-way chat through the host, bad guest isolated without blocking others, reconnect replaces a stale link |
| `WatchPartyLayout` | aspect preserved at 16:9 / 4:3 / 9:16, regions disjoint, two-thirds video cap, IME shrinks chat not the input, degenerate windows |
| pre-existing | episode matching, drift correction, ready check, sync-now, reactions, room codes, invite parsing |

Run them:

```sh
# See /tmp/runner in the session for the harness; the repository tests are the
# source of truth and run under Gradle in CI.
```

## Crash fixed in this branch

The first installed build crashed the moment "Invite a guest" was tapped, with
no Java stack. Cause: both shipped variants are minified, `org.webrtc` resolves
its JNI entry points by name at runtime (`PeerConnection.java` alone carries 73
`@CalledByNative` methods), and the SDK ships no consumer ProGuard rules — so
R8 renamed the classes the native layer looks up. `app/proguard-rules.pro` now
keeps `org.webrtc.**`, its native and annotated members, the ZXing and CameraX
packages.

CI no longer takes that on trust. The build asserts that the class descriptors
`Lorg/webrtc/PeerConnection;` and nine other JNI-visible types still appear
verbatim in the packaged dex, and self-checks the extractor against
`Landroid/app/Activity;` so a broken check cannot masquerade as a stripped
class. The gate passes on the artifact this branch ships.

## Build targets

`debug-v8a.yml` takes both `variant` (`r8Debug` / `sideBySide`) and `abi`
(`arm64-v8a` / `armeabi-v7a`). Artifacts are named
`Hikaru-v<version>-<v8a|v7a>-<r8-debug|sideby>`. The 32-bit build depends on
mpv, ffmpeg-kit and WebRTC all publishing `armeabi-v7a`, which they do; a CI step
asserts the packaged `.so` files are the requested ABI before upload.

## Verified by CI compilation only

Compiled by GitHub Actions, never executed on a device:

- `P2PSession` — offer/answer creation, trickle ICE, DTLS role handling, data
  channel wiring against `org.webrtc` (`io.github.webrtc-sdk:android:150.7871.04`)
- `QrCodec`, `QrScanner` — ZXing rendering/decoding and CameraX capture
- `P2PHostCoordinator`, `P2PGuestCoordinator`, `P2PWatchPartySession`
- `WatchPartyRoomScope`, `AnimeWatchPartyDialog`, `WatchPartyScannerDialog`,
  `WatchPartyPanel`
- The mpv surface resize in `PlayerActivity`

These compile cleanly but have **never run**. Signalling code that compiles is
not signalling code that connects.

## Not proven at all

- **Two devices have never paired.** No QR has been scanned, no data channel has
  opened, no ICE candidate has been exchanged between real phones. The crash on
  tapping "Invite a guest" is diagnosed and the JNI-preservation fix is proven
  in the packaged dex, but whether the QR then scans and a data channel opens
  still needs two phones.
- **The letterbox fix has never been seen.** The arithmetic is tested; whether
  mpv behaves as expected when its surface is resized mid-playback is not.
- **The keyboard behaviour is untested on a device.** `imePadding` is correct in
  Compose, but whether the activity-level insets report the IME height correctly
  on every OEM skin is unknown.
- **No TURN means some networks will fail.** Two devices on different mobile
  carriers, or one behind symmetric NAT, will not connect. The app reports this
  plainly rather than pretending; it does not work around it.

## Deliberate omissions

- No relay fallback in serverless mode. `SignallingMode.RELAY` is a separate,
  opt-in path that needs a self-hosted server.
- No video or audio over the peer connection. Guests stream from their own
  extension, and the signalling layer cannot create a media section.
- No host migration. If the host leaves, the room ends.

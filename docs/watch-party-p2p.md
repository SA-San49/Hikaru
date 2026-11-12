# Reference survey and architecture comparison

Companion to `docs/watch-party.md`. Covers the required study of existing
serverless WebRTC signalling projects before implementing P2P Watch Party.

## 1. Reference repositories

| Repository | Lang | Last push | License | Verdict |
| --- | --- | --- | --- | --- |
| [magarcia/qwbp](https://github.com/magarcia/qwbp) | TypeScript | 2026-01 | **MIT** (spec text CC BY 4.0) | **Primary reference.** Maintained, specified, tested. |
| [NiKrause/libp2p-webrtc-qr](https://github.com/NiKrause/libp2p-webrtc-qr) | JavaScript | 2026-11 | NOASSERTION ("other") | Ideas only — licence is not OSI-clear, so **no code is copied**. |
| [SpacebitLabs/serverless-webrtc-android](https://github.com/SpacebitLabs/serverless-webrtc-android) | Kotlin | 2018 | Unlicense | Android shape reference only; 8 years stale, pre-AndroidX. |
| [dcerisano/serverless-webrtc-qrcode](https://github.com/dcerisano/serverless-webrtc-qrcode) | JavaScript | 2014 | NOASSERTION ("other") | Concept only. Browser-only, abandoned. |

Attribution is recorded in `docs/watch-party.md` §11.

## 2. What was actually adopted

Nothing is vendored or copy-pasted. The useful ideas, and where each landed:

### Compress the signalling payload (QWBP)

The single most important lesson. A raw SDP is 2.5–4 KB — far too much for one
readable QR code, and miserable to copy by hand. QWBP gets 55–100 byte payloads
by **not sending SDP fields that are constant for a DataChannel-only session**
(and by binding ICE credentials to a derived key).

Hikaru adopts the *principle*, not the byte layout:

* send only `ufrag`, `pwd`, host/srflx candidates and a fingerprint;
* rebuild the rest of the SDP locally from a fixed template;
* drop SDP lines that are irrelevant to a DataChannel-only connection.

Result: an invitation is a few hundred bytes, which fits a single QR code and a
paste-able string, versus multi-thousand-byte raw SDP.

### Versioned, magic-prefixed envelope (QWBP)

Leading magic byte + version byte so a decoder can reject a foreign QR code
(menu, URL, ticket) before parsing. Adopted.

### Session fingerprint for binding (QWBP, libp2p-webrtc-qr)

Bind the pairing payload to the DTLS certificate fingerprint so a payload cannot
be swapped for another peer's mid-exchange. Adopted: the fingerprint travels in
the payload and is verified against the accepted `DTLS` role, and a session id +
expiry prevents replay.

### Bounded session lifetime (all four)

Invitations expire. Hikaru uses a monotonic 5-minute deadline that scanning
cannot extend, re-armed per guest.

### Explicit failure, never silent relay fallback (spec requirement, libp2p-webrtc-qr)

libp2p-webrtc-qr relies on libp2p relay infrastructure. Hikaru **rejects** that
model: no relay, no TURN, no hidden fallback — a failed direct connection is
reported as a failure.

### Rejected

* QWBP's key-derivation scheme for ICE credentials (it stops the standard
  `ice-ufrag`/`ice-pwd` exchange working against ordinary peers, and would make
  Hikaru's payloads incompatible with any future non-Hikaru peer).
* libp2p stack (libp2p-webrtc-qr): an enormous dependency for a 2–4 person room.
* Any browser-only API.

## 3. Android WebRTC stack

| Candidate | Choice |
| --- | --- |
| `io.github.webrtc-sdk:android` (WebRTC m150, Maven Central) | **Chosen.** Actively released (2026-10), standard `org.webrtc` API, no extra licence constraints beyond WebRTC's BSD-3. |
| `org.webrtc:google-webrtc` | Unmaintained since 2021. |
| `androidx.webrtc` (Jetpack) | Pre-alpha, camera1-era API. |

CPU architectures: the release APK is ABI-split; `libwebrtc` ships per-ABI, and
the V8a build already filters `abiList=arm64-v8a`, so the ~10 MB native library
only affects that split.

## 4. QR acquisition

| Candidate | Choice |
| --- | --- |
| ZXing core (`com.google.zxing:core:3.5.4`, Apache-2.0) | **Chosen** for both generate and decode. Pure Java, works on every minSdk-26 device, no Play Services requirement. |
| ML Kit Barcode | Better low-light, but adds a Play-Services dependency and does not work on de-Googled installs. |
| `zxing-android-embedded` | Unmaintained since 2021 and forces its own Activity/theme. |

Camera: **CameraX** (`androidx.camera:camera-camera2/lifecycle/view`) with
`ImageAnalysis` frames handed to ZXing — no Play Services, respects the existing
insets/lifecycle architecture, and the analyser is released when the scanner
closes.

## 5. Architecture

```
            ┌──────────────────────────────────────────┐
            │         WatchPartyEngine (unchanged)      │
            │  playback authority, ready check, sync,   │
            │  chat, reactions, episode matching        │
            └───────────────────┬──────────────────────┘
                                │ RoomTransport  (existing interface)
            ┌───────────────────┴──────────────────────┐
            │      P2PHostTransport / P2PGuestTransport │
            └───────────────────┬──────────────────────┘
                                │
                     ┌──────────┴──────────┐
                     │   P2PSession        │  WebRTC PeerConnection
                     │  (one per guest)    │  + DataChannel (DTLS)
                     └──────────┬──────────┘
                                │
        PairingOffer ──compressed──► QR / paste ──► PeerPairingPayload
```

* **Host**: one `PeerConnection` **per guest**, each with its own offer and its
  own invitation QR. Offers are never reused across guests.
* **Guest**: creates the answer from the scanned offer, then displays an answer
  QR that the host scans to complete the handshake.
* **Fan-out**: a guest's outbound frame goes to the host; the host re-broadcasts
  it to every other guest, so existing engine semantics (host is the hub) hold.
* **No video** ever crosses the DataChannel: only the existing JSON room events.

### Why manual pairing is the default

No signalling server exists, so SDP has to travel out-of-band. Scanning two QR
codes (offer, answer) is a few seconds of work and needs zero infrastructure.

### LAN mode

On the same Wi-Fi, host candidates are usually sufficient and no internet is
needed. Toggling **LAN only** clears the STUN server list, so ICE never queries
the internet; mDNS (`webrtc.eMDSource`) keeps host names out of the payload.
If a hotspot blocks peer-to-peer traffic the connection fails loudly.

## 6. Room identity: anime-level, not episode-level

The room id becomes the **anime's stable identity**, not a random per-episode
code:

```
roomId = "<sourceId>:<animeUrlHash>"      // stable across episodes
```

Participants move between episodes freely; `EPISODE_CHANGE` travels over the
existing connection and the room, chat, reactions and peer connections survive.
Episode matching keeps its existing EXACT / PROBABLE / UNRESOLVED / MISMATCH
behaviour, so a wrong episode is never force-played.

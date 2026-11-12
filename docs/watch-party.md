# Watch Party

Watch an anime episode together with friends, long-distance, without moving any
video through Hikaru's servers or through anyone else's.

**Status:** optional, off by default. When the preference is off (or the player
entry point has never been used) no socket is opened, no coroutine is started and
normal playback is byte-for-byte the same as before the feature existed.

---

## 1. The idea in one paragraph

Everyone in the room watches **their own** stream, resolved by **their own**
extension. Hikaru only shares *playback metadata* — "playing", "paused", "we are
at 12:31", "episode 7", "someone said hi" — so a room works even when every
participant uses a different provider, a different quality and different
subtitles. Nothing about your source ever leaves your device.

```
        ┌──────────┐   tiny JSON control frames    ┌──────────┐
        │  Hikaru  │◄──────────  relay  ──────────►│  Hikaru  │
        │  host    │      (no media, no history)   │  guest   │
        └────┬─────┘                               └────┬─────┘
             │ mpv                                      │ mpv
        provider A, 1080p                         provider B, 720p
        own extension, own subtitles              own extension, own subtitles
```

---

## 2. What is shared and what never is

| Shared (control plane)                | Never shared                                |
| ------------------------------------- | ------------------------------------------- |
| play / pause / seek / position        | stream URLs                                 |
| episode number, title, season         | cookies, auth headers, tokens               |
| AniList / MAL ids for episode matching| extension credentials, provider session data |
| display name, readiness, chat, emoji  | private account information                  |

Room frames are capped at 64 KiB, validated by type and field, sequence-numbered
per sender, and sanitised (chat ≤ 500 characters, names ≤ 24 characters with
control characters stripped). Sender ids are random per install.

---

## 3. Architecture

```
ui/player/watchparty/
├── WatchPartyModels.kt        wire + UI model types (kotlinx.serialization)
├── RoomCode.kt                8-char room codes, invite links
├── WatchPartyInviteParser.kt   parses links / pasted text into a code
├── WatchPartyInvites.kt        process-wide hand-off for deep links
├── EpisodeMatcher.kt           decides whether two participants are on the same episode
├── EventRouter.kt              the only place inbound frames are trusted
├── RoomMessageCodec.kt         JSON encode/decode + sanitising + size caps
├── PlaybackSyncEngine.kt       drift thresholds, snap/seek decisions, speed nudges
├── ReadyCheckController.kt     shared 5 s countdown with a single deadline + id
├── SyncNowController.kt        explicit recovery state machine
├── ReactionAggregator.kt       bursts of taps → one frame
├── RoomTransport.kt            transport interface
├── RelayRoomTransport.kt       WebSocket transport to the relay (default)
├── WatchPartyEngine.kt         session orchestration, host authority, tickers
├── WatchPartyController.kt     ViewModel bridge: flows, player hooks, media key
├── WatchPartyPreferences.kt    DataStore-backed preferences
└── WatchPartyScreen.kt         the player sheet (lobby, participants, chat, reactions)
```

The layer boundary is deliberate: the engine talks to a `WatchPartyPlayerBridge`
(implemented by the controller on top of `PlayerViewModel` / mpv) and to a
`RoomTransport`. Neither knows about Compose, and the pure-logic pieces
(`EventRouter`, `PlaybackSyncEngine`, `ReadyCheckController`,
`SyncNowController`, `ReactionAggregator`, `RoomCode`, `EpisodeMatcher`,
`RoomMessageCodec`) are unit-tested without Android.

### Host authority

The host's playback is the reference. Guests **never** move the room: an inbound
`PLAY`/`PAUSE`/`SEEK`/`POSITION` from a non-host is dropped by the event router's
role check. A guest that falls behind either waits for the next host heartbeat or
asks for an explicit `Sync Now`.

The host is whoever created the room. A guest that joined after the host's
announcement learns the host from any presence/join frame that advertises the
host role. Host migration is **not** implemented (see § 9).

### Event set (protocol v1)

`PLAY`, `PAUSE`, `SEEK`, `POSITION`, `READY`, `NOT_READY`, `COUNTDOWN_START`,
`COUNTDOWN_CANCEL`, `SYNC_REQUEST`, `SYNC_BEGIN`, `SYNC_PREPARED`, `SYNC_FAILED`,
`SYNC_RESUME`, `CHAT_MESSAGE`, `EMOJI_REACTION`, `PARTICIPANT_JOIN`,
`PARTICIPANT_LEAVE`, plus internal `PRESENCE`, `EPISODE_CHANGE`, `PING`, `PONG`.

Every frame carries `roomId`, `senderId`, `seq`, `sentAtMs` and
`protocolVersion`. Frames with a newer protocol version, a different room, a
blank sender, a stale/duplicate `seq`, a duplicate message id or an
out-of-alphabet emoji are dropped, not "best effort" applied.

### Playback sync

| Concern | Behaviour |
| --- | --- |
| Heartbeat | host publishes `POSITION` every 1.5 s while playing; presence every 4 s |
| Ignore threshold | drift ≤ 900 ms is ignored (network jitter is not a reason to seek) |
| Seek threshold | drift > 4 s triggers a seek |
| Gentle correction | 900 ms – 4 s is nudged with a temporary ±0.05× speed change for ≤ 4 s |
| Echo prevention | `isApplyingRemote` flag + suppressing the player hooks for the duration of an applied operation |
| Seek loop guard | host ignores `PLAY`/`PAUSE`/`SEEK` echoes for a short window after applying and never re-broadcasts a remote-applied operation |
| Clamping | remote targets are clamped inside the local duration; a target past the end is refused |
| Episode changes | `EPISODE_CHANGE` re-arms readiness; the episode is *matched*, never auto-played |

No MPV re-initialisation is ever performed: the engine only calls
`seek`/`pause`/`play`/`speed` on the already-running player.

### Ready check

The host owns exactly one countdown. `READY` from every connected participant
(manual ready **and** a resolved episode **and** not buffering) starts a 5 s
countdown described by a single `(countdownId, deadlineMs)` pair broadcast in
`COUNTDOWN_START`. Guests render the countdown from the shared deadline, so
clock skew does not shift the start. `NOT_READY`, a leave, a disconnect or an
explicit `COUNTDOWN_CANCEL` from *anyone* cancels it; stale or duplicate
countdown ids are rejected; a restart within 3 s is suppressed.

### Sync Now

Any participant can press Sync Now. A guest sends `SYNC_REQUEST`; the host
captures **its own** position (never the slowest peer's), assigns an operation
id, sends `SYNC_BEGIN`, and every participant pauses → seeks → prepares →
reports `SYNC_PREPARED`/`SYNC_FAILED`. The host sends `SYNC_RESUME` once every
reachable participant is prepared, or after the response timeout (6 s) so one
unreachable peer can never wedge the room; a failed peer is skipped, not fatal.
Concurrent requests merge into the running operation, a 4 s cooldown prevents
request storms, and the operation self-aborts after 45 s.

### Chat and reactions

Chat is room-scoped and in-memory (200 messages), with sender names, timestamps,
own-message styling and dedupe by message id. Reactions are `❤️ 😂 😭 🔥 😱 👏 💀`,
aggregated into 450 ms bursts before sending, echoed locally so the sender sees
their own tap, capped to the last 80 locally, and optional as floating glyphs
over the player.

### Lifecycle and performance

* No background service, no foreground service, no alarm, no wake lock.
* `WatchPartyController` is created lazily on the ViewModel's first use and the
  engine (and therefore the socket) only when a room is created or joined.
* Everything runs on the ViewModel's `viewModelScope` plus a dedicated
  IO scope; nothing blocks the main thread; all sends are queued.
* The transport keeps at most 64 outbound frames, drops the oldest when backed
  up (newest state wins), and reconnects with exponential backoff (0.7 s → 15 s).
* `release()` cancels collectors, closes the socket, clears queues and resets
  state; it is safe to call when nothing was ever created.
* State lives in the ViewModel, so a rotation does not drop the room.

---

## 4. Networking: what actually needs to exist

Watch Party needs a **signalling hop**, not a media server. Two options were
considered:

1. **WebRTC DataChannels (P2P-first).** Best privacy and lowest latency for a
   full mesh, but needs STUN for NAT traversal and a TURN relay for symmetric
   NATs / mobile carriers — and TURN carries the traffic, so it is *not* free at
   scale. It also needs a signalling channel to exchange SDP/ICE anyway, and a
   full mesh costs `n·(n−1)/2` connections.
2. **A tiny WebSocket relay (implemented, default).** Works across symmetric NAT
   and carrier-grade NAT with no STUN/TURN at all. Frames are a few hundred
   bytes; a full 12-person room stays well under ~3 KB/s per client. The relay
   is stateless, never parses the payload and never stores anything.

The chosen default is (2), behind the `RoomTransport` interface so a WebRTC
DataChannel (with the relay doubling as signalling and TURN-free fallback) can
be added later without touching the engine. **No permanently running Hikaru
backend is required** — any operator can run `relay/watch-party-relay.js`, and
users can point the app at their own relay URL in settings.

### Reference relay

`relay/watch-party-relay.js` (Node ≥ 18, one dependency: `ws`):

```bash
cd relay && npm install && npm start        # listens on :8787
# Hikaru → Settings → Advanced → Player → Watch party relay URL
# emulator: ws://10.0.2.2:8787/ws      device on LAN: ws://<pc-ip>:8787/ws
```

Guardrails: 64 KiB frames, 12 participants per room, one socket per participant
id (a reconnect replaces the stale socket), 120 frames / 10 s per socket, room
code and participant id validated against `^[A-Z0-9]{4,12}$` /
`^[A-Za-z0-9_-]{6,64}$`, and `GET /healthz` for uptime checks.

### Free-tier options and limits

| Host | WebSockets | Free tier | Caveats |
| --- | --- | --- | --- |
| Fly.io | yes | shared-cpu-1x 256 MB machines inside the free allowance | needs a card on file for some accounts |
| Render | yes | free web service | spins down when idle → ~30 s cold start |
| Deno Deploy | yes | free tier | V8 isolates, needs a small adapter |
| Cloudflare Workers | via Durable Objects | Workers free tier; Durable Objects need a paid plan | DO is the natural room primitive |
| Railway / Koyeb / Vercel | partial | trial/free tiers vary | Vercel functions do not hold long-lived sockets |

Costs scale with **control traffic only** — a room of four at 1–3 KB/s is a few
hundred MB per month, far inside any free tier. TURN, had we chosen WebRTC,
would be the only real cost driver.

### Discord

Optional and never required. Only an invite link pasted into chat or Discord
Rich Presence would be appropriate. **Self-bots are not supported** and no
automation of a user account is performed anywhere in this feature.

---

## 5. Episode matching

Each participant describes their own episode as `EpisodeIdentity`:
anime title, media key (`anilist:<id>` or `mal:<id>`), episode number, episode
title, season number and duration. The host publishes its identity; guests
compare:

| Result | Rule | Room behaviour |
| --- | --- | --- |
| `EXACT` | same media key **and** same episode number (within 0.01) | sync normally |
| `PROBABLE` | same normalised anime title and same episode number, or same media key with a numeric near-match | sync, with a subtle warning |
| `UNRESOLVED` | one side has no metadata | sync, with a clear "cannot verify the episode" warning |
| `MISMATCH` | different episode numbers | **block the countdown** and show which episode each side is on |

The media key comes from the library entry's AniList track, falling back to
MAL; it is resolved lazily in the background and never blocks the UI. A manual
match is always possible by simply navigating to the right episode, and the
warning explains exactly what to fix.

---

## 6. UI

* **Player overlay** — a participant-count button with connection status;
  the Watch Party sheet opens from the player controls (and auto-opens when an
  invite link launched the player).
* **Room panel** (`WatchPartySheet`) — lobby (create / join by code or pasted
  link, with the invite text and share action), host badge, per-participant
  readiness, connection pill, countdown with a cancel action, Sync Now, chat
  with own-message styling, and the emoji picker.
* **Floating reactions** — optional overlay in the top-end corner of the
  player, on by default only while a room is active.
* Portrait keeps the collapsible sheet; landscape uses the same sheet with the
  player still visible. All colours, spacing and shapes come from
  `MaterialTheme` / `PlayerSheet`, so it matches the rest of Hikaru.

Strings live in `i18n/.../strings.xml` (`player_button_watch_party`,
`pref_watch_party_*`).

---

## 7. Security and reliability

* Room access: the room code is the only credential — 8 chars from a 32-symbol
  alphabet (40 bits), no ambiguous characters, valid for as long as the room
  lives (rooms are dropped when the last member leaves).
* Identity: a random per-install participant id; display names are sanitised and
  bounded; a sender can only ever speak for itself.
* Validation: every inbound frame passes `RoomCode` comparison, protocol
  version, sender, `seq` monotonicity, duplicate-id checks and per-type payload
  validation before the engine sees it.
* Safe parsing: `RoomMessageCodec.decode` returns `null` on anything malformed,
  oversized or type-unknown — it never throws into the engine.
* Reconnect: exponential backoff, presence re-announcement, and a fresh join
  reopens the sequence window for that participant exactly once.
* Timeouts everywhere: connection (20 s), countdown cancel on disconnect, sync
  response (6 s), sync operation (45 s), buffer wait (12 s).
* Not sent, ever: stream URLs, cookies, headers, tokens, extension credentials,
  account data. The codec has no fields for them.

---

## 8. Settings

Settings → Advanced → Player:

| Preference | Default | Meaning |
| --- | --- | --- |
| Enable Watch Party | on | master switch; off means zero networking |
| Display name | device name | shown to the room (≤ 24 chars) |
| Floating reactions | on | show emoji over the player |
| Relay URL | `wss://hikaru-watchparty.fly.dev/ws` | point at your own relay |

The default relay URL is a **placeholder**: it documents the intended shape of
the service, not a promise that a public instance exists. Point the app at your
own relay (or self-host) before relying on it.

---

## 9. What is implemented vs tested vs unfinished

### Implemented

Room create/join/leave via code or link, invite link handling
(`hikaru://watchparty/<CODE>`, `https://hikaru.app/watchparty/<CODE>`),
participants + host badge + connection status, reconnection, sequence numbers and
duplicate protection, host-authoritative playback sync with drift recovery,
episode matching with manual fallback and warnings, ready check with a single
shared 5 s countdown, Sync Now, group chat, emoji reactions (incl. floating),
player integration, settings, the reference relay, and cleanup/lifecycle
handling.

### Tested

* Unit tests (`app/src/test/.../watchparty/`): `RoomCode`, `WatchPartyInviteParser`,
  `EpisodeMatcher`, `EventRouter`, `PlaybackSyncEngine`, `ReadyCheckController`,
  `SyncNowController`, `ReactionAggregator`, `RoomMessageCodec`.
* An end-to-end engine simulation (two peers, 16 scenarios: propagation, guest
  hijack rejection, chat dedupe, reactions, shared countdown, cancel paths,
  countdown-to-play, Sync Now, late join, disconnect, reconnect, cleanup). This
  harness lives outside the repository; it found and fixed three real bugs:
  an overflow that blocked Sync Now forever, a host-discovery race for late
  joiners, and replayed joins resurrecting departed participants.
* The relay was exercised with a real WebSocket client: verbatim forwarding, no
  echo to the sender, room isolation, reconnect replacement and rejection of
  invalid room/participant ids.

### Not verified here

There is no JDK/Android SDK in the environment this was written in, so the full
Gradle build (and therefore Compose compilation, resource linking, R8) has **not**
been run locally. It is verified by the `debug-v8a.yml` GitHub Actions workflow.
Anything that only fails at Compose compile time should be treated as unverified
until that build is green.

### Unfinished / deliberately out of scope

* **Host migration** — if the host leaves, the room ends; no promotion.
* **WebRTC DataChannel transport** — the interface exists, the implementation
  does not; the WebSocket relay is the shipping path.
* **Typing indicators, presence avatars from AniList, chat history across
  sessions** — not implemented.
* **Public hosted relay** — not deployed by this change; operators self-host.
* **Discord Rich Presence / invite sharing** — only manual invite text; no bot,
  no self-bot.

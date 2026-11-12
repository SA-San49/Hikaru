#!/usr/bin/env node
/**
 * Hikaru Watch Party — reference signalling relay.
 *
 * What this does
 * --------------
 * Hikaru's Watch Party only needs a *signalling* hop: a tiny server that keeps
 * rooms alive and forwards opaque, versioned JSON frames to the other members
 * of the same room. Every frame it forwards is already sequence-numbered,
 * validated and sanitised by the clients; the relay never parses or trusts the
 * payload, so it can never leak a stream URL, cookie, token or account detail —
 * those are never sent in the first place (see `docs/watch-party.md`).
 *
 * The relay intentionally holds **no durable state**:
 *   - no database, no accounts, no message history;
 *   - a room exists only while at least one socket is attached to it;
 *   - frames are dropped when the sender is not in the room.
 *
 * Transport
 * ---------
 * WebSocket (`ws`), matches `RelayRoomTransport` on the Android side:
 *   wss://<host>/ws?room=<CODE>&pid=<participantId>&name=<displayName>
 * The first attached participant of a room is announced by the clients as the
 * host; the relay stays completely role-agnostic.
 *
 * Run locally
 * -----------
 *   cd relay && npm install && npm start
 *   # then set "Watch party relay URL" in Hikaru to ws://10.0.2.2:8787/ws
 *
 * Deploy (free tiers, no credit card required at the time of writing)
 * ------------------------------------------------------------------
 *   fly.io    — 3 shared-cpu-1x 256MB machines, free allowance; `fly deploy`
 *   render.com— free web service, spins down after idle (cold start ~30 s)
 *   deno deploy — free tier, works with `deno run --allow-net`
 *   fly.io is the smoothest for WebSockets because it does not idle-suspend.
 *
 * Limits enforced here (defence in depth, the client enforces its own):
 *   - max frame 64 KiB (mirrors RoomMessageCodec.MAX_FRAME_BYTES);
 *   - max 12 participants per room;
 *   - max 8 rooms per IP, 120 frames / 10 s per socket.
 *
 * Environment
 * -----------
 *   PORT        listening port (default 8787)
 *   MAX_ROOM    participants per room (default 12)
 *   ORIGIN      optional comma-separated origin allow-list
 */

'use strict'

const http = require('http')
const { WebSocketServer } = require('ws')

const PORT = Number(process.env.PORT || 8787)
const MAX_ROOM = Number(process.env.MAX_ROOM || 12)
const MAX_FRAME_BYTES = 64 * 1024
const MAX_ROOMS_PER_IP = 8
const RATE_LIMIT_FRAMES = 120
const RATE_LIMIT_WINDOW_MS = 10_000
const ROOM_CODE = /^[A-Z0-9]{4,12}$/
const PARTICIPANT_ID = /^[A-Za-z0-9_-]{6,64}$/

/** roomCode -> Map<socket, { id, name, ip }> */
const rooms = new Map()

function log(...args) {
  console.log(new Date().toISOString(), ...args)
}

function roomMemberCount(code) {
  return rooms.get(code)?.size ?? 0
}

function leaveRoom(ws) {
  const code = ws.hikaruRoom
  if (!code) return
  const room = rooms.get(code)
  if (!room) return
  room.delete(ws)
  if (room.size === 0) {
    rooms.delete(code)
    log(`room ${code} closed`)
  }
}

function sanitizeName(raw) {
  if (typeof raw !== 'string') return 'Guest'
  const cleaned = raw.replace(/[\u0000-\u001F\u007F]/g, '').trim().slice(0, 24)
  return cleaned || 'Guest'
}

const server = http.createServer((req, res) => {
  if (req.url === '/healthz') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: true, rooms: rooms.size }))
    return
  }
  res.writeHead(404, { 'content-type': 'text/plain' })
  res.end('Hikaru watch party relay\n')
})

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: MAX_FRAME_BYTES })

wss.on('connection', (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`)
  const code = (url.searchParams.get('room') || '').toUpperCase()
  // `pid` is what the Android client sends; `id` kept as an alias.
  const rawId = url.searchParams.get('pid') || url.searchParams.get('id') || ''
  const name = sanitizeName(url.searchParams.get('name'))
  const ip = (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').toString().split(',')[0].trim()

  if (!ROOM_CODE.test(code) || !PARTICIPANT_ID.test(rawId)) {
    ws.close(4400, 'bad room or participant id')
    return
  }

  let room = rooms.get(code)
  if (!room) {
    if ([...rooms.values()].filter((members) => [...members.values()].some((m) => m.ip === ip)).length >= MAX_ROOMS_PER_IP) {
      ws.close(4429, 'too many rooms')
      return
    }
    room = new Map()
    rooms.set(code, room)
    log(`room ${code} created`)
  }

  // One socket per participant id: drop the stale duplicate from a reconnect.
  for (const [existing, meta] of room.entries()) {
    if (meta.id === rawId) {
      room.delete(existing)
      existing.close(4409, 'replaced by a newer connection')
    }
  }

  if (room.size >= MAX_ROOM) {
    ws.close(4429, 'room full')
    return
  }

  // The current host is whoever joined first; reconnects keep their slot.
  const isFirst = room.size === 0
  room.set(ws, { id: rawId, name, ip, joinedAt: Date.now() })
  ws.hikaruRoom = code
  ws.hikaruId = rawId
  ws.hikaruBudget = RATE_LIMIT_FRAMES
  ws.hikaruWindowStart = Date.now()

  log(`room ${code}: ${rawId} (${name}) joined, ${room.size} member(s)${isFirst ? ' as first' : ''}`)

  ws.on('message', (data, isBinary) => {
    if (isBinary) return
    const now = Date.now()
    if (now - ws.hikaruWindowStart > RATE_LIMIT_WINDOW_MS) {
      ws.hikaruWindowStart = now
      ws.hikaruBudget = RATE_LIMIT_FRAMES
    }
    ws.hikaruBudget -= 1
    if (ws.hikaruBudget < 0) {
      ws.close(4429, 'rate limited')
      return
    }
    if (data.length > MAX_FRAME_BYTES) return

    const current = rooms.get(code)
    if (!current || !current.has(ws)) return
    if (current.size < 2) return // nobody to talk to

    // Broadcast verbatim: the relay never parses or rewrites events.
    for (const peer of current.keys()) {
      if (peer === ws) continue
      if (peer.readyState !== peer.OPEN) continue
      peer.send(data.toString())
    }
  })

  ws.on('close', () => leaveRoom(ws))
  ws.on('error', (error) => {
    log(`socket error in room ${code}: ${error.message}`)
    leaveRoom(ws)
  })
})

setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.readyState === ws.OPEN) ws.ping()
  }
}, 30_000).unref()

server.listen(PORT, '0.0.0.0', () => {
  log(`watch party relay listening on 0.0.0.0:${PORT}`)
})

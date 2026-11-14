package eu.kanade.tachiyomi.ui.player.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * WebSocket transport to the Watch Party relay.
 *
 * Why a relay WebSocket is the default:
 *  - it works across long distances, symmetric NATs and mobile carriers with no
 *    STUN/TURN configuration at all;
 *  - a room only exchanges tiny control events (play/pause/seek/chat/reactions),
 *    so bandwidth is negligible — typically 1–3 KB/s while a room is active and
 *    zero when it is not;
 *  - no media ever passes through it: every participant streams and decodes
 *    their own video.
 *
 * A WebRTC DataChannel transport can be added later behind [RoomTransport]; the
 * relay then doubles as the signalling channel and stays the TURN-free fallback.
 *
 * Reliability: exponential-backoff reconnects, a bounded outbound queue that
 * drops the oldest frame when backed up (newest state wins), and OkHttp protocol
 * pings so dead sockets are detected quickly.
 */
class RelayRoomTransport(
    private val url: String,
    private val roomId: String,
    private val participantId: String,
    private val displayName: () -> String,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
) : RoomTransport {

    override var listener: RoomTransport.Listener? = null

    private var webSocket: WebSocket? = null
    private var closed = false
    private var attempt = 0
    private var reconnectJob: Job? = null

    /** Bounded queue of frames waiting to be written to the socket. */
    private val outbound = ArrayDeque<String>()

    override fun connect() {
        closed = false
        attempt = 0
        openSocket()
    }

    private fun openSocket() {
        if (closed) return
        listener?.onStateChanged(RoomTransport.State.Connecting)
        val request = Request.Builder()
            .url(buildUrl())
            .header("User-Agent", "Hikaru WatchParty")
            .build()
        webSocket = client.newWebSocket(request, SocketListener())
    }

    private fun buildUrl(): String {
        val base = url.trim().ifBlank { WatchPartyPreferences.DEFAULT_RELAY_URL }
        val separator = if (base.contains('?')) '&' else '?'
        return buildString {
            append(base)
            append(separator)
            append("room=")
            append(roomId)
            append("&pid=")
            append(participantId)
            append("&name=")
            append(java.net.URLEncoder.encode(displayName(), "UTF-8"))
        }
    }

    override fun send(text: String) {
        synchronized(outbound) {
            if (outbound.size >= MAX_QUEUED_FRAMES) {
                // Drop the oldest so the newest state wins.
                outbound.removeFirst()
            }
            outbound.addLast(text)
        }
        webSocket?.let { drainTo(it) }
    }

    private fun drainTo(socket: WebSocket) {
        while (true) {
            val next = synchronized(outbound) {
                if (outbound.isEmpty()) null else outbound.removeFirst()
            } ?: return
            if (!socket.send(next)) {
                // Re-queue (at the front) so the frame is delivered after reconnect.
                synchronized(outbound) {
                    if (outbound.size < MAX_QUEUED_FRAMES) outbound.addFirst(next)
                }
                scheduleReconnect()
                return
            }
        }
    }

    private fun scheduleReconnect() {
        if (closed || reconnectJob?.isActive == true) return
        webSocket = null
        listener?.onStateChanged(RoomTransport.State.Connecting)
        reconnectJob = scope.launch(Dispatchers.IO) {
            while (!closed) {
                attempt++
                val backoff = min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L shl min(attempt, 5)))
                delay(backoff)
                if (closed) return@launch
                openSocket()
                // Success is reported by onOpen, which cancels this job.
                delay(OPEN_CHECK_DELAY_MS)
                if (webSocket != null && reconnectJob?.isActive == true) return@launch
            }
        }
    }

    override fun close() {
        closed = true
        reconnectJob?.cancel()
        reconnectJob = null
        synchronized(outbound) { outbound.clear() }
        webSocket?.close(NORMAL_CLOSURE, "bye")
        webSocket = null
        listener?.onStateChanged(RoomTransport.State.Disconnected)
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            reconnectJob?.cancel()
            reconnectJob = null
            this@RelayRoomTransport.webSocket = webSocket
            listener?.onStateChanged(RoomTransport.State.Connected)
            drainTo(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text == PONG || text == PING) return
            listener?.onMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            logcat(LogPriority.WARN, t) { "WatchParty: relay connection failed" }
            if (!closed) {
                this@RelayRoomTransport.webSocket = null
                scheduleReconnect()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            if (!closed) {
                this@RelayRoomTransport.webSocket = null
                listener?.onStateChanged(RoomTransport.State.Failed(reason))
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed) {
                listener?.onStateChanged(RoomTransport.State.Failed(reason))
            }
        }
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val MAX_QUEUED_FRAMES = 64
        private const val BASE_BACKOFF_MS = 700L
        private const val MAX_BACKOFF_MS = 15_000L
        private const val OPEN_CHECK_DELAY_MS = 900L
        private const val PONG = """{"t":"pong"}"""
        private const val PING = """{"t":"ping"}"""

        /** A dedicated client: tiny payloads, no cache, long read timeout for WS. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

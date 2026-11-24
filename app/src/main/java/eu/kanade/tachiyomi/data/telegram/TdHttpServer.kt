package eu.kanade.tachiyomi.data.telegram

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.min

object TdHttpServer {

    private const val WINDOW_BYTES: Long = 16L * 1024 * 1024
    private const val PRIME_BYTES: Long = 128L * 1024
    private const val GRACE_MS: Long = 30_000L
    private const val START_BUDGET_MS: Long = 60_000L
    private const val POLL_MS: Long = 120L
    private const val SINGLE_BYTE_MASK = 0xFF

    @Volatile
    private var server: Server? = null

    @Synchronized
    fun ensureStarted(): Int {
        val existing = server
        if (existing != null && existing.isAlive) {
            return existing.listeningPort
        }
        val created = Server()
        created.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = created
        return created.listeningPort
    }

    fun urlFor(fileId: Int, chatId: Long = 0L, messageId: Long = 0L): String {
        val port = ensureStarted()
        val base = "http://127.0.0.1:$port/tg?id=$fileId"
        return if (chatId != 0L && messageId != 0L) "$base&chat=$chatId&msg=$messageId" else base
    }

    private class Server : NanoHTTPD("127.0.0.1", 0) {

        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        override fun serve(session: IHTTPSession): Response {
            val path = session.uri ?: "/"
            if (!path.startsWith("/tg")) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            val fileId = session.parameters["id"]?.firstOrNull()?.toIntOrNull() ?: 0
            val chatId = session.parameters["chat"]?.firstOrNull()?.toLongOrNull() ?: 0L
            val messageId = session.parameters["msg"]?.firstOrNull()?.toLongOrNull() ?: 0L
            if (fileId == 0) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing file id")
            }

            var activeId = fileId
            var state = runBlocking { Td.stateOf(activeId) }
            if (state == null && chatId != 0L && messageId != 0L) {
                val refreshed = runBlocking { Td.touchMessage(chatId, messageId) }
                if (refreshed != 0) {
                    activeId = refreshed
                    state = runBlocking { Td.stateOf(activeId) }
                }
            }
            if (state == null || state.size <= 0L) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Telegram file unavailable")
            }

            val totalSize = state.size
            val rangeHeader = session.headers["range"]
            if (rangeHeader != null && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
                val spec = rangeHeader.substring("bytes=".length).substringBefore(",").trim()
                val dash = spec.indexOf("-")
                if (dash < 0) {
                    return rangeNotSatisfiable(totalSize)
                }
                val startPart = spec.substring(0, dash).trim()
                val endPart = spec.substring(dash + 1).trim()
                val start: Long
                val end: Long
                if (startPart.isEmpty()) {
                    val suffix = endPart.toLongOrNull() ?: return rangeNotSatisfiable(totalSize)
                    start = (totalSize - suffix).coerceAtLeast(0L)
                    end = totalSize - 1L
                } else {
                    start = startPart.toLongOrNull() ?: return rangeNotSatisfiable(totalSize)
                    end = (endPart.toLongOrNull() ?: (totalSize - 1L)).coerceAtMost(totalSize - 1L)
                }
                if (start < 0L || start > end || start >= totalSize) {
                    return rangeNotSatisfiable(totalSize)
                }
                val len = end - start + 1L
                val stream: InputStream = if (session.method == Method.HEAD) {
                    InputStream.nullInputStream()
                } else {
                    TdRangeInputStream(activeId, chatId, messageId, start, len, totalSize)
                }
                val resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp4", stream, len)
                resp.addHeader("Accept-Ranges", "bytes")
                resp.addHeader("Content-Range", "bytes $start-$end/$totalSize")
                return resp
            }

            val stream: InputStream = if (session.method == Method.HEAD) {
                InputStream.nullInputStream()
            } else {
                TdRangeInputStream(activeId, chatId, messageId, 0L, totalSize, totalSize)
            }
            val resp = newFixedLengthResponse(Response.Status.OK, "video/mp4", stream, totalSize)
            resp.addHeader("Accept-Ranges", "bytes")
            return resp
        }

        private fun rangeNotSatisfiable(totalSize: Long): Response {
            val resp = newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                "text/plain",
                "Range not satisfiable",
            )
            resp.addHeader("Content-Range", "bytes */$totalSize")
            return resp
        }
    }

    private class TdRangeInputStream(
        private var fileId: Int,
        private val chatId: Long,
        private val messageId: Long,
        startOffset: Long,
        length: Long,
        private val totalSize: Long,
    ) : InputStream() {

        private var pos: Long = startOffset
        private var remaining: Long = length
        private var requestedFrom: Long = -1L
        private var raf: RandomAccessFile? = null
        private var rafPath: String = ""
        private var repaired: Boolean = false
        private val singleByte = ByteArray(1)

        init {
            val window = min(WINDOW_BYTES, (totalSize - startOffset).coerceAtLeast(PRIME_BYTES))
            Td.request(fileId, startOffset, window)
            requestedFrom = startOffset
        }

        override fun read(): Int {
            val n = read(singleByte, 0, 1)
            return if (n < 0) -1 else singleByte[0].toInt() and SINGLE_BYTE_MASK
        }

        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0L || pos >= totalSize) return -1

            val want = min(len.toLong(), remaining).toInt()
            var state = waitForBytes(pos)
            var file = openRaf(state.path)

            var available = availableAt(state, pos)
            if (available <= 0L) {
                state = waitForBytes(pos)
                file = openRaf(state.path)
                available = availableAt(state, pos)
            }
            if (available <= 0L) return -1

            val toRead = min(want.toLong(), available).toInt()
            file.seek(pos)
            val readCount = file.read(b, off, toRead)
            if (readCount <= 0) return -1
            pos += readCount
            remaining -= readCount
            maybeAdvanceWindow()
            return readCount
        }

        private fun maybeAdvanceWindow() {
            if (pos >= requestedFrom + (WINDOW_BYTES / 2L)) {
                val window = min(WINDOW_BYTES, (totalSize - pos).coerceAtLeast(PRIME_BYTES))
                Td.request(fileId, pos, window)
                requestedFrom = pos
            }
        }

        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        private fun waitForBytes(offset: Long): Td.FileState {
            val budget = if (offset == 0L) START_BUDGET_MS else GRACE_MS
            val deadline = System.currentTimeMillis() + budget
            var asked = false
            while (true) {
                var state = Td.fileState(fileId) ?: runBlocking { Td.stateOf(fileId) }
                if (state != null && availableAt(state, offset) > 0L && state.path.isNotBlank()) {
                    return state
                }
                val err = Td.downloadError(fileId)
                if ((err != null || state?.canBeDownloaded == false) && !repaired && chatId != 0L && messageId != 0L) {
                    repaired = true
                    Td.clearDownloadError(fileId)
                    val freshId = runBlocking { Td.touchMessage(chatId, messageId) }
                    if (freshId != 0) {
                        fileId = freshId
                        Td.request(fileId, offset, WINDOW_BYTES)
                        requestedFrom = offset
                        asked = true
                        continue
                    }
                }
                if (err != null && repaired) {
                    throw IOException("Telegram download failed: $err")
                }
                if (!asked) {
                    val window = min(WINDOW_BYTES, (totalSize - offset).coerceAtLeast(PRIME_BYTES))
                    Td.request(fileId, offset, window)
                    requestedFrom = offset
                    asked = true
                }
                if (System.currentTimeMillis() >= deadline) {
                    logcat(LogPriority.ERROR) { "Timed out waiting for Telegram bytes at offset=$offset fileId=$fileId" }
                    throw IOException("Timed out waiting for Telegram stream")
                }
                runBlocking { delay(POLL_MS) }
                state = Td.fileState(fileId)
                if (state != null && state.complete && state.path.isNotBlank()) {
                    return state
                }
            }
        }

        private fun availableAt(state: Td.FileState?, offset: Long): Long {
            if (state == null || state.path.isBlank()) return 0L
            if (state.complete) return (totalSize - offset).coerceAtLeast(0L)
            if (offset >= state.downloadOffset && offset < state.downloadOffset + state.readyFromOffset) {
                return (state.downloadOffset + state.readyFromOffset) - offset
            }
            val prefix = runBlocking { Td.availableFrom(fileId, offset) }
            return if (prefix > 0L) prefix else 0L
        }

        private fun openRaf(path: String): RandomAccessFile {
            val current = raf
            if (current != null && rafPath == path) return current
            runCatching { current?.close() }
            val opened = RandomAccessFile(File(path), "r")
            raf = opened
            rafPath = path
            return opened
        }

        override fun close() {
            runCatching { raf?.close() }
            raf = null
            rafPath = ""
        }
    }
}

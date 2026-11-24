package eu.kanade.tachiyomi.data.telegram

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.min

object TdHttpServer {

    private const val PORT = 49731
    private const val PRIME_BYTES = 256L * 1024
    private const val WINDOW_BYTES = 2L * 1024 * 1024
    private const val ADVANCE_BYTES = 1L * 1024 * 1024
    private const val TAIL_THRESHOLD_BYTES = 1L * 1024 * 1024
    private const val START_BUDGET_MS = 45_000L
    private const val GRACE_MS = 20_000L
    private const val POLL_MS = 25L
    private const val SINGLE_BYTE_MASK = 0xff

    @Volatile
    private var server: LocalTdServer? = null

    @Synchronized
    fun ensureStarted(): Int {
        val current = server
        if (current != null && current.isAlive) return PORT
        runCatching { current?.stop() }
        val created = LocalTdServer()
        created.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        server = created
        return PORT
    }

    fun urlFor(fileId: Int, chatId: Long = 0, messageId: Long = 0): String {
        val port = ensureStarted()
        return "http://127.0.0.1:$port/tg?id=$fileId&chat=$chatId&msg=$messageId"
    }

    fun thumbUrlFor(fileId: Int): String {
        val port = ensureStarted()
        return "http://127.0.0.1:$port/thumb?id=$fileId"
    }

    private class LocalTdServer : NanoHTTPD("127.0.0.1", PORT) {

        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/thumb") {
                return serveThumb(session)
            }
            if (session.uri != "/tg") {
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
            if ((state == null || !state.canBeDownloaded) && chatId != 0L && messageId != 0L) {
                val refreshed = runBlocking { Td.touchMessage(chatId, messageId) }
                if (refreshed != 0) {
                    activeId = refreshed
                    state = runBlocking { Td.stateOf(activeId) }
                }
            }
            if (state == null || state.size <= 0L) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "Telegram file unavailable",
                )
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

        @Suppress("ReturnCount")
        private fun serveThumb(session: IHTTPSession): Response {
            val fileId = session.parameters["id"]?.firstOrNull()?.toIntOrNull() ?: 0
            if (fileId == 0) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing thumb id")
            }
            val path = runBlocking { Td.thumbPath(fileId) }
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Thumb not ready")
            val file = File(path)
            if (!file.exists() || file.length() <= 0L) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Thumb missing")
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                FileInputStream(file),
                file.length(),
            )
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
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        private val singleByte = ByteArray(1)

        init {
            Td.onStreamOpened(fileId)
            val window = computeWindow(startOffset)
            Td.request(fileId, startOffset, window)
            requestedFrom = startOffset
        }

        private fun computeWindow(offset: Long): Long {
            val left = (totalSize - offset).coerceAtLeast(1L)
            return if (left <= TAIL_THRESHOLD_BYTES) {
                left
            } else {
                min(WINDOW_BYTES, left.coerceAtLeast(PRIME_BYTES))
            }
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
            if (remaining <= 0L || pos >= totalSize) return
            if (pos >= requestedFrom + ADVANCE_BYTES) {
                val window = computeWindow(pos)
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
                val revBefore = Td.revision.value
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
                        val window = computeWindow(offset)
                        Td.request(fileId, offset, window)
                        requestedFrom = offset
                        asked = true
                        continue
                    }
                }
                if (err != null && repaired) {
                    throw IOException("Telegram download failed: $err")
                }
                if (!asked) {
                    val window = computeWindow(offset)
                    Td.request(fileId, offset, window)
                    requestedFrom = offset
                    asked = true
                }
                if (System.currentTimeMillis() >= deadline) {
                    logcat(LogPriority.ERROR) {
                        "Timed out waiting for Telegram bytes at offset=$offset fileId=$fileId"
                    }
                    throw IOException("Timed out waiting for Telegram stream")
                }
                runBlocking { Td.awaitUpdate(revBefore, POLL_MS) }
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
            if (closed.compareAndSet(false, true)) {
                Td.onStreamClosed(fileId)
            }
            runCatching { raf?.close() }
            raf = null
            rafPath = ""
        }
    }
}

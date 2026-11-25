package eu.kanade.tachiyomi.data.telegram

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.min

object TdHttpServer {

    private const val PORT = 49731
    private const val PRIME_BYTES = 512L * 1024
    private const val WINDOW_BYTES = 2L * 1024 * 1024
    private const val ADVANCE_BYTES = 1L * 1024 * 1024
    private const val EXT_WINDOW_BYTES = 10L * 1024 * 1024
    private const val EXT_ADVANCE_BYTES = 2L * 1024 * 1024
    private const val TAIL_THRESHOLD_BYTES = 1L * 1024 * 1024
    private const val START_BUDGET_MS = 45_000L
    private const val GRACE_MS = 20_000L
    private const val POLL_MS = 25L
    private const val SINGLE_BYTE_MASK = 0xFF
    private const val HEADER_SNIFF_BYTES = 12
    private const val MIN_HEADER_SNIFF_BYTES = 3

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

    fun urlFor(
        fileId: Int,
        chatId: Long = 0,
        messageId: Long = 0,
        isExtension: Boolean = false,
    ): String {
        val port = ensureStarted()
        val ext = if (isExtension) "&src=ext" else ""
        return "http://127.0.0.1:$port/tg?id=$fileId&chat=$chatId&msg=$messageId$ext"
    }

    fun thumbUrlFor(fileId: Int): String {
        val port = ensureStarted()
        return "http://127.0.0.1:$port/thumb?id=$fileId"
    }

    @Suppress("MagicNumber", "ReturnCount")
    private fun isImageMagicHeader(buf: ByteArray, off: Int, count: Int): Boolean {
        if (count < MIN_HEADER_SNIFF_BYTES) return false
        val b0 = buf[off].toInt() and SINGLE_BYTE_MASK
        val b1 = buf[off + 1].toInt() and SINGLE_BYTE_MASK
        val b2 = buf[off + 2].toInt() and SINGLE_BYTE_MASK
        val isJpeg = b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF
        if (isJpeg || count < 4) return isJpeg
        val b3 = buf[off + 3].toInt() and SINGLE_BYTE_MASK
        val isPng = b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47
        val isGif = b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38
        val isRiff = b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
        val hasWebpTag = count >= 12 &&
            (buf[off + 8].toInt() and SINGLE_BYTE_MASK) == 0x57 &&
            (buf[off + 9].toInt() and SINGLE_BYTE_MASK) == 0x45 &&
            (buf[off + 10].toInt() and SINGLE_BYTE_MASK) == 0x42
        val isWebp = hasWebpTag && isRiff && (buf[off + 11].toInt() and SINGLE_BYTE_MASK) == 0x50
        return isPng || isGif || isWebp
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
            val isExtension = session.parameters["src"]?.firstOrNull() == "ext"
            val windowBytes = if (isExtension) EXT_WINDOW_BYTES else WINDOW_BYTES
            val advanceBytes = if (isExtension) EXT_ADVANCE_BYTES else ADVANCE_BYTES
            if (fileId == 0) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing file id")
            }

            var activeId = fileId
            if (!Td.isVerifiedVideoFile(activeId) && chatId != 0L && messageId != 0L) {
                val verifiedId = runBlocking { Td.verifyVideoFile(activeId, chatId, messageId) }
                if (verifiedId == 0) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not a video message")
                }
                activeId = verifiedId
            }

            var state = Td.fileState(activeId) ?: runBlocking { Td.stateOf(activeId) }
            val imagePath = state?.path.orEmpty().let { Td.isImageFilePath(it) }
            val needsRefresh = state == null || !state.canBeDownloaded || Td.downloadError(activeId) != null || imagePath
            if (needsRefresh && chatId != 0L && messageId != 0L) {
                Td.clearDownloadError(activeId)
                val refreshed = runBlocking { Td.touchMessage(chatId, messageId) }
                if (refreshed == 0) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Telegram video unavailable")
                }
                activeId = refreshed
                Td.clearDownloadError(activeId)
                state = Td.fileState(activeId) ?: runBlocking { Td.stateOf(activeId) }
            }
            if (state == null || state.size <= 0L || Td.isImageFilePath(state.path)) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "Telegram file unavailable",
                )
            }
            val mime = Td.videoMimeFor(activeId, state.path)
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
                    ByteArrayInputStream(ByteArray(0))
                } else {
                    TdRangeInputStream(activeId, chatId, messageId, start, len, totalSize, windowBytes, advanceBytes)
                }
                val resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, len)
                resp.addHeader("Accept-Ranges", "bytes")
                resp.addHeader("Content-Range", "bytes $start-$end/$totalSize")
                return resp
            }

            val stream: InputStream = if (session.method == Method.HEAD) {
                ByteArrayInputStream(ByteArray(0))
            } else {
                TdRangeInputStream(activeId, chatId, messageId, 0L, totalSize, totalSize, windowBytes, advanceBytes)
            }
            val resp = newFixedLengthResponse(Response.Status.OK, mime, stream, totalSize)
            resp.addHeader("Accept-Ranges", "bytes")
            return resp
        }

        @Suppress("ReturnCount")
        private fun serveThumb(session: IHTTPSession): Response {
            val fileId = session.parameters["id"]?.firstOrNull()?.toIntOrNull() ?: 0
            if (fileId == 0) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing thumb id")
            }
            val path = runBlocking {
                Td.ensureReady()
                Td.thumbPath(fileId)
            }
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Thumb not ready")
            val file = File(path)
            if (!file.exists() || file.length() <= 0L) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Thumb missing")
            }
            val thumbStream: InputStream = if (session.method == Method.HEAD) {
                ByteArrayInputStream(ByteArray(0))
            } else {
                FileInputStream(file)
            }
            return newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                thumbStream,
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

    @Suppress("LongParameterList")
    private class TdRangeInputStream(
        private var fileId: Int,
        private val chatId: Long,
        private val messageId: Long,
        startOffset: Long,
        length: Long,
        private val totalSize: Long,
        private val windowBytes: Long,
        private val advanceBytes: Long,
    ) : InputStream() {

        private var pos: Long = startOffset
        private var remaining: Long = length
        private var requestedFrom: Long = -1L
        private var knownAvailableUntil: Long = 0L
        private var headerChecked: Boolean = startOffset != 0L
        private var raf: RandomAccessFile? = null
        private var rafPath: String = ""
        private var repaired: Boolean = false
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        private val singleByte = ByteArray(1)

        init {
            Td.onStreamOpened(fileId)
            val initWindow = computeInitialWindow(startOffset)
            Td.request(fileId, startOffset, initWindow)
            requestedFrom = startOffset
        }

        private fun computeInitialWindow(offset: Long): Long {
            val left = (totalSize - offset).coerceAtLeast(1L)
            return if (left <= TAIL_THRESHOLD_BYTES) {
                left
            } else {
                min(PRIME_BYTES, left)
            }
        }

        private fun computeWindow(offset: Long): Long {
            val left = (totalSize - offset).coerceAtLeast(1L)
            return if (left <= TAIL_THRESHOLD_BYTES) {
                left
            } else {
                min(windowBytes, left.coerceAtLeast(PRIME_BYTES))
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
            var available = knownAvailableUntil - pos
            var file = raf
            if (available <= 0L || file == null) {
                val (state, readyAvail) = waitForBytes(pos)
                file = openRaf(state.path)
                available = readyAvail
                knownAvailableUntil = pos + available
            }
            if (available <= 0L) return -1
            val toRead = min(want.toLong(), available).toInt()
            file.seek(pos)
            val readCount = file.read(b, off, toRead)
            if (readCount <= 0) {
                knownAvailableUntil = 0L
                return -1
            }
            if (!headerChecked && pos == 0L && readCount >= MIN_HEADER_SNIFF_BYTES) {
                headerChecked = true
                if (isImageMagicHeader(b, off, min(readCount, HEADER_SNIFF_BYTES))) {
                    throw IOException("Rejected non-video image payload from Telegram stream")
                }
            }
            pos += readCount
            remaining -= readCount
            maybeAdvanceWindow()
            return readCount
        }

        private fun maybeAdvanceWindow() {
            if (remaining <= 0L || pos >= totalSize) return
            val threshold = if (requestedFrom == 0L) PRIME_BYTES / 2L else advanceBytes
            if (pos >= requestedFrom + threshold) {
                val window = computeWindow(pos)
                Td.request(fileId, pos, window)
                requestedFrom = pos
            }
        }

        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        private fun waitForBytes(offset: Long): Pair<Td.FileState, Long> {
            val budget = if (offset == 0L) START_BUDGET_MS else GRACE_MS
            val deadline = System.currentTimeMillis() + budget
            var asked = false
            while (true) {
                val revBefore = Td.revision.value
                val state = Td.fileState(fileId) ?: runBlocking { Td.stateOf(fileId) }
                if (state != null && state.path.isNotBlank() && !Td.isImageFilePath(state.path)) {
                    val avail = availableAt(state, offset)
                    if (avail > 0L) {
                        return state to avail
                    }
                }
                val err = Td.downloadError(fileId)
                val staleOrErr = err != null || state?.canBeDownloaded == false
                if (staleOrErr && !repaired && chatId != 0L && messageId != 0L) {
                    repaired = true
                    Td.clearDownloadError(fileId)
                    val freshId = runBlocking { Td.touchMessage(chatId, messageId) }
                    if (freshId != 0) {
                        fileId = freshId
                        val window = computeInitialWindow(offset)
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
                    val window = computeInitialWindow(offset)
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

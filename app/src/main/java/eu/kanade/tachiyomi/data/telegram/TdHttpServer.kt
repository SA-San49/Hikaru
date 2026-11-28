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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

object TdHttpServer {

    private const val PORT = 49731
    private const val SOCKET_READ_TIMEOUT_MS = 30_000
    private const val ONE_MB_BYTES = 1024L * 1024L
    private const val CURSOR_NOTIFY_STEP_BYTES = 256L * 1024L
    private const val PROBE_MAX_BYTES = 6L * 1024 * 1024
    private const val TAIL_THRESHOLD_BYTES = 24L * 1024 * 1024
    private const val START_BUDGET_MS = 90_000L
    private const val GRACE_MS = 60_000L
    private const val POLL_MS = 15L
    private const val REASK_INTERVAL_MS = 300L
    private const val IDLE_REASK_MS = 150L
    private const val AVAIL_QUERY_INTERVAL_MS = 80L
    private const val SINGLE_BYTE_MASK = 0xFF
    private const val HEADER_SNIFF_BYTES = 12
    private const val MIN_HEADER_SNIFF_BYTES = 3
    private const val PNG_HEADER_BYTES = 4
    private const val THUMB_CACHE_MAX_AGE_SECS = 604_800

    @Volatile
    private var server: LocalTdServer? = null

    private val latestStreamGen = ConcurrentHashMap<Int, AtomicLong>()

    @Synchronized
    fun ensureStarted(): Int {
        val current = server
        if (current != null && current.isAlive) return PORT
        runCatching { current?.stop() }
        val created = LocalTdServer()
        created.start(SOCKET_READ_TIMEOUT_MS, true)
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

    fun thumbUrlFor(
        fileId: Int,
        chatId: Long = 0L,
        messageId: Long = 0L,
    ): String {
        val port = ensureStarted()
        val extra = if (chatId != 0L && messageId != 0L) "&chat=$chatId&msg=$messageId" else ""
        return "http://127.0.0.1:$port/thumb?id=$fileId$extra"
    }

    private fun nextGeneration(fileId: Int): Long =
        latestStreamGen.computeIfAbsent(fileId) { AtomicLong(0L) }.incrementAndGet()

    private fun currentGeneration(fileId: Int): Long =
        latestStreamGen[fileId]?.get() ?: 0L

    @Suppress("MagicNumber", "ReturnCount")
    private fun isImageMagicHeader(buf: ByteArray, off: Int, count: Int): Boolean {
        if (count < MIN_HEADER_SNIFF_BYTES) return false
        val b0 = buf[off].toInt() and SINGLE_BYTE_MASK
        val b1 = buf[off + 1].toInt() and SINGLE_BYTE_MASK
        val b2 = buf[off + 2].toInt() and SINGLE_BYTE_MASK
        val isJpeg = b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF
        if (isJpeg || count < PNG_HEADER_BYTES) return isJpeg
        val b3 = buf[off + 3].toInt() and SINGLE_BYTE_MASK
        val isPng = b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47
        val isGif = b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38
        val isRiff = b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
        val hasWebpTag = count >= HEADER_SNIFF_BYTES &&
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
            val isDownload = session.parameters["dl"]?.firstOrNull() == "1"
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
            val invalidState = state == null || !state.canBeDownloaded || imagePath
            val needsRefresh = invalidState || Td.downloadError(activeId) != null
            if (needsRefresh && chatId != 0L && messageId != 0L) {
                Td.clearDownloadError(activeId)
                val refreshed = runBlocking { Td.touchMessage(chatId, messageId) }
                if (refreshed == 0) {
                    return newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain",
                        "Telegram video unavailable",
                    )
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
                val isProbe = !isDownload &&
                    start > 0L &&
                    (len <= PROBE_MAX_BYTES || start >= totalSize - TAIL_THRESHOLD_BYTES)
                val stream: InputStream = if (session.method == Method.HEAD) {
                    ByteArrayInputStream(ByteArray(0))
                } else {
                    TdRangeInputStream(
                        fileId = activeId,
                        chatId = chatId,
                        messageId = messageId,
                        startOffset = start,
                        length = len,
                        totalSize = totalSize,
                        isDownload = isDownload,
                        isProbe = isProbe,
                    )
                }
                val resp = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, len)
                resp.addHeader("Accept-Ranges", "bytes")
                resp.addHeader("Content-Range", "bytes $start-$end/$totalSize")
                return resp
            }

            val stream: InputStream = if (session.method == Method.HEAD) {
                ByteArrayInputStream(ByteArray(0))
            } else {
                TdRangeInputStream(
                    fileId = activeId,
                    chatId = chatId,
                    messageId = messageId,
                    startOffset = 0L,
                    length = totalSize,
                    totalSize = totalSize,
                    isDownload = isDownload,
                    isProbe = false,
                )
            }
            val resp = newFixedLengthResponse(Response.Status.OK, mime, stream, totalSize)
            resp.addHeader("Accept-Ranges", "bytes")
            return resp
        }

        @Suppress("ReturnCount")
        private fun serveThumb(session: IHTTPSession): Response {
            val fileId = session.parameters["id"]?.firstOrNull()?.toIntOrNull() ?: 0
            val chatId = session.parameters["chat"]?.firstOrNull()?.toLongOrNull() ?: 0L
            val messageId = session.parameters["msg"]?.firstOrNull()?.toLongOrNull() ?: 0L
            if (fileId == 0 && (chatId == 0L || messageId == 0L)) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing thumb id")
            }
            val path = runBlocking {
                Td.ensureReady()
                Td.thumbPath(fileId, chatId, messageId)
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
            val resp = newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                thumbStream,
                file.length(),
            )
            resp.addHeader("Cache-Control", "public, max-age=$THUMB_CACHE_MAX_AGE_SECS")
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

    @Suppress("LongParameterList")
    private class TdRangeInputStream(
        private var fileId: Int,
        private val chatId: Long,
        private val messageId: Long,
        startOffset: Long,
        length: Long,
        private val totalSize: Long,
        private val isDownload: Boolean,
        private val isProbe: Boolean,
    ) : InputStream() {

        private var pos: Long = startOffset
        private var remaining: Long = length
        private var lastCursorNotifyPos: Long = startOffset
        private var lastAskMs: Long = 0L
        private var lastAvailQueryMs: Long = 0L
        private var knownAvailableUntil: Long = 0L
        private var headerChecked: Boolean = startOffset != 0L
        private var raf: RandomAccessFile? = null
        private var rafPath: String = ""
        private var repaired: Boolean = false
        private val closed = AtomicBoolean(false)
        private val singleByte = ByteArray(1)
        private var generation: Long = if (isDownload || isProbe) 0L else nextGeneration(fileId)

        init {
            Td.onStreamOpened(fileId)
            if (isProbe) {
                Td.beginProbe(fileId)
                issueProbeRequest(startOffset)
            } else if (isDownload) {
                Td.request(fileId, startOffset, 0L)
                lastAskMs = System.currentTimeMillis()
            } else {
                Td.startChunkBuffer(fileId, totalSize, startOffset)
                lastAskMs = System.currentTimeMillis()
            }
        }

        private fun isSuperseded(): Boolean =
            generation != 0L && currentGeneration(fileId) != generation

        override fun read(): Int {
            val n = read(singleByte, 0, 1)
            return if (n < 0) -1 else singleByte[0].toInt() and SINGLE_BYTE_MASK
        }

        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0L || pos >= totalSize) return -1
            if (isSuperseded()) throw IOException("Telegram stream superseded by newer seek")
            val want = min(len.toLong(), remaining).toInt()
            var readCount = readOnce(b, off, want)
            if (readCount <= 0 && pos < totalSize && remaining > 0L) {
                knownAvailableUntil = 0L
                runCatching { raf?.close() }
                raf = null
                rafPath = ""
                readCount = readOnce(b, off, want)
            }
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
            if (!isProbe && !isDownload && pos - lastCursorNotifyPos >= CURSOR_NOTIFY_STEP_BYTES) {
                lastCursorNotifyPos = pos
                Td.updatePlaybackCursor(fileId, pos, totalSize)
            }
            return readCount
        }

        private fun readOnce(b: ByteArray, off: Int, want: Int): Int {
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
            return file.read(b, off, toRead)
        }

        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        private fun waitForBytes(offset: Long): Pair<Td.FileState, Long> {
            val budget = if (offset == 0L || isDownload) START_BUDGET_MS else GRACE_MS
            val deadline = System.currentTimeMillis() + budget
            var asked = false
            while (true) {
                if (isSuperseded()) {
                    throw IOException("Telegram stream superseded by newer seek")
                }
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
                val hasMsgRef = chatId != 0L && messageId != 0L
                val canRepair = staleOrErr && !repaired && hasMsgRef
                if (canRepair) {
                    repaired = true
                    Td.clearDownloadError(fileId)
                    val freshId = runBlocking { Td.touchMessage(chatId, messageId) }
                    if (freshId != 0) {
                        fileId = freshId
                        if (!isDownload && !isProbe) {
                            generation = nextGeneration(fileId)
                            Td.startChunkBuffer(fileId, totalSize, offset)
                        } else if (isProbe) {
                            Td.beginProbe(fileId)
                            issueProbeRequest(offset)
                        } else {
                            Td.request(fileId, offset, 0L)
                        }
                        asked = true
                        continue
                    }
                }
                if (err != null && repaired) {
                    throw IOException("Telegram download failed: $err")
                }
                val now = System.currentTimeMillis()
                val reaskWindow = if (state == null || !state.active || state.downloadOffset > offset) {
                    IDLE_REASK_MS
                } else {
                    REASK_INTERVAL_MS
                }
                val needsAsk = !asked || now - lastAskMs >= reaskWindow
                if (needsAsk) {
                    when {
                        isProbe -> issueProbeRequest(offset)
                        isDownload -> {
                            if (state == null || !state.active) {
                                Td.request(fileId, offset, 0L)
                            }
                            lastAskMs = now
                        }
                        else -> {
                            Td.startChunkBuffer(fileId, totalSize, offset)
                            lastAskMs = now
                        }
                    }
                    asked = true
                }
                if (now >= deadline) {
                    logcat(LogPriority.ERROR) {
                        "Timed out waiting for Telegram bytes at offset=$offset fileId=$fileId"
                    }
                    throw IOException("Timed out waiting for Telegram stream")
                }
                runBlocking { Td.awaitUpdate(revBefore, POLL_MS) }
            }
        }

        private fun issueProbeRequest(offset: Long) {
            val alignedStart = Td.alignTo1MbFloor(offset)
            val endTarget = min(totalSize, offset + maxOf(remaining, ONE_MB_BYTES))
            val limit = (endTarget - alignedStart).coerceAtLeast(1L)
            Td.request(fileId, alignedStart, limit)
            lastAskMs = System.currentTimeMillis()
        }

        @Suppress("ReturnCount")
        private fun availableAt(state: Td.FileState?, offset: Long): Long {
            if (state == null || state.path.isBlank()) return 0L
            if (state.complete) return (totalSize - offset).coerceAtLeast(0L)
            if (offset >= state.downloadOffset && offset < state.downloadOffset + state.readyFromOffset) {
                return (state.downloadOffset + state.readyFromOffset) - offset
            }
            val now = System.currentTimeMillis()
            if (state.downloadOffset == offset && state.readyFromOffset == 0L &&
                now - lastAvailQueryMs < AVAIL_QUERY_INTERVAL_MS
            ) {
                return 0L
            }
            lastAvailQueryMs = now
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
                if (isProbe) {
                    Td.endProbe(fileId)
                }
            }
            runCatching { raf?.close() }
            raf = null
            rafPath = ""
        }
    }
}

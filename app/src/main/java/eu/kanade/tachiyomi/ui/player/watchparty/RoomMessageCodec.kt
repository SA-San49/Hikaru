package eu.kanade.tachiyomi.ui.player.watchparty

import kotlinx.serialization.json.Json

/**
 * Safe wire codec for room traffic.
 *
 * Every transport (relay WebSocket or WebRTC DataChannel) shares this codec:
 *  - unknown fields are ignored so peers on different app versions interoperate;
 *  - encoding never fails on valid Kotlin data;
 *  - decoding is fully guarded: a malformed, truncated or hostile frame yields
 *    `null` instead of an exception.
 */
object RoomMessageCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        isLenient = false
    }

    /** Largest accepted frame; protects against memory abuse from a rogue peer. */
    const val MAX_FRAME_BYTES = 64 * 1024

    fun encode(event: RoomEvent): String = json.encodeToString(RoomEvent.serializer(), event)

    fun decode(raw: String?): RoomEvent? {
        if (raw.isNullOrBlank()) return null
        if (raw.length > MAX_FRAME_BYTES) return null
        return try {
            json.decodeFromString(RoomEvent.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    /** Trims chat text and caps its length, keeping multi-byte emoji intact. */
    fun sanitizeChatText(raw: String): String {
        val collapsed = raw.trim().replace(Regex("""\s+"""), " ")
        if (collapsed.length <= MAX_CHAT_LENGTH) return collapsed
        return collapsed.take(MAX_CHAT_LENGTH)
    }

    fun sanitizeDisplayName(raw: String?): String {
        val name = raw?.trim().orEmpty().ifEmpty { "Guest" }
        val cleaned = name.replace(Regex("""[\u0000-\u001F\u007F]"""), "")
        return cleaned.take(MAX_NAME_LENGTH).ifBlank { "Guest" }
    }

    const val MAX_CHAT_LENGTH = 500
    const val MAX_NAME_LENGTH = 24
}

package eu.kanade.tachiyomi.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val assistantResponseJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val queryKeyPattern = Regex("""(?i)([?&]key=)[^&\s"'<>]+""")
private val authHeaderPattern = Regex("""(?i)(authorization\s*[:=]\s*)(?:[a-z][a-z0-9_-]*\s+)?[^\s,;"']+""")
private val bearerPattern = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
private val namedSecretPattern = Regex(
    """(?i)(\b(?:api[_-]?key|access[_-]?token|token|secret|password|key)\b\s*["']?\s*[=:]\s*["']?)[^\s,&;"'<>}]+""",
)
private val geminiKeyPattern = Regex("""\bAIza[0-9A-Za-z_-]{16,}\b""")
private val openAiKeyPattern = Regex("""\bsk-[A-Za-z0-9_-]{12,}\b""")

internal fun redactSecrets(value: String, apiKey: String? = null): String {
    var safe = value
    if (!apiKey.isNullOrBlank()) safe = safe.replace(apiKey, "[redacted]")
    safe = queryKeyPattern.replace(safe) { "${it.groupValues[1]}[redacted]" }
    safe = authHeaderPattern.replace(safe) { "${it.groupValues[1]}[redacted]" }
    safe = bearerPattern.replace(safe, "Bearer [redacted]")
    safe = namedSecretPattern.replace(safe) { "${it.groupValues[1]}[redacted]" }
    safe = geminiKeyPattern.replace(safe, "[redacted]")
    return openAiKeyPattern.replace(safe, "[redacted]")
}

internal fun describeHttpError(
    provider: String,
    code: Int,
    body: String?,
    apiKey: String? = null,
): String {
    val safeBody = redactSecrets(body.orEmpty(), apiKey)
        .replace(Regex("[\\r\\n]+"), " ")
        .trim()
    val providerDetail = runCatching {
        val root = assistantResponseJson.parseToJsonElement(safeBody).jsonObject
        extractErrorMessage(root["error"])
            ?: root["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf(String::isNotBlank)

    val hint = when (code) {
        400 -> "bad request/model name"
        401, 403 -> "API key invalid or lacks access"
        404 -> "model not found"
        429 -> "rate limit or quota exceeded"
        in 500..599 -> "provider outage"
        else -> null
    }
    val reason = hint ?: providerDetail?.take(200) ?: safeBody.take(200).ifBlank { "request failed" }
    val detail = if (hint != null && !providerDetail.isNullOrBlank() && !providerDetail.equals(hint, true)) {
        ": ${providerDetail.take(160)}"
    } else {
        ""
    }
    return "$provider error $code: $reason$detail"
}

internal data class OpenAiStreamChunk(
    val content: String? = null,
    val reasoning: String? = null,
    val error: String? = null,
)

internal fun parseOpenAiStreamChunk(data: String): OpenAiStreamChunk? {
    val root = runCatching { assistantResponseJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
    val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
    val delta = choice?.get("delta")?.let { runCatching { it.jsonObject }.getOrNull() }
    return OpenAiStreamChunk(
        content = delta?.get("content").textValue(),
        reasoning = delta?.get("reasoning_content").textValue(),
        error = root["error"]?.let(::extractErrorMessage),
    )
}

internal data class GeminiStreamChunk(
    val text: String = "",
    val blockedReason: String? = null,
    val error: String? = null,
)

internal fun parseGeminiStreamChunk(data: String): GeminiStreamChunk? {
    val root = runCatching { assistantResponseJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
    val feedback = root["promptFeedback"]?.let { runCatching { it.jsonObject }.getOrNull() }
    val blockReason = feedback?.get("blockReason").textValue()?.takeIf(String::isNotBlank)
    val candidates = root["candidates"] as? JsonArray ?: JsonArray(emptyList())
    val candidateObjects = candidates.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
    val text = candidateObjects.joinToString(separator = "") { candidate ->
        val parts = candidate["content"]
            ?.let { runCatching { it.jsonObject["parts"] as? JsonArray }.getOrNull() }
            .orEmpty()
        parts.mapNotNull { part ->
            runCatching { part.jsonObject["text"].textValue() }.getOrNull()
        }.joinToString(separator = "")
    }
    val finishReason = candidateObjects
        .mapNotNull { it["finishReason"].textValue() }
        .firstOrNull { it.equals("SAFETY", ignoreCase = true) || it.equals("RECITATION", ignoreCase = true) }
    val reason = blockReason ?: finishReason
    val error = root["error"]?.let(::extractErrorMessage)
    return GeminiStreamChunk(text, reason.takeIf { text.isBlank() }, error)
}

internal fun extractNonSseAssistantText(body: String): String? {
    val root = runCatching { assistantResponseJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null

    val openAiText = (root["choices"] as? JsonArray)
        ?.firstOrNull()
        ?.let { runCatching { it.jsonObject["message"]?.jsonObject?.get("content").textValue() }.getOrNull() }
    if (!openAiText.isNullOrBlank()) return openAiText

    val anthropicText = (root["content"] as? JsonArray)
        ?.mapNotNull { part -> runCatching { part.jsonObject["text"].textValue() }.getOrNull() }
        ?.joinToString(separator = "")
        ?.takeIf(String::isNotBlank)
    if (!anthropicText.isNullOrBlank()) return anthropicText

    val geminiText = (root["candidates"] as? JsonArray)
        ?.firstOrNull()
        ?.let { candidate ->
            runCatching {
                (candidate.jsonObject["content"]?.jsonObject?.get("parts") as? JsonArray)
                    ?.mapNotNull { part -> part.jsonObject["text"].textValue() }
                    ?.joinToString(separator = "")
            }.getOrNull()
        }
        ?.takeIf(String::isNotBlank)
    if (!geminiText.isNullOrBlank()) return geminiText

    return extractNonSseErrorMessage(body)
}

internal fun extractNonSseErrorMessage(body: String): String? {
    val root = runCatching { assistantResponseJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    return root["error"]?.let(::extractErrorMessage)?.takeIf(String::isNotBlank)
        ?: if (root["type"].textValue().equals("error", ignoreCase = true)) {
            root["message"].textValue()?.takeIf(String::isNotBlank)
        } else {
            null
        }
}

internal fun parseServerSentEventData(line: String): String? {
    if (!line.startsWith("data:")) return null
    return line.removePrefix("data:").trim().takeIf(String::isNotEmpty)
}

internal fun isDiagnosticTraceLine(line: String): Boolean =
    stackTraceLinePattern.containsMatchIn(line) ||
        line.contains("Caused by:") ||
        nativeTraceLinePattern.containsMatchIn(line)

private val stackTraceLinePattern = Regex("""(?:^|:\s)\s*(?:\\t)?at\s+\S+\(""")
private val nativeTraceLinePattern = Regex("""#\d+\s+pc\s+""")

private fun JsonElement?.textValue(): String? = when (this) {
    null -> null
    is JsonArray -> mapNotNull { element ->
        runCatching { element.jsonObject["text"].textValue() }.getOrNull()
    }.joinToString(separator = "")
    else -> runCatching { jsonPrimitive.contentOrNull }.getOrNull()
}

private fun extractErrorMessage(error: JsonElement?): String? = when (error) {
    null -> null
    else -> {
        val errorObject = runCatching { error.jsonObject }.getOrNull()
        errorObject?.get("message")?.textValue()
            ?: errorObject?.get("type")?.textValue()
            ?: error.textValue()
    }
}

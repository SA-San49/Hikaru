package eu.kanade.tachiyomi.data.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiResponseParsingTest {

    @Test
    fun `accepts compact and spaced SSE data and ignores keepalive lines`() {
        assertEquals("{\"message\":\"hello\"}", parseServerSentEventData("data: {\"message\":\"hello\"}"))
        assertEquals("{\"message\":\"hello\"}", parseServerSentEventData("data:{\"message\":\"hello\"}"))
        assertEquals("[DONE]", parseServerSentEventData("data:[DONE]"))
        assertNull(parseServerSentEventData(":"))
        assertNull(parseServerSentEventData("event: ping"))
        assertNull(parseServerSentEventData(""))
        assertNull(parseServerSentEventData("data:   "))
    }

    @Test
    fun `OpenAI stream parsing tolerates missing choices and captures reasoning`() {
        val noChoices = parseOpenAiStreamChunk("{\"id\":\"x\",\"usage\":{}}")
        assertNotNull(noChoices)
        assertNull(noChoices?.content)

        val reasoning = parseOpenAiStreamChunk(
            "{\"choices\":[{\"delta\":{\"reasoning_content\":\"Let me think\"}}]}",
        )
        assertEquals("Let me think", reasoning?.reasoning)
    }

    @Test
    fun `Gemini parser handles missing parts and concatenates all part text`() {
        val noParts = parseGeminiStreamChunk("{\"candidates\":[{\"content\":{}}]}")
        assertEquals("", noParts?.text)
        assertNull(noParts?.blockedReason)

        val multipleParts = parseGeminiStreamChunk(
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"},{\"text\":\" there\"}]}}]}",
        )
        assertEquals("Hello there", multipleParts?.text)

        val blocked = parseGeminiStreamChunk("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")
        assertEquals("SAFETY", blocked?.blockedReason)

        val error = parseGeminiStreamChunk("{\"error\":{\"message\":\"quota exceeded\"}}")
        assertEquals("quota exceeded", error?.error)
    }

    @Test
    fun `non SSE responses support OpenAI Anthropic Gemini and provider errors`() {
        assertEquals(
            "OpenAI reply",
            extractNonSseAssistantText("{\"choices\":[{\"message\":{\"content\":\"OpenAI reply\"}}]}"),
        )
        assertEquals(
            "Claude reply",
            extractNonSseAssistantText("{\"content\":[{\"type\":\"text\",\"text\":\"Claude reply\"}]}"),
        )
        assertEquals(
            "Gemini reply",
            extractNonSseAssistantText(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Gemini reply\"}]}}]}",
            ),
        )
        assertEquals(
            "Quota exceeded",
            extractNonSseAssistantText("{\"error\":{\"message\":\"Quota exceeded\"}}"),
        )
        assertEquals(
            "Anthropic unavailable",
            extractNonSseErrorMessage("{\"type\":\"error\",\"message\":\"Anthropic unavailable\"}"),
        )
    }

    @Test
    fun `HTTP errors include status hints and redact key and authorization secrets`() {
        val statuses = mapOf(
            400 to "bad request/model name",
            401 to "API key invalid or lacks access",
            403 to "API key invalid or lacks access",
            404 to "model not found",
            429 to "rate limit or quota exceeded",
            500 to "provider outage",
        )
        statuses.forEach { (code, expected) ->
            assertTrue(describeHttpError("Gemini", code, null).contains(expected))
            assertTrue(
                describeHttpError("Gemini", code, "{\"error\":{\"message\":\"provider detail\"}}")
                    .contains(expected),
            )
        }

        val key = "AIzaSyDUMMY_KEY_0123456789abcdef"
        val token = "sk-test_0123456789abcdef"
        val error = describeHttpError(
            "OpenAI",
            401,
            "{\"error\":{\"message\":\"Invalid key=$key; Authorization: Bearer $token\"}}",
            apiKey = key,
        )
        assertTrue(error.contains("OpenAI error 401: API key invalid or lacks access"))
        assertFalse(error.contains(key))
        assertFalse(error.contains(token))
        assertFalse(error.contains("Bearer sk-"))

        val queryUrl = redactSecrets("https://example.test/models?key=$key&alt=sse", key)
        assertFalse(queryUrl.contains(key))
        assertTrue(queryUrl.contains("key=[redacted]"))

        val jsonSecret = redactSecrets("{\"api_key\":\"do-not-show-this-key\"}")
        assertFalse(jsonSecret.contains("do-not-show-this-key"))

        val basicAuth = redactSecrets("Authorization: Basic dXNlcjpwYXNzd29yZA==")
        assertFalse(basicAuth.contains("dXNlcjpwYXNzd29yZA=="))
    }

    @Test
    fun `log classifier recognizes Android logcat stack and native frames`() {
        assertTrue(
            isDiagnosticTraceLine(
                "09-23 13:31:20.494 22205 22205 E GlobalExceptionHandler: \tat ww2.a(Unknown Source:2)",
            ),
        )
        assertTrue(isDiagnosticTraceLine("Caused by: java.lang.IllegalStateException: broken"))
        assertTrue(isDiagnosticTraceLine("#00 pc 0000000000001234 /system/lib64/libc.so"))
        assertFalse(isDiagnosticTraceLine("09-23 13:31:20.494 22205 22205 I Activity: resumed"))
    }

    @Test
    fun `assistant intent classifier avoids substring false positives`() {
        assertTrue(AssistantIntentClassifier.classify("hello").isEmpty())
        val genericQuestion = AssistantIntentClassifier.classify("I have a question")
        assertFalse(AssistantContextIntent.DIAGNOSTICS in genericQuestion)
        assertFalse(AssistantContextIntent.COMMUNITY in genericQuestion)
        assertFalse(AssistantContextIntent.LIBRARY in genericQuestion)

        val diagnostic = AssistantIntentClassifier.classify("video shows a black screen")
        assertTrue(AssistantContextIntent.DIAGNOSTICS in diagnostic)
        assertTrue(AssistantContextIntent.COMMUNITY in diagnostic)

        assertTrue(AssistantContextIntent.LIBRARY in AssistantIntentClassifier.classify("show my library"))
        assertTrue(AssistantContextIntent.HISTORY in AssistantIntentClassifier.classify("summarize my recent watch history"))
        val browse = AssistantIntentClassifier.classify("show visible sources")
        assertTrue(AssistantContextIntent.BROWSE in browse)
        assertFalse(AssistantContextIntent.COMMUNITY in browse)
        assertTrue(AssistantContextIntent.COMMUNITY in AssistantIntentClassifier.classify("is this a working extension"))
        assertTrue(AssistantContextIntent.SCHEDULE in AssistantIntentClassifier.classify("what is airing this week"))
        assertTrue(AssistantContextIntent.FEED in AssistantIntentClassifier.classify("summarize saved feeds"))
    }
}

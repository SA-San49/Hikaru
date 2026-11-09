package eu.kanade.tachiyomi.data.ai

internal data class GeminiAttemptSummary(
    val failureMessage: String? = null,
    val httpStatus: Int? = null,
    val outcome: String = "empty",
    private val emptyModel: String? = null,
) {
    fun afterFailure(message: String, status: Int?, outcome: String): GeminiAttemptSummary = copy(
        failureMessage = message,
        httpStatus = status,
        outcome = outcome,
        emptyModel = null,
    )

    fun afterEmptyResponse(model: String): GeminiAttemptSummary = copy(
        failureMessage = null,
        httpStatus = null,
        outcome = "empty",
        emptyModel = model,
    )

    fun finalFailureMessage(apiKey: String): String {
        val failure = failureMessage?.takeIf(String::isNotBlank)
        if (failure != null) return "All Gemini keys/models failed. Last failure: $failure"

        val detail = emptyModel?.let {
            "Gemini model '${redactSecrets(it, apiKey).take(80)}' returned no streamed text."
        } ?: "No Gemini key/model attempt returned a response. Verify the API key and model."
        return "All Gemini keys/models failed. $detail"
    }
}

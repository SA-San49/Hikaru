package eu.kanade.tachiyomi.data.ai

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.ai.everythingmoe.EverythingMoeScraper
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import java.io.BufferedReader
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.feature.airingschedule.ScheduleDataRefreshWorker
import mihon.feature.airingschedule.SchedulePreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.history.interactor.GetActivityLog
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.ActivityLog
import tachiyomi.domain.source.interactor.GetFeedSavedSearchCategories
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AiManager(
    private val context: Context,
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val aiPreferences: AiPreferences = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getLibraryAnime: tachiyomi.domain.anime.interactor.GetLibraryAnime = Injekt.get(),
    private val json: Json = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    private val getActivityLog: GetActivityLog = Injekt.get(),
    private val getFeedCategories: GetFeedSavedSearchCategories = Injekt.get(),
    private val getFeedSavedSearches: GetFeedSavedSearchGlobal = Injekt.get(),
    private val getSavedSearches: GetSavedSearchGlobalFeed = Injekt.get(),
    private val schedulePreferences: SchedulePreferences = Injekt.get(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val keyFailures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val chatInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val statsInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val requestStatsMutex = Mutex()

    init {
        // These persisted values can only have been left behind by a previous process.
        aiPreferences.isRequestPending().set(false)
        aiPreferences.isCircuitBreakerTripped().set(false)
    }

    private val everythingMoeScraper: EverythingMoeScraper by lazy {
        EverythingMoeScraper(context, networkHelper, json)
    }

    private val MAP_VERSION = 132

    fun resetCircuitBreaker() {
        aiPreferences.isCircuitBreakerTripped().set(false)
        aiPreferences.isRequestPending().set(false)
    }

    suspend fun chatWithAssistant(query: String, history: List<ChatMessage>): String? {
        val result = StringBuilder()
        chatWithAssistantStream(query, history).collect { result.append(it) }
        return result.toString().ifBlank { null }
    }

    fun chatWithAssistantStream(query: String, history: List<ChatMessage>): Flow<String> = flow {
        if (!chatInFlight.compareAndSet(false, true)) {
            emit("Still working on your previous message...")
            return@flow
        }

        try {
            if (!aiPreferences.enableAi().get()) {
                emit("AI features are turned off. Enable 'AI Integration' in Settings > Advanced Analytics (AI Config) to use the assistant.")
                return@flow
            }
            if (!aiPreferences.enableAiAssistant().get()) {
                emit("The AI Assistant is turned off. Enable 'AI Assistant' in Settings > Advanced Analytics (AI Config) to chat here.")
                return@flow
            }

            val engine = aiPreferences.aiEngine().get().trim().lowercase(Locale.ROOT)
            val apiKey = when (engine) {
                "gemini" -> aiPreferences.geminiApiKey().get()
                "deepseek" -> aiPreferences.deepseekApiKey().get()
                "opencode" -> aiPreferences.opencodeApiKey().get()
                "literouter" -> aiPreferences.literouterApiKey().get()
                "tokenreply" -> aiPreferences.tokenreplyApiKey().get()
                "openai" -> aiPreferences.openaiApiKey().get()
                "anthropic" -> aiPreferences.anthropicApiKey().get()
                "openrouter" -> aiPreferences.openrouterApiKey().get()
                "together" -> aiPreferences.togetherApiKey().get()
                "groq" -> aiPreferences.groqApiKey().get()
                else -> {
                    emit("Unsupported AI provider '$engine'. Choose a provider in Settings > Advanced Analytics (AI Config).")
                    return@flow
                }
            }.ifBlank {
                emit("Please set an API Key in Settings > AI Integration")
                return@flow
            }

            val customPrompt = aiPreferences.aiSystemPrompt().get()
            val defaultSystemInstruction = """
                You are the Hikaru Diagnostic Assistant. Answer the user's question clearly and be honest about uncertainty.
                Hikaru may include local diagnostics, library, watch history, visible browse sources, saved feed setup,
                or cached schedule and community-directory context blocks. Treat those blocks as read-only snapshots, not live access.
                Use only the blocks provided; never claim to browse the internet, refresh feeds, change settings, or perform
                actions that were not actually completed. Do not expose secrets or infer personal information not present.
                Prefer concise explanations and bullet lists. Do not use Markdown tables.
            """.trimIndent()
            val systemInstruction = if (customPrompt.isNotBlank()) customPrompt else defaultSystemInstruction
            val messages = createBoundedRequestMessages(query, history)
            val model = selectedModel(engine)
            val metrics = AiRequestDiagnostics(engine = engine, model = model)
            try {
                val response: Flow<String> = when (engine) {
                    "gemini" -> callGeminiStream(messages, apiKey, systemInstruction, withTools = true)
                    "deepseek" -> callOpenAiCompatibleEndpointStream(
                        "https://api.deepseek.com/chat/completions",
                        model,
                        messages,
                        apiKey,
                        systemInstruction,
                        withTools = true,
                        providerName = "DeepSeek",
                    )
                    "opencode" -> callOpenAiCompatibleEndpointStream(
                        "https://opencode.ai/zen/v1/chat/completions",
                        model,
                        messages,
                        apiKey,
                        systemInstruction,
                        withTools = true,
                        providerName = "OpenCode Zen",
                    )
                    "literouter" -> callOpenAiCompatibleEndpointStream(
                        "https://api.literouter.com/v1/chat/completions",
                        model,
                        messages,
                        apiKey,
                        systemInstruction,
                        withTools = true,
                        providerName = "LiteRouter",
                    )
                    "tokenreply" -> callOpenAiCompatibleEndpointStream(
                        "https://api.tokenreply.com/v1/chat/completions",
                        model,
                        messages,
                        apiKey,
                        systemInstruction,
                        withTools = true,
                        providerName = "TokenReply",
                    )
                    "openai" -> callOpenAiStream(messages, apiKey, systemInstruction, withTools = true)
                    "anthropic" -> callAnthropicStream(messages, apiKey, systemInstruction, withTools = true)
                    "openrouter" -> callOpenRouterStream(messages, apiKey, systemInstruction, withTools = true)
                    "together" -> callTogetherStream(messages, apiKey, systemInstruction, withTools = true)
                    "groq" -> callGroqStream(messages, apiKey, systemInstruction, withTools = true)
                    else -> error("Provider validation and dispatch became inconsistent")
                }
                response.flowOn(metrics).collect { chunk ->
                    val safeChunk = redactSecrets(chunk, apiKey)
                    if (safeChunk.isNotEmpty()) {
                        metrics.charsEmitted.addAndGet(safeChunk.length)
                        emit(safeChunk)
                    }
                }
                if (metrics.charsEmitted.get() == 0) {
                    metrics.outcome = "empty"
                    val safeModel = redactSecrets(model, apiKey).take(80)
                    emit(
                        "The AI provider ($engine, model $safeModel) returned an empty response. " +
                            "This can happen when the model only produced hidden reasoning output, " +
                            "the request was filtered, or the quota is exhausted. Try another model " +
                            "in Settings > AI Integration.",
                    )
                } else if (metrics.outcome == "ok") {
                    recordRequestSuccess()
                }
            } catch (e: CancellationException) {
                metrics.outcome = if (metrics.httpStatus?.let { it in 400..599 } == true) "http_error" else "exception"
                throw e
            } catch (e: Exception) {
                metrics.outcome = if (metrics.httpStatus?.let { it in 400..599 } == true) "http_error" else "exception"
                val detail = redactSecrets(e.message.orEmpty(), apiKey).take(200).ifBlank { e.javaClass.simpleName }
                logcat(LogPriority.ERROR, tag = "AiAssistant") { "AI request failed: $detail" }
                emit("AI request failed: $detail")
            } finally {
                logRequestMetrics(metrics, apiKey)
            }
        } finally {
            chatInFlight.set(false)
            aiPreferences.isRequestPending().set(false)
        }
    }

    private fun selectedModel(engine: String): String = when (engine) {
        "gemini" -> aiPreferences.geminiModel().get().ifBlank { "gemini-flash-latest" }
        "deepseek" -> aiPreferences.deepseekModel().get().ifBlank { "deepseek-chat" }
        "opencode" -> aiPreferences.opencodeModel().get().ifBlank { "deepseek-v4-flash-free" }
        "literouter" -> aiPreferences.literouterModel().get().ifBlank { "deepseek-v4-flash-free" }
        "tokenreply" -> aiPreferences.tokenreplyModel().get().ifBlank { "deepseek-v4-flash-free" }
        "openai" -> aiPreferences.openaiModel().get().ifBlank { "gpt-4o-mini" }
        "anthropic" -> aiPreferences.anthropicModel().get().ifBlank { "claude-3-5-sonnet-20241022" }
        "openrouter" -> aiPreferences.openrouterModel().get().ifBlank { "openai/gpt-4o-mini" }
        "together" -> aiPreferences.togetherModel().get().ifBlank { "meta-llama/Llama-3.3-70B-Instruct-Turbo" }
        "groq" -> aiPreferences.groqModel().get().ifBlank { "llama-3.3-70b-versatile" }
        else -> "unknown"
    }

    private fun createBoundedRequestMessages(query: String, history: List<ChatMessage>): List<ChatMessage> {
        val queryLimit = 8_000
        val boundedQuery = if (query.length <= queryLimit) query else {
            query.take(queryLimit) + "\n[Message truncated to protect request size.]"
        }
        var remainingCharacters = (24_000 - boundedQuery.length).coerceAtLeast(0)
        val recentMessages = mutableListOf<ChatMessage>()
        for (message in history.takeLast(24).asReversed()) {
            if (remainingCharacters <= 0) break
            if (message.role !in setOf("user", "assistant", "model") || message.content.isBlank()) continue
            val content = message.content.take(remainingCharacters)
            remainingCharacters -= content.length
            recentMessages.add(ChatMessage(role = message.role, content = content))
        }
        recentMessages.reverse()
        return recentMessages + ChatMessage(role = "user", content = boundedQuery)
    }

    private suspend fun getLibrarySummary(): String {
        return try {
            val library = getLibraryAnime.await()
            if (library.isEmpty()) return "Library is empty."
            val inProgressCount = library.count { it.seenCount > 0 }
            buildString {
                appendLine("${library.size} titles total; $inProgressCount with watch progress; ${library.size - inProgressCount} not started.")
                appendLine("A sample of the first ${minOf(40, library.size)} titles:")
                library.take(40).forEach { item ->
                    val title = item.anime.title.replace(Regex("[\\r\\n]+"), " ").take(120)
                    appendLine("- $title [status ${item.anime.status}, watched ${item.seenCount} episodes]")
                }
            }.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, tag = "AiAssistant") {
                "Unable to load library context (${e.javaClass.simpleName})."
            }
            ""
        }
    }

    suspend fun getStatisticsAnalysis(statsSummary: String): String? {
        val result = StringBuilder()
        getStatisticsAnalysisStream(statsSummary).collect { result.append(it) }
        return result.toString().ifBlank { null }
    }

    fun getStatisticsAnalysisStream(statsSummary: String): Flow<String> = flow {
        if (!aiPreferences.enableAi().get() || !aiPreferences.enableAiStatistics().get()) return@flow
        if (!statsInFlight.compareAndSet(false, true)) return@flow

        var metrics: AiRequestDiagnostics? = null
        var requestApiKey: String? = null
        try {
            val engine = aiPreferences.aiEngine().get().trim().lowercase(Locale.ROOT)
            val apiKey = when (engine) {
                "gemini" -> aiPreferences.geminiApiKey().get()
                "deepseek" -> aiPreferences.deepseekApiKey().get()
                "opencode" -> aiPreferences.opencodeApiKey().get()
                "literouter" -> aiPreferences.literouterApiKey().get()
                "tokenreply" -> aiPreferences.tokenreplyApiKey().get()
                "openai" -> aiPreferences.openaiApiKey().get()
                "anthropic" -> aiPreferences.anthropicApiKey().get()
                "openrouter" -> aiPreferences.openrouterApiKey().get()
                "together" -> aiPreferences.togetherApiKey().get()
                else -> aiPreferences.groqApiKey().get()
            }.ifBlank { return@flow }
            requestApiKey = apiKey

            val model = selectedModel(engine)
            val requestMetrics = AiRequestDiagnostics(engine = engine, model = model)
            metrics = requestMetrics
            val prompt = """
                Generate a 'System Behavioral Profile' based on the following data.

                DATA INPUT:
                $statsSummary

                REPORT STRUCTURE (STRICTLY NO TABLES):
                - **User Classification**: Technical archetype (e.g., 'High-Volume Archivist').
                - **Temporal Analysis**: Watch habit patterns.
                - **Source Integrity**: Distribution across extensions.
                - **Strategic Recommendations**: 3-5 anime titles based on data patterns.

                Constraint: Use bullet points. Do NOT use Markdown tables.
            """.trimIndent()
            val messages = listOf(ChatMessage(role = "user", content = prompt))
            val systemInstruction = "You are a senior behavioral data analyst."
            val response = when (engine) {
                "gemini" -> callGeminiStream(messages, apiKey, systemInstruction)
                "deepseek" -> callOpenAiCompatibleEndpointStream(
                    "https://api.deepseek.com/chat/completions",
                    model,
                    messages,
                    apiKey,
                    systemInstruction,
                )
                "opencode" -> callOpenAiCompatibleEndpointStream(
                    "https://opencode.ai/zen/v1/chat/completions",
                    model,
                    messages,
                    apiKey,
                    systemInstruction,
                )
                "literouter" -> callOpenAiCompatibleEndpointStream(
                    "https://api.literouter.com/v1/chat/completions",
                    model,
                    messages,
                    apiKey,
                    systemInstruction,
                )
                "tokenreply" -> callOpenAiCompatibleEndpointStream(
                    "https://api.tokenreply.com/v1/chat/completions",
                    model,
                    messages,
                    apiKey,
                    systemInstruction,
                )
                "openai" -> callOpenAiStream(messages, apiKey, systemInstruction)
                "anthropic" -> callAnthropicStream(messages, apiKey, systemInstruction)
                "openrouter" -> callOpenRouterStream(messages, apiKey, systemInstruction)
                "together" -> callTogetherStream(messages, apiKey, systemInstruction)
                "groq" -> callGroqStream(messages, apiKey, systemInstruction)
                else -> error("Unsupported AI provider '$engine'.")
            }
            try {
                response.flowOn(requestMetrics).collect { chunk ->
                    val safeChunk = redactSecrets(chunk, apiKey)
                    if (safeChunk.isNotEmpty()) {
                        requestMetrics.charsEmitted.addAndGet(safeChunk.length)
                        emit(safeChunk)
                    }
                }
                if (requestMetrics.charsEmitted.get() == 0 && requestMetrics.outcome == "ok") {
                    requestMetrics.outcome = "empty"
                } else if (requestMetrics.charsEmitted.get() > 0 && requestMetrics.outcome == "ok") {
                    recordRequestSuccess()
                }
            } catch (e: CancellationException) {
                requestMetrics.outcome = "exception"
                throw e
            } catch (e: Exception) {
                requestMetrics.outcome = if (requestMetrics.httpStatus?.let { it in 400..599 } == true) {
                    "http_error"
                } else {
                    "exception"
                }
                val detail = redactSecrets(e.message.orEmpty(), apiKey).take(200).ifBlank { e.javaClass.simpleName }
                logcat(LogPriority.ERROR, tag = "AiAssistant") { "AI statistics request failed: $detail" }
                emit("AI statistics request failed: $detail")
            }
        } finally {
            metrics?.let { logRequestMetrics(it, requestApiKey) }
            statsInFlight.set(false)
        }
    }

    private suspend fun recordRequestSuccess() = withIOContext {
        requestStatsMutex.withLock {
            val count = aiPreferences.hourlyAiRequestCount().get()
            aiPreferences.hourlyAiRequestCount().set(count + 1)
            aiPreferences.lastAiRequestTime().set(System.currentTimeMillis())
        }
    }

    private suspend fun getSanitizedLogs(): String = withIOContext {
        try {
            val logLines = readLogcatLines(maxLines = 500, priority = "W").toMutableList()
            if (logLines.size < 10) {
                val storageManager = Injekt.get<StorageManager>()
                val internalLogDir = File(context.cacheDir, "logs")
                val logDir = storageManager.getLogsDirectory() ?: UniFile.fromFile(internalLogDir)
                val latestLog = logDir?.listFiles()
                    ?.filter { it.isFile && it.name?.endsWith(".log") == true }
                    ?.maxByOrNull { it.lastModified() }
                if (latestLog != null) {
                    latestLog.openInputStream().bufferedReader().use { reader ->
                        logLines.addAll(readLastLines(reader, maxLines = 500))
                    }
                }
            }

            if (logLines.isEmpty()) {
                return@withIOContext "Diagnostic engine active. No logs available for analysis in this environment."
            }

            val pinnedBlocks = mutableListOf<List<String>>()
            val currentBlock = mutableListOf<String>()
            val packagePattern = Regex(
                "(eu\\.kanade|app\\.hikaru|Hikaru|GlobalExceptionHandler|okhttp|kotlinx\\.serialization|mpv|ffmpeg|AndroidRuntime|libc|DEBUG|System\\.err|XLog|FileUtils|ActivityThread|InputDispatcher)",
            )
            val piiRedaction = Regex(
                "(?i)(?:authorization|cookie|set-cookie):\\s*[^\\n\\r]+|(?:[a-zA-Z0-9+_.-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})|(?:auth|token|key|password|secret|sid|session)=[a-zA-Z0-9._-]+",
            )
            val traceTrigger = Regex("(FATAL EXCEPTION|Native crash|SIGSEGV|SIGABRT|mpv: error|Check failed)", RegexOption.IGNORE_CASE)
            val configuredApiKeys = listOf(
                aiPreferences.geminiApiKey().get(),
                aiPreferences.deepseekApiKey().get(),
                aiPreferences.opencodeApiKey().get(),
                aiPreferences.literouterApiKey().get(),
                aiPreferences.tokenreplyApiKey().get(),
                aiPreferences.openaiApiKey().get(),
                aiPreferences.anthropicApiKey().get(),
                aiPreferences.openrouterApiKey().get(),
                aiPreferences.togetherApiKey().get(),
                aiPreferences.groqApiKey().get(),
            ).flatMap { it.split(",") }.map(String::trim).filter(String::isNotBlank)
            val sanitizedResult = mutableListOf<String>()
            var lastLine = ""
            var repeatCount = 0
            fun appendRepeatSummary() {
                if (repeatCount > 0) sanitizedResult.add("... [TRUNCATED] repeated $repeatCount times ...")
                repeatCount = 0
            }

            for (line in logLines) {
                val withConfiguredKeysRedacted = configuredApiKeys.fold(line) { current, key ->
                    redactSecrets(current, key)
                }
                val sanitizedLine = redactSecrets(withConfiguredKeysRedacted).replace(piiRedaction, "[REDACTED]")
                val isRelevantPackage = packagePattern.containsMatchIn(sanitizedLine) ||
                    sanitizedLine.contains("Unknown Source") || sanitizedLine.contains("SourceFile")
                if (traceTrigger.containsMatchIn(sanitizedLine) || (isDiagnosticTraceLine(sanitizedLine) && currentBlock.isNotEmpty())) {
                    appendRepeatSummary()
                    lastLine = ""
                    currentBlock.add(sanitizedLine)
                    if (currentBlock.size > 80) {
                        pinnedBlocks.add(currentBlock.toList())
                        currentBlock.clear()
                    }
                } else {
                    if (currentBlock.isNotEmpty()) {
                        pinnedBlocks.add(currentBlock.toList())
                        currentBlock.clear()
                    }
                    if (isRelevantPackage) {
                        if (sanitizedLine == lastLine) {
                            repeatCount++
                        } else {
                            appendRepeatSummary()
                            sanitizedResult.add(sanitizedLine)
                            lastLine = sanitizedLine
                        }
                    } else {
                        appendRepeatSummary()
                        lastLine = ""
                    }
                }
            }
            if (currentBlock.isNotEmpty()) pinnedBlocks.add(currentBlock.toList())
            appendRepeatSummary()

            buildString {
                if (pinnedBlocks.isNotEmpty()) {
                    appendLine("### CRITICAL SYSTEM EVENTS (PINNED):")
                    pinnedBlocks.takeLast(2).forEach { block ->
                        appendLine(block.joinToString("\n"))
                        appendLine("---")
                    }
                }
                appendLine("### SYSTEM LOG TAIL:")
                append(sanitizedResult.takeLast(100).joinToString("\n"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, tag = "AiAssistant") {
                "Diagnostic log retrieval failed (${e.javaClass.simpleName})."
            }
            ""
        }
    }

    private fun readLastLines(reader: BufferedReader, maxLines: Int): List<String> {
        val limit = maxLines.coerceAtLeast(1)
        val lines = java.util.ArrayDeque<String>(limit)
        while (true) {
            val line = reader.readLine() ?: break
            if (lines.size == limit) lines.removeFirst()
            lines.addLast(line)
        }
        return lines.toList()
    }

    private suspend fun readLogcatLines(maxLines: Int, priority: String): List<String> {
        return try {
            withTimeoutOrNull(3_000L) {
                coroutineScope {
                    val process = Runtime.getRuntime().exec(
                        arrayOf("logcat", "-d", "-b", "main", "-t", maxLines.toString(), "*:$priority"),
                    )
                    try {
                        val output = async(Dispatchers.IO) {
                            process.inputStream.bufferedReader().useLines { lines -> lines.toList() }
                        }
                        val exited = withContext(Dispatchers.IO) {
                            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                        }
                        if (exited) {
                            output.await()
                        } else {
                            logcat(LogPriority.WARN, tag = "AiAssistant") {
                                "Logcat did not finish within the diagnostic timeout."
                            }
                            emptyList()
                        }
                    } finally {
                        process.destroy()
                        if (process.isAlive) process.destroyForcibly()
                    }
                }
            } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, tag = "AiAssistant") {
                "Unable to read logcat (${e.javaClass.simpleName})."
            }
            emptyList()
        }
    }

    private fun getAppMap(): String {
        val currentVersion = BuildConfig.VERSION_CODE
        val stalenessWarning = if (currentVersion != MAP_VERSION) {
            "[STALENESS_WARNING]: Navigation map version ($MAP_VERSION) differs from App Version ($currentVersion). Paths may be shifted.\n"
        } else ""

        return stalenessWarning + """
            - General: Settings > General
            - Appearance: Settings > Appearance (Theme, Monet, Dark Mode)
            - Library: Settings > Library (Update intervals, Columns)
            - Player: Settings > Player (Shaders/Anime4K, Orientation, Subtitles, External Player)
            - Downloads: Settings > Downloads (Threads, Cache)
            - Tracking: Settings > Tracking (Anilist, MAL)
            - Advanced: Settings > Advanced (Log viewer, Cache, Database)
            - Analytics: Settings > Advanced Analytics (AI Config)
        """.trimIndent()
    }

    private suspend fun getInstalledExtensions(): List<eu.kanade.tachiyomi.extension.model.Extension.Installed> {
        if (!extensionManager.isInitialized.value) {
            val initialized = withTimeoutOrNull(2_000) {
                extensionManager.isInitialized.first { it }
            }
            if (initialized == null) {
                logcat(LogPriority.WARN, tag = "AiAssistant") {
                    "Timed out waiting for installed extensions during context collection."
                }
                return emptyList()
            }
        }
        return extensionManager.installedExtensionsFlow.value
    }

    private suspend fun getExtensionStatusSummary(
        installed: List<eu.kanade.tachiyomi.extension.model.Extension.Installed>,
    ): String {
        if (installed.isEmpty()) {
            val sources = try {
                sourceManager.getVisibleOnlineSources()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, tag = "AiAssistant") {
                    "Unable to load visible source context (${e.javaClass.simpleName})."
                }
                return ""
            }
            return if (sources.isEmpty()) {
                "No extensions or visible online sources are currently loaded."
            } else {
                "Visible online sources in Hikaru:\n" + sources.take(40).joinToString("\n") { source ->
                    "- ${source.name.take(80)} (${source.lang})"
                }
            }
        }
        return installed.take(30).joinToString("\n") { extension ->
            val sourceNames = extension.sources.take(10).joinToString(", ") { source -> source.name.take(60) }
            "- ${extension.name.take(80)} v${extension.versionName} [sources: $sourceNames; obsolete: ${extension.isObsolete}; update available: ${extension.hasUpdate}]"
        }
    }

    private suspend fun getRecentHistorySummary(): String {
        return try {
            val history = getHistory.awaitRecent(30)
            if (history.isEmpty()) return "No recent watch history is available."
            val formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
            buildString {
                appendLine("Most recent watched titles (up to ${history.size} entries):")
                history.forEach { entry ->
                    val title = entry.title.replace(Regex("[\\r\\n]+"), " ").take(120)
                    val watchedAt = entry.seenAt?.toInstant()
                        ?.atZone(ZoneId.systemDefault())
                        ?.format(formatter)
                        ?: "time unavailable"
                    appendLine("- $title — episode ${entry.episodeNumber} — $watchedAt")
                }
            }.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, tag = "AiAssistant") {
                "Unable to load watch history context (${e.javaClass.simpleName})."
            }
            ""
        }
    }

    private suspend fun getBrowseSummary(): String {
        return try {
            val sources = if (sourceManager.isInitialized.value) {
                sourceManager.getVisibleCatalogueSources().take(30)
            } else {
                emptyList()
            }
            val activityCounts = getActivityLog.awaitCountsByPeriod(Date(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000))
            buildString {
                if (sources.isEmpty()) {
                    appendLine("Visible catalogue sources are not currently loaded or configured.")
                } else {
                    appendLine("Visible catalogue sources (up to ${sources.size}):")
                    sources.forEach { source ->
                        appendLine("- ${source.name.take(80)} (${source.lang})")
                    }
                }
                val recentActivity = activityCounts.entries
                    .sortedByDescending { it.value }
                    .take(10)
                if (recentActivity.isNotEmpty()) {
                    appendLine("Aggregate source activity in the last 30 days (not live feed contents):")
                    recentActivity.forEach { (key, count) ->
                        val sourceName = runCatching { sourceManager.getOrStub(key.first).name }
                            .getOrDefault("Source ${key.first}")
                        val event = when (key.second) {
                            ActivityLog.TYPE_OPEN -> "opened"
                            ActivityLog.TYPE_PLAY -> "played"
                            ActivityLog.TYPE_COMPLETE -> "completed"
                            ActivityLog.TYPE_FEED_UPDATE -> "feed updates"
                            ActivityLog.TYPE_FETCH -> "fetched"
                            else -> "activity"
                        }
                        appendLine("- ${sourceName.take(80)}: $count $event")
                    }
                }
                append("Summary generated ${DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()).format(Instant.now().atZone(ZoneId.systemDefault()))}.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, tag = "AiAssistant") {
                "Unable to load browse context (${e.javaClass.simpleName})."
            }
            ""
        }
    }

    private suspend fun getScheduleSummary(): String {
        return try {
            val cache = ScheduleDataRefreshWorker.readCache(context)
                ?: return "No local schedule cache is available yet. Open the schedule screen and refresh it to populate the cache; this assistant request will not fetch schedule data."
            val includeAdult = schedulePreferences.showAdultContent().get()
            val entries = cache.entries.asSequence()
                .filter { it.airingAt >= System.currentTimeMillis() / 1000L }
                .filter { includeAdult || !it.isAdult }
                .sortedBy { it.airingAt }
                .take(20)
                .toList()
            if (entries.isEmpty()) return "The local schedule cache contains no upcoming entries visible with the current content settings."

            val language = runCatching {
                SchedulePreferences.TitleLanguage.valueOf(cache.titleLanguage.orEmpty())
            }.getOrDefault(SchedulePreferences.TitleLanguage.USER_PREFERRED)
            val formatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm", Locale.getDefault())
            val fetchedAt = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
                .format(Instant.ofEpochMilli(cache.fetchedAt).atZone(ZoneId.systemDefault()))
            buildString {
                appendLine("Local cache fetched $fetchedAt; entries shown are upcoming and may be stale:")
                entries.forEach { entry ->
                    val airingAt = formatter.format(Instant.ofEpochSecond(entry.airingAt).atZone(ZoneId.systemDefault()))
                    appendLine("- ${entry.displayTitle(language).take(120)} — episode ${entry.episode} — $airingAt")
                }
            }.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, tag = "AiAssistant") {
                "Unable to load airing schedule context (${e.javaClass.simpleName})."
            }
            ""
        }
    }

    private suspend fun getSavedFeedSummary(): String {
        return try {
            val categories = getFeedCategories.await().sortedBy { it.order }.take(10)
            if (categories.isEmpty()) return "No saved feed categories are configured."
            buildString {
                var configuredFeedCount = 0
                for (category in categories) {
                    val feeds = getFeedSavedSearches.await(category.id).take(20)
                    if (feeds.isEmpty()) continue
                    val savedSearchesById = getSavedSearches.await(category.id).associateBy { it.id }
                    appendLine("${category.name.take(80)}:")
                    feeds.forEach { feed ->
                        val sourceName = runCatching { sourceManager.getOrStub(feed.source).name }
                            .getOrDefault("Source ${feed.source}")
                        val type = FeedSavedSearch.Type.from(feed.type).name
                        val savedSearch = feed.savedSearch?.let(savedSearchesById::get)
                        val savedQuery = savedSearch?.query
                        val details = when {
                            savedSearch == null -> type
                            savedQuery.isNullOrBlank() -> "$type: ${savedSearch.name.take(80)}"
                            else -> "$type: ${savedSearch.name.take(80)} — query ${savedQuery.take(120)}"
                        }
                        appendLine("- ${sourceName.take(80)} — $details")
                        configuredFeedCount++
                    }
                }
                if (configuredFeedCount == 0) {
                    append("Saved categories exist, but no global feeds are configured. Live feed results are not stored in this context.")
                } else {
                    append("These are saved feed definitions only; no live results were fetched.")
                }
            }.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, tag = "AiAssistant") {
                "Unable to load saved feed context (${e.javaClass.simpleName})."
            }
            ""
        }
    }

    private suspend fun buildToolContext(lastQuery: String): String {
        return try {
            coroutineScope {
                val intents = AssistantIntentClassifier.classify(lastQuery)
                val needsExtensions = AssistantContextIntent.EXTENSION_STATUS in intents ||
                    (AssistantContextIntent.COMMUNITY in intents && aiPreferences.aiAssistantEverythingMoe().get())
                val extensionsDeferred = if (needsExtensions) {
                    async {
                        loadContextSource("extensions", 3_000L) { getInstalledExtensions() }
                    }
                } else {
                    null
                }
                val logsDeferred = if (
                    AssistantContextIntent.DIAGNOSTICS in intents && aiPreferences.aiAssistantLogs().get()
                ) {
                    async {
                        loadContextSource("diagnostic logs", 4_000L) { getSanitizedLogs() }
                    }
                } else {
                    null
                }
                val libraryDeferred = if (
                    AssistantContextIntent.LIBRARY in intents && aiPreferences.aiAssistantLibrary().get()
                ) {
                    async {
                        loadContextSource("library", 3_000L) { getLibrarySummary() }
                    }
                } else {
                    null
                }
                val extensionStatusDeferred = if (AssistantContextIntent.DIAGNOSTICS in intents) {
                    async {
                        loadContextSource("extension status", 3_000L) {
                            getExtensionStatusSummary(extensionsDeferred?.await().orEmpty())
                        }
                    }
                } else {
                    null
                }
                val historyDeferred = if (
                    AssistantContextIntent.HISTORY in intents && aiPreferences.aiAssistantHistory().get()
                ) {
                    async { loadContextSource("watch history", 3_000L) { getRecentHistorySummary() } }
                } else {
                    null
                }
                val browseDeferred = if (
                    AssistantContextIntent.BROWSE in intents && aiPreferences.aiAssistantBrowse().get()
                ) {
                    async { loadContextSource("browse", 3_000L) { getBrowseSummary() } }
                } else {
                    null
                }
                val scheduleDeferred = if (
                    AssistantContextIntent.SCHEDULE in intents && aiPreferences.aiAssistantSchedule().get()
                ) {
                    async { loadContextSource("airing schedule", 3_000L) { getScheduleSummary() } }
                } else {
                    null
                }
                val feedDeferred = if (
                    AssistantContextIntent.FEED in intents && aiPreferences.aiAssistantFeed().get()
                ) {
                    async { loadContextSource("saved feed", 3_000L) { getSavedFeedSummary() } }
                } else {
                    null
                }
                val communityDeferred = if (
                    AssistantContextIntent.COMMUNITY in intents && aiPreferences.aiAssistantEverythingMoe().get()
                ) {
                    async {
                        loadContextSource("EverythingMoe", 6_000L) {
                            everythingMoeScraper.getIntelligenceContext(
                                lastQuery,
                                extensionsDeferred?.await().orEmpty(),
                            )
                        }
                    }
                } else {
                    null
                }

                val sections = mutableListOf<AiContextSection>()
                if (AssistantContextIntent.DIAGNOSTICS in intents) {
                    val logs = if (aiPreferences.aiAssistantLogs().get()) {
                        logsDeferred?.await().orEmpty().take(5_000)
                    } else {
                        "Local log sharing is disabled in assistant settings."
                    }
                    sections += AiContextSection("DIAGNOSTICS_DATA", logs)
                    sections += AiContextSection("NAVIGATION_MAP", getAppMap())
                    sections += AiContextSection(
                        "EXTENSIONS_STATUS",
                        extensionStatusDeferred?.await().orEmpty(),
                    )
                    sections += AiContextSection("ENVIRONMENT", getDeviceInfo())
                }
                if (AssistantContextIntent.LIBRARY in intents) {
                    val text = if (aiPreferences.aiAssistantLibrary().get()) {
                        libraryDeferred?.await().orEmpty()
                    } else {
                        "Library sharing is disabled in assistant settings."
                    }
                    sections += AiContextSection("USER_LIBRARY_DATA", text)
                }
                if (AssistantContextIntent.HISTORY in intents) {
                    val content = if (aiPreferences.aiAssistantHistory().get()) {
                        historyDeferred?.await().orEmpty()
                    } else {
                        "Watch-history sharing is disabled in assistant settings."
                    }
                    sections += AiContextSection("RECENT_WATCH_HISTORY", content)
                }
                if (AssistantContextIntent.BROWSE in intents) {
                    val content = if (aiPreferences.aiAssistantBrowse().get()) {
                        browseDeferred?.await().orEmpty()
                    } else {
                        "Browse-context sharing is disabled in assistant settings."
                    }
                    sections += AiContextSection("BROWSE_CONTEXT", content)
                }
                if (AssistantContextIntent.SCHEDULE in intents) {
                    val content = if (aiPreferences.aiAssistantSchedule().get()) {
                        scheduleDeferred?.await().orEmpty()
                    } else {
                        "Schedule sharing is disabled in assistant settings."
                    }
                    sections += AiContextSection("CACHED_AIRING_SCHEDULE", content)
                }
                if (AssistantContextIntent.FEED in intents) {
                    val content = if (aiPreferences.aiAssistantFeed().get()) {
                        feedDeferred?.await().orEmpty()
                    } else {
                        "Feed sharing is disabled in assistant settings."
                    }
                    sections += AiContextSection("SAVED_FEED_SETUP", content)
                }
                communityDeferred?.await()?.takeIf(String::isNotBlank)?.let {
                    sections += AiContextSection("EXTENSION_COMMUNITY_INTELLIGENCE (EverythingMoe)", it.take(4_000))
                }
                val toolContext = assembleBoundedToolContext(sections)
                currentCoroutineContext()[AiRequestDiagnostics]?.toolContextChars = toolContext.length
                toolContext
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, tag = "AiAssistant") {
                "Unable to assemble assistant context (${e.javaClass.simpleName})."
            }
            ""
        }
    }

    private suspend fun <T : Any> loadContextSource(
        name: String,
        timeoutMs: Long,
        block: suspend () -> T,
    ): T? {
        return try {
            withTimeoutOrNull(timeoutMs) { block() }.also { result ->
                if (result == null) {
                    logcat(LogPriority.WARN, tag = "AiAssistant") { "Timed out loading $name context after ${timeoutMs}ms." }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, tag = "AiAssistant") {
                "Unable to load $name context (${e.javaClass.simpleName})."
            }
            null
        }
    }

    private fun assembleBoundedToolContext(sections: List<AiContextSection>): String {
        val bounded = sections.toMutableList()
        fun render(): String = bounded
            .filter { it.content.isNotBlank() }
            .joinToString(separator = "\n") { "[${it.label}]:\n${it.content}" }
            .trim()
        fun contextLength(): Int = render().length

        var excess = contextLength() - MAX_TOOL_CONTEXT_CHARS
        if (excess <= 0) return render()

        fun trimTail(label: String) {
            if (excess <= 0) return
            val index = bounded.indexOfFirst { it.label.startsWith(label) }
            if (index < 0) return
            val section = bounded[index]
            val remove = minOf(excess, section.content.length)
            bounded[index] = section.copy(content = section.content.take(section.content.length - remove))
            excess -= remove
        }

        // Keep pinned crash blocks at the beginning of the diagnostic section; trim log tail last.
        trimTail("EXTENSION_COMMUNITY_INTELLIGENCE")
        trimTail("DIAGNOSTICS_DATA")
        for (index in bounded.indices.reversed()) {
            if (excess <= 0) break
            val section = bounded[index]
            val remove = minOf(excess, section.content.length)
            bounded[index] = section.copy(content = section.content.take(section.content.length - remove))
            excess -= remove
        }
        return render().take(MAX_TOOL_CONTEXT_CHARS)
    }

    private fun getDeviceInfo(): String = "Model: ${android.os.Build.MODEL}, SDK: ${android.os.Build.VERSION.SDK_INT}, App: Hikaru"

    private fun providerError(response: Response, providerName: String, apiKey: String): String {
        val body = response.body?.byteStream()?.bufferedReader()?.use { reader ->
            val buffer = CharArray(1024)
            buildString {
                while (length < MAX_ERROR_BODY_CHARS) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_ERROR_BODY_CHARS - length))
                    if (count <= 0) break
                    append(buffer, 0, count)
                }
            }
        }.orEmpty()
        return describeHttpError(providerName, response.code, body, apiKey)
    }

    private fun providerConnectionError(providerName: String, apiKey: String, error: Exception): String {
        val detail = redactSecrets(error.message.orEmpty(), apiKey)
            .replace(Regex("[\\r\\n]+"), " ")
            .take(160)
            .takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
        return "$providerName connection failed: $detail. Check your connection and provider settings."
    }

    private suspend fun <T> executeCancellable(
        request: Request,
        block: suspend (Response) -> T,
    ): T {
        val client = networkHelper.client.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val call = client.newCall(request)
        val coroutineContext = currentCoroutineContext()
        val cancellationHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        val requestDiagnostics = coroutineContext[AiRequestDiagnostics]
        return try {
            call.execute().use { response ->
                requestDiagnostics?.httpStatus = response.code
                requestDiagnostics?.outcome = if (response.isSuccessful) "ok" else "http_error"
                block(response)
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            throw e
        } finally {
            cancellationHandle.dispose()
        }
    }

    suspend fun getErrorCount(): Int = withIOContext {
        try {
            withTimeoutOrNull(4_000L) {
                val logLines = readLogcatLines(maxLines = 200, priority = "E").toMutableList()
                if (logLines.isEmpty()) {
                    val storageManager = Injekt.get<StorageManager>()
                    val internalLogDir = File(context.cacheDir, "logs")
                    val logDir = storageManager.getLogsDirectory() ?: UniFile.fromFile(internalLogDir)
                    val latestLog = logDir?.listFiles()
                        ?.filter { it.isFile && it.name?.endsWith(".log") == true }
                        ?.maxByOrNull { it.lastModified() }
                    latestLog?.openInputStream()?.bufferedReader()?.use { reader ->
                        logLines.addAll(readLastLines(reader, maxLines = 200))
                    }
                }
                val criticalPatterns = listOf(
                    "FATAL EXCEPTION", "OutOfMemoryError", "Native crash", "SIGSEGV", "mpv: error", "Check failed",
                )
                logLines.count { line -> criticalPatterns.any { pattern -> line.contains(pattern, ignoreCase = true) } }
            } ?: run {
                logcat(LogPriority.WARN, tag = "AiAssistant") { "Timed out counting diagnostic errors." }
                0
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, tag = "AiAssistant") {
                "Unable to count diagnostic errors (${e.javaClass.simpleName})."
            }
            0
        }
    }

    private suspend fun callGeminiStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false,
    ): Flow<String> = flow {
        val healthyKeys = apiKey.split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
            .sortedBy { keyFailures[it] ?: 0L }
        val primaryModel = aiPreferences.geminiModel().get().ifBlank { "gemini-flash-latest" }
        val modelsToTry = if (primaryModel == "gemini-flash-lite-latest") {
            listOf(primaryModel)
        } else {
            listOf(primaryModel, "gemini-flash-lite-latest")
        }
        val finalMessages = if (withTools) {
            val lastQuery = messages.last().content
            val toolContext = buildToolContext(lastQuery)
            messages.dropLast(1) + ChatMessage(
                "user",
                messages.last().content + if (toolContext.isNotBlank()) "\n\n$toolContext" else "",
            )
        } else {
            messages
        }
        val geminiContents = finalMessages.map { message ->
            GeminiContent(
                parts = listOf(GeminiPart(text = message.content)),
                role = if (message.role == "user") "user" else "model",
            )
        }
        val requestBody = GeminiRequest(
            contents = geminiContents,
            systemInstruction = systemInstruction?.let { GeminiContent(parts = listOf(GeminiPart(text = it))) },
            safetySettings = listOf(
                GeminiSafetySetting("HARM_CATEGORY_HARASSMENT", "BLOCK_NONE"),
                GeminiSafetySetting("HARM_CATEGORY_HATE_SPEECH", "BLOCK_NONE"),
                GeminiSafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_NONE"),
                GeminiSafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_NONE"),
            ),
        )

        var streamEmitted = false
        var lastAttempt = GeminiAttemptSummary()
        keyLoop@ for (key in healthyKeys) {
            for (model in modelsToTry) {
                var emittedInAttempt = false
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$key")
                    .header("Content-Type", "application/json")
                    .post(json.encodeToString(GeminiRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
                    .build()

                try {
                    executeCancellable(request) { response ->
                        if (!response.isSuccessful) {
                            keyFailures[key] = System.currentTimeMillis()
                            lastAttempt = lastAttempt.afterFailure(
                                providerError(response, "Gemini", key),
                                response.code,
                                "http_error",
                            )
                            return@executeCancellable
                        }
                        lastAttempt = lastAttempt.afterEmptyResponse(model)
                        val body = response.body ?: return@executeCancellable
                        val source = body.source()
                        val nonSseBody = StringBuilder()
                        var sawSseData = false
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val data = parseServerSentEventData(line)
                            if (data == null) {
                                nonSseBody.appendBoundedLine(line)
                                continue
                            }
                            sawSseData = true
                            if (data == "[DONE]") break
                            val chunk = parseGeminiStreamChunk(data)
                            if (chunk == null) {
                                logInvalidStreamPayload("Gemini", data, key)
                                continue
                            }
                            if (!chunk.error.isNullOrBlank()) {
                                emittedInAttempt = true
                                currentCoroutineContext()[AiRequestDiagnostics]?.outcome = "http_error"
                                emit("Gemini error: ${redactSecrets(chunk.error, key).take(200)}")
                            } else if (chunk.text.isNotEmpty()) {
                                emittedInAttempt = true
                                emit(chunk.text)
                            } else if (!chunk.blockedReason.isNullOrBlank()) {
                                emittedInAttempt = true
                                currentCoroutineContext()[AiRequestDiagnostics]?.outcome = "empty"
                                emit("Gemini blocked this response: ${chunk.blockedReason}. Try rephrasing the request.")
                            }
                        }
                        if (!sawSseData) {
                            val fallbackText = extractNonSseAssistantText(nonSseBody.toString())
                                ?: parseGeminiStreamChunk(nonSseBody.toString())?.blockedReason?.let {
                                    "Gemini blocked this response: $it. Try rephrasing the request."
                                }
                            if (!fallbackText.isNullOrBlank()) {
                                emittedInAttempt = true
                                if (extractNonSseErrorMessage(nonSseBody.toString()) != null) {
                                    currentCoroutineContext()[AiRequestDiagnostics]?.outcome = "http_error"
                                }
                                emit(redactSecrets(fallbackText, key))
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    keyFailures[key] = System.currentTimeMillis()
                    currentCoroutineContext()[AiRequestDiagnostics]?.outcome = "exception"
                    val failure = providerConnectionError("Gemini", key, e)
                    lastAttempt = lastAttempt.afterFailure(failure, null, "exception")
                    if (emittedInAttempt) {
                        emit("\n\n[Gemini stream interrupted: $failure. The partial reply above may be incomplete.]")
                        streamEmitted = true
                        break@keyLoop
                    }
                }

                if (emittedInAttempt) {
                    streamEmitted = true
                    break@keyLoop
                }
            }
        }

        if (!streamEmitted) {
            val metrics = currentCoroutineContext()[AiRequestDiagnostics]
            metrics?.httpStatus = lastAttempt.httpStatus
            metrics?.outcome = lastAttempt.outcome
            emit(lastAttempt.finalFailureMessage(apiKey))
        }
    }

    suspend fun fetchAvailableModels(engine: String): List<String> = withIOContext {
        val apiKey = when (engine) {
            "gemini" -> aiPreferences.geminiApiKey().get().split(",").firstOrNull()?.trim() ?: ""
            "deepseek" -> aiPreferences.deepseekApiKey().get()
            "opencode" -> aiPreferences.opencodeApiKey().get()
            "literouter" -> aiPreferences.literouterApiKey().get()
            "tokenreply" -> aiPreferences.tokenreplyApiKey().get()
            "openai" -> aiPreferences.openaiApiKey().get()
            "anthropic" -> aiPreferences.anthropicApiKey().get()
            "openrouter" -> aiPreferences.openrouterApiKey().get()
            "together" -> aiPreferences.togetherApiKey().get()
            else -> aiPreferences.groqApiKey().get()
        }

        if (apiKey.isBlank()) return@withIOContext getDefaultModelsForEngine(engine)

        try {
            when (engine) {
                "gemini" -> {
                    val request = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                        .build()
                    executeCancellable(request) { response ->
                        if (!response.isSuccessful) {
                            getDefaultModelsForEngine(engine)
                        } else {
                            val body = response.body?.string().orEmpty()
                            val parsed = json.decodeFromString(GeminiModelsListResponse.serializer(), body)
                            val models = parsed.models.map { it.name.removePrefix("models/") }
                                .filter { it.contains("gemini") }
                            models.ifEmpty { getDefaultModelsForEngine(engine) }
                        }
                    }
                }
                "openai", "openrouter", "together", "groq", "deepseek", "opencode", "literouter", "tokenreply" -> {
                    val url = when (engine) {
                        "openai" -> "https://api.openai.com/v1/models"
                        "openrouter" -> "https://openrouter.ai/api/v1/models"
                        "together" -> "https://api.together.xyz/v1/models"
                        "groq" -> "https://api.groq.com/openai/v1/models"
                        "opencode" -> "https://opencode.ai/zen/v1/models"
                        "literouter" -> "https://api.literouter.com/v1/models"
                        "tokenreply" -> "https://api.tokenreply.com/v1/models"
                        else -> "https://api.deepseek.com/models"
                    }
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $apiKey")
                        .build()
                    executeCancellable(request) { response ->
                        if (!response.isSuccessful) {
                            getDefaultModelsForEngine(engine)
                        } else {
                            val body = response.body?.string().orEmpty()
                            val parsed = json.decodeFromString(OpenAiModelsListResponse.serializer(), body)
                            val models = parsed.data.map { it.id }
                            models.ifEmpty { getDefaultModelsForEngine(engine) }
                        }
                    }
                }
                else -> getDefaultModelsForEngine(engine)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            getDefaultModelsForEngine(engine)
        }
    }

    private fun getDefaultModelsForEngine(engine: String): List<String> {
        return when (engine) {
            "gemini" -> listOf("gemini-pro-latest", "gemini-flash-latest", "gemini-flash-lite-latest")
            "openai" -> listOf("gpt-4o-mini", "gpt-4o", "o1-mini", "o1-preview")
            "anthropic" -> listOf("claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "claude-3-opus-20240229")
            "openrouter" -> listOf("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet", "deepseek/deepseek-r1")
            "together" -> listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "deepseek-ai/DeepSeek-R1")
            "deepseek" -> listOf("deepseek-chat", "deepseek-reasoner")
            "opencode" -> listOf("deepseek-v4-flash-free", "deepseek-chat")
            "literouter" -> listOf("deepseek-v4-flash-free", "deepseek-chat")
            "tokenreply" -> listOf("deepseek-v4-flash-free", "deepseek-chat")
            else -> listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
        }
    }

    private suspend fun callOpenAiCompatibleEndpointStream(
        endpointUrl: String,
        modelName: String,
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false,
        providerName: String = "OpenAI-compatible provider",
    ): Flow<String> = flow {
        val finalMessages = prepareFinalMessages(messages, systemInstruction, withTools)
        val requestBody = GroqRequest(messages = finalMessages, model = modelName, stream = true)
        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()
        executeOpenAiFormatStream(request, providerName, apiKey).collect { emit(it) }
    }

    private suspend fun callGroq(messages: List<ChatMessage>, apiKey: String, systemInstruction: String? = null): String? = withIOContext {
        val groqMessages = mutableListOf<GroqMessage>()
        if (systemInstruction != null) groqMessages.add(GroqMessage(role = "system", content = systemInstruction))
        messages.forEach { msg -> groqMessages.add(GroqMessage(role = if (msg.role == "user") "user" else "assistant", content = msg.content)) }
        val requestBody = GroqRequest(messages = groqMessages, model = "groq/compound-mini")
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()
        try {
            executeCancellable(request) { response ->
                if (!response.isSuccessful) {
                    providerError(response, "Groq", apiKey)
                } else {
                    val bodyString = response.body?.string().orEmpty()
                    val groqResponse = try {
                        json.decodeFromString(GroqResponse.serializer(), bodyString)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@executeCancellable "Failed to parse Groq response: ${redactSecrets(e.message.orEmpty(), apiKey).take(160)}"
                    }
                    val answer = groqResponse.choices.firstOrNull()?.message?.content?.trim()
                    answer?.takeIf(String::isNotBlank)
                        ?: "Groq returned a valid JSON but empty message content. Model info: 'groq/compound-mini'."
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Groq connection failed: ${redactSecrets(e.message.orEmpty(), apiKey).take(160)}"
        }
    }

    private suspend fun callGroqStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false,
    ): Flow<String> = flow {
        val finalMessages = prepareFinalMessages(messages, systemInstruction, withTools)
        val model = aiPreferences.groqModel().get().ifBlank { "llama-3.3-70b-versatile" }
        val requestBody = GroqRequest(messages = finalMessages, model = model, stream = true)
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()
        executeOpenAiFormatStream(request, "Groq", apiKey).collect { emit(it) }
    }

    private suspend fun callOpenAiStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false
    ): Flow<String> = flow {
        val finalMessages = prepareFinalMessages(messages, systemInstruction, withTools)
        val model = aiPreferences.openaiModel().get().ifBlank { "gpt-4o-mini" }
        val requestBody = GroqRequest(messages = finalMessages, model = model, stream = true)
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()
        executeOpenAiFormatStream(request, "OpenAI", apiKey).collect { emit(it) }
    }

    private suspend fun callAnthropicStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false,
    ): Flow<String> = flow {
        val contextMessages = if (withTools) {
            val lastQuery = messages.last().content
            val toolContext = buildToolContext(lastQuery)
            messages.dropLast(1) + ChatMessage(
                "user",
                messages.last().content + if (toolContext.isNotBlank()) "\n\n$toolContext" else "",
            )
        } else {
            messages
        }
        val anthropicMessages = contextMessages.map { message ->
            AnthropicMessage(role = if (message.role == "user") "user" else "assistant", content = message.content)
        }
        val model = aiPreferences.anthropicModel().get().ifBlank { "claude-3-5-sonnet-20241022" }
        val requestBody = AnthropicRequest(
            model = model,
            system = systemInstruction,
            messages = anthropicMessages,
            stream = true,
        )
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(AnthropicRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()

        var emittedText = false
        try {
            executeCancellable(request) { response ->
                if (!response.isSuccessful) {
                    emit(providerError(response, "Anthropic", apiKey))
                    emittedText = true
                    return@executeCancellable
                }
                val body = response.body ?: return@executeCancellable
                val source = body.source()
                val nonSseBody = StringBuilder()
                var sawSseData = false
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val data = parseServerSentEventData(line)
                    if (data == null) {
                        nonSseBody.appendBoundedLine(line)
                        continue
                    }
                    sawSseData = true
                    if (data == "[DONE]") break
                    try {
                        val chunk = json.decodeFromString(AnthropicStreamResponse.serializer(), data)
                        val errorMessage = chunk.error?.message ?: chunk.error?.type ?: chunk.message
                            ?: if (chunk.type == "error") "The provider returned an error event." else null
                        if (!errorMessage.isNullOrBlank()) {
                            emittedText = true
                            currentCoroutineContext()[AiRequestDiagnostics]?.outcome = "http_error"
                            emit("Anthropic error: ${redactSecrets(errorMessage, apiKey).take(200)}")
                        } else {
                            val text = chunk.delta?.text
                            if (!text.isNullOrEmpty()) {
                                emittedText = true
                                emit(text)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        logInvalidStreamPayload("Anthropic", data, apiKey)
                    }
                }
                if (!sawSseData) {
                    val fallbackText = extractNonSseAssistantText(nonSseBody.toString())
                    if (!fallbackText.isNullOrBlank()) {
                        emittedText = true
                        if (extractNonSseErrorMessage(nonSseBody.toString()) != null) {
                            currentCoroutineContext()[AiRequestDiagnostics]?.outcome = "http_error"
                        }
                        emit(redactSecrets(fallbackText, apiKey))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext()[AiRequestDiagnostics]?.outcome = "exception"
            emit(providerConnectionError("Anthropic", apiKey, e))
            emittedText = true
        }
        if (!emittedText) {
            val metrics = currentCoroutineContext()[AiRequestDiagnostics]
            if (metrics?.httpStatus?.let { it in 400..599 } != true) metrics?.outcome = "empty"
        }
    }

    private suspend fun callOpenRouterStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false
    ): Flow<String> = flow {
        val finalMessages = prepareFinalMessages(messages, systemInstruction, withTools)
        val model = aiPreferences.openrouterModel().get().ifBlank { "openai/gpt-4o-mini" }
        val requestBody = GroqRequest(messages = finalMessages, model = model, stream = true)
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/SA-San49/Hikaru")
            .header("X-Title", "Hikaru")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()
        executeOpenAiFormatStream(request, "OpenRouter", apiKey).collect { emit(it) }
    }

    private suspend fun callTogetherStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false
    ): Flow<String> = flow {
        val finalMessages = prepareFinalMessages(messages, systemInstruction, withTools)
        val model = aiPreferences.togetherModel().get().ifBlank { "meta-llama/Llama-3.3-70B-Instruct-Turbo" }
        val requestBody = GroqRequest(messages = finalMessages, model = model, stream = true)
        val request = Request.Builder()
            .url("https://api.together.xyz/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()
        executeOpenAiFormatStream(request, "Together AI", apiKey).collect { emit(it) }
    }

    private suspend fun prepareFinalMessages(
        messages: List<ChatMessage>,
        systemInstruction: String?,
        withTools: Boolean
    ): List<GroqMessage> {
        val contextMessages = if (withTools) {
            val lastQuery = messages.last().content
            val toolContext = buildToolContext(lastQuery)
            messages.dropLast(1) + ChatMessage("user", messages.last().content + if (toolContext.isNotBlank()) "\n\n$toolContext" else "")
        } else {
            messages
        }

        val result = mutableListOf<GroqMessage>()
        if (systemInstruction != null) result.add(GroqMessage(role = "system", content = systemInstruction))
        contextMessages.forEach { msg -> result.add(GroqMessage(role = if (msg.role == "user") "user" else "assistant", content = msg.content)) }
        return result
    }

    private fun executeOpenAiFormatStream(
        request: Request,
        providerName: String,
        apiKey: String,
    ): Flow<String> = flow {
        var emittedText = false
        val reasoningText = StringBuilder()
        try {
            executeCancellable(request) { response ->
                if (!response.isSuccessful) {
                    emit(providerError(response, providerName, apiKey))
                    emittedText = true
                    return@executeCancellable
                }
                val body = response.body ?: return@executeCancellable
                val source = body.source()
                val nonSseBody = StringBuilder()
                var sawSseData = false
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val data = parseServerSentEventData(line)
                    if (data == null) {
                        nonSseBody.appendBoundedLine(line)
                        continue
                    }
                    sawSseData = true
                    if (data == "[DONE]") break
                    val chunk = parseOpenAiStreamChunk(data)
                    if (chunk == null) {
                        logInvalidStreamPayload(providerName, data, apiKey)
                        continue
                    }
                    val error = chunk.error
                    if (!error.isNullOrBlank()) {
                        currentCoroutineContext()[AiRequestDiagnostics]?.outcome = "http_error"
                        emit("$providerName error: ${redactSecrets(error, apiKey).take(200)}")
                        emittedText = true
                    }
                    val content = chunk.content
                    if (!content.isNullOrEmpty()) {
                        emittedText = true
                        emit(content)
                    }
                    val reasoning = chunk.reasoning
                    if (!reasoning.isNullOrEmpty()) reasoningText.append(reasoning)
                }
                if (!sawSseData) {
                    val fallbackText = extractNonSseAssistantText(nonSseBody.toString())
                    if (!fallbackText.isNullOrBlank()) {
                        emittedText = true
                        if (extractNonSseErrorMessage(nonSseBody.toString()) != null) {
                            currentCoroutineContext()[AiRequestDiagnostics]?.outcome = "http_error"
                        }
                        emit(redactSecrets(fallbackText, apiKey))
                    }
                }
                if (!emittedText && reasoningText.isNotEmpty()) {
                    emittedText = true
                    emit(redactSecrets(reasoningText.toString(), apiKey))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext()[AiRequestDiagnostics]?.outcome = "exception"
            emit(providerConnectionError(providerName, apiKey, e))
            emittedText = true
        }
        if (!emittedText) {
            val metrics = currentCoroutineContext()[AiRequestDiagnostics]
            if (metrics?.httpStatus?.let { it in 400..599 } != true) metrics?.outcome = "empty"
        }
    }

    @Serializable
    data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>,
        @kotlinx.serialization.SerialName("system_instruction") val systemInstruction: GeminiContent? = null,
        val safetySettings: List<GeminiSafetySetting>? = null,
    )

    @Serializable
    private data class GeminiSafetySetting(
        val category: String,
        val threshold: String,
    )

    @Serializable
    private data class GeminiContent(val parts: List<GeminiPart>, val role: String? = null)

    @Serializable
    private data class GeminiPart(val text: String)

    @Serializable
    private data class GroqRequest(
        val messages: List<GroqMessage>,
        val model: String,
        val stream: Boolean = false,
    )

    @Serializable
    private data class GroqMessage(val role: String, val content: String)

    @Serializable
    private data class GroqResponse(val choices: List<GroqChoice>)

    @Serializable
    private data class GroqChoice(val message: GroqMessage)

    @Serializable
    private data class AnthropicRequest(
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
        val model: String = "claude-3-5-sonnet-20241022",
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
        val max_tokens: Int = 1024,
        val system: String? = null,
        val messages: List<AnthropicMessage>,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
        val stream: Boolean = true,
    )

    @Serializable
    private data class AnthropicMessage(val role: String, val content: String)

    @Serializable
    private data class AnthropicStreamResponse(
        val type: String? = null,
        val delta: AnthropicDelta? = null,
        val error: AnthropicError? = null,
        val message: String? = null,
    )

    @Serializable
    private data class AnthropicError(val type: String? = null, val message: String? = null)

    @Serializable
    private data class AnthropicDelta(val text: String? = null)

    @Serializable
    private data class GeminiModelsListResponse(val models: List<GeminiModelItem>)

    @Serializable
    private data class GeminiModelItem(val name: String)

    @Serializable
    private data class OpenAiModelsListResponse(val data: List<OpenAiModelItem>)

    @Serializable
    private data class OpenAiModelItem(val id: String)
}

private fun logRequestMetrics(metrics: AiRequestDiagnostics, apiKey: String? = null) {
    val safeEngine = redactSecrets(metrics.engine, apiKey).replace(Regex("[\\r\\n]+"), " ").take(40)
    val safeModel = redactSecrets(metrics.model, apiKey).replace(Regex("[\\r\\n]+"), " ").take(80)
    val elapsedMs = (System.nanoTime() - metrics.startedAtNanos) / 1_000_000L
    logcat(LogPriority.INFO, tag = "AiAssistant") {
        "engine=$safeEngine model=$safeModel toolContextChars=${metrics.toolContextChars} " +
            "httpStatus=${metrics.httpStatus ?: "none"} charsEmitted=${metrics.charsEmitted.get()} " +
            "elapsedMs=$elapsedMs outcome=${metrics.outcome}"
    }
}

private class AiRequestDiagnostics(
    val engine: String,
    val model: String,
    val startedAtNanos: Long = System.nanoTime(),
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<AiRequestDiagnostics>

    val charsEmitted = java.util.concurrent.atomic.AtomicInteger()

    @Volatile
    var toolContextChars: Int = 0

    @Volatile
    var httpStatus: Int? = null

    @Volatile
    var outcome: String = "exception"
}

private const val MAX_NON_SSE_BODY_CHARS = 64 * 1024
private const val MAX_ERROR_BODY_CHARS = 8 * 1024

private fun StringBuilder.appendBoundedLine(line: String) {
    val remaining = (MAX_NON_SSE_BODY_CHARS - length).coerceAtLeast(0)
    if (remaining == 0) return
    val boundedLine = line.take(remaining)
    append(boundedLine)
    if (boundedLine.length < remaining) append('\n')
}

private fun logInvalidStreamPayload(provider: String, payload: String, apiKey: String) {
    val safePayload = redactSecrets(payload, apiKey).take(120)
    logcat(LogPriority.DEBUG, tag = "AiAssistant") {
        "Skipped an unrecognized $provider stream payload: $safePayload"
    }
}

private const val MAX_TOOL_CONTEXT_CHARS = 12_000

private data class AiContextSection(
    val label: String,
    val content: String,
)

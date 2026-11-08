package eu.kanade.tachiyomi.data.ai

import android.content.Context
import eu.kanade.tachiyomi.data.ai.everythingmoe.EverythingMoeScraper
import eu.kanade.domain.ai.AiPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.feature.airingschedule.ScheduleDataRefreshWorker
import mihon.feature.airingschedule.SchedulePreferences
import tachiyomi.domain.history.interactor.GetActivityLog
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.ActivityLog
import tachiyomi.domain.source.interactor.GetFeedSavedSearchCategories
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.model.FeedSavedSearch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import com.hippo.unifile.UniFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

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
    private val assistantRequestMutex = Mutex()
    private val interruptedRequestRecoveryAttempted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val everythingMoeScraper: EverythingMoeScraper by lazy {
        EverythingMoeScraper(context, networkHelper, json)
    }

    // Circuit Breaker Config
    private val MAP_VERSION = 132
    private val REMOTE_KILL_SWITCH_URL = "https://raw.githubusercontent.com/salmanbappi/anikku-config/main/ai_kill_switch.json"

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
        if (!aiPreferences.enableAi().get()) {
            emit("AI features are turned off. Enable 'AI Integration' in Settings > Advanced Analytics (AI Config) to use the assistant.")
            return@flow
        }
        if (!aiPreferences.enableAiAssistant().get()) {
            emit("The AI Assistant is turned off. Enable 'AI Assistant' in Settings > Advanced Analytics (AI Config) to chat here.")
            return@flow
        }

        recoverInterruptedRequestState()
        if (isCircuitBreakerTripped()) {
            emit("Stability Alert: AI temporarily disabled due to detected app instability. Send /reset to clear the circuit breaker.")
            return@flow
        }
        if (isRemoteKillSwitchActive()) {
            emit("Service Maintenance: AI Assistant is currently offline.")
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
            Hikaru may include local context blocks such as diagnostics, library, watch history, visible browse sources,
            saved feed setup, or a cached airing schedule. Treat those blocks as read-only snapshots, not live access.
            Use only the blocks provided; never claim to browse the internet, refresh feeds, change settings, or perform
            actions that were not actually completed. Do not expose secrets or infer personal information not present.
            Prefer concise explanations and bullet lists. Do not use Markdown tables.
        """.trimIndent()
        val systemInstruction = if (customPrompt.isNotBlank()) customPrompt else defaultSystemInstruction
        val messages = createBoundedRequestMessages(query, history)

        assistantRequestMutex.withLock {
            aiPreferences.isRequestPending().set(true)
            var emittedResponse = false
            try {
                val response: Flow<String> = when (engine) {
                    "gemini" -> callGeminiStream(messages, apiKey, systemInstruction, withTools = true)
                    "deepseek" -> callOpenAiCompatibleEndpointStream(
                        "https://api.deepseek.com/chat/completions",
                        aiPreferences.deepseekModel().get().ifBlank { "deepseek-chat" },
                        messages,
                        apiKey,
                        systemInstruction,
                        withTools = true,
                        providerName = "DeepSeek",
                    )
                    "opencode" -> callOpenAiCompatibleEndpointStream(
                        "https://opencode.ai/zen/v1/chat/completions",
                        aiPreferences.opencodeModel().get().ifBlank { "deepseek-v4-flash-free" },
                        messages,
                        apiKey,
                        systemInstruction,
                        withTools = true,
                        providerName = "OpenCode Zen",
                    )
                    "literouter" -> callOpenAiCompatibleEndpointStream(
                        "https://api.literouter.com/v1/chat/completions",
                        aiPreferences.literouterModel().get().ifBlank { "deepseek-v4-flash-free" },
                        messages,
                        apiKey,
                        systemInstruction,
                        withTools = true,
                        providerName = "LiteRouter",
                    )
                    "tokenreply" -> callOpenAiCompatibleEndpointStream(
                        "https://api.tokenreply.com/v1/chat/completions",
                        aiPreferences.tokenreplyModel().get().ifBlank { "deepseek-v4-flash-free" },
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
                response.collect { chunk ->
                    if (chunk.isNotEmpty()) {
                        emittedResponse = true
                        emit(chunk)
                    }
                }
                if (!emittedResponse) {
                    emit("The AI provider finished without returning text. Check the selected model's chat/streaming support and try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                emit("The AI request could not be completed. Check your connection, provider settings, and selected model, then try again.")
            } finally {
                aiPreferences.isRequestPending().set(false)
                recordRequestSuccess()
            }
        }
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
            logcat(LogPriority.WARN, e) { "Unable to load library summary for assistant." }
            "Library summary could not be loaded."
        }
    }

    suspend fun getStatisticsAnalysis(statsSummary: String): String? {
        val result = StringBuilder()
        getStatisticsAnalysisStream(statsSummary).collect { result.append(it) }
        return result.toString().ifBlank { null }
    }

    fun getStatisticsAnalysisStream(statsSummary: String): Flow<String> = flow {
        if (!aiPreferences.enableAi().get() || !aiPreferences.enableAiStatistics().get()) return@flow
        
        if (isCircuitBreakerTripped()) return@flow

        val engine = aiPreferences.aiEngine().get()
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

        aiPreferences.isRequestPending().set(true)
        try {
            when (engine) {
                "gemini" -> callGeminiStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "deepseek" -> callOpenAiCompatibleEndpointStream("https://api.deepseek.com/chat/completions", aiPreferences.deepseekModel().get().ifBlank { "deepseek-chat" }, listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "opencode" -> callOpenAiCompatibleEndpointStream("https://opencode.ai/zen/v1/chat/completions", aiPreferences.opencodeModel().get().ifBlank { "deepseek-v4-flash-free" }, listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "literouter" -> callOpenAiCompatibleEndpointStream("https://api.literouter.com/v1/chat/completions", aiPreferences.literouterModel().get().ifBlank { "deepseek-v4-flash-free" }, listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "tokenreply" -> callOpenAiCompatibleEndpointStream("https://api.tokenreply.com/v1/chat/completions", aiPreferences.tokenreplyModel().get().ifBlank { "deepseek-v4-flash-free" }, listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "openai" -> callOpenAiStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "anthropic" -> callAnthropicStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "openrouter" -> callOpenRouterStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "together" -> callTogetherStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                else -> callGroqStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
            }
        } finally {
            aiPreferences.isRequestPending().set(false)
            recordRequestSuccess()
        }
    }

    private fun recoverInterruptedRequestState() {
        if (!interruptedRequestRecoveryAttempted.compareAndSet(false, true)) return
        if (aiPreferences.isRequestPending().get()) {
            aiPreferences.isRequestPending().set(false)
            aiPreferences.isCircuitBreakerTripped().set(false)
            logcat(LogPriority.WARN) { "Cleared stale AI request state left by a previous process." }
        }
    }

    private fun isCircuitBreakerTripped(): Boolean {
        // If the app crashed during the last request, trip the breaker
        if (aiPreferences.isRequestPending().get()) {
            aiPreferences.isCircuitBreakerTripped().set(true)
            return true
        }
        return aiPreferences.isCircuitBreakerTripped().get()
    }

    private suspend fun isRemoteKillSwitchActive(): Boolean = withIOContext {
        try {
            val request = Request.Builder().url(REMOTE_KILL_SWITCH_URL).build()
            networkHelper.client.newCall(request).execute().use {
                if (it.isSuccessful) {
                    val body = it.body.string()
                    body.contains("\"disabled\": true")
                } else false
            }
        } catch (e: Exception) {
            false // Default to enabled if network fails
        }
    }

    private fun recordRequestSuccess() {
        val count = aiPreferences.hourlyAiRequestCount().get()
        aiPreferences.hourlyAiRequestCount().set(count + 1)
        aiPreferences.lastAiRequestTime().set(System.currentTimeMillis())
    }

    private suspend fun getSanitizedLogs(): String = withIOContext {
        try {
            val logLines = mutableListOf<String>()
            
            // 1. Try Logcat with a hard timeout to prevent hanging
            try {
                val process = Runtime.getRuntime().exec("logcat -d -b main -t 500 *:W")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    logLines.add(line)
                }
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                process.destroy()
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Logcat retrieval timed out or failed: ${e.message}" }
            }

            // 2. Fallback to internal XLog files if logcat is restricted (Android 13+)
            if (logLines.size < 10) {
                val storageManager = Injekt.get<StorageManager>()
                val internalLogDir = File(context.cacheDir, "logs")
                val logDir = storageManager.getLogsDirectory() 
                    ?: UniFile.fromFile(internalLogDir)
                
                val latestLog = logDir?.listFiles()
                    ?.filter { it.isFile && it.name?.endsWith(".log") == true }
                    ?.maxByOrNull { it.lastModified() }
                
                if (latestLog != null) {
                    try {
                        latestLog.openInputStream().bufferedReader().useLines { lines ->
                            logLines.addAll(lines.toList().takeLast(500))
                        }
                    } catch (e: Exception) {
                        logLines.add("Error reading internal log file: ${e.message}")
                    }
                }
            }

            if (logLines.isEmpty()) {
                return@withIOContext "Diagnostic engine active. No logs available for analysis in this environment."
            }

            val pinnedBlocks = mutableListOf<List<String>>()
            val currentBlock = mutableListOf<String>()
            
            val packagePattern = "(eu\\.kanade|app\\.hikaru|mpv|ffmpeg|AndroidRuntime|libc|DEBUG|System\\.err|XLog|FileUtils|ActivityThread|InputDispatcher)".toRegex()
            val piiRedaction = "(?i)(?:authorization|cookie|set-cookie):\\s*[^\\n\\r]+|(?<=\\?|&)[^=]+=[^&\\s]*|(?:[a-zA-Z0-9+_.-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})|(?:auth|token|key|password|secret|sid|session)=[a-zA-Z0-9._-]+".toRegex()
            
            // Only trigger analysis on serious events
            val traceTrigger = "(FATAL EXCEPTION|Native crash|SIGSEGV|SIGABRT|mpv: error|Check failed)".toRegex(RegexOption.IGNORE_CASE)
            
            var lastLine = ""
            var repeatCount = 0
            
            val sanitizedResult = mutableListOf<String>()
            for (line in logLines) {
                val sanitizedLine = line.replace(piiRedaction, "[REDACTED]")
                
                val isTraceLine = sanitizedLine.trimStart().startsWith("at ") || 
                                 sanitizedLine.contains("Caused by:") || 
                                 sanitizedLine.contains("#\\d+ pc ".toRegex())

                if (sanitizedLine.contains(traceTrigger) || (isTraceLine && currentBlock.isNotEmpty())) {
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
                    
                    if (sanitizedLine.contains(packagePattern)) {
                        if (sanitizedLine == lastLine) {
                            repeatCount++
                        } else {
                            if (repeatCount > 0) sanitizedResult.add("... [TRUNCATED] repeated $repeatCount times ...")
                            sanitizedResult.add(sanitizedLine)
                            lastLine = sanitizedLine
                            repeatCount = 0
                        }
                    }
                }
            }
            if (currentBlock.isNotEmpty()) pinnedBlocks.add(currentBlock.toList())
            if (repeatCount > 0) sanitizedResult.add("... [TRUNCATED] repeated $repeatCount times ...")
            
            val output = StringBuilder()
            if (pinnedBlocks.isNotEmpty()) {
                output.append("\n### CRITICAL SYSTEM EVENTS (PINNED):\n")
                pinnedBlocks.takeLast(2).forEach { output.append(it.joinToString("\n")).append("\n---\n") }
            }
            output.append("\n### SYSTEM LOG TAIL:\n")
            output.append(sanitizedResult.takeLast(100).joinToString("\n"))
            output.toString()
        } catch (e: Exception) {
            "Diagnostic retrieval failed: ${e.message}"
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
        return try {
            if (!extensionManager.isInitialized.value) {
                withTimeoutOrNull(2000) {
                    extensionManager.isInitialized.first { it }
                }
            }
            extensionManager.installedExtensionsFlow.value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getExtensionStatusSummary(
        installed: List<eu.kanade.tachiyomi.extension.model.Extension.Installed>,
    ): String {
        if (installed.isEmpty()) {
            val sources = runCatching { sourceManager.getVisibleOnlineSources() }.getOrDefault(emptyList())
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

    private suspend fun buildToolContext(lastQuery: String): String {
        val query = lastQuery.lowercase(Locale.ROOT)
        val toolContext = StringBuilder()
        val wantsDiagnostics = query.containsAny(
            "log", "error", "fail", "video", "loading", "device", "black screen", "broke", "froze",
            "slow", "crash", "bug", "stuck", "lag", "hang", "freeze", "diagnostic",
        )
        val wantsLibrary = query.containsAny(
            "library", "collection", "my anime", "anime list", "titles i have", "recommend", "recommendation",
        )
        val wantsHistory = query.containsAny(
            "history", "recently watched", "last watched", "watching history", "watch history", "episodes watched",
            "continue watching", "finished watching",
        )
        val wantsBrowse = query.containsAny(
            "browse", "catalogue", "catalog", "available sources", "visible sources", "source list", "extensions",
        )
        val wantsSchedule = query.containsAny(
            "airing", "schedule", "broadcast", "upcoming episode", "next episode", "releasing this week", "coming out",
        )
        val wantsFeed = query.containsAny(
            "feed", "saved search", "saved feed", "subscriptions", "latest updates", "my updates",
        )
        val wantsCommunityInfo = query.containsAny(
            "everythingmoe", "mirror", "site health", "site status", "stream link", "working extension",
            "broken extension", "1080p", "dub", "sub", "quality",
        )
        val wantsExtensionStatus = wantsDiagnostics || query.containsAny(
            "installed extension", "extension status", "source status",
        )

        if (wantsDiagnostics) {
            if (aiPreferences.aiAssistantLogs().get()) {
                toolContext.append("\n[DIAGNOSTICS_DATA]:\n${getSanitizedLogs().take(5_000)}\n")
            } else {
                toolContext.append("\n[DIAGNOSTICS_DATA]: Local log sharing is disabled in assistant settings.\n")
            }
            toolContext.append("\n[NAVIGATION_MAP]:\n${getAppMap()}\n")
            toolContext.append("\n[ENVIRONMENT]: ${getDeviceInfo()}\n")
        }

        var installedExtensions: List<eu.kanade.tachiyomi.extension.model.Extension.Installed>? = null
        suspend fun extensions(): List<eu.kanade.tachiyomi.extension.model.Extension.Installed> {
            return installedExtensions ?: getInstalledExtensions().also { installedExtensions = it }
        }

        if (wantsExtensionStatus) {
            toolContext.append("\n[EXTENSIONS_STATUS]:\n${getExtensionStatusSummary(extensions())}\n")
        }

        if (wantsLibrary) {
            if (aiPreferences.aiAssistantLibrary().get()) {
                toolContext.append("\n[USER_LIBRARY_DATA]:\n${getLibrarySummary()}\n")
            } else {
                toolContext.append("\n[USER_LIBRARY_DATA]: Library sharing is disabled in assistant settings.\n")
            }
        }

        if (wantsHistory) {
            if (aiPreferences.aiAssistantHistory().get()) {
                toolContext.append("\n[RECENT_WATCH_HISTORY]:\n${getRecentHistorySummary()}\n")
            } else {
                toolContext.append("\n[RECENT_WATCH_HISTORY]: Watch-history sharing is disabled in assistant settings.\n")
            }
        }

        if (wantsBrowse) {
            if (aiPreferences.aiAssistantBrowse().get()) {
                toolContext.append("\n[BROWSE_CONTEXT]:\n${getBrowseSummary()}\n")
            } else {
                toolContext.append("\n[BROWSE_CONTEXT]: Browse-context sharing is disabled in assistant settings.\n")
            }
        }

        if (wantsSchedule) {
            if (aiPreferences.aiAssistantSchedule().get()) {
                toolContext.append("\n[CACHED_AIRING_SCHEDULE]:\n${getScheduleSummary()}\n")
            } else {
                toolContext.append("\n[CACHED_AIRING_SCHEDULE]: Schedule sharing is disabled in assistant settings.\n")
            }
        }

        if (wantsFeed) {
            if (aiPreferences.aiAssistantFeed().get()) {
                toolContext.append("\n[SAVED_FEED_SETUP]:\n${getSavedFeedSummary()}\n")
            } else {
                toolContext.append("\n[SAVED_FEED_SETUP]: Feed sharing is disabled in assistant settings.\n")
            }
        }

        if (wantsCommunityInfo && aiPreferences.aiAssistantEverythingMoe().get()) {
            val communityContext = withTimeoutOrNull(5_000) {
                everythingMoeScraper.getIntelligenceContext(lastQuery, extensions())
            }
            if (!communityContext.isNullOrBlank()) {
                toolContext.append("\n[EXTENSION_COMMUNITY_INTELLIGENCE (EverythingMoe)]:\n")
                toolContext.append(communityContext.take(4_000)).append('\n')
            } else {
                toolContext.append("\n[EXTENSION_COMMUNITY_INTELLIGENCE]: No cached community details were available.\n")
            }
        }

        return toolContext.toString().trim().take(16_000)
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
            logcat(LogPriority.WARN, e) { "Unable to load bounded watch history for assistant context." }
            "Recent watch history could not be loaded."
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
            logcat(LogPriority.WARN, e) { "Unable to load browse context for assistant." }
            "Browse context could not be loaded."
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
            logcat(LogPriority.WARN, e) { "Unable to load cached schedule for assistant." }
            "The local airing schedule could not be loaded."
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
            logcat(LogPriority.WARN, e) { "Unable to load saved feed setup for assistant." }
            "Saved feed setup could not be loaded."
        }
    }

    private fun String.containsAny(vararg phrases: String): Boolean = phrases.any { contains(it) }

    private fun getDeviceInfo(): String = "Model: ${android.os.Build.MODEL}, SDK: ${android.os.Build.VERSION.SDK_INT}, App: Hikaru"

    private fun providerError(response: Response, providerName: String, apiKey: String): String {
        val body = response.body?.string().orEmpty()
        val detail = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val error = root["error"]
            when (error) {
                is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                    ?: error["type"]?.jsonPrimitive?.contentOrNull
                null -> root["message"]?.jsonPrimitive?.contentOrNull
                else -> error.jsonPrimitive.contentOrNull
            }
        }.getOrNull()
            ?.replace(apiKey, "[redacted]")
            ?.replace(Regex("(?i)bearer\\s+\\S+"), "Bearer [redacted]")
            ?.replace(Regex("[\\r\\n]+"), " ")
            ?.take(240)
            ?.takeIf { it.isNotBlank() }
        val status = "HTTP ${response.code} ${response.message}".trim()
        return "$providerName request failed ($status)" + (detail?.let { ": $it" } ?: ". Check the API key, model name, and provider access.")
    }

    private fun providerConnectionError(providerName: String, apiKey: String, error: Exception): String {
        val detail = error.message
            ?.replace(apiKey, "[redacted]")
            ?.replace(Regex("[\\r\\n]+"), " ")
            ?.take(160)
            ?.takeIf { it.isNotBlank() }
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
        val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        return try {
            call.execute().use { response -> block(response) }
        } finally {
            cancellationHandle.dispose()
        }
    }

    suspend fun getErrorCount(): Int = withIOContext {
        try {
            val logLines = mutableListOf<String>()
            val process = Runtime.getRuntime().exec("logcat -d -b main -t 200 *:E")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            while (true) {
                val line = reader.readLine() ?: break
                logLines.add(line)
            }

            // Fallback to internal files if logcat empty
            if (logLines.isEmpty()) {
                val storageManager = Injekt.get<StorageManager>()
                val internalLogDir = File(context.cacheDir, "logs")
                val logDir = storageManager.getLogsDirectory() 
                    ?: UniFile.fromFile(internalLogDir)
                
                val latestLog = logDir?.listFiles()
                    ?.filter { it.isFile && it.name?.endsWith(".log") == true }
                    ?.maxByOrNull { it.lastModified() }
                
                if (latestLog != null) {
                    try {
                        latestLog.openInputStream().bufferedReader().useLines { lines ->
                            logLines.addAll(lines.toList().takeLast(200))
                        }
                    } catch (e: Exception) {
                        logLines.add("Error reading internal log file: ${e.message}")
                    }
                }
            }

            var count = 0
            // Only count CRITICAL failures that affect the user experience
            val criticalPatterns = listOf("FATAL EXCEPTION", "OutOfMemoryError", "Native crash", "SIGSEGV", "mpv: error", "Check failed")
            
            for (line in logLines) {
                if (criticalPatterns.any { line.contains(it, ignoreCase = true) }) {
                    count++
                }
            }
            count
        } catch (e: Exception) { 0 }
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
        var latestError = ""
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
                            latestError = providerError(response, "Gemini", key)
                            return@executeCancellable
                        }
                        val source = response.body.source()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val data = parseServerSentEventData(line) ?: continue
                            if (data == "[DONE]") break
                            try {
                                val chunk = json.decodeFromString(GeminiResponse.serializer(), data)
                                val text = chunk.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                                if (!text.isNullOrEmpty()) {
                                    emittedInAttempt = true
                                    emit(text)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Ignore non-content events and malformed partial records.
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    keyFailures[key] = System.currentTimeMillis()
                    latestError = providerConnectionError("Gemini", key, e)
                    if (emittedInAttempt) {
                        emit("\n\n[Gemini stream interrupted. The partial reply above may be incomplete.]")
                        streamEmitted = true
                        break@keyLoop
                    }
                }

                if (emittedInAttempt) {
                    streamEmitted = true
                    break@keyLoop
                }
                if (latestError.isBlank()) {
                    latestError = "Gemini model '$model' returned no streamed text. Try another Gemini model."
                }
            }
        }

        if (!streamEmitted) {
            emit(
                latestError.ifBlank {
                    "Gemini could not return a response. Verify the API key and model, then try again."
                },
            )
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
                    networkHelper.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withIOContext getDefaultModelsForEngine(engine)
                        val body = response.body.string()
                        val parsed = json.decodeFromString(GeminiModelsListResponse.serializer(), body)
                        val models = parsed.models.map { it.name.removePrefix("models/") }
                            .filter { it.contains("gemini") }
                        models.ifEmpty { getDefaultModelsForEngine(engine) }
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
                    networkHelper.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withIOContext getDefaultModelsForEngine(engine)
                        val body = response.body.string()
                        val parsed = json.decodeFromString(OpenAiModelsListResponse.serializer(), body)
                        val models = parsed.data.map { it.id }
                        models.ifEmpty { getDefaultModelsForEngine(engine) }
                    }
                }
                else -> getDefaultModelsForEngine(engine)
            }
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
            val timedClient = networkHelper.client.newBuilder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            timedClient.newCall(request).execute().use {
                val bodyString = it.body.string()
                if (!it.isSuccessful) {
                    val errorMsg = "Groq Error ${it.code}: ${it.message}"
                    return@withIOContext if (bodyString.contains("rate_limit")) "$errorMsg (Rate limited)" else "$errorMsg\n$bodyString"
                }
                val groqResponse = try {
                    json.decodeFromString(GroqResponse.serializer(), bodyString)
                } catch (e: Exception) {
                    return@withIOContext "Failed to parse Groq response: ${e.message}\nRaw: $bodyString"
                }
                val answer = groqResponse.choices.firstOrNull()?.message?.content?.trim()
                if (answer.isNullOrBlank()) {
                    return@withIOContext "Groq returned a valid JSON but empty message content. Model info: 'groq/compound-mini'."
                }
                answer
            }
        } catch (e: Exception) { "Groq Connection Exception: ${e.message}" }
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
                val source = response.body.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val data = parseServerSentEventData(line) ?: continue
                    if (data == "[DONE]") break
                    try {
                        val chunk = json.decodeFromString(AnthropicStreamResponse.serializer(), data)
                        val text = chunk.delta?.text
                        if (!text.isNullOrEmpty()) {
                            emittedText = true
                            emit(text)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Ignore event types without a text delta.
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(providerConnectionError("Anthropic", apiKey, e))
            emittedText = true
        }
        if (!emittedText) {
            emit("Anthropic returned no streamed text. Check that the selected Claude model supports messages and streaming.")
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
        try {
            executeCancellable(request) { response ->
                if (!response.isSuccessful) {
                    emit(providerError(response, providerName, apiKey))
                    emittedText = true
                    return@executeCancellable
                }
                val source = response.body.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val data = parseServerSentEventData(line) ?: continue
                    if (data == "[DONE]") break
                    try {
                        val chunk = json.decodeFromString(GroqStreamResponse.serializer(), data)
                        val text = chunk.choices.firstOrNull()?.delta?.content
                        if (!text.isNullOrEmpty()) {
                            emittedText = true
                            emit(text)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Ignore keepalive, usage-only, and malformed partial records.
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(providerConnectionError(providerName, apiKey, e))
            emittedText = true
        }
        if (!emittedText) {
            emit("$providerName returned no streamed text. Confirm that the selected model supports chat completions and streaming.")
        }
    }

        @Serializable
        data class ChatMessage(val role: String, val content: String)
    
        @Serializable
        private data class GeminiRequest(
            val contents: List<GeminiContent>, 
            @kotlinx.serialization.SerialName("system_instruction") val systemInstruction: GeminiContent? = null,
            val safetySettings: List<GeminiSafetySetting>? = null
        )
    
        @Serializable
        private data class GeminiSafetySetting(
            val category: String,
            val threshold: String
        )
    
        @Serializable
        private data class GeminiContent(val parts: List<GeminiPart>, val role: String? = null)
    
        @Serializable
        private data class GeminiPart(val text: String)
    
        @Serializable
        private data class GeminiResponse(val candidates: List<GeminiCandidate>)
    
        @Serializable
        private data class GeminiCandidate(val content: GeminiContent)
    
        @Serializable
        private data class GroqRequest(
            val messages: List<GroqMessage>, 
            val model: String,
            val stream: Boolean = false
        )
    
        @Serializable
        private data class GroqMessage(val role: String, val content: String)
    
        @Serializable
        private data class GroqResponse(val choices: List<GroqChoice>)
    
        @Serializable
        private data class GroqChoice(val message: GroqMessage)
    
        @Serializable
        private data class GroqStreamResponse(val choices: List<GroqStreamChoice>)
    
        @Serializable
        private data class GroqStreamChoice(val delta: GroqStreamDelta)
    
        @Serializable
        private data class GroqStreamDelta(val content: String? = null)

        @Serializable
        private data class AnthropicRequest(
            val model: String = "claude-3-5-sonnet-20241022",
            val max_tokens: Int = 1024,
            val system: String? = null,
            val messages: List<AnthropicMessage>,
            val stream: Boolean = true
        )

        @Serializable
        private data class AnthropicMessage(val role: String, val content: String)

        @Serializable
        private data class AnthropicStreamResponse(
            val type: String? = null,
            val delta: AnthropicDelta? = null
        )

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

internal fun parseServerSentEventData(line: String): String? {
    if (!line.startsWith("data:")) return null
    return line.removePrefix("data:").removePrefix(" ").trimEnd()
}

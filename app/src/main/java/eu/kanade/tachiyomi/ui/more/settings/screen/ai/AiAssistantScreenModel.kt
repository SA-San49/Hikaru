package eu.kanade.tachiyomi.ui.more.settings.screen.ai

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.tachiyomi.data.ai.AiManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import tachiyomi.domain.ai.model.ChatMessage
import tachiyomi.domain.ai.model.ChatSession
import tachiyomi.domain.ai.repository.ChatRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant

class AiAssistantScreenModel(
    private val chatRepository: ChatRepository = Injekt.get(),
    private val aiPreferences: AiPreferences = Injekt.get(),
    private val aiManager: AiManager = Injekt.get(),
) : StateScreenModel<AiAssistantScreenModel.State>(State()) {

    val sessions: StateFlow<ImmutableList<ChatSession>> = combine(
        chatRepository.getSessions(),
        state.map { it.activeSessionId }.distinctUntilChanged()
    ) { list, activeSessionId ->
        list.filter { it.messageCount > 0 || it.id == activeSessionId }
            .toImmutableList()
    }
    .stateIn(screenModelScope, SharingStarted.Lazily, persistentListOf())

    init {
        screenModelScope.launchIO {
            val prefSessionId = aiPreferences.activeSessionId().get()
            val allSessions = chatRepository.getSessions().firstOrNull().orEmpty()
            
            // Cleanup truly empty sessions on start (except pref active one)
            val emptySessionIds = allSessions
                .filter { it.messageCount == 0L && it.id != prefSessionId }
                .map { it.id }
            if (emptySessionIds.isNotEmpty()) {
                chatRepository.deleteSessions(emptySessionIds)
            }

            val initialSession = when {
                // 1. Try last active
                prefSessionId != -1L && allSessions.any { it.id == prefSessionId } -> {
                    allSessions.first { it.id == prefSessionId }
                }
                // 2. Try most recent with messages
                allSessions.any { it.messageCount > 0 } -> {
                    allSessions.first { it.messageCount > 0 }
                }
                // 3. Fallback to any session
                allSessions.isNotEmpty() -> {
                    allSessions.first()
                }
                // 4. Create new
                else -> null
            }

            if (initialSession != null) {
                switchSession(initialSession.id)
            } else {
                createNewSession()
            }
        }

        // Auto-create session ONLY if list becomes empty after filtering
        // and we are not already initializing
        screenModelScope.launchIO {
            sessions.collectLatest { list ->
                if (list.isEmpty() && state.value.activeSessionId != null) {
                    createNewSession()
                }
            }
        }
    }

    private var messageCollectionJob: kotlinx.coroutines.Job? = null
    private var sendMessageJob: kotlinx.coroutines.Job? = null

    private fun loadSession(sessionId: Long) {
        // Update ID immediately to prevent race conditions in sendMessage.
        if (state.value.activeSessionId != sessionId) sendMessageJob?.cancel()
        mutableState.update {
            it.copy(activeSessionId = sessionId, messages = persistentListOf(), isLoading = false, streamingMessage = null)
        }

        messageCollectionJob?.cancel()
        messageCollectionJob = screenModelScope.launchIO {
            chatRepository.getMessagesBySessionId(sessionId).collectLatest { messages ->
                if (state.value.activeSessionId == sessionId) {
                    mutableState.update { it.copy(messages = messages.toImmutableList()) }
                }
            }
        }
    }

    fun createNewSession() {
        screenModelScope.launchIO {
            // Find existing empty session to reuse instead of creating a new one
            // Check repo directly because sessions.value is filtered
            val existingEmpty = chatRepository.getSessions().firstOrNull()?.firstOrNull { it.messageCount == 0L }

            if (existingEmpty != null) {
                switchSession(existingEmpty.id)
                return@launchIO
            }

            val sessionId = chatRepository.insertSession("New Analytic Session")
            aiPreferences.activeSessionId().set(sessionId)
            loadSession(sessionId)
        }
    }

    fun switchSession(sessionId: Long) {
        aiPreferences.activeSessionId().set(sessionId)
        loadSession(sessionId)
    }

    fun deleteSession(sessionId: Long) {
        screenModelScope.launchIO {
            chatRepository.deleteSession(sessionId)
            if (state.value.activeSessionId == sessionId) {
                aiPreferences.activeSessionId().set(-1L)
                // The sessions collector will trigger createNewSession if empty
            }
        }
    }

    fun deleteSessions(sessionIds: List<Long>) {
        screenModelScope.launchIO {
            chatRepository.deleteSessions(sessionIds)
            if (state.value.activeSessionId in sessionIds) {
                aiPreferences.activeSessionId().set(-1L)
                // The sessions collector will trigger createNewSession if empty
            }
        }
    }

    fun toggleSessionPin(sessionId: Long, isPinned: Boolean) {
        screenModelScope.launchIO {
            chatRepository.updateSessionPinned(sessionId, !isPinned)
        }
    }

    fun sendMessage(query: String) {
        if (query.isBlank() || state.value.isLoading) return

        sendMessageJob = screenModelScope.launchIO {
            val sessionId = state.value.activeSessionId ?: return@launchIO
            if (chatRepository.getSessionById(sessionId) == null) {
                createNewSession()
                return@launchIO
            }

            // Capture the persisted conversation before inserting this turn, so the current user
            // message is appended exactly once by AiManager.
            val history = state.value.messages.map { AiManager.ChatMessage(it.role, it.content) }
            val isFirstUserMessage = state.value.messages.none { it.role == "user" }
            chatRepository.insertMessage(sessionId, "user", query)

            if (query.trim().equals("/reset", ignoreCase = true)) {
                aiManager.resetCircuitBreaker()
                chatRepository.insertMessage(sessionId, "model", "AI request recovery state cleared.")
                return@launchIO
            }

            mutableState.update { it.copy(isLoading = true, streamingMessage = null) }
            val fullResponse = StringBuilder()
            var lastPublishedAt = 0L
            var lastPublishedLength = 0

            try {
                aiManager.chatWithAssistantStream(query, history).collect { chunk ->
                    fullResponse.append(chunk)
                    val now = System.nanoTime()
                    if (fullResponse.length - lastPublishedLength >= 24 || now - lastPublishedAt >= 80_000_000L) {
                        if (state.value.activeSessionId == sessionId) {
                            mutableState.update { it.copy(streamingMessage = fullResponse.toString()) }
                        }
                        lastPublishedAt = now
                        lastPublishedLength = fullResponse.length
                    }
                }

                val response = fullResponse.toString().ifBlank {
                    "The assistant returned no response. Please try again."
                }
                chatRepository.insertMessage(sessionId, "model", response)
                if (isFirstUserMessage) updateSessionTitle(sessionId, query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                chatRepository.insertMessage(
                    sessionId,
                    "model",
                    "The assistant request failed. Check your connection and provider settings, then try again.",
                )
            } finally {
                if (state.value.activeSessionId == sessionId) {
                    mutableState.update { it.copy(isLoading = false, streamingMessage = null) }
                }
            }
        }
    }

    private suspend fun updateSessionTitle(sessionId: Long, query: String) {
        val title = if (query.length > 30) query.take(27) + "..." else query
        chatRepository.updateSessionTitle(sessionId, title)
    }

    fun toggleSessionSelection(sessionId: Long) {
        mutableState.update { state ->
            val newSelection = if (state.selectedSessionIds.contains(sessionId)) {
                state.selectedSessionIds - sessionId
            } else {
                state.selectedSessionIds + sessionId
            }
            state.copy(selectedSessionIds = newSelection)
        }
    }

    fun clearSessionSelection() {
        mutableState.update { it.copy(selectedSessionIds = emptySet()) }
    }

    fun clearAllSessions() {
        screenModelScope.launchIO {
            chatRepository.deleteAllSessions()
            aiPreferences.activeSessionId().set(-1L)
            createNewSession()
        }
    }

    data class State(
        val activeSessionId: Long? = null,
        val messages: ImmutableList<ChatMessage> = persistentListOf(),
        val isLoading: Boolean = false,
        val selectedSessionIds: Set<Long> = emptySet(),
        val streamingMessage: String? = null,
    )
}

package eu.kanade.tachiyomi.ui.more.settings.screen.ai

import eu.kanade.domain.ai.AiPreferences
import eu.kanade.tachiyomi.data.ai.AiManager
import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.ai.model.ChatSession
import tachiyomi.domain.ai.repository.ChatRepository

class AiAssistantScreenModelTest {

    @Test
    fun `blank provider result becomes a visible assistant fallback`() {
        assertEquals(
            "No response received from the AI provider. Check your connection and API key, then try again.",
            assistantReplyOrFallback("  \n"),
        )
    }

    @Test
    fun `nonblank provider result is preserved`() {
        assertEquals("Reply", assistantReplyOrFallback("Reply"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `blank provider stream is saved as a visible model message`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var screenModel: AiAssistantScreenModel? = null
        try {
            val session = ChatSession(id = 1L, title = "Test", lastMessageAt = 0L, isPinned = false)
            val repository = mockk<ChatRepository>(relaxed = true)
            every { repository.getSessions() } returns flowOf(listOf(session))
            every { repository.getMessagesBySessionId(1L) } returns flowOf(emptyList())
            coEvery { repository.getSessionById(1L) } returns session

            val activeSessionPreference = mockk<Preference<Long>>(relaxed = true)
            every { activeSessionPreference.get() } returns 1L
            val preferences = mockk<AiPreferences>(relaxed = true)
            every { preferences.activeSessionId() } returns activeSessionPreference

            val aiManager = mockk<AiManager>()
            every { aiManager.chatWithAssistantStream(any(), any()) } returns flowOf("")

            val modelReply = CompletableDeferred<String>()
            coEvery { repository.insertMessage(any(), any(), any()) } coAnswers {
                if (secondArg<String>() == "model") modelReply.complete(thirdArg<String>())
                Unit
            }

            val model = AiAssistantScreenModel(repository, preferences, aiManager)
            screenModel = model
            model.state.first { it.activeSessionId == 1L }
            model.sendMessage("hello")

            assertEquals(
                "No response received from the AI provider. Check your connection and API key, then try again.",
                withTimeout(5_000L) { modelReply.await() },
            )
            withTimeout(5_000L) { model.state.first { !it.isLoading } }
        } finally {
            screenModel?.onDispose()
            Dispatchers.resetMain()
        }
    }
}

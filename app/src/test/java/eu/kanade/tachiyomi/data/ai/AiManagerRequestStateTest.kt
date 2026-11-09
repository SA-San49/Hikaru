package eu.kanade.tachiyomi.data.ai

import android.content.Context
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import mihon.feature.airingschedule.SchedulePreferences
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.anime.interactor.GetLibraryAnime
import tachiyomi.domain.history.interactor.GetActivityLog
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.source.interactor.GetFeedSavedSearchCategories
import tachiyomi.domain.source.interactor.GetFeedSavedSearchGlobal
import tachiyomi.domain.source.interactor.GetSavedSearchGlobalFeed
import tachiyomi.domain.source.service.SourceManager

class AiManagerRequestStateTest {

    @Test
    fun `construction clears stale persisted flags and chat stats locks are independent`() {
        val store = MemoryPreferenceStore()
        val preferences = AiPreferences(store)
        preferences.isRequestPending().set(true)
        preferences.isCircuitBreakerTripped().set(true)

        val manager = AiManager(
            context = mockk<Context>(relaxed = true),
            networkHelper = mockk<NetworkHelper>(),
            aiPreferences = preferences,
            extensionManager = mockk<ExtensionManager>(),
            sourceManager = mockk<SourceManager>(),
            getLibraryAnime = mockk<GetLibraryAnime>(),
            json = Json {},
            getHistory = mockk<GetHistory>(),
            getActivityLog = mockk<GetActivityLog>(),
            getFeedCategories = mockk<GetFeedSavedSearchCategories>(),
            getFeedSavedSearches = mockk<GetFeedSavedSearchGlobal>(),
            getSavedSearches = mockk<GetSavedSearchGlobalFeed>(),
            schedulePreferences = mockk<SchedulePreferences>(),
        )

        assertFalse(preferences.isRequestPending().get())
        assertFalse(preferences.isCircuitBreakerTripped().get())

        val chatLock = manager.privateAtomicFlag("chatInFlight")
        val statsLock = manager.privateAtomicFlag("statsInFlight")
        assertFalse(chatLock.get())
        assertFalse(statsLock.get())
        statsLock.set(true)
        assertFalse(chatLock.get())
        chatLock.set(true)
        assertTrue(statsLock.get())
    }

    private fun AiManager.privateAtomicFlag(name: String): AtomicBoolean {
        val field = AiManager::class.java.getDeclaredField(name).apply { isAccessible = true }
        return field.get(this) as AtomicBoolean
    }
}

private class MemoryPreferenceStore : PreferenceStore {
    private val values = mutableMapOf<String, Any>()

    override fun getString(key: String, defaultValue: String): Preference<String> = preference(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long): Preference<Long> = preference(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int): Preference<Int> = preference(key, defaultValue)
    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = preference(key, defaultValue)
    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = preference(key, defaultValue)
    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = preference(key, defaultValue)

    override fun <T> getObject(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = preference(key, defaultValue)

    override fun getAll(): Map<String, *> = values.toMap()

    @Suppress("UNCHECKED_CAST")
    private fun <T> preference(key: String, defaultValue: T): Preference<T> {
        val state = MutableStateFlow(values[key] as? T ?: defaultValue)
        return object : Preference<T> {
            override fun key(): String = key
            override fun get(): T = values[key] as? T ?: defaultValue
            override fun set(value: T) {
                values[key] = value as Any
                state.value = value
            }
            override fun isSet(): Boolean = key in values
            override fun delete() {
                values.remove(key)
                state.value = defaultValue
            }
            override fun defaultValue(): T = defaultValue
            override fun changes(): Flow<T> = flowOf(get())
            override fun stateIn(scope: CoroutineScope): StateFlow<T> = state
        }
    }
}

package eu.kanade.domain.telegram

import tachiyomi.core.common.preference.PreferenceStore

class TelegramPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun apiId() = preferenceStore.getInt("pref_telegram_api_id", DEFAULT_API_ID)

    fun apiHash() = preferenceStore.getString("pref_telegram_api_hash", DEFAULT_API_HASH)

    fun channels() = preferenceStore.getString("pref_telegram_channels", "")

    fun links() = preferenceStore.getString("pref_telegram_links", "")

    fun viewMode() = preferenceStore.getString("pref_telegram_view_mode", "list")

    fun searchIn() = preferenceStore.getString("pref_telegram_search_in", "chat")

    fun cacheMedia() = preferenceStore.getBoolean("pref_telegram_cache_media", false)

    fun browseSources() = preferenceStore.getString("pref_telegram_browse_sources", "")

    fun mergedSeries() = preferenceStore.getString("pref_telegram_merged_series", "")

    companion object {
        const val DEFAULT_API_ID = 2040
        const val DEFAULT_API_HASH = "b18441a1ff607e10a989891a5462e627"
    }
}

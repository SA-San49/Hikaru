package eu.kanade.domain.telegram

import tachiyomi.core.common.preference.PreferenceStore

class TelegramPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun apiId() = preferenceStore.getInt("pref_telegram_api_id", 0)

    fun apiHash() = preferenceStore.getString("pref_telegram_api_hash", "")

    fun channels() = preferenceStore.getString("pref_telegram_channels", "")

    fun links() = preferenceStore.getString("pref_telegram_links", "")

    fun viewMode() = preferenceStore.getString("pref_telegram_view_mode", "list")

    fun searchIn() = preferenceStore.getString("pref_telegram_search_in", "chat")
}

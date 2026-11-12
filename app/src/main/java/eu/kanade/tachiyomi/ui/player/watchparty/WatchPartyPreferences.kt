package eu.kanade.tachiyomi.ui.player.watchparty

import tachiyomi.core.common.preference.PreferenceStore
import java.util.UUID

/**
 * Watch Party settings.
 *
 * The whole feature is optional and lazily initialised: nothing here touches the
 * network. The signalling relay URL is the only piece of infrastructure a user
 * may need to configure, and it is documented in `docs/watch-party.md`.
 */
class WatchPartyPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun enabled() = preferenceStore.getBoolean("pref_watch_party_enabled", true)

    fun displayName() = preferenceStore.getString("pref_watch_party_display_name", "")

    fun relayUrl() = preferenceStore.getString("pref_watch_party_relay_url", DEFAULT_RELAY_URL)

    fun floatingReactions() = preferenceStore.getBoolean("pref_watch_party_floating_reactions", true)

    fun autoOpenPanel() = preferenceStore.getBoolean("pref_watch_party_auto_open_panel", true)

    /** Stable per-install participant id; never derived from account data. */
    fun participantId(): String {
        val pref = preferenceStore.getString("pref_watch_party_participant_id", "")
        val existing = pref.get()
        if (existing.isNotBlank()) return existing
        val generated = UUID.randomUUID().toString()
        pref.set(generated)
        return generated
    }

    /** Resolves the name shown to other participants. */
    fun effectiveDisplayName(): String {
        val configured = displayName().get()
        if (configured.isNotBlank()) return RoomMessageCodec.sanitizeDisplayName(configured)
        return RoomMessageCodec.sanitizeDisplayName("Viewer")
    }

    /** Host suffix from the relay URL so relative relay paths keep working. */
    fun relayHost(): String? {
        val url = relayUrl().get()
        if (url.isBlank()) return null
        return runCatching { java.net.URI(url).host }.getOrNull()
    }

    companion object {
        /**
         * Public, self-hostable signalling relay (see `relay/watch-party-relay.js`
         * in this repository). It only fans out presence and room events; media is
         * never proxied and WebRTC media/data channels are peer-to-peer.
         */
        const val DEFAULT_RELAY_URL = "wss://hikaru-watchparty.fly.dev/ws"
    }
}

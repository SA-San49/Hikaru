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

    /**
     * Signalling mode.
     *
     * [SignallingMode.SERVERLESS_QR] is the default: no Hikaru-operated backend
     * exists, pairing happens by exchanging QR codes. [SignallingMode.RELAY] is
     * the opt-in escape hatch for people who run `relay/watch-party-relay.js`
     * themselves; it is never selected automatically.
     */
    fun signallingMode() = preferenceStore.getEnum("pref_watch_party_signalling_mode", SignallingMode.SERVERLESS_QR)

    /**
     * When true, ICE never queries the internet: the STUN list is empty and only
     * host candidates are exchanged. Used for same-Wi-Fi and hotspot play.
     */
    fun lanOnly() = preferenceStore.getBoolean("pref_watch_party_lan_only", false)

    /** Comma separated STUN URLs; only used when [lanOnly] is false. */
    fun stunServers() = preferenceStore.getString("pref_watch_party_stun_servers", DEFAULT_STUN_SERVERS)

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
         * Self-hostable signalling relay (see `relay/watch-party-relay.js`).
         *
         * Only used when [signallingMode] is [SignallingMode.RELAY]; it fans out
         * room events and never proxies media. Serverless QR pairing needs no
         * server at all.
         */
        const val DEFAULT_RELAY_URL = "wss://hikaru-watchparty.fly.dev/ws"

        /**
         * Public STUN servers used for candidate discovery.
         *
         * STUN only reveals a device's public address to itself; it does not
         * relay any traffic. Two independent providers are listed so one outage
         * does not break pairing.
         */
        const val DEFAULT_STUN_SERVERS = "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302,stun:stun.cloudflare.com:3478"
    }
}

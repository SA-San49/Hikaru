package eu.kanade.tachiyomi.ui.player.watchparty

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

class WatchPartyEngineTest {

    private class FakeBridge : WatchPartyPlayerBridge {
        var pausedState = true
        var position = 10_000L
        var duration: Long? = 120_000L
        var episode: EpisodeIdentity? = EpisodeIdentity(
            animeTitle = "Solo Leveling",
            mediaKey = "151807",
            mediaKeySource = "anilist",
            episodeNumber = 1.0,
            episodeTitle = "You Aren't E-rank, Are You",
            durationMs = 120_000L,
        )

        override fun positionMs(): Long = position
        override fun durationMs(): Long? = duration
        override fun isPaused(): Boolean = pausedState
        override fun isBuffering(): Boolean = false
        override fun localEpisode(): EpisodeIdentity? = episode
        override fun localMediaKey(): Pair<String, String>? = episode?.let {
            (it.mediaKeySource ?: "anilist") to (it.mediaKey ?: "")
        }
        override fun play() { pausedState = false }
        override fun pause() { pausedState = true }
        override fun seekTo(positionMs: Long) { position = positionMs }
        override fun setSpeed(speed: Double) = Unit
    }

    private class FakeTransport : RoomTransport {
        override var listener: RoomTransport.Listener? = null
        val sentFrames = mutableListOf<String>()
        var peerTransport: FakeTransport? = null

        override fun connect() {
            listener?.onStateChanged(RoomTransport.State.Connecting)
        }

        fun openConnection() {
            listener?.onStateChanged(RoomTransport.State.Connected)
        }

        fun reconnectAttempt() {
            listener?.onStateChanged(RoomTransport.State.Connecting)
        }

        override fun send(text: String) {
            sentFrames.add(text)
            peerTransport?.listener?.onMessage(text)
        }

        override fun close() = Unit
    }

    private fun makePrefs(participantId: String, name: String): WatchPartyPreferences {
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreference("pref_watch_party_participant_id", participantId, ""),
                InMemoryPreference("pref_watch_party_display_name", name, ""),
            ),
        )
        return WatchPartyPreferences(store)
    }

    @Test
    fun `initial guest connect reports CONNECTING and subsequent drop reports RECONNECTING`() {
        val transport = FakeTransport()
        val engine = WatchPartyEngine(
            player = FakeBridge(),
            prefs = makePrefs("guest-1", "Guest"),
            transportFactory = { _, _ -> transport },
        )
        assertTrue(engine.startAsGuest("room-123"))
        assertEquals(ConnectionState.CONNECTING, engine.state.value.connection)

        transport.openConnection()
        assertEquals(ConnectionState.CONNECTED, engine.state.value.connection)

        transport.reconnectAttempt()
        assertEquals(ConnectionState.RECONNECTING, engine.state.value.connection)
        engine.leaveRoom()
    }

    @Test
    fun `disconnected guest cannot append phantom chat comments`() {
        val transport = FakeTransport()
        val engine = WatchPartyEngine(
            player = FakeBridge(),
            prefs = makePrefs("guest-1", "Guest"),
            transportFactory = { _, _ -> transport },
        )
        engine.startAsGuest("room-123")
        engine.sendChatMessage("g")
        assertTrue(engine.state.value.chat.isEmpty())
        assertTrue(transport.sentFrames.isEmpty())
        engine.leaveRoom()
    }

    @Test
    fun `chat, replies, comment likes/dislikes, and pre-connect ready sync across host and guest`() {
        val hostTransport = FakeTransport()
        val guestTransport = FakeTransport()
        hostTransport.peerTransport = guestTransport
        guestTransport.peerTransport = hostTransport

        val host = WatchPartyEngine(
            player = FakeBridge(),
            prefs = makePrefs("host-1", "Host"),
            transportFactory = { _, _ -> hostTransport },
        )
        val guest = WatchPartyEngine(
            player = FakeBridge(),
            prefs = makePrefs("guest-1", "Guest"),
            transportFactory = { _, _ -> guestTransport },
        )

        val code = host.createRoom()
        hostTransport.openConnection()
        host.requestReady()

        guest.startAsGuest(code)
        guest.requestReady()
        guestTransport.openConnection()

        assertEquals(2, host.state.value.participants.size)
        assertEquals(2, guest.state.value.participants.size)
        assertEquals(ReadyState.READY, host.state.value.readyStates["guest-1"])
        assertEquals(ReadyState.READY, guest.state.value.readyStates["host-1"])

        host.sendChatMessage("First comment")
        assertEquals(1, guest.state.value.chat.size)
        val parentId = guest.state.value.chat.first().id

        guest.sendChatMessage("Nice reply", replyToId = parentId)
        assertEquals(2, host.state.value.chat.size)
        assertEquals(parentId, host.state.value.chat[1].replyToId)

        guest.toggleCommentVote(parentId, WatchPartyEngine.COMMENT_LIKE)
        assertTrue("guest-1" in host.state.value.commentFeedback[parentId]?.likedBy.orEmpty())
        assertTrue(host.state.value.reactions.isEmpty())

        guest.toggleCommentVote(parentId, WatchPartyEngine.COMMENT_DISLIKE)
        val feedbackAfterSwitch = host.state.value.commentFeedback[parentId]
        assertFalse("guest-1" in feedbackAfterSwitch?.likedBy.orEmpty())
        assertTrue("guest-1" in feedbackAfterSwitch?.dislikedBy.orEmpty())

        guest.toggleCommentVote(parentId, WatchPartyEngine.COMMENT_DISLIKE)
        assertTrue(host.state.value.commentFeedback[parentId] == null)

        host.leaveRoom()
        guest.leaveRoom()
    }
}

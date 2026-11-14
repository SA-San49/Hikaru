package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import eu.kanade.tachiyomi.ui.player.watchparty.RoomTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** File-level alias so the assertions stay readable. */
private typealias RoomTransportState = RoomTransport.State

class PeerHubTest {

    /** A link that records what it was asked to send. */
    private class FakeLink(
        override val peerId: String,
        override val peerName: String = "Guest $peerId",
        var open: Boolean = true,
        var acceptFrames: Boolean = true,
    ) : PeerLink {
        val sent = mutableListOf<String>()
        var closed = false

        override val isOpen: Boolean get() = open

        override fun send(text: String): Boolean {
            if (!open || !acceptFrames) return false
            sent += text
            return true
        }

        override fun close() {
            closed = true
            open = false
        }
    }

    private fun hostHub(
        local: MutableList<String> = mutableListOf(),
        lost: MutableList<String> = mutableListOf(),
    ) = PeerHub(isHost = true, selfId = "host", deliverLocal = { local += it }, onPeerLost = { lost += it })

    private fun guestHub(link: FakeLink, local: MutableList<String> = mutableListOf()) =
        PeerHub(isHost = false, selfId = "me", deliverLocal = { local += it }).also { it.attach(link) }

    // <editor-fold desc="Host fan-out">

    @Test
    fun `the host reaches every guest`() {
        val hub = hostHub()
        val a = FakeLink("a")
        val b = FakeLink("b")
        val c = FakeLink("c")
        listOf(a, b, c).forEach(hub::attach)

        hub.broadcastFromSelf("play")

        assertEquals(listOf("play"), a.sent)
        assertEquals(listOf("play"), b.sent)
        assertEquals(listOf("play"), c.sent)
    }

    @Test
    fun `a guest frame reaches the host and the other guests`() {
        val local = mutableListOf<String>()
        val hub = hostHub(local = local)
        val a = FakeLink("a")
        val b = FakeLink("b")
        hub.attach(a)
        hub.attach(b)

        hub.onFrameFrom("a", "hello from A")

        // Host sees it…
        assertEquals(listOf("hello from A"), local)
        // …and B does too, which is what makes three-way chat work over unicast.
        assertEquals(listOf("hello from A"), b.sent)
    }

    @Test
    fun `the sender never receives its own frame back`() {
        val hub = hostHub()
        val a = FakeLink("a")
        val b = FakeLink("b")
        hub.attach(a)
        hub.attach(b)

        hub.onFrameFrom("a", "echo?")

        assertTrue(a.sent.isEmpty(), "frame bounced back to its sender")
        assertEquals(listOf("echo?"), b.sent)
    }

    @Test
    fun `a three guest conversation is fully connected through the host`() {
        val hostLocal = mutableListOf<String>()
        val hub = hostHub(local = hostLocal)
        val a = FakeLink("a")
        val b = FakeLink("b")
        val c = FakeLink("c")
        listOf(a, b, c).forEach(hub::attach)

        hub.onFrameFrom("b", "from b")

        assertEquals(listOf("from b"), hostLocal)
        assertEquals(listOf("from b"), a.sent)
        assertEquals(listOf("from b"), c.sent)
        assertTrue(b.sent.isEmpty())
    }

    // </editor-fold>

    // <editor-fold desc="Guest side">

    @Test
    fun `a guest has exactly one link to the host`() {
        val hostLink = FakeLink("host")
        val guest = guestHub(hostLink)

        guest.broadcastFromSelf("chat")

        assertEquals(listOf("chat"), hostLink.sent)
        assertEquals(1, guest.size)
    }

    @Test
    fun `a frame from the host is delivered locally and never re-forwarded`() {
        val local = mutableListOf<String>()
        val hostLink = FakeLink("host")
        val guest = PeerHub(isHost = false, selfId = "me", deliverLocal = { local += it })
        guest.attach(hostLink)

        guest.onFrameFrom("host", "host says hi")

        assertEquals(listOf("host says hi"), local)
        // A guest must not echo it back to the host, or the room loops forever.
        assertTrue(hostLink.sent.isEmpty())
    }

    @Test
    fun `a guest ignores frames from an unknown peer`() {
        val local = mutableListOf<String>()
        val guest = guestHub(FakeLink("host"), local)

        guest.onFrameFrom("stranger", "spoofed")

        assertTrue(local.isEmpty(), "a stranger's frame was delivered")
    }

    // </editor-fold>

    // <editor-fold desc="Failure isolation">

    @Test
    fun `a guest that keeps refusing frames is dropped and the rest keep working`() {
        val lost = mutableListOf<String>()
        val hub = hostHub(lost = lost)
        val healthy = FakeLink("healthy")
        val broken = FakeLink("broken", acceptFrames = false)
        hub.attach(healthy)
        hub.attach(broken)

        repeat(PeerHub.MAX_CONSECUTIVE_FAILURES) { hub.broadcastFromSelf("sync $it") }

        assertEquals(PeerHub.MAX_CONSECUTIVE_FAILURES, healthy.sent.size, "one bad guest blocked the broadcast")
        assertEquals(listOf("broken"), lost)
        assertEquals(listOf("healthy"), hub.peerIds)
    }

    @Test
    fun `a momentary backpressure blip does not eject a guest`() {
        val lost = mutableListOf<String>()
        val hub = hostHub(lost = lost)
        val jittery = FakeLink("jittery")
        hub.attach(jittery)

        jittery.acceptFrames = false
        hub.broadcastFromSelf("position 1") // dropped by the native buffer
        jittery.acceptFrames = true
        hub.broadcastFromSelf("position 2")

        assertTrue(lost.isEmpty(), "a guest was ejected for one missed heartbeat")
        assertEquals(listOf("position 2"), jittery.sent)
    }

    @Test
    fun `a dropped frame is not replayed later`() {
        val hub = hostHub()
        val jittery = FakeLink("jittery", acceptFrames = false)
        hub.attach(jittery)

        hub.broadcastFromSelf("stale position")
        jittery.acceptFrames = true
        hub.broadcastFromSelf("fresh position")

        // Playback ticks supersede one another; replaying the stale one after
        // the fresh one would rewind the guest.
        assertEquals(listOf("fresh position"), jittery.sent)
    }

    @Test
    fun `successful writes reset the failure count`() {
        val lost = mutableListOf<String>()
        val hub = hostHub(lost = lost)
        val flaky = FakeLink("flaky")
        hub.attach(flaky)

        repeat(PeerHub.MAX_CONSECUTIVE_FAILURES - 1) {
            flaky.acceptFrames = false
            hub.broadcastFromSelf("fail $it")
        }
        flaky.acceptFrames = true
        hub.broadcastFromSelf("ok")
        repeat(PeerHub.MAX_CONSECUTIVE_FAILURES - 1) {
            flaky.acceptFrames = false
            hub.broadcastFromSelf("fail again $it")
        }

        assertTrue(lost.isEmpty(), "failure count was not reset by a success")
    }

    @Test
    fun `a closed link is reported exactly once`() {
        val lost = mutableListOf<String>()
        val hub = hostHub(lost = lost)
        hub.attach(FakeLink("a"))

        hub.onLinkClosed("a")
        hub.onLinkClosed("a") // the stack may report it twice
        hub.onLinkClosed("never-existed")

        assertEquals(listOf("a"), lost)
    }

    @Test
    fun `a closed link is excluded from the next broadcast`() {
        val hub = hostHub()
        val a = FakeLink("a")
        val b = FakeLink("b")
        hub.attach(a)
        hub.attach(b)

        b.open = false
        hub.broadcastFromSelf("first")

        assertEquals(listOf("first"), a.sent)
        b.open = true
        hub.broadcastFromSelf("second")

        // A link that reported CLOSED was removed; it must not silently reappear.
        assertTrue(b.sent.isEmpty(), "a closed link kept receiving: ${b.sent}")
        assertEquals(listOf("a"), hub.peerIds)
    }

    @Test
    fun `a reconnecting guest replaces its old link instead of duplicating frames`() {
        val hub = hostHub()
        val old = FakeLink("a")
        hub.attach(old)

        val reconnected = FakeLink("a")
        hub.attach(reconnected)

        assertTrue(old.closed, "the stale link was left open")
        assertEquals(1, hub.size)

        hub.broadcastFromSelf("only once")
        assertEquals(listOf("only once"), reconnected.sent)
    }

    @Test
    fun `a frame from a removed guest is dropped`() {
        val local = mutableListOf<String>()
        val hub = hostHub(local = local)
        val a = FakeLink("a")
        hub.attach(a)
        hub.detach("a")

        hub.onFrameFrom("a", "late frame")

        assertTrue(local.isEmpty(), "a departed guest could still inject frames")
    }

    @Test
    fun `a link that throws does not abort the whole broadcast`() {
        val lost = mutableListOf<String>()
        val hub = hostHub(lost = lost)
        val thrower = object : PeerLink {
            override val peerId = "thrower"
            override val peerName = "Thrower"
            override val isOpen = true
            override fun send(text: String): Boolean = throw IllegalStateException("native crash")
            override fun close() = Unit
        }
        val healthy = FakeLink("healthy")
        hub.attach(thrower)
        hub.attach(healthy)

        repeat(PeerHub.MAX_CONSECUTIVE_FAILURES) { hub.broadcastFromSelf("play $it") }

        // One crashed peer must not cost the healthy one a single frame.
        assertEquals(PeerHub.MAX_CONSECUTIVE_FAILURES, healthy.sent.size)
        assertEquals(listOf("thrower"), lost)
    }

    // </editor-fold>

    // <editor-fold desc="Lifecycle">

    @Test
    fun `closing the hub closes every link`() {
        val hub = hostHub()
        val a = FakeLink("a")
        val b = FakeLink("b")
        hub.attach(a)
        hub.attach(b)

        hub.closeAll()

        assertTrue(a.closed)
        assertTrue(b.closed)
        assertEquals(0, hub.size)
    }

    @Test
    fun `closing twice is harmless`() {
        val hub = hostHub()
        hub.attach(FakeLink("a"))
        hub.closeAll()
        hub.closeAll()
        assertEquals(0, hub.size)
    }

    @Test
    fun `the transport adapter fans out like the hub`() {
        val hub = hostHub()
        val a = FakeLink("a")
        hub.attach(a)
        val transport = hub.asTransport()

        transport.send("presence")

        assertEquals(listOf("presence"), a.sent)
    }

    @Test
    fun `the transport adapter reports connected because pairing already happened`() {
        val states = mutableListOf<RoomTransportState>()
        val hub = hostHub()
        val transport = hub.asTransport()
        transport.listener = object : RoomTransport.Listener {
            override fun onStateChanged(state: RoomTransportState) {
                states += state
            }

            override fun onMessage(text: String) = Unit
        }

        transport.connect()

        assertEquals(listOf<RoomTransport.State>(RoomTransport.State.Connected), states)
    }

    @Test
    fun `the transport adapter closes every link`() {
        val hub = hostHub()
        val a = FakeLink("a")
        hub.attach(a)

        hub.asTransport().close()

        assertTrue(a.closed)
    }

    @Test
    fun `the room is capped before the hub becomes a mesh`() {
        assertTrue(PeerHub.canAcceptAnotherGuest(0))
        assertTrue(PeerHub.canAcceptAnotherGuest(PeerHub.MAX_GUESTS - 1))
        assertFalse(PeerHub.canAcceptAnotherGuest(PeerHub.MAX_GUESTS))
    }

    // </editor-fold>
}

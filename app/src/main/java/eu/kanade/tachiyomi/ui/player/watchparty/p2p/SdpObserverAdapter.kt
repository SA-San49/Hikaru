package eu.kanade.tachiyomi.ui.player.watchparty.p2p

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * `SdpObserver` with no-op defaults.
 *
 * `org.webrtc`'s observer interface has four callbacks of which every call site
 * overrides exactly two. Implementing it inline four times, twice per session,
 * buries the interesting part (what happens on success, what happens on failure)
 * inside boilerplate. Overriding only what matters also makes a *missing* error
 * handler obvious during review, which is what you want in signalling code.
 */
internal open class SdpObserverAdapter : SdpObserver {

    override fun onCreateSuccess(description: SessionDescription) = Unit

    override fun onSetSuccess() = Unit

    override fun onCreateFailure(error: String) = Unit

    override fun onSetFailure(error: String) = Unit
}

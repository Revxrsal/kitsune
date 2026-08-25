package revxrsal.kitsune.event

import revxrsal.kitsune.app.application

object NativeEventBridge {

    /**
     * An event raised by the frontend, addressed by [ordinal].
     *
     * [payload] arrives with the ordinal already stripped by the host, so the
     * deserializer reads it from index zero.
     */
    @JvmStatic
    @Suppress("unused") // <--- invoked by rust
    fun eventReceived(ordinal: Int, payload: ByteArray) {
        application.eventsHandler.dispatch(ordinal, payload, source = EventSource.JAVASCRIPT)
    }

    /**
     * Hands an event raised here to the host, which prefixes [ordinal] and pushes
     * the frame to the frontend.
     *
     * Returns whether the host took it. It answers `false` when the outbound queue
     * is full — the call is made from whatever thread raised the event, and the
     * host will not block that thread on a webview that has stopped draining.
     */
    @JvmStatic
    external fun kotlinEmittedEvent(ordinal: Int, payload: ByteArray): Boolean

}

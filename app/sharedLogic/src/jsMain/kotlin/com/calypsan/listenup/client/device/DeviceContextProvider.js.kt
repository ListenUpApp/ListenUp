package com.calypsan.listenup.client.device

/**
 * Browser device classification.
 *
 * Unimplemented by design (web seam check): a browser spans phone through desktop and cannot be
 * typed from a static signal the way a native app can — the real answer is viewport plus pointer
 * capability, re-evaluated on resize, which is a design rather than a port. Throws rather than
 * guessing, so a wrong classification cannot silently reach the UI.
 */
actual class DeviceContextProvider {
    actual fun detect(): DeviceContext = TODO("web: classify by viewport and pointer capability")
}

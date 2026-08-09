package com.calypsan.listenup.client.device

import kotlinx.browser.window

/**
 * Browser device classification from pointer capability plus viewport width.
 *
 * A coarse primary pointer marks touch hardware; the viewport then splits phone from tablet at
 * the same 760px the web layout treats as its thin-fallback boundary. Everything else — a fine
 * pointer, whatever the size — is a desktop browser.
 *
 * Static by design for now: [detect] classifies the moment it is called, matching the expect
 * contract every platform shares. Re-classification on resize (a browser can move between a
 * phone-sized window and a monitor) is a later design that belongs to whoever owns the resize
 * signal, not to this snapshot.
 */
actual class DeviceContextProvider {
    actual fun detect(): DeviceContext {
        val coarsePointer = window.matchMedia("(pointer: coarse)").matches
        val type =
            when {
                coarsePointer && window.innerWidth < TABLET_MIN_WIDTH -> DeviceType.Phone
                coarsePointer -> DeviceType.Tablet
                else -> DeviceType.Desktop
            }
        return DeviceContext(type)
    }
}

/** The web layout's own thin-fallback boundary — one number, shared meaning. */
private const val TABLET_MIN_WIDTH = 760

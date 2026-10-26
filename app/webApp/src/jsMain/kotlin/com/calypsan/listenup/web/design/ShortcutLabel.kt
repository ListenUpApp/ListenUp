package com.calypsan.listenup.web.design

import kotlinx.browser.window

/**
 * The command palette's shortcut as the reader's own keyboard labels it: "⌘K" on a Mac, iPhone or
 * iPad, "Ctrl K" everywhere else. The handler takes either modifier; only the hint has to choose.
 *
 * [platform] is the browser's platform string — `navigator.userAgentData.platform` where the
 * browser offers it, else the older `navigator.platform`.
 */
internal fun paletteShortcutLabel(platform: String = browserPlatform()): String =
    if (isApplePlatform(platform)) "⌘K" else "Ctrl K"

/** Whether [platform] is a Mac, iPhone or iPad, whose keyboards label the command key ⌘. */
internal fun isApplePlatform(platform: String = browserPlatform()): Boolean = APPLE_PLATFORM.containsMatchIn(platform)

private val APPLE_PLATFORM = Regex("mac|iphone|ipad|ipod", RegexOption.IGNORE_CASE)

/** The browser's platform string: `navigator.userAgentData.platform` where the browser offers it, else `navigator.platform`. */
internal fun browserPlatform(): String {
    val navigator = window.navigator.asDynamic()
    val hints = navigator.userAgentData
    val fromHints = if (hints != null && hints != undefined) hints.platform as? String else null
    return fromHints?.takeIf { it.isNotBlank() } ?: (navigator.platform as? String).orEmpty()
}

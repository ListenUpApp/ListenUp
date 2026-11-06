package com.calypsan.listenup.web.dom

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Navigator
import org.w3c.dom.clipboard.Clipboard
import org.w3c.files.File
import kotlin.js.Date
import kotlin.js.Promise

/*
 * The browser APIs the web client calls that Kotlin/JS's bundled `org.w3c.*` declarations predate, declared once
 * and typed. Nothing else in the web client reaches the DOM through `asDynamic()` or `js(…)` without a listed
 * reason (`WebDomCallsAreTypedRule`), so a misspelt option or method is a compile error rather than a silent
 * runtime no-op. Where the stdlib already declares a call (`setPointerCapture`, `scrollIntoView`, `scrollTo`,
 * `createImageBitmap`, `StorageEvent`, `AddEventListenerOptions`) the stdlib is used directly.
 *
 * Kotlin/JS has no external extension functions, so each API is an external interface that the real object is
 * cast to, behind a small ordinary extension that hides the cast.
 */

/**
 * The one place an empty object literal is created for an option bag; [init] fills it in.
 *
 * [T] is trusted to be an `external interface` with only optional fields.
 */
internal fun <T> optionBag(init: T.() -> Unit): T = js("{}").unsafeCast<T>().apply(init)

// ---------------------------------------------------------------------------------------------------------------
// Focus (HTML Living Standard `FocusOptions`; Chrome 64, Firefox 68, Safari 15)
// ---------------------------------------------------------------------------------------------------------------

/** `FocusOptions`: the argument `HTMLElement.focus` takes, which Kotlin's own `focus()` omits. */
internal external interface FocusOptions {
    var preventScroll: Boolean?
}

/** `FocusOptions` that focus without scrolling any box to reveal the element. */
internal fun focusWithoutScrollingOptions(): FocusOptions = optionBag { preventScroll = true }

private external interface FocusableWithOptions {
    fun focus(options: FocusOptions)
}

/** `HTMLElement.focus(options)`. */
internal fun HTMLElement.focus(options: FocusOptions) {
    unsafeCast<FocusableWithOptions>().focus(options)
}

// ---------------------------------------------------------------------------------------------------------------
// Web Animations (`Element.animate`; Chrome 36, Firefox 48, Safari 13.1)
// ---------------------------------------------------------------------------------------------------------------

/** A keyframe that fades and/or transforms: the only properties `animateComposited` can spell. */
internal external interface MotionFrame {
    var opacity: Double?
    var transform: String?
    var transformOrigin: String?
}

/** A new empty [MotionFrame]. */
internal fun motionFrame(): MotionFrame = optionBag {}

/**
 * A keyframe whose one property is a CSS custom property (`--mp-p`) chosen at runtime. Its key is data, not a field
 * name, so it is the one keyframe that cannot be an interface: it is built here and handed on as an opaque object.
 */
internal fun customPropertyFrame(
    property: String,
    value: Number,
): Any {
    val frame = js("{}")
    frame[property] = value
    return frame
}

/** `KeyframeAnimationOptions`: timing for [animate]. Unset fields take the browser's defaults. */
internal external interface AnimationTiming {
    var duration: Number?
    var easing: String?
    var delay: Number?
    var fill: String?
}

/** A new [AnimationTiming] with [duration] milliseconds and [easing]; [init] sets the rest. */
internal fun animationTiming(
    duration: Number,
    easing: String,
    init: AnimationTiming.() -> Unit = {},
): AnimationTiming =
    optionBag {
        this.duration = duration
        this.easing = easing
        init()
    }

/** A running `Animation`, as much of it as the web client drives. */
internal external interface WebAnimation {
    fun cancel()

    /** Called when the animation finishes; not called if it is cancelled. */
    var onfinish: ((dynamic) -> Unit)?
}

private external interface Animatable {
    fun animate(
        keyframes: Array<out Any>,
        options: AnimationTiming,
    ): WebAnimation
}

/** `Element.animate(keyframes, options)`. */
internal fun Element.animate(
    keyframes: Array<out Any>,
    options: AnimationTiming,
): WebAnimation = unsafeCast<Animatable>().animate(keyframes, options)

// ---------------------------------------------------------------------------------------------------------------
// Popover (`HTMLElement.showPopover`, `:popover-open`; Chrome 114, Firefox 125, Safari 17)
// ---------------------------------------------------------------------------------------------------------------

/**
 * Whether this browser has the popover API. One feature check for both `showPopover` and the `:popover-open`
 * pseudo-class: engines that lack the first throw a `SyntaxError` on the second. Read per call, not captured, so a
 * spec can take the API away.
 */
internal val popoverApiAvailable: Boolean
    get() =
        js("typeof HTMLElement !== 'undefined' && typeof HTMLElement.prototype.showPopover === 'function'")
            .unsafeCast<Boolean>()

private external interface Popover {
    fun showPopover()
}

/** `HTMLElement.showPopover()`. Call only where [popoverApiAvailable]. */
internal fun HTMLElement.showPopover() {
    unsafeCast<Popover>().showPopover()
}

// ---------------------------------------------------------------------------------------------------------------
// Page Visibility, directory pickers
// ---------------------------------------------------------------------------------------------------------------

private external interface VisibilityDocument {
    val visibilityState: String
}

/** `Document.visibilityState`: `"visible"` or `"hidden"`. */
internal val Document.visibilityState: String
    get() = unsafeCast<VisibilityDocument>().visibilityState

private external interface DirectoryPickedFile {
    val webkitRelativePath: String?
}

/** `File.webkitRelativePath`: `Book/01.m4b` for a file from a directory pick, empty or absent for a loose file. */
internal val File.webkitRelativePath: String?
    get() = unsafeCast<DirectoryPickedFile>().webkitRelativePath

// ---------------------------------------------------------------------------------------------------------------
// Navigator (Web Share, Clipboard presence, User-Agent Client Hints, Media Session)
// ---------------------------------------------------------------------------------------------------------------

/** `ShareData`: what the system share sheet is offered. */
internal external interface ShareData {
    var title: String?
    var text: String?
    var url: String?
}

/** A new [ShareData]. */
internal fun shareData(
    title: String,
    text: String,
    url: String,
): ShareData =
    optionBag {
        this.title = title
        this.text = text
        this.url = url
    }

/** `NavigatorUAData`, as far as the platform string. */
internal external interface UserAgentData {
    val platform: String?
}

/**
 * The parts of `navigator` Kotlin's [Navigator] lacks, or declares as always present when they are not: each
 * `…OrNull` member is the property read for presence, `undefined` meaning this browser (or an insecure origin) has
 * none.
 */
internal external interface NavigatorFeatures {
    fun share(data: ShareData): Promise<Unit>

    @JsName("share")
    val shareOrNull: Any?

    @JsName("clipboard")
    val clipboardOrNull: Clipboard?

    val userAgentData: UserAgentData?

    val mediaSession: MediaSession?
}

/** [this] navigator, read for the features that may be missing. */
internal val Navigator.features: NavigatorFeatures
    get() = unsafeCast<NavigatorFeatures>()

// ---------------------------------------------------------------------------------------------------------------
// Date formatting (ECMAScript `Date`, which Kotlin's facade does not fully expose)
// ---------------------------------------------------------------------------------------------------------------

/** `Intl.DateTimeFormatOptions`, as far as a time of day. */
internal external interface TimeOfDayFormat {
    var hour: String?
    var minute: String?
}

/** Hour without padding, minute with it: "11:42 PM" or "23:42" by locale. */
internal fun timeOfDayFormat(): TimeOfDayFormat =
    optionBag {
        hour = "numeric"
        minute = "2-digit"
    }

private external interface LocaleDate {
    fun toLocaleString(): String

    fun toLocaleTimeString(
        locales: String? = definedExternally,
        options: TimeOfDayFormat,
    ): String
}

/** `Date#toLocaleString()`: date and time in the browser's locale. */
internal fun Date.toLocaleDateTimeString(): String = unsafeCast<LocaleDate>().toLocaleString()

/** `Date#toLocaleTimeString(undefined, options)`: the time of day in the browser's locale. */
internal fun Date.toLocaleTimeOfDay(options: TimeOfDayFormat): String =
    unsafeCast<LocaleDate>().toLocaleTimeString(options = options)

// ---------------------------------------------------------------------------------------------------------------
// ResizeObserver (Chrome 64, Firefox 69, Safari 13.1) and WeakMap
// ---------------------------------------------------------------------------------------------------------------

/** `ResizeObserverOptions`. */
internal external interface ResizeObserverOptions {
    var box: String?
}

/** Observe the border box, which is what places the next sibling, rather than the content box. */
internal fun borderBoxObservation(): ResizeObserverOptions = optionBag { box = "border-box" }

/** The browser's `ResizeObserver`, as much of it as the list, the tab ink and the frame stage use. */
internal external class ResizeObserver(
    callback: (entries: dynamic, observer: dynamic) -> Unit,
) {
    /** Content-box when [options] is omitted. */
    fun observe(
        target: Element,
        options: ResizeObserverOptions = definedExternally,
    )

    fun disconnect()
}

/** The browser's `WeakMap`, as much of it as the sweep uses. */
internal external class WeakMap<K : Any, V : Any> {
    fun get(key: K): V?

    fun set(
        key: K,
        value: V,
    )
}

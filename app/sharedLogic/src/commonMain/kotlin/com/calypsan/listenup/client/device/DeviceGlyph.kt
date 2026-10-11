package com.calypsan.listenup.client.device

/** What kind of device a session came from, for choosing an icon. Platform-neutral. */
enum class DeviceGlyph { Phone, Tablet, Computer, Browser, Tv, Watch, Car, Headset, Cast, Speaker, Unknown }

/**
 * The glyph for a raw session `deviceType` string; case-insensitive; unknown or null is [DeviceGlyph.Unknown].
 *
 * The one place the Devices screen decides what a session looks like, so Android, iOS and web draw the same
 * kind of device: every [DeviceType.wireName] the app reports, plus the web client's `"browser"` and the
 * `"laptop"`, `"cast"` and `"speaker"` names older servers and sessions carry.
 */
fun deviceGlyphFor(deviceType: String?): DeviceGlyph =
    when (deviceType?.lowercase()) {
        "phone" -> DeviceGlyph.Phone
        "tablet" -> DeviceGlyph.Tablet
        "desktop", "laptop" -> DeviceGlyph.Computer
        "browser" -> DeviceGlyph.Browser
        "tv" -> DeviceGlyph.Tv
        "watch" -> DeviceGlyph.Watch
        "auto" -> DeviceGlyph.Car
        "xr" -> DeviceGlyph.Headset
        "cast" -> DeviceGlyph.Cast
        "speaker" -> DeviceGlyph.Speaker
        else -> DeviceGlyph.Unknown
    }

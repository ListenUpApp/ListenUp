package com.calypsan.listenup.server.metadata.audible

/**
 * Audible regional storefronts supported by the metadata service.
 *
 * Each region's catalog is independent — a title on audible.com may not exist on
 * audible.co.uk and will return 404. The [apiHost] drives HTTPS request routing;
 * the [localeCookie] forces the correct catalog when Audible's CDN would otherwise
 * geo-detect from IP and serve wrong content. [locale] is the API-layer locale
 * string needed for some response_groups.
 *
 * Ported from Go at `server/internal/metadata/audible/types.go`. Regions match
 * exactly — no extras, no omissions.
 */
enum class AudibleRegion(
    /** Operator-facing short code used in config and URL params. */
    val code: String,
    /** Full API hostname, e.g. `api.audible.com`. */
    val apiHost: String,
    /** Cookie header value that pins the regional catalog. */
    val localeCookie: String,
    /** BCP-47 locale string for API params, e.g. `en-US`. */
    val locale: String,
) {
    US(
        code = "us",
        apiHost = "api.audible.com",
        localeCookie = "lc-main=en_US; i18n-prefs=USD",
        locale = "en-US",
    ),
    UK(
        code = "uk",
        apiHost = "api.audible.co.uk",
        localeCookie = "lc-acbuk=en_GB; i18n-prefs=GBP",
        locale = "en-GB",
    ),
    DE(
        code = "de",
        apiHost = "api.audible.de",
        localeCookie = "lc-acbde=de_DE; i18n-prefs=EUR",
        locale = "de-DE",
    ),
    FR(
        code = "fr",
        apiHost = "api.audible.fr",
        localeCookie = "lc-acbfr=fr_FR; i18n-prefs=EUR",
        locale = "fr-FR",
    ),
    AU(
        code = "au",
        apiHost = "api.audible.com.au",
        localeCookie = "lc-acbau=en_AU; i18n-prefs=AUD",
        locale = "en-AU",
    ),
    CA(
        code = "ca",
        apiHost = "api.audible.ca",
        localeCookie = "lc-acbca=en_CA; i18n-prefs=CAD",
        locale = "en-CA",
    ),
    JP(
        code = "jp",
        apiHost = "api.audible.co.jp",
        localeCookie = "lc-acbjp=ja_JP; i18n-prefs=JPY",
        locale = "ja-JP",
    ),
    IT(
        code = "it",
        apiHost = "api.audible.it",
        localeCookie = "lc-acbit=it_IT; i18n-prefs=EUR",
        locale = "it-IT",
    ),
    IN(
        code = "in",
        apiHost = "api.audible.in",
        localeCookie = "lc-acbin=en_IN; i18n-prefs=INR",
        locale = "en-IN",
    ),
    ES(
        code = "es",
        apiHost = "api.audible.es",
        localeCookie = "lc-acbes=es_ES; i18n-prefs=EUR",
        locale = "es-ES",
    ),
    ;

    companion object {
        /** Returns the region whose [code] matches, case-insensitively, or null. */
        fun fromCodeOrNull(code: String): AudibleRegion? =
            entries.firstOrNull { it.code == code.lowercase() }
    }
}

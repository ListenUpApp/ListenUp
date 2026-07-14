package com.calypsan.listenup.server.metadata.audible

/**
 * Server-side routing details for each [AudibleRegion].
 *
 * These fields are **not** part of the contract layer — they are HTTP
 * implementation details that only `:server` needs. The canonical enum lives
 * in `:contract` at [AudibleRegion]; this extension property keeps the
 * server-specific concerns out of it.
 */
internal val AudibleRegion.apiHost: String
    get() =
        when (this) {
            AudibleRegion.US -> "api.audible.com"
            AudibleRegion.UK -> "api.audible.co.uk"
            AudibleRegion.DE -> "api.audible.de"
            AudibleRegion.FR -> "api.audible.fr"
            AudibleRegion.AU -> "api.audible.com.au"
            AudibleRegion.CA -> "api.audible.ca"
            AudibleRegion.JP -> "api.audible.co.jp"
            AudibleRegion.IT -> "api.audible.it"
            AudibleRegion.IN -> "api.audible.in"
            AudibleRegion.ES -> "api.audible.es"
        }

/**
 * The Audible website host for this region, used for web-scraping endpoints
 * such as the contributor author page (`/author/x/{asin}`).
 */
internal val AudibleRegion.webHost: String
    get() =
        when (this) {
            AudibleRegion.US -> "www.audible.com"
            AudibleRegion.UK -> "www.audible.co.uk"
            AudibleRegion.DE -> "www.audible.de"
            AudibleRegion.FR -> "www.audible.fr"
            AudibleRegion.AU -> "www.audible.com.au"
            AudibleRegion.CA -> "www.audible.ca"
            AudibleRegion.JP -> "www.audible.co.jp"
            AudibleRegion.IT -> "www.audible.it"
            AudibleRegion.IN -> "www.audible.in"
            AudibleRegion.ES -> "www.audible.es"
        }

/**
 * The storefront locale cookie for this region, sent on web-host scrapes to force the correct
 * regional catalog.
 *
 * Historically this cookie also turned Audible's cookie-less 503 into a 200, but that no longer holds:
 * the `www` host now bot-blocks datacenter clients with a uniform 503 regardless of the cookie, so the
 * web-scrape features (contributor match, product-tag/mood enrichment) are degraded — see Finding
 * #18b. The cookie is retained for when a future scrape provider can reach the site again.
 */
internal fun AudibleRegion.localeCookie(): String =
    when (this) {
        AudibleRegion.US -> "lc-main=en_US; i18n-prefs=USD"
        AudibleRegion.UK -> "lc-acbuk=en_GB; i18n-prefs=GBP"
        AudibleRegion.DE -> "lc-acbde=de_DE; i18n-prefs=EUR"
        AudibleRegion.FR -> "lc-acbfr=fr_FR; i18n-prefs=EUR"
        AudibleRegion.AU -> "lc-acbau=en_AU; i18n-prefs=AUD"
        AudibleRegion.CA -> "lc-acbca=en_CA; i18n-prefs=CAD"
        AudibleRegion.JP -> "lc-acbjp=ja_JP; i18n-prefs=JPY"
        AudibleRegion.IT -> "lc-acbit=it_IT; i18n-prefs=EUR"
        AudibleRegion.IN -> "lc-acbin=en_IN; i18n-prefs=INR"
        AudibleRegion.ES -> "lc-acbes=es_ES; i18n-prefs=EUR"
    }

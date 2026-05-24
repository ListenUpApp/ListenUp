package com.calypsan.listenup.server.metadata.audible

import com.calypsan.listenup.api.metadata.AudibleRegion

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
 * such as the contributor author page (`/author/x/{asin}`). Ported from Go's
 * `Region.WebHost()` in `server/internal/metadata/audible/types.go`.
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

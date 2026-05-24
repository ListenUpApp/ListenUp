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
    get() = when (this) {
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

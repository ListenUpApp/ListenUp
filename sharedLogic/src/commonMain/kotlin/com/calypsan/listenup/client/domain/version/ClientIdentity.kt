package com.calypsan.listenup.client.domain.version

/** This build's identity, announced to the server on every request and used to evaluate version skew. */
internal interface ClientIdentity {
    /** Semver of this app build, e.g. "0.6.0". Drives the `X-Client-Version` header and `Outdated` copy. */
    val version: String

    /** The API contract version this build expects, e.g. "v1" — mirror of [com.calypsan.listenup.api.EXPECTED_API_VERSION]. */
    val apiVersion: String
}

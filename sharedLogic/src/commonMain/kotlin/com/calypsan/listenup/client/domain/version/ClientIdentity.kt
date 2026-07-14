package com.calypsan.listenup.client.domain.version

import com.calypsan.listenup.api.EXPECTED_API_VERSION

/** This build's identity, announced to the server on every request and used to evaluate version skew. */
internal interface ClientIdentity {
    /** Semver of this app build, e.g. "0.6.0". Drives the `X-Client-Version` header and `Outdated` copy. */
    val version: String

    /** The API contract version this build expects, e.g. "v1" — mirror of [com.calypsan.listenup.api.EXPECTED_API_VERSION]. */
    val apiVersion: String
}

/**
 * The real [ClientIdentity] — [version] comes from [CLIENT_VERSION], build-injected from the
 * repo-root VERSION file (see sharedLogic/build.gradle.kts's generateClientVersion task), the
 * single source of truth shared with the server's mirror-image ServerVersion codegen.
 */
internal object DefaultClientIdentity : ClientIdentity {
    override val version: String = CLIENT_VERSION
    override val apiVersion: String = EXPECTED_API_VERSION
}

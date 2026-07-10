package com.calypsan.listenup.api

/**
 * The API contract version this build of the contract module speaks. Both the client (as its
 * expected [com.calypsan.listenup.client.domain.version.ClientIdentity.apiVersion]) and the server
 * (as `ServerIdentity.API_VERSION`) read from here so a mismatch is a single deliberate edit.
 * Bumping it is a contract act — it trips the client's `Outdated` api-mismatch rule on old peers.
 */
const val EXPECTED_API_VERSION: String = "v1"

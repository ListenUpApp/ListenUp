package com.calypsan.listenup.client.domain.version

/** Deterministic [ClientIdentity] for tests. */
internal class FakeClientIdentity(
    override val version: String = "0.6.0",
    override val apiVersion: String = "v1",
) : ClientIdentity

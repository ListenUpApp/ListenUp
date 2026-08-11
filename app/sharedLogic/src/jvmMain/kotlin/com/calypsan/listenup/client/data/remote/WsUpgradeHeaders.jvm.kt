package com.calypsan.listenup.client.data.remote

/** The JVM engines upgrade over a real HTTP request, so the `Authorization` header is sent. */
internal actual val wsUpgradeCarriesHeaders: Boolean = true

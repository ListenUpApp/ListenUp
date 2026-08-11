package com.calypsan.listenup.client.data.remote

/** OkHttp upgrades over a real HTTP request, so the `Authorization` header is sent. */
internal actual val wsUpgradeCarriesHeaders: Boolean = true

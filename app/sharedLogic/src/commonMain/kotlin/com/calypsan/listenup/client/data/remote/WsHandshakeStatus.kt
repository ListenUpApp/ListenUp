package com.calypsan.listenup.client.data.remote

/**
 * Whether this platform can see the HTTP status of a **rejected** WebSocket upgrade.
 *
 * `false` on the browser and true everywhere else, and — like [wsUpgradeCarriesHeaders], which is a
 * different fact about the same handshake — it is a platform fact rather than a preference. The DOM
 * `WebSocket` reports a failed upgrade as a bare `error` event carrying nothing but `isTrusted`:
 * no status, no message, no body. That is deliberate in the standard, so that a page cannot use
 * cross-origin socket handshakes to probe a network it was never granted. Ktor's JS engine has
 * nothing to read, so the [io.ktor.client.plugins.websocket.WebSocketException] it raises cannot
 * name a status the way CIO's, OkHttp's and Darwin's all can.
 *
 * The consequence is the whole reason this exists: on the browser, `/api/rpc/authed` answering
 * **401 to an expired session** and the network simply being down produce the *same* exception. Read
 * as a transport fault — the default — an expired session is reported to the reader as "No internet
 * connection. Check your network." while their connection is fine, and the session never lapses, so
 * nothing routes them to sign in. See [RpcFailureClassifier.isWsHandshakeOfUnknownStatus] for what
 * is done about it.
 */
internal expect val handshakeStatusIsVisible: Boolean

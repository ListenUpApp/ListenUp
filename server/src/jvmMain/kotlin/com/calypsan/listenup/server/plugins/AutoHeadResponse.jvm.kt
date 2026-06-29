package com.calypsan.listenup.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse

actual fun Application.installAutoHeadResponse() {
    install(AutoHeadResponse)
}

package com.calypsan.listenup.core

import platform.Foundation.NSProcessInfo

/** iOS / macOS reads the `LISTENUP_DEBUG` env var; defaults to `false` in release archives. */
actual val isDebugBuild: Boolean =
    NSProcessInfo.processInfo.environment["LISTENUP_DEBUG"] == "true"

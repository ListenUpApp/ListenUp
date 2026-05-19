package com.calypsan.listenup.core

/**
 * On Android we defer to the JVM system property `listenup.debug` so that the shared
 * module doesn't need Android's BuildConfig generation enabled. The host app can set
 * this property in its Application class based on `BuildConfig.DEBUG`.
 */
actual val isDebugBuild: Boolean = System.getProperty("listenup.debug") == "true"

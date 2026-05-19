package com.calypsan.listenup.core

/** Desktop builds read `-Dlistenup.debug=true` at launch; otherwise release-mode. */
actual val isDebugBuild: Boolean = System.getProperty("listenup.debug") == "true"

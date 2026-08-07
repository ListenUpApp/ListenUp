package com.calypsan.listenup.core

// A browser has neither a system property (desktop) nor an environment variable (Apple,
// Linux) to read, and the expect declaration's own guidance is to keep debug-flag uses
// few and intentional. A constant false is the honest answer until a web build actually
// needs to distinguish.
actual val isDebugBuild: Boolean = false

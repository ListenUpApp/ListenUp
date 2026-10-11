package com.calypsan.listenup.web.shell

import kotlin.js.Date

/**
 * ⛔ Read once per composition, not per row. `relativeOrMonthYear` needs a now to measure against,
 * and a reader list that asked the clock for every line could render two rows either side of a
 * midnight boundary — one "today", one "yesterday", for finishes a millisecond apart.
 */
internal fun nowMs(): Long = Date.now().toLong()

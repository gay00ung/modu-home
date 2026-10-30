package io.moduhome.app.core.navigation

/** Briefly rechecks a newly visible route; a missing readback never authorizes another write. */
internal fun guidanceReadbackDelayMillis(elapsedMillis: Long, hasConfirmedGuidance: Boolean): Long =
    if (!hasConfirmedGuidance && elapsedMillis in 0L until 15_000L) 2_000L else 30_000L

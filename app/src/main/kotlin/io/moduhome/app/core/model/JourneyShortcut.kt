package io.moduhome.app.core.model

/** Identifies a saved journey for viewing; it carries no navigation approval. */
internal data class JourneyShortcutRequest(val mode: JourneyMode, val startedAtMillis: Long) {
    init { require(startedAtMillis >= 0) }
}

internal data class JourneyShortcut(
    val request: JourneyShortcutRequest,
    val totalStops: Int,
    val completedStops: Int,
) {
    init {
        require(totalStops in 1..when (request.mode) { JourneyMode.TOGETHER -> 3; JourneyMode.SOLO -> 2 })
        require(completedStops in 0..totalStops)
    }
    val remainingStops: Int get() = totalStops - completedStops
}

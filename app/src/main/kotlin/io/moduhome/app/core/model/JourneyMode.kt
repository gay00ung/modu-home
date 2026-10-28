package io.moduhome.app.core.model

internal enum class JourneyMode(val maxErrands: Int) {
    TOGETHER(maxErrands = 1),
    SOLO(maxErrands = 2),
}

internal enum class ErrandKind {
    PICKUP,
    GROCERIES,
}

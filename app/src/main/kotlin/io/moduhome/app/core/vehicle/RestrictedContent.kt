package io.moduhome.app.core.vehicle

/** A single, non-interactive surface. Limits count Unicode characters, never partial surrogates. */
internal fun DrivingInteraction.restrictContent(messages: List<String>): List<String> {
    val limits = this as? DrivingInteraction.Known
    if (limits?.maximumContentDepth == 0) return emptyList()
    val count = (limits?.maximumContentItems ?: 3).coerceAtMost(3)
    val length = limits?.maximumTextLength
    return messages.take(count).mapNotNull { text ->
        if (length == null || text.codePointCount(0, text.length) <= length) text
        else when (length) {
            0 -> null
            1 -> "…"
            else -> text.substring(0, text.offsetByCodePoints(0, length - 1)) + "…"
        }
    }
}

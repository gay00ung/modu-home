package io.moduhome.app.core.planning

/** A larger time budget, without changing any other condition of the compared plans. */
internal data class AdditionalTimeSuggestion(
    val allowedMinutes: Int,
    val requiredMinutes: Int,
) {
    init {
        require(allowedMinutes in 0..239 && requiredMinutes in (allowedMinutes + 1)..240)
    }

    val increaseMinutes: Int get() = requiredMinutes - allowedMinutes

    companion object {
        fun fromCandidates(allowedMinutes: Int, requiredMinutes: List<Int>): AdditionalTimeSuggestion? {
            require(allowedMinutes in 0..240)
            val minimum = requiredMinutes.filter { it in (allowedMinutes + 1)..240 }.minOrNull() ?: return null
            return AdditionalTimeSuggestion(allowedMinutes, minimum)
        }
    }
}

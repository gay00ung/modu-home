package io.moduhome.app.core.planning

internal data class TimeBudget(
    val directDrivingMinutes: Int,
    val allowedAdditionalMinutes: Int,
) {
    init {
        require(directDrivingMinutes >= 0)
        require(allowedAdditionalMinutes >= 0)
    }

    fun evaluate(
        plannedDrivingMinutes: Int,
        stayMinutes: Int,
        walkingMinutes: Int = 0,
    ): TimeBudgetResult {
        require(plannedDrivingMinutes >= 0)
        require(stayMinutes >= 0)
        require(walkingMinutes >= 0)

        val totalMinutes = plannedDrivingMinutes + stayMinutes + walkingMinutes
        val additionalMinutes = (totalMinutes - directDrivingMinutes).coerceAtLeast(0)
        return TimeBudgetResult(
            totalMinutes = totalMinutes,
            additionalMinutes = additionalMinutes,
            isWithinBudget = additionalMinutes <= allowedAdditionalMinutes,
        )
    }
}

internal data class TimeBudgetResult(
    val totalMinutes: Int,
    val additionalMinutes: Int,
    val isWithinBudget: Boolean,
)

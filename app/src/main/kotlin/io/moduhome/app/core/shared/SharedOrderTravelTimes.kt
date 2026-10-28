package io.moduhome.app.core.shared

/** Logical stop identities stay distinct even when two passengers use the same physical place. */
internal data class SharedJourneyOrder(
    val stopIds: List<String>,
    val places: List<SharedJourneyPlace>,
) {
    init {
        require(stopIds.size in 1..3 && stopIds.all { it.isNotBlank() })
        require(stopIds.distinct().size == stopIds.size)
        require(places.size == stopIds.size + 2)
    }

    val id: String get() = stopIds.joinToString(">")
}

/** Endpoints belong to this occurrence of a leg, not to a reusable pairwise time matrix. */
internal data class SharedOrderedDrivingLeg(
    val from: SharedJourneyPlace,
    val to: SharedJourneyPlace,
    val estimate: SharedDrivingEstimate,
)

internal data class SharedOrderTravelEstimate(
    val order: SharedJourneyOrder,
    val legs: List<SharedOrderedDrivingLeg>,
)

/**
 * An atomic set for all candidate combinations and stop orders of one place selection.
 *
 * The loader supplies the consumed legs of the actual guidance windows for each whole order.
 * Combining those windows, querying routes, checking freshness and issuing SDK commands stay
 * outside this type. No response is borrowed from another order or from legacy pairwise times.
 */
internal class SharedOrderTravelTimes private constructor(
    val directHome: SharedOrderedDrivingLeg,
    private val requestedOrders: Map<String, SharedJourneyOrder>,
    private val routes: Map<String, List<SharedOrderedDrivingLeg>>,
) {
    fun matches(request: SharedJourneyRequest): Boolean =
        SharedJourneyPlanner().orders(request).associateBy { it.id } == requestedOrders

    fun legsFor(order: SharedJourneyOrder): List<SharedOrderedDrivingLeg> {
        require(requestedOrders[order.id] == order) { "조회한 장소와 방문 순서가 달라요" }
        return requireNotNull(routes[order.id]) { "이 방문 순서의 이동시간이 없어요" }
    }

    companion object {
        /** Invalid, duplicate or incomplete responses never produce a usable comparison set. */
        fun create(
            request: SharedJourneyRequest,
            directHome: SharedOrderedDrivingLeg?,
            estimates: List<SharedOrderTravelEstimate>,
        ): SharedOrderTravelTimes? {
            if (directHome == null || directHome.from != request.origin || directHome.to != request.home) return null
            val orders = SharedJourneyPlanner().orders(request)
            if (estimates.size != orders.size || estimates.map { it.order.id }.distinct().size != estimates.size) return null
            val expected = orders.associateBy { it.id }
            val routes = linkedMapOf<String, List<SharedOrderedDrivingLeg>>()
            for (estimate in estimates) {
                val order = expected[estimate.order.id] ?: return null
                if (estimate.order != order || estimate.legs.size != order.places.size - 1) return null
                if (estimate.legs.withIndex().any { (index, leg) ->
                        leg.from != order.places[index] || leg.to != order.places[index + 1]
                    }) return null
                routes[order.id] = estimate.legs.toList()
            }
            return SharedOrderTravelTimes(
                directHome,
                expected.mapValues { (_, order) -> order.copy(stopIds = order.stopIds.toList(), places = order.places.toList()) },
                routes.toMap(),
            )
        }
    }
}

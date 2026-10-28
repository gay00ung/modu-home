package io.moduhome.app.core.planning

/** Each route retains the legs calculated for that exact visit order, including its home leg. */
internal class RoutedJourneyTimes(
    val directHomeMinutes: Int,
    routes: Map<String, List<Int>>,
) {
    val routes: Map<String, List<Int>> = routes.mapValues { (_, legs) -> legs.toList() }.toMap()

    init {
        require(directHomeMinutes in 0..MAX_LEG_MINUTES)
        require(this.routes.isNotEmpty())
        this.routes.forEach { (key, legs) ->
            val order = key.split('>').map { VisitKind.valueOf(it) }
            require(order.size in 1..2 && order.distinct().size == order.size)
            require(legs.size == order.size + 1)
            require(legs.all { it in 0..MAX_LEG_MINUTES })
        }
    }

    fun legsFor(order: List<VisitKind>): List<Int> {
        require(order.size in 1..2 && order.distinct().size == order.size)
        val key = order.joinToString(">") { it.name }
        return requireNotNull(routes[key]) { "이 방문 순서의 경로별 이동시간이 없어요: $key" }
    }

    companion object {
        const val MAX_LEG_MINUTES = 480
    }
}

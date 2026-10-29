package io.moduhome.app.data.shared

import io.moduhome.app.core.navigation.NavigationPlan
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.routing.ObservedRouteLeg
import io.moduhome.app.core.routing.RouteEstimate
import io.moduhome.app.core.routing.RouteEstimateResult
import io.moduhome.app.core.routing.SnappedRoutePoint
import io.moduhome.app.core.shared.AccessCheck
import io.moduhome.app.core.shared.DropoffVerification
import io.moduhome.app.core.shared.SharedJourneyProgress
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.ErrandProposalInbox
import io.moduhome.app.feature.shared.SharedErrandDraft
import io.moduhome.app.feature.shared.SharedGuidanceWindowEstimate
import io.moduhome.app.feature.shared.SharedJourneyDraft
import io.moduhome.app.feature.shared.SharedJourneySession
import io.moduhome.app.feature.shared.SharedPassengerDraft
import io.moduhome.app.feature.shared.SharedProposalAcceptance
import io.moduhome.app.feature.shared.SharedRoutedJourneyEstimates
import io.moduhome.app.feature.shared.validSharedProposalHistory
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/** Local, bounded state only. No network requests or navigation commands occur during decoding. */
internal object SharedJourneyJson {
    private const val MAX_BYTES = 256 * 1024

    fun encode(session: SharedJourneySession): String? = try {
        val json = JSONObject().put("schema", 1).put("draft", writeDraft(session.draft))
            .put("progress", writeProgress(session.progress)).put("acceptedProposals", writeAccepted(session.acceptedProposals))
        session.routing?.let { json.put("routing", writeRouting(it)) }
        json.toString().takeIf { boundedJson(it) }
    } catch (_: Exception) { null }

    fun decode(saved: String, nowMillis: Long): SharedJourneySession {
        if (!boundedJson(saved)) return SharedJourneySession()
        var fallback = SharedJourneySession()
        return try {
            val json = JSONObject(saved)
            require(integer(json, "schema") == 1)
            val draft = readDraft(json.getJSONObject("draft"))
            fallback = SharedJourneySession(draft)
            val accepted = readAccepted(json)
            fallback = SharedJourneySession(draft, acceptedProposals = accepted)
            val progress = readProgress(json.getJSONObject("progress"))
            val routing = optionalObject(json, "routing")?.let { readRouting(it, draft) ?: return fallback }
            SharedJourneySession.restore(draft, progress, routing, nowMillis, accepted)
        } catch (_: Exception) { fallback }
    }

    private fun writeAccepted(history: List<SharedProposalAcceptance>): JSONArray {
        require(validSharedProposalHistory(history))
        val accepted = JSONArray()
        history.forEach { record ->
            val proposal = record.proposal
            accepted.put(JSONObject().put("appliedAt", record.appliedAtMillis).put("proposal", JSONObject()
                .put("id", proposal.id).put("revision", proposal.revision).put("createdAt", proposal.createdAtMillis)
                .put("expiresAt", proposal.expiresAtMillis).put("kind", proposal.kind.name).put("place", writePoint(proposal.place))
                .put("stay", proposal.stayMinutes).put("walking", proposal.walkingMinutesOneWay).put("ready", proposal.ready)
                .put("deadline", proposal.deadlineAtMillis)))
        }
        return accepted
    }

    private fun readAccepted(json: JSONObject): List<SharedProposalAcceptance> {
        if (!json.has("acceptedProposals")) return emptyList()
        val accepted = array(json, "acceptedProposals", 0..ErrandProposalInbox.MAX_ENTRIES)
        return (0 until accepted.length()).map { index ->
            val record = accepted.getJSONObject(index)
            val proposal = record.getJSONObject("proposal")
            SharedProposalAcceptance(ErrandProposal(
                id = text(proposal, "id", 64), revision = integer(proposal, "revision"),
                createdAtMillis = long(proposal, "createdAt"), expiresAtMillis = long(proposal, "expiresAt"),
                kind = VisitKind.valueOf(text(proposal, "kind", 32)), place = readPoint(proposal.getJSONObject("place")),
                stayMinutes = integer(proposal, "stay"), walkingMinutesOneWay = integer(proposal, "walking"),
                ready = boolean(proposal, "ready"), deadlineAtMillis = optionalLong(proposal, "deadline"),
            ), long(record, "appliedAt"))
        }.also { require(validSharedProposalHistory(it)) }
    }

    private fun writeDraft(draft: SharedJourneyDraft): JSONObject {
        require(draft.passengers.size in 1..2)
        require(draft.allowedAdditionalMinutes in 0..240)
        val people = JSONArray()
        draft.passengers.forEach { person ->
            require(person.stopMinutes in 0..30 && (person.maximumOnboardMinutes == null || person.maximumOnboardMinutes in 0..480))
            people.put(JSONObject().put("id", checkedText(person.id, 64)).put("name", checkedText(person.name, 80))
                .put("dropoff", person.dropoff?.let(::writePoint)).put("doorstep", person.doorstepDropoffConfirmed)
                .put("doorstepAt", person.doorstepConfirmedAtMillis).put("stop", person.stopMinutes)
                .put("deadline", person.latestArrivalAtMillis).put("onboard", person.maximumOnboardMinutes)
                .put("verification", JSONObject().put("vehicle", person.verification.vehicleAccess.name)
                    .put("pedestrian", person.verification.pedestrianConnection.name)
                    .put("source", checkedText(person.verification.source, 500)).put("checkedAt", person.verification.checkedAtMillis)))
        }
        val json = JSONObject().put("home", draft.driverHome?.let(::writePoint))
            .put("budget", draft.allowedAdditionalMinutes).put("passengers", people)
        draft.errand?.let { errand ->
            require(errand.stayMinutes in 0..180 && errand.walkingMinutesOneWay in 0..30)
            json.put("errand", JSONObject().put("kind", errand.kind.name)
            .put("place", errand.place?.let(::writePoint)).put("stay", errand.stayMinutes)
            .put("walking", errand.walkingMinutesOneWay).put("ready", errand.ready).put("deadline", errand.deadlineAtMillis)) }
        return json
    }

    private fun readDraft(json: JSONObject): SharedJourneyDraft {
        val people = array(json, "passengers", 1..2)
        val passengers = (0 until people.length()).map { index ->
            val person = people.getJSONObject(index)
            val verification = person.getJSONObject("verification")
            SharedPassengerDraft(
                id = text(person, "id", 64), name = text(person, "name", 80),
                dropoff = optionalObject(person, "dropoff")?.let(::readPoint),
                doorstepDropoffConfirmed = boolean(person, "doorstep"),
                doorstepConfirmedAtMillis = optionalLong(person, "doorstepAt"),
                verification = DropoffVerification(
                    AccessCheck.valueOf(text(verification, "vehicle", 32)),
                    AccessCheck.valueOf(text(verification, "pedestrian", 32)),
                    text(verification, "source", 500), optionalLong(verification, "checkedAt")),
                stopMinutes = integer(person, "stop"), latestArrivalAtMillis = optionalLong(person, "deadline"),
                maximumOnboardMinutes = optionalLong(person, "onboard")?.let { require(it in 0..480); it.toInt() },
            ).also { require(it.stopMinutes in 0..30) }
        }
        val errand = optionalObject(json, "errand")?.let { visit -> SharedErrandDraft(
            VisitKind.valueOf(text(visit, "kind", 32)), optionalObject(visit, "place")?.let(::readPoint),
            integer(visit, "stay"), integer(visit, "walking"), boolean(visit, "ready"), optionalLong(visit, "deadline"),
        ).also { require(it.stayMinutes in 0..180 && it.walkingMinutesOneWay in 0..30) } }
        return SharedJourneyDraft(optionalObject(json, "home")?.let(::readPoint), passengers, errand, integer(json, "budget"))
            .also { require(it.allowedAdditionalMinutes in 0..240) }
    }

    private fun writeProgress(progress: SharedJourneyProgress): JSONObject {
        val completed = JSONArray()
        require(progress.completedAtMillis.size <= 3)
        progress.completedAtMillis.forEach { completed.put(it) }
        return JSONObject().put("stage", progress.stage.name).put("selected", progress.selectedPlanId?.let { checkedText(it, 512) })
            .put("completed", progress.completedStopCount).put("completedAt", completed).put("startedAt", progress.startedAtMillis)
            .put("confirmed", progress.confirmedWindow?.let(::writePlan)).put("pending", progress.pendingWindow?.let(::writePlan))
            .put("driverHome", progress.driverHome?.let(::writePoint))
    }

    private fun readProgress(json: JSONObject): SharedJourneyProgress {
        val completed = array(json, "completedAt", 0..3)
        val times = (0 until completed.length()).map { index -> numberLong(completed.get(index)) }
        return SharedJourneyProgress(JourneyStage.valueOf(text(json, "stage", 32)), optionalText(json, "selected", 512),
            integer(json, "completed"), times, optionalLong(json, "startedAt"),
            optionalObject(json, "confirmed")?.let(::readPlan), optionalObject(json, "pending")?.let(::readPlan),
            optionalObject(json, "driverHome")?.let(::readPoint))
    }

    private fun writePlan(plan: NavigationPlan): JSONObject {
        require(plan.waypoints.size <= 2)
        val points = JSONArray()
        plan.waypoints.forEach { points.put(writePoint(it)) }
        return JSONObject().put("home", writePoint(plan.destination)).put("waypoints", points)
    }

    private fun readPlan(json: JSONObject): NavigationPlan {
        val points = array(json, "waypoints", 0..2)
        return NavigationPlan(readPoint(json.getJSONObject("home")), (0 until points.length()).map { readPoint(points.getJSONObject(it)) })
    }

    private fun writeRouting(routing: SharedRoutedJourneyEstimates): JSONObject {
        require(routing.windows.size in 1..6)
        val orders = JSONArray()
        routing.windows.forEach { (id, windows) ->
            require(windows.size in 1..2)
            val observations = JSONArray()
            windows.forEach { window ->
                require(window.consumedLegIndices.size in 1..3)
                val indices = JSONArray()
                window.consumedLegIndices.forEach { indices.put(it) }
                observations.put(JSONObject().put("route", writeEstimate(window.route)).put("consumed", indices))
            }
            orders.put(JSONObject().put("id", checkedText(id, 512)).put("windows", observations))
        }
        return JSONObject().put("schema", 1).put("origin", writePoint(routing.origin)).put("departureAt", routing.departureAtMillis)
            .put("validatedAt", routing.validatedAtMillis).put("zone", routing.zoneId.id)
            .put("direct", writeEstimate(routing.direct)).put("orders", orders)
    }

    private fun readRouting(json: JSONObject, draft: SharedJourneyDraft): SharedRoutedJourneyEstimates? {
        require(integer(json, "schema") == 1)
        val orders = array(json, "orders", 1..6)
        val observations = linkedMapOf<String, List<SharedGuidanceWindowEstimate>>()
        for (index in 0 until orders.length()) {
            val order = orders.getJSONObject(index)
            val id = text(order, "id", 512)
            require(id !in observations)
            val windows = array(order, "windows", 1..2)
            observations[id] = (0 until windows.length()).map { windowIndex ->
                val window = windows.getJSONObject(windowIndex)
                val consumed = array(window, "consumed", 1..3)
                SharedGuidanceWindowEstimate(readEstimate(window.getJSONObject("route")),
                    (0 until consumed.length()).map { numberLong(consumed.get(it)).also { value -> require(value in 0..2) }.toInt() })
            }
        }
        return SharedRoutedJourneyEstimates.restore(draft, readPoint(json.getJSONObject("origin")), long(json, "departureAt"),
            readEstimate(json.getJSONObject("direct")), observations, long(json, "validatedAt"), ZoneId.of(text(json, "zone", 100)))
    }

    private fun writeEstimate(route: RouteEstimate): JSONObject {
        require(route.points.size in 2..4 && route.legs.size == route.points.size - 1 && route.snappedPoints.size == route.points.size)
        val points = JSONArray(); val legs = JSONArray(); val snapped = JSONArray()
        route.points.forEach { points.put(writePoint(it)) }
        route.legs.forEach { legs.put(JSONObject().put("seconds", it.durationSeconds).put("meters", it.distanceMeters)) }
        route.snappedPoints.forEach { snapped.put(JSONObject().put("latitude", it.latitude).put("longitude", it.longitude).put("meters", it.distanceMeters)) }
        return JSONObject().put("points", points).put("legs", legs).put("snapped", snapped)
            .put("seconds", route.durationSeconds).put("meters", route.distanceMeters).put("queriedAt", route.queriedAtEpochMillis)
            .put("source", route.source).put("liveTraffic", route.includesLiveTraffic)
            .put("dataVersion", route.dataVersion?.let { checkedText(it, 100) })
    }

    private fun readEstimate(json: JSONObject): RouteEstimate {
        require(text(json, "source", 100) == "OSRM · OpenStreetMap" && !boolean(json, "liveTraffic"))
        val points = array(json, "points", 2..4)
        val legs = array(json, "legs", 1..3)
        val snaps = array(json, "snapped", 2..4)
        require(legs.length() == points.length() - 1 && snaps.length() == points.length())
        return when (val result = RouteEstimate.fromObservation(
            (0 until points.length()).map { readPoint(points.getJSONObject(it)) },
            (0 until legs.length()).map { index -> legs.getJSONObject(index).let { ObservedRouteLeg(decimal(it, "seconds"), decimal(it, "meters")) } },
            (0 until snaps.length()).map { index -> snaps.getJSONObject(index).let {
                SnappedRoutePoint(decimal(it, "latitude"), decimal(it, "longitude"), decimal(it, "meters")) } },
            decimal(json, "seconds"), decimal(json, "meters"), long(json, "queriedAt"), text(json, "dataVersion", 100),
        )) {
            is RouteEstimateResult.Success -> result.estimate
            is RouteEstimateResult.Failed -> throw IllegalArgumentException("저장된 경로 응답이 올바르지 않아요")
        }
    }

    private fun writePoint(point: NavigationPoint) = JSONObject().put("name", checkedText(point.name, 200))
        .put("latitude", point.latitude).put("longitude", point.longitude).put("address", checkedText(point.address, 500))
        .put("placeId", checkedText(point.placeId, 200)).put("subId", checkedText(point.subId, 200)).put("provider", checkedText(point.provider, 200))

    private fun readPoint(json: JSONObject) = NavigationPoint(text(json, "name", 200), decimal(json, "latitude"),
        decimal(json, "longitude"), text(json, "address", 500), text(json, "placeId", 200), text(json, "subId", 200), text(json, "provider", 200))

    private fun checkedText(value: String, maximum: Int): String = value.also {
        require(it.length <= maximum && it.none(Char::isISOControl))
    }
    private fun text(json: JSONObject, key: String, maximum: Int): String {
        val value = json.get(key)
        require(value is String)
        return checkedText(value, maximum)
    }
    private fun optionalText(json: JSONObject, key: String, maximum: Int): String? =
        if (!json.has(key) || json.isNull(key)) null else text(json, key, maximum)
    private fun optionalObject(json: JSONObject, key: String): JSONObject? =
        if (!json.has(key) || json.isNull(key)) null else json.getJSONObject(key)
    private fun optionalLong(json: JSONObject, key: String): Long? =
        if (!json.has(key) || json.isNull(key)) null else long(json, key)
    private fun numberLong(value: Any): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("정수 형식이 올바르지 않아요")
    }
    private fun long(json: JSONObject, key: String) = numberLong(json.get(key))
    private fun integer(json: JSONObject, key: String): Int = long(json, key)
        .also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }.toInt()
    private fun decimal(json: JSONObject, key: String): Double {
        val value = json.get(key)
        require(value is Number && value.toDouble().isFinite())
        return value.toDouble()
    }
    private fun boolean(json: JSONObject, key: String): Boolean {
        val value = json.get(key)
        require(value is Boolean)
        return value
    }
    private fun array(json: JSONObject, key: String, size: IntRange): JSONArray = json.getJSONArray(key).also { require(it.length() in size) }

    private fun boundedJson(value: String): Boolean {
        if (value.length > MAX_BYTES || value.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return false
        var depth = 0; var quoted = false; var escaped = false
        for (char in value) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 20) return false }
                '}', ']' -> { depth--; if (depth < 0) return false }
            }
        }
        return depth == 0 && !quoted
    }
}

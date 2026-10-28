package io.moduhome.app.data

import android.content.Context
import io.moduhome.app.core.planning.JourneyProgress
import io.moduhome.app.core.planning.JourneyStage
import io.moduhome.app.feature.home.HomeUiState
import io.moduhome.app.feature.home.JourneyDraft
import io.moduhome.app.feature.home.TravelLeg
import io.moduhome.app.feature.home.VisitDraft
import io.moduhome.app.feature.home.NavigationPlaces
import io.moduhome.app.feature.home.RoutedJourneyEstimates
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.routing.ObservedRouteLeg
import io.moduhome.app.core.routing.RouteEstimate
import io.moduhome.app.core.routing.RouteEstimateResult
import io.moduhome.app.core.routing.SnappedRoutePoint
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import androidx.core.content.edit

internal class JourneyPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("errand_journey", Context.MODE_PRIVATE)

    fun load(fallback: JourneyDraft): HomeUiState {
        val saved = preferences.getString("session", null) ?: return HomeUiState(fallback)
        if (saved.length > 131_072) return HomeUiState(fallback)
        return try {
            val json = JSONObject(saved)
            if (json.optInt("schema") != 1) return HomeUiState(fallback)
            val input = json.getJSONObject("draft")
            val travel = input.getJSONObject("travel")
            val draft = JourneyDraft(
                origin = input.getString("origin"), home = input.getString("home"),
                departure = input.getString("departure"), dateEpochDay = input.getLong("date"),
                budget = input.getInt("budget"),
                pickup = readVisit(input.getJSONObject("pickup")),
                groceries = readVisit(input.getJSONObject("groceries")),
                travel = TravelLeg.entries.associateWith { travel.optString(it.name, it.initial) },
                navigationPlaces = input.optJSONObject("navigation")?.let { places ->
                    NavigationPlaces(readPoint(places.optJSONObject("home")), readPoint(places.optJSONObject("pickup")),
                        readPoint(places.optJSONObject("groceries")))
                } ?: NavigationPlaces(),
            ).clearUnmatchedPlaces()
            val stage = JourneyStage.entries.firstOrNull { it.name == json.optString("stage") } ?: JourneyStage.INPUT
            val selected = json.optString("selected").takeIf { it.isNotEmpty() }
            val progress = JourneyProgress(
                stage, selected, json.optInt("completed").coerceIn(0, 2),
                json.optLong("started").takeIf { it > 0 }, json.optLong("finished").takeIf { it > 0 },
            )
            val routing = if (json.has("routing")) {
                readRouting(json.optJSONObject("routing"), draft) ?: return HomeUiState(draft)
            } else null
            val restored = HomeUiState(draft, progress, routing = routing)
            val plan = restored.selectedPlan
            val needsValidPlan = stage == JourneyStage.REVIEW || stage == JourneyStage.ACTIVE || stage == JourneyStage.COMPLETE
            if (stage != JourneyStage.INPUT && (plan == null || (needsValidPlan && !plan.isValid) || progress.completedVisits > plan.visits.size)) {
                HomeUiState(draft)
            } else restored
        } catch (_: JSONException) {
            HomeUiState(fallback)
        } catch (_: IllegalArgumentException) {
            HomeUiState(fallback)
        }
    }

    fun save(state: HomeUiState) {
        val draft = state.draft
        val travel = JSONObject()
        draft.travel.forEach { (key, value) -> travel.put(key.name, value) }
        val input = JSONObject()
            .put("origin", draft.origin).put("home", draft.home).put("departure", draft.departure)
            .put("date", draft.dateEpochDay).put("budget", draft.budget)
            .put("pickup", writeVisit(draft.pickup)).put("groceries", writeVisit(draft.groceries)).put("travel", travel)
            .put("navigation", JSONObject()
                .put("home", draft.navigationPlaces.home?.let(::writePoint))
                .put("pickup", draft.navigationPlaces.pickup?.let(::writePoint))
                .put("groceries", draft.navigationPlaces.groceries?.let(::writePoint)))
        val json = JSONObject().put("schema", 1).put("draft", input)
            .put("stage", state.progress.stage.name).put("selected", state.progress.selectedPlanId.orEmpty())
            .put("completed", state.progress.completedVisits)
            .put("started", state.progress.startedAtMillis ?: 0).put("finished", state.progress.completedAtMillis ?: 0)
        state.routing?.let { json.put("routing", writeRouting(it)) }
        preferences.edit { putString("session", json.toString()) }
    }

    private fun readVisit(json: JSONObject) = VisitDraft(
        enabled = json.getBoolean("enabled"), place = json.getString("place"), stay = json.getString("stay"),
        walking = json.getString("walking"), ready = json.getBoolean("ready"),
        deadline = json.getString("deadline"), deadlineNextDay = json.getBoolean("nextDay"),
    )

    private fun writeVisit(visit: VisitDraft) = JSONObject()
        .put("enabled", visit.enabled).put("place", visit.place).put("stay", visit.stay)
        .put("walking", visit.walking).put("ready", visit.ready)
        .put("deadline", visit.deadline).put("nextDay", visit.deadlineNextDay)

    private fun readPoint(json: JSONObject?): NavigationPoint? {
        if (json == null) return null
        val name = json.optString("name")
        val latitude = json.optDouble("latitude")
        val longitude = json.optDouble("longitude")
        if (name.isBlank() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return NavigationPoint(name, latitude, longitude, json.optString("address"), json.optString("placeId"),
            json.optString("subId"), json.optString("provider"))
    }

    private fun writePoint(point: NavigationPoint) = JSONObject()
        .put("name", point.name).put("latitude", point.latitude).put("longitude", point.longitude)
        .put("address", point.address).put("placeId", point.placeId).put("subId", point.subId).put("provider", point.provider)

    private fun writeRouting(routing: RoutedJourneyEstimates): JSONObject {
        val routes = JSONObject()
        routing.routes.forEach { (id, route) -> routes.put(id, writeEstimate(route)) }
        return JSONObject().put("schema", 1).put("originLabel", routing.originLabel).put("origin", writePoint(routing.origin))
            .put("direct", writeEstimate(routing.direct)).put("routes", routes)
    }

    private fun writeEstimate(estimate: RouteEstimate): JSONObject {
        val points = JSONArray()
        estimate.points.forEach { points.put(writePoint(it)) }
        val legs = JSONArray()
        estimate.legs.forEach { leg ->
            legs.put(JSONObject().put("durationSeconds", leg.durationSeconds).put("distanceMeters", leg.distanceMeters))
        }
        val snaps = JSONArray()
        estimate.snappedPoints.forEach { point ->
            snaps.put(JSONObject().put("latitude", point.latitude).put("longitude", point.longitude)
                .put("distanceMeters", point.distanceMeters))
        }
        return JSONObject().put("points", points).put("legs", legs).put("snappedPoints", snaps)
            .put("durationSeconds", estimate.durationSeconds).put("distanceMeters", estimate.distanceMeters)
            .put("queriedAt", estimate.queriedAtEpochMillis).put("source", estimate.source)
            .put("dataVersion", estimate.dataVersion).put("includesLiveTraffic", estimate.includesLiveTraffic)
    }

    private fun readRouting(json: JSONObject?, draft: JourneyDraft): RoutedJourneyEstimates? {
        if (json == null) return null
        return try {
            if (json.getInt("schema") != 1) return null
            if (json.getString("originLabel") != draft.origin.trim()) return null
            val origin = readPoint(json.getJSONObject("origin")) ?: return null
            val direct = readEstimate(json.getJSONObject("direct")) ?: return null
            val savedRoutes = json.getJSONObject("routes")
            if (savedRoutes.length() !in 1..2) return null
            val routes = linkedMapOf<String, RouteEstimate>()
            val ids = savedRoutes.keys()
            while (ids.hasNext()) {
                val id = ids.next()
                routes[id] = readEstimate(savedRoutes.getJSONObject(id)) ?: return null
            }
            RoutedJourneyEstimates.create(draft, origin, direct, routes)
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readEstimate(json: JSONObject): RouteEstimate? {
        if (json.getString("source") != "OSRM · OpenStreetMap" || json.getBoolean("includesLiveTraffic")) return null
        val points = json.getJSONArray("points")
        val legs = json.getJSONArray("legs")
        val snaps = json.getJSONArray("snappedPoints")
        if (points.length() !in 2..RouteEstimate.MAX_ROUTE_POINTS || legs.length() != points.length() - 1 ||
            snaps.length() != points.length()) return null
        val orderedPoints = (0 until points.length()).map { readPoint(points.getJSONObject(it)) ?: return null }
        val observedLegs = (0 until legs.length()).map { index ->
            val leg = legs.getJSONObject(index)
            ObservedRouteLeg(leg.getDouble("durationSeconds"), leg.getDouble("distanceMeters"))
        }
        val snapped = (0 until snaps.length()).map { index ->
            val snap = snaps.getJSONObject(index)
            SnappedRoutePoint(snap.getDouble("latitude"), snap.getDouble("longitude"), snap.getDouble("distanceMeters"))
        }
        return when (val result = RouteEstimate.fromObservation(
            orderedPoints, observedLegs, snapped, json.getDouble("durationSeconds"), json.getDouble("distanceMeters"),
            json.getLong("queriedAt"), json.optString("dataVersion").takeIf { it.isNotEmpty() },
        )) {
            is RouteEstimateResult.Success -> result.estimate
            is RouteEstimateResult.Failed -> null
        }
    }
}

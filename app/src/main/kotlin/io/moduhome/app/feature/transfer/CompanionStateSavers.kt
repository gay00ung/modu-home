package io.moduhome.app.feature.transfer

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import io.moduhome.app.core.address.AddressCandidate
import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import io.moduhome.app.core.transfer.CompanionDeadlineChoice
import io.moduhome.app.core.transfer.CompanionErrandDraft
import io.moduhome.app.core.transfer.CompanionErrandSource
import io.moduhome.app.core.transfer.ErrandProposal
import io.moduhome.app.core.transfer.DropoffProposal
import io.moduhome.app.core.transfer.CompanionDropoffDraft
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Only user-authored form data is saved. Pairing state and pasted links stay outside. */
internal fun companionDraftSaver(
    received: (String, Int) -> ErrandProposal?,
    nowMillis: () -> Long = System::currentTimeMillis,
): Saver<CompanionErrandDraft?, Any> = mapSaver(
    save = { draft ->
        if (draft == null) emptyMap() else buildMap {
            put("version", 1)
            put("opened", draft.openedAtMillis)
            put("date", draft.baseDate.toEpochDay())
            put("zone", draft.zoneId.id)
            put("stay", draft.stayMinutes)
            put("walk", draft.walkingMinutesOneWay)
            put("ready", draft.ready)
            put("deadline", draft.deadline.name)
            put("time", draft.deadlineTime)
            when (val source = draft.source) {
                is CompanionErrandSource.Received -> {
                    put("source", "received")
                    put("id", source.proposal.id)
                    put("revision", source.proposal.revision)
                }
                is CompanionErrandSource.Address -> {
                    put("source", "address")
                    put("kind", source.kind.name)
                    put("name", source.place.name)
                    put("address", source.place.address)
                    put("latitude", source.place.latitude)
                    put("longitude", source.place.longitude)
                    put("placeId", source.place.placeId)
                    put("subId", source.place.subId)
                    put("provider", source.place.provider)
                }
            }
        }
    },
    restore = { fields ->
        runCatching {
            require(fields["version"] == 1)
            val opened = fields.getValue("opened") as Long
            val now = nowMillis()
            require(opened >= 0 && now >= opened && now - opened < ErrandProposal.MAX_LIFETIME_MILLIS)
            val zone = ZoneId.of(fields.getValue("zone") as String)
            val date = LocalDate.ofEpochDay(fields.getValue("date") as Long)
            require(date == Instant.ofEpochMilli(opened).atZone(zone).toLocalDate())
            val source = when (fields["source"]) {
                "received" -> {
                    val id = fields.getValue("id") as String
                    val revision = fields.getValue("revision") as Int
                    val proposal = requireNotNull(received(id, revision))
                    require(proposal.id == id && proposal.revision == revision && proposal.unavailableReason(now) == null)
                    CompanionErrandSource.Received(proposal)
                }
                "address" -> CompanionErrandSource.Address(
                    NavigationPoint(
                        name = fields.getValue("name") as String,
                        latitude = fields.getValue("latitude") as Double,
                        longitude = fields.getValue("longitude") as Double,
                        address = fields.getValue("address") as String,
                        placeId = fields.getValue("placeId") as String,
                        subId = fields.getValue("subId") as String,
                        provider = fields.getValue("provider") as String,
                    ),
                    VisitKind.valueOf(fields.getValue("kind") as String),
                )
                else -> error("Unknown draft source")
            }
            CompanionErrandDraft(
                source, date, zone, opened,
                stayMinutes = fields.getValue("stay") as String,
                walkingMinutesOneWay = fields.getValue("walk") as String,
                ready = fields.getValue("ready") as Boolean,
                deadline = CompanionDeadlineChoice.valueOf(fields.getValue("deadline") as String),
                deadlineTime = fields.getValue("time") as String,
            )
        }.getOrNull()
    },
)

internal val CompanionAddressStateSaver: Saver<CompanionAddressState?, Any> = mapSaver(
    save = { state ->
        if (state == null) emptyMap() else mapOf(
            "version" to 1,
            "query" to state.query,
            "kind" to state.kind.name,
            "purpose" to state.purpose.name,
            "loading" to state.loading,
            "resultQuery" to state.resultQuery.orEmpty(),
            "selected" to state.candidates.indexOf(state.selected),
            "candidates" to ArrayList(state.candidates.map { arrayListOf(it.address, it.point.latitude, it.point.longitude) }),
        )
    },
    restore = { fields ->
        runCatching {
            require(fields["version"] == 1)
            val query = fields.getValue("query") as String
            val candidates = (fields.getValue("candidates") as List<*>).also { require(it.size <= 5) }.map { raw ->
                val row = raw as List<*>
                require(row.size == 3)
                requireNotNull(AddressCandidate.fromObservation(row[0] as String, row[1] as Double, row[2] as Double))
            }
            val loading = fields.getValue("loading") as Boolean
            val resultQuery = (fields.getValue("resultQuery") as String).takeIf(String::isNotEmpty)
            val state = CompanionAddressState(
                purpose = (fields["purpose"] as? String)?.let(CompanionAddressPurpose::valueOf) ?: CompanionAddressPurpose.ERRAND,
                query = query,
                kind = VisitKind.valueOf(fields.getValue("kind") as String),
                candidates = candidates,
                selected = candidates.getOrNull(fields.getValue("selected") as Int),
                resultQuery = resultQuery,
            )
            when {
                loading -> state.clearResults("화면이 바뀌어 검색을 중단했어요. 다시 검색해주세요")
                resultQuery != query.trim() -> state.clearResults()
                else -> state
            }
        }.getOrNull()
    },
)

internal fun companionDropoffDraftSaver(
    received: (String, Int) -> DropoffProposal?,
    nowMillis: () -> Long = System::currentTimeMillis,
): Saver<CompanionDropoffDraft?, Any> = mapSaver(
    save = { draft ->
        if (draft == null) emptyMap() else buildMap {
            put("version", 1); put("opened", draft.openedAtMillis); put("date", draft.baseDate.toEpochDay())
            put("zone", draft.zoneId.id); put("name", draft.passengerName)
            put("deadline", draft.deadline.name); put("time", draft.deadlineTime)
            put("place", arrayListOf(draft.place.name, draft.place.address, draft.place.latitude, draft.place.longitude,
                draft.place.placeId, draft.place.subId, draft.place.provider))
            draft.source?.let { put("sourceId", it.id); put("sourceRevision", it.revision) }
        }
    },
    restore = { fields -> runCatching {
        require(fields["version"] == 1)
        val opened = fields.getValue("opened") as Long
        val now = nowMillis()
        require(opened >= 0 && now >= opened && now - opened < ErrandProposal.MAX_LIFETIME_MILLIS)
        val zone = ZoneId.of(fields.getValue("zone") as String)
        val date = LocalDate.ofEpochDay(fields.getValue("date") as Long)
        require(date == Instant.ofEpochMilli(opened).atZone(zone).toLocalDate())
        val point = fields.getValue("place") as List<*>
        require(point.size == 7)
        val source = (fields["sourceId"] as? String)?.let { id ->
            val revision = fields.getValue("sourceRevision") as Int
            requireNotNull(received(id, revision)).also {
                require(it.id == id && it.revision == revision && it.unavailableReason(now) == null)
            }
        }
        CompanionDropoffDraft(NavigationPoint(name = point[0] as String, latitude = point[2] as Double, longitude = point[3] as Double,
            address = point[1] as String, placeId = point[4] as String, subId = point[5] as String, provider = point[6] as String),
            opened, date, zone, fields.getValue("name") as String,
            CompanionDeadlineChoice.valueOf(fields.getValue("deadline") as String), fields.getValue("time") as String, source)
    }.getOrNull() },
)

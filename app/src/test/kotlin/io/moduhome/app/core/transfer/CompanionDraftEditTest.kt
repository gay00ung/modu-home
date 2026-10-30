package io.moduhome.app.core.transfer

import io.moduhome.app.core.navigation.NavigationPoint
import io.moduhome.app.core.planning.VisitKind
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CompanionDraftEditTest {
    private val now = Instant.parse("2026-10-30T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Seoul")
    private val place = NavigationPoint("공개 주소", 37.55, 126.97)

    @Test fun rapidTimeDeletionAndReplacementDoNotRequireAFrameBetweenEveryKey() {
        val rendered = CompanionDropoffDraft.forAddress(place, now, zone).copy(deadlineTime = "21:30")
        var current = rendered
        for (text in listOf("21:3", "21:", "21", "2", "", "2", "21", "21:", "21:4", "21:45", "21:30")) {
            current = current.applyEdit(rendered, CompanionDropoffEdit.Time(text))!!
        }
        assertEquals("21:30", current.deadlineTime)
    }

    @Test fun aTimeEditFromAnOlderFrameKeepsTheNewNameAndRejectsAnotherForm() {
        val rendered = CompanionDropoffDraft.forAddress(place, now, zone)
        val named = rendered.applyEdit(rendered, CompanionDropoffEdit.Name("친구"))!!
        val timed = named.applyEdit(rendered, CompanionDropoffEdit.Time("21:45"))!!
        assertEquals("친구", timed.passengerName)
        assertEquals("21:45", timed.deadlineTime)
        assertNull(timed.applyEdit(rendered.copy(openedAtMillis = now + 1), CompanionDropoffEdit.Time("21:00")))
        assertNull(timed.applyEdit(rendered.copy(place = place.copy(latitude = 37.6)), CompanionDropoffEdit.Time("21:00")))
    }

    @Test fun rapidErrandEditsKeepBothStayAndWalkingValues() {
        val rendered = CompanionErrandDraft.forAddress(place, VisitKind.GROCERIES, now, zone)
        val stayed = rendered.applyEdit(rendered, CompanionErrandEdit.Stay("12"))!!
        val walked = stayed.applyEdit(rendered, CompanionErrandEdit.Walking("3"))!!
        assertEquals("12", walked.stayMinutes)
        assertEquals("3", walked.walkingMinutesOneWay)
        assertNull(walked.applyEdit(rendered.copy(openedAtMillis = now + 1), CompanionErrandEdit.Time("21:00")))
    }
}

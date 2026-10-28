package io.moduhome.app.core.voice

import io.moduhome.app.core.planning.VisitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanJourneyVoiceParserTest {
    private fun command(text: String): JourneyVoiceCommand {
        val result = KoreanJourneyVoiceParser.parse(text)
        assertTrue("Expected a supported command", result is VoiceCommandParseResult.Supported)
        return (result as VoiceCommandParseResult.Supported).command
    }

    private fun unsupported(vararg texts: String) {
        texts.forEach { assertTrue(KoreanJourneyVoiceParser.parse(it) is VoiceCommandParseResult.Unsupported) }
    }

    @Test fun pickupSuggestionsAndRemovalAreDifferentExplicitCommands() {
        assertEquals(JourneyVoiceCommand.SetVisitEnabled(VisitKind.PICKUP, true), command("픽업도 들를래"))
        assertEquals(JourneyVoiceCommand.SetVisitEnabled(VisitKind.PICKUP, true), command("픽업 추가해줘"))
        assertEquals(JourneyVoiceCommand.SetVisitEnabled(VisitKind.PICKUP, false), command("픽업 빼"))
        assertEquals(JourneyVoiceCommand.SetVisitEnabled(VisitKind.PICKUP, false), command("픽업은 빼줘"))
    }

    @Test fun groceryUtterancesDoNotBecomePickupCommands() {
        assertEquals(JourneyVoiceCommand.SetVisitEnabled(VisitKind.GROCERIES, true), command("장도 보고 갈래"))
        assertEquals(JourneyVoiceCommand.SetVisitEnabled(VisitKind.GROCERIES, true), command("마트도 들러줘"))
        assertEquals(JourneyVoiceCommand.SetVisitEnabled(VisitKind.GROCERIES, false), command("마트 빼"))
        assertEquals(JourneyVoiceCommand.SetVisitEnabled(VisitKind.GROCERIES, false), command("장보기 취소"))
    }

    @Test fun numericBudgetRequiresMinutesAndUsesTheFullSupportedRange() {
        assertEquals(JourneyVoiceCommand.SetAdditionalBudget(20), command("추가 20분"))
        assertEquals(JourneyVoiceCommand.SetAdditionalBudget(0), command("허용 추가시간 0분으로 해줘"))
        assertEquals(JourneyVoiceCommand.SetAdditionalBudget(240), command("추가시간 240분까지 설정해줘"))
    }

    @Test fun commonSpokenKoreanNumbersAreRecognizedWithoutGuessingDurations() {
        assertEquals(JourneyVoiceCommand.SetAdditionalBudget(20), command("추가 이십 분"))
        assertEquals(JourneyVoiceCommand.SetAdditionalBudget(15), command("추가 십오 분"))
        assertEquals(JourneyVoiceCommand.SetAdditionalBudget(25), command("추가 스물다섯 분"))
        assertEquals(JourneyVoiceCommand.SetAdditionalBudget(20), command("추가 스무 분"))
        assertEquals(JourneyVoiceCommand.SetAdditionalBudget(30), command("추가 서른 분"))
        assertEquals(JourneyVoiceCommand.SetAdditionalBudget(240), command("추가 이백사십 분"))
    }

    @Test fun outOfRangeOrUnspecifiedTimeUnitsCannotChangeTheBudget() {
        unsupported("추가 241분", "추가 999분", "추가 이백사십일 분", "추가 -20분", "추가 한 시간", "20분", "추가 20")
    }

    @Test fun spacingPunctuationAndFullWidthDigitsDoNotAlterTheMeaning() {
        assertEquals(JourneyVoiceCommand.SetAdditionalBudget(20), command("  추가　２０ 분. "))
        assertEquals(JourneyVoiceCommand.SetVisitEnabled(VisitKind.PICKUP, false), command("픽업   빼주세요!"))
    }

    @Test fun readinessNegationIsNeverMistakenForAReadyPickup() {
        assertEquals(JourneyVoiceCommand.SetPickupReady(true), command("수령 준비 됐어"))
        assertEquals(JourneyVoiceCommand.SetPickupReady(true), command("픽업 준비 완료"))
        assertEquals(JourneyVoiceCommand.SetPickupReady(false), command("수령 준비 안 됐어"))
        assertEquals(JourneyVoiceCommand.SetPickupReady(false), command("픽업 아직이야"))
    }

    @Test fun negatedOrQuotedActionsAreNotMatchedBySubstrings() {
        unsupported("픽업 빼지 마", "마트 추가하지 마", "수령 준비됐어라고 말하지 않았어", "추가 20분이 아니야", "픽업 빼라는 말 취소")
    }

    @Test fun compoundCommandsRequireASeparateConfirmationForEachAction() {
        unsupported("픽업 빼고 마트 추가", "픽업도 들를래 그리고 추가 20분", "마트 빼. 픽업 빼.")
    }

    @Test fun arbitraryPlaceSearchAndNavigationRequestsRemainUnsupported() {
        unsupported("서울역으로 가줘", "근처 마트 찾아줘", "우리 집으로 안내 시작", "강남대로 123으로 가자")
    }

    @Test fun emptyMalformedAndOverlongTranscriptsDoNotProduceActions() {
        unsupported("", "   ", "?!", "픽업\u0000빼", "픽업빼" + " ".repeat(KoreanJourneyVoiceParser.MAX_TEXT_LENGTH), "🙂")
    }
}

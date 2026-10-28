package io.moduhome.app.core.voice

import io.moduhome.app.core.planning.VisitKind
import java.text.Normalizer

internal sealed interface JourneyVoiceCommand {
    data class SetVisitEnabled(val kind: VisitKind, val enabled: Boolean) : JourneyVoiceCommand
    data class SetAdditionalBudget(val minutes: Int) : JourneyVoiceCommand
    data class SetPickupReady(val ready: Boolean) : JourneyVoiceCommand

    val confirmationText: String get() = when (this) {
        is SetVisitEnabled -> {
            val label = when (kind) {
                VisitKind.PICKUP -> "픽업을"
                VisitKind.GROCERIES -> "장보기를"
            }
            if (enabled) "$label 계획에 추가할까요?" else "$label 계획에서 뺄까요?"
        }
        is SetAdditionalBudget -> "허용 추가시간을 ${minutes}분으로 바꿀까요?"
        is SetPickupReady -> if (ready) "수령 준비가 끝난 것으로 표시할까요?" else "수령 준비를 기다리는 것으로 표시할까요?"
    }
}

internal sealed interface VoiceCommandParseResult {
    data class Supported(val command: JourneyVoiceCommand) : VoiceCommandParseResult
    data class Unsupported(val reason: String) : VoiceCommandParseResult
}

/** Whole-utterance matching deliberately leaves unknown addresses and compound requests unhandled. */
internal object KoreanJourneyVoiceParser {
    const val MAX_TEXT_LENGTH = 200
    private val spacing = Regex("[\\s\\p{Z}]+")
    private val endingPunctuation = Regex("[.!?。！？]+$")
    private val budgetPattern = Regex("(?:허용추가시간|추가시간|추가)([0-9]{1,3}|[가-힣]{1,8})분(?:으로|까지)?(?:설정|설정해줘|해줘|맞춰줘)?")
    private val phrases: Map<String, JourneyVoiceCommand> = buildMap {
        fun add(command: JourneyVoiceCommand, vararg utterances: String) {
            utterances.forEach { put(it, command) }
        }
        add(JourneyVoiceCommand.SetVisitEnabled(VisitKind.PICKUP, true),
            "픽업추가", "픽업추가해", "픽업추가해줘", "픽업도들를래", "픽업들를래", "픽업도들러줘", "픽업들러줘",
            "픽업도하고갈래", "픽업도하고가자", "픽업넣어줘", "픽업켜줘")
        add(JourneyVoiceCommand.SetVisitEnabled(VisitKind.PICKUP, false),
            "픽업빼", "픽업빼줘", "픽업빼주세요", "픽업은빼줘", "픽업제외", "픽업제외해줘", "픽업취소", "픽업취소해줘", "픽업끄기")
        add(JourneyVoiceCommand.SetVisitEnabled(VisitKind.GROCERIES, true),
            "장도보고갈래", "장보고갈래", "장도보고가자", "장보기추가", "장보기추가해줘", "마트도들를래", "마트들를래",
            "마트도들러줘", "마트추가", "마트추가해줘", "마트넣어줘", "장보기켜줘")
        add(JourneyVoiceCommand.SetVisitEnabled(VisitKind.GROCERIES, false),
            "마트빼", "마트빼줘", "장보기빼", "장보기빼줘", "마트제외", "장보기제외", "마트취소", "장보기취소", "장보기끄기")
        add(JourneyVoiceCommand.SetPickupReady(true),
            "수령준비됐어", "수령준비됐어요", "수령준비완료", "픽업준비됐어", "픽업준비됐어요", "픽업준비완료", "픽업준비됐습니다")
        add(JourneyVoiceCommand.SetPickupReady(false),
            "수령아직준비안됐어", "수령준비안됐어", "픽업준비안됐어", "픽업아직이야", "아직준비안됐어",
            "수령준비안됐어요", "픽업준비안됐어요")
    }
    private val spokenMinutes: Map<String, Int> = buildMap {
        put("영", 0)
        put("공", 0)
        for (number in 1..240) put(sinoNumber(number), number)
        val tens = listOf("", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")
        val ones = listOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")
        for (number in 1..99) put(tens[number / 10] + ones[number % 10], number)
        put("스무", 20)
    }

    fun parse(text: String): VoiceCommandParseResult {
        if (text.length > MAX_TEXT_LENGTH || text.any { it.isISOControl() && !it.isWhitespace() }) return unsupported()
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .trim().replace(spacing, "").replace(endingPunctuation, "")
        phrases[normalized]?.let { return VoiceCommandParseResult.Supported(it) }
        val budget = budgetPattern.matchEntire(normalized)?.groupValues?.get(1)
        if (budget != null) {
            val minutes = budget.toIntOrNull() ?: spokenMinutes[budget]
            return if (minutes != null && minutes in 0..240) {
                VoiceCommandParseResult.Supported(JourneyVoiceCommand.SetAdditionalBudget(minutes))
            } else VoiceCommandParseResult.Unsupported("추가시간은 0~240분으로 말해주세요")
        }
        return unsupported()
    }

    private fun unsupported(): VoiceCommandParseResult.Unsupported =
        VoiceCommandParseResult.Unsupported("픽업, 장보기, 추가시간, 수령 준비 중 한 가지를 짧게 말해주세요")

    private fun sinoNumber(number: Int): String {
        val digits = listOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
        return buildString {
            val hundreds = number / 100
            if (hundreds > 0) {
                if (hundreds > 1) append(digits[hundreds])
                append("백")
            }
            val tens = (number / 10) % 10
            if (tens > 0) {
                if (tens > 1) append(digits[tens])
                append("십")
            }
            append(digits[number % 10])
        }
    }
}

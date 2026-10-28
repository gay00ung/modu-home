package io.moduhome.app.core.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class RestrictedContentTest {
    @Test fun `긴 한국어 장소는 제한 글자 수 안에서 줄인다`() {
        val state = DrivingInteraction.Known(true, maximumTextLength = 3)
        assertEquals(listOf("서울…"), state.restrictContent(listOf("서울특별시청")))
    }

    @Test fun `보충 문자를 반으로 자르지 않는다`() {
        val state = DrivingInteraction.Known(true, maximumTextLength = 2)
        assertEquals(listOf("😀…"), state.restrictContent(listOf("😀우리집")))
    }

    @Test fun `차량이 허용한 항목 수를 넘지 않는다`() {
        val state = DrivingInteraction.Known(true, maximumContentItems = 1)
        assertEquals(listOf("다음 목적지"), state.restrictContent(listOf("다음 목적지", "집", "도착 계획")))
    }

    @Test fun `정보를 허용하지 않으면 표시하지 않는다`() {
        assertEquals(emptyList<String>(), DrivingInteraction.Known(true, maximumContentItems = 0).restrictContent(listOf("집")))
        assertEquals(emptyList<String>(), DrivingInteraction.Known(true, maximumTextLength = 0).restrictContent(listOf("집")))
        assertEquals(emptyList<String>(), DrivingInteraction.Known(true, maximumContentDepth = 0).restrictContent(listOf("집")))
    }

    @Test fun `한 글자 제한에서도 생략 표시가 제한을 넘지 않는다`() {
        assertEquals(listOf("…"), DrivingInteraction.Known(true, maximumTextLength = 1).restrictContent(listOf("우리집")))
    }

    @Test fun `별도 제한이 없어도 주행 화면은 세 항목만 표시한다`() {
        assertEquals(listOf("가", "나", "다"), DrivingInteraction.Known(true).restrictContent(listOf("가", "나", "다", "라")))
    }
}

package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 음성 주문 대화 흐름 테스트: STT/TTS 없이 문장만으로 검증 */
class VoiceDialogTest {

    private fun menu(id: String, name: String, price: Int, cook: Int, vararg tags: String) =
        Menu(id, name, price, cook, tags.toList())

    private val jjigae = Store("s1", "한끼찌개 (가상)", "한식", 0.0, 0.0, 0, 24, true, true, listOf(
        menu("m1", "김치찌개", 9000, 10, "국물", "매운"),
        menu("m2", "된장찌개", 8500, 10, "국물")))
    private val chicken = Store("s2", "서초치킨공방 (가상)", "치킨", 0.0, 0.0, 0, 24, true, true, listOf(
        menu("m3", "후라이드 치킨", 19000, 20, "여럿")))

    private fun rec(store: Store, i: Int) = Recommendation(store, store.menus[i], 100, 1500, 0.0)

    /** 태그가 맞는 메뉴만 돌려주는 가짜 추천기 */
    private val all = listOf(rec(jjigae, 0), rec(jjigae, 1), rec(chicken, 0))
    private fun fakeRecommend(tags: Set<String>) =
        if (tags.isEmpty()) all else all.filter { r -> r.menu.tags.any { it in tags } }

    private fun dialog() = VoiceDialog(::fakeRecommend)

    @Test
    fun 국물_요청_후_네라고_하면_첫_메뉴를_주문() {
        val d = dialog()
        assertTrue(d.start().listen)
        val proposal = d.handle("국물 있는 거 먹고 싶어")
        assertTrue(proposal.say.contains("김치찌개"))
        assertTrue(proposal.say.contains("한끼찌개의"))       // 괄호 (가상)은 읽지 않음
        assertTrue(proposal.listen)
        val done = d.handle("응 그걸로 해줘")
        assertEquals("김치찌개", done.order?.menu?.name)
        assertFalse(done.listen)
        assertFalse(d.isActive)
    }

    @Test
    fun 아니라고_하면_다음_후보를_제안() {
        val d = dialog()
        d.start()
        d.handle("국물")
        val next = d.handle("아니")
        assertTrue(next.say.contains("된장찌개"))
        assertNull(next.order)
    }

    @Test
    fun 음식_이름으로_말하면_그_메뉴만_제안() {
        val d = dialog()
        d.start()
        assertTrue(d.handle("치킨 먹을래").say.contains("후라이드 치킨"))
    }

    @Test
    fun 제안_중에_새_조건을_말하면_다시_찾는다() {
        val d = dialog()
        d.start()
        d.handle("치킨")
        assertTrue(d.handle("아니 국물로 줘").say.contains("김치찌개"))
    }

    @Test
    fun 두번_못알아들으면_대화를_끝낸다() {
        val d = dialog()
        d.start()
        d.handle("국물")
        assertTrue(d.handle("음…").listen)
        val end = d.handle("흠")
        assertFalse(end.listen)
        assertFalse(d.isActive)
    }

    @Test
    fun 취소라고_하면_언제든_끝난다() {
        val d = dialog()
        d.start()
        d.handle("국물")
        assertFalse(d.handle("취소해").listen)
        assertFalse(d.isActive)
    }

    @Test
    fun 경로가_없으면_안내하고_끝낸다() {
        val d = VoiceDialog { null }
        d.start()
        val r = d.handle("아무거나")
        assertTrue(r.say.contains("목적지"))
        assertFalse(d.isActive)
    }

    @Test
    fun 매장_이름_받침에_맞춰_을_를() {
        assertEquals("서초치킨공방을", VoiceDialog.withEulReul("서초치킨공방"))
        assertEquals("테헤란샐러드를", VoiceDialog.withEulReul("테헤란샐러드"))
    }

    @Test
    fun 네_아니요_판별() {
        assertEquals(VoiceParser.Answer.YES, VoiceParser.answer("네 주문해 주세요"))
        assertEquals(VoiceParser.Answer.NO, VoiceParser.answer("아니요"))
        assertEquals(VoiceParser.Answer.NO, VoiceParser.answer("다른 거"))
        assertEquals(VoiceParser.Answer.UNKNOWN, VoiceParser.answer("어떤 게 있어"))
    }
}

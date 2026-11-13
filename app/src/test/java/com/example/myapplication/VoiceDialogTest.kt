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

    /** 픽업 후보 */
    private val all = listOf(rec(jjigae, 0), rec(jjigae, 1), rec(chicken, 0))
    /** 배달 후보: 목적지 근처 치킨집 (조리 20분 + 배달 5분) */
    private val deliveryAll = listOf(Recommendation(chicken, chicken.menus[0], 600, 1500, 0.0, delivery = true, deliveryMin = 5))

    private val home = Place("우리집", 37.5, 127.0, listOf("집"))
    private val gangnam = Place("강남역", 37.4979, 127.0276)

    /** 마지막으로 추천을 요청받은 배달 장소 (검증용) */
    private var askedPlace: Place? = null

    private fun fakeRecommend(tags: Set<String>, delivery: Boolean, place: Place?): List<Recommendation> {
        askedPlace = place
        val list = if (delivery) deliveryAll else all
        return if (tags.isEmpty()) list else list.filter { r -> r.menu.tags.any { it in tags } }
    }

    /** 내비에 목적지가 있는 상태 */
    private fun dialog() = VoiceDialog(::fakeRecommend)

    /** 내비에 목적지가 없는 상태 (배달 장소 후보: 집, 강남역) */
    private fun noRouteDialog() = VoiceDialog(::fakeRecommend, hasDestination = { false }, places = { listOf(home, gangnam) })

    // ---------- ① 음식 → ② 방식 → 제안 ----------

    @Test
    fun 음식을_말하면_픽업_배달을_묻고_픽업이면_가는_길_메뉴를_제안() {
        val d = dialog()
        assertTrue(d.start().listen)
        val ask = d.handle("국물 있는 거 먹고 싶어")
        assertEquals(VoiceDialog.MODE_QUESTION, ask.say)
        assertTrue(ask.listen)
        val proposal = d.handle("픽업")
        assertTrue(proposal.say.contains("김치찌개"))
        assertTrue(proposal.say.contains("한끼찌개의"))       // 괄호 (가상)은 읽지 않음
        val done = d.handle("응 그걸로 해줘")
        assertEquals("김치찌개", done.order?.menu?.name)
        assertTrue(done.say.contains("경유지"))              // 목적지가 있으면 매장을 경유지로
        assertNull(done.place)
        assertFalse(done.listen)
        assertFalse(d.isActive)
    }

    @Test
    fun 음식과_방식을_한번에_말하면_방식을_묻지_않는다() {
        val d = dialog()
        d.start()
        val proposal = d.handle("배달로 치킨 시켜줘")
        assertTrue(proposal.say.contains("후라이드 치킨"))
        assertTrue(proposal.say.contains("배달로 주문할까요"))
        val done = d.handle("네")
        assertTrue(done.order!!.delivery)
        assertTrue(done.say.contains("배달 주문"))
        assertFalse(done.say.contains("경유지"))
    }

    @Test
    fun 방식을_물을_때_못_알아들으면_다시_묻는다() {
        val d = dialog()
        d.start()
        d.handle("국물")
        val again = d.handle("음…")
        assertTrue(again.listen)
        assertTrue(again.say.contains("픽업 또는 배달"))
    }

    @Test
    fun 방식을_물을_때_음식을_다시_말하면_음식을_바꾼다() {
        val d = dialog()
        d.start()
        d.handle("국물")
        assertEquals(VoiceDialog.MODE_QUESTION, d.handle("아 그냥 치킨").say)
        assertTrue(d.handle("픽업").say.contains("후라이드 치킨"))
    }

    // ---------- 목적지가 없을 때 ----------

    @Test
    fun 목적지가_없을_때_픽업은_근처에서_찾고_매장으로_안내() {
        val d = noRouteDialog()
        d.start()
        d.handle("국물")
        val proposal = d.handle("들러서 받을게")
        assertTrue(proposal.say.contains("김치찌개"))
        val done = d.handle("네")
        assertTrue(done.say.contains("길을 안내"))
        assertFalse(done.say.contains("경유지"))
        assertNull(done.place)
        assertFalse(done.order!!.delivery)
    }

    @Test
    fun 목적지가_없을_때_배달이면_장소를_묻고_그곳_기준으로_찾는다() {
        val d = noRouteDialog()
        d.start()
        d.handle("치킨")
        val ask = d.handle("배달")
        assertTrue(ask.say.startsWith("어디로 배달할까요?"))
        assertTrue(ask.say.contains("집"))
        assertTrue(ask.say.contains("강남역"))
        val proposal = d.handle("강남역으로")
        assertEquals(gangnam, askedPlace)
        assertTrue(proposal.say.contains("강남역에 도착하실 때"))
        val done = d.handle("네")
        assertEquals(gangnam, done.place)
        assertTrue(done.order!!.delivery)
        assertTrue(done.say.contains("강남역까지 경로"))
    }

    @Test
    fun 집으로_보내_달라고_하면_방식과_장소를_한번에_받는다() {
        val d = noRouteDialog()
        d.start()
        val proposal = d.handle("치킨 집으로 보내 줘")
        assertEquals(home, askedPlace)
        assertTrue(proposal.say.contains("우리집에 도착하실 때"))
    }

    @Test
    fun 모르는_장소면_다시_묻는다() {
        val d = noRouteDialog()
        d.start()
        d.handle("배달로 치킨")
        val again = d.handle("부산역")
        assertTrue(again.listen)
        assertTrue(again.say.contains("어디로 배달할까요?"))
    }

    @Test
    fun 장소를_물을_때_픽업으로_바꿀_수_있다() {
        val d = noRouteDialog()
        d.start()
        d.handle("배달로 국물")
        assertTrue(d.handle("그냥 픽업할게").say.contains("김치찌개"))
        assertNull(askedPlace)
    }

    @Test
    fun 목적지가_있으면_배달_장소를_묻지_않는다() {
        val d = dialog()
        d.start()
        d.handle("치킨 배달")
        assertNull(askedPlace)   // 내비 목적지 기준
    }

    @Test
    fun 근처에_조리가_오래_걸리면_대기_시간을_알려_준다() {
        val waiting = Recommendation(chicken, chicken.menus[0], 1000, 180, 0.0)   // 3분 거리, 조리 20분
        val d = VoiceDialog({ _, _, _ -> listOf(waiting) }, hasDestination = { false })
        d.start()
        d.handle("치킨")
        assertTrue(d.handle("픽업").say.contains("도착 후 17분쯤 기다리면"))
    }

    // ---------- 제안 중 ----------

    @Test
    fun 아니라고_하면_다음_후보를_제안() {
        val d = dialog()
        d.start()
        d.handle("국물 픽업")
        val next = d.handle("아니")
        assertTrue(next.say.contains("된장찌개"))
        assertNull(next.order)
    }

    @Test
    fun 음식_이름으로_말하면_그_메뉴만_제안() {
        val d = dialog()
        d.start()
        assertTrue(d.handle("치킨 먹을래 포장으로").say.contains("후라이드 치킨"))
    }

    @Test
    fun 제안_중에_새_조건을_말하면_다시_찾는다() {
        val d = dialog()
        d.start()
        d.handle("치킨 픽업")
        assertTrue(d.handle("아니 국물로 줘").say.contains("김치찌개"))
    }

    @Test
    fun 픽업_제안_중에_배달로_바꿀_수_있다() {
        val d = dialog()
        d.start()
        assertFalse(d.handle("치킨 픽업").say.contains("배달"))
        val again = d.handle("아니 배달로 해줘")
        assertTrue(again.say.contains("배달로 주문할까요"))
        assertTrue(d.handle("네").order!!.delivery)
    }

    @Test
    fun 찾는_메뉴가_없으면_방식을_바꾸라고_안내() {
        val d = VoiceDialog({ _, delivery, _ -> if (delivery) deliveryAll else emptyList() })
        d.start()
        val r = d.handle("포장으로 치킨")
        assertNull(r.order)
        assertTrue(r.say.contains("배달이라고"))
        assertTrue(d.handle("배달").say.contains("후라이드 치킨"))   // 음식은 기억하고 방식만 바꿈
    }

    // ---------- 종료·무응답 ----------

    @Test
    fun 두번_못알아들으면_대화를_끝낸다() {
        val d = dialog()
        d.start()
        d.handle("국물 픽업")
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
    fun 추천을_계산할_수_없으면_안내하고_끝낸다() {
        val d = VoiceDialog({ _, _, _ -> null })
        d.start()
        val r = d.handle("아무거나 픽업")
        assertFalse(r.listen)
        assertFalse(d.isActive)
    }

    @Test
    fun 무응답이면_한_번_다시_묻고_두_번째에_종료() {
        val d = dialog()
        d.start()
        val again = d.timeout()!!
        assertTrue(again.listen)
        assertTrue(again.say.contains("무엇을 드시고 싶으세요"))
        val end = d.timeout()!!
        assertFalse(end.listen)
        assertFalse(d.isActive)
    }

    @Test
    fun 단계마다_무응답이면_그_질문을_다시_한다() {
        val d = noRouteDialog()
        d.start()
        d.handle("치킨")
        assertTrue(d.timeout()!!.say.contains("픽업과 배달"))
        d.handle("배달")
        assertTrue(d.timeout()!!.say.contains("어디로 배달할까요"))
        d.handle("집")
        assertTrue(d.timeout()!!.say.contains("네 또는 아니요"))
        assertTrue(d.handle("네").order!!.delivery)   // 다시 물은 뒤에도 같은 메뉴로 주문
    }

    @Test
    fun 대화_중이_아니면_무응답을_무시() {
        assertNull(dialog().timeout())
    }

    // ---------- 말 해석 ----------

    @Test
    fun 픽업_배달_판별() {
        assertEquals(VoiceParser.Mode.DELIVERY, VoiceParser.mode("집으로 보내 줘"))
        assertEquals(VoiceParser.Mode.PICKUP, VoiceParser.mode("포장할게"))
        assertEquals(VoiceParser.Mode.PICKUP, VoiceParser.mode("들러서 받을게"))
        assertNull(VoiceParser.mode("치킨"))
    }

    @Test
    fun 장소_판별은_별칭과_이름_띄어쓰기_무시() {
        val places = listOf(home, gangnam)
        assertEquals(home, VoiceParser.place("집으로", places))
        assertEquals(gangnam, VoiceParser.place("강남 역", places))
        assertNull(VoiceParser.place("부산역", places))
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

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

    @Test
    fun 음식을_못_알아들으면_방식을_묻지_않고_음식을_다시_묻는다() {
        val d = dialog()
        d.start()
        val again = d.handle("음…")
        assertTrue(again.listen)
        assertTrue(again.say.contains("무엇을 드시고 싶으세요"))
        assertFalse(again.say.contains(VoiceDialog.MODE_QUESTION))
        // 두 번 연속 못 알아들으면 종료
        assertFalse(d.handle("흠").listen)
        assertFalse(d.isActive)
    }

    @Test
    fun 아무거나라고_하면_방식을_묻는다() {
        val d = dialog()
        d.start()
        assertEquals(VoiceDialog.MODE_QUESTION, d.handle("아무거나 괜찮아").say)
    }

    @Test
    fun 처음에_방식만_말하면_받아_두고_음식을_묻는다() {
        val d = dialog()
        d.start()
        val r = d.handle("배달로 할래")
        assertTrue(r.say.contains("무엇을 드시고 싶으세요"))
        assertTrue(d.handle("치킨").say.contains("배달로 주문할까요"))   // 방식은 다시 묻지 않음
    }

    @Test
    fun 배달이_도착보다_늦어도_알려_주고_주문할_수_있다() {
        val late = Recommendation(chicken, chicken.menus[0], 600, 900, 0.0, delivery = true, deliveryMin = 5)  // 15분 vs 준비 25분
        val d = VoiceDialog({ _, delivery, _ -> if (delivery) listOf(late) else emptyList() })
        d.start()
        val p = d.handle("치킨 배달")
        assertTrue(p.say, p.say.contains("그 뒤 10분쯤 지나 배달돼요"))
        assertTrue(d.handle("네").order!!.delivery)
    }

    @Test
    fun 다_별로라고_하면_다음_후보_대신_음식을_다시_묻는다() {
        val d = dialog()
        d.start()
        d.handle("국물 픽업")
        val r = d.handle("뭔가 다 별론데")
        assertNull(r.order)
        assertTrue(r.say, r.say.contains("무엇을 드시고 싶으세요"))
        assertTrue(d.handle("치킨").say.contains("후라이드 치킨"))   // 픽업은 기억해서 다시 묻지 않음
    }

    @Test
    fun 별론데도_아니요로_알아듣는다() {
        assertEquals(VoiceParser.Answer.NO, VoiceParser.answer("그건 별론데"))
    }

    @Test
    fun 들를_매장이_가득_차면_픽업_대신_배달을_권한다() {
        val d = VoiceDialog(::fakeRecommend, pickupSlots = { 0 })
        d.start()
        val r = d.handle("치킨 픽업")
        assertNull(r.order)
        assertTrue(r.say, r.say.contains("픽업은 더 추가할 수 없어요"))
        assertTrue(d.handle("배달").say.contains("배달로 주문할까요"))
    }

    @Test
    fun 이미_픽업이_있으면_가까운_곳부터_들른다고_안내() {
        val d = VoiceDialog(::fakeRecommend, pickupsPending = { true })
        d.start()
        d.handle("국물 픽업")
        val done = d.handle("네")
        assertTrue(done.say, done.say.contains("가까운 매장부터"))
    }

    @Test
    fun 승낙이_아닌_말로는_주문하지_않는다() {
        // "콜라"의 콜, "예약"의 예, "안 좋아"의 좋아, "방금 뭐라고"의 방금, "처음부터"의 처음에 걸려 주문되던 문제
        for (say in listOf("콜라 있어?", "콜라 있어", "안 좋아", "예약돼?", "방금 뭐라고 했어?", "아까 그거 뭐야?", "얼마라고?")) {
            val d = dialog()
            d.start()
            d.handle("국물 픽업")
            val r = d.handle(say)
            assertNull(say, r.order)
            assertTrue(say, r.listen)
        }
    }

    @Test
    fun 질문하면_제안을_다시_읽어_준다() {
        val d = dialog()
        d.start()
        d.handle("국물 픽업")
        assertTrue(d.handle("뭐라고?").say.contains("다시 말씀드릴게요. 한끼찌개의 김치찌개, 9000원"))
    }

    @Test
    fun 처음부터_다시라고_하면_음식부터_다시_묻는다() {
        val d = dialog()
        d.start()
        d.handle("국물 픽업")
        val r = d.handle("처음부터 다시 할래")
        assertNull(r.order)
        assertTrue(r.say.contains("무엇을 드시고 싶으세요"))
        assertEquals(VoiceDialog.MODE_QUESTION, d.handle("치킨").say)   // 픽업도 버렸으니 다시 물음
    }

    @Test
    fun 짧은_승낙은_낱말_그대로일_때만() {
        for (yes in listOf("네", "넵", "응", "어", "예", "콜", "ㅇㅇ", "네 주문해 주세요", "응 좋아", "네네"))
            assertEquals(yes, VoiceParser.Answer.YES, VoiceParser.answer(yes))
        for (no in listOf("콜라", "예약", "응답", "네가 골라"))
            assertEquals(no, VoiceParser.Answer.UNKNOWN, VoiceParser.answer(no))
    }

    @Test
    fun 중국집_집밥은_배달_장소_집이_아니다() {
        assertNull(VoiceParser.place("중국집 배달", listOf(home, gangnam)))
        assertNull(VoiceParser.place("집밥 배달해줘", listOf(home, gangnam)))
        assertEquals(home, VoiceParser.place("집으로 보내 줘", listOf(home, gangnam)))
        val d = noRouteDialog()
        d.start()
        assertTrue(d.handle("중국집 배달").say.contains("어디로 배달할까요"))   // 장소를 다시 물음
    }

    @Test
    fun 목적지가_있는데_다른_곳으로_배달하라면_목적지_기준이라고_알려_준다() {
        val d = VoiceDialog(::fakeRecommend, places = { listOf(home, gangnam) }, destinationName = { "강남역" })
        d.start()
        val r = d.handle("치킨 집으로 배달")
        assertTrue(r.say, r.say.startsWith("집은 지금 가시는 목적지가 아니라서 목적지 근처에서 찾았어요."))
        // 목적지와 같은 곳이면 안내 없음
        val d2 = VoiceDialog(::fakeRecommend, places = { listOf(home, gangnam) }, destinationName = { "강남역" })
        d2.start()
        assertFalse(d2.handle("치킨 강남역으로 배달").say.contains("목적지가 아니라서"))
    }

    @Test
    fun 수량을_말하면_이름과_가격에_담아_주문한다() {
        val d = dialog()
        d.start()
        val p = d.handle("치킨 두 마리 픽업")
        assertTrue(p.say, p.say.contains("후라이드 치킨 2개, 모두 38000원"))
        val done = d.handle("네")
        assertEquals("후라이드 치킨 ×2", done.order!!.menu.name)
        assertEquals(38000, done.order!!.menu.price)
    }

    @Test
    fun 제안_중에_수량만_말해도_그_수량으로_주문한다() {
        val d = dialog()
        d.start()
        d.handle("국물 픽업")
        val done = d.handle("세 개로 해 줘")
        assertEquals("김치찌개 ×3", done.order!!.menu.name)
        assertEquals(27000, done.order!!.menu.price)
    }

    @Test
    fun 수량은_단위가_있어야_하고_1에서_10까지() {
        assertEquals(2, VoiceParser.quantity("2개"))
        assertEquals(4, VoiceParser.quantity("네 잔"))
        assertNull(VoiceParser.quantity("네"))
        assertNull(VoiceParser.quantity("두 번째 거"))
        assertNull(VoiceParser.quantity("50개"))
    }

    @Test
    fun 파는_곳이_없는_음식은_없다고_알려_준다() {
        val d = dialog()
        d.start()
        val r = d.handle("비빔밥")
        assertTrue(r.say, r.say.startsWith("비빔밥은 주문할 수 있는 매장이 없어요"))
        assertTrue(d.handle("냉면 먹고 싶어").say.startsWith("냉면은"))
        assertTrue(d.isActive)
        assertEquals(null, VoiceParser.notSold("회사로 배달"))   // "회"는 회사와 겹치지 않음
    }

    @Test
    fun 뭐_있어_모르겠어는_아무거나로_받는다() {
        for (say in listOf("뭐 있어?", "모르겠어", "네가 골라 줘")) {
            val d = dialog()
            d.start()
            assertEquals(say, VoiceDialog.MODE_QUESTION, d.handle(say).say)
        }
    }

    @Test
    fun 다음에_할게는_대화를_끝낸다() {
        val d = dialog()
        d.start()
        d.handle("국물 픽업")
        assertFalse(d.handle("다음에 할게").listen)
        assertFalse(d.isActive)
    }

    @Test
    fun 이미_주문한_메뉴는_한_번_더_묻는다() {
        val d = VoiceDialog(::fakeRecommend, alreadyOrdered = { it.menu.name == "김치찌개" })
        d.start()
        d.handle("국물 픽업")
        val ask = d.handle("네")
        assertNull(ask.order)
        assertTrue(ask.say, ask.say.contains("김치찌개는 이미 주문하셨어요. 하나 더 주문할까요?"))
        assertEquals("김치찌개", d.handle("네").order!!.menu.name)   // 한 번 더 "네"면 주문
    }

    @Test
    fun 이미_주문한_메뉴를_묻고_아니라고_하면_다음_후보() {
        val d = VoiceDialog(::fakeRecommend, alreadyOrdered = { it.menu.name == "김치찌개" })
        d.start()
        d.handle("국물 픽업")
        d.handle("네")
        val next = d.handle("아니")
        assertNull(next.order)
        assertTrue(next.say.contains("된장찌개"))
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

    // ---------- 유연한 대답 (2026-11-14) ----------

    private val pizza = Store("s3", "양재피자 (가상)", "피자", 0.0, 0.0, 0, 24, true, true, listOf(
        menu("m4", "페퍼로니 피자", 18900, 12, "여럿"),
        menu("m5", "1인 콤비네이션 피자", 10900, 10, "식사")))

    private fun richDialog() = VoiceDialog({ tags, _, _ ->
        val list = listOf(rec(jjigae, 0), rec(jjigae, 1), rec(chicken, 0), rec(pizza, 0), rec(pizza, 1))
        if (tags.isEmpty()) list else list.filter { r -> r.menu.tags.any { it in tags } }
    })

    @Test
    fun 비슷한_말도_음식으로_알아듣는다() {
        assertEquals("치킨", VoiceParser.foodKeyword("통닭 땡기네"))
        assertEquals("떡볶이", VoiceParser.foodKeyword("떡뽁이 먹을래"))
        assertEquals("커피", VoiceParser.foodKeyword("아메리카노 한 잔"))
        assertEquals("중식", VoiceParser.foodKeyword("탕수육 어때"))
        assertFalse("국물" in VoiceParser.tags("탕수육"))           // '탕'수육은 국물이 아님
        assertEquals("피자", VoiceParser.foodKeyword("치킨 말고 피자"))
    }

    @Test
    fun 방식도_여러_말로_알아듣는다() {
        assertEquals(VoiceParser.Mode.PICKUP, VoiceParser.mode("내가 가지러 갈게"))
        assertEquals(VoiceParser.Mode.DELIVERY, VoiceParser.mode("갖다 줘"))
        assertEquals(VoiceParser.Mode.PICKUP, VoiceParser.mode("배달 말고"))
        assertEquals(VoiceParser.Mode.DELIVERY, VoiceParser.mode("픽업 말고 배달"))
    }

    @Test
    fun 음식_종류가_태그보다_우선() {
        val d = richDialog()
        d.start()
        // '배고파'(식사 태그)에 치킨 메뉴는 없지만 치킨을 말했으므로 치킨을 제안
        assertTrue(d.handle("배고파 치킨 픽업").say.contains("후라이드 치킨"))
    }

    @Test
    fun 원하는_음식이_없으면_대신_다른_메뉴를_제안() {
        val d = dialog()
        d.start()
        val r = d.handle("피자 픽업")
        assertTrue(r.say.startsWith("가는 길에 피자가 없어요. 대신 "))
        assertTrue(r.listen)
        assertEquals("김치찌개", d.handle("네").order!!.menu.name)
    }

    @Test
    fun 제안_중_다른_음식으로_바꾼다() {
        val d = richDialog()
        d.start()
        assertTrue(d.handle("치킨 픽업").say.contains("후라이드 치킨"))
        assertTrue(d.handle("치킨 말고 피자").say.contains("피자"))
    }

    @Test
    fun 방금_거로_하면_앞서_제안한_메뉴를_주문() {
        val d = dialog()
        d.start()
        d.handle("국물 픽업")                         // 김치찌개
        assertTrue(d.handle("다른 거").say.contains("된장찌개"))
        assertEquals("김치찌개", d.handle("아니 방금 거로 주문해 줘").order!!.menu.name)
    }

    @Test
    fun 처음_거로_하면_첫_제안을_주문() {
        val d = dialog()
        d.start()
        d.handle("아무거나 픽업")
        d.handle("아니")
        d.handle("다음")
        assertEquals("김치찌개", d.handle("처음 거로 할게").order!!.menu.name)
    }

    @Test
    fun 더_싼_거를_찾는다() {
        val d = richDialog()
        d.start()
        assertTrue(d.handle("피자 픽업").say.contains("페퍼로니 피자"))
        assertTrue(d.handle("더 싼 거 없어?").say.contains("1인 콤비네이션 피자"))
        assertTrue(d.handle("더 싼 거").say.contains("가장 싼"))
    }

    @Test
    fun 같은_방식을_다시_말하면_주문으로_받는다() {
        val d = dialog()
        d.start()
        d.handle("치킨 배달")
        assertTrue(d.handle("배달로 주문해 줘").order!!.delivery)   // 다시 찾지 않고 바로 주문
    }

    @Test
    fun 여러_긍정_표현() {
        for (t in listOf("그걸로", "좋아요", "그거 부탁해", "먹을게", "ㅇㅇ", "콜"))
            assertEquals(t, VoiceParser.Answer.YES, VoiceParser.answer(t))
        for (t in listOf("별로야", "패스", "싫어", "노"))
            assertEquals(t, VoiceParser.Answer.NO, VoiceParser.answer(t))
    }
}

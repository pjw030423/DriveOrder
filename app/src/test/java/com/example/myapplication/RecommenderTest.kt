package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recommender 단위 테스트: 데모 경로(42dot → 강남역)와 가상 매장 5곳으로 검증 */
class RecommenderTest {

    // 출발: 42dot 근처 (에뮬레이터 기본 위치), 목적지: 강남역 근처
    private val curLat = 37.48544722; private val curLng = 127.03636666
    private val destLat = 37.4979;    private val destLng = 127.0276

    private fun menu(id: String, name: String, price: Int, cook: Int, vararg tags: String) =
        Menu(id, name, price, cook, tags.toList())

    private val stores = listOf(
        Store("store_001", "한끼찌개", "한식", 37.4975, 127.0283, 10, 22, true, true, listOf(
            menu("m1", "김치찌개", 9000, 10, "국물", "매운"),
            menu("m2", "된장찌개", 8500, 10, "국물"))),
        Store("store_002", "역삼버거랩", "패스트푸드", 37.4990, 127.0310, 9, 23, true, true, listOf(
            menu("m3", "클래식 버거 세트", 8900, 7, "빠름"),
            menu("m4", "감자튀김", 3000, 5, "빠름"))),
        Store("store_003", "양재국수", "분식", 37.4870, 127.0350, 11, 21, true, false, listOf(
            menu("m5", "잔치국수", 7000, 6, "국물", "빠름"))),       // 출발지 바로 옆 → 조리할 시간 부족
        Store("store_004", "테헤란샐러드", "샐러드", 37.4985, 127.0265, 8, 20, true, true, listOf(
            menu("m6", "닭가슴살 샐러드", 9900, 5, "가벼움"))),
        Store("store_005", "서초치킨공방", "치킨", 37.4930, 127.0320, 15, 24, true, true, listOf(
            menu("m7", "후라이드 치킨", 19000, 20, "여럿")))         // 조리 20분 → 중간 지점이라 시간 부족
    )

    private fun rec(remainSec: Long, hour: Int, tags: Set<String> = emptySet(), limit: Int = 10) =
        Recommender.recommend(stores, curLat, curLng, destLat, destLng, remainSec, hour, tags, limit)

    @Test
    fun 국물을_원하면_목적지근처_찌개를_추천하고_출발지옆_국수는_제외() {
        val r = rec(remainSec = 1260, hour = 12, tags = setOf("국물"))
        assertEquals("store_001", r.first().store.id)
        assertEquals("된장찌개", r.first().menu.name)          // 점수 같으면 더 싼 메뉴 먼저
        assertFalse(r.any { it.store.id == "store_003" })     // 도착까지 약 3분 → 6분 조리 불가
    }

    @Test
    fun 모든_추천은_도착_전에_조리가_끝난다() {
        for (remain in listOf(300L, 600L, 1260L)) {
            rec(remainSec = remain, hour = 18).forEach {
                assertTrue("${it.menu.name} 여유 ${it.slackSec}초", it.slackSec >= 0)
            }
        }
    }

    @Test
    fun 조리시간이_긴_치킨은_시간이_부족하면_제외() {
        val r = rec(remainSec = 1260, hour = 18)               // 치킨집 영업 중인 시간
        assertFalse(r.any { it.store.id == "store_005" })
    }

    @Test
    fun 영업시간이_아닌_매장은_제외() {
        val r = rec(remainSec = 1260, hour = 21)               // 샐러드(~20시), 국수(~21시) 마감
        assertFalse(r.any { it.store.id == "store_004" || it.store.id == "store_003" })
        assertTrue(r.any { it.store.id == "store_001" })       // 찌개집은 22시까지
    }

    @Test
    fun 경로에서_먼_매장은_제외() {
        val far = Store("far", "먼가게", "한식", 37.5600, 126.9700, 0, 24, true, true,
            listOf(menu("x", "국밥", 9000, 5, "국물")))
        val r = Recommender.recommend(stores + far, curLat, curLng, destLat, destLng, 1260, 12, setOf("국물"), 10)
        assertFalse(r.any { it.store.id == "far" })
    }

    // ---------- 배달 ----------

    private fun delivery(remainSec: Long, hour: Int, tags: Set<String> = emptySet()) =
        Recommender.recommendDelivery(stores, destLat, destLng, remainSec, hour, tags, limit = 10)

    @Test
    fun 배달은_목적지_근처_배달가능_매장만_추천하고_경로와_무관() {
        val r = delivery(remainSec = 1620, hour = 18)
        assertTrue(r.all { it.delivery })
        assertFalse(r.any { it.store.id == "store_003" })     // 배달 불가 매장
        assertTrue(r.any { it.store.id == "store_005" })      // 픽업으로는 시간 부족했던 치킨도 배달은 가능
    }

    @Test
    fun 배달은_조리와_배달_이동을_합쳐_도착_전에_끝나야_한다() {
        val chicken = delivery(remainSec = 1620, hour = 18).first { it.store.id == "store_005" }
        assertTrue(chicken.prepSec <= 1620)
        assertEquals(chicken.prepSec, (20 + chicken.deliveryMin) * 60L)
    }

    @Test
    fun 배달이_도착보다_늦어도_제외하지_않고_뒤로_미룬다() {
        // 목적지까지 20분이면 조리 20분 + 배달 시간을 못 맞춤 → 그래도 주문 가능 (도착 후 기다림)
        val r = delivery(remainSec = 1200, hour = 18)
        val late = r.first { it.store.id == "store_005" }
        assertTrue(late.slackSec < 0)
        // 같은 매장이면 늦는 만큼 점수가 낮음
        val onTime = delivery(remainSec = 3600, hour = 18).first { it.store.id == "store_005" }
        assertTrue(late.score < onTime.score)
    }

    @Test
    fun 배달_이동_시간은_준비_3분과_거리를_올림해_더한다() {
        assertEquals(3, Recommender.deliveryMinutes(0.0))
        assertEquals(4, Recommender.deliveryMinutes(333.0))
        assertEquals(5, Recommender.deliveryMinutes(334.0))
    }

    @Test
    fun 목적지에서_3km_넘는_매장은_배달하지_않는다() {
        val far = Store("far", "먼집", "한식", 37.53, 127.0276, 0, 24, true, true, listOf(menu("x", "국밥", 9000, 5, "국물")))
        assertTrue(Recommender.recommendDelivery(listOf(far), destLat, destLng, 3600, 12).isEmpty())
    }

    // ---------- 경로가 없을 때: 현재 위치 근처 픽업 ----------

    @Test
    fun 경로가_없으면_근처_매장을_조리시간과_무관하게_추천하고_대기가_짧은_순() {
        // 양재국수 옆에서 출발: 강남역 매장들은 5km 안, 모두 후보
        val recs = Recommender.recommendNearby(stores, 37.4870, 127.0350, 16, limit = 10)
        assertTrue(recs.isNotEmpty())
        assertTrue(recs.any { it.menu.cookMin * 60 > it.arriveSec })   // 조리가 더 걸려도 제외하지 않음
        assertTrue(recs.any { it.store.name == "양재국수" })
        // 바로 옆 국수집(도착 즉시, 조리 6분 대기)보다 가는 동안 조리가 끝나는 샐러드집(약 5분 거리, 조리 5분)이 먼저
        assertEquals("테헤란샐러드", recs.first().store.name)
        assertTrue(recs.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun 근처_반경_밖_매장과_픽업_불가_매장은_제외() {
        val far = Recommender.recommendNearby(stores, 37.40, 127.10, 16)   // 강남역에서 10km 이상
        assertTrue(far.isEmpty())
        val noPickup = stores.map { it.copy(pickupAvailable = false) }
        assertTrue(Recommender.recommendNearby(noPickup, destLat, destLng, 16).isEmpty())
    }

    @Test
    fun 예상_주행시간은_직선거리의_1_3배를_시속_36km로() {
        assertEquals(130L, Recommender.driveSecEstimate(1000.0))   // 1000m × 1.3 ÷ 600m/분 = 2.17분
    }
}

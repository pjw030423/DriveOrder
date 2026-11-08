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
}

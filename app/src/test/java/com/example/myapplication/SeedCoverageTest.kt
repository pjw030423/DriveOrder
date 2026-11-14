package com.example.myapplication

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 시연 데이터(stores_seed.json)로 음식 종류 × 상황(경로 유무, 픽업/배달)마다 제안할 메뉴가 있는지 확인.
 * 출발: 에뮬레이터 기본 위치(성남 42dot), 목적지: 강남역 (내비 13km·21분). 10시~자정 영업 시간대 기준.
 */
class SeedCoverageTest {

    private val stores: List<Store> = run {
        val f = listOf("src/main/assets/stores_seed.json", "app/src/main/assets/stores_seed.json").map(::File).first { it.exists() }
        val arr = JSONObject(f.readText(Charsets.UTF_8)).getJSONArray("stores")
        (0 until arr.length()).map { storeFromJson(arr.getJSONObject(it)) }
    }

    private val startLat = 37.4127212; private val startLng = 127.094681
    private val gangnamLat = 37.4979; private val gangnamLng = 127.0276
    private val routeSec = 1260L

    private fun scenarios(hour: Int): Map<String, List<Recommendation>> {
        val est = Recommender.driveSecEstimate(Recommender.distanceM(startLat, startLng, gangnamLat, gangnamLng))
        return mapOf(
            "경로 없음·픽업" to Recommender.recommendNearby(stores, startLat, startLng, hour, limit = 1000),
            "경로 있음·픽업" to Recommender.recommend(stores, startLat, startLng, gangnamLat, gangnamLng, routeSec, hour, limit = 1000),
            "경로 있음·배달" to Recommender.recommendDelivery(stores, gangnamLat, gangnamLng, routeSec, hour, limit = 1000),
            "경로 없음·강남역 배달" to Recommender.recommendDelivery(stores, gangnamLat, gangnamLng, est, hour, limit = 1000),
        )
    }

    @Test
    fun 모든_음식이_모든_상황에서_후보가_있다() {
        val foods = VoiceParser.FOODS.filter { it.name != "파스타" }   // 파스타는 가는 길 피자집에만 있음
        val gaps = mutableListOf<String>()
        for (hour in listOf(10, 12, 15, 19, 23)) {
            for ((name, recs) in scenarios(hour)) {
                for (f in foods) if (recs.none { VoiceParser.matches(f, it) }) gaps += "${hour}시 $name: ${f.name}"
            }
        }
        assertTrue("후보 없음: $gaps", gaps.isEmpty())
    }

    @Test
    fun 모든_취향_태그가_모든_상황에서_후보가_있다() {
        val gaps = mutableListOf<String>()
        for (hour in listOf(10, 12, 19, 23)) {
            for ((name, recs) in scenarios(hour)) {
                for (t in listOf("국물", "빠름", "매운", "가벼움", "여럿", "야식", "음료", "식사", "간식"))
                    if (recs.none { t in it.menu.tags }) gaps += "${hour}시 $name: #$t"
            }
        }
        assertTrue("후보 없음: $gaps", gaps.isEmpty())
    }
}

package com.example.myapplication

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 메뉴 1개 */
data class Menu(
    val id: String,
    val name: String,
    val price: Int,
    val cookMin: Int,          // 조리 시간(분)
    val tags: List<String>     // 매운, 국물, 빠름, 가벼움 …
)

/** 매장 1개 */
data class Store(
    val id: String,
    val name: String,
    val category: String,
    val lat: Double,
    val lng: Double,
    val openHour: Int,
    val closeHour: Int,
    val pickupAvailable: Boolean,
    val deliveryAvailable: Boolean,
    val menus: List<Menu>
)

/** 추천 결과 1건 */
data class Recommendation(
    val store: Store,
    val menu: Menu,
    val detourM: Int,      // 가는 길(직선 경로)에서 벗어난 거리(m)
    val arriveSec: Long,   // 매장 도착까지 예상 시간(초)
    val score: Double
) {
    /** 조리가 끝난 뒤 도착까지 남는 여유(초). 0 이상이어야 도착 시 음식이 준비됨 */
    val slackSec: Long get() = arriveSec - menu.cookMin * 60L
}

/**
 * "도착할 때 딱 맞춰 나올 수 있는 메뉴"를 고르는 추천 로직 (규칙 기반).
 * Pleos SDK와 무관한 순수 계산이라 에뮬레이터 없이 단위 테스트가 가능합니다.
 *
 * 추천 기준
 *  ① 영업 중인가          openHour ≤ 지금 < closeHour
 *  ② 포장 가능한가        pickupAvailable
 *  ③ 가는 길에 있는가      현재 위치→목적지 직선에서 MAX_DETOUR_M 이내
 *  ④ 도착 전 조리 가능한가  조리 시간 ≤ 매장 도착까지 걸리는 시간   ← 핵심
 *  ⑤ 원하는 맛인가        tags 일치 (원하는 태그가 있을 때만)
 */
object Recommender {

    /** 경로에서 이만큼(m)까지 벗어난 매장은 "가는 길"로 인정 */
    const val MAX_DETOUR_M = 1000.0

    fun recommend(
        stores: List<Store>,
        curLat: Double, curLng: Double,      // 현재 위치
        destLat: Double, destLng: Double,    // 목적지
        remainSec: Long,                     // 목적지까지 남은 시간(초, 내비 ETA)
        hour: Int,                           // 현재 시각(0~23시)
        wantedTags: Set<String> = emptySet(),
        limit: Int = 3
    ): List<Recommendation> {
        val results = mutableListOf<Recommendation>()

        for (store in stores) {
            if (!isOpen(store, hour)) continue              // ①
            if (!store.pickupAvailable) continue            // ②

            val (detour, t) = distanceToSegment(
                store.lat, store.lng, curLat, curLng, destLat, destLng
            )
            if (detour > MAX_DETOUR_M) continue             // ③

            // 매장이 경로의 t 지점(0=출발, 1=목적지)에 있으면, 도착까지 대략 남은시간 × t
            val arriveSec = (remainSec * t).toLong()

            for (menu in store.menus) {
                if (menu.cookMin * 60L > arriveSec) continue        // ④ 도착 전 조리 불가 → 제외
                val matches = menu.tags.count { it in wantedTags }
                if (wantedTags.isNotEmpty() && matches == 0) continue // ⑤

                // 점수: 원하는 맛이 많이 맞을수록 ↑, 경로에서 멀수록 ↓
                val score = matches * 10.0 - detour / 100.0
                results += Recommendation(store, menu, detour.roundToInt(), arriveSec, score)
            }
        }
        return results
            .sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.menu.price })
            .take(limit)
    }

    fun isOpen(store: Store, hour: Int): Boolean = hour >= store.openHour && hour < store.closeHour

    /**
     * 점 P(매장)에서 선분 AB(현재 위치→목적지)까지의 최단 거리(m)와 투영 위치 t(0~1).
     * 수 km 범위에서는 지구를 평면으로 근사해도 오차가 작습니다.
     */
    fun distanceToSegment(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ): Pair<Double, Double> {
        val mPerDegLat = 111_320.0                                  // 위도 1도 ≈ 111.32km
        val mPerDegLng = 111_320.0 * cos(Math.toRadians(aLat))      // 경도 1도는 위도에 따라 줄어듦
        val px = (pLng - aLng) * mPerDegLng
        val py = (pLat - aLat) * mPerDegLat
        val bx = (bLng - aLng) * mPerDegLng
        val by = (bLat - aLat) * mPerDegLat
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy) to t
    }
}

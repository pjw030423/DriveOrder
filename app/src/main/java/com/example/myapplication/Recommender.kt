package com.example.myapplication

import kotlin.math.ceil
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
    val detourM: Int,      // 픽업: 가는 길(직선 경로)에서 벗어난 거리(m) / 배달: 매장~목적지 거리(m)
    val arriveSec: Long,   // 픽업: 매장 도착까지 / 배달: 목적지 도착까지 예상 시간(초)
    val score: Double,
    val delivery: Boolean = false,
    val deliveryMin: Int = 0   // 배달: 매장 → 목적지 이동 시간(분)
) {
    /** 준비 시간(초): 조리 + (배달이면) 배달 이동 */
    val prepSec: Long get() = (menu.cookMin + deliveryMin) * 60L

    /** 준비가 끝난 뒤 도착까지 남는 여유(초). 0 이상이어야 도착 시 음식을 받을 수 있음 */
    val slackSec: Long get() = arriveSec - prepSec
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
 *
 * 배달(recommendDelivery)은 ②③④가 바뀜
 *  ② 배달 가능한가        deliveryAvailable
 *  ③ 목적지 근처인가      목적지에서 MAX_DELIVERY_M 이내 (경로와 무관 → 경유지 없음)
 *  ④ 도착 전 받을 수 있나  조리 + 배달 이동 ≤ 목적지 도착까지 걸리는 시간
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
        return rank(results, limit)
    }

    /** 점수 높은 순, 같으면 싼 순으로 limit개 */
    private fun rank(results: List<Recommendation>, limit: Int): List<Recommendation> =
        results.sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.menu.price }).take(limit)

    /** 목적지에서 이만큼(m)까지의 매장만 배달 */
    const val MAX_DELIVERY_M = 3000.0

    /** 배달 이동 속도(m/분): 도심 오토바이 약 20km/h */
    const val DELIVERY_M_PER_MIN = 333.0

    /** 배달 출발 준비(포장·배차) 시간(분) */
    const val DELIVERY_HANDOFF_MIN = 3

    /** 매장 → 목적지 배달 이동 시간(분) 추정: 준비 + 직선거리 ÷ 속도 (올림) */
    fun deliveryMinutes(distanceM: Double): Int =
        DELIVERY_HANDOFF_MIN + ceil(distanceM / DELIVERY_M_PER_MIN).toInt()

    /** 목적지 근처 매장에서 "도착할 때 맞춰 배달되는" 메뉴 추천 */
    fun recommendDelivery(
        stores: List<Store>,
        destLat: Double, destLng: Double,    // 목적지 (배달 받을 곳)
        remainSec: Long,                     // 목적지까지 남은 시간(초, 내비 ETA)
        hour: Int,
        wantedTags: Set<String> = emptySet(),
        limit: Int = 3
    ): List<Recommendation> {
        val results = mutableListOf<Recommendation>()
        for (store in stores) {
            if (!isOpen(store, hour)) continue                      // ①
            if (!store.deliveryAvailable) continue                  // ②
            val dist = distanceM(store.lat, store.lng, destLat, destLng)
            if (dist > MAX_DELIVERY_M) continue                     // ③
            val deliveryMin = deliveryMinutes(dist)
            for (menu in store.menus) {
                if ((menu.cookMin + deliveryMin) * 60L > remainSec) continue  // ④
                val matches = menu.tags.count { it in wantedTags }
                if (wantedTags.isNotEmpty() && matches == 0) continue          // ⑤
                val score = matches * 10.0 - dist / 100.0
                results += Recommendation(store, menu, dist.roundToInt(), remainSec, score,
                    delivery = true, deliveryMin = deliveryMin)
            }
        }
        return rank(results, limit)
    }

    /** 경로가 없을 때 "근처"로 보는 반경(m) */
    const val NEARBY_M = 5000.0

    /**
     * 경로 조회 전 예상 주행 시간용: 평균 36km/h, 실제 도로는 직선보다 약 1.3배 김
     * (42dot → 강남역: 직선 11.1km, 내비 13km·21분 → 추정 24분. 2026-11-13 에뮬레이터 확인)
     */
    const val CAR_M_PER_MIN = 600.0
    const val ROAD_FACTOR = 1.3

    /** 직선거리(m) → 예상 주행 시간(초). 경로를 조회하기 전 후보를 고를 때만 쓰고, 주문은 내비 ETA로 함 */
    fun driveSecEstimate(distanceM: Double): Long = (distanceM * ROAD_FACTOR / CAR_M_PER_MIN * 60).toLong()

    /**
     * 경로가 없을 때: 현재 위치 근처에서 들러서 받을 메뉴 추천.
     * 출발 시각이 정해진 게 아니라 지금 바로 가는 것이므로 조리 시간으로 거르지 않고,
     * 도착 후 기다리는 시간이 짧을수록 점수를 높게 줌 (slackSec < 0 이면 그만큼 대기).
     */
    fun recommendNearby(
        stores: List<Store>,
        curLat: Double, curLng: Double,
        hour: Int,
        wantedTags: Set<String> = emptySet(),
        limit: Int = 3
    ): List<Recommendation> {
        val results = mutableListOf<Recommendation>()
        for (store in stores) {
            if (!isOpen(store, hour) || !store.pickupAvailable) continue
            val dist = distanceM(store.lat, store.lng, curLat, curLng)
            if (dist > NEARBY_M) continue
            val arriveSec = driveSecEstimate(dist)
            for (menu in store.menus) {
                val matches = menu.tags.count { it in wantedTags }
                if (wantedTags.isNotEmpty() && matches == 0) continue
                val waitMin = (menu.cookMin * 60L - arriveSec).coerceAtLeast(0) / 60.0
                val score = matches * 10.0 - waitMin - dist / 500.0
                results += Recommendation(store, menu, dist.roundToInt(), arriveSec, score)
            }
        }
        return rank(results, limit)
    }

    fun isOpen(store: Store, hour: Int): Boolean = hour >= store.openHour && hour < store.closeHour

    /** 두 지점 사이 직선거리(m) */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double =
        distanceToSegment(lat1, lng1, lat2, lng2, lat2, lng2).first

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

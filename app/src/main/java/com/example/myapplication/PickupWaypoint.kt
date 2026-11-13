package com.example.myapplication

/** 내비 경유지 목록에서 "주문한 매장" 경유지를 찾는 순수 로직 (단위 테스트용으로 분리) */
object PickupWaypoint {

    /** 내비가 좌표를 도로 위로 보정할 수 있어서 이 거리(m) 안이면 같은 장소로 봄 */
    const val MATCH_RADIUS_M = 150.0

    /**
     * 경유지가 주문 매장인지 판단.
     * 에뮬레이터 내비는 경유지 좌표를 0.0으로 돌려주는 경우가 있어(2026-11-08 확인)
     * 경유지 추가 때 넣은 이름(poiName = 매장 이름)을 먼저 비교하고, 좌표는 보조로 씀.
     */
    fun matches(wpName: String, wpLat: Double, wpLng: Double, storeName: String, storeLat: Double, storeLng: Double): Boolean {
        if (wpName.isNotBlank() && wpName.trim() == storeName.trim()) return true
        if (wpLat == 0.0 && wpLng == 0.0) return false
        return isSamePlace(wpLat, wpLng, storeLat, storeLng)
    }

    fun isSamePlace(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Boolean =
        Recommender.distanceM(lat1, lng1, lat2, lng2) <= MATCH_RADIUS_M
}

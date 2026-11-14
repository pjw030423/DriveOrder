package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Test

class PickupRouteTest {
    private val start = 37.4127 to 127.0947          // 42dot
    private val near = 37.4200 to 127.0900           // 약 1km
    private val mid = 37.4600 to 127.0600            // 약 6km
    private val far = 37.4979 to 127.0276            // 강남역, 약 11km

    @Test
    fun 가까운_곳부터_들른다() {
        val order = PickupRoute.nearestFirst(start.first, start.second, listOf(far, near, mid)) { it }
        assertEquals(listOf(near, mid, far), order)
    }

    @Test
    fun 다음_매장은_방금_들른_매장에서_가까운_곳() {
        // 출발지에선 b가 a보다 조금 가깝지만, a를 먼저 가면 c가 바로 옆 → 탐욕법은 출발지 기준 가장 가까운 b부터
        val a = 37.4300 to 127.0947
        val b = 37.4127 to 127.1150
        val order = PickupRoute.nearestFirst(start.first, start.second, listOf(a, b)) { it }
        assertEquals(b, order.first())
    }

    @Test
    fun 경유지는_2곳까지_목적지가_없으면_마지막_매장을_목적지로() {
        assertEquals(2, PickupRoute.slotsLeft(0, hasOwnDestination = true))
        assertEquals(0, PickupRoute.slotsLeft(2, hasOwnDestination = true))
        assertEquals(1, PickupRoute.slotsLeft(2, hasOwnDestination = false))
    }
}

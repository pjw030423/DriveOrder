package com.example.myapplication

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickupWaypointTest {

    private val store = "서초치킨공방 (가상)"

    @Test
    fun 내비가_좌표를_0으로_줘도_이름이_같으면_매장_경유지() {
        assertTrue(PickupWaypoint.matches(store, 0.0, 0.0, store, 37.4930, 127.0320))
    }

    @Test
    fun 이름이_다르고_좌표가_0이면_매장_아님() {
        assertFalse(PickupWaypoint.matches("강남역", 0.0, 0.0, store, 37.4930, 127.0320))
    }

    @Test
    fun 내비가_좌표를_조금_보정해도_같은_매장으로_본다() {
        // 위도 0.0005도 ≈ 56m
        assertTrue(PickupWaypoint.matches("", 37.4935, 127.0320, store, 37.4930, 127.0320))
    }

    @Test
    fun 수백미터_떨어진_경유지는_다른_장소() {
        // 위도 0.003도 ≈ 334m
        assertFalse(PickupWaypoint.matches("", 37.4960, 127.0320, store, 37.4930, 127.0320))
    }
}

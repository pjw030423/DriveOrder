package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Test

class DemoDriveTest {

    @Test
    fun 꺼져_있으면_내비_값을_그대로() {
        assertEquals(1380L, DemoDrive().remain(1380, 0))
    }

    @Test
    fun 켜지면_경과_시간만큼_줄어든다() {
        val d = DemoDrive().apply { toggle() }
        assertEquals(1380L, d.remain(1380, 1_000_000))
        // 내비는 계속 1380을 줘도(차가 안 움직임) 60초 뒤엔 1320
        assertEquals(1320L, d.remain(1380, 1_060_000))
    }

    @Test
    fun 정체를_넣으면_그만큼_늘어난다() {
        val d = DemoDrive().apply { toggle() }
        d.remain(1380, 0)
        d.addDelay(180)
        assertEquals(1560L - 10, d.remain(1380, 10_000))
    }

    @Test
    fun 전송_시각은_주행_중_고정되고_정체_시_미뤄진다() {
        val d = DemoDrive().apply { toggle() }
        val (_, send0) = OrderScheduler.schedule(0, d.remain(1380, 0), 1200)
        val (_, send60) = OrderScheduler.schedule(60_000, d.remain(1380, 60_000), 1200)
        assertEquals(send0, send60)                      // 정상 주행: 전송 시각 그대로
        d.addDelay(180)
        val (_, sendJam) = OrderScheduler.schedule(60_000, d.remain(1380, 60_000), 1200)
        assertEquals(send0 + 180_000, sendJam)           // 정체 3분 → 전송 3분 연기
    }

    @Test
    fun 주문마다_자기_도착_시간으로_줄어든다() {
        val d = DemoDrive().apply { toggle() }
        assertEquals(600L, d.remain(600, 0, "가까운 매장"))
        assertEquals(1200L, d.remain(1200, 0, "먼 매장"))
        assertEquals(540L, d.remain(600, 60_000, "가까운 매장"))
        assertEquals(1140L, d.remain(1200, 60_000, "먼 매장"))   // 예전엔 둘 다 540이 됨
    }
}

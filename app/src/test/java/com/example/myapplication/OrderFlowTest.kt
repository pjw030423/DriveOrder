package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 주문 생성·재계산 단위 테스트 (시각은 epoch 밀리초) */
class OrderFlowTest {

    private val now = 1_000_000_000L

    private fun order(status: String = Order.STATUS_SCHEDULED, sendAt: Long = now + 270_000) = Order(
        id = "o1", storeId = "s1", storeName = "한끼찌개", storeLat = 0.0, storeLng = 0.0,
        menuName = "김치찌개", price = 9000, cookMin = 10,
        createdAt = now, etaAt = now + 900_000, sendAt = sendAt, status = status
    )

    @Test
    fun 매장까지_15분_조리10분이면_4분30초뒤_전송() {
        val (eta, send) = OrderScheduler.schedule(now, storeRemainSec = 900, cookSec = 600)
        assertEquals(now + 900_000, eta)
        assertEquals(now + 270_000, send)
    }

    @Test
    fun 매장까지_남은시간은_경로위치_비율로_추정() {
        assertEquals(600L, OrderScheduler.storeRemainSec(destRemainSec = 1200, t = 0.5))
    }

    @Test
    fun 정체로_5분_늦어지면_전송도_5분_미뤄진다() {
        val updated = OrderScheduler.reschedule(order(), now, storeRemainSec = 1200)
        assertNotNull(updated)
        assertEquals(now + 1_200_000, updated!!.etaAt)
        assertEquals(now + 570_000, updated.sendAt)
    }

    @Test
    fun 변화가_작으면_서버에_올리지_않는다() {
        assertNull(OrderScheduler.reschedule(order(), now, storeRemainSec = 905))
    }

    @Test
    fun 이미_전송된_주문은_도착시각만_바뀐다() {
        val sent = order(status = Order.STATUS_COOKING, sendAt = now - 60_000)
        val updated = OrderScheduler.reschedule(sent, now, storeRemainSec = 1200)!!
        assertEquals(now - 60_000, updated.sendAt)
        assertEquals(now + 1_200_000, updated.etaAt)
    }

    @Test
    fun 전송시각이_이미_지났으면_되돌리지_않는다() {
        // 상태는 아직 scheduled지만 sendAt이 지나 매장 화면에 이미 떴을 수 있음
        val due = order(sendAt = now - 1_000)
        val updated = OrderScheduler.reschedule(due, now, storeRemainSec = 1500)!!
        assertEquals(now - 1_000, updated.sendAt)
    }
}

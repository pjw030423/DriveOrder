package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OrderScheduler 단위 테스트: 에뮬레이터 없이 PC에서 바로 실행됩니다. */
class OrderSchedulerTest {

    @Test
    fun 도착15분_조리10분이면_4분30초뒤_전송() {
        // 900초 − 600초 − 여유 30초 = 270초
        assertEquals(270L, OrderScheduler.secondsUntilSend(remainSec = 900, cookSec = 600))
        assertFalse(OrderScheduler.shouldSendNow(remainSec = 900, cookSec = 600))
    }

    @Test
    fun 남은시간이_조리시간보다_짧으면_즉시_전송() {
        assertEquals(0L, OrderScheduler.secondsUntilSend(remainSec = 300, cookSec = 600))
        assertTrue(OrderScheduler.shouldSendNow(remainSec = 300, cookSec = 600))
    }

    @Test
    fun 정체로_남은시간이_늘면_전송도_늦춰진다() {
        val before = OrderScheduler.secondsUntilSend(remainSec = 900, cookSec = 600)
        val after = OrderScheduler.secondsUntilSend(remainSec = 1200, cookSec = 600)
        assertTrue(after > before)
    }
}

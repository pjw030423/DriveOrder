package com.example.myapplication

/**
 * 데모용 가상 주행.
 *
 * 에뮬레이터에서는 차가 움직이지 않아(GPS NMEA 체크섬 문제로 내비가 위치를 무시함) 매장까지 남은 시간이
 * 줄지 않고, 그 결과 전송 시각이 계속 뒤로 밀려 주문이 매장에 가지 않음. 데모 영상·시연용으로,
 * 켜진 동안에는 내비가 처음 알려 준 남은 시간에서 실제 경과 시간만큼 줄어든 값을 씀(1배속 주행).
 * "정체" 버튼으로 남은 시간을 늘려 전송 시각이 미뤄지는 모습을 보여 줄 수 있음.
 */
class DemoDrive {
    var enabled = false
        private set
    private var startMs: Long? = null
    private var baseRemainSec = 0L
    private var delaySec = 0L

    fun toggle() { enabled = !enabled; reset() }

    fun reset() { startMs = null; delaySec = 0 }

    /** 정체 발생: 도착이 sec초 늦어짐 */
    fun addDelay(sec: Long) { delaySec += sec }

    /** 내비가 준 남은 시간 → (데모 중이면) 가상 주행 기준 남은 시간 */
    fun remain(navRemainSec: Long, nowMs: Long): Long {
        if (!enabled) return navRemainSec
        val start = startMs ?: nowMs.also { startMs = it; baseRemainSec = navRemainSec }
        return (baseRemainSec + delaySec - (nowMs - start) / 1000).coerceAtLeast(0)
    }
}

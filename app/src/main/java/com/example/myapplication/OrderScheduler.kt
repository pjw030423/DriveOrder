package com.example.myapplication

/**
 * 도착 시각에 맞춰 "언제 매장에 주문을 보낼지" 계산하는 핵심 로직.
 * Pleos SDK와 무관한 순수 계산이라 에뮬레이터 없이도 테스트할 수 있습니다.
 *
 * 공식: 주문 전송 시각 = 지금 + (남은 시간 − 준비 시간 − 여유)
 *  - 픽업: 남은 시간 = 매장까지, 준비 시간 = 조리
 *  - 배달: 남은 시간 = 목적지까지, 준비 시간 = 조리 + 배달 이동
 * 남은 시간·조리 시간은 '초', 시각(nowMs 등)은 epoch '밀리초'입니다.
 */
object OrderScheduler {

    /** 여유 시간: 정체·신호 등 오차를 흡수하려고 조금 일찍 보냄 (30초) */
    const val SAFETY_MARGIN_SEC = 30L

    /** 이 정도(밀리초) 이상 바뀌었을 때만 서버에 다시 올림 → 5초마다 쓰기 방지 */
    const val RESYNC_THRESHOLD_MS = 15_000L

    /** 지금부터 몇 초 뒤에 주문을 보내야 하는지. 이미 늦었으면 0 */
    fun secondsUntilSend(remainSec: Long, cookSec: Long, marginSec: Long = SAFETY_MARGIN_SEC): Long =
        (remainSec - cookSec - marginSec).coerceAtLeast(0)

    /** 지금 바로 보내야 하는지 */
    fun shouldSendNow(remainSec: Long, cookSec: Long, marginSec: Long = SAFETY_MARGIN_SEC): Boolean =
        secondsUntilSend(remainSec, cookSec, marginSec) == 0L

    /** 도착 예정 시각(etaAt)과 전송 시각(sendAt)을 epoch 밀리초로 계산. cookSec 자리에 배달은 조리+배달 시간 */
    fun schedule(nowMs: Long, storeRemainSec: Long, cookSec: Long): Pair<Long, Long> {
        val etaAt = nowMs + storeRemainSec * 1000
        val sendAt = nowMs + secondsUntilSend(storeRemainSec, cookSec) * 1000
        return etaAt to sendAt
    }

    /**
     * 목적지까지 남은 시간(내비 ETA)에서 "경로 위 매장"까지 남은 시간을 추정.
     * 매장이 경로의 t 지점(0=현재 위치, 1=목적지)에 있으면 남은 시간 × t.
     * (경유지를 내비에 추가한 뒤에는 내비가 주는 경유지 ETA로 바꾸는 것이 정확함)
     */
    fun storeRemainSec(destRemainSec: Long, t: Double): Long = (destRemainSec * t).toLong()

    /**
     * 주행 중 새 ETA로 주문을 다시 계산 (storeRemainSec: 픽업은 매장까지, 배달은 목적지까지 남은 시간).
     *  - 아직 전송 전(scheduled)이면 sendAt도 같이 미룸/당김. 단 이미 지난 sendAt은 되돌리지 않음.
     *  - 이미 전송됐으면 etaAt만 갱신 → 매장 화면에 "도착 예정"이 바뀌어 보임.
     * 변화가 작으면 null (서버에 안 올림).
     */
    fun reschedule(order: Order, nowMs: Long, storeRemainSec: Long): Order? {
        val (newEta, newSend) = schedule(nowMs, storeRemainSec, order.prepSec)
        val stillScheduled = order.status == Order.STATUS_SCHEDULED && order.sendAt > nowMs
        val sendAt = if (stillScheduled) newSend else order.sendAt
        val changed = kotlin.math.abs(newEta - order.etaAt) >= RESYNC_THRESHOLD_MS ||
            kotlin.math.abs(sendAt - order.sendAt) >= RESYNC_THRESHOLD_MS
        return if (changed) order.copy(etaAt = newEta, sendAt = sendAt) else null
    }
}

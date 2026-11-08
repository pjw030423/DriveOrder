package com.example.myapplication

import org.json.JSONObject

/**
 * 주문 1건. Firestore "orders" 컬렉션에 저장됩니다.
 *
 * 흐름: scheduled(앱이 전송 시각 관리) → sent(매장 화면에 표시) → cooking → ready → picked_up
 *  - 앱은 etaAt·sendAt만 고치고, 매장 웹은 status만 고칩니다 (서로 덮어쓰지 않도록 부분 갱신).
 *  - 매장 웹은 sendAt이 지난 주문만 보여 줍니다 → 앱이 꺼져도 정해진 시각에 매장에 뜸.
 * 시각은 모두 epoch 밀리초입니다.
 */
data class Order(
    val id: String,
    val storeId: String,
    val storeName: String,
    val storeLat: Double,
    val storeLng: Double,
    val menuName: String,
    val price: Int,
    val cookMin: Int,
    val createdAt: Long,
    val etaAt: Long,       // 매장 도착 예정 시각
    val sendAt: Long,      // 매장에 주문이 보이기 시작하는 시각
    val status: String = STATUS_SCHEDULED
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("storeId", storeId).put("storeName", storeName)
        .put("storeLat", storeLat).put("storeLng", storeLng)
        .put("menuName", menuName).put("price", price).put("cookMin", cookMin)
        .put("createdAt", createdAt).put("etaAt", etaAt).put("sendAt", sendAt)
        .put("status", status)

    companion object {
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_SENT = "sent"
        const val STATUS_COOKING = "cooking"
        const val STATUS_READY = "ready"
        const val STATUS_PICKED_UP = "picked_up"

        /** 추천 결과로 새 주문을 만듦 */
        fun create(rec: Recommendation, nowMs: Long): Order {
            val (etaAt, sendAt) = OrderScheduler.schedule(nowMs, rec.arriveSec, rec.menu.cookMin * 60L)
            return Order(
                id = "order_$nowMs",
                storeId = rec.store.id, storeName = rec.store.name,
                storeLat = rec.store.lat, storeLng = rec.store.lng,
                menuName = rec.menu.name, price = rec.menu.price, cookMin = rec.menu.cookMin,
                createdAt = nowMs, etaAt = etaAt, sendAt = sendAt
            )
        }

        fun statusLabel(status: String): String = when (status) {
            STATUS_SCHEDULED -> "전송 대기"
            STATUS_SENT -> "매장 접수 대기"
            STATUS_COOKING -> "조리 중"
            STATUS_READY -> "조리 완료 · 픽업 대기"
            STATUS_PICKED_UP -> "픽업 완료"
            else -> status
        }
    }
}

package com.example.myapplication

import org.json.JSONObject

/**
 * 주문 1건. Firestore "orders" 컬렉션에 저장됩니다.
 *
 * 흐름: scheduled(앱이 전송 시각 관리) → sent(매장 화면에 표시) → cooking → ready → picked_up
 *  - 픽업: etaAt = 매장 도착 시각. 매장을 내비 경유지로 추가하고, 운전자가 들러서 받음.
 *  - 배달: etaAt = 목적지 도착 시각. 경유지 없이, 매장이 목적지로 보내 도착 시각에 맞춰 받음.
 *          ready = 조리 완료·배달 출발, picked_up = 배달 완료 (상태 이름은 픽업과 같이 씀)
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
    val etaAt: Long,       // 픽업: 매장 도착 예정 시각 / 배달: 목적지 도착 예정 시각
    val sendAt: Long,      // 매장에 주문이 보이기 시작하는 시각
    val status: String = STATUS_SCHEDULED,
    val type: String = TYPE_PICKUP,
    val deliveryMin: Int = 0,      // 배달: 매장 → 목적지 이동 시간(분). 픽업은 0
    val deliveryTo: String = ""    // 배달: 받을 곳(내비 목적지 이름). 픽업은 빈 값
) {
    val isDelivery: Boolean get() = type == TYPE_DELIVERY

    /** 주문 전송부터 손님이 받을 때까지 필요한 시간(초): 조리 + (배달이면) 배달 이동 */
    val prepSec: Long get() = (cookMin + deliveryMin) * 60L

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("storeId", storeId).put("storeName", storeName)
        .put("storeLat", storeLat).put("storeLng", storeLng)
        .put("menuName", menuName).put("price", price).put("cookMin", cookMin)
        .put("createdAt", createdAt).put("etaAt", etaAt).put("sendAt", sendAt)
        .put("status", status)
        .put("type", type).put("deliveryMin", deliveryMin).put("deliveryTo", deliveryTo)

    companion object {
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_SENT = "sent"
        const val STATUS_COOKING = "cooking"
        const val STATUS_READY = "ready"
        const val STATUS_PICKED_UP = "picked_up"

        const val TYPE_PICKUP = "pickup"
        const val TYPE_DELIVERY = "delivery"

        /** 추천 결과로 새 주문을 만듦 (배달이면 deliveryTo에 목적지 이름) */
        fun create(rec: Recommendation, nowMs: Long, deliveryTo: String = ""): Order {
            val (etaAt, sendAt) = OrderScheduler.schedule(nowMs, rec.arriveSec, rec.prepSec)
            return Order(
                id = "order_$nowMs",
                storeId = rec.store.id, storeName = rec.store.name,
                storeLat = rec.store.lat, storeLng = rec.store.lng,
                menuName = rec.menu.name, price = rec.menu.price, cookMin = rec.menu.cookMin,
                createdAt = nowMs, etaAt = etaAt, sendAt = sendAt,
                type = if (rec.delivery) TYPE_DELIVERY else TYPE_PICKUP,
                deliveryMin = rec.deliveryMin,
                deliveryTo = if (rec.delivery) deliveryTo else ""
            )
        }

        fun statusLabel(status: String, delivery: Boolean = false): String = when (status) {
            STATUS_SCHEDULED -> "전송 대기"
            STATUS_SENT -> "매장 접수 대기"
            STATUS_COOKING -> "조리 중"
            STATUS_READY -> if (delivery) "조리 완료 · 배달 중" else "조리 완료 · 픽업 대기"
            STATUS_PICKED_UP -> if (delivery) "배달 완료" else "픽업 완료"
            else -> status
        }
    }
}

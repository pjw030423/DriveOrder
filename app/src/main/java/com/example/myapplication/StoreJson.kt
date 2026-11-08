package com.example.myapplication

import org.json.JSONArray
import org.json.JSONObject

/** Firestore에서 받은 JSON → Store 객체 변환 (Recommender를 org.json과 분리해 단위 테스트를 쉽게 함) */
fun storeFromJson(o: JSONObject): Store {
    val menusJson = o.optJSONArray("menus") ?: JSONArray()
    val menus = (0 until menusJson.length()).map { i ->
        val m = menusJson.getJSONObject(i)
        val tagsJson = m.optJSONArray("tags") ?: JSONArray()
        Menu(
            id = m.optString("id"),
            name = m.optString("name"),
            price = m.optInt("price"),
            cookMin = m.optInt("cookMin"),
            tags = (0 until tagsJson.length()).map { tagsJson.getString(it) }
        )
    }
    return Store(
        id = o.optString("id"),
        name = o.optString("name"),
        category = o.optString("category"),
        lat = o.optDouble("lat"),
        lng = o.optDouble("lng"),
        openHour = o.optInt("openHour"),
        closeHour = o.optInt("closeHour"),
        pickupAvailable = o.optBoolean("pickupAvailable"),
        deliveryAvailable = o.optBoolean("deliveryAvailable"),
        menus = menus
    )
}

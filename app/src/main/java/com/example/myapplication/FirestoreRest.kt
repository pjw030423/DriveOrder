package com.example.myapplication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Firestore REST API 최소 클라이언트.
 *
 * 왜 Firebase SDK가 아니라 REST인가?
 *  - Pleos(차량 OS)에는 Google Play 서비스가 없을 수 있어서, SDK 대신 HTTP로 직접 통신합니다.
 *
 * ⚠️ 지금은 Firestore '테스트 모드' 보안 규칙(인증 없음)을 전제로 합니다. 제출 전 규칙을 잠가야 합니다.
 */
object FirestoreRest {

    const val PROJECT_ID = "project-free-9191b"
    private const val BASE =
        "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"

    /** 문서 하나를 만들거나 덮어씀. 같은 id면 갱신되므로 여러 번 눌러도 중복되지 않음 */
    suspend fun putDocument(collection: String, id: String, data: JSONObject): Int =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("fields", toFields(data)).toString()
            // HttpURLConnection은 PATCH를 지원하지 않아 POST + 'X-HTTP-Method-Override: PATCH' 사용
            request("POST", "$BASE/$collection/$id", body, methodOverride = "PATCH").first
        }

    /**
     * 문서의 일부 필드만 갱신 (updateMask). 다른 필드는 그대로 둠.
     * 앱은 etaAt·sendAt만, 매장 웹은 status만 고쳐서 서로 덮어쓰지 않게 하려고 사용.
     */
    suspend fun patchFields(collection: String, id: String, data: JSONObject): Int =
        withContext(Dispatchers.IO) {
            val mask = data.keys().asSequence().joinToString("&") { "updateMask.fieldPaths=$it" }
            val body = JSONObject().put("fields", toFields(data)).toString()
            request("POST", "$BASE/$collection/$id?$mask", body, methodOverride = "PATCH").first
        }

    /** 문서 하나를 일반 JSON으로 반환. 없으면 null */
    suspend fun getDocument(collection: String, id: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/$collection/$id", null)
            if (code == 404) return@withContext null
            check(code == 200) { "HTTP $code: $text" }
            fromFields(JSONObject(text).optJSONObject("fields") ?: JSONObject())
        }

    /** 컬렉션의 문서 목록을 일반 JSON 형태로 반환 */
    suspend fun listDocuments(collection: String): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val (code, text) = request("GET", "$BASE/$collection?pageSize=100", null)
            check(code == 200) { "HTTP $code: $text" }
            val docs = JSONObject(text).optJSONArray("documents") ?: return@withContext emptyList()
            (0 until docs.length()).map { fromFields(docs.getJSONObject(it).getJSONObject("fields")) }
        }

    private fun request(
        method: String, url: String, body: String?, methodOverride: String? = null
    ): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            methodOverride?.let { conn.setRequestProperty("X-HTTP-Method-Override", it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 일반 JSON ↔ Firestore 형식 변환 ----------
    // Firestore는 값마다 자료형을 붙여야 함. 예) "name":"김치찌개" → "name":{"stringValue":"김치찌개"}

    private fun toValue(v: Any?): JSONObject = when (v) {
        null, JSONObject.NULL -> JSONObject().put("nullValue", JSONObject.NULL)
        is Boolean -> JSONObject().put("booleanValue", v)
        is Int, is Long -> JSONObject().put("integerValue", v.toString())
        is Number -> JSONObject().put("doubleValue", v.toDouble())
        is String -> JSONObject().put("stringValue", v)
        is JSONArray -> JSONObject().put(
            "arrayValue",
            JSONObject().put("values", JSONArray((0 until v.length()).map { toValue(v.get(it)) }))
        )
        is JSONObject -> JSONObject().put("mapValue", JSONObject().put("fields", toFields(v)))
        else -> JSONObject().put("stringValue", v.toString())
    }

    private fun toFields(obj: JSONObject): JSONObject {
        val fields = JSONObject()
        obj.keys().forEach { key -> fields.put(key, toValue(obj.get(key))) }
        return fields
    }

    private fun fromValue(v: JSONObject): Any = when {
        v.has("stringValue") -> v.getString("stringValue")
        v.has("integerValue") -> v.getString("integerValue").toLong()
        v.has("doubleValue") -> v.getDouble("doubleValue")
        v.has("booleanValue") -> v.getBoolean("booleanValue")
        v.has("arrayValue") -> JSONArray().also { arr ->
            val values = v.getJSONObject("arrayValue").optJSONArray("values")
            if (values != null) for (i in 0 until values.length()) arr.put(fromValue(values.getJSONObject(i)))
        }
        v.has("mapValue") -> fromFields(v.getJSONObject("mapValue").optJSONObject("fields") ?: JSONObject())
        else -> JSONObject.NULL
    }

    private fun fromFields(fields: JSONObject): JSONObject {
        val obj = JSONObject()
        fields.keys().forEach { key -> obj.put(key, fromValue(fields.getJSONObject(key))) }
        return obj
    }
}

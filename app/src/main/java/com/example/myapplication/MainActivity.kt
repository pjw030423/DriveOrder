package com.example.myapplication

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import java.util.Locale
import com.example.myapplication.ui.theme.MyApplicationTheme

/**
 * 0~2단계 테스트 화면
 *  0단계: NaviHelper 연결 → 목적지 설정 → 남은 거리/시간 확인
 *  1단계: 5초마다 자동 조회해서 남은 시간이 "줄어드는지" 확인
 *  2단계: OrderScheduler로 "주문 전송까지 남은 시간" 계산
 * Logcat에서 "DriveOrder"로 검색하면 로그를 볼 수 있습니다.
 */
class MainActivity : ComponentActivity() {

    private val TAG = "DriveOrder"
    private val POLL_MS = 5_000L            // 자동 조회 주기: 5초

    private lateinit var naviHelper: NaviHelper
    private var connected = false           // 내비 서비스 연결 여부
    private var routeActive = false         // 경로가 설정되어 있는지
    private var lastRemain: Long? = null    // 직전 남은 시간 (변화량 계산용)

    private var status by mutableStateOf("NaviHelper 연결 중...")
    private var plan by mutableStateOf("")
    private var storeText by mutableStateOf("")   // 3단계: 매장 데이터 결과 표시
    private var recText by mutableStateOf("")     // 4단계: 추천 결과 표시
    private var demoHour by mutableStateOf<Int?>(null)  // 데모용 시각 고정 (null = 현재 한국 시각)
    private var recs by mutableStateOf<List<Recommendation>>(emptyList())  // 5단계: 주문 버튼용
    private var order by mutableStateOf<Order?>(null)   // 5단계: 진행 중인 주문
    private var orderText by mutableStateOf("")
    private var waypointAdded = false                 // 6단계: 매장을 내비 경유지로 추가했는지
    @Volatile private var waypointRemainSec: Long? = null  // 6단계: 내비가 준 매장(경유지)까지 남은 시간

    // 7단계: 음성 주문
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    @Volatile private var ttsReady = false
    @Volatile private var sttReady = false
    private var listenAfterSpeak = false              // 안내를 다 읽은 뒤 바로 들을지
    private val voiceDialog = VoiceDialog { tags -> computeRecs(tags, limit = 10) }
    private var voiceStatus by mutableStateOf("")
    private var voiceLog by mutableStateOf("")

    // 추천에 필요한 값 (내비에서 받아 저장)
    private var curLat: Double? = null
    private var curLng: Double? = null
    private var destLat: Double? = null
    private var destLng: Double? = null
    private var lastRemainSec: Long? = null
    private var stores: List<Store> = emptyList()

    // 현재 위치를 아직 못 받았을 때 쓰는 기본값: 에뮬레이터 기본 위치(성남 42dot 사옥 근처, 로그로 확인)
    private val DEFAULT_LAT = 37.4127212
    private val DEFAULT_LNG = 127.094681

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.d(TAG, "경로 탐색 시작: $routeStartInfo")
            show("경로 탐색 시작\n$routeStartInfo")
        }

        override fun onRoutingCompleted() {
            Log.d(TAG, "경로 탐색 완료 → 자동 조회 시작")
            routeActive = true
            naviHelper.getDestinationInfo()
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            // remainTime의 자료형·단위를 아직 모르므로 숫자로 변환만 시도
            val raw = destinationInfo.remainTime
            val remain = raw.toString().toLongOrNull()
            val delta = if (remain != null && lastRemain != null) remain - lastRemain!! else null
            lastRemain = remain

            Log.d(TAG, "remainTime=$raw (변화 $delta), remainDistance=${destinationInfo.remainDistance}")
            show(
                "목적지: ${destinationInfo.name}\n" +
                "남은 거리: ${destinationInfo.remainDistance}\n" +
                "남은 시간(원본 값): $raw   변화: ${delta ?: "-"}"
            )

            // remainTime 단위는 '초'로 가정 (5초 간격 로그의 변화량이 약 -5인지로 확인)
            // 경로가 없으면 내비가 거리·시간을 0으로 돌려줌 → "즉시 전송"으로 오판하지 않도록 먼저 걸러냄
            val distance = destinationInfo.remainDistance.toString().toLongOrNull()
            val noRoute = (remain == null || remain <= 0L) && (distance == null || distance <= 0L)

            // 4단계 추천용으로 목적지 좌표와 남은 시간 저장
            destLat = destinationInfo.latitude.toString().toDoubleOrNull()
            destLng = destinationInfo.longitude.toString().toDoubleOrNull()
            lastRemainSec = if (noRoute) null else remain

            val newPlan = when {
                noRoute -> "⚠️ 경로 없음: 내비에 목적지를 먼저 설정하세요"
                remain == null -> "남은 시간 형식 확인 필요: $raw"
                else -> "목적지까지 약 ${remain / 60}분 · ⑤ 추천 후 주문할 수 있어요"
            }
            runOnUiThread { plan = newPlan }

            // 5단계: 주문이 있으면 새 ETA로 매장 도착·전송 시각 재계산
            // (매장이 경유지로 잡히기 전까지만 위치 비율로 추정 → 이후엔 내비의 경유지 ETA 사용)
            if (!noRoute && remain != null && waypointRemainSec == null) rescheduleOrder(estimateStoreRemain(remain))
        }

        // 6단계: 경유지 추가/삭제 결과 → 경유지 정보(남은 시간) 요청
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            Log.d(TAG, "경유지 변경: $waypointChangedInfo")
            naviHelper.getWaypointInfo()
        }

        // 6단계: 경유지 목록 수신 → 주문 매장과 같은 위치의 경유지 ETA로 전송 시각 재계산
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            val current = order ?: return
            val wp = waypointInfoList.firstOrNull {
                PickupWaypoint.matches(it.name, it.latitude, it.longitude, current.storeName, current.storeLat, current.storeLng)
            }
            Log.d(TAG, "경유지 ${waypointInfoList.size}개, 매장 경유지: ${wp?.remainTime}초 / ${wp?.remainDistance}m")
            if (wp == null) return
            waypointRemainSec = wp.remainTime.toLong()
            rescheduleOrder(wp.remainTime.toLong())
        }

        // 6단계: 매장(경유지) 도착
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            Log.d(TAG, "경유지 도착: $waypointArrivedInfo")
            runOnUiThread { plan = "🛍️ 픽업 매장에 도착했어요: ${waypointArrivedInfo.name}" }
        }

        // 4단계: 현재 위치 수신 (getCurrentLocationInfo() 호출 결과)
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            curLat = currentLocationInfo.latitude.toString().toDoubleOrNull()
            curLng = currentLocationInfo.longitude.toString().toDoubleOrNull()
            Log.d(TAG, "현재 위치: $curLat, $curLng")
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.e(TAG, "내비 오류: $errorCode")
            show("오류: $errorCode")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        naviHelper = NaviHelper(applicationContext)
        naviHelper.initialize(
            onConnected = {
                connected = true
                naviHelper.addListener(naviListener)
                show("✅ NaviHelper 연결 성공")
            },
            onFailed = { e ->
                Log.e(TAG, "연결 실패", e)
                show("❌ 연결 실패: ${e.message}")
            }
        )

        initVoice()

        // 1단계: 화면이 보이는 동안 5초마다 남은 시간 자동 조회
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (connected && routeActive) {
                        naviHelper.getDestinationInfo()
                        naviHelper.getCurrentLocationInfo()
                    }
                    if (connected && waypointAdded) naviHelper.getWaypointInfo()
                    syncOrderStatus()
                    delay(POLL_MS)
                }
            }
        }

        setContent {
            MyApplicationTheme {
                Column(Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState())) {
                    Text(status, fontSize = 22.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(plan, fontSize = 26.sp)
                    Spacer(Modifier.height(16.dp))
                    // 7단계: 음성 주문. 마이크가 안 되는 환경(에뮬레이터 등)을 위해 같은 문장을 버튼으로도 입력
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { startVoiceOrder() }) { Text("🎤 음성 주문", fontSize = 22.sp) }
                        listOf("국물 있는 거", "치킨", "네", "아니요", "취소").forEach { say ->
                            Button(onClick = { handleVoice(say) }) {
                                Text("\"$say\"")
                            }
                        }
                    }
                    if (voiceStatus.isNotEmpty()) Text(voiceStatus, fontSize = 20.sp)
                    if (voiceLog.isNotEmpty()) Text(voiceLog, fontSize = 22.sp)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        lastRemain = null
                        // 데모 목적지: 강남역 (가상 매장들이 강남역 주변에 있음. 출발은 에뮬레이터 기본 위치 성남 42dot)
                        naviHelper.requestRoute(
                            RouteInfo(
                                latitude = 37.4979,
                                longitude = 127.0276,
                                displayLongitude = null,
                                displayLatitude = null,
                                poiName = "강남역",
                                poiId = "",
                                poiSubId = "0",
                                address = "서울 강남구 강남대로 396"
                            )
                        )
                    }) { Text("① 목적지: 강남역 (데모)") }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        // 내비에 이미 설정된 경로가 있으면 그 경로로 자동 조회 시작
                        routeActive = true
                        naviHelper.getDestinationInfo()
                        naviHelper.getCurrentLocationInfo()
                    }) {
                        Text("② 현재 경로로 조회 시작")
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { uploadSeed() }) { Text("③ 매장 데이터 업로드 (최초 1회)") }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { loadStores() }) { Text("④ 매장 목록 불러오기") }
                    Spacer(Modifier.height(16.dp))
                    Text(storeText, fontSize = 20.sp)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { recommend(emptySet()) }) { Text("⑤ 추천: 아무거나") }
                        Button(onClick = { recommend(setOf("국물")) }) { Text("국물") }
                        Button(onClick = { recommend(setOf("빠름")) }) { Text("빠른 것") }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 데모 시각 전환: 현재 → 점심 12시 → 저녁 18시 → 현재 …
                    Button(onClick = {
                        demoHour = when (demoHour) { null -> 12; 12 -> 18; else -> null }
                    }) { Text("시간: ${demoHour?.let { "데모 ${it}시" } ?: "현재 시각"} (눌러서 변경)") }
                    Spacer(Modifier.height(16.dp))
                    Text(recText, fontSize = 20.sp)
                    // 5단계: 추천 결과마다 주문 버튼 (진행 중인 주문이 없을 때만)
                    if (order == null) {
                        recs.forEachIndexed { i, r ->
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { placeOrder(r) }) {
                                Text("주문 ${i + 1}: ${r.store.name} · ${r.menu.name}")
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(orderText, fontSize = 22.sp)
                    if (order != null) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            if (waypointAdded) naviHelper.removeWaypoint(WaypointIndex.FIRST)
                            waypointAdded = false
                            waypointRemainSec = null
                            order = null
                            orderText = ""
                        }) { Text("주문 화면 닫기 + 경유지 삭제 (테스트용)") }
                    }
                }
            }
        }
    }

    // 3단계: assets의 가상 매장 데이터를 Firestore에 업로드
    private fun uploadSeed() = lifecycleScope.launch {
        storeText = "업로드 중..."
        try {
            val json = assets.open("stores_seed.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val stores = JSONObject(json).getJSONArray("stores")
            var ok = 0
            for (i in 0 until stores.length()) {
                val store = stores.getJSONObject(i)
                val code = FirestoreRest.putDocument("stores", store.getString("id"), store)
                Log.d(TAG, "업로드 ${store.getString("id")} → HTTP $code")
                if (code in 200..299) ok++
            }
            storeText = "✅ 업로드 완료: $ok / ${stores.length()}개 매장"
        } catch (e: Exception) {
            Log.e(TAG, "업로드 실패", e)
            storeText = "❌ 업로드 실패: ${e.message}"
        }
    }

    // 3단계: Firestore에서 매장 목록을 다시 읽어 와 표시
    private fun loadStores() = lifecycleScope.launch {
        storeText = "불러오는 중..."
        try {
            val stores = FirestoreRest.listDocuments("stores")
            storeText = if (stores.isEmpty()) {
                "매장이 없습니다. ③ 업로드를 먼저 누르세요."
            } else {
                stores.joinToString("\n") { s ->
                    val menuCount = s.optJSONArray("menus")?.length() ?: 0
                    "• ${s.optString("name")} (${s.optString("category")}) · 메뉴 ${menuCount}개"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "불러오기 실패", e)
            storeText = "❌ 불러오기 실패: ${e.message}"
        }
    }

    // 4단계: 가는 길 + 영업 중 + 도착 전 조리 가능 + 원하는 맛 → 메뉴 추천
    private fun recommend(tags: Set<String>) = lifecycleScope.launch {
        try {
            if (stores.isEmpty()) {
                stores = FirestoreRest.listDocuments("stores").map { storeFromJson(it) }
            }
            val remain = lastRemainSec
            val recs = computeRecs(tags)
            if (remain == null || recs == null) {
                recText = "⚠️ 경로 정보가 없습니다. 내비에 목적지를 설정하고 ② 버튼을 먼저 누르세요."
                return@launch
            }
            val hour = currentHour()
            this@MainActivity.recs = recs
            val timeLabel = if (demoHour != null) "데모 ${hour}시" else "현재 ${hour}시"
            val header = "$timeLabel · 목적지까지 ${remain / 60}분 · 조건 ${if (tags.isEmpty()) "없음" else tags.joinToString()}"
            val body = if (recs.isEmpty()) {
                "조건에 맞는 메뉴가 없습니다 (영업시간·조리시간·경로 확인)"
            } else {
                recs.mapIndexed { i, r ->
                    "${i + 1}. ${r.store.name} · ${r.menu.name} ${"%,d".format(r.menu.price)}원\n" +
                    "    경로에서 ${r.detourM}m · 매장까지 약 ${r.arriveSec / 60}분 · 조리 ${r.menu.cookMin}분 (여유 ${r.slackSec / 60}분)"
                }.joinToString("\n")
            }
            val note = if (curLat == null) "\n(현재 위치 미수신: 기본 위치 42dot 기준으로 계산)" else ""
            recText = "$header\n$body$note"
        } catch (e: Exception) {
            Log.e(TAG, "추천 실패", e)
            recText = "❌ 추천 실패: ${e.message}"
        }
    }

    // 에뮬레이터 시계가 UTC일 수 있어 한국 시간으로 계산
    private fun currentHour(): Int = demoHour
        ?: Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).get(Calendar.HOUR_OF_DAY)

    /** 현재 경로·시각 기준 추천 (매장 목록은 미리 불러와 있어야 함). 경로 정보가 없으면 null */
    private fun computeRecs(tags: Set<String>, limit: Int = 3): List<Recommendation>? {
        val remain = lastRemainSec ?: return null
        val dLat = destLat ?: return null
        val dLng = destLng ?: return null
        return Recommender.recommend(
            stores, curLat ?: DEFAULT_LAT, curLng ?: DEFAULT_LNG, dLat, dLng, remain, currentHour(), tags, limit
        )
    }

    // ---------- 7단계: 음성 주문 (STT/TTS, ON_DEVICE 모드라 앱 등록 불필요) ----------

    private fun initVoice() {
        tts = TextToSpeech(applicationContext, TtsMode.ON_DEVICE).also { t ->
            t.initialize(object : TtsInitListener {
                override fun onSuccess() {
                    ttsReady = true
                    t.setLocale(Locale.KOREA)   // ko-KR (Locale.KOREAN은 "ko-"로 전달돼 엔진이 거부함)
                    t.addEventListener(ttsListener)
                    Log.d(TAG, "TTS 초기화 성공")
                }
                override fun onFailure(errorType: TtsErrorType) {
                    Log.e(TAG, "TTS 초기화 실패: $errorType")
                    runOnUiThread { voiceStatus = "TTS 사용 불가($errorType): 화면 글자로 안내" }
                }
            })
        }
        stt = SpeechToText(applicationContext, SttMode.ON_DEVICE).also { s ->
            s.initialize(object : SttInitListener {
                override fun onSuccess() {
                    sttReady = true
                    s.setLocale(Locale.KOREA)   // ko-KR (Locale.KOREAN은 "ko-"로 전달돼 엔진이 거부함)
                    s.addListener(sttListener)
                    Log.d(TAG, "STT 초기화 성공")
                }
                override fun onFailure(errorType: SttErrorType) {
                    Log.e(TAG, "STT 초기화 실패: $errorType")
                    runOnUiThread { voiceStatus = "STT 사용 불가($errorType): 아래 버튼으로 답하세요" }
                }
            })
        }
    }

    private val ttsListener = object : TtsEventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onStop() = Unit
        override fun onDone() { runOnUiThread { if (listenAfterSpeak) startListening() } }
        override fun onError(errorType: TtsErrorType) {
            Log.e(TAG, "TTS 오류: $errorType")
            runOnUiThread { if (listenAfterSpeak) startListening() }   // 못 읽어도 대화는 계속
        }
    }

    private val sttListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() { runOnUiThread { voiceStatus = "🎤 듣는 중..." } }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() { runOnUiThread { if (voiceStatus.startsWith("🎤")) voiceStatus = "" } }
        override fun onUpdated(stt: String, completed: Boolean) {
            runOnUiThread {
                voiceStatus = "🎤 $stt"
                if (completed && stt.isNotBlank()) handleVoice(stt)
            }
        }
        override fun onError(errorType: SttErrorType) {
            Log.e(TAG, "STT 오류: $errorType")
            runOnUiThread { voiceStatus = "음성 인식 오류($errorType): 아래 버튼으로 답하세요" }
        }
    }

    /** 🎤 버튼: 매장 목록을 준비하고 대화 시작 */
    private fun startVoiceOrder() = lifecycleScope.launch {
        try {
            if (stores.isEmpty()) stores = FirestoreRest.listDocuments("stores").map { storeFromJson(it) }
        } catch (e: Exception) {
            Log.e(TAG, "매장 불러오기 실패", e)
        }
        voiceLog = ""
        speakReply(voiceDialog.start())
    }

    /** STT 최종 결과 또는 대체 버튼 입력 */
    private fun handleVoice(text: String) {
        if (!voiceDialog.isActive) return
        stt?.takeIf { sttReady }?.stop()
        appendVoiceLog("🙂 $text")
        speakReply(voiceDialog.handle(text))
    }

    private fun speakReply(reply: VoiceDialog.Reply) {
        appendVoiceLog("🔊 ${reply.say}")
        Log.d(TAG, "음성 안내: ${reply.say} (듣기=${reply.listen})")
        reply.order?.let { placeOrder(it) }
        listenAfterSpeak = reply.listen
        if (ttsReady) tts?.speak(reply.say, true) else if (reply.listen) startListening()
    }

    private fun startListening() {
        listenAfterSpeak = false
        if (sttReady) {
            voiceStatus = "🎤 말씀하세요"
            stt?.request()
        } else {
            voiceStatus = "음성 인식 준비 안 됨: 아래 버튼으로 답하세요"
        }
    }

    private fun appendVoiceLog(line: String) {
        voiceLog = (voiceLog.lines().filter { it.isNotBlank() } + line).takeLast(6).joinToString("\n")
    }

    // 5단계: 추천 메뉴로 주문 생성 → Firestore "orders"에 저장 (매장 웹은 sendAt이 지나면 표시)
    private fun placeOrder(rec: Recommendation) = lifecycleScope.launch {
        val newOrder = Order.create(rec, System.currentTimeMillis())
        orderText = "주문 저장 중..."
        try {
            val code = FirestoreRest.putDocument("orders", newOrder.id, newOrder.toJson())
            check(code in 200..299) { "HTTP $code" }
            Log.d(TAG, "주문 생성 ${newOrder.id}: 도착 ${rec.arriveSec}초 뒤, 조리 ${rec.menu.cookMin}분")
            order = newOrder
            waypointRemainSec = null
            addStoreWaypoint(newOrder)
            renderOrder()
        } catch (e: Exception) {
            Log.e(TAG, "주문 실패", e)
            orderText = "❌ 주문 실패: ${e.message}"
        }
    }

    // 6단계: 주문 매장을 내비 경유지로 추가 → 이후 내비가 매장까지 남은 시간을 직접 알려 줌
    private fun addStoreWaypoint(o: Order) {
        naviHelper.addWaypoint(
            RequestWaypointInfo(
                latitude = o.storeLat,
                longitude = o.storeLng,
                poiName = o.storeName,
                waypointIndex = WaypointIndex.FIRST,
                poiId = "",          // 가상 매장이라 내비 POI ID 없음
                address = "",
                poiSubId = "0"
            )
        )
        waypointAdded = true
        Log.d(TAG, "경유지 추가 요청: ${o.storeName} (${o.storeLat}, ${o.storeLng})")
    }

    // 경유지 ETA를 받기 전: 목적지 ETA × 매장의 경로상 위치 비율로 매장까지 남은 시간 추정
    private fun estimateStoreRemain(destRemainSec: Long): Long? {
        val current = order ?: return null
        val dLat = destLat ?: return null
        val dLng = destLng ?: return null
        val (_, t) = Recommender.distanceToSegment(
            current.storeLat, current.storeLng, curLat ?: DEFAULT_LAT, curLng ?: DEFAULT_LNG, dLat, dLng
        )
        return OrderScheduler.storeRemainSec(destRemainSec, t)
    }

    // 5단계: 주행 중 매장까지 남은 시간으로 도착·전송 시각을 다시 계산해 서버에 반영 (정체 시 전송 연기)
    private fun rescheduleOrder(storeRemainSec: Long?) {
        if (storeRemainSec == null) return
        val current = order ?: return
        val updated = OrderScheduler.reschedule(current, System.currentTimeMillis(), storeRemainSec) ?: return
        lifecycleScope.launch {
            try {
                FirestoreRest.patchFields(
                    "orders", updated.id,
                    JSONObject().put("etaAt", updated.etaAt).put("sendAt", updated.sendAt)
                )
                Log.d(TAG, "주문 재계산: etaAt=${updated.etaAt}, sendAt=${updated.sendAt}")
                if (order?.id == updated.id) order = updated.copy(status = order!!.status)
            } catch (e: Exception) {
                Log.e(TAG, "재계산 반영 실패", e)
            }
        }
    }

    // 5단계: 매장 웹이 바꾼 상태(조리 중·완료)를 읽어 오고, 전송 시각이 되면 'sent'로 표시
    private suspend fun syncOrderStatus() {
        val current = order ?: return
        try {
            val now = System.currentTimeMillis()
            var status = FirestoreRest.getDocument("orders", current.id)
                ?.optString("status", current.status) ?: current.status
            if (status == Order.STATUS_SCHEDULED && now >= current.sendAt) {
                FirestoreRest.patchFields("orders", current.id, JSONObject().put("status", Order.STATUS_SENT))
                status = Order.STATUS_SENT
                Log.d(TAG, "🔔 매장에 주문 전송: ${current.id}")
            }
            if (order?.id == current.id) order = order!!.copy(status = status)
        } catch (e: Exception) {
            Log.e(TAG, "주문 상태 조회 실패", e)
        }
        renderOrder()
    }

    private fun renderOrder() {
        val o = order ?: return
        val now = System.currentTimeMillis()
        val sendIn = ((o.sendAt - now) / 1000).coerceAtLeast(0)
        val etaIn = ((o.etaAt - now) / 1000).coerceAtLeast(0)
        val sendLine = if (o.status == Order.STATUS_SCHEDULED && sendIn > 0)
            "매장 전송까지 ${sendIn / 60}분 ${sendIn % 60}초" else "매장에 전송됨"
        orderText = "🧾 ${o.storeName} · ${o.menuName} ${"%,d".format(o.price)}원\n" +
            "상태: ${Order.statusLabel(o.status)}\n" +
            "$sendLine · 매장 도착 약 ${etaIn / 60}분 ${etaIn % 60}초 뒤 (조리 ${o.cookMin}분)"
    }

    // SDK 콜백은 다른 스레드에서 올 수 있으므로 화면 갱신은 UI 스레드에서
    private fun show(msg: String) = runOnUiThread { status = msg }

    override fun onDestroy() {
        naviHelper.removeListener(naviListener)
        naviHelper.release()
        stt?.let { it.removeListener(sttListener); it.release() }
        tts?.let { it.removeEventListener(ttsListener); it.release() }
        super.onDestroy()
    }
}

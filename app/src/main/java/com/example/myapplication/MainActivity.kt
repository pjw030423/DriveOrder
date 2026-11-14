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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
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
    private val LISTEN_TIMEOUT_MS = 8_000L  // 이만큼 아무 말이 없으면 듣기 종료
    private val ROUTE_TIMEOUT_MS = 20_000L  // 경로 조회 후 이만큼 경로가 시작되지 않으면 주문 접음

    private lateinit var naviHelper: NaviHelper
    private var connected = false           // 내비 서비스 연결 여부
    private var routeActive = false         // 경로가 설정되어 있는지
    private var lastRemain: Long? = null    // 직전 남은 시간 (변화량 계산용)

    private var status by mutableStateOf("NaviHelper 연결 중...")
    private var plan by mutableStateOf("")
    private var orders by mutableStateOf<List<Order>>(emptyList())   // 5단계: 진행 중인 주문들 (픽업 여러 곳 + 배달)
    private var orderText by mutableStateOf("")
    private var waypointAdded = false                 // 6단계: 매장을 내비 경유지로 추가했는지
    private var storeIsDestination = false            // 내 목적지 없이 매장 자체로 길 안내 중인지 (주문을 닫으면 안내도 끝냄)
    private var ownDest: Place? = null                // 픽업 경유지를 넣기 전 운전자의 원래 목적지 (경로를 다시 짤 때 유지)
    private val wpEtaOrders = mutableSetOf<String>()  // 6단계: 내비가 경유지 ETA를 준 주문 (그 전엔 위치 비율로 추정)

    // 7단계: 음성 주문
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    @Volatile private var ttsReady = false
    @Volatile private var sttReady = false
    private var listenAfterSpeak = false              // 안내를 다 읽은 뒤 바로 들을지
    private var listenTimeout: Job? = null            // 듣는 중 무응답 타이머 (STT는 무음이면 끝나지 않음)
    private val voiceDialog = VoiceDialog(
        recommend = { tags, delivery, place -> computeRecs(tags, limit = 100, delivery = delivery, place = place) },
        hasDestination = { userRoute() },
        pickupSlots = { PickupRoute.slotsLeft(pickupStores().size, ownDest != null || userRoute()) },
        pickupsPending = { pickupStores().isNotEmpty() },
        destinationName = { if (userRoute()) destName else "" },
        // 질문에서 앞의 3곳만 읽음: 집·회사 → 데모 장소(강남역) → 최근 목적지
        // 최근 목적지 중 가상 매장(픽업하러 갔던 곳)은 배달 장소에서 뺌
        places = {
            (navPlaces.filter { it.aliases.isNotEmpty() } + DEMO_PLACE + navPlaces)
                .filter { p -> stores.none { it.name == p.name } }
                .distinctBy { it.name }
        }
    )
    @Volatile private var navPlaces: List<Place> = emptyList()   // 내비 즐겨찾기 + 최근 목적지 (배달 장소 후보)
    // 경로가 없을 때 고른 주문: 경로를 조회해 내비 ETA를 받은 뒤 주문 (pendingTo = 배달 받을 곳 이름)
    @Volatile private var pendingRec: Recommendation? = null
    private var pendingTo: String = ""

    // 데모용 가상 주행 (DemoDrive.kt 참고)
    private val demoDrive = DemoDrive()
    private var demoOn by mutableStateOf(false)
    private var voiceStatus by mutableStateOf("")
    private var voiceLog by mutableStateOf("")

    // 추천에 필요한 값 (내비에서 받아 저장)
    private var curLat: Double? = null
    private var curLng: Double? = null
    private var destLat: Double? = null
    private var destLng: Double? = null
    private var destName: String = ""           // 배달 받을 곳으로 매장에 보냄
    private var lastRemainSec: Long? = null
    private var stores: List<Store> = emptyList()

    // 현재 위치를 아직 못 받았을 때 쓰는 기본값: 에뮬레이터 기본 위치(성남 42dot 사옥 근처, 로그로 확인)
    private val DEFAULT_LAT = 37.4127212
    private val DEFAULT_LNG = 127.094681

    // 데모 장소: 가상 매장들이 모여 있는 강남역. 내비 즐겨찾기가 비어 있어도 배달 장소로 고를 수 있게 함
    private val DEMO_PLACE = Place("강남역", 37.4979, 127.0276)

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.d(TAG, "경로 탐색 시작: $routeStartInfo")
            show("🧭 길 안내 시작: ${routeStartInfo.name}")
            // onRoutingCompleted가 오지 않는 경우가 있어(2026-11-13 에뮬레이터 확인) 안내가 시작되면 바로 조회 시작
            routeActive = true
            naviHelper.getDestinationInfo()
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

            // remainTime 단위는 '초'로 가정 (5초 간격 로그의 변화량이 약 -5인지로 확인)
            // 경로가 없으면 내비가 거리·시간을 0으로 돌려줌 → "즉시 전송"으로 오판하지 않도록 먼저 걸러냄
            val distance = destinationInfo.remainDistance.toString().toLongOrNull()
            val noRoute = (remain == null || remain <= 0L) && (distance == null || distance <= 0L)

            // 4단계 추천용으로 목적지 좌표와 남은 시간 저장
            destLat = destinationInfo.latitude.toString().toDoubleOrNull()
            destLng = destinationInfo.longitude.toString().toDoubleOrNull()
            destName = destinationInfo.name.toString()
            lastRemainSec = if (noRoute) null else remain
            show(if (noRoute || distance == null) "📍 목적지 없음" else "📍 목적지: $destName · ${"%.1f".format(distance / 1000.0)}km")
            if (!noRoute) routeActive = true   // 내비에 이미 경로가 있으면 자동 조회 시작

            val newPlan = when {
                noRoute -> "🎤 음성 주문으로 근처 매장 픽업이나 원하는 곳 배달을 주문할 수 있어요"
                remain == null -> "남은 시간 형식 확인 필요: $raw"
                else -> "목적지까지 약 ${remain / 60}분 · 🎤 음성 주문으로 가는 길에 주문할 수 있어요"
            }
            runOnUiThread { plan = newPlan }

            // 5단계: 주문이 있으면 새 ETA로 도착·전송 시각 재계산
            //  - 배달: 목적지 도착 시각이 기준 → 목적지 ETA를 그대로 사용
            //  - 픽업: 매장이 경유지로 잡히기 전까지만 위치 비율로 추정 → 이후엔 내비의 경유지 ETA 사용
            if (!noRoute && remain != null) {
                // 경로가 없을 때 고른 주문: 방금 조회한 경로의 ETA로 이제 주문
                pendingRec?.let { rec ->
                    pendingRec = null
                    Log.d(TAG, "경로 조회 완료 → 대기 주문 진행 (ETA ${remain}초)")
                    placeOrder(rec.copy(arriveSec = remain), deliveryTo = pendingTo, viaWaypoint = false)
                    return
                }
                for (o in orders) when {
                    o.isDelivery -> if (!storeIsDestination) rescheduleOrder(o, remain)
                    // 매장이 내비 목적지면 목적지 ETA가 곧 매장 ETA
                    storeIsDestination && PickupWaypoint.matches(destName, destLat ?: 0.0, destLng ?: 0.0, o.storeName, o.storeLat, o.storeLng) ->
                        rescheduleOrder(o, remain)
                    o.id !in wpEtaOrders -> rescheduleOrder(o, estimateStoreRemain(o, remain))
                }
            }
        }

        // 배달 장소 후보: 즐겨찾기(집·회사 등)
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            // 설정 안 된 즐겨찾기는 이름이 비고 좌표가 0으로 옴 (에뮬레이터 확인) → 제외
            val list = bookmarkInfoList.filter { it.latitude != 0.0 || it.longitude != 0.0 }.map { b ->
                val alias = when (b.category) {
                    BookmarkType.HOME -> listOf("집")
                    BookmarkType.OFFICE -> listOf("회사", "사무실", "직장")
                    else -> emptyList()
                }
                Place(b.name.ifBlank { alias.firstOrNull() ?: "즐겨찾기" }, b.latitude, b.longitude, alias)
            }
            Log.d(TAG, "즐겨찾기 ${list.size}곳: ${list.joinToString { it.name }}")
            navPlaces = (list + navPlaces.filter { it.aliases.isEmpty() }).distinctBy { it.name }
        }

        // 배달 장소 후보: 최근 목적지
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
            val list = recentDestinationInfoList
                .filter { it.name.isNotBlank() && (it.latitude != 0.0 || it.longitude != 0.0) }
                .map { Place(it.name, it.latitude, it.longitude) }
            Log.d(TAG, "최근 목적지 ${list.size}곳: ${list.joinToString { it.name }}")
            navPlaces = (navPlaces.filter { it.aliases.isNotEmpty() } + list).distinctBy { it.name }
        }

        // 6단계: 경유지 추가/삭제 결과 → 경유지 정보(남은 시간) 요청
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            Log.d(TAG, "경유지 변경: $waypointChangedInfo")
            naviHelper.getWaypointInfo()
        }

        // 6단계: 경유지 목록 수신 → 주문 매장과 같은 위치의 경유지 ETA로 전송 시각 재계산
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            Log.d(TAG, "경유지 ${waypointInfoList.size}개: ${waypointInfoList.joinToString { "${it.name} ${it.remainTime}초" }}")
            for (o in orders.filter { !it.isDelivery }) {   // 배달은 경유지 없음
                val wp = waypointInfoList.firstOrNull {
                    PickupWaypoint.matches(it.name, it.latitude, it.longitude, o.storeName, o.storeLat, o.storeLng)
                } ?: continue
                // 처음 받은 경유지 ETA부터 데모 주행을 다시 시작 (그 전 값은 위치 비율로 추정한 값이라 부정확)
                if (wpEtaOrders.isEmpty()) demoDrive.reset()
                wpEtaOrders += o.id
                rescheduleOrder(o, wp.remainTime.toLong())
            }
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
            if (pendingRec != null) {   // 경로를 못 찾으면 대기 주문도 접음
                pendingRec = null
                OrderTrackingService.stop(this@MainActivity)
                runOnUiThread { orderText = "❌ 경로를 찾지 못해 주문하지 않았어요 ($errorCode)" }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        naviHelper = NaviHelper(applicationContext)
        naviHelper.initialize(
            onConnected = {
                connected = true
                naviHelper.addListener(naviListener)
                loadNavPlaces()
                show("✅ NaviHelper 연결 성공")
            },
            onFailed = { e ->
                Log.e(TAG, "연결 실패", e)
                show("❌ 연결 실패: ${e.message}")
            }
        )

        initVoice()

        // 5초마다 남은 시간·경유지 ETA·주문 상태 조회.
        // 화면이 가려져도 계속 돌아야 함: 경유지를 추가하면 내비가 앞으로 나와 이 앱은 백그라운드가 되는데,
        // 예전처럼 STARTED 동안만 돌면 주행 중 재계산·상태 동기화가 멈춤 (2026-11-08 에뮬레이터에서 확인).
        // 앱(Activity)이 종료될 때 lifecycleScope와 함께 멈춤.
        lifecycleScope.launch {
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

        setContent {
            MyApplicationTheme {
                Column(Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState())) {
                    Text(status, fontSize = 22.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(plan, fontSize = 26.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { startVoiceOrder() }) { Text("🎤 음성 주문", fontSize = 26.sp) }
                    if (voiceStatus.isNotEmpty()) Text(voiceStatus, fontSize = 20.sp)
                    if (voiceLog.isNotEmpty()) Text(voiceLog, fontSize = 22.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(orderText, fontSize = 22.sp)
                    if (orders.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { clearOrder("") }) { Text("주문 화면 닫기") }
                    }
                    Spacer(Modifier.height(40.dp))
                    // 마이크가 없는 환경(에뮬레이터 등)을 위해 같은 대답을 버튼으로도 입력
                    Text("마이크가 없을 때 대답 입력", fontSize = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("국물 있는 거", "치킨", "픽업", "배달", "강남역", "네", "아니요", "취소").forEach { say ->
                            OutlinedButton(onClick = { handleVoice(say) }) { Text("\"$say\"") }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("시연용", fontSize = 16.sp)
                    // 데모 목적지: 강남역 (가상 매장들이 강남역 주변에 있음. 출발은 에뮬레이터 기본 위치 성남 42dot)
                    // 데모용 가상 주행: 에뮬레이터는 차가 움직이지 않아 남은 시간이 줄지 않음
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            routeTo(DEMO_PLACE.name, DEMO_PLACE.lat, DEMO_PLACE.lng, "서울 강남구 강남대로 396")
                        }) { Text("목적지: 강남역") }
                        OutlinedButton(onClick = { demoDrive.toggle(); demoOn = demoDrive.enabled }) {
                            Text(if (demoOn) "데모 주행: 켜짐 (가상)" else "데모 주행: 꺼짐")
                        }
                        if (demoOn) OutlinedButton(onClick = { demoDrive.addDelay(180); Log.d(TAG, "데모: 정체 +3분") }) {
                            Text("정체 +3분")
                        }
                    }
                }
            }
        }
    }

    // 에뮬레이터 시계가 UTC일 수 있어 한국 시간으로 계산
    private fun currentHour(): Int =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).get(Calendar.HOUR_OF_DAY)

    /** 내비에 경로(목적지 + 남은 시간)가 있는지 */
    private fun hasRoute() = lastRemainSec != null && destLat != null && destLng != null

    /** 운전자가 직접 정한 목적지로 가는 중인지 (픽업 매장으로 가는 안내는 제외) */
    private fun userRoute() = hasRoute() && !storeIsDestination

    /** 들러야 할 픽업 매장 (같은 매장 주문은 한 곳으로) */
    private fun pickupStores() = orders.filter { !it.isDelivery && it.status != Order.STATUS_PICKED_UP }.distinctBy { it.storeId }

    /**
     * 추천 (매장 목록은 미리 불러와 있어야 함). 계산할 수 없으면 null
     *  - 픽업: 경로가 있으면 가는 길, 없으면 현재 위치 근처
     *  - 배달: place가 있으면 그곳(남은 시간은 직선거리로 추정), 없으면 내비 목적지
     */
    private fun computeRecs(
        tags: Set<String>, limit: Int = 3, delivery: Boolean = false, place: Place? = null
    ): List<Recommendation>? {
        val cLat = curLat ?: DEFAULT_LAT
        val cLng = curLng ?: DEFAULT_LNG
        val hour = currentHour()
        if (delivery && place != null) {
            val remain = Recommender.driveSecEstimate(Recommender.distanceM(cLat, cLng, place.lat, place.lng))
            return Recommender.recommendDelivery(stores, place.lat, place.lng, remain, hour, tags, limit)
        }
        val remain = lastRemainSec
        val dLat = destLat
        val dLng = destLng
        // 매장으로 가는 안내 중이면 "가는 길"이 아니라 근처에서 찾음 (가까운 곳부터 들르도록 경로를 다시 짬)
        if (remain == null || dLat == null || dLng == null || storeIsDestination) {
            return if (delivery) null else Recommender.recommendNearby(stores, cLat, cLng, hour, tags, limit)
        }
        if (delivery) return Recommender.recommendDelivery(stores, dLat, dLng, remain, hour, tags, limit)
        return Recommender.recommend(stores, cLat, cLng, dLat, dLng, remain, hour, tags, limit)
    }

    /** 내비에 경로 요청 (결과는 onRoutingCompleted → onDestinationInfo로 옴) */
    private fun routeTo(name: String, lat: Double, lng: Double, address: String = "") {
        lastRemain = null
        naviHelper.requestRoute(
            RouteInfo(
                latitude = lat, longitude = lng,
                displayLongitude = null, displayLatitude = null,
                poiName = name, poiId = "", poiSubId = "0", address = address
            )
        )
        Log.d(TAG, "경로 요청: $name ($lat, $lng)")
    }

    /** 배달 장소 후보(즐겨찾기·최근 목적지) 요청. 결과는 onBookmarkInfo·onRecentDestinationInfo로 옴 */
    private fun loadNavPlaces() {
        if (!connected) return
        naviHelper.getDestinationInfo()      // 경로가 있는지 (있으면 픽업은 가는 길, 배달은 그 목적지)
        naviHelper.getCurrentLocationInfo()
        naviHelper.getBookmarkInfo()
        naviHelper.getRecentDestinationInfo()
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
                else if (stt.isNotBlank()) armListenTimeout()   // 말하는 중이면 타이머 연장
            }
        }
        override fun onError(errorType: SttErrorType) {
            Log.e(TAG, "STT 오류: $errorType")
            runOnUiThread {
                listenTimeout?.cancel()
                voiceStatus = "음성 인식 오류($errorType): 아래 버튼으로 답하세요"
            }
        }
    }

    /** 🎤 버튼: 매장 목록을 준비하고 대화 시작 */
    private fun startVoiceOrder() = lifecycleScope.launch {
        try {
            ensureStores()
        } catch (e: Exception) {
            Log.e(TAG, "매장 불러오기 실패", e)
        }
        loadNavPlaces()   // 즐겨찾기·최근 목적지를 최신으로 (배달 장소 질문용)
        listenTimeout?.cancel()
        voiceLog = ""
        speakReply(voiceDialog.start())
    }

    /** STT 최종 결과 또는 대체 버튼 입력 */
    private fun handleVoice(text: String) {
        if (!voiceDialog.isActive) return
        listenTimeout?.cancel()
        stt?.takeIf { sttReady }?.stop()
        voiceStatus = ""
        appendVoiceLog("🙂 $text")
        speakReply(voiceDialog.handle(text))
    }

    private fun speakReply(reply: VoiceDialog.Reply) {
        appendVoiceLog("🔊 ${reply.say}")
        Log.d(TAG, "음성 안내: ${reply.say} (듣기=${reply.listen})")
        reply.order?.let { startOrder(it, reply.place) }
        listenAfterSpeak = reply.listen
        if (ttsReady) tts?.speak(reply.say, true) else if (reply.listen) startListening()
    }

    private fun startListening() {
        listenAfterSpeak = false
        if (sttReady) {
            voiceStatus = "🎤 말씀하세요"
            stt?.request()
            armListenTimeout()
        } else {
            voiceStatus = "음성 인식 준비 안 됨: 아래 버튼으로 답하세요"
        }
    }

    /** 무응답 타이머를 (다시) 시작. 시간이 지나면 듣기를 멈추고 대화에 "말 없음"을 알림 */
    private fun armListenTimeout() {
        listenTimeout?.cancel()
        listenTimeout = lifecycleScope.launch {
            delay(LISTEN_TIMEOUT_MS)
            stt?.takeIf { sttReady }?.stop()
            voiceStatus = ""                     // "🎤 말씀하세요"가 남지 않게 (다시 들으면 새로 표시)
            val reply = voiceDialog.timeout() ?: return@launch
            Log.d(TAG, "음성 무응답 ${LISTEN_TIMEOUT_MS / 1000}초 → 듣기 종료")
            appendVoiceLog("⏱ (응답 없음)")
            speakReply(reply)
        }
    }

    private fun appendVoiceLog(line: String) {
        voiceLog = (voiceLog.lines().filter { it.isNotBlank() } + line).takeLast(6).joinToString("\n")
    }

    /**
     * 음성으로 고른 메뉴 주문.
     *  - 경로가 있고 배달 장소를 따로 고르지 않았으면 바로 주문 (픽업은 매장을 경유지로 추가)
     *  - 경로가 없으면 먼저 경로 조회 (픽업 → 매장, 배달 → 고른 장소). 내비 ETA가 오면 onDestinationInfo에서 주문
     */
    private fun startOrder(rec: Recommendation, place: Place?) {
        // 이미 들를 매장이 있으면 경로를 새로 찾지 않고 그 경로에 추가 (가까운 곳부터 다시 짬)
        if (pickupStores().isNotEmpty() && (!rec.delivery || place != null)) {
            placeOrder(rec, deliveryTo = place?.name ?: destName); return
        }
        if (userRoute() && place == null) { placeOrder(rec); return }
        val (name, lat, lng) = if (rec.delivery) {
            val p = place ?: return   // 배달인데 장소도 경로도 없음 (대화에서 막힘)
            Triple(p.name, p.lat, p.lng)
        } else Triple(rec.store.name, rec.store.lat, rec.store.lng)
        pendingRec = rec
        pendingTo = if (rec.delivery) name else ""
        orderText = "🧭 $name 경로를 찾는 중… 도착 시간이 나오면 주문할게요"
        // 경로를 요청하면 내비가 앞으로 나와 이 앱이 백그라운드가 됨 → 얼지 않도록 서비스를 먼저 켬
        OrderTrackingService.start(this, "$name 경로를 찾은 뒤 주문을 전달할게요")
        routeTo(name, lat, lng)
        // 에뮬레이터를 막 켜서 내비가 준비되지 않으면 경로 요청이 조용히 무시됨 → 기다리지 않고 접음
        lifecycleScope.launch {
            delay(ROUTE_TIMEOUT_MS)
            if (pendingRec === rec) {
                pendingRec = null
                OrderTrackingService.stop(this@MainActivity)
                orderText = "❌ 내비가 경로를 시작하지 않아 주문하지 않았어요. 내비 화면을 한 번 연 뒤 다시 주문해 주세요."
                if (ttsReady) tts?.speak("내비가 경로를 시작하지 않아 주문하지 않았어요.", true)
            }
        }
    }

    // 5단계: 추천 메뉴로 주문 생성 → Firestore "orders"에 저장 (매장 웹은 sendAt이 지나면 표시)
    // viaWaypoint=false: 매장 자체가 내비 목적지인 픽업 (경로가 없을 때 매장으로 안내한 경우)
    private fun placeOrder(rec: Recommendation, deliveryTo: String = destName, viaWaypoint: Boolean = true) = lifecycleScope.launch {
        val newOrder = Order.create(rec, System.currentTimeMillis(), deliveryTo = deliveryTo)
        orderText = "주문 저장 중..."
        try {
            val code = FirestoreRest.putDocument("orders", newOrder.id, newOrder.toJson())
            check(code in 200..299) { "HTTP $code" }
            Log.d(TAG, "주문 생성 ${newOrder.id} (${newOrder.type}): 도착 ${rec.arriveSec}초 뒤, 조리 ${rec.menu.cookMin}분, 배달 ${rec.deliveryMin}분")
            if (orders.isEmpty()) demoDrive.reset()
            // 경로를 바꾸기 전에 운전자의 원래 목적지를 기억 (매장 경유지를 넣고 다시 짜도 목적지는 유지)
            if (ownDest == null && viaWaypoint && userRoute()) ownDest = Place(destName, destLat!!, destLng!!)
            orders = orders + newOrder
            if (!newOrder.isDelivery) {   // 배달은 경로를 바꾸지 않음
                if (viaWaypoint) rebuildRoute() else storeIsDestination = true
            }
            val what = if (orders.size > 1) "주문 ${orders.size}건 진행 중"
                else if (newOrder.isDelivery) "목적지 도착에 맞춰 배달 요청 중" else "도착 시간에 맞춰 주문 전달 중"
            OrderTrackingService.start(this@MainActivity, "${newOrder.storeName} · ${newOrder.menuName}: $what")
            renderOrder()
        } catch (e: Exception) {
            Log.e(TAG, "주문 실패", e)
            orderText = "❌ 주문 실패: ${e.message}"
        }
    }

    /**
     * 6단계: 픽업 매장들을 가까운 곳부터 들르도록 경로를 다시 요청 → 이후 내비가 매장마다 남은 시간을 알려 줌.
     *  - 내 목적지가 있으면: 목적지는 그대로, 매장들은 경유지 (최대 2곳)
     *  - 없으면: 가장 마지막에 들를 매장이 목적지, 나머지는 경유지
     */
    private fun rebuildRoute() {
        val stops = PickupRoute.nearestFirst(curLat ?: DEFAULT_LAT, curLng ?: DEFAULT_LNG, pickupStores()) { it.storeLat to it.storeLng }
        if (stops.isEmpty()) return
        val own = ownDest
        val dest = if (own != null) routeInfo(own.name, own.lat, own.lng)
            else stops.last().let { routeInfo(it.storeName, it.storeLat, it.storeLng) }
        val via = (if (own != null) stops else stops.dropLast(1)).take(PickupRoute.MAX_WAYPOINTS)
        val indexes = listOf(WaypointIndex.FIRST, WaypointIndex.SECOND)
        val waypoints = via.mapIndexed { i, o ->
            RequestWaypointInfo(
                latitude = o.storeLat,
                longitude = o.storeLng,
                poiName = o.storeName,
                waypointIndex = indexes[i],
                poiId = "",          // 가상 매장이라 내비 POI ID 없음
                address = "",
                poiSubId = "0"
            )
        }
        lastRemain = null
        wpEtaOrders.clear()   // 순서가 바뀌면 경유지 ETA도 바뀜
        naviHelper.requestRouteWithWaypoints(RouteWithWaypointsRequest(dest, waypoints))
        waypointAdded = waypoints.isNotEmpty()
        storeIsDestination = own == null
        Log.d(TAG, "경로 다시 요청: ${(via.map { it.storeName } + dest.poiName).joinToString(" → ")}")
    }

    private fun routeInfo(name: String, lat: Double, lng: Double) = RouteInfo(
        latitude = lat, longitude = lng,
        displayLongitude = null, displayLatitude = null,
        poiName = name, poiId = "", poiSubId = "0", address = ""
    )

    // 경유지 ETA를 받기 전: 목적지 ETA × 매장의 경로상 위치 비율로 매장까지 남은 시간 추정
    private fun estimateStoreRemain(current: Order, destRemainSec: Long): Long? {
        val dLat = destLat ?: return null
        val dLng = destLng ?: return null
        val (_, t) = Recommender.distanceToSegment(
            current.storeLat, current.storeLng, curLat ?: DEFAULT_LAT, curLng ?: DEFAULT_LNG, dLat, dLng
        )
        return OrderScheduler.storeRemainSec(destRemainSec, t)
    }

    // 5단계: 주행 중 매장까지 남은 시간으로 도착·전송 시각을 다시 계산해 서버에 반영 (정체 시 전송 연기)
    private fun rescheduleOrder(current: Order, storeRemainSec: Long?) {
        if (storeRemainSec == null) return
        val now = System.currentTimeMillis()
        val remain = demoDrive.remain(storeRemainSec, now)   // 데모 주행이 켜져 있으면 가상 주행 기준
        val updated = OrderScheduler.reschedule(current, now, remain) ?: return
        lifecycleScope.launch {
            try {
                FirestoreRest.patchFields(
                    "orders", updated.id,
                    JSONObject().put("etaAt", updated.etaAt).put("sendAt", updated.sendAt)
                )
                Log.d(TAG, "주문 재계산: etaAt=${updated.etaAt}, sendAt=${updated.sendAt}")
                orders = orders.map { if (it.id == updated.id) updated.copy(status = it.status) else it }
            } catch (e: Exception) {
                Log.e(TAG, "재계산 반영 실패", e)
            }
        }
    }

    // 5단계: 매장 웹이 바꾼 상태(조리 중·완료)를 읽어 오고, 전송 시각이 되면 'sent'로 표시
    private suspend fun syncOrderStatus() {
        for (current in orders) syncOne(current)
        if (orders.isNotEmpty() && orders.all { it.status == Order.STATUS_PICKED_UP }) OrderTrackingService.stop(this)   // 모두 완료 → 서비스 종료
        renderOrder()
    }

    private suspend fun syncOne(current: Order) {
        // 픽업·배달 완료 후에는 더 바뀔 상태가 없음 → 5초마다 서버 조회하지 않음
        if (current.status == Order.STATUS_PICKED_UP) return
        try {
            val now = System.currentTimeMillis()
            val doc = FirestoreRest.getDocument("orders", current.id)
            if (doc == null) {
                // 서버에서 주문이 삭제됨(매장 취소·테스트 정리 등) → 차에서도 그 주문을 내려놓음
                Log.d(TAG, "주문이 서버에서 삭제됨: ${current.id}")
                removeOrder(current)
                return
            }
            var status = doc.optString("status", current.status)
            if (status == Order.STATUS_SCHEDULED && now >= current.sendAt) {
                FirestoreRest.patchFields("orders", current.id, JSONObject().put("status", Order.STATUS_SENT))
                status = Order.STATUS_SENT
                Log.d(TAG, "🔔 매장에 주문 전송: ${current.id}")
            }
            if (status != current.status) Log.d(TAG, "주문 상태 변경: ${current.status} → $status")
            orders = orders.map { if (it.id == current.id) it.copy(status = status) else it }
        } catch (e: Exception) {
            Log.e(TAG, "주문 상태 조회 실패", e)
        }
    }

    /** 주문 1건만 내려놓음: 남은 픽업 매장으로 경로를 다시 짬 (남은 게 없으면 전부 정리) */
    private fun removeOrder(o: Order) {
        if (orders.size <= 1) { clearOrder("주문이 취소되었어요 (매장에서 삭제됨)"); return }
        orders = orders.filter { it.id != o.id }
        if (!o.isDelivery) {
            if (pickupStores().isNotEmpty()) rebuildRoute()
            else if (waypointAdded) { naviHelper.removeWaypoint(WaypointIndex.ALL); waypointAdded = false }
        }
    }

    /** 진행 중인 주문을 내려놓음: 경유지 삭제, 서비스 종료, 안내 문구 표시 */
    private fun clearOrder(message: String) {
        if (waypointAdded && !storeIsDestination) naviHelper.removeWaypoint(WaypointIndex.ALL)
        waypointAdded = false
        // 매장으로 가던 안내를 남겨 두면 다음 주문에서 그 매장을 목적지로 착각함 → 안내도 끝냄
        if (storeIsDestination) {
            naviHelper.cancelRoute()
            routeActive = false
            lastRemainSec = null
            lastRemain = null
            Log.d(TAG, "매장 길 안내 종료")
            lifecycleScope.launch { delay(1500); naviHelper.getDestinationInfo() }   // 화면의 목적지 표시 갱신
        }
        storeIsDestination = false
        ownDest = null
        wpEtaOrders.clear()
        orders = emptyList()
        OrderTrackingService.stop(this)
        orderText = message
    }

    /** 매장 목록이 비어 있으면 Firestore에서 불러옴 */
    private suspend fun ensureStores() {
        if (stores.isNotEmpty()) return
        var docs = FirestoreRest.listDocuments("stores")
        if (docs.isEmpty()) {
            // 처음 쓰는 Firestore면 assets의 가상 매장 데이터를 올림
            val json = assets.open("stores_seed.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val seed = JSONObject(json).getJSONArray("stores")
            docs = (0 until seed.length()).map { seed.getJSONObject(it) }
            docs.forEach { FirestoreRest.putDocument("stores", it.getString("id"), it) }
            Log.d(TAG, "매장 데이터 ${docs.size}곳 업로드")
        }
        stores = docs.map { storeFromJson(it) }
    }

    private fun renderOrder() {
        if (orders.isEmpty()) return
        val stops = PickupRoute.nearestFirst(curLat ?: DEFAULT_LAT, curLng ?: DEFAULT_LNG, pickupStores()) { it.storeLat to it.storeLng }
        val route = if (stops.size > 1) "🧭 들르는 순서: ${(stops.map { it.storeName } + listOfNotNull(ownDest?.name)).joinToString(" → ")}\n\n" else ""
        orderText = route + orders.joinToString("\n\n") { orderLines(it) }
    }

    private fun orderLines(o: Order): String {
        val now = System.currentTimeMillis()
        val sendIn = ((o.sendAt - now) / 1000).coerceAtLeast(0)
        val etaIn = ((o.etaAt - now) / 1000).coerceAtLeast(0)
        val sendLine = if (o.status == Order.STATUS_SCHEDULED && sendIn > 0)
            "매장 전송까지 ${sendIn / 60}분 ${sendIn % 60}초" else "매장에 전송됨"
        val kind = if (o.isDelivery) "🛵 배달 → ${o.deliveryTo}" else "🛍️ 픽업"
        val etaLine = if (o.isDelivery)
            "목적지 도착 약 ${etaIn / 60}분 ${etaIn % 60}초 뒤 (조리 ${o.cookMin}분 + 배달 ${o.deliveryMin}분)"
        else
            "매장 도착 약 ${etaIn / 60}분 ${etaIn % 60}초 뒤 (조리 ${o.cookMin}분)"
        return "🧾 $kind · ${o.storeName} · ${o.menuName} ${"%,d".format(o.price)}원\n" +
            "상태: ${Order.statusLabel(o.status, o.isDelivery)}\n" +
            "$sendLine · $etaLine"
    }

    // SDK 콜백은 다른 스레드에서 올 수 있으므로 화면 갱신은 UI 스레드에서
    private fun show(msg: String) = runOnUiThread { status = msg }

    override fun onDestroy() {
        naviHelper.removeListener(naviListener)
        naviHelper.release()
        stt?.let { it.removeListener(sttListener); it.release() }
        tts?.let { it.removeEventListener(ttsListener); it.release() }
        OrderTrackingService.stop(this)
        super.onDestroy()
    }
}

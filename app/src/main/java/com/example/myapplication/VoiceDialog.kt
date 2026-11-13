package com.example.myapplication

/** 배달 받을 곳 후보 (내비 즐겨찾기·최근 목적지·데모 장소). aliases는 이름 외에 알아들을 말 ("집", "회사") */
data class Place(val name: String, val lat: Double, val lng: Double, val aliases: List<String> = emptyList())

/**
 * 음성 주문 대화 흐름 (SDK와 무관한 순수 로직 → 단위 테스트 가능).
 *
 *  ① 무엇을 드시고 싶으세요?        → "국물 있는 거", "치킨", "아무거나"
 *  ② 픽업과 배달 중 어떤 걸로 할까요? → "픽업" / "배달"   (①에서 같이 말했으면 생략)
 *  ③ (배달인데 내비 목적지가 없으면) 어디로 배달할까요? → "집", "강남역" (즐겨찾기·최근 목적지에서 찾음)
 *  ④ "한끼찌개의 김치찌개, 9000원… 주문할까요?" → "네"면 주문 / "아니"면 다음 후보
 *  ⑤ 주문: 경로가 있으면 바로(픽업은 경유지 추가), 없으면 MainActivity가 경로를 조회한 뒤 그 ETA로 주문
 *
 * 추천 범위: 픽업은 경로가 있으면 가는 길, 없으면 현재 위치 근처 / 배달은 내비 목적지 또는 ③에서 고른 곳 근처.
 * 화면을 보지 않고 쓰도록 한 번에 하나만 묻고, 짧은 말로 답하게 함.
 */
class VoiceDialog(
    /** 태그·방식(true=배달)·배달 장소(null=내비 목적지)로 추천 목록을 돌려줌. 계산할 수 없으면 null */
    private val recommend: (tags: Set<String>, delivery: Boolean, place: Place?) -> List<Recommendation>?,
    /** 내비에 목적지(경로)가 설정되어 있는지 */
    private val hasDestination: () -> Boolean = { true },
    /** 배달 장소로 고를 수 있는 곳들 */
    private val places: () -> List<Place> = { emptyList() }
) {
    /** 앱이 할 일: say를 읽고, listen이면 다시 듣고, order가 있으면 주문 (place가 있으면 그곳으로 배달) */
    data class Reply(val say: String, val listen: Boolean, val order: Recommendation? = null, val place: Place? = null)

    private sealed class State {
        object Idle : State()
        object AskingFood : State()
        object AskingMode : State()
        object AskingPlace : State()
        data class Proposing(val recs: List<Recommendation>, val index: Int) : State()
    }

    private var state: State = State.Idle
    private var misses = 0                       // 연속으로 못 알아들은 횟수
    private var mode: VoiceParser.Mode? = null   // 픽업/배달 (아직 모르면 null)
    private var place: Place? = null             // 배달 받을 곳 (내비 목적지를 쓰면 null)
    private var tags: Set<String> = emptySet()
    private var keyword: String? = null

    private val delivery get() = mode == VoiceParser.Mode.DELIVERY

    val isActive: Boolean get() = state != State.Idle

    fun start(): Reply {
        state = State.AskingFood
        misses = 0
        mode = null
        place = null
        tags = emptySet()
        keyword = null
        return Reply("무엇을 드시고 싶으세요? 국물, 빠른 거, 치킨처럼 말씀해 주세요.", listen = true)
    }

    fun cancel(): Reply {
        state = State.Idle
        return Reply("주문을 취소했어요.", listen = false)
    }

    /**
     * 듣는 동안 아무 말이 없을 때 (앱의 STT 타임아웃). 못 알아들은 것과 같게 처리 →
     * 한 번은 다시 묻고, 두 번 연속이면 종료. 대화 중이 아니면 null.
     */
    fun timeout(): Reply? = when (state) {
        State.Idle -> null
        State.AskingFood -> miss("말씀이 없으셔서 다시 여쭤볼게요. 무엇을 드시고 싶으세요?")
        State.AskingMode -> miss("말씀이 없으셔서 다시 여쭤볼게요. $MODE_QUESTION")
        State.AskingPlace -> miss("말씀이 없으셔서 다시 여쭤볼게요. ${placeQuestion()}")
        is State.Proposing -> miss("말씀이 없으셔서 다시 여쭤볼게요. 네 또는 아니요로 말씀해 주세요.")
    }

    /** STT 최종 결과(또는 대체 버튼 입력)를 받아 다음 응답을 만듦 */
    fun handle(text: String): Reply {
        if (VoiceParser.isCancel(text)) return cancel()
        return when (val s = state) {
            State.Idle -> start()
            State.AskingFood -> onFood(text)
            State.AskingMode -> onMode(text)
            State.AskingPlace -> onPlace(text)
            is State.Proposing -> answer(s, text)
        }
    }

    // ① 음식 (같은 문장에 방식·장소가 있으면 함께 받음: "배달로 치킨", "집으로 치킨 보내 줘")
    private fun onFood(text: String): Reply {
        tags = VoiceParser.tags(text)
        keyword = VoiceParser.foodKeyword(text)
        takeModeAndPlace(text)
        misses = 0
        return next()
    }

    // ② 픽업/배달
    private fun onMode(text: String): Reply {
        if (VoiceParser.mode(text) == null) {
            // 방식 대신 음식을 다시 말했으면 음식부터 다시
            if (saysFood(text)) return onFood(text)
            return miss("픽업 또는 배달로 말씀해 주세요.")
        }
        takeModeAndPlace(text)
        misses = 0
        return next()
    }

    // ③ 배달 장소
    private fun onPlace(text: String): Reply {
        if (VoiceParser.mode(text) == VoiceParser.Mode.PICKUP) { mode = VoiceParser.Mode.PICKUP; misses = 0; return next() }
        place = VoiceParser.place(text, places()) ?: return miss("잘 못 들었어요. ${placeQuestion()}")
        misses = 0
        return next()
    }

    private fun takeModeAndPlace(text: String) {
        VoiceParser.mode(text)?.let { mode = it }
        // 장소는 내비 목적지가 없을 때만 받음 (목적지가 있으면 그곳으로 배달 → 주행 경로를 바꾸지 않음)
        if (delivery && !hasDestination()) VoiceParser.place(text, places())?.let { place = it }
    }

    private fun saysFood(text: String) = VoiceParser.tags(text).isNotEmpty() || VoiceParser.foodKeyword(text) != null

    /** 아직 모르는 것을 묻거나, 다 알면 찾아서 제안 */
    private fun next(): Reply {
        if (mode == null) {
            state = State.AskingMode
            return Reply(MODE_QUESTION, listen = true)
        }
        if (delivery && place == null && !hasDestination()) {
            state = State.AskingPlace
            return Reply(placeQuestion(), listen = true)
        }
        return search()
    }

    private fun search(): Reply {
        val recs = recommend(tags, delivery, if (delivery) place else null) ?: run {
            state = State.Idle
            return Reply("주변 매장 정보를 가져오지 못했어요. 잠시 뒤 다시 불러 주세요.", listen = false)
        }
        val found = keyword?.let { k ->
            recs.filter { k in it.menu.name || k in it.store.category || k in it.store.name }
        } ?: recs
        if (found.isNotEmpty()) { misses = 0; return propose(found, 0) }
        state = State.AskingFood   // 방식·장소는 유지하고 음식만 다시
        return miss(
            if (delivery) "${placeName()} 근처에 도착 전까지 배달되는 메뉴가 없어요. 다른 걸 말씀하시거나 픽업이라고 말씀해 주세요."
            else "근처에 받을 수 있는 메뉴가 없어요. 다른 걸 말씀하시거나 배달이라고 말씀해 주세요."
        )
    }

    private fun answer(s: State.Proposing, text: String): Reply {
        // "아니, 국물로", "배달로 해 줘" 처럼 새 조건을 말하면 다시 찾음 (말하지 않은 조건은 그대로)
        if (saysFood(text) || VoiceParser.mode(text) != null) {
            if (saysFood(text)) { tags = VoiceParser.tags(text); keyword = VoiceParser.foodKeyword(text) }
            takeModeAndPlace(text)
            misses = 0
            return next()
        }
        return when (VoiceParser.answer(text)) {
            VoiceParser.Answer.YES -> {
                val rec = s.recs[s.index]
                state = State.Idle
                val store = spokenName(rec.store.name)
                val p = if (rec.delivery) place else null
                val say = when {
                    rec.delivery && p != null ->
                        "${rec.menu.name} 배달 주문할게요. ${p.name}까지 경로를 안내하고, 도착 시간에 맞춰 ${store}에서 보내 드릴게요."
                    rec.delivery ->
                        "${rec.menu.name} 배달 주문할게요. 목적지에 도착하실 때 맞춰 ${store}에서 보내 드릴게요."
                    hasDestination() ->
                        "${rec.menu.name} 주문할게요. ${VoiceDialog.withEulReul(store)} 경유지로 추가하고, 도착 시간에 맞춰 조리를 요청할게요."
                    else ->
                        "${rec.menu.name} 주문할게요. ${store}까지 길을 안내하고, 도착 시간에 맞춰 조리를 요청할게요."
                }
                Reply(say, listen = false, order = rec, place = p)
            }
            VoiceParser.Answer.NO ->
                if (s.index + 1 < s.recs.size) { misses = 0; propose(s.recs, s.index + 1) }
                else { state = State.AskingFood; Reply("추천할 메뉴가 더 없어요. 다른 걸 말씀해 주세요.", listen = true) }
            VoiceParser.Answer.UNKNOWN -> miss("잘 못 들었어요. 네 또는 아니요로 말씀해 주세요.")
        }
    }

    private fun propose(recs: List<Recommendation>, index: Int): Reply {
        state = State.Proposing(recs, index)
        val r = recs[index]
        val min = r.arriveSec / 60
        val detail = when {
            r.delivery -> "약 ${min}분 뒤 ${placeName()}에 도착하실 때 맞춰 배달돼요. 배달로 주문할까요?"
            r.slackSec >= 0 -> "약 ${min}분 뒤 도착하면 바로 받을 수 있어요. 주문할까요?"
            else -> "약 ${min}분 거리이고, 도착 후 ${(-r.slackSec + 59) / 60}분쯤 기다리면 받아요. 주문할까요?"
        }
        return Reply("${spokenName(r.store.name)}의 ${r.menu.name}, ${r.menu.price}원이에요. $detail", listen = true)
    }

    /** 두 번 연속 못 알아들으면 종료 (운전 중 대화가 길어지지 않게) */
    private fun miss(say: String): Reply {
        misses++
        if (misses >= MAX_MISSES) {
            state = State.Idle
            return Reply("주문을 취소할게요. 필요하면 다시 불러 주세요.", listen = false)
        }
        return Reply(say, listen = true)
    }

    private fun placeName() = place?.name ?: "목적지"

    /** "어디로 배달할까요? 집, 회사, 강남역 중에서 말씀해 주세요." (후보는 최대 3곳만 읽음) */
    private fun placeQuestion(): String {
        val names = places().map { it.aliases.firstOrNull() ?: spokenName(it.name) }.distinct().take(3)
        return if (names.isEmpty()) "어디로 배달할까요?" else "어디로 배달할까요? ${names.joinToString(", ")} 중에서 말씀해 주세요."
    }

    /** "한끼찌개 (가상)" → "한끼찌개" (읽을 때 괄호 제거) */
    private fun spokenName(name: String) = name.substringBefore(" (").trim()

    companion object {
        const val MAX_MISSES = 2
        const val MODE_QUESTION = "들러서 받는 픽업과 배달 중 어떤 걸로 할까요?"

        /** 받침 유무에 따라 을/를 ("서초치킨공방을", "테헤란샐러드를") */
        fun withEulReul(word: String): String {
            val last = word.lastOrNull() ?: return word
            if (last !in '가'..'힣') return "${word}을(를)"
            val hasBatchim = (last - '가') % 28 != 0
            return word + if (hasBatchim) "을" else "를"
        }
    }
}

/** 말한 문장에서 의도·조건을 뽑는 단순 키워드 규칙 */
object VoiceParser {
    enum class Answer { YES, NO, UNKNOWN }
    enum class Mode { PICKUP, DELIVERY }

    private val DELIVERY_WORDS = listOf("배달", "집으로", "보내")
    private val PICKUP_WORDS = listOf("포장", "픽업", "들러", "들를", "가는 길", "직접")

    private val NO_WORDS = listOf("아니", "싫어", "다른", "다음", "별로", "말고", "노")
    private val YES_WORDS = listOf("응", "네", "예", "좋아", "그래", "주문", "해줘", "해 줘", "오케이", "콜", "yes", "ok")
    private val CANCEL_WORDS = listOf("취소", "그만", "됐어", "안 먹", "안먹")

    /** 말 → 메뉴 태그 (stores_seed.json의 tags와 같은 이름) */
    private val TAG_WORDS = mapOf(
        "국물" to listOf("국물", "찌개", "국수", "탕", "따뜻"),
        "빠름" to listOf("빠른", "빨리", "금방", "간단"),
        "매운" to listOf("매운", "맵게", "얼큰", "칼칼"),
        "가벼움" to listOf("가벼운", "가볍게", "샐러드", "다이어트", "건강"),
        "여럿" to listOf("여럿", "같이", "가족", "다 같이", "나눠"),
        "야식" to listOf("야식"),
        "음료" to listOf("커피", "음료", "마실"),
    )

    /** 메뉴 이름·업종에서 찾을 음식 단어 (태그로 표현 안 되는 것) */
    private val FOOD_WORDS = listOf("치킨", "버거", "김밥", "찌개", "국수", "국밥", "샐러드", "포케", "제육")

    fun isCancel(text: String) = CANCEL_WORDS.any { it in text }

    /** "배달로", "집으로 보내 줘" → 배달 / "포장", "픽업" → 픽업 / 말하지 않으면 null */
    fun mode(text: String): Mode? = when {
        DELIVERY_WORDS.any { it in text } -> Mode.DELIVERY
        PICKUP_WORDS.any { it in text } -> Mode.PICKUP
        else -> null
    }

    /** 말한 문장에서 장소 찾기: 별칭("집", "회사") 또는 이름("강남역")이 들어 있으면. 띄어쓰기는 무시 */
    fun place(text: String, places: List<Place>): Place? {
        val t = text.replace(" ", "")
        return places.firstOrNull { p ->
            (p.aliases + p.name).any { w -> w.replace(" ", "").let { it.isNotEmpty() && it in t } }
        }
    }

    fun answer(text: String): Answer {
        val t = text.trim().lowercase()
        if (NO_WORDS.any { it in t }) return Answer.NO
        if (YES_WORDS.any { it in t }) return Answer.YES
        return Answer.UNKNOWN
    }

    fun tags(text: String): Set<String> =
        TAG_WORDS.filterValues { words -> words.any { it in text } }.keys

    fun foodKeyword(text: String): String? = FOOD_WORDS.firstOrNull { it in text }
}

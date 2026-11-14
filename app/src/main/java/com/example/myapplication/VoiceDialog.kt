package com.example.myapplication

/** 배달 받을 곳 후보 (내비 즐겨찾기·최근 목적지·데모 장소). aliases는 이름 외에 알아들을 말 ("집", "회사") */
data class Place(val name: String, val lat: Double, val lng: Double, val aliases: List<String> = emptyList())

/**
 * 음성 주문 대화 흐름 (SDK와 무관한 순수 로직 → 단위 테스트 가능).
 *
 *  ① 무엇을 드시고 싶으세요?        → "국물 있는 거", "치킨", "아무거나" (못 알아들으면 다시 물음)
 *  ② 픽업과 배달 중 어떤 걸로 할까요? → "픽업" / "배달"   (①에서 같이 말했으면 생략)
 *  ③ (배달인데 내비 목적지가 없으면) 어디로 배달할까요? → "집", "강남역" (즐겨찾기·최근 목적지에서 찾음)
 *  ④ "한끼찌개의 김치찌개, 9000원… 주문할까요?" → "네"면 주문 / "아니"면 다음 후보
 *     (배달이 도착보다 늦으면 몇 분 뒤 오는지 알려 주고 그래도 주문 가능)
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
    private var food: VoiceParser.Food? = null   // 치킨, 피자 같은 음식 종류 (태그보다 우선)

    private val delivery get() = mode == VoiceParser.Mode.DELIVERY

    val isActive: Boolean get() = state != State.Idle

    fun start(): Reply {
        state = State.AskingFood
        misses = 0
        mode = null
        place = null
        tags = emptySet()
        food = null
        return Reply(FOOD_QUESTION, listen = true)
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
        is State.Proposing -> miss("말씀이 없으셔서 다시 여쭤볼게요. 주문할까요? 네 또는 아니요로 말씀해 주세요.")
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
        // 음식을 못 알아들었으면 방식으로 넘어가지 않고 다시 물음 ("배달로"만 말했으면 방식은 받아 둠)
        if (!saysFood(text) && !VoiceParser.isAnything(text)) {
            val m = VoiceParser.mode(text) ?: return miss("잘 못 들었어요. $FOOD_QUESTION 아무거나라고 하셔도 돼요.")
            mode = m
            takeModeAndPlace(text)
            misses = 0
            // 이미 음식을 들었으면 (찾는 게 없어 방식만 바꾸는 경우) 바로 다시 찾음
            if (food != null || tags.isNotEmpty()) return next()
            return Reply("${if (m == VoiceParser.Mode.DELIVERY) "배달로" else "픽업으로"} 할게요. 무엇을 드시고 싶으세요?", listen = true)
        }
        tags = VoiceParser.tags(text)
        food = VoiceParser.food(text)
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

    private fun saysFood(text: String) = VoiceParser.tags(text).isNotEmpty() || VoiceParser.food(text) != null

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
        val where = if (delivery) placeName() else null
        val recs = find(tags) ?: run {
            state = State.Idle
            return Reply("주변 매장 정보를 가져오지 못했어요. 잠시 뒤 다시 불러 주세요.", listen = false)
        }
        val f = food
        // 음식 종류를 말했으면 그 음식이 우선 ("배고파, 치킨"에서 '든든' 태그가 치킨을 거르지 않게)
        val found = if (f == null) recs else recs.filter { VoiceParser.matches(f, it) }
            .ifEmpty { (find(emptySet()) ?: emptyList()).filter { VoiceParser.matches(f, it) } }
        if (found.isNotEmpty()) { misses = 0; return propose(found, 0) }

        // 원하는 게 없으면 끝내지 않고 대신 고를 만한 메뉴를 제안
        val alt = (if (f != null) recs else emptyList()).ifEmpty { find(emptySet()) ?: emptyList() }
        val what = f?.name ?: "말씀하신 메뉴"
        val area = if (delivery) "$where 근처에 배달되는 $what" else if (hasDestination()) "가는 길에 $what" else "근처에 $what"
        if (alt.isNotEmpty()) {
            misses = 0
            return propose(alt, 0, prefix = "${withIGa(area)} 없어요. 대신 ")
        }
        state = State.AskingFood   // 방식·장소는 유지하고 음식만 다시
        return miss(
            if (delivery) "$where 근처에 배달되는 메뉴가 없어요. 다른 걸 말씀하시거나 픽업이라고 말씀해 주세요."
            else "근처에 받을 수 있는 메뉴가 없어요. 다른 걸 말씀하시거나 배달이라고 말씀해 주세요."
        )
    }

    private fun find(t: Set<String>) = recommend(t, delivery, if (delivery) place else null)

    private fun answer(s: State.Proposing, text: String): Reply {
        // "아니, 국물로", "배달로 해 줘", "치킨 말고 피자"처럼 새 조건을 말하면 다시 찾음 (말하지 않은 조건은 그대로)
        val newTags = VoiceParser.tags(text)
        val newFood = VoiceParser.food(text)
        val newMode = VoiceParser.mode(text)
        val foodChanged = (newFood != null && newFood != food) || (newFood == null && newTags.isNotEmpty() && newTags != tags)
        val modeChanged = newMode != null && newMode != mode
        if (foodChanged || modeChanged) {
            if (foodChanged) { tags = newTags; food = newFood }
            takeModeAndPlace(text)
            misses = 0
            return next()
        }
        // "다 별로야", "맘에 드는 게 없어" → 남은 후보를 넘기지 않고 음식부터 다시 (방식·장소는 유지)
        if (VoiceParser.rejectsAll(text)) {
            state = State.AskingFood
            misses = 0
            return Reply("그럼 다른 걸 찾아볼게요. $FOOD_QUESTION", listen = true)
        }
        // "방금 거로 해 줘", "처음 거" → 앞서 들은 메뉴로 바로 주문
        if (VoiceParser.wantsFirst(text)) return order(s.recs[0])
        if (VoiceParser.wantsPrevious(text)) return order(s.recs[(s.index - 1).coerceAtLeast(0)])
        // "더 싼 거" → 남은 후보 중 지금보다 싼 메뉴
        if (VoiceParser.wantsCheaper(text)) {
            val cur = s.recs[s.index]
            val cheaper = s.recs.withIndex().filter { it.value.menu.price < cur.menu.price }.minByOrNull { it.value.menu.price }
            return if (cheaper != null) { misses = 0; propose(s.recs, cheaper.index) }
            else Reply("이게 가장 싼 메뉴예요. 주문할까요?", listen = true)
        }
        return when (VoiceParser.answer(text)) {
            VoiceParser.Answer.YES -> order(s.recs[s.index])
            VoiceParser.Answer.NO ->
                if (s.index + 1 < s.recs.size) { misses = 0; propose(s.recs, s.index + 1) }
                else { state = State.AskingFood; Reply("추천할 메뉴가 더 없어요. 다른 걸 말씀해 주세요.", listen = true) }
            VoiceParser.Answer.UNKNOWN -> miss("잘 못 들었어요. 주문할까요? 다른 메뉴를 원하시면 아니요라고 해 주세요.")
        }
    }

    private fun order(rec: Recommendation): Reply {
        state = State.Idle
        val store = spokenName(rec.store.name)
        val p = if (rec.delivery) place else null
        val say = when {
            rec.delivery && p != null ->
                "${rec.menu.name} 배달 주문할게요. ${p.name}까지 경로를 안내하고, 도착 시간에 맞춰 ${store}에서 보내 드릴게요."
            rec.delivery ->
                "${rec.menu.name} 배달 주문할게요. 목적지에 도착하실 때 맞춰 ${store}에서 보내 드릴게요."
            hasDestination() ->
                "${rec.menu.name} 주문할게요. ${withEulReul(store)} 경유지로 추가하고, 도착 시간에 맞춰 조리를 요청할게요."
            else ->
                "${rec.menu.name} 주문할게요. ${store}까지 길을 안내하고, 도착 시간에 맞춰 조리를 요청할게요."
        }
        return Reply(say, listen = false, order = rec, place = p)
    }

    private fun propose(recs: List<Recommendation>, index: Int, prefix: String = ""): Reply {
        state = State.Proposing(recs, index)
        val r = recs[index]
        val min = r.arriveSec / 60
        val detail = when {
            r.delivery && r.slackSec >= 0 -> "약 ${min}분 뒤 ${placeName()}에 도착하실 때 맞춰 배달돼요. 배달로 주문할까요?"
            r.delivery -> "약 ${min}분 뒤 ${placeName()}에 도착하시고, 그 뒤 ${(-r.slackSec + 59) / 60}분쯤 지나 배달돼요. 배달로 주문할까요?"
            r.slackSec >= 0 -> "약 ${min}분 뒤 도착하면 바로 받을 수 있어요. 주문할까요?"
            else -> "약 ${min}분 거리이고, 도착 후 ${(-r.slackSec + 59) / 60}분쯤 기다리면 받아요. 주문할까요?"
        }
        return Reply("$prefix${spokenName(r.store.name)}의 ${r.menu.name}, ${r.menu.price}원이에요. $detail", listen = true)
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
        const val FOOD_QUESTION = "무엇을 드시고 싶으세요? 치킨, 국밥, 커피처럼 말씀해 주세요."
        const val MODE_QUESTION = "들러서 받는 픽업과 배달 중 어떤 걸로 할까요?"

        /** 받침 유무에 따라 을/를 ("서초치킨공방을", "테헤란샐러드를") */
        fun withEulReul(word: String): String {
            val last = word.lastOrNull() ?: return word
            if (last !in '가'..'힣') return "${word}을(를)"
            val hasBatchim = (last - '가') % 28 != 0
            return word + if (hasBatchim) "을" else "를"
        }

        /** 받침 유무에 따라 이/가 ("치킨이", "피자가") */
        fun withIGa(word: String): String {
            val last = word.lastOrNull() ?: return word
            if (last !in '가'..'힣') return "${word}이(가)"
            return word + if ((last - '가') % 28 != 0) "이" else "가"
        }
    }
}

/** 말한 문장에서 의도·조건을 뽑는 단순 키워드 규칙 (2차에서 Gleo AI로 대체 예정) */
object VoiceParser {
    enum class Answer { YES, NO, UNKNOWN }
    enum class Mode { PICKUP, DELIVERY }

    /** 음식 종류 하나: 말할 때 쓰는 단어들과, 메뉴 이름·업종·매장 이름에서 찾을 단어들 */
    data class Food(val name: String, val say: List<String>, val match: List<String>)

    private val DELIVERY_WORDS = listOf("배달", "집으로", "보내", "갖다", "가져다", "배송")
    private val PICKUP_WORDS = listOf("포장", "픽업", "들러", "들를", "들렀", "가는 길", "가는길", "직접",
        "가지러", "찾으러", "받으러", "테이크아웃", "내가 갈", "가서 받")

    private val NO_WORDS = listOf("아니", "싫", "다른", "다음", "별로", "별론", "별루", "말고", "노노", "패스", "바꿔", "안 할", "안할")
    private val YES_WORDS = listOf("응", "네", "예", "좋아", "좋네", "좋습니다", "그래", "주문", "해줘", "해 줘", "오케이",
        "콜", "걸로", "그거", "부탁", "할게", "할래", "먹을게", "갈게", "맞아", "가자", "고고", "ㅇㅇ", "yes", "ok")
    private val CANCEL_WORDS = listOf("취소", "그만", "됐어", "안 먹", "안먹")
    /** "방금 거", "아까 그거" → 바로 앞에 제안한 메뉴 / "처음 거" → 첫 제안 */
    private val PREV_WORDS = listOf("방금", "아까", "이전", "앞에", "전에 거", "전에거", "전 거")
    private val FIRST_WORDS = listOf("처음", "첫 번째", "첫번째", "맨 앞")
    private val CHEAPER_WORDS = listOf("싼", "저렴", "싸게")

    /** 말 → 메뉴 태그 (stores_seed.json의 tags와 같은 이름) */
    private val TAG_WORDS = mapOf(
        "국물" to listOf("국물", "찌개", "국수", "국밥", "따뜻", "뜨끈", "해장", "매운탕", "갈비탕", "우동"),
        "빠름" to listOf("빠른", "빨리", "금방", "간단", "급해", "바빠"),
        "매운" to listOf("매운", "맵게", "매콤", "얼큰", "칼칼"),
        "가벼움" to listOf("가벼운", "가볍게", "샐러드", "다이어트", "건강"),
        "여럿" to listOf("여럿", "같이", "가족", "나눠", "친구들"),
        "야식" to listOf("야식"),
        "음료" to listOf("커피", "음료", "마실", "라떼", "아메리카노"),
        "식사" to listOf("든든", "배고", "식사"),
        "간식" to listOf("간식", "출출", "입이 심심"),
    )

    /** 음식 종류 (앞에 있는 것을 먼저 찾음: "치킨 버거"는 치킨) */
    val FOODS = listOf(
        Food("치킨", listOf("치킨", "통닭", "닭강정", "후라이드", "순살"), listOf("치킨", "통닭")),
        Food("피자", listOf("피자"), listOf("피자")),
        Food("버거", listOf("버거"), listOf("버거")),
        Food("떡볶이", listOf("떡볶이", "떡볶기", "떡뽁이", "라볶이"), listOf("떡볶이", "라볶이")),
        Food("김밥", listOf("김밥"), listOf("김밥")),
        Food("라면", listOf("라면"), listOf("라면", "라볶이")),
        Food("짜장", listOf("짜장", "자장"), listOf("짜장")),
        Food("짬뽕", listOf("짬뽕"), listOf("짬뽕")),
        Food("중식", listOf("중식", "중국집", "중국 음식", "탕수육"), listOf("중식")),
        Food("돈까스", listOf("돈까스", "돈가스", "까스"), listOf("돈까스")),
        Food("우동", listOf("우동"), listOf("우동")),
        Food("초밥", listOf("초밥", "스시"), listOf("초밥")),
        Food("일식", listOf("일식"), listOf("일식")),
        Food("국밥", listOf("국밥", "순대국", "순댓국"), listOf("국밥", "순대국")),
        Food("찌개", listOf("찌개"), listOf("찌개")),
        Food("국수", listOf("국수"), listOf("국수")),
        Food("파스타", listOf("파스타", "스파게티"), listOf("스파게티", "파스타")),
        Food("샐러드", listOf("샐러드", "포케"), listOf("샐러드", "포케")),
        Food("샌드위치", listOf("샌드위치", "베이글", "토스트"), listOf("샌드위치")),
        Food("커피", listOf("커피", "아메리카노", "라떼", "카페"), listOf("아메리카노", "라떼", "카페")),
        Food("덮밥", listOf("덮밥", "제육", "볶음밥"), listOf("덮밥", "제육", "볶음밥")),
        Food("분식", listOf("분식"), listOf("분식")),
        Food("한식", listOf("한식", "집밥"), listOf("한식")),
    )

    /** "아무거나", "추천해 줘" → 음식을 정하지 않고 아무 메뉴나 */
    private val ANY_WORDS = listOf("아무거나", "아무 거나", "아무거", "아무 거", "아무꺼", "뭐든", "뭐라도", "추천", "상관없", "상관 없", "알아서", "맛있는")

    /** "다 별로", "맘에 드는 게 없어" → 제안한 메뉴 전부 싫음 */
    private val REJECT_ALL_WORDS = listOf("다 별로", "다별로", "다 별론", "다별론", "다 싫", "다싫", "다 아니", "전부", "모두", "죄다",
        "하나도", "맘에 안", "마음에 안", "맘에 드는 게 없", "마음에 드는 게 없", "땡기는 게 없", "당기는 게 없", "먹고 싶은 게 없")

    fun rejectsAll(text: String) = REJECT_ALL_WORDS.any { it in text }

    fun isAnything(text: String) = ANY_WORDS.any { it in text }

    fun isCancel(text: String) = CANCEL_WORDS.any { it in text }

    /** "치킨 말고 피자" → "피자" 쪽만 봄 */
    private fun afterMalgo(text: String) = if ("말고" in text) text.substringAfterLast("말고") else text

    /**
     * "배달로", "집으로 보내 줘" → 배달 / "포장", "가지러 갈게" → 픽업 / 말하지 않으면 null.
     * "배달 말고"처럼 하나를 빼면 다른 쪽.
     */
    fun mode(text: String): Mode? {
        val after = afterMalgo(text)
        modeIn(after)?.let { return it }
        if (after != text) return when (modeIn(text.substringBeforeLast("말고"))) {
            Mode.DELIVERY -> Mode.PICKUP
            Mode.PICKUP -> Mode.DELIVERY
            null -> null
        }
        return null
    }

    private fun modeIn(text: String): Mode? = when {
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
        if (t == "노" || NO_WORDS.any { it in t }) return Answer.NO
        if (YES_WORDS.any { it in t }) return Answer.YES
        return Answer.UNKNOWN
    }

    fun wantsPrevious(text: String) = PREV_WORDS.any { it in text }
    fun wantsFirst(text: String) = FIRST_WORDS.any { it in text }
    fun wantsCheaper(text: String) = CHEAPER_WORDS.any { it in text }

    fun tags(text: String): Set<String> {
        val t = afterMalgo(text).replace("탕수육", "")
        return TAG_WORDS.filterValues { words -> words.any { it in t } }.keys
    }

    fun food(text: String): Food? {
        val t = afterMalgo(text)
        return FOODS.firstOrNull { f -> f.say.any { it in t } }
    }

    fun foodKeyword(text: String): String? = food(text)?.name

    /** 이 메뉴가 그 음식인지 (메뉴 이름·업종·매장 이름에서 찾음) */
    fun matches(food: Food, r: Recommendation) =
        food.match.any { k -> k in r.menu.name || k in r.store.category || k in r.store.name }
}

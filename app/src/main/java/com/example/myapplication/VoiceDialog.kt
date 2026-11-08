package com.example.myapplication

/**
 * 음성 주문 대화 흐름 (SDK와 무관한 순수 로직 → 단위 테스트 가능).
 *
 *  ① "무엇을 드시고 싶으세요?"          → 사용자: "국물 있는 거", "치킨", "아무거나"
 *  ② "한끼찌개의 김치찌개, 9000원… 주문할까요?" → "응" 이면 주문 / "아니" 면 다음 후보
 *  ③ "주문할게요…"                       → 주문 + 경유지 추가는 MainActivity가 처리
 *
 * 화면을 보지 않고 쓰도록 한 번에 한 메뉴만 제안하고, 네/아니요로만 답하게 함.
 */
class VoiceDialog(
    /** 원하는 태그로 추천 목록을 돌려줌. 경로 정보가 없으면 null */
    private val recommend: (Set<String>) -> List<Recommendation>?
) {
    /** 앱이 할 일: say를 읽고, listen이면 다시 듣고, order가 있으면 주문 */
    data class Reply(val say: String, val listen: Boolean, val order: Recommendation? = null)

    private sealed class State {
        object Idle : State()
        object AskingFood : State()
        data class Proposing(val recs: List<Recommendation>, val index: Int) : State()
    }

    private var state: State = State.Idle
    private var misses = 0          // 연속으로 못 알아들은 횟수

    val isActive: Boolean get() = state != State.Idle

    fun start(): Reply {
        state = State.AskingFood
        misses = 0
        return Reply("무엇을 드시고 싶으세요? 국물, 빠른 거, 가벼운 거처럼 말씀해 주세요.", listen = true)
    }

    fun cancel(): Reply {
        state = State.Idle
        return Reply("주문을 취소했어요.", listen = false)
    }

    /** STT 최종 결과(또는 대체 버튼 입력)를 받아 다음 응답을 만듦 */
    fun handle(text: String): Reply {
        if (VoiceParser.isCancel(text)) return cancel()
        return when (val s = state) {
            State.Idle -> start()
            State.AskingFood -> askFood(text)
            is State.Proposing -> answer(s, text)
        }
    }

    private fun askFood(text: String): Reply {
        val tags = VoiceParser.tags(text)
        val keyword = VoiceParser.foodKeyword(text)
        val recs = recommend(tags) ?: run {
            state = State.Idle
            return Reply("경로 정보가 없어요. 내비에 목적지를 먼저 설정해 주세요.", listen = false)
        }
        val filtered = if (keyword == null) recs
            else recs.filter { keyword in it.menu.name || keyword in it.store.category || keyword in it.store.name }
        if (filtered.isEmpty()) return miss("지금 가는 길에 도착 전까지 준비되는 메뉴가 없어요. 다른 걸 말씀해 주세요.")
        misses = 0
        return propose(filtered, 0)
    }

    private fun answer(s: State.Proposing, text: String): Reply {
        // "아니, 국물로" 처럼 새 조건을 말하면 다시 찾음
        if (VoiceParser.tags(text).isNotEmpty() || VoiceParser.foodKeyword(text) != null) {
            state = State.AskingFood
            return askFood(text)
        }
        return when (VoiceParser.answer(text)) {
            VoiceParser.Answer.YES -> {
                val rec = s.recs[s.index]
                state = State.Idle
                Reply(
                    "${rec.menu.name} 주문할게요. ${withEulReul(spokenName(rec.store.name))} 경유지로 추가하고, " +
                        "도착 시간에 맞춰 조리를 요청할게요.",
                    listen = false, order = rec
                )
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
        return Reply(
            "${spokenName(r.store.name)}의 ${r.menu.name}, ${r.menu.price}원이에요. " +
                "약 ${r.arriveSec / 60}분 뒤 도착하면 바로 받을 수 있어요. 주문할까요?",
            listen = true
        )
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

    /** "한끼찌개 (가상)" → "한끼찌개" (읽을 때 괄호 제거) */
    private fun spokenName(name: String) = name.substringBefore(" (").trim()

    companion object {
        const val MAX_MISSES = 2

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
    private val FOOD_WORDS = listOf("치킨", "버거", "김밥", "찌개", "국수", "샐러드", "포케", "제육")

    fun isCancel(text: String) = CANCEL_WORDS.any { it in text }

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

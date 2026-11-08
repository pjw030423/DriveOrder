# DriveOrder — 도착하는 순간 완성되는 주문

> 현대자동차그룹 차량용 앱 개발 공모전 출품작 · Pleos Connect 차량 앱

운전 중에 **음성만으로** 포장 주문을 하면, 앱이 **내 경로 위 매장**을 경유지로 넣고
**매장 도착 시각에 맞춰 조리가 끝나도록** 주문 전송 시각을 계산해 매장에 전달합니다.

```
주문 전송 시각 = 매장 도착 예정 시각 − 조리 시간 − 여유 30초
```

정체로 도착이 늦어지면 전송 시각도 자동으로 뒤로 미뤄지고, 이미 전송된 뒤라면 매장 화면에 지연 시간이 표시됩니다.

---

## 왜 필요한가

| 문제 | DriveOrder의 해결 |
|---|---|
| 운전 중 휴대폰 주문은 위험하고 불법(도로교통법 제49조) | 화면을 보지 않고 **음성 + 네/아니요**로 주문 |
| 출발 전이나 도착 후에 주문하면 매장에서 또 기다림 | 내비 ETA로 **도착 시각에 맞춰** 조리 시작 |
| 배달·포장 앱은 터치 조작 전제 | 차량 내비·음성 SDK에 맞춘 차량 전용 흐름 |

## 사용 시나리오

1. 운전자: 🎤 버튼 → 앱이 묻습니다. "무엇을 드시고 싶으세요?"
2. 운전자: "치킨" → 앱: "서초치킨공방의 후라이드 치킨, 19000원이에요. 약 25분 뒤 도착하면 바로 받을 수 있어요. 주문할까요?"
3. 운전자: "네" → 주문 저장, 매장이 **내비 경유지로 자동 추가**
4. 주행 중 5초마다 내비의 **매장(경유지)까지 남은 시간**으로 전송 시각 재계산
5. 전송 시각이 되면 매장 웹 "접수 대기"에 주문이 뜨고, 매장은 조리 시작 → 완료 → 픽업 완료 처리
6. 차량 앱에 상태(조리 중·조리 완료)가 표시되고, 매장 도착 시 음식이 준비되어 있음

## Pleos Connect SDK 활용

부가 기능이 아니라 **핵심 동작마다** 차량 SDK를 씁니다.

| SDK | 쓰는 기능 | DriveOrder에서의 역할 |
|---|---|---|
| **NaviHelper** 3.0.5 | `getDestinationInfo`, `getCurrentLocationInfo` | 목적지 ETA·현재 위치 → 경로 위 매장 추천 |
| | `addWaypoint`, `getWaypointInfo`, `onWayPointArrived` | 주문 매장을 경유지로 추가, **경유지까지 남은 시간으로 전송 시각 계산** |
| **SpeechToText** 3.3.1 | `request`, `ResultListener.onUpdated` (ON_DEVICE) | 운전자의 메뉴 요청·네/아니요 인식 |
| **TextToSpeech** 3.3.1 | `speak`, `EventListener.onDone` (ON_DEVICE) | 추천·확인 안내, 안내가 끝나면 자동으로 듣기 시작 |

ON_DEVICE 모드라 서버 앱 등록(Client ID/Secret) 없이 동작합니다.

## 시스템 구조

```mermaid
flowchart LR
  subgraph Car["차량 앱 (Pleos, Android Automotive)"]
    V[음성 대화<br/>STT · TTS] --> R[경로 위 메뉴 추천]
    R --> O[주문 생성]
    O --> W[NaviHelper<br/>경유지 추가]
    W --> S[전송 시각 계산<br/>5초마다 재계산]
  end
  S -- "etaAt · sendAt" --> F[(Firestore<br/>stores · orders)]
  O -- "주문" --> F
  F -- "sendAt이 지난 주문" --> M[매장 웹<br/>store-web/index.html]
  M -- "status: 조리 중 → 완료 → 픽업" --> F
  F -- "상태" --> Car
```

- **전송은 시각(`sendAt`) 기반**입니다. 매장 웹은 `sendAt`이 지난 주문만 보여 주므로, 차량 앱이 잠시 꺼져 있어도 정해진 시각에 주문이 매장에 뜹니다.
- 차량 앱은 `etaAt`·`sendAt`만, 매장 웹은 `status`만 **부분 갱신**해 서로의 값을 덮어쓰지 않습니다.
- Pleos에는 Google Play 서비스가 없을 수 있어 Firebase SDK 대신 **Firestore REST API**를 씁니다.
- 실제 배달 앱은 외부 연동이 불가능해 **가상 매장 5곳**(`seed/stores_seed.json`, 이름 끝에 "(가상)")과 매장 웹으로 대체했습니다.

### 주문 상태

```
scheduled(전송 대기) → sent(매장 접수 대기) → cooking(조리 중) → ready(조리 완료) → picked_up(픽업 완료)
```

## 실행 방법

### 준비물

- Android Studio (AGP 9.5, Kotlin 2.2, compileSdk 37 / minSdk 28)
- Pleos Connect Emulator v2.1.0 (Android Automotive, API 34) — [설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
- Firebase 프로젝트의 Firestore (프로젝트 ID는 `FirestoreRest.kt`의 `PROJECT_ID`)

### 1. 에뮬레이터 실행

외장 GPU가 없는 노트북에서는 하드웨어 렌더링이 실패할 수 있어 소프트웨어 렌더링으로 실행합니다.

```bash
emulator -avd Pleos_Connect_v2.1.0 -no-snapshot-save -gpu swiftshader_indirect
```

### 2. 앱 설치

Android Studio에서 실행하거나:

```bash
./gradlew installDebug
```

### 3. 매장 웹 열기

`store-web/index.html`을 브라우저로 엽니다(서버 불필요). 또는:

```bash
python -m http.server 8765 --directory store-web
```

### 4. 데모 순서

1. **③ 매장 데이터 업로드 (최초 1회)** — Firestore에 가상 매장 5곳 저장 (보안 규칙 적용 *전*에 실행)
2. **① 목적지: 강남역 (데모)** — 에뮬레이터 기본 위치(성남 42dot 사옥)에서 강남역까지 약 27분 경로
3. **② 현재 경로로 조회 시작**
4. **데모 주행: 켜짐** — 아래 "에뮬레이터 한계" 참고
5. **🎤 음성 주문** → "치킨" → "네" (마이크가 없으면 옆의 대체 버튼 사용)
6. 매장 웹에서 "예약됨" 카운트다운 → "접수 대기" 이동 확인 → 조리 시작/완료/픽업 완료
7. **정체 +3분**을 누르면 전송 시각이 3분 미뤄지는 것을 매장 웹에서 확인

> 매장마다 영업시간이 있습니다(예: 찌개집 10~22시, 치킨집 15~24시). 늦은 시각에는 추천이 줄어드니
> "시간: 현재 시각" 버튼으로 데모 시각(12시·18시)을 바꿀 수 있습니다.

## 테스트

SDK와 무관한 핵심 로직은 순수 Kotlin으로 분리해 PC에서 단위 테스트합니다 (32개).

```bash
./gradlew testDebugUnitTest
```

| 테스트 | 검증 내용 |
|---|---|
| `OrderSchedulerTest`, `OrderFlowTest` | 전송 시각 계산, 정체 시 연기, 이미 전송된 주문은 도착 시각만 갱신 |
| `RecommenderTest` | 영업시간·경로 이탈 거리·도착 전 조리 가능 여부·취향 태그 |
| `VoiceDialogTest` | 음성 대화 흐름(제안 → 네/아니요 → 다음 후보, 취소, 못 알아들음 2회 종료) |
| `PickupWaypointTest` | 내비 경유지 목록에서 주문 매장 찾기 |
| `DemoDriveTest` | 데모 주행 중 전송 시각 고정, 정체 시 연기 |

에뮬레이터에서 확인한 것: 내비 경로·ETA 수신, 경유지 추가와 경유지 ETA 수신, TTS 재생과 STT 듣기 시작,
음성 흐름(대체 버튼 입력) → 주문 → 경유지 추가, 백그라운드에서 정해진 시각에 `sent` 전환.

## 프로젝트 구조

```
app/src/main/java/com/example/myapplication/
├── MainActivity.kt          화면, NaviHelper·STT·TTS 연결, 5초 조회 루프
├── Recommender.kt           경로 위 메뉴 추천 (규칙 기반)
├── OrderScheduler.kt        전송 시각 계산·재계산
├── Order.kt                 주문 데이터와 상태
├── VoiceDialog.kt           음성 대화 흐름과 문장 해석
├── PickupWaypoint.kt        경유지 ↔ 주문 매장 매칭
├── OrderTrackingService.kt  주문 진행 중 백그라운드 동작 유지 (포그라운드 서비스)
├── DemoDrive.kt             데모용 가상 주행
├── FirestoreRest.kt         Firestore REST 클라이언트
└── StoreJson.kt             JSON → 매장 객체
store-web/index.html         가상 매장 주문 접수 화면
seed/stores_seed.json        가상 매장·메뉴 데이터
firestore.rules              Firestore 보안 규칙
```

## 알려진 한계

- **에뮬레이터에서는 차가 움직이지 않습니다.** 에뮬레이터가 보내는 GPS(NMEA) 문장의 체크섬이 항상 `*47`로 고정되어 내비가 위치를 무시합니다. 그래서 남은 시간이 줄지 않고 전송 시각이 계속 밀립니다. 시연에는 **데모 주행**(경과 시간만큼 남은 시간을 줄이는 가상 주행)을 씁니다. 실제 차량에서는 끄고 사용합니다.
- **결제는 아직 없습니다.** PG 테스트 모드(토스페이먼츠) 연동은 2차 계획입니다.
- **인증이 없습니다.** 보안 규칙으로 문서 모양과 상태 순서는 제한하지만, 누가 쓰는지는 구분하지 못합니다. 실서비스에는 Firebase Auth가 필요합니다.
- 음성 문장 해석은 키워드 규칙 기반입니다(LLM SDK는 에뮬레이터 v2.1.0에서 제공 종료).
- 에뮬레이터에서는 실제 목소리 인식을 검증하지 못했습니다(마이크 입력 없이 대체 버튼으로 확인).

## 로드맵 (2차)

- PG 테스트 결제(빌링키, 마지막 음성 확인 후 결제)
- 배달 주문: 목적지 도착 시각에 맞춘 배달
- 매장 접근 알림(지오펜스), 주행/정차 화면 분리(Vehicle SDK)
- 탑승 인원에 맞춘 추천(Vehicle 착좌 정보)

---

팀명 미정 · 영남대학교

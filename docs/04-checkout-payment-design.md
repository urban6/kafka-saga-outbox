# 결제창 방식 전환 설계

빌링키 자동청구를 **PG 결제창(인증 결제)** 방식으로 바꾼다. 고객이 상품 목록에서 결제하기를 누르면 결제창에서 인증하고, 서버가 PG 승인(confirm)을 호출한다.

## 확정한 결정

| # | 결정 |
|---|---|
| Q1 | 결제 모델은 결제창 방식. 빌링키 코드는 제거한다 |
| Q2' | 목록 가격 대조는 하지 않는다. 결제창에 최종 금액이 표시된다 |
| Q3 | binlog 보관 14일 > outbox 보관 7일 > Stuck 임계값. 재발행 도구는 남은 과제 |
| Q5 | confirm은 payment가 직접 받는다. 서비스 간 동기 호출은 만들지 않는다 |
| Q6 | order가 Outbox로 결제 준비 커맨드를 보내고, payment가 `READY` 행을 만든다 |
| Q7 | confirm 중 PG 타임아웃은 `202`로 응답하고, 클라이언트가 주문 조회를 폴링한다 |
| Q8 | 만료는 payment가 판정한다. `READY`에서 조건부 UPDATE로 승인과 만료 중 하나만 성공한다 |
| Q9 | 요청 금액이 `READY` 행 금액과 다르면 PG를 호출하지 않고 `400`으로 거절한다 |
| Q10 | 중복 confirm은 조건부 UPDATE로 막고, PG에는 `orderNo`를 멱등키로 보낸다 |
| Q11 | 데모는 Toss 샌드박스, 장애 재현은 Mock PG를 쓴다. 프로필로 전환한다 |
| Q12 | UI는 정적 HTML·JS 세 화면. nginx가 정적 파일 제공과 API 라우팅을 맡는다 |

## 전체 흐름

```text
[UI]            [nginx]          [order]                         [payment]                [PG]
 상품 목록 ───── GET /api/products → 상품 조회
 결제하기 ────── POST /api/orders → 주문 PENDING, 재고 예약,
                                   saga(AWAIT_PAYMENT),
                                   outbox(PREPARE_PAYMENT) ─커밋─┐
   ← 202 {orderNo, amount, expiresAt}                           │ Debezium
                                                                └→ payment READY 행 생성
 결제창 호출 ──────────────────────────────────────────────────────────────────────→ 고객 인증
   ← successUrl?paymentKey&orderId&amount ←───────────────────────────────────────────┘
 confirm ─────── POST /api/payments/confirm ──────────────────→ ① READY→IN_PROGRESS (커밋)
                                                                ② PG confirm (트랜잭션 밖) ──→ 승인
                                                                ③ DONE + outbox(PAYMENT_APPROVED)
   ← 200 DONE / 202 확인 중                                         │ Debezium
                                   saga 완료, 재고 확정 ←───────────┘
 결과 화면 ───── GET /api/orders/{orderNo} 폴링
```

결제창을 닫고 떠난 경우는 다음과 같이 정리된다.

```text
payment 만료 배치: READY 이고 expires_at 경과 → EXPIRED + outbox(PAYMENT_EXPIRED)
order: 재고 반환, 주문 EXPIRED, 사가 CANCELED
```

## 핵심 설계

### 1. Outbox는 양방향에 그대로 남는다

| 방향 | 메시지 | 같은 트랜잭션에 묶이는 것 |
|---|---|---|
| order → payment | `PREPARE_PAYMENT` (orderNo, customerId, amount, expiresAt) | 주문 저장, 재고 예약, 사가 시작 |
| payment → order | `PAYMENT_APPROVED` / `PAYMENT_REJECTED` / `PAYMENT_EXPIRED` | 결제 상태 확정 |

`APPROVE_PAYMENT`를 `PREPARE_PAYMENT`로 바꾼다. order가 payment에게 "이 금액을 이 시각까지 받을 준비를 하라"고 지시하는 커맨드다. 토픽은 그대로 `payment.commands`를 쓰므로 리스너, DLT, 멱등 처리 구조를 재사용한다.

### 2. payment 상태 전이

```text
           PREPARE_PAYMENT 수신
                  │
                READY ──── 만료 배치 (expires_at 경과) ───→ EXPIRED   → PAYMENT_EXPIRED
                  │
     confirm (금액 일치, 만료 전)
                  │
             IN_PROGRESS ── PG 승인 ──────→ DONE      → PAYMENT_APPROVED
                  │  └──── PG 거절 ──────→ ABORTED   → PAYMENT_REJECTED
                  │  └──── PG 재시도 오류 → READY     (회신 없음, 클라이언트 재시도)
                  └─ 타임아웃·미분류 오류 → IN_PROGRESS 유지 → InDoubtRecoveryService가 조회로 확정
```

- 모든 전이는 `WHERE status = :from` 조건부 UPDATE다. 영향 행이 0이면 다른 쪽이 먼저 바꾼 것이다.
- `IN_PROGRESS`는 "confirm 호출 중"과 "결과 모름"을 함께 뜻한다. ①을 커밋한 직후 서버가 죽어도 행은 `IN_PROGRESS`로 남는다. in-doubt 복구 배치가 PG에 조회해 확정하므로 별도 처리가 필요 없다. 다만 `grace`(30초)가 PG `read-timeout`(3초)보다 길어야, 아직 진행 중인 confirm을 배치가 건드리지 않는다.
- PG 재시도 가능 오류(`PROVIDER_ERROR` 등)는 돈이 움직이지 않은 경우다. `READY`로 되돌리고 `503`을 응답하면 클라이언트가 다시 시도할 수 있다.
- confirm 단계에서 거절되면(잔액 부족 등) 주문을 취소한다. 같은 주문으로 다시 결제하는 기능은 만들지 않는다. 고객은 새로 주문한다.

### 3. confirm API

`POST /api/payments/confirm` `{ paymentKey, orderId, amount }`

| 상황 | PG 호출 | 응답 |
|---|---|---|
| `READY` 행 없음 (`PREPARE_PAYMENT` 미도착) | 안 함 | `409 PAYMENT_NOT_READY`. 클라이언트가 1초 간격으로 몇 번 재시도한다 |
| 금액 불일치 | 안 함 | `400 AMOUNT_MISMATCH`. `READY` 유지, 경고 로그 |
| `expires_at` 경과 | 안 함 | `410 PAYMENT_EXPIRED` |
| 이미 `IN_PROGRESS`/`DONE`/`ABORTED` (중복 confirm) | 안 함 | `200`, 현재 상태 |
| 승인 | 함 | `200 DONE` |
| 거절 | 함 | `200 ABORTED`, 실패 사유 포함 |
| 타임아웃·결과 모름 | 함 | `202 IN_PROGRESS`. UI는 주문 조회를 폴링한다 |
| 재시도 가능 오류 | 함 | `503`. `READY`로 되돌림 |

① `READY → IN_PROGRESS` 전이 조건에 `expires_at > now`를 함께 건다. 만료 판정과 승인 시작이 같은 문장 안에서 갈리므로 둘 사이에 끼어들 틈이 없다.

PG는 confirm 호출 전에는 돈을 확정하지 않는다. 인증만 하고 confirm을 받지 못한 결제는 PG가 일정 시간 뒤 무효 처리한다. 그래서 만료로 정리된 주문에는 환불 보상이 필요 없다.

### 4. 만료

- 만료 시각은 주문 생성 후 15분이다. order가 계산해 `PREPARE_PAYMENT`에 싣는다. 판정에 쓰는 시계는 payment 하나다.
- payment 만료 배치(`PaymentExpiryService`)는 `status = READY AND expires_at < now`인 행을 건별 트랜잭션으로 `EXPIRED`로 바꾸고 `PAYMENT_EXPIRED`를 Outbox에 적재한다.
- order는 `PAYMENT_EXPIRED`를 받으면 거절과 같은 보상(재고 반환)을 하고, 주문 상태는 `EXPIRED`로 둔다. UI가 "카드 거절"과 "결제 안 함"을 구분해 보여 주기 위해서다.
- **Debezium이 멈춘 경우**: `PREPARE_PAYMENT`가 늦게 도착하면 `READY` 행이 이미 만료 시각을 넘긴 상태로 만들어진다. 다음 만료 배치가 곧바로 만료시키므로, Debezium이 재개되면 별도 조치 없이 정리된다.

### 5. order 변경

| 대상 | 변경 |
|---|---|
| `SagaStep` | `APPROVE_PAYMENT` → `AWAIT_PAYMENT` |
| `OrderStatus` | `EXPIRED` 추가 |
| `EventType` | `APPROVE_PAYMENT` → `PREPARE_PAYMENT`, 회신에 `PAYMENT_EXPIRED` 추가 |
| `OrderSagaOrchestrator.decide` | `PAYMENT_EXPIRED` → 보상. 주문 전이 대상만 `EXPIRED` |
| 도메인 이벤트 | 만료도 `ORDER_CANCELED`로 발행하고, payload의 `status`가 `EXPIRED`로 구분한다 |
| `PlaceOrderResponse` | `expiresAt` 추가 (UI 표시용) |
| `ProductController` | `GET /api/products` 추가 |
| Stuck 임계값 | `AWAIT_PAYMENT: 20m`. 만료 15분 + 만료 배치 주기 + 여유 |

Stuck 임계값이 60초에서 20분으로 늘어나므로, Debezium 정지를 알아채는 시간도 그만큼 늦어진다. Debezium 정지는 사가 정체로 간접 감지하기보다 커넥터 지표(`MilliSecondsBehindSource`)로 직접 보는 게 맞다. 이 부분은 남은 과제로 둔다.

### 6. PG 연동

| 프로필 | base-url | 인증 | 결제창 |
|---|---|---|---|
| 기본 (mock) | `http://localhost:8082` | 없음 | Mock PG의 `GET /mockpg/checkout` 페이지 |
| `toss` | `https://api.tosspayments.com` | `Authorization: Basic base64(secretKey:)` | Toss 결제위젯 SDK |

- `PgClient.charge` → `PgClient.confirm(paymentKey, orderNo, amount)`. `POST /v1/payments/confirm`, `Idempotency-Key: orderNo`. 에러 코드 분류 구조(`map`)와 `reconcile`은 그대로 쓴다.
- 시크릿 키는 환경변수 `TOSS_SECRET_KEY`로만 주입한다. 저장소에 커밋하지 않는다. UI의 클라이언트 키는 테스트 키(`test_ck_...`)라 공개돼도 된다.
- Mock PG에 추가할 것: 결제창 흉내 페이지(승인 버튼, 취소 버튼), `POST /v1/payments/confirm`. 기존 장애 주입(`MockPgFaults`)을 confirm에도 적용한다.

### 7. UI와 nginx

```text
ui/
  index.html     상품 목록, 수량 선택, 결제하기
  success.html   successUrl. confirm 호출, 202면 주문 조회 폴링, 결과 표시
  fail.html      failUrl. 결제창 취소·인증 실패 안내
  app.js         API 호출, PG 모드(mock/toss) 분기
```

- 로그인은 없다. `customerId`는 화면 상단에서 선택한다.
- nginx는 docker-compose에 추가한다. `/`는 `ui/` 정적 파일, `/api/orders`와 `/api/products`는 `host.docker.internal:8081`, `/api/payments`는 `host.docker.internal:8082`로 보낸다. 모든 요청이 같은 출처라 CORS 설정이 필요 없다.

### 8. 데이터 변경

```sql
-- payment_db.payment
ALTER: status 값에 READY, EXPIRED 추가 (VARCHAR 라 DDL 변경 없음)
ADD   expires_at DATETIME(6) NOT NULL
ADD   KEY idx_expiry (status, expires_at)

-- 제거
DROP TABLE billing_key
```

```yaml
# docker-compose.yml
--binlog-expire-logs-seconds=1209600   # 14일
```

## 제거하는 것

빌링키 등록 API, `BillingKey`, `RegisterBillingKeyService`, `billing_key` 테이블, `MockBillingController`, `PaymentController`의 HTTP 직접 승인(`POST /api/payments`), `ApprovePaymentCommand`. 코드는 git 기록에 남는다.

README의 장애 재현 결과는 빌링 흐름에서 측정한 값이다. 새 흐름에서 다시 측정해 교체한다.

## 작업 순서

각 단계는 1~3개 파일이고, 단계마다 테스트로 확인하고 넘어간다.

1. **DDL과 설정**: `payment.expires_at`, 인덱스, binlog 14일 → 검증: `./gradlew integrationTest`가 DDL 검증(`ddl-auto: validate`)을 통과한다.
2. **payment: `PREPARE_PAYMENT` 수신 → `READY` 행 생성** → 검증: 같은 메시지를 두 번 보내도 행이 하나다.
3. **order: 커맨드 교체** (`PlaceOrderService`, `EventType`, `SagaStep`) → 검증: 주문하면 outbox에 `PREPARE_PAYMENT`가 남는다.
4. **payment: confirm API + `PgClient.confirm` + Mock PG confirm** → 검증: 표 3의 응답을 하나씩 테스트한다(금액 불일치, 중복, 타임아웃).
5. **payment: 만료 배치 + order: `PAYMENT_EXPIRED` 처리** → 검증: confirm과 만료가 동시에 실행돼도 둘 중 하나만 성공한다.
6. **Mock PG 결제창, 상품 목록 API, nginx, UI** → 검증: 브라우저에서 주문부터 완료까지 진행된다.
7. **`toss` 프로필** → 검증: Toss 테스트 카드로 결제가 완료된다.
8. **빌링키 코드 제거, README 장애 재현 재측정**

## 남은 과제

- outbox 재발행 도구 (Q3)
- Debezium 지연 지표 기반 알람
- 결제 취소·환불 (승인 후 고객 취소)
- Toss에서 인증 실패 후 같은 `orderId`로 결제창을 다시 열 수 있는지 확인. 가능하면 UI에 "다시 결제"를 추가한다

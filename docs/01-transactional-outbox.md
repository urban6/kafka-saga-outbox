# Outbox Pattern

주문 저장과 Kafka 메시지 발행은 서로 다른 시스템에서 이루어지므로, 하나의 DB 트랜잭션으로 원자성을 보장할 수 없다.

- **커밋 전에 발행하면:** 메시지는 전달됐지만 주문 저장은 롤백될 수 있다.
- **커밋 후에 발행하면:** 주문은 저장됐지만 서버 종료나 발행 실패로 메시지가 전달되지 않을 수 있다.

Outbox Pattern은 **주문과 발행할 메시지를 같은 DB 트랜잭션으로 저장**해 이 문제를 해결한다. 실제 Kafka 발행은 커밋 이후 별도로 진행한다.

```text
[DB 트랜잭션]
주문 저장 + Outbox에 메시지 저장
              ↓ 커밋
Debezium이 binlog에서 Outbox 변경 감지
              ↓
Kafka로 메시지 발행
```

## 메시지 발행 방식

| 방식 | 동작 | 예시 |
|---|---|---|
| 폴링 | 일정 주기로 Outbox 테이블을 조회 | JDBC Source Connector, 직접 구현한 발행기 |
| 로그 기반 CDC | DB 변경 로그에서 Outbox 변경을 감지 | Debezium |

이 프로젝트는 **Debezium이 MySQL binlog를 읽는 로그 기반 CDC 방식**을 사용한다. Kafka Connect가 전송과 처리 위치 저장을 관리하고, Outbox Event Router가 토픽·키·본문을 구성한다.

두 방식 모두 장애 복구 과정에서 중복 발행될 수 있으므로, 소비자는 멱등성을 갖춰야 한다.

## 현재 Outbox 테이블 구조

| 컬럼 | 타입 | 역할 |
|---|---|---|
| `id` | `CHAR(36)` | 메시지 ID(UUID)이자 기본 키. `eventId`와 같으며 중복 처리 방지에 사용 |
| `aggregate_type` | `VARCHAR(64)` | 관련 도메인 종류. 예: `order`, `payment` |
| `aggregate_id` | `VARCHAR(64)` | 관련 주문번호. Kafka 메시지 키로 사용 |
| `event_type` | `VARCHAR(100)` | 메시지 종류. 예: `APPROVE_PAYMENT`, `PAYMENT_APPROVED` |
| `topic` | `VARCHAR(100)` | 발행할 Kafka 토픽. 예: `payment.commands` |
| `payload` | `JSON` | 메시지 ID·종류·버전과 전달할 데이터를 포함한 본문 |
| `created_at` | `DATETIME(6)` | 생성 시각. 메시지 타임스탬프와 기록 정리에 사용 |

발행 상태 컬럼은 없다. Debezium의 처리 위치는 Kafka Connect의 오프셋으로 저장하며, Outbox에 발행 완료 여부를 갱신하지 않는다.

Outbox 구조는 고정된 규격이 아니다. 이 프로젝트는 커넥터 설정으로 컬럼을 매핑하고 `topic` 값으로 발행할 토픽을 정한다. [Debezium 2.7 Outbox Event Router 규약](https://debezium.io/documentation/reference/2.7/transformations/outbox-event-router.html#basic-outbox-table)

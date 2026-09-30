# Kafka 구성 요소

## 메시지 처리

| 용어 | 의미 | 현재 프로젝트 예시 |
|---|---|---|
| **Kafka Broker** | 메시지를 저장하고 생산자·소비자의 요청을 처리하는 서버 | `kafka` 컨테이너 |
| **Topic** | 메시지를 종류별로 구분하는 저장 단위 | `payment.commands` |
| **Partition** | 토픽을 나눈 단위. 파티션 내부에서 기록 순서를 유지 | 토픽당 3개 |
| **Producer** | Kafka에 메시지를 발행하는 주체 | Kafka Connect가 사용하는 생산자 |
| **Consumer** | Kafka 메시지를 읽는 주체 | 결제 요청을 처리하는 payment 서비스 |
| **Consumer Group** | 파티션을 나눠 맡는 소비자들의 그룹 | `payment-service` |

## 외부 시스템 연동

| 용어 | 의미 | 현재 프로젝트 예시 |
|---|---|---|
| **Kafka Connect** | Kafka와 외부 시스템 사이의 데이터 이동을 실행·관리하는 프레임워크 | Debezium 커넥터 실행과 오프셋 관리 |
| **Worker** | Kafka Connect를 실행하는 프로세스 | `kafka-connect` 컨테이너 안의 프로세스 |
| **Connector Plugin** | 특정 시스템에 연결하는 방법을 구현한 코드 | Debezium MySQL 플러그인 |
| **Connector** | 플러그인에 설정을 적용해 등록한 연동 작업. Task를 구성 | `order-outbox-connector` |
| **Task** | 실제 데이터를 읽거나 쓰는 작업 단위 | MySQL binlog에서 Outbox 변경 읽기 |
| **Source Connector** | 외부 시스템 → Kafka 방향의 커넥터 | Debezium MySQL 커넥터 |
| **Sink Connector** | Kafka → 외부 시스템 방향의 커넥터 | 현재 사용하지 않음 |
| **Connect Cluster** | Distributed 모드에서 Task를 분산 실행하고 장애 시 인계하는 Worker들의 집합 | 현재 Worker 1개 |

## 데이터 흐름

```text
주문 서비스
    ↓ 주문과 Outbox를 같은 트랜잭션으로 저장·커밋
MySQL binlog
    ↓
Kafka Connect Worker
    ├─ Debezium 커넥터의 Task: 변경 수집
    ├─ Outbox Event Router: 메시지 변환
    └─ Kafka 전송과 Source 오프셋 저장
         ↓
Kafka Broker
    └─ payment.commands 토픽
         ↓
결제 서비스의 Consumer
```

Worker가 종료되면 같은 Connect 클러스터의 다른 Worker가 Task를 넘겨받을 수 있다. **Worker를 늘려도 하나의 Task가 자동으로 나뉘지는 않는다.** Debezium MySQL 커넥터 하나의 binlog 읽기는 단일 Task가 담당한다.

참고: [Kafka Connect 사용 안내](https://kafka.apache.org/36/kafka-connect/user-guide/), [Connector와 Task 설명](https://kafka.apache.org/35/kafka-connect/connector-development-guide/)

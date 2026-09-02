-- ── Order 스키마 ─────────────────────────────────────────

CREATE TABLE orders (
                        id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        order_no        VARCHAR(64)  NOT NULL,
                        customer_id     VARCHAR(64)  NOT NULL,
                        total_amount    DECIMAL(19,4) NOT NULL,
                        status          VARCHAR(32)  NOT NULL,
                        version         BIGINT       NOT NULL DEFAULT 0,
                        created_at      DATETIME(6)  NOT NULL,
                        updated_at      DATETIME(6)  NOT NULL,
                        UNIQUE KEY uk_order_no (order_no),
                        KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB;

CREATE TABLE saga_instance (
                               saga_id         VARCHAR(64)  NOT NULL PRIMARY KEY,
                               order_no        VARCHAR(64)  NOT NULL,
                               current_step    VARCHAR(64)  NOT NULL,
                               status          VARCHAR(32)  NOT NULL,  -- RUNNING/COMPENSATING/COMPLETED/FAILED
                               payload         JSON         NOT NULL,  -- 보상에 필요한 컨텍스트(예약ID, 결제ID)
                               step_started_at DATETIME(6)  NOT NULL,
                               last_error      TEXT         NULL,
                               version         BIGINT       NOT NULL DEFAULT 0,
                               created_at      DATETIME(6)  NOT NULL,
                               updated_at      DATETIME(6)  NOT NULL,
                               UNIQUE KEY uk_order_no (order_no),
                               KEY idx_stuck (status, step_started_at)   -- Stuck Saga 탐지용
) ENGINE=InnoDB;


-- ── 공통(각 서비스 스키마에 동일 생성) ────────────────────

CREATE TABLE outbox (
                        id              CHAR(36)     NOT NULL PRIMARY KEY,  -- EventEnvelope.eventId
                        aggregate_type  VARCHAR(64)  NOT NULL,              -- 라우팅 메타데이터(헤더)
                        aggregate_id    VARCHAR(64)  NOT NULL,              -- 카프카 메시지 키(orderNo)
                        event_type      VARCHAR(100) NOT NULL,
                        topic           VARCHAR(100) NOT NULL,              -- route.by.field 대상
                        payload         JSON         NOT NULL,              -- 직렬화된 EventEnvelope
                        created_at      DATETIME(6)  NOT NULL,
                        KEY idx_created (created_at)                        -- 보관주기 지난 행 정리용
) ENGINE=InnoDB;

CREATE TABLE consumed_message (
                                  message_id      CHAR(36)     NOT NULL,
                                  consumer_group  VARCHAR(100) NOT NULL,
                                  event_type      VARCHAR(100) NOT NULL,
                                  processed_at    DATETIME(6)  NOT NULL,
                                  PRIMARY KEY (message_id, consumer_group),
                                  KEY idx_processed (processed_at)      -- 보관주기 지난 행 정리용
) ENGINE=InnoDB;

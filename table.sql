-- ── Order 스키마 ─────────────────────────────────────────

CREATE TABLE orders (
                        order_id        VARCHAR(64)  NOT NULL PRIMARY KEY,
                        customer_id     VARCHAR(64)  NOT NULL,
                        total_amount    DECIMAL(19,4) NOT NULL,
                        status          VARCHAR(32)  NOT NULL,
                        version         BIGINT       NOT NULL DEFAULT 0,
                        created_at      DATETIME(6)  NOT NULL,
                        updated_at      DATETIME(6)  NOT NULL,
                        KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB;

CREATE TABLE saga_instance (
                               saga_id         VARCHAR(64)  NOT NULL PRIMARY KEY,
                               order_id        VARCHAR(64)  NOT NULL,
                               current_step    VARCHAR(64)  NOT NULL,
                               status          VARCHAR(32)  NOT NULL,  -- RUNNING/COMPENSATING/COMPLETED/FAILED
                               payload         JSON         NOT NULL,  -- 보상에 필요한 컨텍스트(예약ID, 결제ID)
                               step_started_at DATETIME(6)  NOT NULL,
                               last_error      TEXT         NULL,
                               version         BIGINT       NOT NULL DEFAULT 0,
                               created_at      DATETIME(6)  NOT NULL,
                               updated_at      DATETIME(6)  NOT NULL,
                               UNIQUE KEY uk_order (order_id),
                               KEY idx_stuck (status, step_started_at)   -- Stuck Saga 탐지용
) ENGINE=InnoDB;


-- ── 공통(각 서비스 스키마에 동일 생성) ────────────────────

CREATE TABLE outbox (
                        id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        event_id        CHAR(36)     NOT NULL,
                        aggregate_id    VARCHAR(64)  NOT NULL,
                        topic           VARCHAR(100) NOT NULL,
                        partition_key   VARCHAR(64)  NOT NULL,
                        event_type      VARCHAR(100) NOT NULL,
                        event_version   INT          NOT NULL,
                        payload         JSON         NOT NULL,
                        headers         JSON         NULL,
                        status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
                        retry_count     INT          NOT NULL DEFAULT 0,
                        created_at      DATETIME(6)  NOT NULL,
                        sent_at         DATETIME(6)  NULL,
                        UNIQUE KEY uk_event (event_id),
                        KEY idx_poll (status, id)
) ENGINE=InnoDB;

CREATE TABLE consumed_message (
                                  message_id      CHAR(36)     NOT NULL,
                                  consumer_group  VARCHAR(100) NOT NULL,
                                  event_type      VARCHAR(100) NOT NULL,
                                  processed_at    DATETIME(6)  NOT NULL,
                                  PRIMARY KEY (message_id, consumer_group),
                                  KEY idx_processed_at (processed_at)      -- 보관주기 지난 행 정리용
) ENGINE=InnoDB;

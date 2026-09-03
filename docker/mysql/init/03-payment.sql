USE payment_db;

CREATE TABLE payment (
                         payment_id      VARCHAR(64)   NOT NULL,
                         order_no        VARCHAR(64)   NOT NULL,
                         amount          DECIMAL(19,4) NOT NULL,
                         status          VARCHAR(32)   NOT NULL,
                         payment_key     VARCHAR(128)  NULL,
                         failure_code    VARCHAR(64)   NULL,
                         failure_reason  VARCHAR(255)  NULL,
                         created_at      DATETIME(6)   NOT NULL,
                         updated_at      DATETIME(6)   NOT NULL,
                         PRIMARY KEY (payment_id),
                         UNIQUE KEY uk_order_no (order_no),
                         KEY idx_in_doubt (status, updated_at)
) ENGINE=InnoDB;

-- 고객이 등록한 카드의 빌링키. 고객당 하나 — 재등록은 덮어쓴다.
-- 카드번호는 PG 에만 보내고 여기엔 끝 4자리만 남는다(PCI 경계).
-- customer_id 는 orders.customer_id 와 같은 값이라 컬럼명을 맞춘다.
CREATE TABLE billing_key (
                             customer_id     VARCHAR(64)   NOT NULL,
                             billing_key     VARCHAR(128)  NOT NULL,
                             card_last4      CHAR(4)       NOT NULL,
                             created_at      DATETIME(6)   NOT NULL,
                             updated_at      DATETIME(6)   NOT NULL,
                             PRIMARY KEY (customer_id)
) ENGINE=InnoDB;

CREATE TABLE outbox (
                        id              CHAR(36)      NOT NULL,
                        aggregate_type  VARCHAR(64)   NOT NULL,
                        aggregate_id    VARCHAR(64)   NOT NULL,
                        event_type      VARCHAR(100)  NOT NULL,
                        topic           VARCHAR(100)  NOT NULL,
                        payload         JSON          NOT NULL,
                        created_at      DATETIME(6)   NOT NULL,
                        PRIMARY KEY (id),
                        KEY idx_created (created_at)
) ENGINE=InnoDB;

CREATE TABLE consumed_message (
                                  message_id      CHAR(36)      NOT NULL,
                                  consumer_group  VARCHAR(100)  NOT NULL,
                                  event_type      VARCHAR(100)  NOT NULL,
                                  processed_at    DATETIME(6)   NOT NULL,
                                  PRIMARY KEY (message_id, consumer_group),
                                  KEY idx_processed (processed_at)
) ENGINE=InnoDB;

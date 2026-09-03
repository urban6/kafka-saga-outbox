USE order_db;

CREATE TABLE orders (
                        id              BIGINT        NOT NULL AUTO_INCREMENT,
                        order_no        VARCHAR(64)   NOT NULL,
                        customer_id     VARCHAR(64)   NOT NULL,
                        total_amount    DECIMAL(19,4) NOT NULL,
                        status          VARCHAR(32)   NOT NULL,
                        created_at      DATETIME(6)   NOT NULL,
                        updated_at      DATETIME(6)   NOT NULL,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_order_no (order_no)
) ENGINE=InnoDB;

CREATE TABLE order_item (
                            id              BIGINT        NOT NULL AUTO_INCREMENT,
                            order_id        BIGINT        NOT NULL,
                            product_id      VARCHAR(64)   NOT NULL,
                            quantity        INT           NOT NULL,
                            unit_price      DECIMAL(19,4) NOT NULL,
                            PRIMARY KEY (id),
                            UNIQUE KEY uk_order_product (order_id, product_id)
) ENGINE=InnoDB;

CREATE TABLE product (
                         product_id          VARCHAR(64)   NOT NULL,
                         name                VARCHAR(255)  NOT NULL,
                         price               DECIMAL(19,4) NOT NULL,
                         total_quantity      INT           NOT NULL,
                         reserved_quantity   INT           NOT NULL DEFAULT 0,
                         created_at          DATETIME(6)   NOT NULL,
                         updated_at          DATETIME(6)   NOT NULL,
                         PRIMARY KEY (product_id)
) ENGINE=InnoDB;

CREATE TABLE saga_instance (
                               saga_id         CHAR(36)      NOT NULL,
                               order_no        VARCHAR(64)   NOT NULL,
                               current_step    VARCHAR(64)   NOT NULL,
                               status          VARCHAR(32)   NOT NULL,
                               payload         JSON          NOT NULL,
                               step_started_at DATETIME(6)   NOT NULL,
                               created_at      DATETIME(6)   NOT NULL,
                               updated_at      DATETIME(6)   NOT NULL,
                               PRIMARY KEY (saga_id),
                               UNIQUE KEY uk_order_no (order_no),
                               KEY idx_stuck (status, step_started_at)
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

-- API 멱등. 클라이언트가 만든 Idempotency-Key 로 같은 주문 요청의 재시도를 흡수한다.
-- consumed_message 와 달리 결과(order_no)를 함께 보관한다 — HTTP 는 중복이어도 응답을 돌려줘야 한다.
CREATE TABLE api_idempotency (
                                 idempotency_key VARCHAR(128)  NOT NULL,
                                 request_hash    CHAR(64)      NOT NULL,
                                 order_no        VARCHAR(64)   NOT NULL,
                                 created_at      DATETIME(6)   NOT NULL,
                                 PRIMARY KEY (idempotency_key),
                                 KEY idx_created (created_at)
) ENGINE=InnoDB;

INSERT INTO product (product_id, name, price, total_quantity, created_at, updated_at) VALUES
    ('P-1001', '기계식 키보드', 129000, 100, NOW(6), NOW(6)),
    ('P-1002', '무선 마우스',    45000, 100, NOW(6), NOW(6)),
    ('P-1003', '한정판 스피커', 299000,   2, NOW(6), NOW(6));

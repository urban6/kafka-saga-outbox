USE inventory_db;

CREATE TABLE product (
                         product_id          VARCHAR(64)   NOT NULL,
                         name                VARCHAR(255)  NOT NULL,
                         price               DECIMAL(19,4) NOT NULL,
                         total_quantity      INT           NOT NULL,
                         reserved_quantity   INT           NOT NULL DEFAULT 0,
                         version             BIGINT        NOT NULL DEFAULT 0,
                         created_at          DATETIME(6)   NOT NULL,
                         updated_at          DATETIME(6)   NOT NULL,
                         PRIMARY KEY (product_id)
) ENGINE=InnoDB;

CREATE TABLE stock_reservation (
                                   reservation_id  VARCHAR(64)   NOT NULL,
                                   order_no        VARCHAR(64)   NOT NULL,
                                   product_id      VARCHAR(64)   NOT NULL,
                                   quantity        INT           NOT NULL,
                                   status          VARCHAR(32)   NOT NULL,
                                   version         BIGINT        NOT NULL DEFAULT 0,
                                   created_at      DATETIME(6)   NOT NULL,
                                   updated_at      DATETIME(6)   NOT NULL,
                                   PRIMARY KEY (reservation_id),
                                   UNIQUE KEY uk_order_product (order_no, product_id),
                                   KEY idx_status (status, created_at)
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

INSERT INTO product (product_id, name, price, total_quantity, created_at, updated_at) VALUES
                                                                                          ('P-1001', '기계식 키보드', 129000, 100, NOW(6), NOW(6)),
                                                                                          ('P-1002', '무선 마우스',    45000, 100, NOW(6), NOW(6)),
                                                                                          ('P-1003', '한정판 스피커', 299000,   2, NOW(6), NOW(6));

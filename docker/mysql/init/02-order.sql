USE order_db;

CREATE TABLE orders (
                        id              BIGINT        NOT NULL AUTO_INCREMENT,
                        order_no        VARCHAR(64)   NOT NULL,
                        customer_id     VARCHAR(64)   NOT NULL,
                        total_amount    DECIMAL(19,4) NOT NULL,
                        status          VARCHAR(32)   NOT NULL,
                        version         BIGINT        NOT NULL DEFAULT 0,
                        created_at      DATETIME(6)   NOT NULL,
                        updated_at      DATETIME(6)   NOT NULL,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_order_no (order_no),
                        KEY idx_customer (customer_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE order_item (
                            id              BIGINT        NOT NULL AUTO_INCREMENT,
                            order_id        BIGINT        NOT NULL,
                            product_id      VARCHAR(64)   NOT NULL,
                            quantity        INT           NOT NULL,
                            unit_price      DECIMAL(19,4) NOT NULL,
                            PRIMARY KEY (id),
                            KEY idx_order (order_id)
) ENGINE=InnoDB;

CREATE TABLE saga_instance (
                               saga_id         CHAR(36)      NOT NULL,
                               order_no        VARCHAR(64)   NOT NULL,
                               saga_type       VARCHAR(64)   NOT NULL,
                               current_step    VARCHAR(64)   NOT NULL,
                               status          VARCHAR(32)   NOT NULL,
                               payload         JSON          NOT NULL,
                               step_started_at DATETIME(6)   NOT NULL,
                               retry_count     INT           NOT NULL DEFAULT 0,
                               last_error      TEXT          NULL,
                               version         BIGINT        NOT NULL DEFAULT 0,
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

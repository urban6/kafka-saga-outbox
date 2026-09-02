CREATE DATABASE IF NOT EXISTS order_db
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS payment_db
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'order_user'     IDENTIFIED BY 'order_pw';
CREATE USER 'payment_user'   IDENTIFIED BY 'payment_pw';

GRANT ALL PRIVILEGES ON order_db.*     TO 'order_user';
GRANT ALL PRIVILEGES ON payment_db.*   TO 'payment_user';

CREATE USER 'debezium' IDENTIFIED BY 'debezium_pw';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT
    ON *.* TO 'debezium';

FLUSH PRIVILEGES;

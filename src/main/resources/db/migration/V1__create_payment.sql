-- 결제 1건 = 1행. 청구 의도(charge_key)당 orderId는 하나이므로 두 컬럼 모두 UNIQUE.
CREATE TABLE payment
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_id        VARCHAR(64)  NOT NULL,
    charge_key      VARCHAR(100) NOT NULL,
    billing_key     VARCHAR(200) NOT NULL,
    customer_key    VARCHAR(300) NOT NULL,
    amount          BIGINT       NOT NULL,
    order_name      VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    pg_payment_key  VARCHAR(200) NULL,
    failure_code    VARCHAR(100) NULL,
    failure_message VARCHAR(500) NULL,
    requested_at    DATETIME(6)  NOT NULL,
    approved_at     DATETIME(6)  NULL,
    resolved_at     DATETIME(6)  NULL,
    version         BIGINT       NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_order_id UNIQUE (order_id),
    CONSTRAINT uk_payment_charge_key UNIQUE (charge_key),
    -- 확정 잡이 (REQUESTED, UNKNOWN) 중 오래된 건을 찾을 때 쓴다.
    INDEX idx_payment_status_requested_at (status, requested_at)
);

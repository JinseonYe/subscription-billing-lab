-- 구독 1건 = 1행. 결제(payment)와는 chargeKey 문자열로만 이어지고 FK는 두지 않는다. subscription 패키지는 payment를 모른다.
CREATE TABLE subscription
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    customer_key         VARCHAR(300) NOT NULL,
    -- mock 빌링키를 평문으로 둔다(ADR 0001 쟁점 2와 같다). 발급과 암호화는 빌링키 기능에서 한다.
    billing_key          VARCHAR(200) NOT NULL,
    amount               BIGINT       NOT NULL,
    order_name           VARCHAR(100) NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    -- 마지막으로 시작한 청구 주기와 그 주기의 시도 번호. 현재 시도의 chargeKey는 SUB-{id}-C{cycle_no}-A{attempt_no}다.
    cycle_no             INT          NOT NULL,
    attempt_no           INT          NOT NULL,
    -- 결제사로 보낼 수 있는 시도가 열려 있으면 그 chargeKey. 최종 결과(승인 또는 거절)를 반영할 때만 비운다.
    pending_charge_key   VARCHAR(100) NULL,
    -- 첫 결제의 승인 시각. 모든 주기의 기간은 이 앵커에서 계산한다. 첫 결제가 승인되기 전에는 없다.
    anchor_at            DATETIME(6)  NULL,
    period_start         DATETIME(6)  NULL,
    period_end           DATETIME(6)  NULL,
    -- PENDING에서는 해지 요청, ACTIVE에서는 해지 예약을 뜻한다.
    cancel_at_period_end BOOLEAN      NOT NULL,
    canceled_reason      VARCHAR(30)  NULL,
    retry_count          INT          NOT NULL,
    next_retry_at        DATETIME(6)  NULL,
    created_at           DATETIME(6)  NOT NULL,
    version              BIGINT       NOT NULL,
    -- CANCELED가 아닌 구독만 customer_key를 갖는다. UNIQUE는 NULL을 여러 개 허용하므로,
    -- 고객당 live(PENDING, ACTIVE, PAST_DUE) 구독은 하나만 강제하고 CANCELED는 얼마든지 쌓인다.
    -- 엔티티에는 매핑하지 않는다. ddl-auto=validate는 매핑하지 않은 컬럼을 무시한다.
    live_customer_key    VARCHAR(300) GENERATED ALWAYS AS (IF(status = 'CANCELED', NULL, customer_key)) VIRTUAL,
    PRIMARY KEY (id),
    CONSTRAINT uk_subscription_live_customer UNIQUE (live_customer_key),
    -- 송신 중인 구독은 CANCELED가 될 수 없다. 엔티티 가드와 별개로 DB도 막는다.
    CONSTRAINT chk_subscription_canceled_no_pending CHECK (status <> 'CANCELED' OR pending_charge_key IS NULL),
    -- 해지 사유는 CANCELED일 때만 있다.
    CONSTRAINT chk_subscription_canceled_reason CHECK ((status = 'CANCELED') = (canceled_reason IS NOT NULL)),
    -- 신청 때 고객의 live 구독을 찾는다. CANCELED가 쌓여도 느려지지 않게 한다.
    INDEX idx_subscription_customer_key (customer_key)
);

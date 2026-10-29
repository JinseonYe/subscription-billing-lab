# 설계 검토 1단계: design-idealist

- 대상: 결제 1건 흐름 + Mock PG 설계 초안
- 규칙 기준: `CLAUDE.md`
- 날짜: 2026-10-29
- 역할: 일정 제약이 없다고 보고 이상적인 구조를 제시한다. 실현 가능성은 다음 단계(pessimist, realist)가 판단한다.
- 전제: 초안의 "토스 공식 문서 확인 결과"는 사실로 본다. 멱등키 유효기간 15일, `DUPLICATED_ORDER_ID`와 `IDEMPOTENT_REQUEST_PROCESSING`은 Unknown, read-timeout 65초, lookup 결과 `NOT_FOUND`와 `NOT_FOUND_PAYMENT`는 NotFound로 처리한다.

---

## 요약

초안의 뼈대는 맞다. 기록 커밋, 트랜잭션 밖 호출, 결과 반영으로 이어지는 흐름, 멱등키 재전송, 그리고 `@Version`으로 경합을 막는 방식이 여기에 해당한다. 다만 아래 세 가지가 아직 사람의 기억이나 시간 가정에 기대고 있다.

1. "한 청구에 진행 중인 시도는 최대 1개, 승인은 최대 1건"이라는 불변식. 지금은 `attempt_no`가 1로 고정이어서 우연히 지켜질 뿐이다.
2. "원래 요청과 확정 잡이 겹치지 않는다"는 전제. 근거가 5분이라는 시간 상수뿐이다.
3. "결제사가 실제로 무엇을 했는가"에 대한 기록. PgResult로 변환되고 나면 남지 않는다.

아래 제안 5개는 이 세 가지를 구조로 보장하는 방향이다.

| # | 제안 | 주된 관점 |
|---|---|---|
| 1 | 청구 의도(Charge)와 결제 시도(PaymentAttempt)를 분리하고, 불변식을 DB 제약으로 둔다 | 도메인 경계 |
| 2 | 상태 모델을 정교하게 나눈다: 전송 여부, 종결 사유, 사람 확인 상태 | 상태 모델 |
| 3 | 결제사 관측 원장과 대사 이슈 테이블을 둔다 | 정산 대사 |
| 4 | 시간 분리 대신 행 단위 선점(lease)으로 경합을 푼다 | 다중 인스턴스 |
| 5 | 재전송, 재시도, 요금 결정을 순수 정책 객체로 분리하고 요청을 스냅샷으로 고정한다 | 요금제 정책 분리 |

---

## 제안 1. 청구 의도와 결제 시도의 분리, 불변식은 DB에 둔다

**현재 설계**
- `payment` 테이블 하나가 두 개념을 함께 맡는다. 하나는 "청구 의도"(`charge_key`, 같은 청구를 가리키는 키)이고, 다른 하나는 "결제 시도"(`order_id`, 시도마다 새로 만드는 키)다.
- `UNIQUE(charge_key, attempt_no)`는 같은 번호의 시도가 두 번 생기는 것만 막는다. 시도 1이 UNKNOWN인 상태에서 시도 2가 만들어지는 것은 막지 못하고, 그러면 이중 청구가 된다. 지금은 `attempt_no`가 항상 1이라 문제가 드러나지 않을 뿐, 구독 기능에서 재시도가 들어오는 순간 이 불변식은 애플리케이션 코드가 지켜야 한다.

**이상적인 설계**
- `charge`(청구 의도) 테이블. 소유 패키지는 `charge`다.
  - `charge_key UNIQUE`, `amount`, `order_name`, `billing_key_id`, `status`(`OPEN` / `SETTLED` / `CANCELLED` / `EXHAUSTED`), `current_attempt_id`, `version`
- `payment_attempt` 테이블. 소유 패키지는 `payment`다.
  - `order_id UNIQUE`, `charge_id`, `attempt_no`, 요청 스냅샷(제안 5), `status`
  - `payment` 패키지는 `charge`를 모른다. `charge_id`는 `Long` 값으로만 들고, 연결은 DB FK가 맡는다. 그래서 기존 ArchUnit 규칙("payment는 charge에 의존하지 않는다")을 그대로 지킬 수 있다.
- 불변식은 두 겹으로 보장한다.
  - 애플리케이션: 새 시도는 `charge` 행을 `SELECT ... FOR UPDATE`로 잠근 상태에서, 현재 시도가 "결제사가 확정 거절"(제안 2의 `DECLINED`)이거나 "한 번도 보내지 않음"(`ABANDONED`)일 때만 만든다.
  - DB: MySQL 8.4의 함수 기반 유니크 인덱스로 한 번 더 막는다.

```sql
-- 한 청구에 종결되지 않은 시도는 최대 1개
CREATE UNIQUE INDEX uk_attempt_open_per_charge ON payment_attempt
  ((CASE WHEN status NOT IN ('DECLINED','ABANDONED') THEN charge_id END));
-- 한 청구에 승인은 최대 1건
CREATE UNIQUE INDEX uk_attempt_approved_per_charge ON payment_attempt
  ((CASE WHEN status = 'APPROVED' THEN charge_id END));
```

- 구독은 `charge_key`와 `charge.status`만 본다. 그러면 "subscription과 payment는 서로를 직접 참조하지 않는다"는 규칙이 데이터 모델에도 그대로 나타난다.

**얻는 것**
- 이중 청구 0이 코드 리뷰가 아니라 DB 제약으로 보장된다. 재시도 정책을 잘못 짜도 INSERT가 실패할 뿐 돈이 나가지 않는다.
- 쟁점 4(`attempt_no`)와 쟁점 5(해지 확인)에 자연스러운 자리가 생긴다. 의도를 취소하면 그 의도에 속한 시도는 더 보내지 않는다.
- 정산 대사의 단위가 분명해진다. 구독 한 주기는 `charge` 하나다. "이 주기에 돈을 받았는가"는 시도 여러 개를 모아 따질 필요 없이 `charge.status`로 답한다.

**비용**
- 테이블과 엔티티가 2개로 늘고, 결과 반영 트랜잭션(TX2)에서 두 행을 갱신한다(시도 APPROVED, 의도 SETTLED).
- 함수 기반 인덱스는 JPA `ddl-auto=validate`가 검증하지 않는다. 마이그레이션 테스트로 따로 확인해야 한다.
- Kotlin과 Spring을 처음 쓰는 사용자에게는 개념이 하나 더 생긴다.

---

## 제안 2. 상태 모델 정교화: 전송 여부, 종결 사유, 사람 확인 상태

**현재 설계**
- 상태는 `REQUESTED / APPROVED / FAILED / UNKNOWN` 4개다.
- `REQUESTED`만 보고는 "아직 보내지 않음"인지 "보냈는데 응답 전에 죽음"인지 구분할 수 없다.
- `FAILED`만 보고는 "결제사가 거절함"인지 "우리가 포기함"인지 구분할 수 없다. 포기 경로는 쟁점 1과 6의 결론에 따라 반드시 생긴다.
- 확정 잡이 포기할 때 갈 상태가 없다.
- lookup 결과 중 `READY`, `IN_PROGRESS`, `CANCELED`, `PARTIAL_CANCELED`, `WAITING_FOR_DEPOSIT`의 매핑이 비어 있다.

**이상적인 설계**

```
PREPARED ──(dispatch TX)──> DISPATCHED ─┬─> APPROVED
   │                                     ├─> DECLINED          (결제사가 확정 거절)
   └─> ABANDONED (보낸 적 없음)          └─> UNKNOWN ─┬─> APPROVED / DECLINED
                                                      └─> NEEDS_REVIEW ─> APPROVED / DECLINED (사람 또는 늦은 lookup)
```

- `PREPARED`는 시도를 미리 만들어 둔 상태다. 이후 구독 스케줄러가 주기별 시도를 일괄로 만드는 경우가 여기에 해당한다. 보내기 직전에 쟁점 5의 확인을 거쳐 `DISPATCHED`로 바꾼다. `ABANDONED`로 끝난 시도는 결제사가 받은 적이 없으므로 청구 위험이 없다.
- 원래 흐름(지금 범위)에서는 TX1이 곧바로 `DISPATCHED`로 INSERT한다. 따라서 이번 기능에서 트랜잭션이 늘지 않는다.
- `FAILED`는 `DECLINED`와 `ABANDONED`로 나눈다. 자동 처리에서 `UNKNOWN`이 `FAILED`로 가는 전이는 두지 않는다.
- `NEEDS_REVIEW`는 종결 상태가 아니다. 다만 자동 재전송 대상에서 빠지고, 저빈도 lookup으로만 감시한다.
- 확정 잡용 컬럼을 추가한다: `dispatch_count`, `first_dispatched_at`, `last_dispatched_at`, `resolve_attempts`, `next_resolve_at`. 인덱스는 `(status, requested_at)` 대신 `(status, next_resolve_at)`을 쓴다.
- 허용 전이표는 `PaymentStatus` enum 안에 `when`으로 빠짐없이 적는다. `PaymentTest`는 "모든 상태 쌍 × 허용 여부" 표 하나로 검증한다.
- lookup 매핑을 보완한다.
  - `READY`, `IN_PROGRESS`는 Unknown
  - `CANCELED`, `PARTIAL_CANCELED`는 Declined로 매핑하면 안 된다. 승인된 뒤 취소된 사실이므로 Approved로 반영하고, 취소 관측은 제안 3의 대사 이슈로 남긴다.
  - `WAITING_FOR_DEPOSIT`는 자동결제에서 나오면 안 되는 값이다. Unknown으로 두고 경보를 낸다.
  - approve 응답이 200인데 `status`가 DONE이 아니면 Unknown이다.

**얻는 것**
- 쟁점 1(NotFound일 때 재전송)과 쟁점 6(상한 초과 시 처리)의 결론을 담을 상태가 생긴다.
- 운영 질의가 단순해진다. "사람이 볼 결제"는 `status = 'NEEDS_REVIEW'` 한 줄이다.
- 구독 스케줄러와의 연결 지점(`PREPARED`에서 확인 후 `DISPATCHED`)이 이번 설계 안에 미리 마련된다.

**비용**
- 상태가 4개에서 7개로 늘고, 전이 테스트 조합도 그만큼 늘어난다.
- `PREPARED`와 `ABANDONED`는 이번 범위에서 쓰는 곳이 없어 미리 만든 구조가 된다.
- 컬럼이 5개 늘어난다.

---

## 제안 3. 결제사 관측 원장과 대사 이슈 테이블

**현재 설계**
- 결제사 응답은 PgResult로 바뀐 뒤 `pg_payment_key`, `failure_code`, `failure_message` 컬럼에만 남는다.
- approve와 lookup을 몇 번, 언제 불렀고 무엇을 받았는지는 기록되지 않는다(Mock 카운터에만 있다).
- 승인 금액이 우리 금액과 같은지 확인하지 않는다.
- 상태 충돌(@Version 경합 뒤 서로 다른 최종 상태)은 ERROR 로그만 남긴다.

**이상적인 설계**
- `pg_exchange` 테이블. 쓰기만 하고 고치지 않는다. 소유 패키지는 `pg`다.
  - 컬럼: `order_id`, `operation`(APPROVE / LOOKUP), `idempotency_key`, `sent_at`, `received_at`, `http_status`, `outcome`(PgResult 종류), `raw_code`(pg 밖에서는 해석하지 않는 문자열), `pg_payment_key`, `pg_status`, `pg_amount`
  - 호출을 감싸지 않는다. 응답을 받은 뒤 별도의 짧은 트랜잭션으로 기록한다. 결제 흐름의 트랜잭션 규칙은 그대로 지킨다.
  - 토스 코드를 해석하는 일은 pg 안에서만 한다. 나머지 패키지는 PgResult만 본다. 따라서 CLAUDE.md의 "pg 밖에서 토스 에러 코드를 쓰지 않는다" 규칙과 충돌하지 않는다.
- `PgResult.Approved`에 `amount`를 넣는다. 결과 반영 시 `payment.amount`와 비교하고, 다르면 승인은 반영하되 대사 이슈를 만든다.
- `reconciliation_issue` 테이블
  - 컬럼: `type`, `payment_id`, `detected_at`, `detail`, `resolved_at`, `resolution`
  - `type` 값: `AMOUNT_MISMATCH`, `STATE_CONFLICT`, `CANCELED_AT_PG`, `LATE_APPROVAL_AFTER_ABANDON`, `UNRESOLVED_PAST_DEADLINE`
  - 이후 정산 대사 기능(결제사 정산 파일과 우리 원장 비교)이 차이를 찾으면 같은 테이블에 같은 모양으로 쌓는다. 정산 대사는 이 테이블에 기록을 남기는 탐지 경로를 하나 더하는 작업이 된다.
- `payment`에 `pg_provider` 컬럼을 둔다. 다중 결제사와 대사 파일 출처를 구분하기 위해서다.

**얻는 것**
- 정산 대사에 쓸 기반 데이터가 첫 기능부터 쌓인다.
- 쟁점 6의 상한 판단과 장애 분석 근거가 로그가 아니라 질의 가능한 데이터로 남는다.
- 시나리오 테스트에서 "approve 호출 수"를 Mock 카운터뿐 아니라 우리 쪽 기록으로도 검증할 수 있다. Mock과 실제 토스 연동에 같은 검증을 쓸 수 있다.
- "중복 청구 0, 미확정 0, 기록 불일치 0" 중 마지막 항목을 측정할 수 있게 된다.

**비용**
- 결제 1건당 행이 2개 이상 늘어난다. 보존 기간과 정리 정책이 필요하다.
- 응답 본문을 저장한다면 마스킹 책임이 생긴다(카드번호 일부, 개인정보).
- 관측 기록 쓰기가 실패했을 때 결제 흐름을 막을지 정해야 한다. 이상적인 답은 막지 않고 경보만 내는 것이다.

---

## 제안 4. 시간 분리 대신 행 단위 선점(lease)

**현재 설계**
- 원래 요청과 확정 잡은 `requested_at < now - 5분`이라는 시간 가정과 `@Version`으로 분리된다.
- 5분은 read-timeout(65초)과 따로 정한 상수다. 누군가 timeout을 늘리면 불변식이 조용히 깨진다.
- 인스턴스가 여러 개면 확정 잡이 같은 행을 동시에 집는다. 그러면 lookup과 재전송이 중복된다. 결제사는 멱등으로 처리하므로 돈은 안전하다. 하지만 `409 IDEMPOTENT_REQUEST_PROCESSING`이 Unknown 소음을 만들고, `dispatch_count` 같은 집계가 왜곡된다.
- `MockPgLedger`는 프로세스 메모리에 있다. 다중 인스턴스 테스트에서 인스턴스마다 원장이 달라 Mock이 사실과 다른 결과를 낸다.

**이상적인 설계**
- 시도 행에 `lease_owner VARCHAR`와 `lease_until DATETIME(6)`를 둔다.
- TX1은 행을 만들면서 lease를 잡는다. `lease_until = now + connect-timeout + read-timeout + margin`이고, 이 값은 `PgProperties`에서 계산한다. 기동 시 "확정 대기 > 호출 최대 소요 시간"을 검증하고, 어기면 기동에 실패한다.
- 확정 잡은 조건부 UPDATE로 선점한다. 갱신된 행이 1개일 때만 진행한다.

```sql
UPDATE payment_attempt
   SET lease_owner = :me, lease_until = :until, version = version + 1
 WHERE id = :id AND status IN ('DISPATCHED','UNKNOWN','NEEDS_REVIEW')
   AND lease_until < :now AND next_resolve_at <= :now
```

  또는 `SELECT ... FOR UPDATE SKIP LOCKED LIMIT n`으로 묶음 단위로 선점한다.
- 결과 반영은 "내가 lease 소유자"이거나 "이미 같은 최종 상태"일 때만 한다. 그 밖의 경우는 제안 3의 `STATE_CONFLICT`로 남긴다.
- Mock PG는 원장을 DB 테이블로 옮기거나 별도 프로세스로 띄운다. 토스의 멱등 상태(`PROCESSING`, `COMPLETED(저장된 응답)`)를 그대로 흉내 내서, 처리 중 재전송에는 `409 IDEMPOTENT_REQUEST_PROCESSING`을 돌려준다.

**얻는 것**
- 수평 확장 시 중복 작업이 0이 된다. 스케줄러를 붙일 때 ShedLock 같은 전역 잡 락 없이 행 단위로 작업을 나눠 가질 수 있다.
- "확정 대기 > timeout" 불변식이 설정값 사이의 암묵 약속이 아니라 코드로 보장된다.
- "지금 누가 이 결제를 잡고 있는가"를 DB에서 볼 수 있다.

**비용**
- 컬럼과 조건부 쿼리가 늘어난다. lease 만료와 갱신 버그는 새로운 실패 경로다.
- 인스턴스 간 시계 오차를 margin으로 흡수해야 한다(Clock 주입 규칙은 유지한다).
- 단일 인스턴스인 지금은 직접적인 효용이 없다.
- Mock을 DB나 별도 프로세스로 옮기면 테스트 구성이 무거워진다.

---

## 제안 5. 정책 분리와 요청 스냅샷: 재전송, 재시도, 요금

**현재 설계**
- "5분 뒤", "NotFound면 재전송", "Unknown이면 그대로 둠"이 `PaymentStatusResolver` 안에 절차로 박혀 있다.
- 금액과 주문명은 호출자의 `cmd` 값을 그대로 쓴다.
- 구독 기능이 들어오면 요금제(플랜, 프로모션, 일할 계산), 재시도 간격(카드 거절 시 D+1, D+3), 해지 여부가 이 흐름에 끼어든다. 그러면 Resolver와 `ChargeService`가 커진다.

**이상적인 설계**
- 메커니즘(호출, 기록, 선점)과 정책(무엇을 할지)을 나눈다.

```kotlin
fun interface ResolutionPolicy {
    fun decide(attempt: AttemptSnapshot, observed: PgResult, eligibility: Eligibility, now: Instant): Resolution
}
sealed interface Resolution {
    data class Apply(val result: PgResult) : Resolution
    data object Resend : Resolution
    data class Wait(val nextAt: Instant) : Resolution
    data class Escalate(val reason: String) : Resolution   // NEEDS_REVIEW
    data class Abandon(val reason: String) : Resolution    // PREPARED에서만
}
```

  순수 함수이므로 쟁점 1, 5, 6, 7의 결론을 Spring과 DB 없이 표 기반 단위 테스트로 모두 고정할 수 있다. Resolver는 결정 결과를 `when`으로 실행만 한다.
- 요금은 청구 의도를 만들 때 `PricingPolicy`가 정하고 스냅샷으로 저장한다(`amount`, `plan_id`, `plan_version`, `pricing_reason`). 결제 흐름은 금액을 계산하지 않는다.
- 시도에는 `request_fingerprint`를 저장한다. 금액, 빌링키 참조, 주문명, customer_key의 해시다. 재전송 직전에 다시 계산해서 다르면 재전송하지 않고 `Escalate`한다. 멱등키를 다시 쓰려면 요청 내용이 원래와 같아야 하기 때문이다.

**얻는 것**
- 요금제 정책이 결제 메커니즘에서 분리된다. 요금 변경이 진행 중인 청구나 재전송에 영향을 주지 않는다.
- 정산 대사에서 "왜 이 금액인가"를 스냅샷으로 설명할 수 있다.
- 돈과 관련된 판단 규칙이 한 파일에 모인다. 사용자가 읽고 설명할 수 있어야 한다는 CLAUDE.md 원칙에도 맞는다.

**비용**
- 간접층이 하나 늘어난다. 정책 구현이 하나뿐인 지금은 과설계로 보일 수 있다.
- 스냅샷과 fingerprint 컬럼이 늘어난다.
- 해시에 넣을 필드 목록을 계속 관리해야 한다.

---

## 토론 쟁점별 입장

### 1. NotFound일 때 재전송할지, FAILED로 둘지
**재전송한다. 단, 세 조건을 모두 만족할 때만 한다.**
- (a) 실제로 보낸 시도(`DISPATCHED`, `UNKNOWN`)여야 한다. 보낸 적 없는 `PREPARED`는 재전송하지 않고 `ABANDONED`로 끝낸다. 다음 시도를 만들지는 청구 의도 수준의 정책이 새로 판단한다.
- (b) 재전송 직전에 청구 의도가 여전히 유효해야 한다(쟁점 5).
- (c) 재전송 기한 안이어야 한다(쟁점 7).

FAILED로 두고 새 시도를 만드는 안은 기각한다. 원래 요청이 늦게 처리되면 이중 청구가 되고, 우리 기록은 FAILED인데 돈은 나간 기록 불일치도 생긴다.

조건 (b)나 (c)를 만족하지 못하면 재전송하지 않고 `NEEDS_REVIEW`로 보낸 뒤 lookup으로만 감시한다. 이후 승인이 보이면 `LATE_APPROVAL_AFTER_ABANDON` 대사 이슈를 남겨 환불 후보로 넘긴다.

### 2. payment에 billing_key를 직접 저장할지
**값은 저장하지 않고 참조(`billing_key_id`)를 저장한다.** 재전송은 원래 요청과 같은 빌링키로 해야 한다. 그래서 "시도 시점의 빌링키"를 고정해야 하고, 바뀌지 않는 `billing_key` 레코드를 참조하는 방식이 맞다. 카드를 바꾸면 새 레코드를 만들고 기존 레코드는 폐기 상태로 둔다.

빌링키 값은 `billing_key` 테이블에만 암호화해서 둔다. `customer_key`도 그 레코드에 딸린 값이므로 시도 행에서 뺀다.

이번 범위에서도 발급 기능 없이 테이블과 엔티티만 먼저 만들고, 테스트 픽스처로 행을 넣는 방식을 권한다. CLAUDE.md는 이미 커밋된 Flyway 마이그레이션을 고치지 못하게 한다. 따라서 나중에 VARCHAR를 FK로 바꾸려면 새 버전 파일과 데이터 이관이 필요하고, 처음부터 참조로 두는 쪽이 더 싸다.

### 3. 애매한 4xx 목록을 denylist로 둘지, allowlist로 둘지
**allowlist로 둔다.** 결제사가 확정 거절했다고 문서로 확인된 코드만 Declined로 보내고, 그 밖의 모든 코드(모르는 코드 포함)는 Unknown으로 보낸다.

근거는 오분류의 비용 차이다.
- Unknown을 Declined로 잘못 분류하면 재시도가 생겨 이중 청구로 이어질 수 있다.
- Declined를 Unknown으로 잘못 분류하면 lookup 한 번과 멱등 재전송 비용만 든다.

목록에 없는 코드가 나오면 경보를 내고 `pg_exchange.raw_code`로 모아 allowlist를 키워 나간다.

더 나아가 Declined를 원인별로 나누기를 제안한다. 카드 거절이나 한도 초과는 고객 원인이고, 인증키 오류나 잘못된 요청은 가맹점 원인이다. 가맹점 원인은 고객에게 결제 실패로 보이면 안 되므로 `NEEDS_REVIEW`와 경보로 보낸다.

### 4. `attempt_no`를 지금 넣을지
**넣는다.** 이상적으로는 제안 1의 의도와 시도 분리 형태로 넣는다. 단일 테이블을 유지하더라도 `attempt_no`와 "charge_key당 종결되지 않은 시도 1개" 함수 기반 유니크 인덱스를 함께 둔다. 유니크 제약을 나중에 추가하면 기존 데이터를 검증하는 마이그레이션이 필요하고, 그 사이 기간에는 불변식이 비어 있게 된다.

### 5. 재전송 전 구독 상태 확인: 지금 둘 연결 지점
**연결 지점은 청구 의도의 상태(`charge.status`)로 두는 것이 이상적이다. 조회용 포트만 두는 방식은 부족하다.**
- 구독을 해지하면 charge에 "열린 청구 의도 취소" 명령을 보낸다(push).
- 재전송이나 전송 직전에는 `charge` 행을 `FOR UPDATE`로 잠근 상태에서 `OPEN`인지 확인하고 `DISPATCHED` 기록을 남긴다.
- 이렇게 하면 해지와 재전송이 같은 행 잠금으로 줄을 서게 된다. "확인 후 해지" 경합 창이 사라진다. 해지가 먼저면 보내지 않는다. 전송이 먼저면 해지 쪽이 "전송 중"을 보고 환불 정책으로 넘긴다.

이번 범위에서 할 일은 `charge.status`, `ChargeService.cancel(chargeKey)`, 그리고 Resolver가 정책(제안 5)에 넘기는 `Eligibility` 값이다. 구독 없이도 "청구를 취소한 뒤 확정 잡이 재전송하지 않는다"를 테스트할 수 있다.

### 6. 확정 잡의 재시도 상한과, 넘었을 때의 처리
**상한은 횟수가 아니라 시각(deadline)으로 정한다.** 횟수 상한은 잡 실행 빈도에 따라 의미가 달라진다. 결제사 장애가 몇 시간 이어지면 순식간에 소진된다.
- 확정 잡은 지수 백오프로 돈다. `next_resolve_at`이 5분, 10분, 20분… 으로 늘어나고 최대 간격은 1시간이다.
- **재전송 기한**(예: 첫 전송 후 24시간)이 지나면 재전송을 금지하고 lookup만 한다.
- **확정 기한**(예: 첫 전송 후 72시간)이 지나면 `NEEDS_REVIEW`로 보내고 `UNRESOLVED_PAST_DEADLINE` 대사 이슈와 경보를 남긴다.
- **자동으로 FAILED 처리하는 일은 절대 없다.** 돈이 이미 나갔을 수 있기 때문이다.
- `NEEDS_REVIEW` 상태에서도 GET lookup은 낮은 빈도로 계속한다. GET은 멱등키 유효기간과 상관이 없다.
- 구체적인 수치는 정책 객체(제안 5)의 설정값으로 둔다.

### 7. 토스 멱등키 유효기간과, 지난 뒤 재전송하는 위험
**유효기간은 15일이다(공식 문서 확인값).** 기간이 지난 뒤 같은 키로 재전송하면 결제사가 새 요청으로 처리할 수 있다.
- 원래 요청이 이미 승인됐다면 orderId 중복 검사(`DUPLICATED_ORDER_ID`, 우리는 Unknown으로 처리)가 2차 방어가 된다. 따라서 이중 청구까지는 막힌다.
- 진짜 위험은 두 가지다.
  - (a) 원래 요청이 미도달, 실패, 중단이었다면 15일 넘게 지난 청구가 지금 새로 발생한다. 고객 입장에서 이것은 의도와 다른 청구다.
  - (b) 문서상 `DUPLICATED_ORDER_ID`는 "승인 및 취소가 진행된" 주문에만 적용된다. 실패하거나 중단된 orderId를 다시 쓸 수 있는지는 문서에 없다.

입장은 다음과 같다.
- 재전송 기한을 15일보다 훨씬 짧게 잡는다(기본 24시간). 기동 시 `재전송 기한 < 15일 - 1일`을 검증한다. 문서가 "처음 요청한 날부터"라고만 해서 날짜 단위로 계산될 가능성이 있으므로 1일 margin을 둔다.
- 기한의 기준 시각은 `requested_at`이 아니라 `first_dispatched_at`이다. 결제사가 받은 시각은 우리가 보낸 시각과 같거나 늦으므로, 우리 전송 시각을 기준으로 잡으면 항상 보수적인 쪽이 된다.

---

## 기타 관찰 (제안 외, 다음 단계 참고용)

- **Mock 시나리오와 원장 재생 규칙의 우선순위가 명시되지 않았다.** "원장에 기록이 있으면 재생"을 "시나리오는 첫 요청에만 적용"보다 앞에 둔다고 적어야 한다.
- **에러 응답 재생은 문서에 없다.** 토스가 에러 응답도 멱등키로 재생하는지는 확인되지 않았다. Mock에 재생 모드와 재처리 모드를 파라미터로 두고, 두 가정 모두에서 불변식이 지켜지는지 테스트하기를 권한다.
- **시나리오 추가를 제안한다.** `TIMEOUT_AFTER_APPROVE`가 진행 중일 때 재전송이 들어오면 `409 IDEMPOTENT_REQUEST_PROCESSING`을 받는 시나리오가 필요하다.
- **@Version 충돌의 처리.** 경합 뒤 상태가 서로 다르면 지금은 ERROR 로그만 남긴다. 제안 3의 `STATE_CONFLICT` 레코드로 남겨야 "기록 불일치 0"을 측정할 수 있다.

## 비관론자에게 넘기는 검증 요청

- 제안 4의 lease가 만료된 순간 원래 요청 스레드가 깨어나 TX2를 시도하는 경로
- 제안 1의 함수 기반 유니크 인덱스와 Hibernate `validate`, Testcontainers MySQL 8.4가 실제로 함께 동작하는지
- 제안 5의 `request_fingerprint` 대상 필드가 빠졌을 때 생기는 재전송 오판
- 쟁점 5의 행 잠금 직렬화 순서에서 교착이 생길 가능성. 구독 해지 트랜잭션이 다른 행을 먼저 잠그는 경우가 해당한다.

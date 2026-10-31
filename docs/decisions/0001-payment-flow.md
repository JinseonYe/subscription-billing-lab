# ADR 0001: 결제 1건 흐름 (TX1 → 외부 호출 → TX2, 확정 잡)

- 날짜: 2026-10-29 (코드 리뷰 반영: 2026-10-31)
- 상태: 승인됨 (구현 완료, 코드 리뷰 반영 포함)
- 코드 리뷰 지적과 처리 결과: `docs/review-log.md`
- 토론 원문: `docs/design-reviews/2026-10-29-payment-flow-{1-idealist,2-pessimist,3-realist}.md`

## 배경
결제사 호출은 타임아웃이 나면 결과를 알 수 없다. 이때 실패로 처리하면 돈은 나갔는데 구독이 끊기고, 다시 결제하면 이중 청구가 된다.
첫 기능으로 결제 1건 흐름을 만들고, 결과를 모르는 결제를 확정 잡이 조회로 확정한다. 구독 상태 머신, 빌링키 발급, 스케줄러는 다음 기능이다.

## 결정 원칙
1. **청구 의도(charge_key) 하나에 orderId는 하나다.** orderId는 TX1에서 한 번만 만들고 다시 만들지 않는다. 재전송은 항상 같은 orderId와 같은 멱등키로 한다.
   - 같은 키가 15일 안에 다시 오면 토스의 멱등키가 막고, 승인된 뒤에는 `DUPLICATED_ORDER_ID`가 막는다.
   - DB에서도 `UNIQUE(charge_key)`로 두 번째 시도 행이 생기지 않게 한다.
2. **결제사가 확정한 사실은 항상 기록한다.** DB 제약과 엔티티 가드는 새로 보내는 것만 막는다. 이미 일어난 승인을 기록하지 못하게 막지 않는다. 상태가 충돌하면 예외로 롤백하지 않고 `PAYMENT_CONFLICT` ERROR 로그를 남긴다.
3. **호출자는 예외나 `Pending`을 실패로 해석하지 않는다.** 결제 상태로만 판단한다. `charge_key`는 호출자가 결정적으로 만들고(예: `SUB-{id}-{주기}`), 재시도할 때 같은 값을 쓴다.
   - 결제사에 요청을 보낸 뒤에 생긴 예외는 `Pending`으로 바꿔서 돌려준다. 승인이 끝났을 수 있기 때문이다.
   - 요청을 보내기 전에 던지는 예외(원칙 7의 트랜잭션 예외, 원칙 8의 `ChargeRequestMismatchException`)는 호출자의 잘못된 사용을 알리는 것이다. 청구가 일어나지 않았으므로 `Pending`으로 바꾸지 않고 그대로 던진다.
4. **자동으로 FAILED 처리하지 않는다.** FAILED는 결제사가 확정 거절했을 때만 기록한다.
5. **잠금 순서**: 이후 테이블이 늘어나면 모든 트랜잭션은 charge, attempt 순서로 잠근다.
6. **실패 여부는 `status`로만 판단한다.** FAILED로 기록됐다가 결제사 승인이 확인되어 APPROVED가 된 결제는 `failure_code`와 `failure_message`를 이력으로 남겨 둔다. 따라서 APPROVED인데 `failure_code`가 있어도 실패로 해석하지 않는다. `failure_code IS NOT NULL`로 실패 건을 집계하지 않는다. (거절 시각은 따로 저장하지 않으며 `resolved_at`은 승인 시각으로 덮어쓴다. 전이가 일어나면 `PAYMENT_CONFLICT` ERROR 로그가 남는다.)
7. **결제 흐름은 트랜잭션 안에서 호출하지 않는다.** `ChargeService.charge()`와 `PaymentStatusResolver.resolvePending()`은 호출한 쪽에 활성 트랜잭션이 있으면 결제사를 부르기 전에 `IllegalStateException`을 던진다 (`TransactionGuard`의 `requireNoTransaction`).
   - 이유: 바깥 트랜잭션이 있으면 `PaymentWriter`의 트랜잭션이 거기에 합류한다. 그러면 결제 기록(TX1)이 커밋되기 전에 승인 요청이 나가고 DB 커넥션을 최대 65초 붙잡는다. 승인된 뒤 결과 반영(TX2)이 실패하면 바깥 트랜잭션이 롤백되어 결제 기록이 사라지고, 같은 `charge_key`로 다시 시도하면 새 `orderId`로 두 번째 승인이 나간다 (이중 청구).
   - 선택: `@Transactional(propagation = NEVER)` 대신 직접 검사한다. 어노테이션 없이 어디서 불러도 동작하고, "결제 코드에는 `@Transactional`을 쓰지 않는다"는 규칙과 충돌하지 않는다.
   - 호출자는 구독 갱신 같은 상위 로직에서도 트랜잭션 밖에서 이 두 메서드를 불러야 한다.
8. **같은 `charge_key`에 내용이 다른 요청은 거절한다.** 이미 만든 결제를 찾았을 때 `amount`, `customerKey`, `billingKey`를 새 요청과 비교한다. 하나라도 다르면 `ChargeRequestMismatchException`을 던지고 `PAYMENT_CONFLICT` ERROR 로그를 남긴다.
   - 이유: 비교하지 않으면 9,900원으로 승인된 키에 19,900원 요청이 와도 `Approved`를 돌려줘서, 호출자가 요청한 내용이 결제된 것으로 오해한다.
   - 결제사를 부르기 전에 던지므로 청구는 일어나지 않는다. 동시 요청 경합으로 먼저 만든 결제를 찾은 경로에도 같은 비교를 적용한다.
   - 예외 메시지와 로그에는 달라진 항목의 이름만 넣고 빌링키 같은 값은 넣지 않는다. 표시용인 `orderName`은 비교하지 않는다.
9. **기한을 넘긴 `Unknown`은 상태를 유지하고 `RESOLVE_STUCK` ERROR를 남긴다.** 확정 잡의 조회 결과가 `Unknown`인 채로 `requested_at`부터 재전송 기한(24시간)을 넘으면 `RESOLVE_STUCK` ERROR 로그를 남긴다. 기한 안이거나 정확히 24시간이면 INFO 로그만 남긴다.
   - 상태는 바꾸지 않는다. `FAILED`로 바꾸지 않고 재전송도 하지 않는다 (원칙 4).
   - 이유: 승인됐을 수도 있는 결제가 알림 없이 쌓이는 것을 막는다. 예를 들어 비밀키가 잘못되면 모든 조회가 401이 되어 모든 결제가 `UNKNOWN`에 머무는데, ERROR 로그가 없으면 ERROR 기준 알림에 걸리지 않는다.
   - 결제사에 기록이 없는 경우(`NotFound`)는 기존대로 `RESEND_DEADLINE_PASSED` ERROR를 남긴다. 알림 연동과 `Unknown` 사유(에러 코드)의 로그 기록은 구현하지 않았다.

## 판정 요약

**채택**
- `UNIQUE(charge_key)`
- `ChargeOutcome.Pending` (송신 후 예외를 밖으로 던지지 않음)
- 반영 규칙 표와 FAILED→APPROVED 허용
- Declined allowlist
- lookup 비종결 상태는 Unknown, 취소 상태는 Approved + CONFLICT 로그
- 같은 orderId로만 재전송, 재전송 기한 24시간, 자동 FAILED 없음
- Mock PG: `compute`, 409 흉내, 기록을 지연보다 먼저, orderId별 카운터, 시나리오 7
- 요청 필드는 `val`
- `@Version` 설명 정정 (동시 트랜잭션의 덮어쓰기 방지용일 뿐, 늦게 커밋되는 쪽은 반영 규칙 표가 처리)

**보류 (구독, 스케줄러, 정산 단계)**
- charge/attempt 테이블 분리, NEEDS_REVIEW, 확정 기한 72시간
- next_resolve_at 선점, 백오프, 실행당 건수 상한
- pg_exchange, reconciliation_issue, 금액 비교
- 재전송 전 구독 상태 확인 (쟁점 5)
- 빌링키 암호화와 FK

**기각**
- PREPARED / DISPATCHED / ABANDONED: 이번 범위에서는 동작 차이가 없고, ABANDONED는 이중 청구 경로를 만든다
- 함수 기반 유니크 인덱스, lease_owner
- Mock 원장 DB화 (`@Profile`이 엔티티 스캔에 적용되지 않아 운영 스키마가 오염됨)
- ResolutionPolicy, request_fingerprint (`val` 필드로 대신함)

## 쟁점별 결정

| # | 쟁점 | 결정 |
|---|---|---|
| 1 | NotFound일 때 재전송 | 같은 orderId와 멱등키로, `now - requested_at <= 24h`일 때만 재전송한다. 기한이 지나면 재전송도 FAILED 처리도 하지 않고 ERROR 로그를 남긴다 |
| 2 | billing_key 저장 | 평문 컬럼(`val`). mock 키만 쓰고, 로그와 예외 메시지에 넣지 않는다. 암호화나 FK 교체는 빌링키 기능에서 한다 |
| 3 | 애매한 4xx | allowlist. 목록에 있는 거절 코드만 Declined, 나머지 4xx는 Unknown |
| 4 | attempt_no | **넣지 않는다.** idealist와 pessimist는 지금 넣자고 했지만, 이번 범위에는 시도 2를 만드는 경로가 없다. `UNIQUE(charge_key)`가 DB에서 시도 2를 물리적으로 막는 가장 강한 방어다 |
| 5 | 재전송 전 구독 상태 확인 | 이번에는 만들지 않는다. 사전 확인은 최선 노력일 뿐 틈(확인과 송신 사이)을 막지 못한다. 보장은 "승인은 항상 기록 + 해지 후 승인은 사후 처리"가 맡고, 연결 지점은 구독 기능에서 만든다 |
| 6 | 확정 잡 재시도 상한 | 재전송 기한 24시간만 둔다. 확정 기한, NEEDS_REVIEW, 백오프는 스케줄러 단계로 미룬다. 기한을 넘긴 `Unknown`은 `RESOLVE_STUCK` ERROR 로그로 드러낸다 (원칙 9) |
| 7 | 토스 멱등키 유효기간 | 처음 요청한 날부터 15일 (공식 문서). 재전송 기한 24시간이 이보다 훨씬 짧아 만료된 키로 재전송하는 일은 없다. 만료된 키로 보냈을 때의 동작은 문서에 없다 |

## 시도 2를 도입할 때의 필수 조건 (구독 기능)
`UNIQUE(charge_key)`를 풀고 같은 청구에 새 orderId를 만들려면 아래를 모두 갖춰야 한다.
1. 이전 시도가 결제사 기준으로 종결됐는지 lookup으로 확인한다 (확정 거절이거나 결제사에 기록이 없음을 확인).
2. 송신 직전 상태 전이를 조건부 UPDATE로 한다 (`WHERE status='PREPARED'`로 행이 1개 바뀔 때만 송신).
3. 잠금 순서를 charge → attempt로 고정한다.
4. NEEDS_REVIEW 상태와, 그동안 구독을 어떻게 보여줄지(이용 허용 또는 차단)를 정한다.
5. 해지 후 승인(`CHARGED_AFTER_CANCEL`)의 사후 처리와, 갱신일 당일 해지의 정책을 정한다.
6. `next_resolve_at` 조건부 UPDATE로 확정 잡의 중복 실행을 막는다.
7. 결제사가 취소 상태(CANCELED)로 알려준 건을 미결제로 보고 재청구할지 정한다. 재청구하면 그것이 시도 2다.

## 알려진 한계 (README에 같은 내용을 명시)
- 청구 의도당 결제 시도는 1번이다.
- 24시간이 지나도 결과를 모르는 결제는 자동으로 확정하지 않고 `REQUESTED`/`UNKNOWN`으로 남는다. 조회 결과가 `Unknown`이면 `RESOLVE_STUCK`, 결제사에 기록이 없으면 `RESEND_DEADLINE_PASSED` ERROR 로그로 드러난다. 알림 연동은 아직 없다.
- 카드 정지, 카드 번호 오류 같은 코드는 거절 허용 목록에 없어서 `Unknown`으로 처리되고 24시간 기한까지 미확정으로 남는다.
- 확정 잡은 스케줄러 없이 직접 호출한다. 동시 실행을 막는 선점 장치가 없지만 같은 orderId만 쓰므로 이중 청구는 없다.
- 결제사 요청과 응답 원문은 DB가 아니라 로그에만 남는다.
- 결제사 측 취소는 승인으로 기록한다.
- 빌링키는 평문으로 저장한다 (mock 전용).
- 거절로 보는 에러 코드는 allowlist에 있는 것뿐이다.

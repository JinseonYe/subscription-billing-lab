# 설계 검토 2단계: design-pessimist (구독 상태 머신, MVP 2)

- 날짜: 2026-11-01
- 범위: 안건 1, 3, 5만. 안건마다 가장 위험한 실패 시나리오 1~2개와 막는 방법. 현실주의자 단계는 생략하고 메인 세션이 중재한다.
- 전제로 넘긴 결정 (사용자 지시):
  - 안건 2: 해지 후 승인은 감지와 기록(ERROR 로그)만 한다. 결제사 취소 API와 refund_obligation 테이블은 이번 범위 밖이다. CANCELED는 종결 상태다.
  - 안건 4: 구독 반영은 pull 방식이다. 변경 감지 스윕은 넣지 않는다.
  - 재시도 직전의 이전 시도 lookup은 안건 3의 최소안에 포함한다.
  - 알려진 한계: CANCELED로 끝난 뒤 FAILED→APPROVED가 오는 경우는 수용한다. 토스가 거절, ABORTED, EXPIRED 뒤에 DONE으로 바꾸는지는 미확인이다.
- 저장 메모: 에이전트가 전달한 원문을 그대로 저장했다. 표기만 바로잡았다(HTML 이스케이프 `&lt;`를 `<`로).

---

# 비관론자 검토: 구독 상태 머신 MVP 2 (안건 1, 3, 5)

`ChargeService.charge`와 `PaymentStatusResolver`를 직접 읽고 확인한 사실이 두 가지 있습니다.
- 이미 있는 키로 `charge()`를 부르면 송신하지 않고 지금 상태만 돌려줍니다.
- Resolver는 NotFound면 송신 직전에 상태를 다시 읽지 않고 approve를 보냅니다. 선점 장치도 없습니다.

## 안건 1

### 시나리오 1. 이미 끝난 갱신을 다시 시작함 (순차 중복 실행) / 높음
1. 호출자 A가 기간 종료 대상을 읽습니다. 구독 7, cycle 3, period_end 11-01입니다.
2. 호출자 B(다른 인스턴스나 재실행)가 같은 구독을 끝까지 처리합니다. startRenewal TX, `charge(C4-A1)` 승인, 반영 TX(period_end 12-01, pending 비움) 순서입니다. Mock에서는 몇 초면 끝납니다.
3. A가 startRenewal을 부릅니다. 새 TX가 최신 행을 읽으므로 @Version 충돌이 없고, pending도 비어 있어 가드를 통과합니다.
4. 엔티티가 행의 period_end와 Clock으로 기한을 검사하지 않으면 C5-A1이 만들어집니다.
- 결과: 다음 달 요금이 바로 청구되어 한 달에 두 번 청구됩니다. @Version과 pending 가드는 동시 실행만 막고 순차 중복은 막지 못합니다. AlreadyStarted는 결제가 진행 중일 때만 나옵니다.
- 수정: `startRenewal(expectedCycleNo)`로 바꿉니다. 행의 cycle_no가 다르거나 `now < period_end`면 sealed `AlreadyRenewed`를 돌려줍니다.

### 시나리오 2. 늦게 온 결과가 다른 시도의 펜스를 비움 / 높음
1. 스레드 T가 startRenewal(C4-A1)을 커밋한 뒤 `charge()`를 부르기 전에 멈춥니다(GC, 일시 정지).
2. 동기화 S가 결제 행이 없는 것을 보고 `charge(C4-A1)`를 부릅니다. 결과는 거절이고, 구독은 PAST_DUE가 되며 pending이 비워집니다.
3. COOL_DOWN이 지나 startRetry가 실행됩니다. pending은 C4-A2이고 송신 중입니다.
4. T가 깨어나 `charge(C4-A1)`를 부르고 기존 FAILED를 받습니다. T는 이 결과로 구독에 거절을 반영합니다.
5. 반영 메서드가 키를 비교하지 않으면 A2가 거절된 것으로 처리됩니다. retryCount가 1 늘고 pending이 비워지며, 소진이면 CANCELED가 됩니다.
- 결과: A2가 진행 중인데 펜스가 열립니다. 해지가 CANCELED로 통과하고(CHECK도 통과) 그 뒤 A2가 승인되면 해지 후 청구입니다. A3가 송신되면 한 주기 이중 승인입니다.
- 수정: 구독 반영 메서드는 chargeKey를 받습니다. 행의 cycle_no와 attempt_no로 만든 현재 시도 키와 다르면 no-op로 두고 WARN을 남깁니다. 인라인 경로도 ChargeOutcome을 직접 넘기지 않고 `sync(subscriptionId)`만 부릅니다.

## 안건 3

### 시나리오 3. 늦은 승인을 반영하다 크래시해서 PAST_DUE에 고착됨 / 높음
1. 재시도에 들어가면서 A2(FAILED)를 lookup하니 Approved입니다(조건 3의 Approved 분기).
2. payment TX에서 FAILED→APPROVED가 커밋됩니다.
3. 구독 TX(PAST_DUE→ACTIVE)가 돌기 전에 서버가 죽습니다.
4. 다음 실행에서는 pending이 이미 비어 있어 pull 동기화가 읽을 키가 없습니다. 재시도는 조건 1(A{n}이 FAILED)을 만족하지 못해 건너뜁니다. 소진 판단도 하지 않습니다(마지막 시도가 FAILED가 아님).
- 결과: 돈은 받았는데 구독은 PAST_DUE로 영구히 남고, D+7부터 이용이 막힙니다. 스윕이 폐기됐으므로 자동 복구 경로가 없습니다.
- 수정: 재시도 진입에서 현재 시도 키의 결제 상태를 sealed로 나눕니다(Failed / Approved / Pending / NoPayment). Approved는 항상 "그 키로 ACTIVE 반영"으로 보내서 재실행하면 복구되게 합니다.

### 시나리오 4. COOL_DOWN을 넘겨 멈춰 있던 송신 스레드 / 높음 (토스 동작 확인 필요)
1. 원래 `charge()` 스레드 T가 A1의 TX1을 커밋한 뒤 approve를 보내기 전에 멈춥니다. Resolver R1이 NotFound를 본 뒤 approve 전에 멈추는 경우도 같습니다.
2. Resolver R2가 NotFound를 보고 같은 멱등키로 재전송합니다. 결과는 거절이고 FAILED, resolved_at=t가 기록됩니다.
3. t+COOL_DOWN 뒤 재시도가 lookup(A1)에서 Declined를 받고 A2를 보냅니다. A2가 승인되어 ACTIVE가 됩니다.
4. T(또는 R1)가 깨어나 같은 orderId와 멱등키로 A1 approve를 보냅니다.
5. 토스가 거절 응답을 재생하지 않고 새로 처리하면(그사이 카드 잔액이 생김) A1이 승인됩니다. FAILED→APPROVED입니다.
- 결과: 한 주기에 승인이 2건이고, 남는 것은 PAYMENT_CONFLICT 로그뿐입니다. COOL_DOWN은 응답을 기다리는 시간만 덮고, 송신 전에 멈춘 시간에는 상한이 없습니다.
- 수정: 토스가 거절된 orderId와 멱등키의 재요청에 어떻게 응답하는지 확인하기 전에는 시도 2를 열지 않습니다. 열더라도 ChargeService와 Resolver 양쪽에 송신 직전 상태 재조회(REQUESTED/UNKNOWN일 때만 송신)를 넣어 틈을 좁히고, 남는 틈은 ADR에 적습니다.

## 안건 5

### 시나리오 5. 신청 INSERT와 첫 시도 시작 사이에 크래시해서 고객이 잠김 / 중간
1. 신청 TX가 PENDING을 INSERT하고 커밋합니다. 키에 id가 필요하므로 pending은 아직 NULL입니다.
2. 첫 시도를 시작하는 TX가 돌기 전에 서버가 죽습니다.
3. 고객이 다시 신청하면 UNIQUE 충돌이 나고, 신청 내용이 같으므로 기존 구독을 돌려줍니다. 이 경로는 첫 결제를 시작하지 않습니다.
4. pending이 NULL이므로 동기화도 이 구독을 보지 않습니다.
- 결과: 청구는 없지만 PENDING 고아가 자리를 차지해 다시 가입할 수 없습니다.
- 수정: INSERT와 pending 채우기를 한 TX에서 합니다(persist 직후 id가 나오는지 확인 필요). 충돌해서 기존 구독을 돌려주는 경로도 "첫 시도 보장(AlreadyStarted 허용) 후 같은 키로 `charge()`"를 거칩니다.

### 시나리오 6. 첫 결제가 끝내 확정되지 않음 + CHECK + live UNIQUE / 중간
1. C1-A1을 보내는 동안 결제사 장애가 24시간 넘게 이어집니다. RESEND_DEADLINE_PASSED(NotFound)나 RESOLVE_STUCK(Unknown)이 남습니다.
2. pending은 최종 결과에서만 비우므로 계속 채워져 있습니다. CHECK가 CANCELED를 막아 해지는 요청 기록으로만 남습니다.
3. PENDING도 live로 세므로 다시 신청하면 409입니다.
- 결과: NotFound라면 청구는 없는데도 고객은 해지도 재가입도 못 합니다. 운영자가 고치려 해도 CHECK 때문에 status와 pending을 한 UPDATE에서 함께 바꿔야 합니다.
- 수정: 자동 포기는 넣지 않습니다. ADR 알려진 한계에 적고, RESEND_DEADLINE_PASSED 건에만 쓰는 운영 절차(두 컬럼을 함께 바꾸는 쿼리)를 남깁니다.

## §10 검증 요청 답
- **1번**: 위 시나리오 2와 4입니다. 지금 코드에는 관리자 경로가 없습니다. 동기화가 고아 시도를 완료하는 경로는 구독 행을 바꾸지 않지만, 결정적 키와 `UNIQUE(charge_key)` 덕분에 안전합니다. 다만 전제가 있습니다. pending은 결제 행의 최종 상태를 반영할 때만 비워야 하고, 결제 행 없이 비우는 경로를 만들면 안 됩니다.
- **3번**: 위 시나리오 4입니다.
- **4번**: 없습니다. live 값이 NULL에서 값으로 바뀌는 것은 CANCELED에서 나가는 전이뿐이고, CANCELED는 종결 상태입니다. 위반은 신청 INSERT에서만 납니다. ADR에 "CANCELED 재활성화 금지는 이 UNIQUE의 전제"라고 적어 두세요.
- **5번**: 마감 전에 승인 확정까지 끝나면 맞습니다(D 기준 고정, D부터 이용이 이어짐). 어긋나는 곳은 두 군데입니다.
  - **정의 오류 (중간)**: PAST_DUE 동안 행의 period_start는 아직 지난 기간의 시작(D-1개월)입니다. `period_start + DUNNING_WINDOW`를 그대로 계산하면 PAST_DUE에 들어가는 순간 이용이 막힙니다. `period_end(=D) + DUNNING_WINDOW`로 바꿔야 합니다.
  - **확정 지연 (낮음)**: D+5 시도가 UNKNOWN으로 남았다가 D+9에 승인으로 확정되면, D+7~D+9는 막혔는데 한 달 요금을 받습니다. RENEWAL_GRACE(24h)를 넘긴 갱신 미확정도 같습니다. 알려진 한계로 기록합니다.

## 확인 필요 (추측하지 않음)
- Hibernate 7 `ddl-auto=validate`가 매핑하지 않은 생성 컬럼을 무시하는지, MySQL 8.4에서 VIRTUAL 생성 컬럼에 UNIQUE를 걸 수 있는지(안 되면 STORED).
- 신청 INSERT의 UNIQUE 위반 예외가 persist 시점에 나는지 커밋 시점에 나는지. 같은 TX 안에서 잡으면 rollback-only 때문에 `UnexpectedRollbackException`이 날 수 있습니다. ChargeService처럼 writer TX 밖에서 잡고 새 조회로 찾아야 합니다.
- @Version 충돌 예외가 flush나 커밋 때 프록시 밖으로 나오는지. 호출자는 키를 TX 메서드의 반환값으로만 받고, 예외가 나면 `charge()`를 부르지 않아야 합니다.
- 커밋은 됐는데 응답이 유실된 경우, 다시 부르면 AlreadyStarted와 같은 키가 돌아오는지 테스트로 확인해야 합니다.
- 토스가 거절된 orderId를 같은 멱등키로 다시 받으면 거절을 재생하는지, 다시 승인할 수 있는지(시나리오 4의 전제).

참고 파일:
- `src/main/kotlin/com/jinseon/billing/charge/ChargeService.kt`
- `src/main/kotlin/com/jinseon/billing/charge/PaymentStatusResolver.kt`
- `docs/design-reviews/2026-11-01-subscription-state-machine-1-idealist.md`

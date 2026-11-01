# ADR 0002: 구독 상태 머신 (구독 행이 송신 펜스, 결제 확정은 pull)

- 날짜: 2026-11-01
- 상태: **승인됨 (2026-11-01)**. 구현 계획은 다음 단계에서 계획 모드로 만든다. 승인할 때 중재자 판단 1번(재시도 시작 엔티티 메서드 구현)은 철회됐다(원칙 7).
- 토론 원문: `docs/design-reviews/2026-11-01-subscription-state-machine-{1-idealist,2-pessimist}.md`
- 토론 방식: 이상주의자 → 비관론자까지만 돌렸다. 토큰 비용 때문에 현실주의자 단계는 생략했고, 메인 세션이 중재하면서 MVP 범위 정리를 겸했다. 비관론자에게는 안건 1, 3, 5만 보냈고 안건 2, 4는 토론 없이 정했다.
- "(중재자 판단)" 표시는 사용자 지시에 없던 것을 메인 세션이 정한 항목이다.

## 배경
ADR 0001은 결제 1건 흐름을 만들면서 구독 기능으로 세 가지를 미뤘다. 재전송 전 구독 상태 확인(쟁점 5), 시도 2를 도입할 때의 필수 조건 7개, 해지 후 승인의 사후 처리다.
이번에는 시간에 따라 바뀌는 구독 상태 머신을 만들고, 구독과 결제가 어긋나는 경로를 닫는다. 구독은 결제를 직접 참조하지 않고 charge 패키지가 둘을 엮는다.
스케줄러(MVP 4)와 빌링키(MVP 3)는 이번 범위가 아니다. 기간 종료와 동기화는 직접 호출하는 메서드로만 만든다.

## 상태와 전이

### 상태 그래프 (`SubscriptionStatus.canTransitionTo`, `else` 없이 `when`)
```
PENDING  -> ACTIVE | CANCELED
ACTIVE   -> PAST_DUE | CANCELED
PAST_DUE -> ACTIVE | CANCELED
CANCELED -> (없음, 종결)
```
- PENDING의 정의를 바꾼다. "첫 결제가 UNKNOWN일 때만"이 아니라 **첫 결제가 승인으로 확정되기 전**이다. chargeKey에 구독 id가 들어가므로 구독 행을 먼저 커밋해야 하고, 첫 결제를 부르는 동안에는 항상 PENDING이다.
- 기간 연장, 재시도 횟수 증가, 해지 예약은 상태가 바뀌지 않으므로 그래프에 자기 자신으로 가는 전이를 넣지 않는다. 이런 변경은 엔티티 메서드가 상태와 조건을 `check`로 막는다.

### 이벤트별 전이
"송신 중"은 `pending_charge_key`가 비어 있지 않다는 뜻이다. "예약"은 `cancel_at_period_end`다. PENDING에서는 "해지 요청"으로, ACTIVE에서는 "해지 예약"으로 같은 컬럼을 쓴다.
"이번"은 이번 기능에서 구현하는지를 뜻한다. **13~18번은 설계로만 남기고 구현은 MVP 4에서 재시도 송신을 열 때 함께 한다**(원칙 7). 이번 기능에서는 도달할 수 없는 코드이기 때문이다.

| # | 현재 | 이벤트 | 조건 | 결과 | 이번 |
|---|---|---|---|---|---|
| 1 | (없음) | 신청 | | PENDING, `pending_charge_key = SUB-{id}-C1-A1`. 한 트랜잭션 | O |
| 2 | PENDING | 첫 결제 승인 반영 | 키가 현재 시도 키 | ACTIVE. 앵커 = 승인 시각, 기간 = 앵커 ~ 앵커+1개월. 송신 비움. 예약은 유지 | O |
| 3 | PENDING | 첫 결제 거절 반영 | | CANCELED(FIRST_PAYMENT_DECLINED). 송신 비움. 해지 요청이 있어도 사유는 이쪽 (중재자 판단) | O |
| 4 | PENDING | 첫 결제 미확정 | | 유지 | O |
| 5 | PENDING, ACTIVE | 사용자 해지 | | 예약 기록, 상태 유지 (멱등) | O |
| 6 | ACTIVE | 기간 종료 | 예약 있음, 송신 없음, `now >= period_end` | CANCELED(USER_CANCELED) | O |
| 7 | ACTIVE | 갱신 시작 | 예약 없음, 송신 없음, `now >= period_end`, `expectedCycleNo`가 행의 `cycle_no`와 같음 | `cycle_no+1`, `attempt_no=1`, `pending_charge_key=C{n+1}-A1`. 상태 유지 | O |
| 8 | ACTIVE | 갱신 승인 반영 | 키가 현재 시도 키 | ACTIVE. 기간을 앵커 기준으로 연장, 송신 비움, 재시도 횟수 초기화. **예약은 유지**하므로 새 기간 끝에 CANCELED (중재자 판단) | O |
| 9 | ACTIVE | 갱신 거절 반영 | 예약 없음 | PAST_DUE(`retry_count=0`, `next_retry_at`). 송신 비움 | O |
| 10 | ACTIVE | 갱신 거절 반영 | 예약 있음 | CANCELED(USER_CANCELED) (중재자 판단) | O |
| 11 | ACTIVE, PAST_DUE | 갱신/재시도 미확정 | | 유지. 소진 판단 안 함 | ACTIVE만 (PAST_DUE는 MVP 4) |
| 12 | PAST_DUE | 사용자 해지 | 송신 없음 | CANCELED(USER_CANCELED) | O |
| 13 | PAST_DUE | 사용자 해지 | 송신 중 (재시도 진행) | 예약 기록. 승인되면 ACTIVE + 예약, 거절되면 CANCELED(USER_CANCELED) | MVP 4 |
| 14 | PAST_DUE | 재시도 시작 | 송신 없음, 마지막 시도가 FAILED로 반영됨, 예약 없음, 횟수 남음, `expectedAttemptNo` 일치 | `attempt_no+1`, `pending_charge_key=C{n}-A{m+1}` | MVP 4 (설계만, 원칙 7) |
| 15 | PAST_DUE | 재시도 승인 반영 | 키가 현재 시도 키 | ACTIVE. 기간 = 앵커 기준 해당 주기, 송신 비움, 재시도 횟수 초기화 | MVP 4 |
| 16 | PAST_DUE | 재시도 거절 반영 | 예약 없음, 소진 아님 | PAST_DUE, `retry_count+1`, `next_retry_at` 갱신. 송신 비움 | MVP 4 |
| 17 | PAST_DUE | 재시도 거절 반영 | 예약 없음, 소진 | CANCELED(RETRY_EXHAUSTED). 송신 비움 | MVP 4 |
| 18 | PAST_DUE | 현재 시도 키의 승인이 FAILED 반영 뒤에 확인됨 | 키가 현재 시도 키 | ACTIVE (FAILED→APPROVED 복구) | MVP 4 |
| 19 | CANCELED | 현재 시도 키의 승인 도착 | 이 주기의 승인이 아직 반영되지 않았음 (아래 설명) | 상태 불변, `CHARGED_AFTER_CANCEL` ERROR 로그, **예외 없음** | O |
| 20 | CANCELED | 사용자 해지 재요청 | | `AlreadyCanceled` 결과 (중재자 판단) | O |
| 21 | CANCELED | 그 밖의 사용자/시간 이벤트 | | 예외 (허용되지 않은 전이) | O |
| 22 | PENDING | 기간 종료, 갱신 시작 | | 예외 (재시도 시작은 MVP 4에서 같은 규칙으로 추가) | O |
| 23 | 모든 상태 | 키가 현재 시도 키와 다른 결과 | | 상태 불변. Approved면 `SUBSCRIPTION_STALE_APPROVAL` ERROR, 그 밖에는 WARN (중재자 판단) | O |

- 예외와 결과를 나눈다 (중재자 판단). **허용되지 않은 전이만 예외**다. 이미 처리된 이벤트의 재실행(같은 갱신 시작이 두 번, 해지 두 번 등)은 예외가 아니라 sealed 결과(`AlreadyStarted`, `AlreadyRenewed`, `AlreadyCanceled`)로 돌려준다. 예외로 하면 MVP 4의 스케줄러가 재실행할 때마다 ERROR가 난다.
- 19번에서 예외를 던지지 않는 이유는 ADR 0001 원칙 2와 같다. 확정된 사실은 기록하고, 충돌은 로그로 남긴다. 예외로 롤백하면 다음 실행에서 같은 예외가 영원히 반복된다.
- 19번의 "도착"은 **이 주기의 승인이 아직 반영되지 않았을 때**만이다. 승인된 기간이 끝나 예약대로 CANCELED가 된 구독(USER_CANCELED)은 현재 시도 키의 결제가 APPROVED인 것이 정상이라 로그를 남기지 않는다. `sync()`는 이미 해지된 구독에도 불릴 수 있어서, 구분하지 않으면 정상 해지를 해지 후 승인으로 오탐한다. 행의 `period_start`가 현재 `cycle_no`의 기간 시작과 같으면 이 주기의 승인은 이미 반영된 것이다. 다르면(첫 결제 거절 뒤, 갱신 거절 뒤, PAST_DUE에서의 해지 뒤) 승인은 해지 뒤에 도착한 것이다. 스키마를 바꾸지 않고 구분할 수 있다.

## 결정 원칙

1. **구독 행이 송신 펜스다.** 송신 의도를 만드는 모든 전이(첫 결제, 갱신 시작, 재시도 시작)와 해지는 반드시 **같은 구독 행**을 바꾼다. `@Version`이 둘을 줄 세운다.
   - 펜스를 두 행에 나누면 두 `@Version`이 서로를 보지 못해 해지와 재시도가 동시에 커밋된다.
   - `pending_charge_key`(nullable)는 결제사로 보낼 수 있는 시도가 열려 있다는 뜻이다. 송신 의도를 만드는 엔티티 메서드가 채우고, 이미 차 있으면 거부한다.
2. **송신 중인 구독은 CANCELED가 될 수 없다.** 엔티티 가드와 DB `CHECK (status <> 'CANCELED' OR pending_charge_key IS NULL)`로 막는다.
   - ADR 0001 쟁점 5의 "재전송 전 청구 유효성 포트"는 **두지 않는다.** 이 불변식이 지켜지면 Resolver가 재전송하는 결제(REQUESTED, UNKNOWN)의 구독은 구조적으로 CANCELED일 수 없어서 포트가 확인하는 값은 항상 "유효"다. 죽은 코드가 된다.
   - `pending_charge_key`는 **결제 행의 최종 상태(승인 또는 거절)를 반영할 때만** 비운다. 결제 행이 없는데 비우는 경로를 만들지 않는다. 운영자의 수동 처리는 예외다(알려진 한계 4번).
3. **시간 이벤트는 기대값을 받는다.** `startRenewal(expectedCycleNo)`처럼 호출자가 읽은 주기 번호를 넘기고, 행의 값과 다르거나 `now < period_end`면 `AlreadyRenewed`를 돌려준다. (비관론자 1번)
   - `@Version`과 송신 가드는 동시 실행만 막는다. 다른 인스턴스가 갱신을 끝까지 처리한 뒤에 오는 순차 중복 호출은 막지 못해서, 기대값 검사가 없으면 다음 달 요금이 바로 청구된다.
   - 재시도 시작은 `expectedAttemptNo`로 같은 규칙을 적용한다.
4. **결제 결과는 chargeKey와 함께 반영하고, 현재 시도 키와 다르면 버린다.** (비관론자 2번)
   - 구독 반영 메서드는 `ChargeOutcome`이 아니라 chargeKey와 subscription 패키지의 sealed 결과(`Approved(approvedAt)`, `Declined`, `Pending`, `NoPayment`)를 받는다. 키가 행의 `cycle_no`, `attempt_no`로 만든 현재 시도 키와 다르면 상태를 바꾸지 않는다(표 23번).
   - 인라인 경로(`charge()` 직후)도 결과를 직접 넘기지 않고 `sync(subscriptionId)`만 부른다.
   - 이유: 멈춰 있던 스레드가 이미 끝난 시도의 결과를 늦게 반영하면 진행 중인 다음 시도의 펜스가 비워진다. 그러면 해지가 통과하고 그 뒤에 승인되면 해지 후 청구가 된다.
5. **결제 확정은 pull로 반영한다** (안건 4, 토론 없이 결정). 아래 "토론 없이 결정한 안건" 참고.
   - `sync(subscriptionId)`는 charge 패키지에 두고 트랜잭션 밖에서 부른다. **현재 시도 키**(`cycle_no`, `attempt_no`에서 만든 키)의 결제를 읽어 sealed 결과로 바꿔 구독 반영 TX에 넘긴다. 몇 번 다시 실행해도 결과가 같다.
   - `pending_charge_key`가 비어 있어도 현재 시도 키를 읽는다. 결제 TX는 커밋됐는데 구독 반영 전에 서버가 죽은 경우, 다음 실행에서 다시 읽어 복구해야 하기 때문이다. 재시도 진입에서 Approved를 발견하면 항상 "그 키로 ACTIVE 반영"으로 보낸다(표 18번, 구현은 MVP 4). (비관론자 3번)
   - 결제 행이 없고 `attempt_no == 1`인 송신 중 시도(첫 결제, 갱신)는 같은 키로 `charge()`를 다시 불러 완료한다(멱등). `attempt_no >= 2`는 이 경로에서도 부르지 않는다(원칙 7).
   - 호출 흐름은 항상 **구독 전이 TX 커밋 → (TX 밖) `charge()` → (TX 밖) `sync()` → 구독 반영 TX**다. 전이 TX가 예외로 끝나면(`@Version` 충돌 포함) `charge()`를 부르지 않는다. 키는 TX 메서드의 반환값으로만 받는다.
6. **구독 테이블과 payment 테이블을 한 트랜잭션에서 함께 쓰지 않는다.** 잠금 순서 문제를 없앤다. 구독 쪽 결과 타입은 subscription 패키지에 두어 subscription이 charge를 참조하지 않게 한다.
7. **이번 기능에서는 새 키로 재시도를 송신하는 코드를 열지 않는다.** (사용자 결정, 비관론자 4번)
   - 이번에 만드는 것: 상태 머신(표 1~12, 19~23번), PAST_DUE 진입(표 9번).
   - 이번에 만들지 않는 것: 재시도 시작(표 14번)과 재시도 결과 반영(표 13, 15~18번, 표 11번의 PAST_DUE 부분). **설계로만 남기고 구현과 테스트는 MVP 4에서 송신을 열 때 함께 한다.** 이번 기능에서 도달할 수 없는 코드라서 엔티티 메서드와 단위 테스트도 만들지 않는다. (사용자 결정. 처음에는 규칙을 코드로 고정하려고 메서드만 두자고 했으나 철회했다.)
   - 이유: 거절된 orderId와 같은 멱등키로 재요청이 오면 토스가 어떻게 응답하는지 문서에 없다(미확인). 멈춰 있던 송신이 재시도(A2) 뒤에 도착해 A1이 승인될 수 있다. 그러면 한 주기에 승인 2건이 되고, 남는 것은 `PAYMENT_CONFLICT` 로그뿐이다. 재시도 직전 lookup과 COOL_DOWN은 이 틈을 완전히 막지 못한다(lookup 시점에는 거절이고 송신은 그 뒤에 도착할 수 있다).
   - 순서: **MVP 3**에서 테스트 키로 토스의 응답을 확인한다 → **MVP 4**에서 그 결과를 보고 재시도 송신을 연다.
   - MVP 4에서 송신을 열 때의 필수 조건(이상주의자 제안 3, 모두 채택하되 이번에는 구현하지 않음):
     1. 이전 시도가 FAILED
     2. `resolved_at + COOL_DOWN <= now` (COOL_DOWN > RESOLVE_DELAY + read-timeout)
     3. 트랜잭션 밖에서 이전 시도의 orderId를 lookup. Declined, NotFound면 진행, Approved면 반영하고 재시도 취소, Unknown이면 보류
     4. 구독 행의 재시도 시작 전이가 `@Version`, 송신 가드, `expectedAttemptNo`를 통과
     5. 해지 예약 없음
     6. 토스가 거절된 orderId의 재요청에 어떻게 응답하는지 확인된 결과를 반영하고, `ChargeService`와 Resolver 양쪽에 송신 직전 상태 재조회(REQUESTED, UNKNOWN일 때만 송신)를 넣어 틈을 좁힌다.
   - 결과: 이번 기능이 끝난 시점에 갱신이 거절된 구독은 PAST_DUE에 머문다. 재시도는 MVP 4 전까지 일어나지 않는다. 사용자 해지(표 12)나 이용 기간 종료로만 상태가 바뀐다.
8. **chargeKey 형식은 `SUB-{subscriptionId}-C{cycleNo}-A{attemptNo}`다.** 대문자 ASCII와 숫자만 쓴다(리뷰 로그 6번의 콜레이션 문제를 피한다). 첫 결제는 `C1-A1`, 갱신은 `C{n+1}-A1`이다. ADR 0001 원칙 3의 예시 `SUB-{id}-{주기}`를 이 형식으로 대체한다.
   - `cycle_no`, `attempt_no`는 구독 행에 둔다. 주기와 시도 테이블은 만들지 않는다(보류, 정산 대사 단계). 키 형식을 지금 확정하므로 나중에 만드는 테이블이 기존 키를 그대로 채울 수 있다. 키의 만들기와 파싱은 한 곳(값 타입)에 모은다.
   - 금액, 주문명, 빌링키는 구독 행에 스냅샷으로 둔다(비교에서 `ChargeRequestMismatchException`이 나지 않게). 빌링키는 mock 평문이며 로그와 예외 메시지에 넣지 않는다(ADR 0001 쟁점 2와 같다).
9. **앵커는 고정하고, PAST_DUE 동안 독촉 기간만큼 이용을 허용한다.** (한 결정, 안건 3)
   - `period_n = anchor + (n-1)개월`. 시간대는 Asia/Seoul이고 말일은 앵커에서 매번 계산해서 보정한다. 직전 기간 끝에 한 달을 더해 이어 붙이면 결제일이 밀린다(31일 → 28일 → 28일).
   - 첫 앵커는 결제사의 `approvedAt`이다. 돈이 실제로 나간 시각이고 Mock에서도 주입된 Clock으로 만들어 테스트가 결정적이다.
   - 앵커를 고정하면서 PAST_DUE 이용을 막으면 막은 일수만큼 돈은 받고 서비스는 주지 않게 된다. 그래서 이용을 허용하되 상한을 둔다.
   - `accessUntil(subscription, now)`는 저장하지 않는 계산이다. (비관론자 §10-5의 정의 오류를 반영해 **PAST_DUE는 `period_end` 기준**이다. 이 시점의 행은 아직 지난 기간을 가지고 있어서 `period_start`를 쓰면 PAST_DUE에 들어가는 순간 이용이 막힌다.)

     | 상태 | accessUntil |
     |---|---|
     | ACTIVE | `period_end`. 갱신이 송신 중이면 `period_end + RENEWAL_GRACE` |
     | PAST_DUE | `period_end + DUNNING_WINDOW` |
     | PENDING, CANCELED | 없음 |

   - 기본값 (중재자 판단, 사업 협의 전까지의 임시값이며 상수 한 곳에 둔다): `RENEWAL_GRACE = 24시간`(재전송 기한과 맞춤), `DUNNING_WINDOW = 7일`.
   - `RetryPolicy`: 재시도 일정은 `period_end` 기준 D+1, D+3, D+5이고 최대 3회다. 직전 시도가 아니라 주기 끝이 기준이라 미확정이 길어져도 독촉 기간이 늘어나지 않는다. 소진은 **마지막 시도가 FAILED로 확정됐을 때만** 판단한다. UNKNOWN에서는 판단하지 않는다. `now`를 인자로 받고 Clock을 직접 쓰지 않는다. 이번 기능에서는 PAST_DUE 진입 때의 첫 `next_retry_at` 계산만 쓰고, 소진 판단과 다음 일정 갱신은 MVP 4에서 구현한다.
   - NEEDS_REVIEW 상태는 만들지 않는다. 오래 미확정인 구독은 `accessUntil`이 이용 상한을 정하고, 로그(`RESOLVE_STUCK` 등)가 사람에게 알린다.
10. **고객당 live 구독은 DB가 하나로 강제한다.** (안건 5, 비관론자 5, 6번 반영)
    - 생성 컬럼 `live_customer_key`(상태가 CANCELED면 NULL, 아니면 `customer_key`)에 일반 UNIQUE를 건다. MySQL의 UNIQUE는 NULL을 여러 개 허용한다. 엔티티에는 매핑하지 않는다. PENDING도 live로 센다. 안 세면 정체된 첫 결제 뒤에 재가입이 가능해져 첫 결제가 두 번 나갈 수 있다.
    - 신청 INSERT가 UNIQUE에 걸리면(writer TX 밖에서 잡고 새로 조회한다) 그 고객의 live 구독을 찾는다. 신청 내용(금액, 빌링키)이 같으면 그 구독을 돌려주고, 다르면 409다. ADR 0001 원칙 8과 같은 모양이다.
    - **INSERT와 `pending_charge_key` 채우기는 한 트랜잭션이다.** 따로 하면 INSERT 직후 크래시로 `pending`이 없는 PENDING 고아가 생기고, 동기화도 이 구독을 보지 않아 고객이 다시 가입하지 못한다. 충돌해서 기존 구독을 돌려주는 경로도 "첫 시도 보장 후 같은 키로 `charge()`"를 거친다.
    - **CANCELED 재활성화 금지는 이 UNIQUE의 전제다.** CANCELED에서 나가는 전이가 없어야 live 값이 NULL에서 값으로 바뀌는 일이 INSERT에서만 생기고, 동기화 TX가 UNIQUE 위반으로 무한 반복되지 않는다.
    - 클라이언트 `Idempotency-Key`는 보류한다. 돈에 관한 불변식에는 필요 없다(중재자 판단).

## 토론 없이 결정한 안건

### 안건 2: 해지 후 승인은 감지와 기록만 한다 (결제사 취소 API는 이번 범위 밖)
- 결정: 현재 시도 키의 승인이 CANCELED 구독에 도착하면 상태를 바꾸지 않고 `CHARGED_AFTER_CANCEL` ERROR 로그를 남긴다(표 19번). CANCELED는 종결 상태로 유지하고, 재활성화는 하지 않는다.
- 이유:
  1. 원칙 2가 지켜지면 해지 후 승인은 FAILED→APPROVED 뒤집힘에서만 생긴다. 현재 코드에서 이 전이는 늦게 도착한 in-flight 응답이 유일한 경로이고 Mock PG로 재현할 수 없다. 범위가 아주 좁다.
  2. 결제사 취소는 결제 흐름 하나만큼 큰 작업이다. 취소 요청의 타임아웃도 UNKNOWN이고, 취소 멱등키, 취소 상태, lookup의 CANCELED 매핑 개정(지금은 승인 + `PAYMENT_CONFLICT`라서 우리가 취소한 건까지 충돌로 남는다), Mock 시나리오가 필요하다. 토스 취소 API의 동작도 확인하지 못했다.
  3. 재활성화는 안건 5와 충돌한다. 고객이 이미 새 구독을 만들었다면 UNIQUE에 걸린다.
- 비용: 환불은 일어나지 않는다. 환불 의무를 DB에 기록하는 `refund_obligation` 테이블(이상주의자 제안 4)은 보류한다. 그래서 고객에게 돌려줄 돈의 기록이 ERROR 로그에만 남고 알림 연동이 없다. 운영자가 로그를 확인해야 하고, 로그 보존 기간이 지나면 사라진다.

### 안건 4: 결제 확정은 pull로 구독에 반영한다
- 결정: 원칙 5의 `sync(subscriptionId)`가 구독에 기록된 키로 결제를 읽는다. 이상주의자 제안 2의 변경 감지 스윕(`payment_event` outbox, `resolved_at` 스윕)은 **넣지 않는다.**
- 이유:
  1. push(이벤트)는 크래시하면 이벤트가 사라진다. pull은 상태가 DB에 남아 있어서 다시 실행하면 복구된다(S7).
  2. 스윕을 넣지 않은 근거는 현재 코드의 확인 결과다. FAILED가 APPROVED로 바뀌는 경로는 `PaymentWriter.applyApproved`의 FAILED 분기 하나뿐이고, 입력은 `ChargeService`의 승인 응답, Resolver의 조회, Resolver의 재전송이다. FAILED를 다시 조회하는 코드는 없다. 모순된 결과가 같은 orderId에 두 번 와야 하는데 `charge()` 스레드가 원인이 되기는 어렵다(읽기 타임아웃 65초, 확정 잡은 5분 뒤). 이 전이를 검증하는 테스트는 `applyResult`를 직접 부르는 단위 테스트 둘뿐이고, Mock PG는 거절을 원장에 기록하지 않아 거절 뒤 승인을 재현할 수 없다. 재현하지 못하는 장치는 검증할 수 없다.
  3. 재시도 직전 이전 시도 lookup(원칙 7의 필수 조건 3)이 재시도 쪽의 한 갈래를 줄인다. 스윕 없이 된다.

## 판정 요약

**채택**
- 구독 행 `@Version` 펜스, `pending_charge_key`, 엔티티 가드와 DB CHECK, 청구 유효성 포트 없이 불변식으로 대신함 (이상주의자 1)
- 시간 이벤트의 기대값 (`expectedCycleNo`, `AlreadyRenewed`) (비관론자 1)
- chargeKey가 현재 시도 키와 다르면 반영하지 않기, `sync(subscriptionId)`만 호출 (비관론자 2)
- 현재 시도 키를 pending과 무관하게 읽어 Approved를 ACTIVE로 복구 (비관론자 3)
- 키 형식 `SUB-{id}-C{n}-A{m}`, 앵커 고정, 7일 독촉 기간 이용 허용, `accessUntil` (이상주의자 3, 비관론자 §10-5 정의 수정 반영)
- 재시도 시작 조건 5개 + 송신 직전 재조회(MVP 4에서 송신을 열 때의 필수 조건으로)
- 고객당 live 구독 1개 생성 컬럼 + UNIQUE, INSERT와 pending을 한 TX에 (이상주의자 5, 비관론자 5)
- 첫 결제 미확정 장기화는 자동 포기 없이 알려진 한계 + 운영 절차 (비관론자 6)
- 해지 예약이 갱신 승인 뒤에도 유지됨, 해지 예약 + 갱신 거절은 CANCELED (이상주의자 M1, M2, 중재자 판단)
- 이미 처리된 이벤트는 sealed 결과, 허용되지 않은 전이만 예외 (이상주의자 M6, 중재자 판단)

**보류**
- 주기, 시도 테이블 분리 (정산 대사 단계. 키 형식은 지금 확정)
- 재시도 시작과 재시도 결과 반영의 구현, 새 키로 재시도를 송신하는 코드 (MVP 4. MVP 3에서 토스 응답 확인 뒤. 규칙은 이 ADR에 설계로 남김)
- `refund_obligation` 테이블 (결제사 취소와 함께)
- 클라이언트 `Idempotency-Key`

**기각**
- 청구 유효성 포트 (불변식이 대신하며 죽은 코드가 된다)
- 변경 감지 스윕과 `payment_event` outbox (경로가 거의 없고 재현할 수 없다)
- CANCELED 재활성화 (안건 5의 UNIQUE와 충돌)
- NEEDS_REVIEW 상태 (`accessUntil`과 로그가 대신한다)
- 결제사 취소 API (안건 2)

## ADR 0001 "시도 2를 도입할 때의 필수 조건"과의 대응

| 조건 | 이번 ADR에서의 처리 |
|---|---|
| 1. 이전 시도를 lookup으로 종결 확인 | 원칙 7의 필수 조건 3. MVP 4에서 구현 |
| 2. 송신 직전 조건부 전이 | 구독 행의 `@Version`, `pending_charge_key` 가드, `expectedAttemptNo`. 2차 방어는 결정적 키와 `UNIQUE(charge_key)` |
| 3. 잠금 순서 | 원칙 6. 구독과 payment를 한 TX에서 쓰지 않으므로 구독 안의 순서만 남는다 |
| 4. NEEDS_REVIEW와 그동안의 노출 | NEEDS_REVIEW 없이 `accessUntil`과 `RESOLVE_STUCK` 로그 |
| 5. 해지 후 승인과 갱신일 당일 해지 | 안건 2 (감지와 기록). 갱신 시작 뒤 해지는 예약으로 기록하고 새 기간 끝에 적용 |
| 6. `next_resolve_at`으로 확정 잡 중복 실행 방지 | MVP 4. 확정 잡의 중복은 같은 orderId만 쓰므로 이중 청구가 아니고, 재시도 시작의 중복은 구독 `@Version`이 막는다 |
| 7. 결제사 측 취소 상태를 재청구할지 | 재청구하지 않는다(승인으로 기록). 재청구하면 그것이 시도 2다 |

## 알려진 한계 (README에 같은 내용을 명시)
1. **CANCELED로 끝난 뒤 FAILED→APPROVED가 오는 경우**는 수용한다. 첫 결제 거절과 재시도 소진은 CANCELED가 되며 되돌릴 방법이 없다. 감지는 `sync()`가 호출될 때만 하고(`CHARGED_AFTER_CANCEL` ERROR), 환불은 일어나지 않는다. **토스가 거절, ABORTED, EXPIRED 뒤에 같은 orderId를 DONE으로 바꾸는지는 미확인이다.**
2. **변경 감지 스윕이 없다.** 잘못 FAILED가 된 결제를 먼저 찾아 고치는 코드가 없다. 재시도 직전 lookup(MVP 4)만 이 틈을 줄인다.
3. **재시도 송신이 없다.** 갱신이 거절된 구독은 MVP 4 전까지 PAST_DUE에 머문다. 시나리오 "멈춰 있던 송신이 재시도 뒤에 도착해 한 주기에 승인 2건"은 MVP 3의 확인 결과를 보고 MVP 4에서 닫는다.
4. **첫 결제가 끝내 확정되지 않으면**(결제사 장애 24시간 이상, `RESEND_DEADLINE_PASSED`, `RESOLVE_STUCK`) `pending_charge_key`가 계속 차 있어서 CHECK 때문에 고객은 해지도 재가입도 못 한다. 자동으로 포기하지 않는다. NotFound로 청구가 없음을 확인한 건에 한해 운영자가 `status`, `pending_charge_key`, `canceled_reason` 세 컬럼을 **한 UPDATE에서 함께** 바꾸는 절차를 남긴다. (`pending_charge_key`가 있는 채로 `status`만 CANCELED로 바꾸면 `chk_subscription_canceled_no_pending`에 걸리고, `canceled_reason` 없이 바꾸면 `chk_subscription_canceled_reason`에 걸린다. 2단계 테스트가 세 컬럼 UPDATE의 통과를 확인한다.)
5. **확정 지연 중에는 이용 기간과 요금이 어긋날 수 있다.** 예를 들어 D+5 재시도가 UNKNOWN으로 남았다가 D+9에 승인되면, D+7~D+9는 이용이 막혔는데 한 달 요금을 받는다. `RENEWAL_GRACE`(24시간)를 넘긴 갱신 미확정도 같다.
6. **환불은 일어나지 않는다.** 해지 후 승인과 한 주기 승인 2건(`DOUBLE_CHARGED` 후보)은 ERROR 로그로만 남고, 알림 연동이 없다.
7. 기간 종료와 동기화를 부르는 스케줄러가 없다. 직접 호출한다.
8. **PAST_DUE에서 갱신 시도(`A1`)의 늦은 승인은 복구하지 않는다.** 갱신이 거절되어 PAST_DUE가 된 뒤 같은 키가 FAILED→APPROVED로 뒤집혀도 이번에는 ACTIVE로 돌리지 않는다(표 18번은 MVP 4). 상태는 그대로이고 ERROR 로그만 남는다. 돈은 받았는데 구독이 PAST_DUE에 머물 수 있다. 재시도 키(`A2` 이상)와 상관없이 갱신 시도 `A1`만으로도 열리는 경로다.
9. 앞의 한계 외에 ADR 0001의 알려진 한계(빌링키 평문 저장, 결제사 요청과 응답 원문 로그 보관, 거절 허용 목록)가 그대로 유효하다.

## 구현 시 검증 (확인하지 못한 가정)
- **확인됨 (2단계)** Hibernate 7 `ddl-auto=validate`는 엔티티에 매핑하지 않은 생성 컬럼(`live_customer_key`)을 무시한다. 컨텍스트가 정상 기동하고 `BOOLEAN` 컬럼도 검증을 통과했다. 읽기 전용 매핑 같은 대안은 필요 없다.
- **확인됨 (2단계)** MySQL 8.4에서 VIRTUAL 생성 컬럼에 UNIQUE를 걸 수 있어서 STORED로 바꿀 필요가 없었다. CHECK 두 개와 생성 컬럼 UNIQUE가 Testcontainers(`mysql:8.4`)에서 실제로 강제된다.
- **확인됨 (2단계)** CHECK 위반(MySQL 에러 3819)은 Spring이 번역하지 못해 `UncategorizedSQLException`으로 나온다. UNIQUE 위반은 `DataIntegrityViolationException`(`DuplicateKeyException`)이다. 애플리케이션 코드에서 CHECK 위반을 잡을 일은 없다(엔티티 가드가 먼저 막는다). 어느 제약에 걸렸는지는 메시지의 제약 이름으로 구분한다.
- 신청 INSERT의 UNIQUE 위반 예외가 `persist` 시점에 나는지 커밋 시점에 나는지. 같은 TX 안에서 잡으면 rollback-only 때문에 `UnexpectedRollbackException`이 날 수 있다. `ChargeService`처럼 writer TX 밖에서 잡고 새 조회로 찾는다.
- `@Version` 충돌 예외가 flush에서 나는지 커밋에서 나는지, 프록시 밖으로 나오는 형태. 예외가 나면 `charge()`를 부르지 않아야 한다(원칙 5).
- 커밋은 됐지만 응답이 유실된 경우, 같은 호출을 다시 하면 `AlreadyStarted`와 같은 키가 돌아오는지(테스트).
- **확인됨 (1단계)** 시간대와 말일 처리(앵커 + n개월, Asia/Seoul)의 경계값 테스트. `BillingPeriodTest`가 1월 31일 앵커, 윤년, UTC와 서울의 날짜 차이를 확인한다.
- **토스 문서 확인 (MVP 3, 테스트 키)**: 거절된 orderId를 같은 멱등키로 다시 요청하면 거절을 재생하는지, 새로 처리하는지. 거절, ABORTED, EXPIRED 뒤에 DONE이 되는 경우가 있는지.

## 구현 계획으로 넘기는 것
승인 후 계획 모드에서 정한다: V2 마이그레이션(구독 테이블, 생성 컬럼, CHECK), `SubscriptionStatus`와 엔티티, subscription 패키지의 sealed 결과 타입과 `RetryPolicy`, charge 패키지의 구독 결제 서비스와 `sync`, ArchitectureTest 규칙, 시나리오 테스트와 `docs/scenarios.md`.

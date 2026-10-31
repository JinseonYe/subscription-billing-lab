# 3단계 현실주의자 검토: 결제 1건 흐름과 Mock PG

- 날짜: 2026-10-29
- 입력: 설계 초안, 1단계 이상주의자 제안 요약, 2단계 비관론자 지적 요약, `CLAUDE.md`
- 역할: design-realist. MVP 범위로 정리한다. 이중 청구 문제는 미루지 않는다.

---

## 0. 판단 기준

이번 범위에는 구독, 재시도 정책, 스케줄러가 없다. 그래서 이중 청구를 막는 핵심 방어선을 하나로 모은다.

> **청구 의도(charge_key) 하나에는 orderId가 딱 하나다. orderId는 TX1에서 한 번 만들고, 다시 만들지 않는다. 재전송은 항상 같은 orderId와 같은 멱등키로 한다.**

- 이 규칙만 지켜도 결제사에서 실제 청구는 최대 1건이다. 같은 orderId를 다시 보내면 결제사가 멱등키나 `DUPLICATED_ORDER_ID`로 막는다.
- DB에서도 `UNIQUE(charge_key)`로 강제한다. 버그가 있어도 두 번째 시도 행이 생기지 않는다.
- 나머지 장치는 이 규칙이 깨지지 않게 하거나, 기록이 PG 사실과 어긋나지 않게 하는 데만 쓴다. 기록이 어긋나면 구독 기능에서 잘못된 재시도로 이어진다.
- 시도 2(새 orderId)를 만드는 경로는 이번 범위에 없다. 그 경로가 생길 때 필요한 장치(펜스, NEEDS_REVIEW, 해지 사후 처리)는 구독 기능으로 미룬다. 이번 범위에서는 그 장치가 막을 위험 자체가 생기지 않는다.

---

## 1. 항목별 판단

### 1-1. 이상주의자 제안

| ID | 항목 | 판단 | 이유(한 줄) |
|---|---|---|---|
| I1-a | charge와 payment_attempt 테이블 분리 | 확장 단계로 미룸 | 시도가 항상 1개라 테이블이 하나면 된다. 분리는 시도 2를 만드는 구독 재시도 기능에서 한다 |
| I1-b | 함수 기반 유니크 인덱스("미종결 1개", "APPROVED 1건") | 불필요 | `UNIQUE(charge_key)`가 더 강한 제약(행 자체가 1개)이고, MySQL 함수 인덱스는 입문자가 설명하기 어렵다 |
| I1-c | 새 시도 전 charge FOR UPDATE | 확장 단계로 미룸 | 새 시도를 만드는 경로가 없다 |
| I2-a | PREPARED/DISPATCHED 세분화 | 불필요 | 송신 전(②)과 송신 후(③)의 복구가 lookup에서 NotFound면 같은 키로 재전송하는 것으로 똑같아서, 구분해도 동작이 달라지지 않는다 |
| I2-b | ABANDONED | 불필요 | 포기 후 새 시도를 하는 경로가 없다. 이 상태가 있으면 P1의 이중 청구 위험이 생긴다 |
| I2-c | NEEDS_REVIEW | 확장 단계로 미룸 | 스케줄러가 없어 무한 재시도 부하가 없다. 검토 중 구독을 어떻게 다룰지는 구독 기능에서 정한다 |
| I2-d | 시간이 지났다고 UNKNOWN을 자동으로 FAILED로 바꾸지 않음 | MVP에서 반영 | FAILED가 잘못 찍히면 이후 재시도가 이중 청구로 이어진다. 구현 비용은 0이다(그런 코드를 안 쓰면 된다) |
| I2-e | dispatch_count, first/last_dispatched_at | 불필요 | 기한 기준을 `requested_at`으로 잡으면 첫 송신보다 항상 이르거나 같아서 보수적이고 안전하다. 디버깅용 정보는 로그로 대신한다 |
| I2-f | resolve_attempts, next_resolve_at | 확장 단계로 미룸 | 백오프와 선점은 스케줄러가 생겨 반복 실행할 때 필요하다 |
| I2-g | lookup 상태 매핑(READY/IN_PROGRESS→Unknown 등) | MVP에서 반영 | 비종결 상태를 Declined로 보면 FAILED가 잘못 찍힌다. 매핑 표 한 장으로 끝난다 |
| I3-a | pg_exchange(모든 호출을 DB에 기록) | 확장 단계로 미룸 | 구조화된 로그로 충분하다. 테이블, 별도 트랜잭션, 실패 격리까지 만들면 MVP에 비해 과하다 |
| I3-b | PgResult.Approved에 amount를 담아 금액 불일치 탐지 | 확장 단계로 미룸 | 금액은 우리가 보내고 Mock은 그대로 돌려준다. 금액 불일치는 정산 대사 주제다 |
| I3-c | reconciliation_issue 테이블 | 확장 단계로 미룸 | 정산 대사(핵심 주제 3, 확장)에서 만든다. 지금은 고정 접두어를 붙인 ERROR 로그로 대신한다 |
| I4-a | lease_owner/lease_until 행 선점 | 불필요 | 이번 범위에서는 확정 잡이 테스트에서만 한 번씩 돈다. 스케줄러 단계에서도 P6의 조건부 UPDATE가 더 단순하다 |
| I4-b | 기동 시 "확정 대기 > 호출 최대 소요" 검증 | 불필요 | 확정 대기와 재전송 기한을 설정이 아니라 코드 상수로 둔다. 겹쳐도 같은 orderId라 409→Unknown이고 기존 상태를 바꾸지 않는다 |
| I4-c | Mock 원장 DB화 | 불필요 | P7 지적대로 `@Profile`이 엔티티 스캔에는 적용되지 않아 운영 스키마가 오염된다 |
| I4-d | Mock 409 흉내 | MVP에서 반영 | 실제 토스 동작(처리 중 재요청은 409)을 흉내 내지 않으면 재전송 테스트가 이중 청구 버그를 놓친다 |
| I5-a | ResolutionPolicy 순수 함수(Apply/Resend/Wait/Escalate/Abandon) | 불필요 | Escalate와 Abandon이 없어 분기가 3개뿐이다. 확정 잡 안의 `when` 하나로 충분하다 |
| I5-b | 요금 스냅샷 | 불필요 | `amount`, `order_name`이 이미 payment 행에 있다. 엔티티에서 `val`로 두면 바뀌지 않는다 |
| I5-c | request_fingerprint | 불필요 | 재전송 요청은 같은 행의 불변(`val`) 필드로만 만든다. 첫 송신과 같다는 것을 언어 차원에서 보장한다 |

### 1-2. 비관론자 지적

| ID | 항목 | 판단 | 이유(한 줄) |
|---|---|---|---|
| P1 | ABANDONED 후 새 시도로 인한 이중 청구 | MVP에서 반영(원인 제거) | ABANDONED와 시도 2 생성 경로를 만들지 않고, `UNIQUE(charge_key)`로 DB에서도 막는다. 펜스 UPDATE는 시도 2가 생길 때 만든다 |
| P2-a | PG가 확정한 사실은 무조건 기록(FAILED→APPROVED 허용) | MVP에서 반영 | 돈은 나갔는데 기록이 없으면 구독 기능에서 재시도 이중 청구로 이어진다 |
| P2-b | APPROVED 1건 유니크 대신 이슈 기록 | MVP에서 반영(축소) | 이슈 테이블 대신 `PAYMENT_CONFLICT` 접두어의 ERROR 로그만 남긴다. 상태 충돌을 예외로 던져 롤백하지 않는다 |
| P3-a | 해지와 송신 사이 틈(TOCTOU), 사후 처리 | 확장 단계로 미룸 | 이번 범위에는 해지 개념이 없다. "APPROVED는 항상 기록" 원칙(P2-a)은 지금 반영해 둔다 |
| P3-b | 잠금 순서 charge→attempt 고정 | 확장 단계로 미룸 | 잠글 테이블이 payment 하나뿐이다. ADR에 규칙으로 적어 둔다 |
| P4-a | charge()가 송신 후 실패를 `ChargeOutcome.Pending`(sealed)으로 반환 | MVP에서 반영 | 예외를 결제 실패로 해석해 재시도하는 것이 이중 청구의 직접 경로다. sealed 타입 3개로 끝난다 |
| P4-b | "@Version이 TX2와 확정 잡 경합을 막는다"는 초안 서술 정정 | MVP에서 반영 | 서술이 틀렸다. `@Version`은 동시에 진행 중인 트랜잭션끼리 덮어쓰는 것만 막는다. 늦게 커밋되는 쪽은 상태 조건 분기로 막는다 |
| P4-c | TX2는 기대 상태일 때만 반영하고, 아니면 no-op과 충돌 기록 | MVP에서 반영 | 아래 5-3의 반영 규칙 표로 정의한다 |
| P4-d | pg_exchange 실패 격리 | 불필요 | pg_exchange를 미루므로 해당 없다 |
| P5 | Declined allowlist, 비종결 lookup은 Unknown, 취소 상태는 Approved와 이슈 | MVP에서 반영 | FAILED가 잘못 찍히는 것을 막는다. 목록에 없는 코드는 Unknown이라 돈 문제가 생기지 않는다 |
| P6-a | next_resolve_at 조건부 UPDATE로 선점, 실행당 건수 상한 | 확장 단계로 미룸 | 스케줄러가 없다. 중복 실행돼도 같은 orderId라 이중 청구는 없다 |
| P6-b | Unknown은 종결 상태를 덮지 않음 | MVP에서 반영 | 늦게 온 원래 흐름의 409→Unknown이 확정 잡이 반영한 APPROVED를 덮거나 예외를 내는 것을 막는다 |
| P6-c | 원래 흐름은 송신 직전 경과 시간을 보고 넘었으면 송신 안 함 | 불필요 | orderId가 하나라 늦게 송신해도 멱등 응답이나 409/DUPLICATED→Unknown이고, P6-b 덕분에 기존 상태를 바꾸지 않는다 |
| P7-a | Mock 메모리 원장 유지 | MVP에서 반영 | I4-c 기각과 같은 이유다 |
| P7-b | 원장을 compute로 원자적으로 갱신, 처리 중이면 409, lookup은 IN_PROGRESS | MVP에서 반영 | get 후 put 경합으로 원장에 2건이 생기면 테스트가 이중 청구를 잘못 판정한다 |
| P7-c | 승인 기록은 지연 전에 | MVP에서 반영 | reset 후 다음 테스트가 오염되는 것을 막는다. 순서만 바꾸면 된다 |
| P7-d | orderId별 호출과 승인 카운터로 단언 | MVP에서 반영 | "청구 1건 이하"를 건 단위로 증명하는 유일한 수단이다 |
| P7-e | "처리 중 lookup 후 늦게 DONE" 시나리오 | MVP에서 반영 | IN_PROGRESS→Unknown 매핑과 재전송하지 않는 동작을 검증한다 |

### 1-3. 토론 쟁점

| # | 쟁점 | 판단 | 결정 내용 |
|---|---|---|---|
| 1 | NotFound일 때 재전송할지 | MVP에서 반영(조건부) | 같은 orderId와 멱등키로, `now - requested_at <= 24h`일 때만 재전송한다. 기한이 지나면 재전송하지 않고 상태를 유지하며 ERROR를 남긴다. FAILED로 바꾸지 않는다. "의도가 유효한지" 확인은 구독 기능에서 붙인다 |
| 2 | billing_key 저장 | MVP에서 반영(평문 컬럼) | 재전송에 필요하다. 빌링키 테이블이 없어서 불변 참조는 불가능하다. `val`로 두고 로그와 예외 메시지에 넣지 않는다. 암호화나 FK 교체는 빌링키 기능에서 한다 |
| 3 | 거부 목록인가 허용 목록인가 | MVP에서 반영 | allowlist다. 목록에 있는 코드만 Declined, 나머지 4xx는 Unknown이다 |
| 4 | attempt_no를 지금 넣을지 | 확장 단계로 미룸(두 역할과 다른 의견) | 컬럼을 빼고 `UNIQUE(charge_key)`로 둔다. 시도 2는 DB가 물리적으로 거부하는 편이 MVP에서 가장 강한 이중 청구 방어다. 6절에 절충안이 있다 |
| 5 | 재전송 전 구독 상태 확인 연결 지점 | 확장 단계로 미룸 | 구현체가 없는 포트를 미리 만들지 않는다. 사전 확인은 최선 노력이고 보장은 사후 처리라는 원칙만 ADR에 적는다 |
| 6 | 확정 잡 재시도 상한 | MVP에서 반영(최소) / 나머지는 미룸 | 재전송 기한 24h만 MVP에 넣는다. 확정 기한 72h, NEEDS_REVIEW, 백오프는 스케줄러 단계로 미룬다. 자동 FAILED는 없다 |
| 7 | 멱등키 15일 | MVP에서 반영 | 재전송 기한 24h(기준 `requested_at`)가 15일보다 훨씬 짧아 만료된 키로 재전송할 일이 없다. "orderId는 절대 새로 만들지 않는다"를 ADR에 적는다 |

---

## 2. 미루는 항목의 위험과 README 명시

### 2-1. 위험 표

| 미룬 항목 | 그동안 생기는 위험 | 이중 출금 가능성 | 대신 두는 것 |
|---|---|---|---|
| charge/attempt 분리, attempt_no, 펜스, FOR UPDATE | 한 청구 의도에 결제 시도가 1번뿐이라 카드 거절 후 재시도를 못 한다 | 없음. `UNIQUE(charge_key)`가 막는다 | ADR에 "시도 2를 도입할 때의 필수 조건" 명시 |
| NEEDS_REVIEW, 확정 기한 72h | 24h가 지나도 NotFound이거나 계속 Unknown인 건이 REQUESTED/UNKNOWN에 영원히 남는다 | 없음. 재전송하지 않는다 | ERROR 로그(`RESEND_DEADLINE_PASSED`)와 README의 수동 확인 쿼리 |
| next_resolve_at 선점, 백오프, 실행당 상한 | 확정 잡을 여러 번 동시에 돌리면 같은 건에 lookup이나 재전송이 중복된다(409, PG 호출 낭비) | 없음. 같은 orderId다 | 이번 범위에서는 테스트에서만 직접 호출 |
| pg_exchange | PG 요청과 응답 원문이 DB에 없어 분쟁 시 증빙이 약하다 | 없음 | orderId, 작업, HTTP 상태, 코드, 매핑 결과를 담은 구조화 로그(비밀값, 빌링키 제외) |
| reconciliation_issue, amount 비교 | PG 측 취소나 금액 불일치를 DB에서 집계하지 못한다 | 없음 | `PAYMENT_CONFLICT` 접두어의 ERROR 로그 |
| 구독 해지 확인, 사후 처리 | 해당 없음(구독이 없음) | 없음 | ADR에 구독 기능의 전제 조건으로 명시 |
| 빌링키 암호화 | DB에 빌링키가 평문으로 저장된다 | 없음(유출 위험) | mock-pg에서는 가짜 키만 쓴다. 실제 키를 쓰기 전에 교체 |
| Declined 코드 목록 확장 | 목록에 없는 실제 거절이 UNKNOWN으로 남는다 | 없음(보수적 처리) | allowlist에 출처 주석을 달고 단위 테스트 |

### 2-2. README "알려진 한계"에 붙여 넣을 문구

```markdown
## 알려진 한계
결제 1건 흐름(첫 기능) 기준. 아래 항목은 모두 **이중 청구를 일으키지 않는 쪽으로** 보수적으로 처리했다.

- **청구 의도당 결제 시도는 1번이다.** `charge_key`가 유니크라 카드 거절 후 재시도할 수 없다. 재시도는 구독 기능에서 시도 번호와 함께 도입한다(ADR 0001 "시도 2 도입 조건").
- **24시간이 지나도 결과를 모르는 결제는 자동으로 확정하지 않는다.** 결제사 조회가 계속 NotFound이거나 Unknown이면 재전송도, 실패 처리도 하지 않고 `REQUESTED`/`UNKNOWN`으로 남긴다. 확인 쿼리:
  `SELECT * FROM payment WHERE status IN ('REQUESTED','UNKNOWN') AND requested_at < NOW(6) - INTERVAL 24 HOUR;`
- **확정 잡은 스케줄러 없이 직접 호출한다.** 여러 인스턴스에서 동시에 돌리는 경우를 막는 선점 장치가 없다. 같은 orderId만 쓰므로 이중 청구는 없고, 결제사 호출이 중복될 수 있다.
- **결제사 요청과 응답 원문은 DB가 아니라 로그에만 남는다.** 상태 충돌(예: 실패로 기록한 뒤 승인 확인)은 `PAYMENT_CONFLICT` ERROR 로그로만 드러난다. 집계는 정산 대사 단계에서 한다.
- **결제사 측 취소(CANCELED/PARTIAL_CANCELED)는 승인으로 기록한다.** 환불 여부는 정산 대사 단계에서 맞춘다.
- **빌링키는 평문으로 저장한다.** mock-pg 전용 가짜 키만 쓴다. 실제 키를 쓰기 전에 빌링키 기능에서 암호화하거나 참조로 바꾼다.
- **거절로 보는 결제사 에러 코드는 허용 목록에 있는 것뿐이다.** 목록에 없는 거절 코드는 미확정(UNKNOWN)으로 남는다.
```

### 2-3. README "4역할 설계 토론" 표에 넣을 한 줄

| 설계 | 이상주의자 제안 | 비관론자 지적 | 현실주의자 판단 | 최종 결정 |
|---|---|---|---|---|
| 결제 1건 흐름 | charge/attempt 분리, 7상태, pg_exchange, lease | 상태 충돌을 예외로 처리하면 승인 기록이 사라짐, TX2 예외를 실패로 해석하면 이중 청구 | 테이블 1개, `UNIQUE(charge_key)`, 상태 4개 + FAILED→APPROVED 허용, ChargeOutcome.Pending, allowlist | (중재자 기입) |

---

## 3. 초안에서 줄일 복잡도

| # | 초안의 요소 | 문제 | 제거안 |
|---|---|---|---|
| C1 | `attempt_no` 컬럼과 `UNIQUE(charge_key, attempt_no)` | 항상 1인 값이 있으면 시도 2를 허용하는 것처럼 읽히고, 버그로 2가 들어가도 DB가 막지 못한다 | 컬럼을 삭제하고 `UNIQUE(charge_key)`로 바꾼다. `findByChargeKeyAndAttemptNo`는 `findByChargeKey`로 바꾼다 |
| C2 | `markUnknown(reason)`의 reason | 저장할 컬럼이 초안 스키마에 없다 | reason은 로그에만 남긴다. 컬럼은 추가하지 않는다 |
| C3 | §2 "`@Version`이 TX2와 확정 잡 경합을 막는다" | 틀린 설명이라 입문자가 잘못 배운다 | `@Version`은 유지하되, 동시 트랜잭션의 덮어쓰기 방지용으로 설명을 고친다. 늦게 커밋되는 쪽 처리는 5-3의 반영 규칙 표로 한다 |
| C4 | 확정 대기 5분(설정값처럼 서술) | 설정으로 만들면 다른 값과의 관계 검증이 필요해진다 | `RESOLVE_DELAY = 5분`, `RESEND_DEADLINE = 24시간`을 charge 패키지의 코드 상수로 둔다. 설정은 HTTP 타임아웃만 남긴다 |
| C5 | "다른 상태면 ERROR 로그" 후 엔티티 예외 | 상태 충돌이 예외로 올라가 TX2 롤백, 호출자 예외로 이어진다(P4) | 쓰기 계층(writer)이 현재 상태를 보고 먼저 분기한다. 엔티티 예외는 버그를 감지할 때만 난다 |
| C6 | MockScenario를 어떻게 고르는지 미정 | orderId가 TX1 안에서 만들어져 테스트가 미리 알 수 없다 | `MockPgLedger.assignScenario(customerKey, scenario)`로 고객 키 기준으로 지정한다. 기본값은 APPROVE_OK다 |
| C7 | PgResult.NotFound가 approve 경로에서 나올 가능성 | 분기가 늘어난다 | 매핑 함수가 approve 경로에서는 NotFound를 만들지 않는다. writer의 `when`에서 NotFound는 Unknown과 같은 분기로 묶는다(else 없이) |

이상주의자 제안 중 MVP에서 받지 않은 것들(테이블 3개 `charge`, `pg_exchange`, `reconciliation_issue`, 상태 3개 `PREPARED/DISPATCHED/ABANDONED`, 컬럼 7개, `ResolutionPolicy`, lease)이 이번 판단으로 줄인 복잡도의 대부분이다.

---

## 4. 이중 청구 경로 점검(미루지 않은 근거)

| 경로 | MVP 방어 | 검증 테스트 |
|---|---|---|
| 한 청구 의도에 orderId가 2개 생김 | `UNIQUE(charge_key)`, orderId는 TX1에서만 생성(`val`), 시도 2 경로 없음 | 같은 charge_key로 두 번 요청하는 시나리오와 동시 요청 테스트 |
| 같은 orderId가 PG에서 두 번 승인됨 | 같은 멱등키(=orderId). Mock은 compute와 409로 토스 동작을 재현 | MockPgLedgerTest 동시 approve, 시나리오 4와 7 |
| 호출자가 예외나 지연을 실패로 보고 다른 키로 재시도 | `ChargeOutcome.Pending`. 송신 후 예외는 밖으로 던지지 않음. charge_key는 호출자가 결정적으로 만든다(ADR) | ChargeServiceTest(MockK) |
| 실제로는 승인됐는데 FAILED로 기록되어 이후 재시도 | allowlist, 비종결 lookup은 Unknown, 200인데 DONE이 아니면 Unknown, FAILED→APPROVED 허용 | TossResponseMapperTest, PaymentTest, PaymentWriterTest |
| 늦게 온 응답이 확정 결과를 덮어씀 | 반영 규칙 표(Unknown은 종결 상태를 덮지 않음), `@Version` 충돌은 Pending 또는 건너뛰기 | PaymentWriterTest, 시나리오 7 |
| 만료된 멱등키로 늦은 재전송 | 재전송 기한 24h(`requested_at` 기준) | 재전송 기한 시나리오 |

---

## 5. 최종 MVP 설계 변경 목록

중재자가 초안에 그대로 반영할 수 있도록 절 단위로 적는다.

### 5-1. 핵심 흐름(초안 §1 변경)

**`ChargeService.charge(cmd): ChargeOutcome`** (트랜잭션 없음)
1. `findByChargeKey(cmd.chargeKey)`로 기존 행이 있으면 **PG를 호출하지 않고** 그 상태에 맞는 Outcome을 반환한다.
2. TX1 `PaymentWriter.createRequested(cmd)`: orderId를 생성하고 INSERT한다. `DataIntegrityViolationException`(charge_key 중복)이 나면 트랜잭션 밖에서 다시 조회해 1과 같이 반환한다.
3. `try { pgClient.approve(req); writer.applyResult(id, result) } catch (e: Exception) { 로그; return Pending(id) }`
   - TX1 커밋 뒤의 모든 예외(낙관적 잠금 충돌, DB 장애 등)는 `Pending`이다. 예외를 호출자에게 던지지 않는다.
   - TX1 이전의 예외는 그대로 던진다. 아무것도 보내지 않았으니 같은 charge_key로 재시도해도 안전하다.
4. 반영 후 상태를 Outcome으로 바꿔 반환한다.

**`sealed interface ChargeOutcome`** (charge 패키지, 추가)
- `Approved(paymentId: Long)`
- `Declined(paymentId: Long, code: String)`
- `Pending(paymentId: Long)`: REQUESTED, UNKNOWN, 또는 반영 실패
- 상태→Outcome 변환: APPROVED→Approved, FAILED→Declined, REQUESTED/UNKNOWN→Pending

**`PaymentStatusResolver.resolvePending()`** (트랜잭션 없음, 변경)
- 대상: `status IN (REQUESTED, UNKNOWN) AND requested_at < now - RESOLVE_DELAY(5분)`, `requested_at` 오름차순
- 건마다 try/catch로 감싼다. 예외가 나면 로그를 남기고 다음 건으로 넘어간다.
- `lookup(orderId)` 결과로 분기(`when`, else 없음):
  - `Approved`, `Declined` → `applyResult`
  - `Unknown` → 변경 없음(INFO 로그)
  - `NotFound`이고 `now - requested_at <= RESEND_DEADLINE(24h)` → 행의 불변 필드로 **같은 orderId와 멱등키**로 approve를 재전송한 뒤 `applyResult`
  - `NotFound`이고 기한이 지났으면 → 재전송하지 않고 상태를 유지하며 `ERROR RESEND_DEADLINE_PASSED orderId=…`를 남긴다
- 상수: `RESOLVE_DELAY = Duration.ofMinutes(5)`(주석: read-timeout 65초보다 충분히 길어야 함), `RESEND_DEADLINE = Duration.ofHours(24)`(주석: 토스 멱등키 15일보다 짧아야 함)
- 시간이 지났다고 FAILED로 바꾸지 않는다. NEEDS_REVIEW도 없다.

### 5-2. 상태와 엔티티(초안 §3 변경)

상태는 `REQUESTED`, `UNKNOWN`, `APPROVED`, `FAILED` 4개로 초안과 같다. 전이를 하나 추가한다.

```
REQUESTED ─┬─> APPROVED
           ├─> FAILED ──> APPROVED   (추가: PG 승인 사실 우선, PAYMENT_CONFLICT ERROR 로그)
           └─> UNKNOWN ─┬─> APPROVED
                        └─> FAILED
```

- `approve(pgPaymentKey, approvedAt, now)`: REQUESTED, UNKNOWN, FAILED에서 허용. FAILED에서 올 때 `failure_code`와 `failure_message`는 이력으로 남겨 둔다.
- `fail(code, message, now)`: REQUESTED, UNKNOWN에서만 허용
- `markUnknown()`: REQUESTED에서만 허용. reason은 로그로만 남긴다.
- 그 밖의 전이는 `IllegalStateException`. writer가 미리 걸러내므로 이 예외가 나면 버그다.
- 요청 필드(`orderId`, `chargeKey`, `billingKey`, `customerKey`, `amount`, `orderName`, `requestedAt`)는 **`val`**로 둔다. 재전송 요청이 첫 송신과 같다는 것을 언어로 보장한다.
- 엔티티에 `toString`을 만들지 않는다. billingKey가 로그에 찍히지 않게 하기 위해서다.

### 5-3. `PaymentWriter.applyResult` 반영 규칙(추가)

행을 읽고 현재 상태와 결과로 분기한다. 상태 충돌은 예외로 던지지 않는다.

| 현재 상태 \ PgResult | Approved | Declined | Unknown / NotFound |
|---|---|---|---|
| REQUESTED | APPROVED | FAILED | UNKNOWN |
| UNKNOWN | APPROVED | FAILED | 변경 없음 |
| APPROVED | 변경 없음(paymentKey가 다르면 `PAYMENT_CONFLICT` ERROR) | 변경 없음 + `PAYMENT_CONFLICT` ERROR | 변경 없음 |
| FAILED | **APPROVED** + `PAYMENT_CONFLICT` ERROR | 변경 없음 | 변경 없음 |

- `@Version`은 유지한다. 동시에 진행 중인 두 트랜잭션이 같은 행을 쓰면 늦은 쪽이 `ObjectOptimisticLockingFailureException`을 받는다. 원래 흐름은 이를 Pending으로 반환하고, 확정 잡은 건너뛰었다가 다음 실행에서 다시 처리한다.
- 초안 §2 "경합 방지" 문단을 위 내용으로 바꾼다.

### 5-4. Flyway `V1__create_payment.sql`(초안 표 변경)

| 컬럼 | 타입 | NULL | 비고 |
|---|---|---|---|
| id | BIGINT AUTO_INCREMENT PK | NOT NULL | |
| order_id | VARCHAR(64) | NOT NULL | **UNIQUE**. TX1에서만 생성하고 멱등키로도 쓴다 |
| charge_key | VARCHAR(100) | NOT NULL | **UNIQUE** (변경: attempt_no와의 복합 유니크에서 단독 유니크로) |
| ~~attempt_no~~ | | | **삭제** |
| billing_key | VARCHAR(200) | NOT NULL | 평문. 로그 금지 |
| customer_key | VARCHAR(300) | NOT NULL | 길이는 구현 시 토스 문서로 확인 |
| amount | BIGINT | NOT NULL | |
| order_name | VARCHAR(100) | NOT NULL | |
| status | VARCHAR(20) | NOT NULL | REQUESTED/UNKNOWN/APPROVED/FAILED |
| pg_payment_key | VARCHAR(200) | NULL | |
| failure_code | VARCHAR(100) | NULL | |
| failure_message | VARCHAR(500) | NULL | |
| requested_at | DATETIME(6) | NOT NULL | 재전송 기한의 기준 |
| approved_at | DATETIME(6) | NULL | PG가 준 승인 시각 |
| resolved_at | DATETIME(6) | NULL | 우리가 최종 상태로 반영한 시각 |
| version | BIGINT | NOT NULL | |
| 인덱스 | `(status, requested_at)` | | 확정 잡 대상 조회용 |

- 추가하지 않는 테이블: `charge`, `payment_attempt`, `pg_exchange`, `reconciliation_issue`
- 추가하지 않는 컬럼: `dispatch_count`, `first/last_dispatched_at`, `resolve_attempts`, `next_resolve_at`, `lease_owner`, `lease_until`, `request_fingerprint`
- Repository: `findByChargeKey(chargeKey)`, `findResolvable(statuses, before)`

### 5-5. pg 매핑(초안 §4 표를 교체)

순수 함수 `internal object TossResponseMapper`로 분리한다(HTTP 없이 단위 테스트하려는 목적).

**approve**

| 응답 | PgResult |
|---|---|
| 200, status=DONE | Approved(paymentKey, approvedAt) |
| 200, status≠DONE | Unknown |
| 4xx, code ∈ `DECLINE_CODES` | Declined(code, message) |
| 그 밖의 모든 4xx(401/403 인증, 409, 429, `DUPLICATED_ORDER_ID`, `IDEMPOTENT_REQUEST_PROCESSING`, `ALREADY_PROCESSED_PAYMENT`, `PROVIDER_ERROR`, `FAILED_INTERNAL_SYSTEM_PROCESSING`, `UNKNOWN_PAYMENT_ERROR`, 처음 보는 코드, 본문 파싱 실패) | Unknown |
| 5xx, 타임아웃, I/O 오류 | Unknown |

- `DECLINE_CODES`는 allowlist다. 처음에는 `REJECT_CARD_PAYMENT` 하나로 시작한다. 구현할 때 토스 에러 코드 문서에서 카드 거절이 확실한 코드만 출처 주석과 함께 추가한다.
- 초안의 "애매한 코드 목록"은 테스트 케이스로만 남긴다. 분기 조건으로 쓰지 않는다.

**lookup**

| 응답 | PgResult |
|---|---|
| 200, DONE | Approved |
| 200, CANCELED / PARTIAL_CANCELED | Approved + `PAYMENT_CONFLICT` ERROR("PG 측 취소, 정산 대사 대상") |
| 200, ABORTED / EXPIRED | Declined(code = status 값) |
| 200, READY / IN_PROGRESS | Unknown |
| 200, WAITING_FOR_DEPOSIT 또는 처음 보는 status | Unknown + ERROR |
| 404, `NOT_FOUND_PAYMENT` / `NOT_FOUND` | NotFound |
| 그 밖 | Unknown |

- 설정: `read-timeout` 65초(테스트는 500ms), `connect-timeout` 3초
- 로그 필드: orderId, 작업(approve/lookup), HTTP 상태, code, 매핑 결과. secretKey, billingKey, 요청 본문은 남기지 않는다.

### 5-6. mockpg(초안 §5 변경)

- **원장**: 메모리 `ConcurrentHashMap<orderId, Entry>`를 유지하고 DB화하지 않는다. Entry는 `status(IN_PROGRESS/DONE)`, `paymentKey`, `approvedAt`, `amount`를 가진다.
- **멱등 응답 저장소**: `ConcurrentHashMap<orderId, 저장된 응답>`. 거절 응답도 여기에 저장하고, 원장에는 넣지 않는다(lookup하면 NotFound).
- **approve 처리**는 `compute`로 원자적으로 한다.
  - 저장된 최종 응답이 있으면 그대로 반환한다.
  - 원장이 IN_PROGRESS면 `409 IDEMPOTENT_REQUEST_PROCESSING`을 반환한다.
  - 처음이면 시나리오를 적용한다. 시나리오는 orderId별 첫 approve에만 적용한다.
- **상태 기록은 항상 지연(sleep) 전에** 한다. 지연은 응답에만 영향을 준다. 그래서 reset 후에 다른 테스트가 오염되지 않는다.
- **카운터(orderId별)**: `approveCalls(orderId)`, `lookupCalls(orderId)`, `approvedCount(orderId)`(DONE으로 바뀐 횟수). 전체 합계도 둔다.
- **테스트 훅**: `assignScenario(customerKey, scenario)`, `completeInProgress(orderId)`(IN_PROGRESS→DONE), `failNextLookups(orderId, n)`(시나리오 6용), `reset()`

**시나리오 표(7번 추가, 4번 정의 명확화)**

| # | 시나리오 | 첫 approve 처리 | 응답 | 기대 최종 결과 |
|---|---|---|---|---|
| 1 | APPROVE_OK | DONE 기록 | 200 DONE | APPROVED, lookup 0 |
| 2 | DECLINED | 거절 응답만 저장 | 403 REJECT_CARD_PAYMENT | FAILED |
| 3 | TIMEOUT_AFTER_APPROVE | DONE 기록 후 지연 | 지연 | UNKNOWN → lookup DONE → APPROVED, 재전송 0 |
| 4 | TIMEOUT_BEFORE_APPROVE | **아무것도 기록하지 않음**(요청 유실) 후 지연 | 지연 | UNKNOWN → NotFound → 재전송 → APPROVED, approvedCount=1 |
| 5 | SERVER_ERROR_AFTER_APPROVE | DONE 기록 | 500 | UNKNOWN → lookup DONE → APPROVED |
| 6 | LOOKUP_UNAVAILABLE | DONE 기록 후 지연, 첫 lookup은 503 | 지연 | 1차 확정 잡 후 UNKNOWN → 2차 → APPROVED |
| 7 | **IN_PROGRESS_THEN_DONE** (추가) | IN_PROGRESS 기록 후 지연 | 지연 | 1차 확정 잡에서 lookup IN_PROGRESS → UNKNOWN 유지, **재전송 0** → `completeInProgress` → 2차 → APPROVED, approvedCount=1 |

### 5-7. 테스트(초안 §7 변경)

**단위 테스트(Spring 없음)**
- `PaymentTest`: 허용 전이 5개(FAILED→APPROVED 포함)와 금지 전이의 예외(APPROVED→FAILED, APPROVED→UNKNOWN, FAILED→FAILED, UNKNOWN→UNKNOWN 등)
- `TossResponseMapperTest`: 5-5의 표를 한 행씩 테스트한다. 특히 `DUPLICATED_ORDER_ID`, `IDEMPOTENT_REQUEST_PROCESSING`, 401, 처음 보는 4xx 코드, 200인데 IN_PROGRESS인 approve 응답, lookup CANCELED, `NOT_FOUND` 두 종류
- `ChargeServiceTest`(MockK): `applyResult`가 예외를 던지면 Pending이고 예외가 밖으로 나가지 않는다. `approve`가 예외를 던지면 Pending이다. charge_key가 이미 있으면 `approve` 호출은 0이다.
- `MockPgLedgerTest`: 같은 orderId로 동시에 approve하면 approvedCount=1이고 나머지는 409 또는 같은 응답이다. IN_PROGRESS 중 재요청은 409다. 최종 응답은 재요청 시 그대로 반환한다.

**통합 테스트(SpringBootTest + Testcontainers + mock-pg + MutableClock)**
- `PaymentWriterTest`: 5-3 표 12칸. 충돌 칸에서는 예외 없이 상태가 유지되는지(또는 FAILED→APPROVED인지) 확인한다.
- `charge/scenario/*ScenarioTest`
  - 시나리오 1~7
  - 크래시 ②: REQUESTED 행, 원장 비어 있음 → 확정 잡 → 재전송 → APPROVED, approvedCount=1
  - 크래시 ③: REQUESTED 행, 원장 DONE → 확정 잡 → APPROVED, 재전송 0
  - 크래시 ⑤: 확정 잡을 두 번 실행해도 결과가 같다
  - 같은 charge_key로 두 번 순차 요청: 두 번째는 approve 0이고 기존 결과를 반환한다
  - 같은 charge_key로 두 스레드가 동시에 요청: payment 행 1개, approveCalls ≤ 1
  - **재전송 기한(추가)**: `requested_at`이 25시간 전인 REQUESTED 행과 빈 원장 → 확정 잡 → approve 0, 상태 REQUESTED 유지
  - **늦은 Unknown(추가)**: 확정 잡이 APPROVED로 반영한 뒤 `applyResult(Unknown)` → APPROVED 유지, 예외 없음
  - 공통 단언: 결제 상태, orderId별 approve/lookup 호출 수, **orderId별 approvedCount ≤ 1**
- `ArchitectureTest`: 초안과 같다. 추가로 `ChargeOutcome`은 charge 패키지에만 둔다.

### 5-8. 문서(초안 §7-1 4번 보강)

- `docs/decisions/0001-payment-flow.md`에 아래를 명시한다.
  1. 청구 의도당 orderId는 하나이고 절대 새로 만들지 않는다. 재전송은 같은 orderId와 멱등키로만 한다.
  2. charge_key는 호출자가 결정적으로 만들고(예: `SUB-{id}-{주기}`), 재시도할 때 같은 값을 쓴다.
  3. 호출자는 `Pending`이나 예외를 실패로 해석하지 않는다. 결제 상태로만 판단한다.
  4. PG가 승인했다는 사실은 항상 기록한다. 상태 충돌은 예외가 아니라 로그로 남긴다.
  5. **시도 2를 도입할 때의 필수 조건**(구독 기능): 이전 시도가 PG 기준으로 종결됐는지 lookup으로 확인, 조건부 UPDATE 펜스, 잠금 순서 charge→attempt, NEEDS_REVIEW 상태와 그동안의 구독 정책, 해지 후 승인 사후 처리(CHARGED_AFTER_CANCEL), next_resolve_at 선점
  6. 채택, 보류, 기각 목록(이 문서 1절)
- `docs/scenarios.md`: 5-6 표와 크래시 시나리오, 재전송 기한 시나리오
- README "알려진 한계": 2-2 문구

---

## 6. 중재자에게 남기는 쟁점

1. **attempt_no 제거(쟁점 4)**: 이상주의자와 비관론자 모두 "컬럼은 지금 넣자"는 입장이다. 나는 `UNIQUE(charge_key)`가 MVP에서 더 강하고 단순한 방어라서 제거를 권한다. 컬럼을 남기려면 절충안으로 `attempt_no INT NOT NULL DEFAULT 1` + `CHECK (attempt_no = 1)` + `UNIQUE(charge_key, attempt_no)`를 쓴다. MySQL 8.4는 CHECK를 강제하므로 DB 보장은 같다. 나중에 CHECK를 없애는 마이그레이션이 시도 2 설계 시점을 드러내 준다.
2. **NEEDS_REVIEW 미룸과 README 성공 지표 2**: "확정 잡 실행 후 UNKNOWN 0건"은 24시간 기한 안의 카오스 테스트에서는 지킬 수 있다. 기한이 지난 건은 REQUESTED/UNKNOWN으로 남는다. 지표 문구에 "24시간 이내"를 덧붙일지 결정이 필요하다.
3. **CANCELED/PARTIAL_CANCELED를 Approved로 기록**: 두 역할의 의견을 따랐다. 다만 구독 기능에서 "PG 측 취소 = 미결제로 보고 재청구"로 정하면, 그 재청구가 시도 2가 되므로 6-1과 함께 다뤄야 한다.

---
name: add-state-machine
description: 상태를 가진 엔티티(결제, 구독 등)에 상태 enum, 전이 허용표, 전이 메서드와 그 테스트를 추가할 때 사용한다.
---
# 상태 머신 엔티티 추가

Payment, Subscription에서 두 번 한 절차다. 참고 파일: PaymentStatus, SubscriptionStatus, Subscription, PaymentTest, SubscriptionStatusTest, SubscriptionTest.

1. 상태 enum에 `canTransitionTo(next)`를 `when (this)` 한 곳에 `else` 없이 둔다. 종결 상태는 `false`다.
   기간 연장이나 해지 예약처럼 상태가 바뀌지 않는 변경은 자기 자신으로 가는 전이를 넣지 않는다.
2. 전이 허용표 테스트: "모든 상태 쌍 × 허용 여부"를 허용 집합과 비교한다. 종결 상태와 자기 자신 전이가 막히는지도 따로 본다.
3. 엔티티의 상태는 `var` + `protected set`이다. 전이 메서드만 상태를 바꾸고, 안에서 `check(canTransitionTo)`로 막는다.
   허용되지 않은 전이만 예외다. 이미 처리된 이벤트의 재실행은 예외가 아니라 sealed 결과로 돌려준다.
   엔티티는 로그를 남기지 않고 결과만 돌려준다. 로그는 트랜잭션을 맡은 writer가 남긴다.
4. 엔티티는 DB 없이 단위 테스트한다. 원하는 상태는 헬퍼에서 엔티티 메서드로 만든다. id가 필요하면 `ReflectionTestUtils.setField`를 쓴다.
5. 상태 × 사실 행렬 테스트: 상태 변형(예약 유무, 송신 중 여부 포함) × 들어오는 사실 × 키 일치와 불일치를 파라미터 테스트로 전부 확인한다.
   각 행에서 결과 타입, 다음 상태, 전이 유효성(`before == after || canTransitionTo`), 불변식을 단언한다.
6. 삭제 실험: 안전장치(가드, 키 비교, 기대값 검사)를 하나씩 일부러 지우거나 꺼서 해당 테스트가 실패하는지 확인하고 원복한다.
   실패하지 않으면 그 테스트는 안전장치를 검증하지 못한 것이다. 원복은 `git status`와 전체 검사로 확인한다.
7. 커밋 전에 `./gradlew spotlessApply check`를 통과시킨다. 테스트 수와 삭제 실험 결과를 보고에 쓴다.

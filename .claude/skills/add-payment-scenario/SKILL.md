---
name: add-payment-scenario
description: 결제사 장애나 경합 상황을 재현하는 결제 시나리오 테스트를 추가할 때 사용한다.
---
# 결제 시나리오 테스트 추가

1. 시나리오를 한 문장으로 정의한다: "결제사가 [반응]을 하면 결제는 [상태], 구독은 [상태]가 된다"
2. docs/scenarios.md에 같은 시나리오가 있는지 확인한다. 있으면 중단하고 사용자에게 알린다.
3. Mock PG에 필요한 동작이 없으면 mockpg의 MockScenario enum에 추가하고,
   토스 API 응답 형태(상태 코드, 에러 코드, JSON 구조)를 그대로 흉내 낸다.
4. src/test/kotlin/.../charge/scenario/ 에 테스트를 추가한다.
   - SpringBootTest + Testcontainers(MySQL) + mock-pg 프로필 + 고정 Clock
   - Given: Mock PG 시나리오 설정, 구독 데이터
   - When: ChargeService 실행 (필요하면 PaymentStatusResolver까지)
   - Then: 결제 상태, 구독 상태, 결제사 호출 횟수, Mock PG 원장 기준 실제 청구 횟수를 모두 검증
5. 테스트 이름은 백틱 한글 함수명. 예: `타임아웃 후 결제사에 승인 기록이 있으면 승인으로 확정된다`
6. 처리 코드를 잠시 주석 처리해 테스트가 실패하는지 한 번 확인한 뒤 원복한다.
7. docs/scenarios.md 표에 추가한다: 시나리오 / Mock 설정 / 기대 결과 / 테스트 위치

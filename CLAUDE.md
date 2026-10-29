- 사용자에게는 한국어로 답한다. 코드, 커밋 메시지 접두사(feat, fix 등), 식별자는 영어

# 구독 결제와 정산 서비스

토스페이먼츠 자동결제 기반 구독 서비스. 핵심 주제는
(1) 결제사 타임아웃 시 상태 조회로 결과 확정 (2) 시간 기반 구독 상태 머신 (3) 정산 대사(확장).
사용자는 Node.js/TypeScript 개발자이고 Kotlin과 Spring은 처음이다.
사용자가 이해하고 설명할 수 있는 코드만 반영한다.

## 스택
- Kotlin 2.3.x(Spring Boot 관리 버전), JVM 25, Spring Boot 4.1.x, Spring MVC(블로킹), Hibernate 7, MySQL 8.4, Flyway
- 코루틴, WebFlux, Kotest 사용 금지
- HTTP 클라이언트는 RestClient만 사용
- Spring Boot 4는 3.x와 다르다(Jackson 3, 분리된 스타터). 기억에 의존하지 말고 build.gradle.kts와 컴파일 결과로 확인한다.
- Kotlin + Spring 예제는 Java보다 적다. Java 스타일(Optional, getter 메서드, !!)을 그대로 옮기지 않는다.

## 명령어
- 전체 검사: ./gradlew spotlessApply check
- 로컬 실행: docker compose up -d mysql && ./gradlew bootRun --args='--spring.profiles.active=local,mock-pg'

## 아키텍처 규칙 (ArchitectureTest로 강제)
- 패키지: subscription, payment, billingkey, pg, charge, mockpg, common
- pg 밖에서 토스 API의 타입, 에러 코드, JSON 구조를 쓰지 않는다. 결과는 PgResult로만 전달한다.
- subscription과 payment는 서로를 직접 참조하지 않는다. 둘을 엮는 일은 charge가 한다.
- mockpg는 @Profile("mock-pg")에서만 로드된다. 다른 패키지에서 mockpg를 참조하지 않는다.

## 결제 코드 규칙 (가장 중요)
- 외부 결제사 호출은 절대 @Transactional 안에서 하지 않는다.
  흐름: 결제 시도 기록 커밋 → 트랜잭션 밖 호출 → 결과 반영 트랜잭션
- 타임아웃과 5xx는 실패가 아니라 UNKNOWN이다.
- 모든 승인 요청에는 결제 시도마다 고유한 멱등키를 보내고, 재전송 시 같은 값을 쓴다.
- 금액은 원 단위 Long. Double, Float 금지
- 현재 시각은 주입받은 Clock으로만 얻는다. now() 직접 호출 금지
- 상태 전이는 엔티티 메서드로만 한다. 허용되지 않은 전이는 예외

## Kotlin 코드 규칙
- 엔티티: 일반 class. data class 금지. 상태 프로퍼티는 var + protected set. id는 val id: Long = 0L
- 엔티티 프로퍼티의 nullable 여부는 Flyway 스키마의 NOT NULL과 반드시 일치시킨다.
- DTO는 data class. 검증 어노테이션은 @field: 를 붙인다.
- !! 금지. 불가피하면 사용자에게 이유를 설명한다.
- 조회는 findByIdOrNull. Optional 금지
- 의존성은 주 생성자 주입만. lateinit 주입 금지
- 결과 분기는 sealed interface + when. else로 뭉개지 않는다.
- 스코프 함수는 ?.let 정도만. apply/also/run 중첩 금지
- 동등 비교는 ==. === 는 참조 비교가 꼭 필요할 때만
- 같은 클래스 내부 호출로 @Transactional 메서드를 부르지 않는다(프록시 우회).

## 테스트
- 결제 시나리오 테스트: SpringBootTest + Testcontainers(MySQL) + mock-pg 프로필 + 고정 Clock
- 테스트 이름은 백틱 한글 함수명
- 목은 MockK. Mockito 금지

## Git 규칙
- main에 직접 커밋하지 않는다. feat/, fix/, refactor/, revert/ 브랜치를 만든다.
- 커밋은 작게, 하나의 의도만. Conventional Commits(한글 본문 허용)
- main 반영은 PR과 CI 통과 후 squash merge로만
- 되돌릴 때는 main에서 revert/ 브랜치를 만들어 git revert 후 PR로 반영하고, docs/reverts.md에
  "무엇을 / 왜 / 대신 어떻게"를 기록한다.
- 이미 커밋된 Flyway 마이그레이션은 수정하지 않는다. 새 버전 파일을 만든다.
- 사용자가 코드를 이해했다고 확인하기 전에는 커밋하지 않는다.

## 작업 방식
- 새 기능은 계획 모드에서 설계안을 만들고 사용자 승인 후 구현한다.
- 돈이 걸린 핵심 설계(결제 흐름, 구독 상태 머신, 정산 대사)만 4역할 토론으로 검토한다.
  1. design-idealist: 이상적인 설계로 보강
  2. design-pessimist: 실패 경로 공격
  3. design-realist: MVP 범위로 정리
  4. 중재자(메인 세션): 세 의견을 비교해 최종 결정. 채택, 보류, 기각과 이유를 docs/decisions/에 ADR로 남긴다.
  토론 원문은 docs/design-reviews/에 저장한다. 그 외 기능은 계획 모드만 사용한다.
- code-reviewer는 기능 하나가 끝났을 때만 호출한다.
- 구현을 마치면 새로 등장한 Kotlin/Spring 개념을 Node.js/TypeScript와 비교해 3~5줄로 설명하고
  docs/learning/에 기록한다.

## 스킬
- 반복 작업은 .claude/skills/의 절차를 따른다. 스킬에 없는 방식으로 같은 작업을 하지 않는다.
- 같은 종류의 작업이 두 번 반복되면 스킬로 만들지 사용자에게 제안한다.

## 기록 (PR마다 한 줄씩, 가볍게)
- 리뷰 지적 사항은 docs/review-log.md에 "날짜 / PR / 문제 / 심각도 / 처리와 이유"로 추가한다.
  심각도 높음은 README "리뷰 에이전트가 잡은 주요 문제"에도 추가한다(최대 5개).
- 결제 시나리오를 추가하면 docs/scenarios.md를 갱신한다.
- 사용자가 내 결과물을 고치거나 리뷰에서 같은 유형의 문제가 두 번 나오면, README
  "에이전트와 일하며 바꾼 것" 표에 한 줄을 추가하고 고칠 규칙(CLAUDE.md, 훅, 스킬)을 제안한다.
- 모델이나 Claude Code 기능이 바뀌어 규칙, 훅, 스킬, 에이전트를 고치면 같은 표에 "도구 변화"로 기록한다.

## 인프라 (확장 단계)
- Terraform은 plan까지만 실행한다. apply와 destroy는 사용자가 직접 한다.

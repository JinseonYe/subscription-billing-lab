package com.jinseon.billing

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

// CLAUDE.md의 "아키텍처 규칙"을 테스트로 강제한다. 테스트 코드는 검사 대상에서 뺀다(테스트는 mockpg를 써도 된다).
// ktlint는 @Test가 있는 파일에서만 백틱 함수명을 허용한다. ArchUnit은 @ArchTest를 쓰므로 이 클래스만 예외로 둔다.
@Suppress("ktlint:standard:function-naming")
@AnalyzeClasses(packages = ["com.jinseon.billing"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {
    @ArchTest
    fun `mockpg는 다른 패키지에서 참조하지 않는다`(classes: JavaClasses) {
        noClasses()
            .that()
            .resideOutsideOfPackage("..mockpg..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..mockpg..")
            .check(classes)
    }

    @ArchTest
    fun `mockpg의 스프링 빈은 모두 mock-pg 프로필에서만 로드된다`(classes: JavaClasses) {
        classes()
            .that()
            .resideInAPackage("..mockpg..")
            .and()
            .areMetaAnnotatedWith(Component::class.java)
            .should(beActivatedOnlyByMockPgProfile)
            .check(classes)
    }

    @ArchTest
    fun `토스 타입은 pg 패키지 밖에서 쓰지 않는다`(classes: JavaClasses) {
        noClasses()
            .that()
            .resideOutsideOfPackage("..pg..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameStartingWith("Toss")
            .check(classes)
    }

    @ArchTest
    fun `pg는 payment, charge, mockpg를 알지 못한다`(classes: JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("..pg..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..payment..", "..charge..", "..mockpg..")
            .check(classes)
    }

    @ArchTest
    fun `payment는 charge를 알지 못한다`(classes: JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("..payment..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..charge..")
            .check(classes)
    }

    // 아직 subscription 패키지가 없어서 검사 대상이 비어 있다. 생기는 순간부터 이 규칙이 적용된다.
    @ArchTest
    fun `subscription과 payment는 서로를 직접 참조하지 않는다`(classes: JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("..subscription..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..payment..")
            .allowEmptyShould(true)
            .check(classes)
        noClasses()
            .that()
            .resideInAPackage("..payment..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..subscription..")
            .allowEmptyShould(true)
            .check(classes)
    }
}

// @Profile이 붙어 있는지만 보면 @Profile("local")처럼 잘못된 값도 통과한다. 값이 정확히 "mock-pg"인지까지 본다.
private val beActivatedOnlyByMockPgProfile =
    object : ArchCondition<JavaClass>("@Profile(\"mock-pg\")만 붙어 있다") {
        override fun check(
            item: JavaClass,
            events: ConditionEvents,
        ) {
            val profiles =
                item
                    .tryGetAnnotationOfType(Profile::class.java)
                    .map { it.value.toList() }
                    .orElse(emptyList())
            val message = "${item.name}의 @Profile 값: $profiles"
            events.add(
                if (profiles == listOf("mock-pg")) {
                    SimpleConditionEvent.satisfied(item, message)
                } else {
                    SimpleConditionEvent.violated(item, message)
                },
            )
        }
    }

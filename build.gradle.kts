import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.spring") version "2.1.20"
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.dwje"
version = "0.0.1"
description = "덕우전자 AX 시스템 API"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // --- Spring Boot ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // --- Kotlin ---
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // --- DB (PostgreSQL / Native SQL Direct Binding) ---
    runtimeOnly("org.postgresql:postgresql")

    // --- 메일 발송 (이메일 인증) ---
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // --- 비밀번호 해시 (BCrypt) ---
    implementation("org.springframework.security:spring-security-crypto")

    // --- JWT ---
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // --- 엑셀/CSV 내려받기 ---
    implementation("org.apache.poi:poi-ooxml:5.3.0")

    // --- API 문서 ---
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")

    // --- Test ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // EnvVarDocumentedTest 는 소스가 아닌 파일(README·실행 구성)을 읽어 프로파일 yml 과 대조한다.
    // 입력으로 선언하지 않으면 그 파일만 바뀌었을 때 Gradle 이 test 를 UP-TO-DATE 로 건너뛰어,
    // 문서에서 환경변수를 빼도 빌드가 통과한다. (실제로 그렇게 통과했다)
    inputs.files(
        "README.md",
        ".run/dwje-api [dev].run.xml",
        "src/main/resources/application.yml",
        "docs/REQUEST_BODY_CONTRACT.md"
    )
        .withPropertyName("envVarDocs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional()

    // 규약 테스트 몇 개는 **소스 파일 본문을 직접 읽어** 규칙을 고정한다
    // (집계 구간 주입, 설비 대수 집계 단위, Map 본문 등).
    // 컴파일 결과만 test 입력이라 SQL 문자열 한 줄을 되돌려도 Gradle 이
    // test 를 UP-TO-DATE 로 건너뛰어 **가드가 헛돈다.**
    // 실제로 되돌림 검증 중에 그렇게 통과했다.
    inputs.dir("src/main/kotlin")
        .withPropertyName("scannedMainSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("dwje-api-${project.version}.jar")
}

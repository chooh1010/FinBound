plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// 경보 워커(docs/04 §18). Core 피드만 읽고, 자기 상태는 자기 데이터베이스에 둔다. Core 데이터베이스에 접속하지 않는다.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // Kafka comparison experiment only (finbound-kafka-comparison-spec §8-§9); off unless switched on.
    implementation("org.springframework.kafka:spring-kafka")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
}

tasks.test {
    // 데이터베이스 격리 시험이 infrastructure/postgres-init의 스크립트를 그대로 실행한다.
    systemProperty("finguard.repository.root", rootProject.projectDir.absolutePath)
    // 모듈 밖 파일이라 선언하지 않으면 스크립트를 바꿔도 Gradle이 이전 시험 결과를 재사용한다.
    inputs.dir(rootProject.file("infrastructure/postgres-init"))
    // Contract fixtures live outside the module; declare them so a fixture change reruns the tests.
    inputs.dir(rootProject.file("contracts"))
}

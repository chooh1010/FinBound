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
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.wiremock:wiremock-standalone:3.10.0")
}

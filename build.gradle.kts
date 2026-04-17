plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    checkstyle
    pmd
    id("com.github.spotbugs") version "6.0.9"
}

group = "io.simakov.analytics"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // SpotBugs annotations (for @SuppressFBWarnings)
    implementation("com.github.spotbugs:spotbugs-annotations:4.8.6")

    // Database
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // OpenAPI / Swagger UI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Jackson extras
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("io.github.mfvanek:pg-index-health-test-starter:0.20.3")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation("org.apache.httpcomponents.client5:httpclient5")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.20.1")
    }
}

// ── Checkstyle ────────────────────────────────────────────────────────────────
checkstyle {
    toolVersion = "10.17.0"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

// ── PMD ───────────────────────────────────────────────────────────────────────
pmd {
    toolVersion = "7.4.0"
    isConsoleOutput = true
    ruleSetFiles = files("config/pmd/pmd-rules.xml")
    ruleSets = emptyList()
    isIgnoreFailures = false
}

// ── SpotBugs ──────────────────────────────────────────────────────────────────
spotbugs {
    toolVersion = "4.8.6"
    excludeFilter = file("config/spotbugs/spotbugs-exclude.xml")
    ignoreFailures = false
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask> {
    reports.create("html") {
        required = true
    }
    reports.create("xml") {
        required = false
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

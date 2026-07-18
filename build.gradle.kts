import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("java")
    id("org.springframework.boot") version "2.7.18"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.zoho.jw"
version = "1.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Catalyst Java SDK — gives us the managed SmartBrowz service (HTML -> PDF via real Chrome).
    implementation("com.zoho.catalyst:java-sdk:2.1.0")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.json:json:20250517")

    // Jackson comes transitively from spring-boot-starter-web at Spring Boot's managed version
    // (2.13.5 — a consistent core/databind/annotations set). Do NOT pin jackson-databind to a newer
    // version: Spring's BOM keeps jackson-core at 2.13.5, and a 2.16 databind then can't find
    // StreamConstraintsException (added in core 2.15) → NoClassDefFoundError at runtime.

    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

repositories {
    maven {
        url = uri("https://maven.zohodl.com")
        name = "java-sdk"
        content { includeGroup("com.zoho.catalyst") }
    }
    mavenCentral()
}

// ---------------------------------------------------------------------------
// Serve the front-end from this AppSail (same origin as the API ⇒ zero CORS).
// The single source of truth stays in ../jw-schedule; it's copied into the jar's
// static resources at build time, so `/` serves index.html and /css, /js load
// from the same origin as /me, /congregations, /pdf.
// ---------------------------------------------------------------------------
val frontendSrc = "../jw-schedule"
val frontendStaged = layout.buildDirectory.dir("frontend")

val copyFrontend by tasks.registering(Copy::class) {
    from(frontendSrc) { include("index.html", "css/**", "js/**") }
    into(frontendStaged.map { it.dir("static") })
}
sourceSets.main.get().resources.srcDir(frontendStaged)
tasks.named("processResources") { dependsOn(copyFrontend) }

// AppSail runs `java -jar <the bootJar>`; keep the artifact name stable for app-config.json.
tasks.named<BootJar>("bootJar") {
    archiveFileName.set("jw-schedule-backend-1.0.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Local run: `./gradlew bootRun` — set the Catalyst DC URLs (India). Override for other DCs.
tasks.named<BootRun>("bootRun") {
    environment("X_ZOHO_CATALYST_ACCOUNTS_URL", "https://accounts.zoho.in")
    environment("X_ZOHO_CATALYST_CONSOLE_URL", "https://api.catalyst.zoho.in")
}

tasks.withType<Test> { useJUnitPlatform() }

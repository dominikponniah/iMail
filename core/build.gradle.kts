plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

dependencies {
    // IMAP/SMTP. Angus Mail is the Jakarta Mail reference implementation.
    api("org.eclipse.angus:angus-mail:2.0.3")
    // Allowlist-based HTML sanitizer for mail bodies.
    implementation("org.jsoup:jsoup:1.18.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
    testImplementation("com.icegreen:greenmail:2.1.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false }
}

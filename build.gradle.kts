plugins {
    kotlin("jvm") version "2.4.20"
}

group = "com.tiarebalbi"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Config *reading* only (issue #7) -- its JsonObject preserves key
    // order, which modules-map first-match-wins depends on; org.json's
    // HashMap-backed JSONObject would silently break that. The #8 JSON
    // *writer* stays hand-rolled regardless: it must byte-match Python's
    // json.dump(indent=2, sort_keys=True), which no off-the-shelf
    // pretty-printer reproduces (separator/float-repr conventions differ).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

tasks.test {
    useJUnitPlatform()
}

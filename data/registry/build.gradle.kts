plugins {
    alias(libs.plugins.org.jetbrains.kotlin.jvm)
    alias(libs.plugins.org.jetbrains.kotlin.serialization)
}

// Platform-neutral channel-registry rules: name matching, candidate parsing,
// stream validation and ranking. Shared by the Android data layer and mirrored
// by the registry repository's build script (see registry-template/).
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.squareup.okhttp)
    implementation("dev.oxyroid.parser:m3u")

    testImplementation(kotlin("test-junit"))
    testImplementation(project(":testing:mock-server"))
}

val matchVectors = rootProject.layout.projectDirectory.file("registry-template/tests/match_vectors.json")

tasks.withType<Test>().configureEach {
    inputs.file(matchVectors).withPropertyName("matchVectors")
    systemProperty("registry.matchVectors", matchVectors.asFile.absolutePath)
}

plugins { kotlin("jvm") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
sourceSets {
    main { kotlin.setSrcDirs(listOf("../../../core-logic/src/main/kotlin")) }
    test { kotlin.setSrcDirs(listOf("../../../core-logic/src/test/kotlin")) }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
tasks.test { useJUnit(); testLogging { events("passed", "failed", "skipped"); showStandardStreams = false } }

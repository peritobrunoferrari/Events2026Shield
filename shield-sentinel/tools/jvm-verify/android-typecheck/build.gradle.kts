plugins { kotlin("jvm") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Módulos Android checados por tipo (sem :app, que depende de Jetpack Compose).
val androidModules = listOf(
    "core-security", "secure-storage", "audit", "device-owner", "usb-security",
    "device-hardening", "auto-reboot", "watchdog", "emergency", "anti-theft",
    "remote-wipe", "duress",
)
sourceSets {
    main {
        kotlin.setSrcDirs(androidModules.map { "../../../$it/src/main/kotlin" } + listOf("src/stubs/kotlin"))
    }
}
dependencies {
    implementation(project(":core-logic-jvm"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Framework Android 17 (API 37) empacotado pelo Robolectric — apenas compile-time.
    compileOnly("org.robolectric:android-all:17-robolectric-15733970")
}

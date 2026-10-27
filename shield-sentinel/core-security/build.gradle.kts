plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.shieldpericias.sentinel.coresecurity"
    compileSdk = (project.property("sentinelCompileSdk") as String).toInt()
    (project.findProperty("sentinelCompileSdkMinor") as String?)?.toIntOrNull()?.takeIf { it > 0 }?.let { compileSdkMinor = it }
    defaultConfig {
        minSdk = (project.property("sentinelMinSdk") as String).toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-logic"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

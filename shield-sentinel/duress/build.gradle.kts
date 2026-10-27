plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.shieldpericias.sentinel.duress"
    compileSdk = (project.property("sentinelCompileSdk") as String).toInt()
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
    implementation(project(":core-security"))
    implementation(project(":device-owner"))
    implementation(project(":secure-storage"))
    implementation(project(":audit"))
    implementation(project(":anti-theft"))
    implementation(project(":emergency"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

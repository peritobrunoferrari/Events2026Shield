plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shieldpericias.sentinel"
    compileSdk = (project.property("sentinelCompileSdk") as String).toInt()

    defaultConfig {
        applicationId = "com.shieldpericias.sentinel"
        minSdk = (project.property("sentinelMinSdk") as String).toInt()
        targetSdk = (project.property("sentinelTargetSdk") as String).toInt()
        versionCode = 1
        versionName = "0.1.0-alpha"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core-logic"))
    implementation(project(":core-security"))
    implementation(project(":secure-storage"))
    implementation(project(":audit"))
    implementation(project(":device-owner"))
    implementation(project(":usb-security"))
    implementation(project(":device-hardening"))
    implementation(project(":auto-reboot"))
    implementation(project(":watchdog"))
    implementation(project(":emergency"))
    implementation(project(":anti-theft"))
    implementation(project(":remote-wipe"))
    implementation(project(":duress"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}

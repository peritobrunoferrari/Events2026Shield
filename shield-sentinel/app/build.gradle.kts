import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shieldpericias.sentinel"
    compileSdk = (project.property("sentinelCompileSdk") as String).toInt()
    (project.findProperty("sentinelCompileSdkMinor") as String?)?.toIntOrNull()?.takeIf { it > 0 }?.let { compileSdkMinor = it }

    defaultConfig {
        applicationId = "com.shieldpericias.sentinel"
        minSdk = (project.property("sentinelMinSdk") as String).toInt()
        targetSdk = (project.property("sentinelTargetSdk") as String).toInt()
        versionCode = 1
        versionName = "0.1.0-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Assinatura de release: lida de app/keystore.properties (não versionado) ou de variáveis
    // de ambiente SENTINEL_KEYSTORE_FILE/PASSWORD/KEY_ALIAS/KEY_PASSWORD. Sem elas, o APK de
    // release sai NÃO assinado (apenas para verificar o build).
    val keystoreProps = Properties().apply {
        val f = rootProject.file("app/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val ksFile = keystoreProps.getProperty("storeFile") ?: System.getenv("SENTINEL_KEYSTORE_FILE")
    if (ksFile != null && file(ksFile).exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(ksFile)
                storePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("SENTINEL_KEYSTORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("SENTINEL_KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("SENTINEL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksFile != null && file(ksFile).exists()) signingConfig = signingConfigs.getByName("release")
        }
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
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}

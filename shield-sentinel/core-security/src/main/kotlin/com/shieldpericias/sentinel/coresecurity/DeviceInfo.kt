package com.shieldpericias.sentinel.coresecurity

import android.os.Build

data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val sdkInt: Int,
    val securityPatch: String,
    val buildId: String,
    val buildType: String,
    val isPixel: Boolean,
) {
    companion object {
        fun collect(): DeviceInfo = DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            buildId = Build.DISPLAY,
            buildType = Build.TYPE,
            isPixel = Build.MANUFACTURER.equals("Google", ignoreCase = true),
        )
    }
}

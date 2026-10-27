package com.shieldpericias.sentinel.coresecurity

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import com.shieldpericias.sentinel.core.AdminMode

data class AdminStatus(
    val mode: AdminMode,
    val isDeviceOwner: Boolean,
    val isProfileOwner: Boolean,
    val isDeviceAdminActive: Boolean,
    val isManagedProfile: Boolean,
    val activeAdminCount: Int,
    val provisioningAllowed: Boolean,
) {
    companion object {
        fun detect(context: Context, admin: ComponentName): AdminStatus {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            val um = context.getSystemService(UserManager::class.java)
            val pkg = context.packageName
            val isDo = runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false)
            val isPo = runCatching { dpm.isProfileOwnerApp(pkg) }.getOrDefault(false)
            val isAdmin = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)
            val mode = when {
                isDo -> AdminMode.DEVICE_OWNER
                isPo -> AdminMode.PROFILE_OWNER
                isAdmin -> AdminMode.DEVICE_ADMIN
                else -> AdminMode.NONE
            }
            return AdminStatus(
                mode = mode,
                isDeviceOwner = isDo,
                isProfileOwner = isPo,
                isDeviceAdminActive = isAdmin,
                isManagedProfile = runCatching { um.isManagedProfile }.getOrDefault(false),
                activeAdminCount = runCatching { dpm.activeAdmins?.size ?: 0 }.getOrDefault(0),
                provisioningAllowed = runCatching {
                    dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE)
                }.getOrDefault(false),
            )
        }
    }
}

package com.shieldpericias.sentinel.deviceowner

import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdateInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.shieldpericias.sentinel.core.AdminMode
import com.shieldpericias.sentinel.core.FeatureState
import com.shieldpericias.sentinel.core.PolicyResult
import com.shieldpericias.sentinel.coresecurity.AdminStatus

/**
 * Fachada única sobre o DevicePolicyManager. Toda chamada:
 *  - verifica o modo administrativo exigido;
 *  - converte exceções em [PolicyResult] (nunca propaga SecurityException à UI);
 *  - permite leitura do estado efetivo depois de aplicar.
 *
 * Políticas destrutivas (wipe) ficam em métodos explícitos e são chamadas
 * somente pelos módulos anti-theft/duress/remote-wipe via WipeController.
 */
open class DevicePolicyFacade(protected val context: Context) {

    val admin: ComponentName = SentinelDeviceAdminReceiver.component(context)
    protected val dpm: DevicePolicyManager = context.getSystemService(DevicePolicyManager::class.java)
    protected val userManager: UserManager = context.getSystemService(UserManager::class.java)

    open fun status(): AdminStatus = AdminStatus.detect(context, admin)
    open fun mode(): AdminMode = status().mode

    // ------------------------------------------------------------------ util

    protected inline fun guarded(required: AdminMode? = AdminMode.DEVICE_OWNER, allowProfileOwner: Boolean = false, block: () -> Unit): PolicyResult {
        val m = mode()
        if (required != null) {
            val ok = when {
                m == AdminMode.DEVICE_OWNER -> true
                allowProfileOwner && m == AdminMode.PROFILE_OWNER -> true
                required == AdminMode.DEVICE_ADMIN && m != AdminMode.NONE -> true
                else -> false
            }
            if (!ok) return PolicyResult.Unsupported("Exige ${required.name}${if (allowProfileOwner) " ou PROFILE_OWNER" else ""}; modo atual: ${m.name}")
        }
        return try {
            block(); PolicyResult.Applied
        } catch (e: SecurityException) {
            PolicyResult.Denied(e.message ?: "SecurityException")
        } catch (e: UnsupportedOperationException) {
            PolicyResult.Unsupported(e.message ?: "UnsupportedOperationException")
        } catch (e: IllegalStateException) {
            PolicyResult.Failed("IllegalStateException: ${e.message}")
        } catch (e: Exception) {
            PolicyResult.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    protected inline fun readState(block: () -> FeatureState): FeatureState = try {
        block()
    } catch (e: SecurityException) {
        FeatureState.Unavailable("Sem privilégio para ler: ${e.message ?: "SecurityException"}")
    } catch (e: Exception) {
        FeatureState.Error("${e.javaClass.simpleName}: ${e.message}")
    }

    // ------------------------------------------------------- bloqueio/reboot

    /** Bloqueia imediatamente. Exige Device Admin ativo (qualquer modo administrativo). */
    open fun lockNow(): PolicyResult = guarded(AdminMode.DEVICE_ADMIN) { dpm.lockNow() }

    /** Reinicia (Device Owner). Falha com IllegalStateException se houver chamada em andamento. */
    open fun reboot(): PolicyResult = guarded { dpm.reboot(admin) }

    // ------------------------------------------------------ restrições de usuário

    open fun setUserRestriction(key: String, enabled: Boolean): PolicyResult =
        guarded(allowProfileOwner = true) {
            if (enabled) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
        }

    /** Estado efetivo da restrição (considera também restrições de outros administradores). */
    open fun restrictionState(key: String): FeatureState = readState {
        val mine = if (mode().isOwner) runCatching { dpm.getUserRestrictions(admin).getBoolean(key, false) }.getOrDefault(false) else false
        val effective = runCatching { userManager.hasUserRestriction(key) }.getOrDefault(false)
        when {
            mine -> FeatureState.Active("Aplicada pelo Sentinel")
            effective -> FeatureState.Active("Aplicada por outro administrador/sistema")
            !mode().isOwner -> FeatureState.Unavailable("Exige Device/Profile Owner")
            else -> FeatureState.Inactive()
        }
    }

    // -------------------------------------------------------------------- USB

    open fun canUsbDataSignalingBeDisabled(): Boolean = runCatching { dpm.canUsbDataSignalingBeDisabled() }.getOrDefault(false)

    open fun isUsbDataSignalingEnabled(): FeatureState = readState {
        if (!canUsbDataSignalingBeDisabled()) return@readState FeatureState.Unavailable("Aparelho não suporta controle de sinalização USB")
        if (dpm.isUsbDataSignalingEnabled) FeatureState.Inactive("Sinalização USB habilitada (dados permitidos)")
        else FeatureState.Active("Sinalização USB desabilitada (somente carga)")
    }

    open fun setUsbDataSignalingEnabled(enabled: Boolean): PolicyResult {
        if (!canUsbDataSignalingBeDisabled()) return PolicyResult.Unsupported("canUsbDataSignalingBeDisabled() == false")
        return guarded(allowProfileOwner = true) { dpm.setUsbDataSignalingEnabled(enabled) }
    }

    // -------------------------------------------------------------- hardening

    open fun setCameraDisabled(disabled: Boolean): PolicyResult = guarded(AdminMode.DEVICE_ADMIN) { dpm.setCameraDisabled(admin, disabled) }
    open fun cameraDisabled(): FeatureState = readState {
        if (mode() == AdminMode.NONE) FeatureState.Unavailable("Sem administrador ativo")
        else if (dpm.getCameraDisabled(admin)) FeatureState.Active("Câmera desativada") else FeatureState.Inactive()
    }

    open fun setScreenCaptureDisabled(disabled: Boolean): PolicyResult = guarded(allowProfileOwner = true) { dpm.setScreenCaptureDisabled(admin, disabled) }
    open fun screenCaptureDisabled(): FeatureState = readState {
        if (!mode().isOwner) FeatureState.Unavailable("Exige Device/Profile Owner")
        else if (dpm.getScreenCaptureDisabled(admin)) FeatureState.Active("Captura de tela bloqueada no sistema") else FeatureState.Inactive()
    }

    open fun setRequiredPasswordComplexity(complexity: Int): PolicyResult = guarded(allowProfileOwner = true) { dpm.requiredPasswordComplexity = complexity }
    open fun requiredPasswordComplexity(): Int? = runCatching { if (mode().isOwner) dpm.requiredPasswordComplexity else null }.getOrNull()
    open fun currentPasswordComplexity(): Int? = runCatching { dpm.passwordComplexity }.getOrNull()

    open fun setMaximumTimeToLock(ms: Long): PolicyResult = guarded(AdminMode.DEVICE_ADMIN) { dpm.setMaximumTimeToLock(admin, ms) }
    open fun maximumTimeToLock(): Long? = runCatching { if (mode() != AdminMode.NONE) dpm.getMaximumTimeToLock(admin) else null }.getOrNull()

    open fun setKeyguardDisabledFeatures(flags: Int): PolicyResult = guarded(AdminMode.DEVICE_ADMIN) { dpm.setKeyguardDisabledFeatures(admin, flags) }
    open fun keyguardDisabledFeatures(): Int? = runCatching { if (mode() != AdminMode.NONE) dpm.getKeyguardDisabledFeatures(admin) else null }.getOrNull()

    open fun setAutoTimeEnabled(enabled: Boolean): PolicyResult = guarded(allowProfileOwner = true) { dpm.setAutoTimeEnabled(admin, enabled) }
    open fun autoTimeEnabled(): FeatureState = readState {
        if (!mode().isOwner) FeatureState.Unavailable("Exige Device/Profile Owner")
        else if (dpm.getAutoTimeEnabled(admin)) FeatureState.Active("Hora automática forçada") else FeatureState.Inactive()
    }

    open fun setUninstallBlocked(blocked: Boolean): PolicyResult = guarded(allowProfileOwner = true) { dpm.setUninstallBlocked(admin, context.packageName, blocked) }
    open fun uninstallBlocked(): FeatureState = readState {
        if (!mode().isOwner) FeatureState.Unavailable("Exige Device/Profile Owner")
        else if (dpm.isUninstallBlocked(admin, context.packageName)) FeatureState.Active("Desinstalação do Sentinel bloqueada") else FeatureState.Inactive()
    }

    open fun setPermissionGranted(permission: String, granted: Boolean): PolicyResult = guarded(allowProfileOwner = true) {
        val state = if (granted) DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED else DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
        dpm.setPermissionGrantState(admin, context.packageName, permission, state)
    }

    open fun setLockScreenInfo(text: CharSequence?): PolicyResult = guarded { dpm.setDeviceOwnerLockScreenInfo(admin, text) }
    open fun setOrganizationName(name: CharSequence?): PolicyResult = guarded(allowProfileOwner = true) { dpm.setOrganizationName(admin, name) }

    open fun pendingSystemUpdate(): SystemUpdateInfo? = runCatching { if (mode().isOwner) dpm.getPendingSystemUpdate(admin) else null }.getOrNull()

    // --------------------------------------------------------------- apagamento

    /**
     * Apagamento completo do aparelho (factory reset). Exige Device Owner e Android 14+
     * (`wipeDevice`). Em Android 12/13 usa `wipeData` a partir do usuário primário.
     * NÃO chamar diretamente da UI: passe por WipeController.
     */
    open fun wipeDeviceInternal(flags: Int): PolicyResult = guarded {
        if (Build.VERSION.SDK_INT >= 34) dpm.wipeDevice(flags) else dpm.wipeData(flags)
    }

    /** Apaga somente o perfil/usuário gerenciado (Profile Owner). */
    open fun wipeProfileInternal(flags: Int, reason: CharSequence): PolicyResult =
        guarded(required = AdminMode.PROFILE_OWNER, allowProfileOwner = true) { dpm.wipeData(flags, reason) }

    // ------------------------------------------------------------- recuperação

    /** Remove o Sentinel como Device Owner (procedimento de recuperação administrativa). */
    open fun clearDeviceOwner(): PolicyResult = guarded { dpm.clearDeviceOwnerApp(context.packageName) }

    /** Desativa o Device Admin (quando não é owner). */
    open fun removeActiveAdmin(): PolicyResult = guarded(AdminMode.DEVICE_ADMIN) { dpm.removeActiveAdmin(admin) }
}

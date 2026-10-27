package com.shieldpericias.sentinel.usbsecurity

import android.os.UserManager
import com.shieldpericias.sentinel.audit.AuditLog
import com.shieldpericias.sentinel.audit.AuditTypes
import com.shieldpericias.sentinel.core.FeatureState
import com.shieldpericias.sentinel.core.PolicyCheck
import com.shieldpericias.sentinel.core.PolicyResult
import com.shieldpericias.sentinel.core.usb.UsbProfile
import com.shieldpericias.sentinel.deviceowner.DevicePolicyFacade
import com.shieldpericias.sentinel.securestorage.DeviceProtectedKv

data class UsbSnapshot(
    /** Camada A: Modo de Proteção Avançada (nativo). */
    val nativeProtection: FeatureState,
    /** Camada B: sinalização de dados USB desabilitada (DO). */
    val dataSignalingDisabled: FeatureState,
    val dataSignalingSupported: Boolean,
    /** Camada C: DISALLOW_USB_FILE_TRANSFER. */
    val fileTransferRestricted: FeatureState,
    /** Complemento: DISALLOW_MOUNT_PHYSICAL_MEDIA (OTG/armazenamento). */
    val physicalMediaRestricted: FeatureState,
    val selectedProfile: UsbProfile?,
)

/**
 * Proteção USB multicamada. Não promete equivalência ao controle de hardware
 * (ex.: GrapheneOS); documenta o impacto de cada camada e oferece reversão.
 */
class UsbPolicyController(
    private val facade: DevicePolicyFacade,
    private val native: AdvancedProtectionProbe,
    private val kv: DeviceProtectedKv,
    private val audit: AuditLog,
) {
    companion object {
        private const val KEY_PROFILE = "usb.profile"
        const val CHECK_NATIVE = "usb.native"
        const val CHECK_SIGNALING = "usb.signaling"
        const val CHECK_FILE_TRANSFER = "usb.file_transfer"
        const val CHECK_PHYSICAL_MEDIA = "usb.physical_media"
    }

    fun selectedProfile(): UsbProfile? = kv.get(KEY_PROFILE)?.let { runCatching { UsbProfile.valueOf(it) }.getOrNull() }

    fun snapshot(): UsbSnapshot = UsbSnapshot(
        nativeProtection = native.state(),
        dataSignalingDisabled = facade.isUsbDataSignalingEnabled(),
        dataSignalingSupported = facade.canUsbDataSignalingBeDisabled(),
        fileTransferRestricted = facade.restrictionState(UserManager.DISALLOW_USB_FILE_TRANSFER),
        physicalMediaRestricted = facade.restrictionState(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA),
        selectedProfile = selectedProfile(),
    )

    /**
     * Aplica o perfil. Retorna o resultado por política; o estado real deve ser
     * relido com [snapshot] — a aplicação pode ser parcial.
     */
    fun applyProfile(profile: UsbProfile): List<Pair<String, PolicyResult>> {
        val results = mutableListOf<Pair<String, PolicyResult>>()
        results += CHECK_FILE_TRANSFER to facade.setUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, profile.restrictFileTransfer)
        results += CHECK_PHYSICAL_MEDIA to facade.setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, profile.restrictPhysicalMedia)
        results += CHECK_SIGNALING to if (profile.disableUsbDataSignaling) {
            facade.setUsbDataSignalingEnabled(false)
        } else {
            // Reversão explícita: ao sair do perfil máximo, reabilita a sinalização se controlável.
            if (facade.canUsbDataSignalingBeDisabled()) facade.setUsbDataSignalingEnabled(true) else PolicyResult.Unsupported("sem controle de sinalização")
        }
        kv.put(KEY_PROFILE, profile.name)
        results.forEach { (id, r) ->
            audit.append(if (r.ok) AuditTypes.POLICY_APPLIED else AuditTypes.POLICY_FAILED, "usb:${profile.name}:$id:${r.javaClass.simpleName}")
        }
        return results
    }

    /** Procedimento de reversão: remove todas as políticas USB do Sentinel. */
    fun revertAll(): List<Pair<String, PolicyResult>> {
        val results = listOf(
            CHECK_SIGNALING to (if (facade.canUsbDataSignalingBeDisabled()) facade.setUsbDataSignalingEnabled(true) else PolicyResult.Unsupported("sem controle de sinalização")),
            CHECK_FILE_TRANSFER to facade.setUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, false),
            CHECK_PHYSICAL_MEDIA to facade.setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, false),
        )
        kv.remove(KEY_PROFILE)
        audit.append(AuditTypes.POLICY_REVERTED, "usb:all")
        return results
    }

    fun checks(snapshot: UsbSnapshot = snapshot()): List<PolicyCheck> = listOf(
        PolicyCheck(CHECK_NATIVE, "Proteção Avançada (nativa)", snapshot.nativeProtection, weight = 3),
        PolicyCheck(CHECK_SIGNALING, "Sinalização de dados USB desabilitada", snapshot.dataSignalingDisabled,
            detail = "Bloqueia ADB, Android Auto por cabo, OTG e acessórios de dados; carga continua.", weight = 4),
        PolicyCheck(CHECK_FILE_TRANSFER, "Transferência de arquivos USB restrita", snapshot.fileTransferRestricted,
            detail = "Não equivale ao bloqueio completo de dados USB.", weight = 2),
        PolicyCheck(CHECK_PHYSICAL_MEDIA, "Mídia física/OTG restrita", snapshot.physicalMediaRestricted, weight = 1),
    )
}

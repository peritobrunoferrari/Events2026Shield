package com.shieldpericias.sentinel.coresecurity

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.shieldpericias.sentinel.core.AdminMode
import com.shieldpericias.sentinel.core.FeatureState

/** Capacidade do aparelho/modo administrativo, com estado real. */
data class Capability(val id: String, val title: String, val state: FeatureState, val note: String? = null)

/**
 * Detecta o que o aparelho e o modo administrativo atual permitem. Toda leitura
 * é protegida contra exceções; falhas viram [FeatureState.Error], nunca promessa.
 */
class CapabilityDetector(private val context: Context, private val admin: ComponentName) {

    private val dpm get() = context.getSystemService(DevicePolicyManager::class.java)

    fun detect(status: AdminStatus = AdminStatus.detect(context, admin)): List<Capability> {
        val mode = status.mode
        val list = mutableListOf<Capability>()

        list += Capability(
            "admin_mode", "Modo administrativo",
            when (mode) {
                AdminMode.DEVICE_OWNER -> FeatureState.Active("Device Owner")
                AdminMode.PROFILE_OWNER -> FeatureState.Active("Profile Owner (políticas limitadas ao perfil)")
                AdminMode.DEVICE_ADMIN -> FeatureState.Inactive("Apenas Device Admin legado")
                AdminMode.NONE -> FeatureState.Inactive("Aplicativo comum")
            },
        )

        list += Capability(
            "device_admin_feature", "Recurso device_admin do sistema",
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)) FeatureState.Active()
            else FeatureState.Unavailable("Aparelho não declara FEATURE_DEVICE_ADMIN"),
        )

        list += Capability(
            "usb_signaling_control", "Controle de sinalização de dados USB (API 31+)",
            safeState {
                val supported = dpm.canUsbDataSignalingBeDisabled()
                when {
                    !supported -> FeatureState.Unavailable("Aparelho não suporta desabilitar sinalização USB")
                    !mode.isDeviceOwner -> FeatureState.Unavailable("Exige Device Owner (ou PO de perfil corporativo)")
                    else -> FeatureState.Inactive("Suportado; aplicar pelo módulo USB")
                }
            },
        )

        list += Capability(
            "advanced_protection_api", "API do Modo de Proteção Avançada (Android 16+)",
            if (Build.VERSION.SDK_INT >= 36) FeatureState.Inactive("Disponível para consulta") else FeatureState.Unavailable("Requer Android 16 (API 36)"),
        )

        list += Capability(
            "reboot", "DevicePolicyManager.reboot()",
            if (mode.isDeviceOwner) FeatureState.Inactive("Permitido ao Device Owner") else FeatureState.Unavailable("Exige Device Owner"),
        )

        list += Capability(
            "wipe", "Apagamento (wipeDevice / wipeData)",
            when (mode) {
                AdminMode.DEVICE_OWNER -> FeatureState.Inactive(
                    if (Build.VERSION.SDK_INT >= 34) "wipeDevice(): apagamento completo do aparelho" else "wipeData(): apagamento completo",
                )
                AdminMode.PROFILE_OWNER -> FeatureState.Inactive("wipeData(): apaga somente o perfil gerenciado")
                AdminMode.DEVICE_ADMIN -> FeatureState.Unavailable("Device Admin legado não pode apagar o aparelho com targetSdk ≥ 34")
                AdminMode.NONE -> FeatureState.Unavailable("Sem privilégio administrativo")
            },
        )

        list += Capability(
            "user_restrictions", "Restrições de usuário (UserManager.DISALLOW_*)",
            if (mode.isOwner) FeatureState.Inactive("Disponíveis") else FeatureState.Unavailable("Exige Device/Profile Owner"),
        )

        val keyguard = context.getSystemService(KeyguardManager::class.java)
        list += Capability(
            "secure_lock", "Bloqueio de tela seguro",
            if (keyguard?.isDeviceSecure == true) FeatureState.Active() else FeatureState.Inactive("Configure PIN/senha/padrão"),
        )

        list += Capability(
            "fbe", "Criptografia baseada em arquivos (FBE)",
            safeState {
                when (dpm.storageEncryptionStatus) {
                    DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> FeatureState.Active("Por usuário (FBE)")
                    DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE -> FeatureState.Active("Ativa")
                    DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> FeatureState.Inactive("Chave padrão (sem credencial)")
                    DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED -> FeatureState.Unavailable("Não suportada")
                    else -> FeatureState.Inactive("Inativa/ativando")
                }
            },
        )

        val alarm = context.getSystemService(AlarmManager::class.java)
        list += Capability(
            "exact_alarms", "Alarmes exatos (Auto-Reboot pontual)",
            if (alarm?.canScheduleExactAlarms() == true) FeatureState.Active()
            else FeatureState.Inactive("Permissão de alarmes exatos não concedida; usa alarme inexato"),
        )

        val nm = context.getSystemService(NotificationManager::class.java)
        list += Capability(
            "notifications", "Notificações locais (alertas do Watchdog)",
            if (nm?.areNotificationsEnabled() == true) FeatureState.Active() else FeatureState.Inactive("Permita notificações"),
        )

        list += Capability(
            "developer_options", "Opções do desenvolvedor",
            if (globalInt(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1) FeatureState.Inactive("Ativadas (superfície de ataque)") else FeatureState.Active("Desativadas"),
            note = "Leitura apenas; desative em Configurações ou via DISALLOW_DEBUGGING_FEATURES.",
        )
        list += Capability(
            "adb_enabled", "Depuração USB (ADB)",
            if (globalInt(Settings.Global.ADB_ENABLED) == 1) FeatureState.Inactive("Ativada") else FeatureState.Active("Desativada"),
        )

        list += Capability(
            "provisioning_allowed", "Provisionamento Device Owner permitido agora",
            if (status.provisioningAllowed) FeatureState.Active("Sim (aparelho sem contas/usuários, sem outro DO)")
            else FeatureState.Inactive("Não (já gerenciado, contas presentes ou setup concluído)"),
        )

        return list
    }

    private fun globalInt(name: String): Int = runCatching { Settings.Global.getInt(context.contentResolver, name) }.getOrDefault(0)

    private inline fun safeState(block: () -> FeatureState): FeatureState = try {
        block()
    } catch (e: SecurityException) {
        FeatureState.Unavailable("Sem privilégio: ${e.message ?: "SecurityException"}")
    } catch (e: Exception) {
        FeatureState.Error(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
    }
}

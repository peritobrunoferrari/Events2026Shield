package com.shieldpericias.sentinel.hardening

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.shieldpericias.sentinel.audit.AuditLog
import com.shieldpericias.sentinel.audit.AuditTypes
import com.shieldpericias.sentinel.core.FeatureState
import com.shieldpericias.sentinel.core.PolicyCheck
import com.shieldpericias.sentinel.core.PolicyResult
import com.shieldpericias.sentinel.deviceowner.DevicePolicyFacade
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Políticas de hardening configuráveis. Nenhuma desativa atualizações ou altera SELinux. */
enum class HardeningPolicy(val title: String, val description: String, val weight: Int, val riskNote: String? = null) {
    UNKNOWN_SOURCES("Bloquear fontes desconhecidas", "DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY — impede sideload em todos os usuários.", 3),
    DEBUGGING("Bloquear depuração", "DISALLOW_DEBUGGING_FEATURES — desativa opções do desenvolvedor e ADB.", 3,
        riskNote = "Remove o ADB como via de recuperação. Garanta outro caminho (ver Guia de Recuperação)."),
    SAFE_BOOT("Bloquear modo seguro", "DISALLOW_SAFE_BOOT — impede boot em modo seguro (desabilitaria o Sentinel).", 2),
    FACTORY_RESET_UI("Bloquear reset pela interface", "DISALLOW_FACTORY_RESET — remove o reset de fábrica das Configurações.", 2,
        riskNote = "O reset continua possível pelo próprio Sentinel (Auto-Wipe/Duress) e pelo recovery."),
    ADD_USER("Bloquear novos usuários", "DISALLOW_ADD_USER — impede criar usuários/convidado.", 1),
    CONFIG_DATE_TIME("Proteger data e hora", "DISALLOW_CONFIG_DATE_TIME + hora automática — dificulta manipular os contadores de inatividade.", 2),
    CAMERA_DISABLED("Desativar câmera", "setCameraDisabled — desativa todas as câmeras do aparelho.", 0,
        riskNote = "Opcional e situacional (ex.: ambiente restrito)."),
    SCREEN_CAPTURE_DISABLED("Bloquear captura de tela (sistema)", "setScreenCaptureDisabled — bloqueia screenshots/gravação em todo o aparelho.", 1,
        riskNote = "O Sentinel já protege a própria interface com FLAG_SECURE independentemente desta política."),
    PASSWORD_COMPLEXITY_HIGH("Exigir senha de alta complexidade", "setRequiredPasswordComplexity(HIGH) — PIN ≥ 8 dígitos sem sequência, ou senha alfanumérica ≥ 6.", 3),
    MAX_TIME_TO_LOCK("Bloqueio automático em até 1 minuto", "setMaximumTimeToLock(60 s).", 2),
    DISABLE_TRUST_AGENTS("Desativar agentes de confiança (Smart Lock)", "KEYGUARD_DISABLE_TRUST_AGENTS — impede desbloqueio por local/dispositivo confiável.", 2),
    UNINSTALL_BLOCKED_SELF("Bloquear desinstalação do Sentinel", "setUninstallBlocked — impede remoção pelo usuário; NÃO impede remoção via reset/recovery.", 2),
}

class HardeningController(
    private val context: Context,
    private val facade: DevicePolicyFacade,
    private val audit: AuditLog,
) {
    private val lockTimeoutMs = 60_000L

    fun state(policy: HardeningPolicy): FeatureState = when (policy) {
        HardeningPolicy.UNKNOWN_SOURCES -> facade.restrictionState(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        HardeningPolicy.DEBUGGING -> facade.restrictionState(UserManager.DISALLOW_DEBUGGING_FEATURES)
        HardeningPolicy.SAFE_BOOT -> facade.restrictionState(UserManager.DISALLOW_SAFE_BOOT)
        HardeningPolicy.FACTORY_RESET_UI -> facade.restrictionState(UserManager.DISALLOW_FACTORY_RESET)
        HardeningPolicy.ADD_USER -> facade.restrictionState(UserManager.DISALLOW_ADD_USER)
        HardeningPolicy.CONFIG_DATE_TIME -> {
            val r = facade.restrictionState(UserManager.DISALLOW_CONFIG_DATE_TIME)
            val t = facade.autoTimeEnabled()
            if (r.isActive && t.isActive) FeatureState.Active("Restrição + hora automática") else if (r is FeatureState.Unavailable) r else FeatureState.Inactive()
        }
        HardeningPolicy.CAMERA_DISABLED -> facade.cameraDisabled()
        HardeningPolicy.SCREEN_CAPTURE_DISABLED -> facade.screenCaptureDisabled()
        HardeningPolicy.PASSWORD_COMPLEXITY_HIGH -> {
            val required = facade.requiredPasswordComplexity()
            when {
                !facade.mode().isOwner -> FeatureState.Unavailable("Exige Device/Profile Owner")
                required == null -> FeatureState.Error("Não foi possível ler a complexidade exigida")
                required >= DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH -> FeatureState.Active("HIGH exigida")
                else -> FeatureState.Inactive("Exigência atual: ${complexityName(required)}")
            }
        }
        HardeningPolicy.MAX_TIME_TO_LOCK -> {
            val v = facade.maximumTimeToLock()
            when {
                v == null -> FeatureState.Unavailable("Sem administrador ativo")
                v in 1..lockTimeoutMs -> FeatureState.Active("${v / 1000} s")
                else -> FeatureState.Inactive(if (v == 0L) "Sem limite" else "${v / 1000} s")
            }
        }
        HardeningPolicy.DISABLE_TRUST_AGENTS -> {
            val f = facade.keyguardDisabledFeatures()
            when {
                f == null -> FeatureState.Unavailable("Sem administrador ativo")
                f and DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS != 0 -> FeatureState.Active()
                else -> FeatureState.Inactive()
            }
        }
        HardeningPolicy.UNINSTALL_BLOCKED_SELF -> facade.uninstallBlocked()
    }

    fun apply(policy: HardeningPolicy, enable: Boolean): PolicyResult {
        val r = when (policy) {
            HardeningPolicy.UNKNOWN_SOURCES -> facade.setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, enable)
            HardeningPolicy.DEBUGGING -> facade.setUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, enable)
            HardeningPolicy.SAFE_BOOT -> facade.setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, enable)
            HardeningPolicy.FACTORY_RESET_UI -> facade.setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, enable)
            HardeningPolicy.ADD_USER -> facade.setUserRestriction(UserManager.DISALLOW_ADD_USER, enable)
            HardeningPolicy.CONFIG_DATE_TIME -> {
                val a = facade.setUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, enable)
                val b = facade.setAutoTimeEnabled(enable)
                if (a.ok) b else a
            }
            HardeningPolicy.CAMERA_DISABLED -> facade.setCameraDisabled(enable)
            HardeningPolicy.SCREEN_CAPTURE_DISABLED -> facade.setScreenCaptureDisabled(enable)
            HardeningPolicy.PASSWORD_COMPLEXITY_HIGH -> facade.setRequiredPasswordComplexity(
                if (enable) DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH else DevicePolicyManager.PASSWORD_COMPLEXITY_NONE,
            )
            HardeningPolicy.MAX_TIME_TO_LOCK -> facade.setMaximumTimeToLock(if (enable) lockTimeoutMs else 0L)
            HardeningPolicy.DISABLE_TRUST_AGENTS -> {
                val current = facade.keyguardDisabledFeatures() ?: 0
                val next = if (enable) current or DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS
                else current and DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS.inv()
                facade.setKeyguardDisabledFeatures(next)
            }
            HardeningPolicy.UNINSTALL_BLOCKED_SELF -> facade.setUninstallBlocked(enable)
        }
        audit.append(
            if (r.ok) (if (enable) AuditTypes.POLICY_APPLIED else AuditTypes.POLICY_REVERTED) else AuditTypes.POLICY_FAILED,
            "hardening:${policy.name}:${r.javaClass.simpleName}",
        )
        return r
    }

    fun checks(): List<PolicyCheck> = HardeningPolicy.entries.map {
        PolicyCheck("hardening.${it.name.lowercase()}", it.title, state(it), detail = it.description, weight = it.weight)
    }

    /** Alertas de configurações inseguras (somente leitura). */
    fun insecureSettings(): List<PolicyCheck> {
        val list = mutableListOf<PolicyCheck>()
        val km = context.getSystemService(KeyguardManager::class.java)
        list += PolicyCheck("insecure.lock", "Bloqueio de tela seguro",
            if (km?.isDeviceSecure == true) FeatureState.Active() else FeatureState.Inactive("Sem PIN/senha: FBE protege pouco e o Sentinel não consegue verificar desbloqueios"),
            weight = 4, critical = true)
        val dev = globalInt(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1
        list += PolicyCheck("insecure.devopts", "Opções do desenvolvedor desativadas", if (dev) FeatureState.Inactive("Ativadas") else FeatureState.Active(), weight = 1)
        val adb = globalInt(Settings.Global.ADB_ENABLED) == 1
        list += PolicyCheck("insecure.adb", "Depuração USB desativada", if (adb) FeatureState.Inactive("ADB ativo") else FeatureState.Active(), weight = 2)
        facade.currentPasswordComplexity()?.let { c ->
            list += PolicyCheck("insecure.complexity", "Complexidade da credencial atual",
                if (c >= DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM) FeatureState.Active(complexityName(c)) else FeatureState.Inactive(complexityName(c)), weight = 2)
        }
        list += securityPatchCheck()
        facade.pendingSystemUpdate()?.let { info ->
            list += PolicyCheck("update.pending", "Atualização do sistema pendente",
                FeatureState.Inactive("Recebida em ${java.util.Date(info.receivedTime)}; instale quando possível"), weight = 0)
        }
        return list
    }

    private fun securityPatchCheck(): PolicyCheck {
        val patch = Build.VERSION.SECURITY_PATCH
        val state = runCatching {
            val days = ChronoUnit.DAYS.between(LocalDate.parse(patch), LocalDate.now())
            if (days <= 60) FeatureState.Active("Patch $patch") else FeatureState.Inactive("Patch $patch tem $days dias")
        }.getOrElse { FeatureState.Error("Patch ilegível: $patch") }
        return PolicyCheck("update.patch", "Patch de segurança recente", state, weight = 2)
    }

    private fun globalInt(name: String) = runCatching { Settings.Global.getInt(context.contentResolver, name) }.getOrDefault(0)

    private fun complexityName(c: Int) = when (c) {
        DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH -> "HIGH"
        DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM -> "MEDIUM"
        DevicePolicyManager.PASSWORD_COMPLEXITY_LOW -> "LOW"
        else -> "NONE"
    }
}

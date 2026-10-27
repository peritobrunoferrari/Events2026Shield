package com.shieldpericias.sentinel.emergency

import com.shieldpericias.sentinel.audit.AuditLog
import com.shieldpericias.sentinel.audit.AuditTypes
import com.shieldpericias.sentinel.core.emergency.EmergencyActionId
import com.shieldpericias.sentinel.core.emergency.EmergencyArming
import com.shieldpericias.sentinel.securestorage.DeviceProtectedKv

enum class TriggerSource { APP_BUTTON, SHORTCUT, QUICK_SETTINGS_TILE, DURESS_PIN, REMOTE_COMMAND }

sealed class EngineResult {
    data class Executed(val results: List<ActionResult>) : EngineResult()
    data object NotArmed : EngineResult()
    data object NeedsConfirmation : EngineResult()
}

class EmergencyConfigStore(private val kv: DeviceProtectedKv) {
    fun load(): EmergencyArming = EmergencyArming(
        armed = kv.getBoolean("em.armed", false),
        requireConfirmation = kv.getBoolean("em.confirm", true),
        actions = kv.get("em.actions")?.split(',')?.mapNotNull { runCatching { EmergencyActionId.valueOf(it) }.getOrNull() }?.toSet()
            ?: setOf(EmergencyActionId.LOCK_NOW, EmergencyActionId.SECURITY_STATUS),
    )

    fun save(a: EmergencyArming) {
        kv.putBoolean("em.armed", a.armed)
        kv.putBoolean("em.confirm", a.requireConfirmation)
        kv.put("em.actions", a.actions.joinToString(",") { it.name })
    }

    /** Permite executar direto do Tile mesmo com a tela bloqueada (apenas ações protetivas). */
    var allowFromLockScreen: Boolean
        get() = kv.getBoolean("em.lockscreen", false)
        set(v) = kv.putBoolean("em.lockscreen", v)
}

/**
 * Emergency Action Engine. Exige configuração ("armado") e, por padrão,
 * confirmação explícita. A ordem de execução é fixa: bloquear → USB → status → reboot.
 */
class EmergencyEngine(
    private val configStore: EmergencyConfigStore,
    private val actions: Map<EmergencyActionId, EmergencyAction>,
    private val audit: AuditLog,
) {
    private val order = listOf(EmergencyActionId.LOCK_NOW, EmergencyActionId.USB_PROTECTION, EmergencyActionId.SECURITY_STATUS, EmergencyActionId.REBOOT)

    fun config(): EmergencyArming = configStore.load()

    fun trigger(source: TriggerSource, confirmed: Boolean, override: Set<EmergencyActionId>? = null): EngineResult {
        val cfg = configStore.load()
        if (!cfg.armed && source != TriggerSource.DURESS_PIN && source != TriggerSource.REMOTE_COMMAND) {
            audit.append(AuditTypes.EMERGENCY_REFUSED, "$source: não armado")
            return EngineResult.NotArmed
        }
        if (cfg.requireConfirmation && !confirmed && source != TriggerSource.DURESS_PIN && source != TriggerSource.REMOTE_COMMAND) {
            return EngineResult.NeedsConfirmation
        }
        val selected = override ?: cfg.actions
        audit.append(AuditTypes.EMERGENCY_TRIGGERED, "$source: ${selected.joinToString(",")}")
        val results = order.filter { it in selected }.mapNotNull { id -> actions[id]?.let { runCatching { it.execute() }.getOrElse { e ->
            ActionResult(id, com.shieldpericias.sentinel.core.PolicyResult.Failed(e.javaClass.simpleName), e.message ?: "erro") } } }
        return EngineResult.Executed(results)
    }
}

object EmergencyIntents {
    /** Ação implícita (restrita ao próprio pacote) tratada pela Activity de emergência do app. */
    const val ACTION_EMERGENCY = "com.shieldpericias.sentinel.action.EMERGENCY"
    const val EXTRA_SOURCE = "source"
}

object EmergencyEntryPoint {
    @Volatile
    var provider: ((android.content.Context) -> EmergencyEngine)? = null

    @Volatile
    var configStoreProvider: ((android.content.Context) -> EmergencyConfigStore)? = null
}

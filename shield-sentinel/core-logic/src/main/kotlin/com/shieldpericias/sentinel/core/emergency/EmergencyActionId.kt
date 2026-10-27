package com.shieldpericias.sentinel.core.emergency

enum class EmergencyActionId {
    LOCK_NOW,
    USB_PROTECTION,
    REBOOT,
    SECURITY_STATUS,
}

/** Política de proteção contra acionamento acidental. */
data class EmergencyArming(
    /** Precisa estar armado (configurado conscientemente) para executar. */
    val armed: Boolean = false,
    /** Exigir confirmação na UI (duplo toque / diálogo) antes de executar. */
    val requireConfirmation: Boolean = true,
    val actions: Set<EmergencyActionId> = setOf(EmergencyActionId.LOCK_NOW, EmergencyActionId.SECURITY_STATUS),
)

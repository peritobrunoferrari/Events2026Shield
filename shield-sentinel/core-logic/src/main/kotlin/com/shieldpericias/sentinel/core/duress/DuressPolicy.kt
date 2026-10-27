package com.shieldpericias.sentinel.core.duress

/** Ação executada quando o PIN de emergência (duress) é digitado na UI do Sentinel. */
enum class DuressAction {
    LOCK,
    LOCK_AND_REBOOT,
    /** Apagamento completo. Opcional, desativado por padrão, exige reconhecimento explícito. */
    FACTORY_RESET,
}

data class DuressPolicy(
    val enabled: Boolean = false,
    val action: DuressAction = DuressAction.LOCK,
    /** Reconhecimento consciente do proprietário para FACTORY_RESET (texto de confirmação digitado). */
    val ownerAcknowledgedFactoryReset: Boolean = false,
    /** eSIM nunca apagada por padrão. */
    val wipeEsim: Boolean = false,
) {
    /** Política é válida para execução sem confirmação adicional? */
    val isExecutable: Boolean
        get() = enabled && (action != DuressAction.FACTORY_RESET || ownerAcknowledgedFactoryReset)
}

enum class PinKind { NORMAL, DURESS, INVALID }

/**
 * Verifica o PIN digitado contra ambos os registros SEMPRE (trabalho constante),
 * para que o tempo de resposta não revele qual PIN existe.
 */
object PinVerifier {
    fun classify(pin: CharArray, normal: PinRecord?, duress: PinRecord?): PinKind {
        val n = normal?.let { PinHasher.matches(pin, it) } ?: false
        val d = duress?.let { PinHasher.matches(pin, it) } ?: false
        return when {
            n -> PinKind.NORMAL
            d -> PinKind.DURESS
            else -> PinKind.INVALID
        }
    }

    /** PIN duress não pode ser igual ao normal e deve ter tamanho mínimo. */
    fun validateNewDuressPin(pin: CharArray, normal: PinRecord?, minLength: Int = 4): Boolean {
        if (pin.size < minLength) return false
        if (normal != null && PinHasher.matches(pin, normal)) return false
        return true
    }
}

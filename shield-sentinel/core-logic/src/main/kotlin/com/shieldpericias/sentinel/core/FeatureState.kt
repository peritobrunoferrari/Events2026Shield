package com.shieldpericias.sentinel.core

/**
 * Estado REAL de um recurso. Todo recurso do Shield Sentinel deve ser exibido
 * com um destes estados — nunca como promessa.
 */
sealed class FeatureState {
    /** Política aplicada e confirmada por leitura do sistema. */
    data class Active(val detail: String? = null) : FeatureState()

    /** Recurso disponível, porém não aplicado/habilitado. */
    data class Inactive(val detail: String? = null) : FeatureState()

    /** O sistema/dispositivo/modo administrativo não permite o recurso. */
    data class Unavailable(val reason: String) : FeatureState()

    /** Tentativa de leitura ou aplicação falhou. */
    data class Error(val message: String) : FeatureState()

    val isActive: Boolean get() = this is Active
    val isUnavailable: Boolean get() = this is Unavailable

    val kind: Kind
        get() = when (this) {
            is Active -> Kind.ACTIVE
            is Inactive -> Kind.INACTIVE
            is Unavailable -> Kind.UNAVAILABLE
            is Error -> Kind.ERROR
        }

    enum class Kind { ACTIVE, INACTIVE, UNAVAILABLE, ERROR }
}

/** Resultado de uma tentativa de aplicar/reverter uma política. */
sealed class PolicyResult {
    data object Applied : PolicyResult()
    data class Unsupported(val reason: String) : PolicyResult()
    data class Denied(val reason: String) : PolicyResult()
    data class Failed(val message: String) : PolicyResult()

    val ok: Boolean get() = this is Applied
}

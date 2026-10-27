package com.shieldpericias.sentinel.antitheft

import android.app.admin.DevicePolicyManager
import com.shieldpericias.sentinel.core.AdminMode
import com.shieldpericias.sentinel.core.PolicyResult
import com.shieldpericias.sentinel.deviceowner.DevicePolicyFacade

enum class WipeScope {
    /** Factory reset do aparelho inteiro (Device Owner). */
    DEVICE,
    /** Apenas o perfil/usuário gerenciado (Profile Owner). */
    PROFILE,
}

data class WipeRequest(
    val scope: WipeScope,
    val reason: String,
    val origin: String,
    val wipeEsim: Boolean = false,
    val wipeExternalStorage: Boolean = false,
    val wipeResetProtectionData: Boolean = false,
) {
    fun flags(): Int {
        var f = 0
        if (wipeEsim) f = f or DevicePolicyManager.WIPE_EUICC
        if (wipeExternalStorage) f = f or DevicePolicyManager.WIPE_EXTERNAL_STORAGE
        if (wipeResetProtectionData) f = f or DevicePolicyManager.WIPE_RESET_PROTECTION_DATA
        return f
    }
}

data class WipeOutcome(val requested: WipeRequest, val result: PolicyResult, val simulated: Boolean)

/**
 * Única porta de saída para apagamento. Toda chamada de wipe no projeto passa
 * por aqui, de modo que testes e o modo de ensaio usem [FakeWipeController].
 */
interface WipeController {
    fun wipe(request: WipeRequest): WipeOutcome

    /** Qual escopo o modo administrativo atual permite, ou null se nenhum. */
    fun availableScope(): WipeScope?
}

class RealWipeController(private val facade: DevicePolicyFacade) : WipeController {
    override fun availableScope(): WipeScope? = when (facade.mode()) {
        AdminMode.DEVICE_OWNER -> WipeScope.DEVICE
        AdminMode.PROFILE_OWNER -> WipeScope.PROFILE
        else -> null
    }

    override fun wipe(request: WipeRequest): WipeOutcome {
        val scope = availableScope()
            ?: return WipeOutcome(request, PolicyResult.Unsupported("Sem Device/Profile Owner: apagamento indisponível"), simulated = false)
        if (scope != request.scope) {
            return WipeOutcome(request, PolicyResult.Unsupported("Modo atual permite apenas ${scope.name}; solicitado ${request.scope.name}"), simulated = false)
        }
        val r = when (scope) {
            WipeScope.DEVICE -> facade.wipeDeviceInternal(request.flags())
            WipeScope.PROFILE -> facade.wipeProfileInternal(request.flags(), request.reason)
        }
        return WipeOutcome(request, r, simulated = false)
    }
}

/** Usado em TODOS os testes e no modo de ensaio: registra e nunca apaga. */
class FakeWipeController(private val scope: WipeScope? = WipeScope.DEVICE) : WipeController {
    val requests = mutableListOf<WipeRequest>()
    override fun availableScope(): WipeScope? = scope
    override fun wipe(request: WipeRequest): WipeOutcome {
        requests += request
        return WipeOutcome(request, PolicyResult.Applied, simulated = true)
    }
}

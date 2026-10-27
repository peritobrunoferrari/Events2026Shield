package com.shieldpericias.sentinel.usbsecurity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import com.shieldpericias.sentinel.core.FeatureState
import java.util.concurrent.Executor

/**
 * Camada A — Proteção nativa do Android (Modo de Proteção Avançada, Android 16+).
 *
 * A API pública permite apenas CONSULTAR o estado e receber callbacks
 * (permissão normal QUERY_ADVANCED_PROTECTION_MODE). A ativação é feita pelo
 * usuário nas Configurações; o Sentinel só orienta — não há controle programático.
 */
class AdvancedProtectionProbe(private val context: Context) {

    private val manager: AdvancedProtectionManager?
        get() = if (Build.VERSION.SDK_INT >= 36) context.getSystemService(AdvancedProtectionManager::class.java) else null

    val isApiAvailable: Boolean get() = Build.VERSION.SDK_INT >= 36

    fun state(): FeatureState {
        if (Build.VERSION.SDK_INT < 36) return FeatureState.Unavailable("Modo de Proteção Avançada requer Android 16 (API 36)")
        val m = manager ?: return FeatureState.Unavailable("Serviço AdvancedProtectionManager indisponível neste aparelho")
        return try {
            if (m.isAdvancedProtectionEnabled) FeatureState.Active("Proteção Avançada ativada pelo usuário")
            else FeatureState.Inactive("Ative em Configurações › Segurança e privacidade › Proteção Avançada")
        } catch (e: SecurityException) {
            FeatureState.Error("Permissão QUERY_ADVANCED_PROTECTION_MODE ausente: ${e.message}")
        } catch (e: Exception) {
            FeatureState.Error("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Observa mudanças de estado. Retorna função de cancelamento (no-op quando indisponível). */
    fun observe(executor: Executor, onChange: (Boolean) -> Unit): () -> Unit {
        val m = manager ?: return {}
        val cb = object : AdvancedProtectionManager.Callback {
            override fun onAdvancedProtectionChanged(enabled: Boolean) = onChange(enabled)
        }
        val registered = runCatching { m.registerAdvancedProtectionCallback(executor, cb) }.isSuccess
        if (!registered) return {}
        val cancel: () -> Unit = { runCatching { m.unregisterAdvancedProtectionCallback(cb) } }
        return cancel
    }

    /** Intent para a tela de segurança (o sistema não expõe ação pública específica da Proteção Avançada). */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

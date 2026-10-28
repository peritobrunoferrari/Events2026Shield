package com.shieldpericias.sentinel.deviceowner

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle

/**
 * Ponto de entrada administrativo do Shield Sentinel.
 *
 * Os callbacks são encaminhados a [DeviceAdminEvents] para que módulos de nível
 * superior (auto-reboot, antirroubo, auditoria) reajam sem criar dependências
 * circulares. Nada aqui tenta "promover" privilégios: o receiver só observa.
 */
class SentinelDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        DeviceAdminEvents.dispatch(context) { it.onAdminEnabled(context) }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.sentinel_admin_disable_warning)

    override fun onDisabled(context: Context, intent: Intent) {
        DeviceAdminEvents.dispatch(context) { it.onAdminDisabled(context) }
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        DeviceAdminEvents.dispatch(context) { it.onProvisioningComplete(context) }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        // Sinal secundário de desbloqueio autenticado (credencial). A verificação
        // criptográfica (chave auth-bound) é feita pelo módulo watchdog.
        DeviceAdminEvents.dispatch(context) { it.onCredentialSucceeded(context) }
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        DeviceAdminEvents.dispatch(context) { it.onCredentialFailed(context) }
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) = Unit

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context.applicationContext, SentinelDeviceAdminReceiver::class.java)
    }
}

/** Registro de ouvintes dos eventos administrativos (configurado pelo grafo do app). */
object DeviceAdminEvents {
    interface Listener {
        fun onAdminEnabled(context: Context) {}
        fun onAdminDisabled(context: Context) {}
        fun onProvisioningComplete(context: Context) {}
        fun onCredentialSucceeded(context: Context) {}
        fun onCredentialFailed(context: Context) {}
        /** Resultado assíncrono de política (API 34+). [changed] = true quando a política mudou após ser definida. */
        fun onPolicyResult(context: Context, policyIdentifier: String, result: String, changed: Boolean) {}
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    /** Fábrica opcional usada quando o processo é iniciado pelo próprio broadcast (listeners ainda vazios). */
    @Volatile
    var bootstrap: ((Context) -> Unit)? = null

    fun register(l: Listener) { if (l !in listeners) listeners += l }
    fun unregister(l: Listener) { listeners -= l }

    internal fun dispatch(context: Context, block: (Listener) -> Unit) {
        if (listeners.isEmpty()) bootstrap?.invoke(context.applicationContext)
        listeners.forEach { runCatching { block(it) } }
    }
}

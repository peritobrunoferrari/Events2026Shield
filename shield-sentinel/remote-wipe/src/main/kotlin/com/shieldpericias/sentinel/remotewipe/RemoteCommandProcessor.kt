package com.shieldpericias.sentinel.remotewipe

import com.shieldpericias.sentinel.antitheft.WipeController
import com.shieldpericias.sentinel.antitheft.WipeRequest
import com.shieldpericias.sentinel.audit.AuditLog
import com.shieldpericias.sentinel.audit.AuditTypes
import com.shieldpericias.sentinel.autoreboot.RebootExecutor
import com.shieldpericias.sentinel.core.PolicyResult
import com.shieldpericias.sentinel.core.remote.CommandVerifier
import com.shieldpericias.sentinel.core.remote.RemoteAction
import com.shieldpericias.sentinel.core.remote.RemoteCommand
import com.shieldpericias.sentinel.core.remote.VerificationResult
import com.shieldpericias.sentinel.deviceowner.DevicePolicyFacade

/**
 * Transporte é opcional e intercambiável: o processador só recebe texto. Qualquer
 * transporte (QR, área de transferência, push, HTTPS) deve entregar o comando a
 * [RemoteCommandProcessor.submit]; a autorização nunca depende do canal.
 */
interface RemoteCommandTransport {
    val name: String
    fun start(onCommand: (String) -> Unit)
    fun stop()
}

/** Transporte manual (colar/QR na UI). Funciona 100% offline e serve de referência. */
class ManualTransport : RemoteCommandTransport {
    override val name = "manual"
    private var handler: ((String) -> Unit)? = null
    override fun start(onCommand: (String) -> Unit) { handler = onCommand }
    override fun stop() { handler = null }
    fun submit(text: String) { handler?.invoke(text) }
}

sealed class RemoteOutcome {
    data class Rejected(val reason: VerificationResult.Reason, val detail: String?) : RemoteOutcome()
    data class PendingConfirmation(val command: RemoteCommand) : RemoteOutcome()
    data class Executed(val command: RemoteCommand, val result: PolicyResult, val simulated: Boolean) : RemoteOutcome()
}

class RemoteCommandProcessor(
    private val configStore: RemoteWipeConfigStore,
    private val verifier: CommandVerifier,
    private val facade: DevicePolicyFacade,
    private val wipeControllerProvider: () -> WipeController,
    private val rebootExecutor: RebootExecutor,
    private val audit: AuditLog,
    private val onStatusReport: () -> Unit = {},
) {
    private val pending = java.util.concurrent.ConcurrentHashMap<String, RemoteCommand>()

    fun pendingCommands(): List<RemoteCommand> = pending.values.toList()

    fun submit(text: String): RemoteOutcome {
        val cfg = configStore.load()
        if (!cfg.enabled) {
            audit.append(AuditTypes.REMOTE_COMMAND_REJECTED, "DISABLED")
            return RemoteOutcome.Rejected(VerificationResult.Reason.DISABLED, "Remote Wipe desativado")
        }
        return when (val v = verifier.verifyTransport(text)) {
            is VerificationResult.Rejected -> {
                audit.append(AuditTypes.REMOTE_COMMAND_REJECTED, "${v.reason} ${v.detail ?: ""}")
                RemoteOutcome.Rejected(v.reason, v.detail)
            }
            is VerificationResult.Accepted -> {
                audit.append(AuditTypes.REMOTE_COMMAND_ACCEPTED, "${v.command.action} key=${v.command.keyId}")
                if (v.command.action == RemoteAction.WIPE_DEVICE && cfg.requireAdminConfirmation) {
                    pending[v.command.commandId] = v.command
                    RemoteOutcome.PendingConfirmation(v.command)
                } else execute(v.command)
            }
        }
    }

    fun confirm(commandId: String): RemoteOutcome? = pending.remove(commandId)?.let { execute(it) }
    fun discard(commandId: String) { pending.remove(commandId) }

    private fun execute(c: RemoteCommand): RemoteOutcome {
        val (result, simulated) = when (c.action) {
            RemoteAction.LOCK -> facade.lockNow() to false
            RemoteAction.REBOOT -> rebootExecutor.reboot("remote") to false
            RemoteAction.STATUS_REPORT -> { onStatusReport(); PolicyResult.Applied to false }
            RemoteAction.WIPE_DEVICE -> {
                val wc = wipeControllerProvider()
                val scope = wc.availableScope()
                if (scope == null) PolicyResult.Unsupported("sem escopo de apagamento") to false
                else wc.wipe(WipeRequest(scope, "Shield Sentinel: comando remoto autenticado", "remote_wipe")).let { it.result to it.simulated }
            }
        }
        audit.append(AuditTypes.REMOTE_COMMAND_EXECUTED, "${c.action} ${result.javaClass.simpleName}${if (simulated) " (ensaio)" else ""}")
        return RemoteOutcome.Executed(c, result, simulated)
    }
}

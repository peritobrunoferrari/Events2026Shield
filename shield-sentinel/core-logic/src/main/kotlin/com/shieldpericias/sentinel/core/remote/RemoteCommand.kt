package com.shieldpericias.sentinel.core.remote

/** Ações aceitas por comando remoto autenticado. */
enum class RemoteAction { LOCK, REBOOT, STATUS_REPORT, WIPE_DEVICE }

/**
 * Comando remoto. O transporte (módulo opcional) só entrega bytes; a autorização
 * depende exclusivamente da assinatura, do vínculo com o dispositivo, da janela
 * temporal e da proteção contra replay verificados em [CommandVerifier].
 */
data class RemoteCommand(
    val version: Int = PROTOCOL_VERSION,
    /** Identificador do dispositivo gerado localmente no enrolamento (não é IMEI/telefone). */
    val deviceId: String,
    /** Identificador único do comando (UUID) — proteção contra replay. */
    val commandId: String,
    /** Contador monotônico por chave administrativa — proteção contra replay/reordenação. */
    val sequence: Long,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val action: RemoteAction,
    /** Identificador da chave administrativa que assina. */
    val keyId: String,
) {
    companion object {
        const val PROTOCOL_VERSION = 1
    }
}

data class SignedCommand(val command: RemoteCommand, val signature: ByteArray)

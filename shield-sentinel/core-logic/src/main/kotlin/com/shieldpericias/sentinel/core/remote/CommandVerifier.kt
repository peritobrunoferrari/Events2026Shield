package com.shieldpericias.sentinel.core.remote

import java.security.PublicKey

/** Chaves administrativas confiáveis (pinadas no enrolamento) e revogação. */
interface TrustAnchors {
    fun publicKey(keyId: String): PublicKey?
    fun isRevoked(keyId: String): Boolean
}

/** Memória anti-replay: último contador por chave e IDs já vistos. */
interface ReplayStore {
    fun lastSequence(keyId: String): Long?
    fun wasSeen(commandId: String): Boolean
    fun remember(keyId: String, sequence: Long, commandId: String)
}

class InMemoryReplayStore : ReplayStore {
    private val seq = HashMap<String, Long>()
    private val seen = HashSet<String>()
    override fun lastSequence(keyId: String) = seq[keyId]
    override fun wasSeen(commandId: String) = commandId in seen
    override fun remember(keyId: String, sequence: Long, commandId: String) {
        seq[keyId] = maxOf(seq[keyId] ?: Long.MIN_VALUE, sequence)
        seen += commandId
    }
}

sealed class VerificationResult {
    data class Accepted(val command: RemoteCommand) : VerificationResult()
    data class Rejected(val reason: Reason, val detail: String? = null) : VerificationResult()

    enum class Reason {
        MALFORMED, UNSUPPORTED_VERSION, WRONG_DEVICE, UNKNOWN_KEY, REVOKED_KEY, BAD_SIGNATURE,
        NOT_YET_VALID, EXPIRED, VALIDITY_TOO_LONG, REPLAY_SEQUENCE, REPLAY_COMMAND_ID, DISABLED,
    }
}

/**
 * Verificador de comandos remotos. Ordem das verificações é deliberada:
 * primeiro o que não depende de segredo, depois assinatura, depois tempo e replay.
 */
class CommandVerifier(
    private val localDeviceId: String,
    private val trust: TrustAnchors,
    private val replay: ReplayStore,
    private val nowEpochMs: () -> Long,
    private val maxValidityMs: Long = DEFAULT_MAX_VALIDITY_MS,
    private val clockSkewMs: Long = DEFAULT_CLOCK_SKEW_MS,
    private val enabled: () -> Boolean = { true },
) {
    companion object {
        const val DEFAULT_MAX_VALIDITY_MS = 24 * 60 * 60 * 1000L
        const val DEFAULT_CLOCK_SKEW_MS = 5 * 60 * 1000L
    }

    fun verifyTransport(text: String): VerificationResult {
        val signed = try {
            CommandCodec.decodeTransport(text)
        } catch (e: Exception) {
            return VerificationResult.Rejected(VerificationResult.Reason.MALFORMED, e.message)
        }
        return verify(signed)
    }

    fun verify(signed: SignedCommand): VerificationResult {
        val c = signed.command
        if (!enabled()) return VerificationResult.Rejected(VerificationResult.Reason.DISABLED)
        if (c.version != RemoteCommand.PROTOCOL_VERSION) {
            return VerificationResult.Rejected(VerificationResult.Reason.UNSUPPORTED_VERSION, c.version.toString())
        }
        if (c.deviceId != localDeviceId) return VerificationResult.Rejected(VerificationResult.Reason.WRONG_DEVICE)
        if (trust.isRevoked(c.keyId)) return VerificationResult.Rejected(VerificationResult.Reason.REVOKED_KEY, c.keyId)
        val key = trust.publicKey(c.keyId)
            ?: return VerificationResult.Rejected(VerificationResult.Reason.UNKNOWN_KEY, c.keyId)
        if (!CommandSigner.verify(signed, key)) return VerificationResult.Rejected(VerificationResult.Reason.BAD_SIGNATURE)

        val now = nowEpochMs()
        if (c.expiresAtEpochMs - c.issuedAtEpochMs > maxValidityMs || c.expiresAtEpochMs <= c.issuedAtEpochMs) {
            return VerificationResult.Rejected(VerificationResult.Reason.VALIDITY_TOO_LONG)
        }
        if (now < c.issuedAtEpochMs - clockSkewMs) return VerificationResult.Rejected(VerificationResult.Reason.NOT_YET_VALID)
        if (now > c.expiresAtEpochMs) return VerificationResult.Rejected(VerificationResult.Reason.EXPIRED)

        val last = replay.lastSequence(c.keyId)
        if (last != null && c.sequence <= last) return VerificationResult.Rejected(VerificationResult.Reason.REPLAY_SEQUENCE)
        if (replay.wasSeen(c.commandId)) return VerificationResult.Rejected(VerificationResult.Reason.REPLAY_COMMAND_ID)

        replay.remember(c.keyId, c.sequence, c.commandId)
        return VerificationResult.Accepted(c)
    }
}

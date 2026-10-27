package com.shieldpericias.sentinel.tools

import com.shieldpericias.sentinel.core.remote.CommandCodec
import com.shieldpericias.sentinel.core.remote.CommandSigner
import com.shieldpericias.sentinel.core.remote.RemoteAction
import com.shieldpericias.sentinel.core.remote.RemoteCommand
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * Uso:
 *   admin-cli keygen
 *       → imprime PRIVATE (PKCS#8 base64, guarde offline) e PUBLIC (SPKI base64, registre no aparelho)
 *   admin-cli sign <privateBase64> <keyId> <deviceId> <sequence> <ACTION> [ttlMinutes=60]
 *       → imprime o comando assinado no formato de transporte (payload.assinatura)
 */
object AdminCli {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "keygen" -> {
                val kp = CommandSigner.generateAdminKeyPair()
                println("PRIVATE_PKCS8_B64=" + Base64.getEncoder().encodeToString(kp.private.encoded))
                println("PUBLIC_SPKI_B64=" + Base64.getEncoder().encodeToString(kp.public.encoded))
            }
            "sign" -> {
                require(args.size >= 6) { "sign <privateBase64> <keyId> <deviceId> <sequence> <ACTION> [ttlMinutes]" }
                val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(args[1])))
                val now = System.currentTimeMillis()
                val ttl = (args.getOrNull(6)?.toLongOrNull() ?: 60L) * 60_000L
                val cmd = RemoteCommand(
                    deviceId = args[3], commandId = UUID.randomUUID().toString(), sequence = args[4].toLong(),
                    issuedAtEpochMs = now, expiresAtEpochMs = now + ttl, action = RemoteAction.valueOf(args[5]), keyId = args[2],
                )
                println(CommandCodec.encodeTransport(CommandSigner.sign(cmd, priv)))
            }
            else -> { System.err.println("comandos: keygen | sign"); System.exit(2) }
        }
    }
}

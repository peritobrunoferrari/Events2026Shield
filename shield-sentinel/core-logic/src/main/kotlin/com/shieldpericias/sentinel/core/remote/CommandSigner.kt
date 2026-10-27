package com.shieldpericias.sentinel.core.remote

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Assinatura ECDSA P-256 / SHA-256 (disponível na JVM e no Android Keystore).
 * Usado por ferramentas administrativas e por testes; o dispositivo só VERIFICA.
 */
object CommandSigner {
    const val ALGORITHM = "SHA256withECDSA"

    fun generateAdminKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun sign(command: RemoteCommand, privateKey: PrivateKey): SignedCommand {
        val sig = Signature.getInstance(ALGORITHM).apply {
            initSign(privateKey)
            update(CommandCodec.canonicalBytes(command))
        }.sign()
        return SignedCommand(command, sig)
    }

    fun verify(signed: SignedCommand, publicKey: PublicKey): Boolean = try {
        Signature.getInstance(ALGORITHM).run {
            initVerify(publicKey)
            update(CommandCodec.canonicalBytes(signed.command))
            verify(signed.signature)
        }
    } catch (_: Exception) {
        false
    }
}

package com.shieldpericias.sentinel.core

import com.shieldpericias.sentinel.core.remote.CommandCodec
import com.shieldpericias.sentinel.core.remote.CommandSigner
import com.shieldpericias.sentinel.core.remote.CommandVerifier
import com.shieldpericias.sentinel.core.remote.InMemoryReplayStore
import com.shieldpericias.sentinel.core.remote.RemoteAction
import com.shieldpericias.sentinel.core.remote.RemoteCommand
import com.shieldpericias.sentinel.core.remote.TrustAnchors
import com.shieldpericias.sentinel.core.remote.VerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.PublicKey
import java.util.UUID

class CommandVerifierTest {
    private val admin = CommandSigner.generateAdminKeyPair()
    private val other = CommandSigner.generateAdminKeyPair()
    private var now = 1_800_000_000_000L
    private val revoked = HashSet<String>()
    private val trust = object : TrustAnchors {
        override fun publicKey(keyId: String): PublicKey? = if (keyId == "admin-1") admin.public else null
        override fun isRevoked(keyId: String) = keyId in revoked
    }
    private val replay = InMemoryReplayStore()
    private val verifier = CommandVerifier("device-abc", trust, replay, { now })

    private fun cmd(seq: Long = 1, action: RemoteAction = RemoteAction.LOCK, device: String = "device-abc", key: String = "admin-1",
                    issued: Long = now, expires: Long = now + 60_000) =
        RemoteCommand(deviceId = device, commandId = UUID.randomUUID().toString(), sequence = seq,
            issuedAtEpochMs = issued, expiresAtEpochMs = expires, action = action, keyId = key)

    private fun reason(r: VerificationResult) = (r as VerificationResult.Rejected).reason

    @Test
    fun `comando valido e aceito e sobrevive ao transporte`() {
        val signed = CommandSigner.sign(cmd(), admin.private)
        val text = CommandCodec.encodeTransport(signed)
        val r = verifier.verifyTransport(text)
        assertTrue(r is VerificationResult.Accepted)
        assertEquals(RemoteAction.LOCK, (r as VerificationResult.Accepted).command.action)
    }

    @Test
    fun `assinatura de outra chave e rejeitada`() {
        val r = verifier.verify(CommandSigner.sign(cmd(), other.private))
        assertEquals(VerificationResult.Reason.BAD_SIGNATURE, reason(r))
    }

    @Test
    fun `comando adulterado e rejeitado`() {
        val signed = CommandSigner.sign(cmd(action = RemoteAction.LOCK), admin.private)
        val tampered = signed.copy(command = signed.command.copy(action = RemoteAction.WIPE_DEVICE))
        assertEquals(VerificationResult.Reason.BAD_SIGNATURE, reason(verifier.verify(tampered)))
    }

    @Test
    fun `dispositivo errado chave desconhecida e revogada`() {
        assertEquals(VerificationResult.Reason.WRONG_DEVICE, reason(verifier.verify(CommandSigner.sign(cmd(device = "x"), admin.private))))
        assertEquals(VerificationResult.Reason.UNKNOWN_KEY, reason(verifier.verify(CommandSigner.sign(cmd(key = "nope"), other.private))))
        revoked += "admin-1"
        assertEquals(VerificationResult.Reason.REVOKED_KEY, reason(verifier.verify(CommandSigner.sign(cmd(), admin.private))))
    }

    @Test
    fun `janela temporal`() {
        assertEquals(VerificationResult.Reason.EXPIRED, reason(verifier.verify(CommandSigner.sign(cmd(issued = now - 120_000, expires = now - 1), admin.private))))
        assertEquals(VerificationResult.Reason.NOT_YET_VALID, reason(verifier.verify(CommandSigner.sign(cmd(issued = now + 3_600_000, expires = now + 3_660_000), admin.private))))
        assertEquals(VerificationResult.Reason.VALIDITY_TOO_LONG, reason(verifier.verify(CommandSigner.sign(cmd(expires = now + 48 * 3_600_000L), admin.private))))
    }

    @Test
    fun `replay por id e por sequencia`() {
        val first = CommandSigner.sign(cmd(seq = 5), admin.private)
        assertTrue(verifier.verify(first) is VerificationResult.Accepted)
        assertEquals(VerificationResult.Reason.REPLAY_SEQUENCE, reason(verifier.verify(first)))
        assertEquals(VerificationResult.Reason.REPLAY_SEQUENCE, reason(verifier.verify(CommandSigner.sign(cmd(seq = 4), admin.private))))
        assertTrue(verifier.verify(CommandSigner.sign(cmd(seq = 6), admin.private)) is VerificationResult.Accepted)
    }

    @Test
    fun `transporte malformado`() {
        assertEquals(VerificationResult.Reason.MALFORMED, reason(verifier.verifyTransport("isso-nao-e-um-comando")))
        assertEquals(VerificationResult.Reason.MALFORMED, reason(verifier.verifyTransport("AAAA.BBBB")))
    }

    @Test
    fun `desabilitado por padrao rejeita tudo`() {
        val off = CommandVerifier("device-abc", trust, InMemoryReplayStore(), { now }, enabled = { false })
        assertEquals(VerificationResult.Reason.DISABLED, reason(off.verify(CommandSigner.sign(cmd(), admin.private))))
    }
}

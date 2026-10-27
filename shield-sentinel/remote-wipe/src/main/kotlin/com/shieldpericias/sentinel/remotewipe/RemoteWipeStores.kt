package com.shieldpericias.sentinel.remotewipe

import android.util.Base64
import com.shieldpericias.sentinel.core.remote.ReplayStore
import com.shieldpericias.sentinel.core.remote.TrustAnchors
import com.shieldpericias.sentinel.securestorage.SecureKv
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

data class RemoteWipeConfig(
    /** Desativado por padrão. */
    val enabled: Boolean = false,
    /** Mesmo com comando válido, exigir confirmação administrativa local antes de executar WIPE_DEVICE. */
    val requireAdminConfirmation: Boolean = true,
)

class RemoteWipeConfigStore(private val kv: SecureKv) {
    fun load() = RemoteWipeConfig(
        enabled = kv.getBoolean("rw.enabled", false),
        requireAdminConfirmation = kv.getBoolean("rw.confirm", true),
    )
    fun save(c: RemoteWipeConfig) { kv.putBoolean("rw.enabled", c.enabled); kv.putBoolean("rw.confirm", c.requireAdminConfirmation) }
}

/** Identidade local do dispositivo para o protocolo (UUID aleatório; não é IMEI/telefone). */
class DeviceIdentity(private val kv: SecureKv) {
    fun id(): String = kv.getString("rw.device_id") ?: UUID.randomUUID().toString().also { kv.putString("rw.device_id", it) }
    fun rotate(): String = UUID.randomUUID().toString().also { kv.putString("rw.device_id", it) }
}

/** Chaves públicas administrativas pinadas (SubjectPublicKeyInfo em base64) + lista de revogação. */
class TrustAnchorStore(private val kv: SecureKv) : TrustAnchors {
    private val prefix = "rw.key."
    private val revokedPrefix = "rw.revoked."

    fun add(keyId: String, spkiBase64: String): Boolean {
        val key = parse(spkiBase64) ?: return false
        require(key.algorithm == "EC") { "apenas chaves EC P-256" }
        kv.putString(prefix + keyId, spkiBase64)
        kv.remove(revokedPrefix + keyId)
        return true
    }

    fun revoke(keyId: String) { kv.putBoolean(revokedPrefix + keyId, true) }
    fun remove(keyId: String) { kv.remove(prefix + keyId); kv.remove(revokedPrefix + keyId) }
    fun keyIds(): List<String> = kv.keys().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }

    override fun publicKey(keyId: String): PublicKey? = kv.getString(prefix + keyId)?.let { parse(it) }
    override fun isRevoked(keyId: String): Boolean = kv.getBoolean(revokedPrefix + keyId, false)

    private fun parse(spkiBase64: String): PublicKey? = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(spkiBase64.trim(), Base64.DEFAULT)))
    }.getOrNull()
}

class PersistentReplayStore(private val kv: SecureKv) : ReplayStore {
    override fun lastSequence(keyId: String): Long? = kv.getLong("rw.seq.$keyId")
    override fun wasSeen(commandId: String): Boolean = kv.contains("rw.seen.$commandId")
    override fun remember(keyId: String, sequence: Long, commandId: String) {
        val last = lastSequence(keyId)
        if (last == null || sequence > last) kv.putLong("rw.seq.$keyId", sequence)
        kv.putBoolean("rw.seen.$commandId", true)
    }
}

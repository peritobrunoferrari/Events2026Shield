package com.shieldpericias.sentinel.securestorage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chaves simétricas no Android Keystore (hardware-backed quando disponível).
 * Nenhuma chave sai do Keystore; segredos são cifrados com AES-256-GCM.
 */
class KeystoreCrypto(private val masterAlias: String = MASTER_ALIAS, private val macAlias: String = MAC_ALIAS) {

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val MASTER_ALIAS = "sentinel.master.v1"
        const val MAC_ALIAS = "sentinel.mac.v1"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    @Synchronized
    private fun masterKey(): SecretKey {
        (keyStore.getKey(masterAlias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(masterAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    @Synchronized
    private fun macKey(): SecretKey {
        (keyStore.getKey(macAlias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(macAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER).apply { init(spec) }.generateKey()
    }

    /** Retorna iv(12) || texto cifrado+tag. */
    fun encrypt(plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        aad?.let { cipher.updateAAD(it) }
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES)
        val ct = cipher.doFinal(plain)
        return iv + ct
    }

    fun decrypt(blob: ByteArray, aad: ByteArray? = null): ByteArray {
        require(blob.size > GCM_IV_BYTES) { "blob too short" }
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val ct = blob.copyOfRange(GCM_IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        aad?.let { cipher.updateAAD(it) }
        return cipher.doFinal(ct)
    }

    /** HMAC-SHA256 com chave do Keystore — integridade de dados em Device Protected Storage. */
    fun mac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(macKey()) }.doFinal(data)

    fun macMatches(data: ByteArray, expected: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(mac(data), expected)

    /** Indica se a chave mestra está em hardware seguro (StrongBox/TEE). Apenas informativo. */
    fun masterKeySecurityLevel(): String = try {
        val key = masterKey()
        val factory = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
        val info = factory.getKeySpec(key, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "UNKNOWN_SECURE"
            else -> "UNKNOWN"
        }
    } catch (e: Exception) {
        "ERROR"
    }
}

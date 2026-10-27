package com.shieldpericias.sentinel.securestorage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

/**
 * Armazenamento chave-valor em Credential Encrypted storage (disponível só após
 * o primeiro desbloqueio) com valores cifrados por AES-GCM via Keystore.
 * Use para segredos: registros de PIN, chaves públicas administrativas, configs sensíveis.
 */
class SecureKv(context: Context, private val crypto: KeystoreCrypto, name: String = "sentinel_secure") {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun putBytes(key: String, value: ByteArray) {
        val blob = crypto.encrypt(value, key.toByteArray())
        prefs.edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun getBytes(key: String): ByteArray? {
        val b64 = prefs.getString(key, null) ?: return null
        return try {
            crypto.decrypt(Base64.decode(b64, Base64.NO_WRAP), key.toByteArray())
        } catch (e: Exception) {
            null
        }
    }

    fun putString(key: String, value: String) = putBytes(key, value.toByteArray(Charsets.UTF_8))
    fun getString(key: String): String? = getBytes(key)?.toString(Charsets.UTF_8)
    fun putLong(key: String, value: Long) = putString(key, value.toString())
    fun getLong(key: String): Long? = getString(key)?.toLongOrNull()
    fun putBoolean(key: String, value: Boolean) = putString(key, if (value) "1" else "0")
    fun getBoolean(key: String, default: Boolean = false): Boolean = getString(key)?.let { it == "1" } ?: default
    fun contains(key: String) = prefs.contains(key)
    fun remove(key: String) = prefs.edit().remove(key).apply()
    fun keys(): Set<String> = prefs.all.keys
}
